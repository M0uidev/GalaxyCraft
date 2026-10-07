'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const delta = require('../src/core/delta');

function edited(source) {
  // An archive with one file replaced: the start and end kept, a new middle, a moved block.
  const middle = crypto.randomBytes(40000);
  return Buffer.concat([source.subarray(0, 300000), middle, source.subarray(500000, 520000), source.subarray(350000)]);
}

test('a patch rebuilds the file exactly, and holds only what is new', () => {
  const source = crypto.randomBytes(1 << 20);
  const target = edited(source);
  const patch = delta.encode(source, target);
  assert.ok(patch.length < 45000, `patch is ${patch.length} bytes`);
  assert.deepEqual(delta.apply(source, patch), target);
  const meta = delta.info(patch);
  assert.equal(meta.sourceSize, source.length);
  assert.equal(meta.outputSize, target.length);
});

test('copies of the source never ship inside the patch', () => {
  const source = crypto.randomBytes(200000);
  const patch = delta.encode(source, Buffer.from(source));
  assert.ok(patch.length < 200, `patch is ${patch.length} bytes`);
  assert.deepEqual(delta.apply(source, patch), source);
});

test('edge cases: empty files, tiny files', () => {
  for (const [s, t] of [[Buffer.alloc(0), Buffer.from('hello')], [Buffer.from('hello'), Buffer.alloc(0)], [Buffer.from('abc'), Buffer.from('abcabc')]]) {
    assert.deepEqual(delta.apply(s, delta.encode(s, t)), t);
  }
});

test('a different disc file or a damaged patch is refused', () => {
  const source = crypto.randomBytes(50000);
  const patch = delta.encode(source, edited(Buffer.concat([source, source, source, source, source, source, source, source, source, source, source, source])).subarray(0, 60000));
  const other = Buffer.from(source); other[10] ^= 1;
  assert.throws(() => delta.apply(other, patch), /not the one the patch expects/);
  assert.throws(() => delta.apply(source, Buffer.from('nope')), /Not a GXD1 patch/);
  const damaged = Buffer.from(patch); damaged[damaged.length - 3] ^= 0xff;
  assert.throws(() => delta.apply(source, damaged));
});
