'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const gs = require('../src/core/gamesettings');

test('values made valid as the game does', () => {
  const range = gs.SCHEMA.find((s) => s.key === 'blockDistance');
  assert.equal(gs.validate(range, '100'), 96);
  assert.equal(gs.validate(range, 999), 160);
  assert.equal(gs.validate(range, 'x'), 96);
  const movement = gs.SCHEMA.find((s) => s.key === 'movement');
  assert.equal(gs.validate(movement, 'MARIO_MC'), 'MARIO_MC');
  assert.equal(gs.validate(movement, 'LUIGI'), 'MARIO');
  const skin = gs.SCHEMA.find((s) => s.key === 'skin');
  assert.equal(gs.validate(skin, 'x'.repeat(40)).length, 16);
});

test('reading and writing keep keys the launcher does not know', () => {
  const old = '#c\nmovement=MINECRAFT\nfuture=42\n';
  assert.equal(gs.read(old).movement, 'MINECRAFT');
  assert.equal(gs.read('').particles, true);
  const text = gs.write(old, { skin: 'jeb_', particles: false });
  const back = gs.read(text);
  assert.equal(back.skin, 'jeb_');
  assert.equal(back.particles, false);
  assert.equal(back.movement, 'MINECRAFT');
  assert.match(text, /^future=42$/m);
});

// The launcher's list must match the game's: a setting renamed in GalaxyOptions.java shows here.
test('the schema matches GalaxyOptions.java', () => {
  const file = path.join(__dirname, '..', '..', 'fabric', 'src', 'client', 'java', 'dev', 'moui', 'galaxycraft',
    'client', 'GalaxyOptions.java');
  const src = fs.readFileSync(file, 'utf8');
  const found = [];
  const re = /new Setting\.(Choice|Text|Range|Toggle)(?:<[^>]*>)?\(\s*"(\w+)"([\s\S]*?)\)\);/g;
  let m;
  while ((m = re.exec(src))) {
    const [, kind, key, rest] = m;
    const entry = { key, type: kind.toLowerCase() };
    if (kind === 'Range') {
      const nums = /,\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*"[^"]*"\s*$/.exec(rest);
      assert.ok(nums, `Range ${key}: numbers not found`);
      Object.assign(entry, { min: +nums[1], max: +nums[2], step: +nums[3], default: +nums[4] });
    }
    if (kind === 'Toggle') entry.default = /true\s*$/.test(rest.trim());
    found.push(entry);
  }
  assert.deepEqual(found.map((f) => f.key), gs.SCHEMA.map((s) => s.key));
  for (const f of found) {
    const s = gs.SCHEMA.find((x) => x.key === f.key);
    assert.equal(s.type, f.type, f.key);
    for (const k of ['min', 'max', 'step', 'default']) if (k in f) assert.equal(s[k], f[k], `${f.key}.${k}`);
  }
  const movement = fs.readFileSync(path.join(path.dirname(file), '..', '..', '..', '..', '..', '..', 'main', 'java', 'dev',
    'moui', 'galaxycraft', 'settings', 'Movement.java'), 'utf8');
  const names = [...movement.matchAll(/^\s*([A-Z_]+)\("/gm)].map((x) => x[1]);
  assert.deepEqual(names, gs.SCHEMA[0].options.map((o) => o.value));
});
