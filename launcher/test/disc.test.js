'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const path = require('node:path');
const disc = require('../src/core/disc');

function header(id, title = 'SUPER MARIO GALAXY 2', wii = true) {
  const h = Buffer.alloc(0x100);
  h.write(id, 0, 'latin1');
  if (wii) h.writeUInt32BE(0x5d1c9ea3, 0x18);
  h.write(title, 0x20, 'utf8');
  return h;
}
const reader = (buf) => (offset, length) => buf.subarray(offset, offset + length);
function place(prefix, at, h) {
  const b = Buffer.alloc(at + 0x100);
  prefix.copy(b, 0);
  h.copy(b, at);
  return b;
}

test('ISO, RVZ, WIA, WBFS and CISO: the game id from the disc header', () => {
  const h = header('SB4E01');
  const rvz = place(Buffer.from('RVZ\u0001', 'latin1'), 0x58, h);
  const wia = place(Buffer.from('WIA\u0001', 'latin1'), 0x58, h);
  const wbfsHead = Buffer.alloc(16); wbfsHead.write('WBFS', 0, 'latin1'); wbfsHead[8] = 9;
  const wbfs = place(wbfsHead, 0x200, h);
  const ciso = place(Buffer.from('CISO', 'latin1'), 0x8000, h);
  for (const [buf, format] of [[h, 'ISO'], [rvz, 'RVZ'], [wia, 'WIA'], [wbfs, 'WBFS'], [ciso, 'CISO']]) {
    const r = disc.identify('x', reader(buf));
    assert.equal(r.ok, true, format);
    assert.equal(r.format, format);
    assert.equal(r.gameId, 'SB4E01');
    assert.equal(r.title, 'SUPER MARIO GALAXY 2');
    assert.equal(r.region, 'USA');
  }
});

test('other regions, other games and other files are refused with a reason', () => {
  let r = disc.identify('x', reader(header('SB4P01')));
  assert.equal(r.ok, false);
  assert.match(r.reason, /Europe.*USA version/);
  r = disc.identify('x', reader(header('RMGE01', 'SUPER MARIO GALAXY')));
  assert.match(r.reason, /SUPER MARIO GALAXY, not Super Mario Galaxy 2/);
  r = disc.identify('x', reader(header('SB4E01', '', false)));
  assert.match(r.reason, /not a Wii game/);
  assert.match(disc.identify('/no/such/file.iso').reason, /Could not read/);
});

// Literals, a short back reference and a long one (third byte), as Nintendo's encoder writes them.
const YAZ0 = Buffer.concat([
  Buffer.from('Yaz0', 'latin1'), Buffer.from([0, 0, 0, 26]), Buffer.alloc(8),
  Buffer.from([0b11110000]), Buffer.from('abcd', 'latin1'), Buffer.from([0x20, 0x03]), Buffer.from([0x00, 0x03, 0x00]),
]);

test('Yaz0', () => {
  assert.equal(disc.yaz0Decompress(YAZ0).toString('latin1'), 'abcd'.repeat(6) + 'ab');
  const plain = Buffer.from('not compressed');
  assert.equal(disc.yaz0Decompress(plain), plain);
});

test('Yaz0 matches tools/rarc.py', () => {
  let python;
  try {
    python = execFileSync(process.platform === 'win32' ? 'python' : 'python3', ['-I', '-c',
      'import sys; sys.path.insert(0, sys.argv[1]); import rarc; sys.stdout.write(rarc.yaz0_decompress(bytes.fromhex(sys.argv[2])).hex())',
      path.join(__dirname, '..', '..', 'tools'), YAZ0.toString('hex')], { encoding: 'utf8' });
  } catch {
    return; // no Python here: the test above covers it
  }
  assert.equal(python, disc.yaz0Decompress(YAZ0).toString('hex'));
});
