'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const official = require('../src/core/official');
const gamepack = require('../src/core/gamepack');
const store = require('../src/core/store');
const { platformPaths } = require('../src/core/paths');

test('which Minecraft PLAY uses', () => {
  assert.equal(official.minecraftSource('auto', null), 'official');
  assert.equal(official.minecraftSource('auto', { name: 'Moui' }), 'launcher');
  assert.equal(official.minecraftSource('official', { name: 'Moui' }), 'official');
  assert.equal(official.minecraftSource('launcher', null), 'launcher');
  assert.equal(store.normalize({ settings: { minecraftFrom: 'nope' } }).settings.minecraftFrom, 'auto');
  assert.equal(store.normalize({ settings: { minecraftFrom: 'official' } }).settings.minecraftFrom, 'official');
  assert.equal(store.defaultState().settings.closeMinecraftLauncher, true);
  assert.equal(store.normalize({ settings: { closeMinecraftLauncher: false } }).settings.closeMinecraftLauncher, false);
});

test('where the Minecraft Launcher is opened from', () => {
  const win = official.launcherCommands({ platform: 'win32', env: { 'ProgramFiles(x86)': 'C:\\Program Files (x86)', LOCALAPPDATA: 'C:\\Users\\m\\AppData\\Local' } });
  assert.equal(win[0].cmd, 'C:\\Program Files (x86)\\Minecraft Launcher\\MinecraftLauncher.exe');
  const fromStore = win.find((c) => c.cmd === 'explorer.exe');
  assert.deepEqual(fromStore.args, ['shell:AppsFolder\\Microsoft.4297127D64EC6_8wekyb3d8bbwe!Minecraft']);
  assert.equal(fromStore.check, 'C:\\Users\\m\\AppData\\Local\\Packages\\Microsoft.4297127D64EC6_8wekyb3d8bbwe');
  assert.equal(new Set(win.map((c) => c.cmd)).size, win.length); // no duplicates
  assert.equal(official.launcherCommands({ platform: 'linux', env: {}, home: '/home/m' })[0].cmd, '/usr/bin/minecraft-launcher');
  assert.deepEqual(official.launcherCommands({ platform: 'linux', env: { GXL_MINECRAFT_LAUNCHER: '/opt/mc/launcher' } }),
    [{ cmd: '/opt/mc/launcher', args: [], check: '/opt/mc/launcher' }]);
});

test('the Minecraft Launcher\'s processes: its own package or program, never Bedrock', () => {
  assert.deepEqual(official.launcherProcessPatterns('win32', {}), ['*\\WindowsApps\\Microsoft.4297127D64EC6_*', '*\\MinecraftLauncher.exe']);
  assert.deepEqual(official.launcherProcessPatterns('linux', {}), ['minecraft-launcher']);
  assert.deepEqual(official.launcherProcessPatterns('win32', { GXL_MINECRAFT_LAUNCHER: 'D:\\mc\\l.exe' }), ['D:\\mc\\l.exe']);
});

test('PLAY with the Minecraft Launcher starts nothing and watches for Minecraft', () => {
  const plan = gamepack.minecraftLauncherPlan();
  assert.deepEqual(plan.processes, []);
  assert.equal(plan.seed, null);
  assert.deepEqual(plan.watch, { name: 'Minecraft', match: 'galaxycraft.hidden=true' });
  assert.equal(plan.stopMatch, 'galaxycraft.hidden=true'); // STOP closes that Minecraft (and the Dolphin it started)
});

test('Fabric\'s version without downloading Minecraft: fetched once, where register looks', async () => {
  const { fabricProfile } = require('../src/main/official');
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'smg-fabric-'));
  try {
    const paths = platformPaths({ env: { GXC_DATA_DIR: tmp } });
    const manifest = { version: '0.2.0', minecraft: { version: '26.3', fabricLoader: '0.19.5' } };
    const body = fs.readFileSync(path.join(__dirname, 'fixtures', 'fabric-profile.json'), 'utf8');
    const urls = [];
    const fetchFn = async (url) => { urls.push(url); return new Response(body, { status: 200 }); };
    const file = await fabricProfile({ manifest, paths, fetchFn });
    assert.equal(file, path.join(gamepack.layout(paths, '0.2.0').mc.versions, 'fabric-loader-0.19.5-26.3', 'fabric-loader-0.19.5-26.3.json'));
    assert.equal(JSON.parse(fs.readFileSync(file, 'utf8')).id, 'fabric-loader-0.19.5-26.3');
    assert.match(urls[0], /\/versions\/loader\/26\.3\/0\.19\.5\/profile\/json$/);
    await fabricProfile({ manifest, paths, fetchFn });
    assert.equal(urls.length, 1);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});
