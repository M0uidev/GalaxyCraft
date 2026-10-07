'use strict';
// Disc patches (GXD1). A file the game needs that is made from the disc (Steve in Mario's
// archives, GalaxyCraftSpace) ships as a patch against the player's own copy of that disc file:
// "copy these bytes from your file" plus the bytes that are ours. A patch alone holds nothing
// of the disc, and both ends are checked with SHA-256, so a different disc is caught.
//
//   'GXD1' | u8 version=1 | u32 source size | source sha256 | u32 output size | output sha256 |
//   raw deflate of ops: 1 COPY varint offset varint length | 2 ADD varint length bytes | 0 END
const crypto = require('node:crypto');
const zlib = require('node:zlib');

const MAGIC = 'GXD1';
const BLOCK = 16;
const sha256 = (b) => crypto.createHash('sha256').update(b).digest();

function writeVarint(out, n) {
  while (n >= 0x80) { out.push((n & 0x7f) | 0x80); n = Math.floor(n / 128); }
  out.push(n);
}

function blockHash(buf, i) {
  // FNV-1a over BLOCK bytes: plenty for files of a few MiB.
  let h = 0x811c9dc5;
  for (let k = 0; k < BLOCK; k++) h = Math.imul(h ^ buf[i + k], 16777619);
  return h >>> 0;
}

/** The patch that makes `target` from `source`. */
function encode(source, target) {
  const index = new Map();
  for (let i = 0; i + BLOCK <= source.length; i += 4) {
    const h = blockHash(source, i);
    if (!index.has(h)) index.set(h, i);
  }
  const ops = [];
  let pending = [];
  const flush = () => {
    if (!pending.length) return;
    ops.push(2); writeVarint(ops, pending.length);
    for (const b of pending) ops.push(b);
    pending = [];
  };
  let i = 0;
  while (i < target.length) {
    let best = 0;
    let bestAt = 0;
    if (i + BLOCK <= target.length) {
      const at = index.get(blockHash(target, i));
      if (at !== undefined) {
        let n = 0;
        while (at + n < source.length && i + n < target.length && source[at + n] === target[i + n]) n++;
        if (n >= BLOCK) { best = n; bestAt = at; }
      }
    }
    if (best) {
      flush();
      ops.push(1); writeVarint(ops, bestAt); writeVarint(ops, best);
      i += best;
    } else {
      pending.push(target[i++]);
    }
  }
  flush();
  ops.push(0);
  const head = Buffer.alloc(4 + 1 + 4 + 32 + 4 + 32);
  head.write(MAGIC, 0, 'latin1');
  head[4] = 1;
  head.writeUInt32LE(source.length, 5);
  sha256(source).copy(head, 9);
  head.writeUInt32LE(target.length, 41);
  sha256(target).copy(head, 45);
  return Buffer.concat([head, zlib.deflateRawSync(Buffer.from(ops), { level: 9 })]);
}

/** What a patch says about its two ends: { sourceSize, sourceSha256, outputSize, outputSha256 }. */
function info(patch) {
  if (patch.length < 77 || patch.toString('latin1', 0, 4) !== MAGIC || patch[4] !== 1) throw new Error('Not a GXD1 patch');
  return {
    sourceSize: patch.readUInt32LE(5),
    sourceSha256: patch.subarray(9, 41).toString('hex'),
    outputSize: patch.readUInt32LE(41),
    outputSha256: patch.subarray(45, 77).toString('hex'),
  };
}

/** Applies a patch to the player's file; throws if the file is not the one it was made for. */
function apply(source, patch) {
  const meta = info(patch);
  if (source.length !== meta.sourceSize || sha256(source).toString('hex') !== meta.sourceSha256) {
    throw new Error('This file from your disc is not the one the patch expects (a different or modified copy of the game?)');
  }
  const ops = zlib.inflateRawSync(patch.subarray(77));
  const out = Buffer.alloc(meta.outputSize);
  let p = 0;
  let d = 0;
  const varint = () => {
    let n = 0;
    let mul = 1;
    for (;;) {
      const b = ops[p++];
      n += (b & 0x7f) * mul;
      if (b < 0x80) return n;
      mul *= 128;
    }
  };
  for (;;) {
    const op = ops[p++];
    if (op === 0) break;
    if (op === 1) {
      const at = varint();
      const n = varint();
      if (at + n > source.length || d + n > out.length) throw new Error('Damaged patch (copy out of range)');
      source.copy(out, d, at, at + n);
      d += n;
    } else if (op === 2) {
      const n = varint();
      if (p + n > ops.length || d + n > out.length) throw new Error('Damaged patch (add out of range)');
      ops.copy(out, d, p, p + n);
      p += n;
      d += n;
    } else {
      throw new Error('Damaged patch (unknown op)');
    }
  }
  if (d !== out.length || sha256(out).toString('hex') !== meta.outputSha256) throw new Error('The patched file does not check out');
  return out;
}

module.exports = { encode, apply, info, sha256 };
