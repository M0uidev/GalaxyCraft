'use strict';
// Minecraft as the official launcher runs it, without Gradle: Mojang's version files, their
// rules per system, the libraries, the assets, Mojang's own Java runtime, Fabric's loader on top,
// and the command line. Pure: what to download and what to run, as data.
const path = require('node:path');

const MANIFEST_URL = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json';
const RUNTIMES_URL = 'https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json';
const RESOURCES_URL = 'https://resources.download.minecraft.net';
const FABRIC_META = 'https://meta.fabricmc.net/v2';
const FABRIC_MAVEN = 'https://maven.fabricmc.net/';

/** This system in Mojang's words. */
function mojangOs(platform = process.platform, arch = process.arch) {
  return {
    name: platform === 'win32' ? 'windows' : platform === 'darwin' ? 'osx' : 'linux',
    arch: arch === 'x64' ? 'x86_64' : arch === 'ia32' ? 'x86' : arch,
  };
}

/** The key of Mojang's Java runtimes for this system. */
function runtimePlatform(platform = process.platform, arch = process.arch) {
  if (platform === 'win32') return arch === 'arm64' ? 'windows-arm64' : arch === 'ia32' ? 'windows-x86' : 'windows-x64';
  if (platform === 'darwin') return arch === 'arm64' ? 'mac-os-arm64' : 'mac-os';
  return arch === 'ia32' ? 'linux-i386' : 'linux';
}

function ruleMatches(rule, os, features) {
  if (rule.os) {
    if (rule.os.name && rule.os.name !== os.name) return false;
    if (rule.os.arch && rule.os.arch !== os.arch) return false;
    if (rule.os.version && os.version && !new RegExp(rule.os.version).test(os.version)) return false;
  }
  if (rule.features) {
    for (const [k, v] of Object.entries(rule.features)) if (!!features[k] !== v) return false;
  }
  return true;
}

/** Mojang's rules: none means allowed; otherwise the last rule that matches decides. */
function allowed(rules, os, features = {}) {
  if (!rules || !rules.length) return true;
  let ok = false;
  for (const r of rules) if (ruleMatches(r, os, features)) ok = r.action === 'allow';
  return ok;
}

/** group:artifact:version[:classifier][@ext] -> its path in a Maven repository. */
function mavenPath(coord) {
  const [main, ext = 'jar'] = coord.split('@');
  const [group, artifact, version, classifier] = main.split(':');
  const file = `${artifact}-${version}${classifier ? `-${classifier}` : ''}.${ext}`;
  return [...group.split('.'), artifact, version, file].join('/');
}

/** group:artifact[:classifier]: two libraries with the same key are the same library. */
function libraryKey(name) {
  const [group, artifact, , classifier] = name.split('@')[0].split(':');
  return [group, artifact, classifier || ''].join(':');
}

/** A Fabric profile (or any inheriting version) over its parent: the child's libraries win. */
function merge(child, parent) {
  if (!parent) return child;
  const libs = [];
  const seen = new Set();
  for (const lib of [...(child.libraries || []), ...(parent.libraries || [])]) {
    const key = libraryKey(lib.name);
    if (seen.has(key)) continue;
    seen.add(key);
    libs.push(lib);
  }
  return {
    ...parent,
    ...child,
    id: child.id,
    inheritsFrom: undefined,
    mainClass: child.mainClass || parent.mainClass,
    libraries: libs,
    arguments: {
      game: [...((parent.arguments || {}).game || []), ...((child.arguments || {}).game || [])],
      jvm: [...((parent.arguments || {}).jvm || []), ...((child.arguments || {}).jvm || [])],
    },
    downloads: parent.downloads,
    assetIndex: parent.assetIndex,
    assets: parent.assets,
    javaVersion: child.javaVersion || parent.javaVersion,
    logging: parent.logging,
    type: parent.type,
    clientVersion: parent.id,
  };
}

/**
 * The libraries this system needs: [{ name, path, url, sha1, size }], in classpath order. A
 * library without downloads (Fabric's) is fetched from its Maven repository.
 */
function libraries(version, os) {
  const out = [];
  for (const lib of version.libraries || []) {
    if (!allowed(lib.rules, os)) continue;
    const art = lib.downloads && lib.downloads.artifact;
    if (art) {
      out.push({ name: lib.name, path: art.path, url: art.url, sha1: art.sha1, size: art.size });
    } else if (!lib.downloads) {
      const p = mavenPath(lib.name);
      out.push({ name: lib.name, path: p, url: (lib.url || 'https://libraries.minecraft.net/').replace(/\/?$/, '/') + p,
        sha1: lib.sha1, size: lib.size });
    }
    // Old-style natives (a "natives" map with classifiers) are not used by the versions this
    // game runs: their LWJGL natives are plain libraries with rules, above.
  }
  return out;
}

