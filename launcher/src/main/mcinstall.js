'use strict';
// Gets Minecraft ready to run without Gradle: Mojang's version and Fabric's profile, the
// libraries, the assets and Mojang's Java runtime, all checked. Plain Node (no Electron), so CI
// runs it against the real servers. Once done, it works offline from what it kept.
const fs = require('node:fs');
const path = require('node:path');
const mc = require('../core/minecraft');
const { downloadAll, fetchJson } = require('../core/download');

function readJson(file) {
  try { return JSON.parse(fs.readFileSync(file, 'utf8')); } catch { return null; }
}
function writeJson(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(data));
}

/** JSON from the network, kept in `cache`; the kept copy when offline. */
async function cachedJson(url, cache, fetchFn) {
  try {
    const data = await fetchJson(url, fetchFn);
    writeJson(cache, data);
    return data;
  } catch (e) {
    const kept = readJson(cache);
    if (kept) return kept;
    throw new Error(`Could not reach ${new URL(url).host} and nothing is kept from before (${e.message})`);
  }
}

/**
 * dirs: { libraries, versions, assets, runtime, natives }. want: { version, fabricLoader }.
 * Returns { version (merged), java, dirs } once every file is there.
 * opts: { fetchFn, onProgress, assets (default true), verify ('full' | 'size'), log }
 */
async function ensureMinecraft(dirs, want, { fetchFn = globalThis.fetch, onProgress = () => {}, assets = true, verify = 'size',
  log = () => {}, platform = process.platform, arch = process.arch } = {}) {
  const os = mc.mojangOs(platform, arch);
  const phase = (label) => onProgress({ phase: 'minecraft', label, done: 0, total: 0, bytes: 0, totalBytes: 0 });

  phase('Minecraft\'s version list');
  const vanillaFile = path.join(dirs.versions, want.version, `${want.version}.json`);
  let vanilla = readJson(vanillaFile);
  if (!vanilla) {
    const list = await cachedJson(mc.MANIFEST_URL, path.join(dirs.versions, 'version_manifest_v2.json'), fetchFn);
    const entry = (list.versions || []).find((v) => v.id === want.version);
    if (!entry) throw new Error(`Minecraft ${want.version} is not in Mojang's version list`);
    await downloadAll([{ url: entry.url, path: vanillaFile, sha1: entry.sha1 }], { fetchFn });
    vanilla = readJson(vanillaFile);
  }

  phase('Fabric');
  const fabricId = `fabric-loader-${want.fabricLoader}-${want.version}`;
  const fabricFile = path.join(dirs.versions, fabricId, `${fabricId}.json`);
  const fabric = readJson(fabricFile) || await cachedJson(`${mc.FABRIC_META}/versions/loader/${encodeURIComponent(want.version)}/${encodeURIComponent(want.fabricLoader)}/profile/json`,
    fabricFile, fetchFn);
  const version = mc.merge(fabric, vanilla);

  phase('Minecraft\'s assets');
  const indexFile = path.join(dirs.assets, 'indexes', `${vanilla.assetIndex.id}.json`);
  await downloadAll([{ url: vanilla.assetIndex.url, path: indexFile, sha1: vanilla.assetIndex.sha1, size: vanilla.assetIndex.size }],
    { fetchFn, verify });
  const assetIndex = readJson(indexFile);

  phase('Java');
  const component = (vanilla.javaVersion && vanilla.javaVersion.component) || 'java-runtime-delta';
  const runtimeRoot = path.join(dirs.runtime, component);
  const runtimeIndex = path.join(dirs.runtime, `${component}.json`);
  let runtimeManifest = readJson(runtimeIndex);
  const java = mc.javaExecutable(runtimeRoot, platform);
  if (!runtimeManifest || !fs.existsSync(java)) {
    const all = await fetchJson(mc.RUNTIMES_URL, fetchFn);
    const pick = mc.pickRuntime(all, component, mc.runtimePlatform(platform, arch));
    if (!pick) throw new Error(`Mojang has no ${component} Java for this system`);
    runtimeManifest = await fetchJson(pick.manifest.url, fetchFn);
    writeJson(runtimeIndex, runtimeManifest);
  }
  const rt = mc.runtimeFiles(runtimeManifest, runtimeRoot);
  for (const d of rt.dirs) fs.mkdirSync(d, { recursive: true });

  const items = [...rt.files, ...mc.downloads(version, assetIndex, os, dirs, { assets })];
  log(`Minecraft ${want.version} with Fabric ${want.fabricLoader}: checking ${items.length} files`);
  const r = await downloadAll(items, {
    fetchFn, verify,
    onProgress: (p) => onProgress({ phase: 'minecraft', label: 'Minecraft', ...p }),
  });
  log(`Minecraft: ${r.downloaded} downloaded, ${r.skipped} already there`);
  if (platform !== 'win32') {
    for (const l of rt.links) {
      try { fs.symlinkSync(l.target, l.path); } catch (e) { if (e.code !== 'EEXIST') throw e; }
    }
  }
  const natives = path.join(dirs.natives, version.id);
  fs.mkdirSync(natives, { recursive: true });
  return { version, java, dirs: { ...dirs, natives } };
}

module.exports = { ensureMinecraft, cachedJson };
