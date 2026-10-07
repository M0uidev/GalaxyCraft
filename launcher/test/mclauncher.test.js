'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const ml = require('../src/core/mclauncher');
const gamepack = require('../src/core/gamepack');
const { platformPaths } = require('../src/core/paths');
const { fakeFs } = require('./helpers');

test('.minecraft candidates per system', () => {
  assert.deepEqual(ml.minecraftDirCandidates({ platform: 'linux', env: {}, home: '/home/mo' }),
    ['/home/mo/.minecraft', '/home/mo/.var/app/com.mojang.Minecraft/.minecraft']);
  assert.deepEqual(ml.minecraftDirCandidates({ platform: 'win32', env: { APPDATA: 'C:\\Users\\Mo\\AppData\\Roaming' }, home: 'C:\\Users\\Mo' }),
    ['C:\\Users\\Mo\\AppData\\Roaming\\.minecraft']);
});

test('the folder is the first one with a launcher_profiles.json', () => {
  const opts = { platform: 'linux', env: {}, home: '/h' };
  assert.equal(ml.findMinecraftDir(opts, fakeFs({ '/h/.minecraft/options.txt': '' }).exists), null);
  assert.equal(ml.findMinecraftDir(opts, fakeFs({ '/h/.var/app/com.mojang.Minecraft/.minecraft/launcher_profiles.json': '{}' }).exists),
    '/h/.var/app/com.mojang.Minecraft/.minecraft');
  assert.equal(ml.findMinecraftDir(opts, fakeFs({ '/h/.minecraft/launcher_profiles.json': '{}',
    '/h/.var/app/com.mojang.Minecraft/.minecraft/launcher_profiles.json': '{}' }).exists), '/h/.minecraft');
});

const entry = (extra = {}) => ml.profileEntry({ versionId: 'fabric-loader-0.19.5-26.3', gameDir: '/g', javaArgs: '-Xmx4G -Dgalaxycraft.hidden=true',
  icon: 'data:image/png;base64,AA==', now: '2026-10-07T00:00:00.000Z', ...extra });

test('the profile entry', () => {
  assert.deepEqual(entry(), {
    name: 'Super Minecraft Galaxy', type: 'custom', created: '2026-10-07T00:00:00.000Z', lastUsed: '2026-10-07T00:00:00.000Z',
    icon: 'data:image/png;base64,AA==', lastVersionId: 'fabric-loader-0.19.5-26.3', gameDir: '/g',
    javaArgs: '-Xmx4G -Dgalaxycraft.hidden=true -Dgalaxycraft.startDolphin=true',
  });
  assert.equal(ml.fabricVersionId({ version: '26.3', fabricLoader: '0.19.5' }), 'fabric-loader-0.19.5-26.3');
});

test('merging keeps the other profiles and unknown keys', () => {
  const existing = { profiles: { other: { name: 'x', lastVersionId: 'latest-release' } }, settings: { crashAssistance: true }, version: 3, authenticationDatabase: {} };
  const out = ml.mergeProfiles(existing, entry());
  assert.deepEqual(out.profiles.other, existing.profiles.other);
  assert.deepEqual(out.settings, { crashAssistance: true });
  assert.equal(out.version, 3);
  assert.deepEqual(Object.keys(out.profiles).sort(), ['other', 'super-minecraft-galaxy']);
  assert.equal(out.profiles['super-minecraft-galaxy'].name, 'Super Minecraft Galaxy');
  assert.deepEqual(Object.keys(existing.profiles), ['other']); // the input is not changed
});

test('merging updates ours and keeps its created date and unknown fields', () => {
  const first = ml.mergeProfiles({}, entry());
  first.profiles['super-minecraft-galaxy'].resolution = { width: 1, height: 1 };
  const out = ml.mergeProfiles(first, entry({ gameDir: '/h', now: '2026-11-01T00:00:00.000Z', versionId: 'v2' }));
  const p = out.profiles['super-minecraft-galaxy'];
  assert.equal(p.created, '2026-10-07T00:00:00.000Z');
  assert.equal(p.lastUsed, '2026-11-01T00:00:00.000Z');
  assert.equal(p.gameDir, '/h');
  assert.equal(p.lastVersionId, 'v2');
  assert.deepEqual(p.resolution, { width: 1, height: 1 });
});

test('merging into nothing or a broken file makes a profiles file', () => {
  for (const bad of [null, undefined, [], 'x', { profiles: [] }]) {
    assert.deepEqual(Object.keys(ml.mergeProfiles(bad, entry()).profiles), ['super-minecraft-galaxy']);
  }
});

test('play.json is the Dolphin process of the plan, with only the variables PLAY adds', () => {
  const paths = platformPaths({ platform: 'linux', env: {}, home: '/h' });
  const inst = { id: 'default', dualCore: true, dolphinArgs: '' };
  const lay = gamepack.layout(paths, '0.2.0');
  const mcCommand = { cmd: 'java', args: [] };
  const plan = gamepack.playerPlan({ paths, inst, lay, dolphinExe: '/d/dolphin-emu', mcCommand, env: {} });
  const j = ml.playJson(plan);
  assert.equal(j.format, 1);
  assert.equal(j.cmd, '/d/dolphin-emu');
  assert.equal(j.cwd, lay.dolphin);
  assert.deepEqual(j.args, plan.processes[0].args);
  assert.deepEqual(j.env, { GALAXYCRAFT: '1', GALAXYCRAFT_BOOT: 'space', QT_QPA_PLATFORM: 'xcb' });
  // With a whole environment as the base, only the additions stay.
  const full = gamepack.playerPlan({ paths, inst, lay, dolphinExe: '/d/dolphin-emu', mcCommand, env: { PATH: '/bin' } });
  assert.deepEqual(ml.playJson(full, { PATH: '/bin' }).env, j.env);
});
