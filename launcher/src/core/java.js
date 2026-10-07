'use strict';
// Finding a Java 25 for Gradle and Minecraft, as tools/gxplay.sh does on Linux
// ($HOME/.local/opt/jdk-25*), plus the usual install places on each system.
const { pathFor } = require('./paths');

const WANTED = 25;

/** The major version a JDK folder's name says (jdk-25.0.1+8, temurin-25, java-25-openjdk), or 0. */
function versionFromName(name) {
  const m = /(?:jdk|java|temurin|zulu|openjdk|jre)[-_]?(\d+)/i.exec(name) || /^(\d+)(?:[.\-+]|$)/.exec(name);
  return m ? Number(m[1]) : 0;
}

/** The major version in a JDK's `release` file text (JAVA_VERSION="25.0.1"), or 0. */
function versionFromRelease(text) {
  const m = /JAVA_VERSION="(?:1\.)?(\d+)/.exec(text || '');
  return m ? Number(m[1]) : 0;
}

/** Folders whose children may be JDKs. */
function jdkParents({ platform = process.platform, env = process.env, home } = {}) {
  const p = pathFor(platform);
  home = home || env.USERPROFILE || env.HOME || '';
  if (platform === 'win32') {
    const roots = [env.ProgramFiles || 'C:\\Program Files', env.LOCALAPPDATA && p.join(env.LOCALAPPDATA, 'Programs')]
      .filter(Boolean);
    const vendors = ['Java', 'Eclipse Adoptium', 'Microsoft', 'Zulu', 'Amazon Corretto', 'BellSoft', 'OpenJDK'];
    return [p.join(home, '.jdks'), ...roots.flatMap((r) => vendors.map((v) => p.join(r, v)))];
  }
  return [p.join(home, '.local', 'opt'), p.join(home, '.jdks'), p.join(home, '.sdkman', 'candidates', 'java'),
    '/usr/lib/jvm', '/opt'];
}

/**
 * The Java home to use: the installation's own choice, then $JAVA_HOME if it is a 25, then the
 * newest 25 found in the usual places. fs: { exists(path), list(dir) -> names, read(path) -> text }.
 * Returns { home, version, source } or null.
 */
function findJava({ chosen, platform = process.platform, env = process.env, home } = {}, fs) {
  const p = pathFor(platform);
  const javaBin = (h) => p.join(h, 'bin', platform === 'win32' ? 'java.exe' : 'java');
  const versionOf = (h) => {
    let v = 0;
    try { v = versionFromRelease(fs.read(p.join(h, 'release'))); } catch { /* no release file */ }
    return v || versionFromName(p.basename(h));
  };
  if (chosen) {
    return fs.exists(javaBin(chosen)) ? { home: chosen, version: versionOf(chosen), source: 'installation' } : null;
  }
  if (env.JAVA_HOME && fs.exists(javaBin(env.JAVA_HOME)) && versionOf(env.JAVA_HOME) === WANTED) {
    return { home: env.JAVA_HOME, version: WANTED, source: 'JAVA_HOME' };
  }
  const found = [];
  for (const parent of jdkParents({ platform, env, home })) {
    let names = [];
    try { names = fs.list(parent); } catch { continue; }
    for (const name of names.sort().reverse()) {
      if (versionFromName(name) !== WANTED) continue;
      const h = p.join(parent, name);
      if (fs.exists(javaBin(h))) found.push({ home: h, version: WANTED, source: 'found' });
    }
  }
  if (found.length) return found[0];
  // A JAVA_HOME of another version: Gradle says what it needs, better than nothing.
  if (env.JAVA_HOME && fs.exists(javaBin(env.JAVA_HOME))) {
    return { home: env.JAVA_HOME, version: versionOf(env.JAVA_HOME), source: 'JAVA_HOME' };
  }
  return null;
}

module.exports = { WANTED, versionFromName, versionFromRelease, jdkParents, findJava };
