'use strict';
// Where things live on each system. Everything takes the platform, the environment and the home
// folder as arguments, so Windows' paths are tested on Linux (and the other way around).
const path = require('node:path');

function pathFor(platform) {
  return platform === 'win32' ? path.win32 : path.posix;
}

/**
 * The folders the launcher and the game use on this system.
 *   dataDir       GalaxyCraft's data: Minecraft's folder, Dolphin's folder, blueprints
 *                 (the same one tools/gxplay.sh uses on Linux, so worlds carry over)
 *   dolphinUsers  the player's usual Dolphin user folders, newest layout first: where their
 *                 Config/GFX.ini, Config/Hotkeys.ini and SMG2's save are
 *   dolphinConfigs the folders of the usual Dolphin's .ini files
 */
function platformPaths({ platform = process.platform, env = process.env, home } = {}) {
  const p = pathFor(platform);
  const isWindows = platform === 'win32';
  home = home || env.USERPROFILE || env.HOME || '';
  let dataDir;
  let dolphinUsers;
  let dolphinConfigs;
  if (isWindows) {
    const appData = env.APPDATA || p.join(home, 'AppData', 'Roaming');
    const documents = p.join(home, 'Documents');
    dataDir = p.join(appData, 'galaxycraft');
    dolphinUsers = [p.join(appData, 'Dolphin Emulator'), p.join(documents, 'Dolphin Emulator')];
    dolphinConfigs = dolphinUsers.map((u) => p.join(u, 'Config'));
  } else {
    const dataHome = env.XDG_DATA_HOME || p.join(home, '.local', 'share');
    const configHome = env.XDG_CONFIG_HOME || p.join(home, '.config');
    dataDir = p.join(dataHome, 'galaxycraft');
    dolphinUsers = [p.join(dataHome, 'dolphin-emu'), p.join(home, '.dolphin-emu')];
    dolphinConfigs = [p.join(configHome, 'dolphin-emu'), p.join(home, '.dolphin-emu', 'Config')];
  }
  // $GXC_DATA_DIR moves the game's data elsewhere (tests, a second copy of the game).
  if (env.GXC_DATA_DIR) dataDir = env.GXC_DATA_DIR;
  return {
    platform,
    isWindows,
    home,
    path: p,
    dataDir,
    defaultGameDir: p.join(dataDir, 'minecraft'),
    dolphinDir: p.join(dataDir, 'dolphin'),
    installationsDir: p.join(dataDir, 'installations'),
    dolphinUsers,
    dolphinConfigs,
  };
}

/** The patched Dolphin's binary, where each build leaves it (the first that exists is used). */
function dolphinBinaryCandidates(root, platform = process.platform) {
  const p = pathFor(platform);
  if (platform === 'win32') {
    return [
      p.join(root, 'dolphin', 'build', 'Binaries', 'Dolphin.exe'), // CMake + Ninja/MSVC
      p.join(root, 'dolphin', 'build', 'Binaries', 'x64', 'Dolphin.exe'),
      p.join(root, 'dolphin', 'build', 'Binaries', 'Release', 'Dolphin.exe'),
      p.join(root, 'dolphin', 'src', 'Binary', 'x64', 'Dolphin.exe'), // Dolphin's own .sln
    ];
  }
  return [p.join(root, 'dolphin', 'build', 'Binaries', 'dolphin-emu')];
}

/** Gradle's wrapper for this system. */
function gradleWrapper(root, platform = process.platform) {
  const p = pathFor(platform);
  return p.join(root, 'fabric', platform === 'win32' ? 'gradlew.bat' : 'gradlew');
}

/** The module's Riivolution patch, built by syati/build.sh. */
function modulePatch(root, platform = process.platform) {
  return pathFor(platform).join(root, 'syati', 'build', 'galaxycraft.json');
}

/** A folder is a GalaxyCraft checkout if it holds the shared protocol and the mod. */
function isGameRoot(dir, exists, platform = process.platform) {
  if (!dir) return false;
  const p = pathFor(platform);
  return exists(p.join(dir, 'protocol', 'galaxycraft_protocol.h'))
    && exists(p.join(dir, 'fabric', 'build.gradle'));
}

/**
 * Folders that may hold the game, most likely first: the one chosen in the settings, $GXC_ROOT,
 * the checkout this launcher runs from (development), then the usual clone places.
 */
function gameRootCandidates({ chosen, env = process.env, home, platform = process.platform, appDir } = {}) {
  const p = pathFor(platform);
  home = home || env.USERPROFILE || env.HOME || '';
  const out = [];
  if (chosen) out.push(chosen);
  if (env.GXC_ROOT) out.push(env.GXC_ROOT);
  if (appDir) out.push(p.resolve(appDir, '..'));
  for (const name of ['GalaxyCraft', 'galaxycraft']) {
    out.push(p.join(home, name));
    out.push(p.join(home, 'Documents', name));
    out.push(p.join(home, 'Projects', name));
    out.push(p.join(home, 'src', name));
    out.push(p.join(home, 'dev', name));
  }
  return [...new Set(out)];
}

function findGameRoot(opts, exists) {
  const platform = opts.platform || process.platform;
  return gameRootCandidates(opts).find((d) => isGameRoot(d, exists, platform)) || null;
}

module.exports = {
  pathFor,
  platformPaths,
  dolphinBinaryCandidates,
  gradleWrapper,
  modulePatch,
  isGameRoot,
  gameRootCandidates,
  findGameRoot,
};
