'use strict';
// The player's own Super Mario Galaxy 2: which game a file is (from its disc header, in every
// format Dolphin reads that keeps one in plain sight), and Yaz0, the compression of the disc's
// archives.
const fs = require('node:fs');

/** The game the module is built for: Super Mario Galaxy 2, USA. */
const WANTED_ID = 'SB4E01';
const REGIONS = { E: 'USA', P: 'Europe', J: 'Japan', K: 'Korea', W: 'Taiwan' };
const WII_MAGIC = 0x5d1c9ea3;
const GC_MAGIC = 0xc2339f3d;

/** Where the 0x80-byte disc header sits in each format, by the file's first bytes. */
function headerOffset(head) {
  const magic = head.toString('latin1', 0, 4);
  if (magic === 'RVZ\u0001' || magic === 'WIA\u0001') return { format: magic.slice(0, 3), offset: 0x58 };
  if (magic === 'WBFS') return { format: 'WBFS', offset: 1 << head[8] };
  if (magic === 'CISO') return { format: 'CISO', offset: 0x8000 };
  return { format: 'ISO', offset: 0 };
}

/** What a disc header says: { gameId, title, wii, gamecube }. */
function parseHeader(h) {
  if (!h || h.length < 0x60) return null;
  const gameId = h.toString('latin1', 0, 6);
  const wii = h.readUInt32BE(0x18) === WII_MAGIC;
  const gamecube = h.readUInt32BE(0x1c) === GC_MAGIC;
  const title = h.toString('utf8', 0x20, 0x60).replace(/\0[\s\S]*$/, '').trim();
  return { gameId, title, wii, gamecube };
}

/**
 * Identifies a game file. Returns { ok, format, gameId, title, region, reason } where ok means
 * it is the Super Mario Galaxy 2 the game needs. readAt(offset, length) -> Buffer, for tests;
 * by default the file is read.
 */
function identify(file, readAt) {
  let fd = null;
  const read = readAt || ((offset, length) => {
    const b = Buffer.alloc(length);
    const n = fs.readSync(fd, b, 0, length, offset);
    return b.subarray(0, n);
  });
  try {
    if (!readAt) fd = fs.openSync(file, 'r');
    const head = read(0, 0x100);
    if (head.length < 0x60) return { ok: false, reason: 'This file is too small to be a game.' };
    const { format, offset } = headerOffset(head);
    const info = parseHeader(offset === 0 ? head : read(offset, 0x80));
    if (!info || (!info.wii && !info.gamecube)) {
      return { ok: false, format, reason: 'This is not a Wii game file (.iso, .rvz, .wbfs, .wia or .ciso).' };
    }
    const region = REGIONS[info.gameId[3]] || 'unknown region';
    const base = { format, gameId: info.gameId, title: info.title, region };
    if (info.gameId === WANTED_ID) return { ok: true, ...base };
    if (info.gameId.startsWith('SB4')) {
      return { ok: false, ...base, reason: `This is Super Mario Galaxy 2 for ${region} (${info.gameId}). The game needs the USA version (SB4E01) for now.` };
    }
    return { ok: false, ...base, reason: `This is ${info.title || info.gameId}, not Super Mario Galaxy 2.` };
  } catch (e) {
    return { ok: false, reason: `Could not read the file: ${e.message}` };
  } finally {
    if (fd !== null) fs.closeSync(fd);
  }
}

/** Yaz0 decompression (anything not starting with Yaz0 is returned as it is). */
function yaz0Decompress(src) {
  if (src.length < 16 || src.toString('latin1', 0, 4) !== 'Yaz0') return src;
  const size = src.readUInt32BE(4);
  const out = Buffer.alloc(size);
  let s = 16;
  let d = 0;
  while (d < size) {
    const code = src[s++];
    for (let bit = 7; bit >= 0 && d < size; bit--) {
      if (code & (1 << bit)) {
        out[d++] = src[s++];
      } else {
        const b1 = src[s++];
        const b2 = src[s++];
        const dist = (((b1 & 0x0f) << 8) | b2) + 1;
        let n = b1 >> 4;
        n = n === 0 ? src[s++] + 0x12 : n + 2;
        if (dist > d) throw new Error('Yaz0: a back reference before the start');
        for (let k = 0; k < n && d < size; k++, d++) out[d] = out[d - dist];
      }
    }
  }
  return out;
}

module.exports = { WANTED_ID, identify, parseHeader, headerOffset, yaz0Decompress };
