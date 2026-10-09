'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const zlib = require('node:zlib');
const report = require('../src/core/report');
const { fakeFs } = require('./helpers');

/** Reads a zip made by buildZip back: { name: Buffer }. */
function unzip(buf) {
  const out = {};
  const end = buf.lastIndexOf(Buffer.from([0x50, 0x4b, 5, 6]));
  const count = buf.readUInt16LE(end + 10);
  let p = buf.readUInt32LE(end + 16);
  for (let i = 0; i < count; i++) {
    const method = buf.readUInt16LE(p + 10);
    const crc = buf.readUInt32LE(p + 16);
    const csize = buf.readUInt32LE(p + 20);
    const nlen = buf.readUInt16LE(p + 28);
    const elen = buf.readUInt16LE(p + 30);
    const clen = buf.readUInt16LE(p + 32);
    const off = buf.readUInt32LE(p + 42);
    const name = buf.toString('utf8', p + 46, p + 46 + nlen);
    const lnlen = buf.readUInt16LE(off + 26);
    const lelen = buf.readUInt16LE(off + 28);
    const raw = buf.subarray(off + 30 + lnlen + lelen, off + 30 + lnlen + lelen + csize);
    const data = method === 8 ? zlib.inflateRawSync(raw) : raw;
    assert.equal(zlib.crc32 ? zlib.crc32(data) : crc, crc, 'crc of ' + name);
    out[name] = data;
    p += 46 + nlen + elen + clen;
  }
  return out;
}

test('buildZip: entries come back intact, with unicode names and empty files', () => {
  const zip = report.buildZip([
    { name: 'a.txt', data: 'hello '.repeat(500) },
    { name: 'dir/ñ.log', data: Buffer.from('x') },
    { name: 'empty.txt', data: '' },
  ]);
  const got = unzip(zip);
  assert.equal(got['a.txt'].toString(), 'hello '.repeat(500));
  assert.equal(got['dir/ñ.log'].toString(), 'x');
  assert.equal(got['empty.txt'].length, 0);
});

test('redact: the home folder, tokens and the session are removed', () => {
  const home = '/home/mo';
  const text = [
    'opened /home/mo/.local/share/galaxycraft/logs',
    'java --accessToken eyJhbGciOiJSUzI1NiJ9.abcdefghijk.lmnopqrstuv --uuid 123e4567-e89b-12d3-a456-426614174000 --xuid 2535',
    'Authorization: Bearer abc.DEF-123',
    '{"access_token":"secret1","refresh_token": "secret2"}',
    'Setting user: Steve',
  ].join('\n');
  const out = report.redact(text, { home });
  assert.ok(!out.includes('/home/mo/'));
  assert.ok(out.includes('~/.local/share/galaxycraft/logs'));
  for (const s of ['eyJhbGci', '123e4567', '2535', 'abc.DEF', 'secret1', 'secret2']) assert.ok(!out.includes(s), s);
  assert.ok(out.includes('Setting user: Steve'));
});

test('redact: a Windows home folder, with either slash', () => {
  const out = report.redact('C:\\Users\\Mo\\AppData\\x and C:/Users/Mo/y', { home: 'C:\\Users\\Mo' });
  assert.equal(out, '~\\AppData\\x and ~/y');
});

test('tail: keeps the end, whole lines, and says it cut', () => {
  const text = Array.from({ length: 100 }, (_, i) => `line ${i}`).join('\n');
  const out = report.tail(text, 60);
  assert.ok(out.startsWith('[... cut'));
  assert.ok(out.endsWith('line 99'));
  assert.equal(report.tail('short', 60), 'short');
});

const P = { dataDir: '/d/galaxycraft', dolphinDir: '/d/galaxycraft/dolphin', path: require('node:path').posix, home: '/h' };

