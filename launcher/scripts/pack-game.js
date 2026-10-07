'use strict';
// Makes release/module, the part of a game release only the developer's machine can build: the
// SMG2 module (CodeWarrior and Kamek, see docs/PHASE3.md) and the disc patches, made from the
// developer's own disc. Nothing from the disc is kept: Steve's archives and GalaxyCraftSpace go
// out as GXD1 patches against the player's copy, and Steve is built with a blank skin (the mod
// sends the player's skin at runtime). Commit release/module afterwards; CI packs the rest.
//   npm run pack-game -- --game "/path/to/Super Mario Galaxy 2.rvz" [--skip-build]
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const zlib = require('node:zlib');
const disc = require('../src/core/disc');
const delta = require('../src/core/delta');
const { moduleHash, ROOT } = require('./module-hash');

const args = process.argv.slice(2);
const opt = (name) => { const i = args.indexOf(name); return i >= 0 ? args[i + 1] : null; };
const game = opt('--game') || process.env.GXC_GAME;
const OUT = path.join(ROOT, 'release', 'module');
const WIN = process.platform === 'win32';
// The patched DolphinTool (tools/hostexe.py looks in the same places) and Python.
const TOOL = WIN
  ? ['dolphin/build/Binaries/DolphinTool.exe', 'dolphin/build-win/Binaries/DolphinTool.exe', 'dist/dolphin-win32-x64/DolphinTool.exe']
    .map((p) => path.join(ROOT, p)).find((p) => fs.existsSync(p)) || path.join(ROOT, 'dolphin', 'build', 'Binaries', 'DolphinTool.exe')
  : path.join(ROOT, 'dolphin', 'build', 'Binaries', 'dolphin-tool');
const PYTHON = process.env.PYTHON || (WIN ? 'python' : 'python3');
const die = (m) => { console.error(`pack-game: ${m}`); process.exit(1); };

/** A 64x64 skin with nothing of Mojang's in it: plain gray-blue, PNG. */
function blankSkin() {
  const w = 64;
  const h = 64;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const o = y * (w * 4 + 1) + 1 + x * 4;
      raw[o] = 96; raw[o + 1] = 112; raw[o + 2] = 140; raw[o + 3] = 255;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'latin1'), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(zlib.crc32(td) >>> 0);
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4); ihdr[8] = 8; ihdr[9] = 6;
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', ihdr), chunk('IDAT', zlib.deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

function extract(rom, discPath, work) {
  const out = fs.mkdtempSync(path.join(work, 'x-'));
  execFileSync(TOOL, ['extract', '-i', rom, '-s', discPath, '-o', out, '-q'], { stdio: 'inherit' });
  const find = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name);
      if (e.isDirectory()) { const f = find(p); if (f) return f; } else if (e.name === path.posix.basename(discPath)) return p;
    }
    return null;
  };
  const f = find(out);
  if (!f) die(`${discPath} not found on the disc`);
  return fs.readFileSync(f);
}

function main() {
  if (!game) die('which disc? --game "/path/to/Super Mario Galaxy 2.rvz" (or GXC_GAME)');
  const id = disc.identify(game);
  if (!id.ok) die(id.reason);
  if (!fs.existsSync(TOOL)) die(`no ${TOOL}: run dolphin/build.sh first`);
  const env = { ...process.env, GXC_GAME: game };
  if (!args.includes('--skip-build')) execFileSync(PYTHON, [path.join(ROOT, 'syati', 'build.py')], { stdio: 'inherit', env });
  const build = path.join(ROOT, 'syati', 'build');
  for (const f of ['CustomCode/CustomCode_SB4E.bin', 'galaxycraft.xml']) if (!fs.existsSync(path.join(build, f))) die(`no syati/build/${f}`);

  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'pack-game-'));
  try {
    // The disc-made files again, with a blank skin, apart from the developer's own build.
    const skin = path.join(work, 'blank-skin.png');
    fs.writeFileSync(skin, blankSkin());
    const made = path.join(work, 'made');
    execFileSync(PYTHON, [path.join(ROOT, 'tools', 'steve', 'build.py'), '--skin', skin, '--out', made], { stdio: 'inherit', env });
    execFileSync(PYTHON, [path.join(ROOT, 'tools', 'space_galaxy.py'), '--out', made], { stdio: 'inherit', env });

    fs.rmSync(OUT, { recursive: true, force: true });
    fs.mkdirSync(path.join(OUT, 'CustomCode'), { recursive: true });
    fs.copyFileSync(path.join(build, 'CustomCode', 'CustomCode_SB4E.bin'), path.join(OUT, 'CustomCode', 'CustomCode_SB4E.bin'));
    fs.copyFileSync(path.join(build, 'galaxycraft.xml'), path.join(OUT, 'galaxycraft.xml'));
    fs.mkdirSync(path.join(OUT, 'dolphin-play'), { recursive: true });
    for (const f of fs.readdirSync(path.join(ROOT, 'tools', 'dolphin-play'))) {
      fs.copyFileSync(path.join(ROOT, 'tools', 'dolphin-play', f), path.join(OUT, 'dolphin-play', f));
    }

    const outputs = [];
    for (const name of fs.readdirSync(path.join(made, 'ObjectData')).filter((n) => n.endsWith('.arc')).sort()) {
      outputs.push({ out: `ObjectData/${name}`, disc: `ObjectData/${name}`, file: path.join(made, 'ObjectData', name) });
    }
    const space = path.join(made, 'StageData', 'GalaxyCraftSpace');
    for (const name of fs.readdirSync(space).filter((n) => n.endsWith('.arc')).sort()) {
      const part = name.replace(/^GalaxyCraftSpace/, '');
      outputs.push({ out: `StageData/GalaxyCraftSpace/${name}`, disc: `StageData/RedBlueExGalaxy/RedBlueExGalaxy${part}`,
        file: path.join(space, name) });
    }
    const xml = fs.readFileSync(path.join(OUT, 'galaxycraft.xml'), 'utf8');
    for (const o of outputs.filter((x) => x.out.startsWith('ObjectData/'))) {
      if (!xml.includes(`external="/${o.out}"`)) die(`galaxycraft.xml does not load ${o.out}: rebuild syati/build`);
    }

    const list = [];
    let total = 0;
    for (const o of outputs) {
      const raw = extract(game, o.disc, work);
      const source = disc.yaz0Decompress(raw);
      const target = fs.readFileSync(o.file);
      const patch = delta.encode(source, target);
      if (!delta.apply(source, patch).equals(target)) die(`patch for ${o.out} does not rebuild it`);
      const rel = `patches/${o.out}.gxd`;
      fs.mkdirSync(path.dirname(path.join(OUT, rel)), { recursive: true });
      fs.writeFileSync(path.join(OUT, rel), patch);
      list.push({ out: o.out, disc: o.disc, yaz0: raw.toString('latin1', 0, 4) === 'Yaz0', patch: rel });
      total += patch.length;
      console.log(`pack-game: ${o.out}: ${target.length} bytes from a ${patch.length}-byte patch`);
    }
    fs.writeFileSync(path.join(OUT, 'patches.json'), `${JSON.stringify(list, null, 2)}\n`);
    fs.writeFileSync(path.join(OUT, 'module.json'), `${JSON.stringify({
      sourceHash: moduleHash(), gameId: disc.WANTED_ID, patches: list.length, madeAt: new Date().toISOString(),
    }, null, 2)}\n`);
    console.log(`pack-game: release/module made (${list.length} patches, ${(total / 1024).toFixed(0)} KiB). Commit it: git add release/module`);
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
}

main();
