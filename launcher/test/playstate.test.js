'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { playerState } = require('../src/core/playstate');

const manifest = (version, systems = ['linux-x64']) => ({ version, dolphin: Object.fromEntries(systems.map((s) => [s, { file: 'd' }])) });
const base = {
  account: { name: 'Moui' }, signInReady: true, rom: { path: '/g.rvz', check: { ok: true } },
  installed: { version: '0.2.0', manifest: manifest('0.2.0') }, latest: manifest('0.2.0'), system: 'linux-x64',
};

test('the button, step by step', () => {
  assert.equal(playerState({ ...base, installed: null, latest: null }).action, 'checking');
  assert.equal(playerState({ ...base, installed: null, latest: new Error('offline') }).action, 'unavailable');
  assert.equal(playerState({ ...base, account: null }).label, 'SIGN IN');
  assert.match(playerState({ ...base, account: null, signInReady: false }).detail, /not set up/);
  assert.equal(playerState({ ...base, rom: null }).label, 'CHOOSE GAME');
  assert.match(playerState({ ...base, rom: { path: '/x', check: { ok: false, reason: 'PAL!' } } }).detail, /PAL!/);
  assert.deepEqual(playerState({ ...base, installed: null }), { action: 'install', label: 'INSTALL', detail: 'Super Minecraft Galaxy 0.2.0: ready to install.', version: '0.2.0' });
  assert.equal(playerState({ ...base, latest: manifest('0.10.0') }).action, 'update');
  assert.equal(playerState(base).action, 'play');
});

test('offline with the game installed: play anyway', () => {
  assert.equal(playerState({ ...base, latest: new Error('offline') }).action, 'play');
});

test('a system without a release yet', () => {
  const r = playerState({ ...base, installed: null, system: 'win32-x64' });
  assert.equal(r.action, 'unsupported');
  assert.match(r.detail, /not out for Windows yet/);
  // An update that drops this system does not take the installed game away.
  assert.equal(playerState({ ...base, latest: manifest('0.3.0', ['win32-x64']) }).action, 'play');
});
