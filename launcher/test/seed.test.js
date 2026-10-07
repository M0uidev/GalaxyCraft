'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { seed } = require('../src/core/seed');
const { buildPlan } = require('../src/core/launchplan');
const { platformPaths } = require('../src/core/paths');
const store = require('../src/core/store');

function write(file, text = '') {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, text);
}

test('Dolphin\'s folder and SMG2\'s save, made once, never overwritten', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'gxl-seed-'));
  const home = path.join(tmp, 'home');
  const root = path.join(tmp, 'repo');
  const platform = process.platform;
  const env = platform === 'win32' ? { APPDATA: path.join(home, 'AppData') } : {};
  const paths = platformPaths({ platform, env, home });
  write(path.join(root, 'tools', 'dolphin-play', 'Dolphin.ini'), 'template');
  write(path.join(root, 'tools', 'dolphin-play', 'WiimoteNew.ini'), 'wiimote');
  write(path.join(paths.dolphinConfigs[0], 'GFX.ini'), 'my gfx');
  const save = path.join(paths.dolphinUsers[0], 'Wii', 'title', '00010000', '53423445', 'data', 'GameData.bin');
  write(save, 'my save');

  const plan = buildPlan({ root, paths, inst: store.defaultInstallation(), javaHome: 'j', dolphinBin: 'd', env: {} });
  const logs = [];
  seed(plan, (l) => logs.push(l));
  const cfg = path.join(paths.dolphinDir, 'Config');
  assert.equal(fs.readFileSync(path.join(cfg, 'Dolphin.ini'), 'utf8'), 'template');
  assert.equal(fs.readFileSync(path.join(cfg, 'GFX.ini'), 'utf8'), 'my gfx');
  assert.equal(fs.existsSync(path.join(cfg, 'Hotkeys.ini')), false);
  const copied = path.join(paths.dolphinDir, 'Wii', 'title', '00010000', '53423445', 'data', 'GameData.bin');
  assert.equal(fs.readFileSync(copied, 'utf8'), 'my save');
  assert.ok(fs.existsSync(plan.gameDir));
  assert.equal(logs.length, 3);

  // Later runs leave the player's changes alone.
  fs.writeFileSync(path.join(cfg, 'Dolphin.ini'), 'changed');
  fs.writeFileSync(copied, 'played');
  seed(plan);
  assert.equal(fs.readFileSync(path.join(cfg, 'Dolphin.ini'), 'utf8'), 'changed');
  assert.equal(fs.readFileSync(copied, 'utf8'), 'played');
  fs.rmSync(tmp, { recursive: true, force: true });
});