test('collect: launcher, Minecraft and Dolphin logs, crash reports and settings; absent files are skipped', () => {
  const fs = fakeFs({
    '/d/galaxycraft/logs/launcher.log': 'L1 /h/x',
    '/d/galaxycraft/logs/launcher.old.log': 'L0',
    '/g/logs/latest.log': 'MC',
    '/g/logs/debug.log': 'DBG',
    '/g/crash-reports/crash-1.txt': 'CR1',
    '/g/hs_err_pid42.log': 'HS',
    '/g/config/galaxycraft.properties': 'a=1',
    '/d/galaxycraft/dolphin/Logs/dolphin.log': 'DOL',
    '/d/galaxycraft/dolphin/Logs/galaxycraft.log': 'GXC',
    '/u/launcher.json': '{"settings":{}}',
    '/g/logs/2026-10-07-1.log.gz': 'ignored',
  });
  const files = report.collect({ fs, paths: P, gameDir: '/g', stateFile: '/u/launcher.json' });
  const byName = Object.fromEntries(files.map((f) => [f.name, f.data]));
  assert.deepEqual(Object.keys(byName).sort(), [
    'config/galaxycraft.properties', 'config/launcher.json',
    'dolphin/dolphin.log', 'dolphin/galaxycraft.log',
    'launcher/launcher.log', 'launcher/launcher.old.log',
    'minecraft/crash-reports/crash-1.txt', 'minecraft/debug.log', 'minecraft/hs_err_pid42.log', 'minecraft/latest.log',
  ]);
  assert.equal(byName['launcher/launcher.log'], 'L1 ~/x');
});

test('collect: only the newest crash reports, and big logs are cut to their end', () => {
  const crashes = {};
  for (let i = 1; i <= 6; i++) crashes[`/g/crash-reports/crash-${i}.txt`] = 'c' + i;
  const fs = fakeFs({ ...crashes, '/g/logs/latest.log': 'x\n'.repeat(5000) });
  const files = report.collect({ fs, paths: P, gameDir: '/g', maxBytes: 100 });
  assert.equal(files.filter((f) => f.name.includes('crash-reports')).length, 3);
  assert.ok(files.find((f) => f.name === 'minecraft/latest.log').data.length < 200);
});

test('collect: the settings file does not carry the skins looked up', () => {
  const fs = fakeFs({ '/u/launcher.json': JSON.stringify({ settings: { onPlay: 'keep', recentSkins: ['notch'] } }) });
  const [f] = report.collect({ fs, paths: P, gameDir: '/g', stateFile: '/u/launcher.json' });
  assert.ok(f.data.includes('onPlay'));
  assert.ok(!f.data.includes('notch'));
});

test('systemText: versions, system, game state and the files, without account data', () => {
  const text = report.systemText({
    version: '0.1.2', platform: 'linux', arch: 'x64', osRelease: '7.2', electron: '44.0',
    totalMem: 16 * 2 ** 30, cpus: 'Ryzen 5 x12', gpu: 'RTX 3060',
    game: { state: 'stopped', crashed: true, by: 'Minecraft', code: 1 },
    installation: { name: 'Super Minecraft Galaxy', dualCore: true, javaArgs: '', gameDir: '/h/g' },
    settings: { onPlay: 'keep', msaClientId: 'abc', recentSkins: ['notch'] },
    files: ['launcher/launcher.log'], when: new Date('2026-10-08T12:00:00Z'), home: '/h',
  });
  assert.match(text, /Launcher 0\.1\.2/);
  assert.match(text, /linux x64/);
  assert.match(text, /16 GB/);
  assert.match(text, /crashed/);
  assert.match(text, /2026-10-08T12:00:00/);
  assert.match(text, /launcher\/launcher\.log/);
  assert.ok(!text.includes('/h/g'));
  assert.ok(!text.includes('notch') && !text.includes('abc'));
});

test('defaultName: smg-report-<date>-<time>.zip', () => {
  assert.equal(report.defaultName(new Date('2026-10-08T12:34:56Z')), 'smg-report-2026-10-08-123456.zip');
});
