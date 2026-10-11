'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const mcprofile = require('../src/main/mcprofile');
const { platformPaths } = require('../src/core/paths');
const store = require('../src/core/store');

test('play.json carries the Picture Quality setting (the mod starts Dolphin from it)', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'gxl-mcprofile-'));
  const home = path.join(tmp, 'home');
  const platform = process.platform;
  const paths = platformPaths({ platform, env: platform === 'win32' ? { APPDATA: path.join(home, 'AppData') } : {}, home });
  const inst = store.defaultInstallation();
  const lay = { module: path.join(tmp, 'module'), dolphin: path.join(tmp, 'dolphin'), descriptor: path.join(tmp, 'galaxycraft.json'),
    mc: { versions: path.join(tmp, 'versions') } };
  const installer = {
    system: 'test',
    installed: () => ({ manifest: { dolphin: { test: { exe: 'Dolphin' } }, minecraft: {} }, lay }),
    syncMods: () => {},
  };
  const playJson = () => JSON.parse(fs.readFileSync(path.join(paths.dataDir, 'play.json'), 'utf8')).args.join(' ');

  // No settings file yet: High, the game's default. No .minecraft: play.json is written all the same.
  mcprofile.register({ installer, inst, paths, minecraftDir: null });
  assert.match(playJson(), /InternalResolution=3 .*MSAA=4/);

  const settings = path.join(store.gameDirOf(inst, paths), 'config', 'galaxycraft.properties');
  fs.mkdirSync(path.dirname(settings), { recursive: true });
  fs.writeFileSync(settings, '#GalaxyCraft settings\r\nvideoQuality=MEDIUM\r\n');
  mcprofile.register({ installer, inst, paths, minecraftDir: null });
  assert.match(playJson(), /InternalResolution=2 .*MSAA=2/);
});