/** Everything to download for a version (merged with Fabric's profile). dirs: { libraries, versions, assets }. */
function downloads(version, assetIndex, os, dirs, { assets = true } = {}) {
  const list = [];
  for (const lib of libraries(version, os)) {
    list.push({ url: lib.url, path: path.join(dirs.libraries, ...lib.path.split('/')), sha1: lib.sha1, size: lib.size });
  }
  const clientId = version.clientVersion || version.id;
  const client = version.downloads.client;
  list.push({ url: client.url, path: clientJar(dirs, clientId), sha1: client.sha1, size: client.size });
  if (version.logging && version.logging.client) {
    const f = version.logging.client.file;
    list.push({ url: f.url, path: path.join(dirs.assets, 'log_configs', f.id), sha1: f.sha1, size: f.size });
  }
  if (assets && assetIndex) {
    const seen = new Set();
    for (const { hash, size } of Object.values(assetIndex.objects || {})) {
      if (seen.has(hash)) continue;
      seen.add(hash);
      list.push({ url: `${RESOURCES_URL}/${hash.slice(0, 2)}/${hash}`, path: path.join(dirs.assets, 'objects', hash.slice(0, 2), hash),
        sha1: hash, size, quick: true });
    }
  }
  return list;
}

const clientJar = (dirs, id) => path.join(dirs.versions, id, `${id}.jar`);

/** The files of one of Mojang's Java runtimes: { files: [{path, url, sha1, size, executable}], dirs, links }. */
function runtimeFiles(manifest, root) {
  const files = [];
  const dirs = [];
  const links = [];
  for (const [rel, f] of Object.entries(manifest.files || {})) {
    const p = path.join(root, ...rel.split('/'));
    if (f.type === 'directory') dirs.push(p);
    else if (f.type === 'link') links.push({ path: p, target: f.target });
    else if (f.type === 'file' && f.downloads && f.downloads.raw) {
      files.push({ path: p, url: f.downloads.raw.url, sha1: f.downloads.raw.sha1, size: f.downloads.raw.size, executable: !!f.executable });
    }
  }
  return { files, dirs, links };
}

/** The runtime Mojang lists for a component on this system, or null. */
function pickRuntime(all, component, platformKey) {
  const list = ((all || {})[platformKey] || {})[component] || [];
  return list.length ? list[0] : null;
}

function javaExecutable(root, platform = process.platform) {
  // java.exe, not javaw.exe: its output reaches the launcher's log (no console window: windowsHide).
  return platform === 'win32' ? path.join(root, 'bin', 'java.exe') : path.join(root, 'bin', 'java');
}

/** ${name} placeholders filled in (unknown ones left as they are). */
function fill(arg, vars) {
  return arg.replace(/\$\{([a-zA-Z_]+)\}/g, (m, k) => (k in vars ? String(vars[k]) : m));
}

function expand(list, os, features, vars) {
  const out = [];
  for (const a of list || []) {
    if (typeof a === 'string') out.push(fill(a, vars));
    else if (allowed(a.rules, os, features)) for (const v of [].concat(a.value)) out.push(fill(v, vars));
  }
  return out;
}

/**
 * The command line that starts Minecraft.
 *   version   merged version (Fabric over Mojang's)
 *   dirs      { libraries, versions, assets, natives, game }
 *   auth      { name, uuid, accessToken, xuid, userType }
 *   java      the java executable
 *   jvmArgs   extra JVM arguments (memory, GalaxyCraft's -D flags)
 */
function command({ version, dirs, auth, java, jvmArgs = [], platform = process.platform, arch = process.arch,
  launcher = { name: 'super-minecraft-galaxy', version: '0' } }) {
  const os = mojangOs(platform, arch);
  const sep = platform === 'win32' ? ';' : ':';
  const classpath = [...libraries(version, os).map((l) => path.join(dirs.libraries, ...l.path.split('/'))),
    clientJar(dirs, version.clientVersion || version.id)];
  const vars = {
    auth_player_name: auth.name,
    version_name: version.id,
    game_directory: dirs.game,
    assets_root: dirs.assets,
    game_assets: dirs.assets,
    assets_index_name: version.assets || (version.assetIndex && version.assetIndex.id),
    auth_uuid: auth.uuid,
    auth_access_token: auth.accessToken,
    auth_session: auth.accessToken,
    clientid: auth.clientId || '',
    auth_xuid: auth.xuid || '0',
    user_type: auth.userType || 'msa',
    user_properties: '{}',
    version_type: version.type || 'release',
    natives_directory: dirs.natives,
    launcher_name: launcher.name,
    launcher_version: launcher.version,
    classpath: classpath.join(sep),
    classpath_separator: sep,
    library_directory: dirs.libraries,
  };
  const features = { is_demo_user: false, has_custom_resolution: false, has_quick_plays_support: false,
    is_quick_play_singleplayer: false, is_quick_play_multiplayer: false, is_quick_play_realms: false };
  const jvm = version.arguments ? expand(version.arguments.jvm, os, features, vars)
    : [`-Djava.library.path=${dirs.natives}`, '-cp', vars.classpath];
  const game = version.arguments ? expand(version.arguments.game, os, features, vars)
    : fill(version.minecraftArguments || '', vars).split(' ').filter(Boolean);
  const logging = [];
  if (version.logging && version.logging.client) {
    const c = version.logging.client;
    logging.push(fill(c.argument, { path: path.join(dirs.assets, 'log_configs', c.file.id) }));
  }
  // The player's arguments go before -cp so they apply to the JVM, not to Minecraft.
  return { cmd: java, args: [...jvmArgs, ...logging, ...jvm, version.mainClass, ...game] };
}

module.exports = {
  MANIFEST_URL, RUNTIMES_URL, FABRIC_META, FABRIC_MAVEN,
  mojangOs, runtimePlatform, allowed, mavenPath, libraryKey, merge, libraries, downloads, clientJar,
  runtimeFiles, pickRuntime, javaExecutable, fill, command,
};
