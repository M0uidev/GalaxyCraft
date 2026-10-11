'use strict';
// Writes game.json, the list of what a game release is made of, for the launchers out there:
// the files of this release (module, Dolphin per system, the mod) with their SHA-256, Fabric
// API from Fabric's Maven, and the Minecraft and Fabric versions from fabric/gradle.properties.
// Run by CI for a release, on the folder of files it attaches.
//   node scripts/game-manifest.js --version 0.2.0 --dir out --base https://github.com/.../releases/download/v0.2.0
// With --local instead of --base, the files are named as they sit next to game.json, for a
// launcher started with GXL_GAME_MANIFEST=<that game.json> (CI's Windows test build).
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const mc = require('../src/core/minecraft');
const gamepack = require('../src/core/gamepack');

const args = process.argv.slice(2);
const opt = (n) => { const i = args.indexOf(n); return i >= 0 ? args[i + 1] : null; };
const version = opt('--version');
const dir = opt('--dir');
const local = args.includes('--local');
const base = (opt('--base') || '').replace(/\/$/, '');
const die = (m) => { console.error(`game-manifest: ${m}`); process.exit(1); };

const sha256 = (b) => crypto.createHash('sha256').update(b).digest('hex');
function entry(file) {
  const p = path.join(dir, file);
  if (!fs.existsSync(p)) return null;
  const b = fs.readFileSync(p);
  return { file, url: local ? file : `${base}/${encodeURIComponent(file)}`, sha256: sha256(b), size: b.length };
}

async function main() {
  if (!version || !dir || (!base && !local)) die('--version, --dir and --base (or --local) are needed');
  const props = Object.fromEntries(fs.readFileSync(path.join(__dirname, '..', '..', 'fabric', 'gradle.properties'), 'utf8')
    .split('\n').map((l) => l.split('=')).filter((kv) => kv.length === 2).map(([k, v]) => [k.trim(), v.trim()]));

  const module = entry(`module-${version}.tar.gz`) || die(`no module-${version}.tar.gz`);
  const mod = entry(`galaxycraft-${version}.jar`) || die(`no galaxycraft-${version}.jar`);
  const dolphin = {};
  const linux = entry(`dolphin-linux-x64-${version}.tar.gz`);
  if (linux) dolphin['linux-x64'] = { ...linux, exe: 'usr/bin/dolphin-emu', tool: 'usr/bin/dolphin-tool' };
  const win = entry(`dolphin-win32-x64-${version}.tar.gz`);
  if (win) dolphin['win32-x64'] = { ...win, exe: 'Dolphin.exe', tool: 'DolphinTool.exe' };
  if (!Object.keys(dolphin).length) die('no Dolphin for any system');

  // Fabric API, from where Fabric publishes it.
  const api = `net.fabricmc.fabric-api:fabric-api:${props.fabric_api_version}`;
  const url = `${mc.FABRIC_MAVEN}${mc.mavenPath(api)}`;
  const res = await fetch(url);
  if (!res.ok) die(`Fabric API: HTTP ${res.status} for ${url}`);
  const jar = Buffer.from(await res.arrayBuffer());
  const fabricApi = { file: path.posix.basename(mc.mavenPath(api)), url, sha256: sha256(jar), size: jar.length };

  const manifest = {
    format: gamepack.FORMAT,
    version,
    released: new Date().toISOString().slice(0, 10),
    minecraft: { version: props.minecraft_version, fabricLoader: props.loader_version },
    javaArgs: '-Xmx6G',
    gameId: 'SB4E01',
    module,
    mods: [mod, fabricApi],
    dolphin,
  };
  const check = local ? gamepack.localManifest(manifest, path.join(dir, 'game.json')) : gamepack.parseManifest(manifest);
  if (check instanceof Error) die(check.message);
  fs.writeFileSync(path.join(dir, 'game.json'), `${JSON.stringify(manifest, null, 2)}\n`);
  console.log(`game.json: ${version}, Minecraft ${manifest.minecraft.version}, Fabric ${manifest.minecraft.fabricLoader}, Dolphin for ${Object.keys(dolphin).join(', ')}`);
}

main().catch((e) => die(e.stack || e.message));
