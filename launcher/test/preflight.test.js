'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { preflight } = require('../src/core/preflight');
const store = require('../src/core/store');
const { fakeFs } = require('./helpers');

const ROOT = {
  '/h/GalaxyCraft/protocol/galaxycraft_protocol.h': '',
  '/h/GalaxyCraft/fabric/build.gradle': '',
  '/h/GalaxyCraft/fabric/gradle/wrapper/gradle-wrapper.jar': '',
  '/h/GalaxyCraft/tools/dolphin-play/Dolphin.ini': '',
};
const base = { settings: store.defaultState().settings, inst: store.defaultInstallation(), platform: 'linux', env: {}, home: '/h' };

test('nothing built: PLAY says what to run', () => {
  const r = preflight(base, fakeFs({ ...ROOT, '/h/.local/opt/jdk-25/bin/java': '' }));
  assert.equal(r.ok, false);
  assert.equal(r.root, '/h/GalaxyCraft');
  assert.equal(r.reason.id, 'dolphin');
  assert.match(r.reason.fix, /dolphin\/build\.sh/);
  assert.equal(r.checks.find((c) => c.id === 'module').ok, false);
});

test('everything there: ready to play', () => {
  const r = preflight(base, fakeFs({
    ...ROOT,
    '/h/GalaxyCraft/dolphin/build/Binaries/dolphin-emu': '',
    '/h/GalaxyCraft/syati/build/galaxycraft.json': '',
    '/h/.local/opt/jdk-25/bin/java': '',
  }));
  assert.equal(r.ok, true, JSON.stringify(r.reason));
  assert.equal(r.dolphinBin, '/h/GalaxyCraft/dolphin/build/Binaries/dolphin-emu');
  assert.equal(r.java.home, '/h/.local/opt/jdk-25');
});

test('no game folder and no Java', () => {
  const r = preflight({ ...base, settings: { ...base.settings, gameRoot: '/wrong' } }, fakeFs({}));
  assert.equal(r.reason.id, 'root');
  assert.match(r.reason.detail, /\/wrong/);
  assert.equal(r.checks.find((c) => c.id === 'java').ok, false);
});

test('Windows: the fix points to docs/WINDOWS.md', () => {
  const fs = fakeFs({
    'C:\\GalaxyCraft\\protocol\\galaxycraft_protocol.h': '', 'C:\\GalaxyCraft\\fabric\\build.gradle': '',
  }, 'win32');
  const r = preflight({ ...base, platform: 'win32', home: 'C:\\Users\\Mo', settings: { ...base.settings, gameRoot: 'C:\\GalaxyCraft' } }, fs);
  assert.equal(r.reason.id, 'dolphin');
  assert.match(r.reason.fix, /WINDOWS\.md/);
});
