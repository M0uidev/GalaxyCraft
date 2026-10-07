'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const mc = require('../src/core/minecraft');

const vanilla = require('./fixtures/version-26.3.json');
const fabric = require('./fixtures/fabric-profile.json');
const linux = mc.mojangOs('linux', 'x64');
const win = mc.mojangOs('win32', 'x64');

test('rules: per system and per feature, the last match decides', () => {
  assert.equal(mc.allowed(undefined, linux), true);
  assert.equal(mc.allowed([{ action: 'allow', os: { name: 'linux' } }], linux), true);
  assert.equal(mc.allowed([{ action: 'allow', os: { name: 'linux' } }], win), false);
  assert.equal(mc.allowed([{ action: 'allow' }, { action: 'disallow', os: { name: 'osx' } }], linux), true);
  assert.equal(mc.allowed([{ action: 'allow', os: { arch: 'x86' } }], linux), false);
  assert.equal(mc.allowed([{ action: 'allow', features: { is_demo_user: true } }], linux, {}), false);
  assert.equal(mc.runtimePlatform('win32', 'x64'), 'windows-x64');
  assert.equal(mc.runtimePlatform('linux', 'x64'), 'linux');
});

test('Maven paths and library keys', () => {
  assert.equal(mc.mavenPath('net.fabricmc:fabric-loader:0.19.5'), 'net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar');
  assert.equal(mc.mavenPath('org.lwjgl:lwjgl:3.3.3:natives-linux'), 'org/lwjgl/lwjgl/3.3.3/lwjgl-3.3.3-natives-linux.jar');
  assert.equal(mc.libraryKey('org.ow2.asm:asm:9.9'), mc.libraryKey('org.ow2.asm:asm:9.6'));
  assert.notEqual(mc.libraryKey('org.lwjgl:lwjgl:3.3.3'), mc.libraryKey('org.lwjgl:lwjgl:3.3.3:natives-linux'));
});

test('Fabric over Mojang\'s version: its loader, its newer ASM, Mojang\'s natives for this system', () => {
  const v = mc.merge(fabric, vanilla);
  assert.equal(v.id, 'fabric-loader-0.19.5-26.3');
  assert.equal(v.clientVersion, '26.3');
  assert.equal(v.mainClass, 'net.fabricmc.loader.impl.launch.knot.KnotClient');
  const names = mc.libraries(v, linux).map((l) => l.name);
  assert.deepEqual(names, ['org.ow2.asm:asm:9.9', 'net.fabricmc:sponge-mixin:0.16.5+mixin.0.8.7', 'net.fabricmc:fabric-loader:0.19.5',
    'com.mojang:brigadier:1.3.10', 'org.lwjgl:lwjgl:3.3.3', 'org.lwjgl:lwjgl:3.3.3:natives-linux']);
  const winNames = mc.libraries(v, win).map((l) => l.name);
  assert.ok(winNames.includes('org.lwjgl:lwjgl:3.3.3:natives-windows'));
  assert.ok(!winNames.includes('org.lwjgl:lwjgl:3.3.3:natives-windows-arm64'));
  const loader = mc.libraries(v, linux)[2];
  assert.equal(loader.url, 'https://maven.fabricmc.net/net/fabricmc/fabric-loader/0.19.5/fabric-loader-0.19.5.jar');
});

test('downloads: libraries, the client, the log config, assets once per hash', () => {
  const v = mc.merge(fabric, vanilla);
  const dirs = { libraries: '/mc/libraries', versions: '/mc/versions', assets: '/mc/assets' };
  const index = { objects: { 'a/x.png': { hash: 'ab12', size: 3 }, 'b/y.png': { hash: 'ab12', size: 3 }, 'c.ogg': { hash: 'cd34', size: 9 } } };
  const list = mc.downloads(v, index, linux, dirs);
  assert.ok(list.some((d) => d.path === path.join('/mc/versions', '26.3', '26.3.jar') && d.sha1 === 'cc'));
  assert.ok(list.some((d) => d.path === path.join('/mc/assets', 'log_configs', 'client-1.21.2.xml')));
  const assets = list.filter((d) => d.quick);
  assert.deepEqual(assets.map((d) => d.url), ['https://resources.download.minecraft.net/ab/ab12', 'https://resources.download.minecraft.net/cd/cd34']);
  assert.equal(mc.downloads(v, index, linux, dirs, { assets: false }).filter((d) => d.quick).length, 0);
});

