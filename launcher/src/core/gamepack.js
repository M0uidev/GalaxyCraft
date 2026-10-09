'use strict';
// The game for players: each GitHub Release carries game.json, which lists what a version is made
// of (the module, the patched Dolphin per system, the mod jars, Minecraft's and Fabric's
// versions). The launcher installs a version next to the previous one, makes the disc files from
// the player's own Super Mario Galaxy 2, and PLAY runs it with the player's Minecraft account.
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { pathFor } = require('./paths');
const { DOLPHIN_BATCH, HIDDEN_MATCH, SMG2_SAVE, MEM2_BYTES, splitArgs } = require('./launchplan');
const { gameDirOf } = require('./store');

const FORMAT = 1;
const LATEST_URL = 'https://github.com/M0uidev/GalaxyCraft/releases/latest/download/game.json';
const MANAGED = '.super-minecraft-galaxy.json';

const SYSTEMS = { 'linux-x64': 'Linux', 'win32-x64': 'Windows', 'darwin-arm64': 'macOS', 'darwin-x64': 'macOS' };
const systemKey = (platform = process.platform, arch = process.arch) => `${platform}-${arch}`;

// https, or this machine (tests serve a release locally); file: only for a game.json that is a
// file on this machine itself (localManifest).
const isFileOf = (f, allowFile = false) => f && typeof f === 'object' && typeof f.url === 'string'
  && (/^(https:\/\/|http:\/\/127\.0\.0\.1[:/])/.test(f.url) || (allowFile && /^file:\/\//.test(f.url)))
  && typeof f.sha256 === 'string' && /^[0-9a-f]{64}$/.test(f.sha256) && typeof f.file === 'string'
  && /^[\w.+-]+$/.test(f.file);

/**
 * A game.json read from this machine (GXL_GAME_MANIFEST=path, as CI's Windows test build is): its
 * files' urls may be names next to it, which become file: URLs. Then parseManifest.
 */
function localManifest(raw, manifestPath) {
  const dir = path.dirname(path.resolve(manifestPath));
  const local = (f) => (f && typeof f === 'object' && typeof f.url === 'string' && !/^[a-z]+:/i.test(f.url)
    ? { ...f, url: pathToFileURL(path.join(dir, f.url)).href } : f);
  const m = raw && typeof raw === 'object' ? raw : {};
  const dolphin = Object.fromEntries(Object.entries(m.dolphin || {}).map(([k, v]) => [k, local(v)]));
  return parseManifest({ ...m, module: local(m.module), mods: Array.isArray(m.mods) ? m.mods.map(local) : m.mods, dolphin },
    { allowFile: true });
}

/** A game.json made safe to act on, or an Error saying what is wrong. */
function parseManifest(raw, { allowFile = false } = {}) {
  const isFile = (f) => isFileOf(f, allowFile);
  const m = raw && typeof raw === 'object' ? raw : null;
  if (!m || m.format !== FORMAT) return new Error('This game release needs a newer launcher: update the launcher.');
  if (typeof m.version !== 'string' || !/^\d+\.\d+\.\d+$/.test(m.version)) return new Error('game.json: bad version');
  const mc = m.minecraft || {};
  if (typeof mc.version !== 'string' || typeof mc.fabricLoader !== 'string') return new Error('game.json: no Minecraft version');
  if (!isFile(m.module)) return new Error('game.json: no module');
  if (!Array.isArray(m.mods) || !m.mods.every(isFile)) return new Error('game.json: bad mods');
  const dolphin = {};
  for (const [k, v] of Object.entries(m.dolphin || {})) {
    if (isFile(v) && typeof v.exe === 'string' && typeof v.tool === 'string') dolphin[k] = v;
  }
  return {
    version: m.version,
    released: typeof m.released === 'string' ? m.released : '',
    notes: typeof m.notes === 'string' ? m.notes : '',
    minecraft: { version: mc.version, fabricLoader: mc.fabricLoader },
    javaArgs: typeof m.javaArgs === 'string' ? m.javaArgs : '-Xmx4G',
    module: m.module,
    mods: m.mods,
    dolphin,
    gameId: typeof m.gameId === 'string' ? m.gameId : 'SB4E01',
  };
}

/** Whether this system has a Dolphin in that release; if not, the name of the system for the message. */
function supported(manifest, key = systemKey()) {
  return { ok: !!manifest.dolphin[key], system: SYSTEMS[key] || key };
}

/** Compares x.y.z versions. */
function compareVersions(a, b) {
  const pa = String(a || '0.0.0').split('.').map(Number);
  const pb = String(b || '0.0.0').split('.').map(Number);
  for (let i = 0; i < 3; i++) if ((pa[i] || 0) !== (pb[i] || 0)) return (pa[i] || 0) - (pb[i] || 0);
  return 0;
}

/** Where a version lives. */
function layout(paths, version) {
  const p = paths.path;
  const root = p.join(paths.dataDir, 'game', version);
  return {
    root,
    downloads: p.join(root, 'downloads'),
    module: p.join(root, 'module'),
    dolphin: p.join(root, 'dolphin'),
    mods: p.join(root, 'mods'),
    descriptor: p.join(root, 'galaxycraft.json'),
    installed: p.join(paths.dataDir, 'game', 'installed.json'),
    mc: {
      libraries: p.join(paths.dataDir, 'minecraft-files', 'libraries'),
      versions: p.join(paths.dataDir, 'minecraft-files', 'versions'),
      assets: p.join(paths.dataDir, 'minecraft-files', 'assets'),
      runtime: p.join(paths.dataDir, 'minecraft-files', 'runtime'),
      natives: p.join(paths.dataDir, 'minecraft-files', 'natives'),
    },
  };
}

/** Dolphin's game mod descriptor: the player's disc, with the module's Riivolution patch. */
function descriptor(rom, moduleDir, platform = process.platform) {
  const p = pathFor(platform);
  return {
    type: 'dolphin-game-mod-descriptor',
    version: 1,
    'base-file': rom,
    'display-name': 'Super Mario Galaxy 2 (Super Minecraft Galaxy)',
    riivolution: {
      patches: [{
        xml: p.join(moduleDir, 'galaxycraft.xml'),
        root: moduleDir,
        options: [{ 'section-name': 'GalaxyCraft', 'option-name': 'GalaxyCraft', choice: 1 }],
      }],
    },
  };
}

/** The same descriptor for a developer's build (syati/build), with the chosen disc. */
function devDescriptor(rom, root, platform = process.platform) {
  return descriptor(rom, pathFor(platform).join(root, 'syati', 'build'), platform);
}

/**
 * PLAY for players: the installed Dolphin with the module and the player's disc, and Minecraft
 * started directly (Mojang's files, Fabric, the mods) with the player's account.
 *   mcCommand  { cmd, args } from minecraft.command()
 */
function playerPlan({ paths, inst, lay, dolphinExe, mcCommand, env = process.env }) {
  const p = paths.path;
  const gameDir = gameDirOf(inst, paths);
  const dolphinDir = paths.dolphinDir;
  const seed = {
    mkdirs: [gameDir, dolphinDir, p.join(gameDir, 'mods')],
    dolphinConfig: {
      dir: p.join(dolphinDir, 'Config'),
      templates: p.join(lay.module, 'dolphin-play'),
      fromUser: ['GFX.ini', 'Hotkeys.ini'].map((name) => ({ name, candidates: paths.dolphinConfigs.map((d) => p.join(d, name)) })),
    },
    smg2Save: { to: p.join(dolphinDir, ...SMG2_SAVE), candidates: paths.dolphinUsers.map((u) => p.join(u, ...SMG2_SAVE)) },
  };
  const dolphin = {
    name: 'Dolphin',
    cmd: dolphinExe,
    cwd: lay.dolphin,
    // X11 (XWayland on Wayland desktops): the GalaxyCraft input hooks XInput2.
    env: { ...env, GALAXYCRAFT: '1', GALAXYCRAFT_BOOT: 'space', ...(paths.platform === 'linux' ? { QT_QPA_PLATFORM: 'xcb' } : {}) },
    args: [
      '-u', dolphinDir, DOLPHIN_BATCH, '-e', lay.descriptor,
      '-C', 'Dolphin.Input.BackgroundInput=True', '-C', 'Dolphin.General.HotkeysRequireFocus=False',
      '-C', 'Dolphin.Core.RAMOverrideEnable=True', '-C', `Dolphin.Core.MEM2Size=${MEM2_BYTES}`,
      '-C', `Dolphin.Core.CPUThread=${inst.dualCore ? 'True' : 'False'}`, '-C', 'Dolphin.Interface.ConfirmStop=False',
      ...(inst.fullscreen ? ['-C', 'Dolphin.Display.Fullscreen=True'] : []),
      ...splitArgs(inst.dolphinArgs),
    ],
  };
  const minecraft = { name: 'Minecraft', cmd: mcCommand.cmd, args: mcCommand.args, cwd: gameDir, env: { ...env }, hideWindow: true };
  return { gameDir, dolphinDir, seed, processes: [dolphin, minecraft], stopMatch: HIDDEN_MATCH };
}

/**
 * PLAY with Minecraft from the Minecraft Launcher: nothing started here. The player presses Play
 * there, the mod starts Dolphin (play.json) and closes it with Minecraft; the plan only watches
 * for that Minecraft, and STOP or the game's end stops it (Dolphin goes with it).
 */
function minecraftLauncherPlan() {
  return { seed: null, processes: [], stopMatch: HIDDEN_MATCH, watch: { name: 'Minecraft', match: HIDDEN_MATCH } };
}

/** The JVM arguments for Minecraft in the game: the installation's memory, hidden window. */
function minecraftJvmArgs(manifest, inst) {
  const own = splitArgs(inst.javaArgs || '');
  const base = own.length ? own : splitArgs(manifest.javaArgs);
  return [...base, '-Dgalaxycraft.hidden=true'];
}

/** Which mod files to put in the game's mods folder, and which old ones of ours to take out. */
function modChanges(manifest, previouslyManaged = []) {
  const want = manifest.mods.map((m) => m.file);
  return { add: want, remove: previouslyManaged.filter((f) => !want.includes(f)) };
}

module.exports = {
  FORMAT, LATEST_URL, MANAGED, SYSTEMS, systemKey, parseManifest, localManifest, supported, compareVersions, layout,
  descriptor, devDescriptor, playerPlan, minecraftLauncherPlan, minecraftJvmArgs, modChanges,
};
