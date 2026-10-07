'use strict';
// The page's markdown: news come from the internet, so nothing in them may become HTML.
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

const load = () => import(pathToFileURL(path.join(__dirname, '..', 'src', 'renderer', 'md.js')).href);

test('markdown: bold, code, https links; HTML escaped', async () => {
  const md = await load();
  assert.equal(md.inline('**b** *i* `<x>`'), '<strong>b</strong> <em>i</em> <code>&lt;x&gt;</code>');
  assert.equal(md.inline('<img src=x onerror=alert(1)>'), '&lt;img src=x onerror=alert(1)&gt;');
  assert.equal(md.inline('[a](javascript:alert(1))'), '[a](javascript:alert(1))');
  assert.equal(md.inline('[a](https://x.y/"z)'), '<a href="https://x.y/&quot;z" data-external>a</a>');
  assert.equal(md.block('one\ntwo\n\n- a\n- b'), '<p>one<br>two</p><ul><li>a</li><li>b</li></ul>');
});

test('URLs inside CSS url() cannot break out', async () => {
  const md = await load();
  assert.equal(md.cssUrl("https://x/a b'(c)\".png"), 'https://x/a%20b%27%28c%29%22.png');
});
