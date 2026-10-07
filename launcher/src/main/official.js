'use strict';
// Minecraft from the official Minecraft Launcher (src/core/official.js): Fabric's version for it
// without downloading Minecraft here, and opening and closing it.
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const official = require('../core/official');
const mc = require('../core/minecraft');
const gamepack = require('../core/gamepack');
const { fabricVersionId } = require('../core/mclauncher');
const { cachedJson } = require('./mcinstall');
const proc = require('./proc');

/**
 * Fabric's version for a release, kept where an install that downloads Minecraft keeps it (so
 * mcprofile.register finds it either way). From Fabric's servers once, then from the kept copy.
 */
async function fabricProfile({ manifest, paths, fetchFn }) {
  const id = fabricVersionId(manifest.minecraft);
  const file = path.join(gamepack.layout(paths, manifest.version).mc.versions, id, `${id}.json`);
  if (fs.existsSync(file)) return file;
  const want = manifest.minecraft;
  await cachedJson(`${mc.FABRIC_META}/versions/loader/${encodeURIComponent(want.version)}/${encodeURIComponent(want.fabricLoader)}/profile/json`,
    file, fetchFn);
  return file;
}

/** Opens the Minecraft Launcher, on its own (closing our game does not close it). Returns whether it was found. */
function open(env = process.env) {
  for (const c of official.launcherCommands({ env })) {
    if (!fs.existsSync(c.check)) continue;
    try {
      const child = spawn(c.cmd, c.args, { detached: true, stdio: 'ignore', windowsHide: false });
      child.on('error', () => {});
      child.unref();
      return true;
    } catch { /* the next one */ }
  }
  return false;
}

/** Closes the Minecraft Launcher (all of its windows and processes). Resolves to how many processes it had. */
function close(env = process.env) {
  return proc.stopPrograms(official.launcherProcessPatterns(process.platform, env));
}

module.exports = { fabricProfile, open, close };
