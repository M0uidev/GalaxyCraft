'use strict';
// A whole install and update from a release served on this machine: the game's files checked
// and unpacked, a disc file rebuilt from a (fake) disc through its patch, the mods, the previous
// version kept. The fake dolphin-tool is a shell script, so this runs where there is a shell.
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const tar = require('tar');
const { Installer } = require('../src/main/installer');
const { platformPaths } = require('../src/core/paths');
const gamepack = require('../src/core/gamepack');
const delta = require('../src/core/delta');

const sha256 = (b) => crypto.createHash('sha256').update(b).digest('hex');

function isoHeader() {
  const h = Buffer.alloc(0x400);
  h.write('SB4E01', 0, 'latin1');
  h.writeUInt32BE(0x5d1c9ea3, 0x18);
  h.write('SUPER MARIO GALAXY 2', 0x20);
  return h;
}

async function release(dir, version, discArc, served) {
  // module: the Riivolution files and a patch that turns the disc's Mario.arc into ours.
  const mod = path.join(dir, `src-module-${version}`);
  fs.mkdirSync(path.join(mod, 'patches', 'ObjectData'), { recursive: true });
  fs.mkdirSync(path.join(mod, 'dolphin-play'), { recursive: true });
  fs.writeFileSync(path.join(mod, 'galaxycraft.xml'), '<wiidisc/>');
  fs.writeFileSync(path.join(mod, 'dolphin-play', 'Dolphin.ini'), '[Core]\n');
  const ours = Buffer.concat([discArc.subarray(0, 3000), Buffer.from(`steve ${version}`), discArc.subarray(3000)]);
  fs.writeFileSync(path.join(mod, 'patches', 'ObjectData', 'Mario.arc.gxd'), delta.encode(discArc, ours));
  fs.writeFileSync(path.join(mod, 'patches.json'), JSON.stringify([
    { out: 'ObjectData/Mario.arc', disc: 'ObjectData/Mario.arc', yaz0: false, patch: 'patches/ObjectData/Mario.arc.gxd' }]));
  const dol = path.join(dir, `src-dolphin-${version}`);
  fs.mkdirSync(dol, { recursive: true });
  fs.writeFileSync(path.join(dol, 'dolphin-emu'), '#!/bin/sh\necho dolphin\n');
  // dolphin-tool extract -i ROM -s PATH -o OUT -q: copies PATH from the fake disc folder next to the ROM.
  fs.writeFileSync(path.join(dol, 'dolphin-tool'), '#!/bin/sh\nmkdir -p "$7/DATA/files/$(dirname "$5")"\ncp "$3.files/$5" "$7/DATA/files/$5"\n');
  const files = {};
  const add = async (name, make) => { await make(path.join(dir, name)); files[name] = fs.readFileSync(path.join(dir, name)); };
  await add(`module-${version}.tar.gz`, (f) => tar.c({ gzip: true, file: f, cwd: mod }, ['.']));
  await add(`dolphin-linux-${version}.tar.gz`, (f) => tar.c({ gzip: true, file: f, cwd: dol }, ['.']));
  await add(`galaxycraft-${version}.jar`, (f) => fs.writeFileSync(f, `jar ${version}`));
  Object.assign(served, files);
  const entry = (name) => ({ file: name, url: `https://example.invalid/${name}`, sha256: sha256(files[name]), size: files[name].length });
  return { manifest: gamepack.parseManifest({
    format: 1, version, minecraft: { version: '26.3', fabricLoader: '0.19.5' },
    module: entry(`module-${version}.tar.gz`), mods: [entry(`galaxycraft-${version}.jar`)],
    dolphin: { 'linux-x64': { ...entry(`dolphin-linux-${version}.tar.gz`), exe: 'dolphin-emu', tool: 'dolphin-tool' } },
  }), ours };
}

