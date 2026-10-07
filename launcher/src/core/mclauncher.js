'use strict';
// Super Minecraft Galaxy as an installation in Mojang's own Minecraft Launcher: the profile in
// its launcher_profiles.json, and play.json, which tells the mod how to start Dolphin. Pure: the
// files are written by src/main/mcprofile.js.
const { pathFor } = require('./paths');

const PROFILE_ID = 'super-minecraft-galaxy';
const PROFILE_NAME = 'Super Minecraft Galaxy';
const PLAY_FORMAT = 1;

/** Where the Minecraft Launcher keeps its files on this system, most likely first. */
function minecraftDirCandidates({ platform = process.platform, env = process.env, home } = {}) {
  const p = pathFor(platform);
  home = home || env.USERPROFILE || env.HOME || '';
  if (platform === 'win32') return [p.join(env.APPDATA || p.join(home, 'AppData', 'Roaming'), '.minecraft')];
  if (platform === 'darwin') return [p.join(home, 'Library', 'Application Support', 'minecraft')];
  return [p.join(home, '.minecraft'), p.join(home, '.var', 'app', 'com.mojang.Minecraft', '.minecraft')];
}

/** The first folder that holds a launcher_profiles.json (that is, the launcher has run), or null. */
function findMinecraftDir(opts, exists) {
  const p = pathFor(opts.platform || process.platform);
  return minecraftDirCandidates(opts).find((d) => exists(p.join(d, 'launcher_profiles.json'))) || null;
}

/** The Fabric version's id, as Fabric's own installer names it. */
const fabricVersionId = (mc) => `fabric-loader-${mc.fabricLoader}-${mc.version}`;

/** Our profile for launcher_profiles.json. icon: a data:image/png;base64, URI. */
function profileEntry({ versionId, gameDir, javaArgs, icon, now = new Date().toISOString() }) {
  return {
    name: PROFILE_NAME,
    type: 'custom',
    created: now,
    lastUsed: now,
    icon,
    lastVersionId: versionId,
    gameDir,
    javaArgs: `${javaArgs} -Dgalaxycraft.startDolphin=true`.trim(),
  };
}

/** launcher_profiles.json with our profile added or updated; everything else is left as it is. */
function mergeProfiles(existing, entry) {
  const doc = existing && typeof existing === 'object' && !Array.isArray(existing) ? existing : {};
  const profiles = doc.profiles && typeof doc.profiles === 'object' ? doc.profiles : {};
  const old = profiles[PROFILE_ID];
  const created = old && typeof old.created === 'string' ? old.created : entry.created;
  return { ...doc, profiles: { ...profiles, [PROFILE_ID]: { ...(old || {}), ...entry, created } } };
}

/** play.json: the Dolphin process of a player's plan, and nothing else (env: only what PLAY adds). */
function playJson(plan, baseEnv = {}) {
  const d = plan.processes.find((x) => x.name === 'Dolphin');
  const env = {};
  for (const [k, v] of Object.entries(d.env || {})) if (baseEnv[k] !== v) env[k] = v;
  return { format: PLAY_FORMAT, cmd: d.cmd, args: d.args, cwd: d.cwd, env };
}

module.exports = {
  PROFILE_ID, PROFILE_NAME, PLAY_FORMAT, minecraftDirCandidates, findMinecraftDir, fabricVersionId, profileEntry, mergeProfiles, playJson,
};
