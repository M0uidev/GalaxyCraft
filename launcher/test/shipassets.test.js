'use strict';
// The Toad ship's models: five arcs copied from the player's disc, decompressed if Yaz0.
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const shipassets = require('../src/core/shipassets');

test('copies the arcs the disc has and lists the ones it lacks', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ship-'));
  const r = await shipassets.install({
    dir,
    extract: async (out) => {
      fs.mkdirSync(path.join(out, 'ObjectData'), { recursive: true });
      fs.writeFileSync(path.join(out, 'ObjectData', 'MiniPlayerRocket.arc'), Buffer.from('RARC'));
    },
    yaz0: (b) => b,
  });
  assert.equal(r.copied, 1);
  assert.ok(fs.existsSync(path.join(dir, 'MiniPlayerRocket.arc')));
  assert.ok(r.missing.includes('MarioFaceShipJet.arc'));
});

test('decompresses Yaz0 arcs', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ship-'));
  await shipassets.install({
    dir,
    extract: async (out) => {
      fs.mkdirSync(path.join(out, 'ObjectData'), { recursive: true });
      fs.writeFileSync(path.join(out, 'ObjectData', 'SpaceShipStep.arc'), Buffer.from('Yaz0xxxx'));
    },
    yaz0: () => Buffer.from('RARCok'),
  });
  assert.equal(fs.readFileSync(path.join(dir, 'SpaceShipStep.arc')).toString(), 'RARCok');
});

test('a disc with none of the arcs installs nothing and does not throw', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ship-'));
  const r = await shipassets.install({ dir, extract: async () => {}, yaz0: (b) => b });
  assert.equal(r.copied, 0);
  assert.equal(r.missing.length, shipassets.ARCS.length);
});
