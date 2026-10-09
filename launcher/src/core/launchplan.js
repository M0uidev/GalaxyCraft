'use strict';
// What PLAY does, as data: the port of tools/gxplay.sh. The runner (src/main/runner.js) carries
// the plan out; building it touches nothing, so every system's plan is unit tested.
const { pathFor, modulePatch } = require('./paths');
const { gameDirOf } = require('./store');

/** The Java processes of Minecraft run hidden for the game say this in their command line. */
const HIDDEN_MATCH = 'galaxycraft.hidden=true';
/** Dolphin's batch mode: the game's window only, without Dolphin's own main window (game list). */
const DOLPHIN_BATCH = '-b';
/** SMG2's (USA) save in a Dolphin user folder. */
const SMG2_SAVE = ['Wii', 'title', '00010000', '53423445'];
/** The game needs 256 MiB of MEM2 (Dolphin's RAM override): the module keeps planets there. */
const MEM2_BYTES = 268435456;

/**
 * Smoother video than Dolphin's own defaults (the native 640x528, no anti-aliasing, which makes
 * far planets and block edges jagged): 3x internal resolution, 4x MSAA, 8x anisotropic filtering.
 * Each one only if the player's GFX.ini does not already say its own value.
 */
const VIDEO_DEFAULTS = [
  ['Settings', 'InternalResolution', 3],
  ['Settings', 'MSAA', 4],
  ['Enhancements', 'MaxAnisotropy', 3],
];

/** The -C arguments for the video defaults that the GFX.ini text (or '' if none) leaves unset. */
function videoArgs(gfxIni = '') {
  const args = [];
  for (const [section, key, value] of VIDEO_DEFAULTS) {
    if (!new RegExp(`^\\s*${key}\\s*=`, 'm').test(gfxIni)) args.push('-C', `Graphics.${section}.${key}=${value}`);
  }
  return args;
}

/** Splits a command line typed by the player into arguments ("quoted parts" kept whole). */
function splitArgs(text) {
  const out = [];
  const re = /"((?:[^"\\]|\\.)*)"|'([^']*)'|(\S+)/g;
  let m;
  while ((m = re.exec(String(text || '')))) {
    if (m[1] !== undefined) out.push(m[1].replace(/\\(.)/g, '$1'));
    else if (m[2] !== undefined) out.push(m[2]);
    else out.push(m[3]);
  }
  return out;
}

/**
 * The plan for an installation.
 *   root       the GalaxyCraft checkout
 *   paths      platformPaths() of this system
 *   inst       the installation
 *   javaHome   the Java 25 to run Gradle (and so Minecraft) with
 *   dolphinBin the patched Dolphin's binary
 *   env        the launcher's environment (copied into both processes)
 */
function buildPlan({ root, paths, inst, javaHome, dolphinBin, descriptor = null, env = process.env, gfxIni = null }) {
  const p = pathFor(paths.platform);
  const win = paths.platform === 'win32';
  const gameDir = gameDirOf(inst, paths);
  const dolphinDir = paths.dolphinDir;

  const seed = {
    mkdirs: [gameDir, dolphinDir],
    // Dolphin's own folder, seeded once: an emulated Wii Remote + Nunchuk only the game drives,
    // with the player's own video settings and hotkeys if they have a Dolphin.
    dolphinConfig: {
      dir: p.join(dolphinDir, 'Config'),
      templates: p.join(root, 'tools', 'dolphin-play'),
      fromUser: ['GFX.ini', 'Hotkeys.ini'].map((name) => ({
        name,
        candidates: paths.dolphinConfigs.map((d) => p.join(d, name)),
      })),
    },
    // SMG2's save from the player's usual Dolphin while this folder has none (without one the
    // game makes a file by itself).
    smg2Save: {
      to: p.join(dolphinDir, ...SMG2_SAVE),
      candidates: paths.dolphinUsers.map((u) => p.join(u, ...SMG2_SAVE)),
    },
  };

  const dolphin = {
    name: 'Dolphin',
    cmd: dolphinBin,
    cwd: root,
    env: { ...env, GALAXYCRAFT: '1', GALAXYCRAFT_BOOT: 'space' },
    args: [
      '-u', dolphinDir,
      DOLPHIN_BATCH,
      '-e', descriptor || modulePatch(root, paths.platform), // the chosen disc's descriptor, else the build's
      // Background input and hotkeys without focus, for this run only (-C is not saved).
      '-C', 'Dolphin.Input.BackgroundInput=True',
      '-C', 'Dolphin.General.HotkeysRequireFocus=False',
      '-C', 'Dolphin.Core.RAMOverrideEnable=True',
      '-C', `Dolphin.Core.MEM2Size=${MEM2_BYTES}`,
      // Dual core: the CPU and the GPU on their own threads, headroom on planets.
      '-C', `Dolphin.Core.CPUThread=${inst.dualCore ? 'True' : 'False'}`,
      '-C', 'Dolphin.Interface.ConfirmStop=False',
      ...(gfxIni === null ? [] : videoArgs(gfxIni)), // null: the caller did not look
      ...(inst.fullscreen ? ['-C', 'Dolphin.Display.Fullscreen=True'] : []),
      ...splitArgs(inst.dolphinArgs),
    ],
  };

  // Minecraft through Gradle's wrapper jar, run by Java itself as gradlew/gradlew.bat would
  // (no shell script, so the same on both systems).
  const java = p.join(javaHome, 'bin', win ? 'java.exe' : 'java');
  const fabric = p.join(root, 'fabric');
  const minecraft = {
    name: 'Minecraft',
    cmd: java,
    hideWindow: true, // no console window on Windows (Minecraft's own window is hidden too)
    cwd: fabric,
    env: { ...env, JAVA_HOME: javaHome },
    args: [
      '-Xmx64m', '-Xms64m', '-Dorg.gradle.appname=gradlew',
      '-jar', p.join(fabric, 'gradle', 'wrapper', 'gradle-wrapper.jar'),
      'runClient', '-PgalaxycraftHidden', `-PgalaxycraftGameDir=${gameDir}`,
      '--console=plain', '-q',
      ...splitArgs(inst.gradleArgs),
    ],
  };

  return { gameDir, dolphinDir, seed, processes: [dolphin, minecraft], stopMatch: HIDDEN_MATCH };
}

/** The plan as the shell lines a player could type to do the same (shown in the log). */
function describe(plan, platform = process.platform) {
  const q = platform === 'win32'
    ? (a) => (/[\s"]/.test(a) || a === '' ? `"${a.replace(/"/g, '\\"')}"` : a)
    : (a) => (/[\s"'$`\\]/.test(a) || a === '' ? `'${a.replace(/'/g, "'\\''")}'` : a);
  // Secrets never reach the log.
  const hide = (args) => args.map((a, i) => (/^--(accessToken|session)$/.test(args[i - 1] || '') ? '********' : a));
  return plan.processes.map((proc) => `[${proc.name}] ${[proc.cmd, ...hide(proc.args)].map(q).join(' ')}`);
}

module.exports = { DOLPHIN_BATCH, HIDDEN_MATCH, SMG2_SAVE, MEM2_BYTES, splitArgs, videoArgs, buildPlan, describe };
