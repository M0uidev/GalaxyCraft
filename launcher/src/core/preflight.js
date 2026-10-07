'use strict';
// Before PLAY: everything the game needs, each missing piece with how to get it. PLAY is
// disabled with the first blocking reason instead of failing halfway.
const { pathFor, dolphinBinaryCandidates, modulePatch, findGameRoot } = require('./paths');
const { findJava, WANTED } = require('./java');

/**
 * fs: { exists(path), list(dir), read(path) }.
 * Returns { ok, root, dolphinBin, java, checks: [{ id, ok, blocking, label, detail, fix }] }.
 */
function preflight({ settings, inst, platform = process.platform, env = process.env, home, appDir }, fs) {
  const p = pathFor(platform);
  const win = platform === 'win32';
  const checks = [];
  const add = (c) => checks.push({ blocking: true, ...c });

  const root = findGameRoot({ chosen: settings.gameRoot, env, home, platform, appDir }, fs.exists);
  add({
    id: 'root', ok: !!root, label: 'Game folder',
    detail: root || (settings.gameRoot ? `Not a Super Minecraft Galaxy folder: ${settings.gameRoot}` : 'Not found'),
    fix: 'Choose the folder you cloned GalaxyCraft into, in Settings > Game folder.',
  });

  let dolphinBin = null;
  let java = null;
  if (root) {
    dolphinBin = dolphinBinaryCandidates(root, platform).find(fs.exists) || null;
    add({
      id: 'dolphin', ok: !!dolphinBin, label: 'Patched Dolphin',
      detail: dolphinBin || 'Not built',
      fix: win ? 'Build the patched Dolphin for Windows (see docs/WINDOWS.md).' : 'Run dolphin/build.sh in the game folder.',
    });

    const patch = modulePatch(root, platform);
    add({
      id: 'module', ok: fs.exists(patch), label: 'Game module (SMG2)',
      detail: fs.exists(patch) ? patch : 'Not built',
      fix: win ? 'Run python syati/build.py in the game folder (see docs/WINDOWS.md).' : 'Run syati/build.sh in the game folder.',
    });

    const jar = p.join(root, 'fabric', 'gradle', 'wrapper', 'gradle-wrapper.jar');
    add({
      id: 'gradle', ok: fs.exists(jar), label: 'Minecraft mod (Gradle)',
      detail: fs.exists(jar) ? 'fabric/' : 'fabric/gradle/wrapper/gradle-wrapper.jar is missing',
      fix: 'Get the whole repository again (git checkout -- fabric/gradle).',
    });

    const templates = p.join(root, 'tools', 'dolphin-play');
    add({
      id: 'controls', ok: fs.exists(templates), blocking: false, label: 'Controller profile',
      detail: fs.exists(templates) ? 'tools/dolphin-play' : 'tools/dolphin-play is missing',
      fix: 'Get the whole repository again.',
    });
  }

  java = findJava({ chosen: inst.javaHome, platform, env, home }, fs);
  add({
    id: 'java', ok: !!java && java.version === WANTED, label: `Java ${WANTED}`,
    blocking: !java,
    detail: java ? `${java.home} (Java ${java.version || '?'}, ${java.source})`
      : inst.javaHome ? `No Java in ${inst.javaHome}` : 'Not found',
    fix: `Install a JDK ${WANTED} (for example Eclipse Temurin), or choose one in the installation.`,
  });

  const blocking = checks.filter((c) => c.blocking && !c.ok);
  return { ok: blocking.length === 0, reason: blocking[0] || null, root, dolphinBin, java, checks };
}

module.exports = { preflight };
