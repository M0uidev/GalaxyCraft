'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const props = require('../src/core/properties');

test('reads what java.util.Properties writes', () => {
  const text = '#GalaxyCraft settings\n#Mon Oct 06 10:00:00 CLT 2026\nmovement=MINECRAFT\nskin=Notch\n'
    + 'key\\ with\\ space = value\npath=C\\:\\\\Users\\\\Mo\nunicode=\\u00F1\nlong=a\\\n    b\n! comment\nempty=\n';
  const m = props.parse(text);
  assert.equal(m.get('movement'), 'MINECRAFT');
  assert.equal(m.get('skin'), 'Notch');
  assert.equal(m.get('key with space'), 'value');
  assert.equal(m.get('path'), 'C:\\Users\\Mo');
  assert.equal(m.get('unicode'), 'ñ');
  assert.equal(m.get('long'), 'ab');
  assert.equal(m.get('empty'), '');
  assert.equal(m.size, 7);
});

test('writes text that reads back the same', () => {
  const m = new Map([['a', 'x=y:z'], ['b c', ' lead'], ['d', 'ñ\ttab'], ['e', 'C:\\x']]);
  const back = props.parse(props.stringify(m, 'comment'));
  assert.deepEqual([...back], [...m]);
});
