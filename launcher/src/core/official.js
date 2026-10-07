'use strict';
// PLAY with Minecraft from the official Minecraft Launcher: the player signs in there, not here.
// Our installation in it and play.json are src/core/mclauncher.js's (the mod starts Dolphin); this
// is the rest: which Minecraft PLAY uses, how to open the Minecraft Launcher, and how to find its
// processes to close it with the game. Pure, so every system is tested on any.
const { pathFor } = require('./paths');

/** The Minecraft Launcher from the Microsoft Store / Xbox app. */
const STORE_PACKAGE = 'Microsoft.4297127D64EC6_8wekyb3d8bbwe';

/**
 * Which Minecraft PLAY uses, from the setting: 'official' (the Minecraft Launcher) or 'launcher'
 * (this one, with the account signed in here). Automatic: this one only with an account in it.
 */
function minecraftSource(setting, account) {
  if (setting === 'official' || setting === 'launcher') return setting;
  return account ? 'launcher' : 'official';
}

/**
 * Ways to open the Minecraft Launcher, most likely first; each { cmd, args, check } where `check`
 * must exist for it to be tried. $GXL_MINECRAFT_LAUNCHER names it instead (installed elsewhere, tests).
 */
function launcherCommands({ platform = process.platform, env = process.env, home } = {}) {
  const p = pathFor(platform);
  home = home || env.USERPROFILE || env.HOME || '';
  if (env.GXL_MINECRAFT_LAUNCHER) return [{ cmd: env.GXL_MINECRAFT_LAUNCHER, args: [], check: env.GXL_MINECRAFT_LAUNCHER }];
  if (platform === 'win32') {
    const out = [];
    for (const pf of [env['ProgramFiles(x86)'], env.ProgramFiles, 'C:\\Program Files (x86)', 'C:\\Program Files']) {
      if (!pf) continue;
      const exe = p.join(pf, 'Minecraft Launcher', 'MinecraftLauncher.exe');
      out.push({ cmd: exe, args: [], check: exe });
    }
    const local = env.LOCALAPPDATA || p.join(home, 'AppData', 'Local');
    out.push({ cmd: 'explorer.exe', args: [`shell:AppsFolder\\${STORE_PACKAGE}!Minecraft`], check: p.join(local, 'Packages', STORE_PACKAGE) });
    return [...new Map(out.map((c) => [c.cmd + c.args.join(' '), c])).values()];
  }
  if (platform === 'darwin') {
    const app = '/Applications/Minecraft.app';
    return [{ cmd: 'open', args: [app], check: app }];
  }
  return ['/usr/bin/minecraft-launcher', '/usr/local/bin/minecraft-launcher', p.join(home, '.local', 'bin', 'minecraft-launcher'),
    '/opt/minecraft-launcher/minecraft-launcher']
    .map((exe) => ({ cmd: exe, args: [], check: exe }));
}

/**
 * How to recognize the Minecraft Launcher's own processes, to close it with the game. Windows: by
 * the program's path (the Microsoft Store's package, or the installer's MinecraftLauncher.exe;
 * never Bedrock, another package); elsewhere: by the command line (pgrep -f). With
 * $GXL_MINECRAFT_LAUNCHER, only that program.
 */
function launcherProcessPatterns(platform = process.platform, env = process.env) {
  if (env.GXL_MINECRAFT_LAUNCHER) return [env.GXL_MINECRAFT_LAUNCHER];
  if (platform === 'win32') return [`*\\WindowsApps\\${STORE_PACKAGE.split('_')[0]}_*`, '*\\MinecraftLauncher.exe'];
  if (platform === 'darwin') return ['Minecraft.app/Contents/MacOS/launcher'];
  return ['minecraft-launcher'];
}

module.exports = { STORE_PACKAGE, minecraftSource, launcherCommands, launcherProcessPatterns };
