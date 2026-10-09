'use strict';
// The soundtrack install: the songs a default catalog lists are copied from the player's disc
// (one extraction of AudioRes/Stream) into the shared soundtrack folder, and tracks.tsv keeps the
// player's choices while new songs from an update are added. The disc is a fake: a function that
// drops files into a folder.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const soundtrack = require('../src/core/soundtrack');

const HEADER = '# id\ttitle\tfile\tsource\tmood\ttags\tenabled\n';
const DEFAULTS = HEADER
  + 'galaxy02\tGalaxy 02\tSMG2_galaxy02_strm.ast\tsmg2\tspace\t\ttrue\n'
  + 'galaxy13\tGalaxy 13\tSMG2_galaxy13_strm.ast\tsmg2\tplanet\t\ttrue\n'
  + 'boss01a\tBoss 01 A\tSMG2_boss01a_strm.ast\tsmg2\t\t\ttrue\n';

function tmp() { return fs.mkdtempSync(path.join(os.tmpdir(), 'gxc-soundtrack-')); }

/** A fake disc: extract(out) writes these files (name -> content) into out/DATA/files/AudioRes/Stream. */
function fakeDisc(names) {
  const calls = { n: 0 };
  const extract = async (out) => {
    calls.n++;
    const d = path.join(out, 'DATA', 'files', 'AudioRes', 'Stream');
    fs.mkdirSync(d, { recursive: true });
    for (const n of names) fs.writeFileSync(path.join(d, n), `song ${n}`);
  };
  return { extract, calls };
}

const ALL = ['SMG2_galaxy02_strm.ast', 'SMG2_galaxy13_strm.ast', 'SMG2_boss01a_strm.ast', 'SMG2_other_strm.ast'];

test('copies the listed songs and writes the default catalog', async () => {
  const dir = tmp();
  const { extract, calls } = fakeDisc(ALL);
  const r = await soundtrack.install({ dir, defaults: DEFAULTS, extract });
  assert.equal(calls.n, 1);
  assert.deepEqual(fs.readdirSync(dir).sort(), ['SMG2_boss01a_strm.ast', 'SMG2_galaxy02_strm.ast', 'SMG2_galaxy13_strm.ast', 'tracks.tsv']);
  assert.equal(fs.readFileSync(path.join(dir, 'SMG2_galaxy02_strm.ast'), 'utf8'), 'song SMG2_galaxy02_strm.ast');
  assert.equal(fs.readFileSync(path.join(dir, 'tracks.tsv'), 'utf8'), DEFAULTS);
  assert.deepEqual(r, { copied: 3, missing: [] });
});

test('does not touch the disc when everything is there', async () => {
  const dir = tmp();
  await soundtrack.install({ dir, defaults: DEFAULTS, extract: fakeDisc(ALL).extract });
  const again = fakeDisc(ALL);
  const r = await soundtrack.install({ dir, defaults: DEFAULTS, extract: again.extract });
  assert.equal(again.calls.n, 0);
  assert.deepEqual(r, { copied: 0, missing: [] });
});

test('copies only the missing songs when some are there', async () => {
  const dir = tmp();
  fs.writeFileSync(path.join(dir, 'SMG2_galaxy02_strm.ast'), 'mine');
  const { extract, calls } = fakeDisc(ALL);
  const r = await soundtrack.install({ dir, defaults: DEFAULTS, extract });
  assert.equal(calls.n, 1);
  assert.equal(r.copied, 2);
  assert.equal(fs.readFileSync(path.join(dir, 'SMG2_galaxy02_strm.ast'), 'utf8'), 'mine', 'a song already there is left alone');
});

test('a song the disc does not have is reported and the rest still installed', async () => {
  const dir = tmp();
  const r = await soundtrack.install({ dir, defaults: DEFAULTS, extract: fakeDisc(['SMG2_galaxy02_strm.ast']).extract });
  assert.deepEqual(r.missing.sort(), ['SMG2_boss01a_strm.ast', 'SMG2_galaxy13_strm.ast']);
  assert.equal(r.copied, 1);
});

test('keeps the player\'s choices and adds the songs an update brings', async () => {
  const dir = tmp();
  const mine = HEADER
    + 'galaxy02\tMy Title\tSMG2_galaxy02_strm.ast\tsmg2\tplanet\t\tfalse\n'
    + 'minecraft1\tMC\tminecraft:x\tminecraft\tplanet\t\ttrue\n';
  fs.writeFileSync(path.join(dir, 'tracks.tsv'), mine);
  await soundtrack.install({ dir, defaults: DEFAULTS, extract: fakeDisc(ALL).extract });
  const lines = fs.readFileSync(path.join(dir, 'tracks.tsv'), 'utf8').split('\n').filter((l) => l && !l.startsWith('#'));
  assert.equal(lines[0], 'galaxy02\tMy Title\tSMG2_galaxy02_strm.ast\tsmg2\tplanet\t\tfalse', 'their row is kept as it is');
  assert.ok(lines.includes('minecraft1\tMC\tminecraft:x\tminecraft\tplanet\t\ttrue'));
  assert.ok(lines.some((l) => l.startsWith('galaxy13\t')) && lines.some((l) => l.startsWith('boss01a\t')));
  assert.equal(lines.filter((l) => l.startsWith('galaxy02\t')).length, 1, 'no duplicate of a song they have');
});

test('a catalog that is not text or has broken lines does not stop the install', async () => {
  const dir = tmp();
  fs.writeFileSync(path.join(dir, 'tracks.tsv'), 'garbage\nnot\ta\tcatalog\n');
  const r = await soundtrack.install({ dir, defaults: DEFAULTS, extract: fakeDisc(ALL).extract });
  assert.equal(r.copied, 3);
  assert.match(fs.readFileSync(path.join(dir, 'tracks.tsv'), 'utf8'), /galaxy13\t/);
});

test('defaults with no songs does nothing and never opens the disc', async () => {
  const dir = tmp();
  const d = fakeDisc(ALL);
  const r = await soundtrack.install({ dir, defaults: HEADER, extract: d.extract });
  assert.equal(d.calls.n, 0);
  assert.deepEqual(r, { copied: 0, missing: [] });
});