test('the command line, as the official launcher builds it', () => {
  const v = mc.merge(fabric, vanilla);
  const dirs = { libraries: '/mc/libraries', versions: '/mc/versions', assets: '/mc/assets', natives: '/mc/natives', game: '/games/default' };
  const auth = { name: 'Steve', uuid: '0123', accessToken: 'tok', xuid: '42', clientId: 'cid' };
  const { cmd, args } = mc.command({ version: v, dirs, auth, java: '/rt/bin/java', jvmArgs: ['-Xmx4G', '-Dgalaxycraft.hidden=true'],
    platform: 'linux', arch: 'x64', launcher: { name: 'smg', version: '1.0' } });
  assert.equal(cmd, '/rt/bin/java');
  const j = (...p) => path.join(...p);
  assert.deepEqual(args.slice(0, 3), ['-Xmx4G', '-Dgalaxycraft.hidden=true', `-Dlog4j.configurationFile=${j('/mc/assets', 'log_configs', 'client-1.21.2.xml')}`]);
  assert.ok(!args.includes('-XstartOnFirstThread'));
  assert.ok(!args.includes('--demo'));
  assert.ok(!args.includes('--quickPlaySingleplayer'));
  assert.ok(args.includes('-Djava.library.path=/mc/natives'));
  assert.ok(args.includes('-DFabricMcEmu= net.minecraft.client.main.Main '));
  const cp = args[args.indexOf('-cp') + 1].split(':');
  assert.equal(cp[0], j('/mc/libraries', 'org', 'ow2', 'asm', 'asm', '9.9', 'asm-9.9.jar'));
  assert.equal(cp.at(-1), j('/mc/versions', '26.3', '26.3.jar'));
  const main = args.indexOf('net.fabricmc.loader.impl.launch.knot.KnotClient');
  assert.ok(main > args.indexOf('-cp'));
  const game = args.slice(main + 1);
  assert.deepEqual(game.slice(0, 6), ['--username', 'Steve', '--version', 'fabric-loader-0.19.5-26.3', '--gameDir', '/games/default']);
  assert.equal(game[game.indexOf('--accessToken') + 1], 'tok');
  assert.equal(game[game.indexOf('--assetIndex') + 1], '29');

  const w = mc.command({ version: v, dirs, auth, java: 'C:\\rt\\bin\\java.exe', platform: 'win32', arch: 'x64' });
  assert.ok(w.args.some((a) => a.startsWith('-XX:HeapDumpPath')));
  assert.ok(w.args[w.args.indexOf('-cp') + 1].includes(';'));
});

test('Mojang\'s Java runtime: files, folders, links', () => {
  const manifest = { files: {
    bin: { type: 'directory' },
    'bin/java': { type: 'file', executable: true, downloads: { raw: { sha1: 's', size: 1, url: 'https://x/java' } } },
    'legal/LICENSE': { type: 'link', target: '../LICENSE' },
  } };
  const r = mc.runtimeFiles(manifest, '/rt');
  assert.deepEqual(r.files, [{ path: path.join('/rt', 'bin', 'java'), url: 'https://x/java', sha1: 's', size: 1, executable: true }]);
  assert.deepEqual(r.dirs, [path.join('/rt', 'bin')]);
  assert.equal(r.links[0].target, '../LICENSE');
  const all = { linux: { 'java-runtime-epsilon': [{ manifest: { url: 'u' }, version: { name: '25.0.1' } }] } };
  assert.equal(mc.pickRuntime(all, 'java-runtime-epsilon', 'linux').version.name, '25.0.1');
  assert.equal(mc.pickRuntime(all, 'java-runtime-epsilon', 'windows-x64'), null);
  assert.equal(mc.javaExecutable('C:\\rt', 'win32'), path.join('C:\\rt', 'bin', 'java.exe'));
});
