'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const paths = require('../src/core/paths');
const { fakeFs } = require('./helpers');

test('Linux: the data folder is the one tools/gxplay.sh uses', () => {
  const p = paths.platformPaths({ platform: 'linux', env: {}, home: '/home/mo' });
  assert.equal(p.dataDir, '/home/mo/.local/share/galaxycraft');
  assert.equal(p.defaultGameDir, '/home/mo/.local/share/galaxycraft/minecraft');
  assert.equal(p.dolphinDir, '/home/mo/.local/share/galaxycraft/dolphin');
  assert.deepEqual(p.dolphinConfigs, ['/home/mo/.config/dolphin-emu', '/home/mo/.dolphin-emu/Config']);
  assert.deepEqual(p.dolphinUsers, ['/home/mo/.local/share/dolphin-emu', '/home/mo/.dolphin-emu']);
});

test('Linux: XDG folders are followed', () => {
  const p = paths.platformPaths({ platform: 'linux', env: { XDG_DATA_HOME: '/d', XDG_CONFIG_HOME: '/c' }, home: '/h' });
  assert.equal(p.dataDir, '/d/galaxycraft');
  assert.equal(p.dolphinConfigs[0], '/c/dolphin-emu');
});

test('Windows: %APPDATA% and Dolphin Emulator folders', () => {
  const env = { APPDATA: 'C:\\Users\\Mo\\AppData\\Roaming', USERPROFILE: 'C:\\Users\\Mo' };
  const p = paths.platformPaths({ platform: 'win32', env });
  assert.equal(p.dataDir, 'C:\\Users\\Mo\\AppData\\Roaming\\galaxycraft');
  assert.equal(p.defaultGameDir, 'C:\\Users\\Mo\\AppData\\Roaming\\galaxycraft\\minecraft');
  assert.deepEqual(p.dolphinUsers, ['C:\\Users\\Mo\\AppData\\Roaming\\Dolphin Emulator', 'C:\\Users\\Mo\\Documents\\Dolphin Emulator']);
  assert.equal(p.dolphinConfigs[1], 'C:\\Users\\Mo\\Documents\\Dolphin Emulator\\Config');
});

test('Dolphin binary, wrapper and module per system', () => {
  assert.deepEqual(paths.dolphinBinaryCandidates('/g', 'linux'), ['/g/dolphin/build/Binaries/dolphin-emu']);
  assert.equal(paths.dolphinBinaryCandidates('C:\\g', 'win32')[0], 'C:\\g\\dolphin\\build\\Binaries\\Dolphin.exe');
  assert.equal(paths.gradleWrapper('C:\\g', 'win32'), 'C:\\g\\fabric\\gradlew.bat');
  assert.equal(paths.gradleWrapper('/g', 'linux'), '/g/fabric/gradlew');
  assert.equal(paths.modulePatch('/g', 'linux'), '/g/syati/build/galaxycraft.json');
});

test('the game folder: chosen first, then GXC_ROOT, the launcher\'s checkout, the usual places', () => {
  const fs = fakeFs({
    '/home/mo/GalaxyCraft/protocol/galaxycraft_protocol.h': '',
    '/home/mo/GalaxyCraft/fabric/build.gradle': '',
    '/repo/protocol/galaxycraft_protocol.h': '',
    '/repo/fabric/build.gradle': '',
  });
  const opts = { env: {}, home: '/home/mo', platform: 'linux' };
  assert.equal(paths.findGameRoot(opts, fs.exists), '/home/mo/GalaxyCraft');
  assert.equal(paths.findGameRoot({ ...opts, appDir: '/repo/launcher' }, fs.exists), '/repo');
  assert.equal(paths.findGameRoot({ ...opts, chosen: '/nope', env: { GXC_ROOT: '/repo' } }, fs.exists), '/repo');
  assert.equal(paths.findGameRoot({ ...opts, home: '/x' }, fs.exists), null);
});

test('GXC_DATA_DIR moves the game data', () => {
  const p = paths.platformPaths({ platform: 'linux', env: { GXC_DATA_DIR: '/tmp/gx' }, home: '/h' });
  assert.equal(p.dataDir, '/tmp/gx');
  assert.equal(p.dolphinDir, '/tmp/gx/dolphin');
});
