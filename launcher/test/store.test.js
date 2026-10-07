'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const store = require('../src/core/store');
const { platformPaths } = require('../src/core/paths');

test('a broken or empty file gives the defaults', () => {
  for (const raw of [null, 'x', {}, { installations: 'no' }]) {
    const s = store.normalize(raw);
    assert.equal(s.installations.length, 1);
    assert.equal(s.selected, 'default');
    assert.equal(s.settings.accent, 'grass');
  }
});

test('bad values fall back, good ones stay', () => {
  const s = store.normalize({
    settings: { accent: 'toString', onPlay: 'hide', customAccent: 'red', background: 'custom', customBackground: '' },
    installations: [{ id: 'a', name: '  ', icon: 'nope', dualCore: 'yes' }, { id: 'a', name: 'dupe' }],
    selected: 'zzz',
  });
  assert.equal(s.settings.accent, 'grass');
  assert.equal(s.settings.onPlay, 'hide');
  assert.equal(s.settings.background, 'planet');
  assert.equal(s.installations.length, 1);
  assert.equal(s.installations[0].name, 'Installation');
  assert.equal(s.installations[0].icon, 'planet');
  assert.equal(s.installations[0].dualCore, true);
  assert.equal(s.selected, 'a');
});

test('installations: add, duplicate, delete (never the last)', () => {
  let s = store.defaultState();
  s = store.saveInstallation(s, { name: 'Creative Builds', icon: 'diamond' });
  assert.equal(s.selected, 'creative-builds');
  s = store.saveInstallation(s, { name: 'Creative Builds' });
  assert.equal(s.installations[2].id, 'creative-builds-2');
  s = store.duplicateInstallation(s, 'default');
  assert.equal(s.installations.at(-1).name, 'Super Minecraft Galaxy (copy)');
  s = store.saveInstallation(s, { ...s.installations[1], name: 'Renamed' });
  assert.equal(s.installations[1].name, 'Renamed');
  assert.equal(s.installations[1].id, 'creative-builds');
  for (const i of [...s.installations]) s = store.deleteInstallation(s, i.id);
  assert.equal(s.installations.length, 1);
});

test('game folders: the default is gxplay.sh\'s, others get their own', () => {
  const paths = platformPaths({ platform: 'linux', env: {}, home: '/h' });
  assert.equal(store.gameDirOf(store.defaultInstallation(), paths), '/h/.local/share/galaxycraft/minecraft');
  assert.equal(store.gameDirOf({ id: 'x', gameDir: '' }, paths), '/h/.local/share/galaxycraft/installations/x');
  assert.equal(store.gameDirOf({ id: 'x', gameDir: '/mine' }, paths), '/mine');
});