test('install, play prep, update, keep the previous version', { skip: process.platform === 'win32', timeout: 60000 }, async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'gxl-inst-'));
  const served = {};
  const fetchFn = async (url) => {
    const body = served[url.split('/').pop()];
    return body ? new Response(body) : new Response('', { status: 404 });
  };
  const rom = path.join(dir, 'smg2.iso');
  fs.writeFileSync(rom, isoHeader());
  const discArc = crypto.randomBytes(20000);
  fs.mkdirSync(`${rom}.files/ObjectData`, { recursive: true });
  fs.writeFileSync(`${rom}.files/ObjectData/Mario.arc`, discArc);

  const paths = platformPaths({ platform: 'linux', env: { GXC_DATA_DIR: path.join(dir, 'data') }, home: dir });
  const mcCalls = [];
  const inst = new Installer({ paths, fetchFn, system: 'linux-x64', ensureMinecraftFn: async (...a) => { mcCalls.push(a); return {}; } });
  const phases = new Set();
  inst.on('progress', (p) => phases.add(p.phase));

  const r1 = await release(dir, '0.2.0', discArc, served);
  let i = await inst.install(r1.manifest, rom);
  assert.equal(i.version, '0.2.0');
  assert.deepEqual([...phases].sort(), ['disc', 'game']);
  assert.equal(mcCalls.length, 1);
  assert.deepEqual(fs.readFileSync(path.join(i.lay.module, 'ObjectData', 'Mario.arc')), r1.ours);
  const desc = JSON.parse(fs.readFileSync(i.lay.descriptor, 'utf8'));
  assert.equal(desc['base-file'], rom);
  assert.equal(desc.riivolution.patches[0].root, i.lay.module);
  assert.ok(fs.statSync(path.join(i.lay.dolphin, 'dolphin-emu')).mode & 0o100);
  assert.equal(fs.existsSync(i.lay.downloads), false);

  // The installation's mods folder: ours in, a player's own mod left alone.
  const gameDir = path.join(dir, 'game');
  fs.mkdirSync(path.join(gameDir, 'mods'), { recursive: true });
  fs.writeFileSync(path.join(gameDir, 'mods', 'sodium.jar'), 'mine');
  inst.syncMods(i, gameDir);
  assert.deepEqual(fs.readdirSync(path.join(gameDir, 'mods')).sort(), ['.super-minecraft-galaxy.json', 'galaxycraft-0.2.0.jar', 'sodium.jar']);

  // PLAY prep again: nothing to redo.
  const before = fs.statSync(path.join(i.lay.module, 'ObjectData', 'Mario.arc')).mtimeMs;
  await inst.prepare(rom);
  assert.equal(fs.statSync(path.join(i.lay.module, 'ObjectData', 'Mario.arc')).mtimeMs, before);

  // An update: the new version, the old one kept, our old jar replaced.
  const r2 = await release(dir, '0.3.0', discArc, served);
  i = await inst.install(r2.manifest, rom);
  assert.equal(i.version, '0.3.0');
  assert.equal(i.previous, '0.2.0');
  inst.syncMods(i, gameDir);
  assert.deepEqual(fs.readdirSync(path.join(gameDir, 'mods')).sort(), ['.super-minecraft-galaxy.json', 'galaxycraft-0.3.0.jar', 'sodium.jar']);
  assert.ok(fs.existsSync(path.join(dir, 'data', 'game', '0.2.0')));

  // A disc that is not the one the patches were made for.
  const r3 = await release(dir, '0.4.0', crypto.randomBytes(20000), served);
  await assert.rejects(inst.install(r3.manifest, rom), /not the one the patch expects/);
  assert.equal(inst.installed().version, '0.3.0');

  // A damaged download is caught.
  const r4 = await release(dir, '0.5.0', discArc, served);
  served['module-0.5.0.tar.gz'] = Buffer.from('broken');
  await assert.rejects(inst.install(r4.manifest, rom), /size|SHA-256/);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('game.json is checked before anything is done', () => {
  assert.match(gamepack.parseManifest({ format: 2 }).message, /newer launcher/);
  assert.match(gamepack.parseManifest({ format: 1, version: '1.0.0', minecraft: { version: '26.3', fabricLoader: '1' },
    module: { file: '../x', url: 'https://x', sha256: '0'.repeat(64) }, mods: [] }).message, /no module/);
  assert.equal(gamepack.compareVersions('0.10.0', '0.9.9') > 0, true);
});
