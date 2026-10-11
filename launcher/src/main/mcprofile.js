'use strict';
// Registers the game in the Minecraft Launcher (profile + Fabric version) and writes play.json.
const fs = require('node:fs');
const path = require('node:path');
const gamepack = require('../core/gamepack');
const store = require('../core/store');
const ml = require('../core/mclauncher');
const { seed } = require('../core/seed');

const readJson = (f) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return null; } };

function writeAtomic(file, text) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, text);
  fs.renameSync(tmp, file);
}

/**
 * Makes the game playable from the Minecraft Launcher. Returns { ok, dir?, reason? }: no
 * .minecraft (the launcher never ran) is not an error, the Play tab says what to do.
 * installer: the Installer; iconFile: the PNG for the profile; minecraftDir: to override the search.
 */
function register({ installer, inst, paths, iconFile, minecraftDir, log = () => {} }) {
  const i = installer.installed();
  if (!i) return { ok: false, reason: 'not installed' };
  const sys = i.manifest.dolphin[installer.system];
  if (!sys) return { ok: false, reason: 'no Dolphin for this system' };
  const gameDir = store.gameDirOf(inst, paths);
  // play.json and Dolphin's folder do not depend on the Minecraft Launcher being there.
  // The game's settings as PLAY reads them (Picture Quality): '' if the game has not made its file.
  let settingsText = '';
  try { settingsText = fs.readFileSync(path.join(gameDir, 'config', 'galaxycraft.properties'), 'utf8'); } catch { /* none yet */ }
  const plan = gamepack.playerPlan({ paths, inst, lay: i.lay, dolphinExe: path.join(i.lay.dolphin, sys.exe),
    mcCommand: { cmd: '', args: [] }, env: {}, settingsText });
  seed(plan, log);
  installer.syncMods(i, gameDir);
  writeAtomic(path.join(paths.dataDir, 'play.json'), JSON.stringify(ml.playJson(plan), null, 2));

  const dir = minecraftDir || ml.findMinecraftDir({ platform: paths.platform, home: paths.home }, fs.existsSync);
  if (!dir) return { ok: false, reason: 'no .minecraft' };
  const versionId = ml.fabricVersionId(i.manifest.minecraft);
  // The Fabric version, from the copy the install kept; the launcher downloads the libraries itself.
  const profile = readJson(path.join(i.lay.mc.versions, versionId, `${versionId}.json`));
  if (!profile) return { ok: false, reason: 'no Fabric version' };
  const vdir = path.join(dir, 'versions', versionId);
  writeAtomic(path.join(vdir, `${versionId}.json`), JSON.stringify(profile, null, 2));
  const jar = path.join(vdir, `${versionId}.jar`);
  if (!fs.existsSync(jar)) fs.writeFileSync(jar, '');

  let icon = '';
  try { icon = `data:image/png;base64,${fs.readFileSync(iconFile).toString('base64')}`; } catch { /* the launcher's default icon */ }
  const file = path.join(dir, 'launcher_profiles.json');
  const entry = ml.profileEntry({ versionId, gameDir, javaArgs: gamepack.minecraftJvmArgs(i.manifest, inst).join(' '), icon });
  writeAtomic(file, JSON.stringify(ml.mergeProfiles(readJson(file), entry), null, 2));
  log(`Super Minecraft Galaxy is in the Minecraft Launcher (${dir})`);
  return { ok: true, dir };
}

module.exports = { register };
