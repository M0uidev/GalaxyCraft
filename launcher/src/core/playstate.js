'use strict';
// What the big button does for a player (the released game, not a game folder): sign in,
// choose the game, install, update, or play, with the reason when it cannot. With Minecraft from
// the official Minecraft Launcher, the player signs in there, not here.
const { compareVersions, supported } = require('./gamepack');

/**
 *   account    the signed-in account (public part) or null
 *   signInReady whether this launcher can sign in (an approved client id)
 *   rom        { path, check } of the chosen Super Mario Galaxy 2, or null
 *   installed  { version, manifest } or null
 *   latest     the newest game.json (parsed), an Error (offline), or null (not checked yet)
 *   system     this system's key (linux-x64, win32-x64)
 *   official   Minecraft comes from the Minecraft Launcher: no account needed here
 * Returns { action, label, detail, version? } with action one of
 *   signin | rom | install | update | play | unsupported | unavailable | checking
 */
function playerState({ account, signInReady, rom, installed, latest, system, official = false }) {
  const latestOk = latest && !(latest instanceof Error) ? latest : null;
  const manifest = installed ? installed.manifest : latestOk;
  if (!installed && !latest) return { action: 'checking', label: 'PLAY', detail: 'Looking for the game...' };
  if (!manifest) {
    return { action: 'unavailable', label: 'PLAY', detail: `${latest.message}. Connect to the internet to install the game.` };
  }
  const sup = supported(latestOk || manifest, system);
  if (!sup.ok && !(installed && supported(installed.manifest, system).ok)) {
    return { action: 'unsupported', label: 'COMING SOON', detail: `Super Minecraft Galaxy is not out for ${sup.system} yet: it is coming. Follow the News.` };
  }
  // Without an account here: with the Minecraft Launcher, PLAY goes on; else the player still
  // chooses the game and installs (then plays from the Minecraft Launcher themselves).
  if (!account && !official && (signInReady || installed)) {
    return { action: 'signin', label: 'SIGN IN', detail: signInReady ? 'Sign in with the Microsoft account that owns Minecraft: Java Edition.'
      : 'Microsoft sign-in is not set up in this launcher yet.' };
  }
  if (!rom || !rom.path) return { action: 'rom', label: 'CHOOSE GAME', detail: 'Choose your Super Mario Galaxy 2 (USA) game file: .iso, .rvz or .wbfs.' };
  if (!rom.check || !rom.check.ok) return { action: 'rom', label: 'CHOOSE GAME', detail: (rom.check && rom.check.reason) || 'Choose your Super Mario Galaxy 2.' };
  if (!installed) return { action: 'install', label: 'INSTALL', detail: `Super Minecraft Galaxy ${latestOk.version}: ready to install.`, version: latestOk.version };
  if (latestOk && compareVersions(latestOk.version, installed.version) > 0 && supported(latestOk, system).ok) {
    return { action: 'update', label: 'UPDATE', detail: `Version ${latestOk.version} is out (you have ${installed.version}).`, version: latestOk.version };
  }
  return { action: 'play', label: 'PLAY', detail: `Super Minecraft Galaxy ${installed.version}${official ? ' · Minecraft from the Minecraft Launcher' : ''}`,
    version: installed.version };
}

module.exports = { playerState };
