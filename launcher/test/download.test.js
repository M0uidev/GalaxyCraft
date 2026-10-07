'use strict';
// A real HTTP server on this machine: downloads, checks, retries.
const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const os = require('node:os');
const path = require('node:path');
const { downloadAll, fetchJson } = require('../src/core/download');

const files = { '/a.bin': crypto.randomBytes(100000), '/b.bin': crypto.randomBytes(5000), '/c.json': Buffer.from('{"x":1}') };
const sha1 = (b) => crypto.createHash('sha1').update(b).digest('hex');
let flaky = 0;
let hits = 0;

function serve() {
  return new Promise((resolve) => {
    const server = http.createServer((req, res) => {
      hits++;
      if (req.url === '/flaky.bin' && flaky++ < 1) { res.writeHead(500); res.end(); return; }
      const body = files[req.url === '/flaky.bin' ? '/b.bin' : req.url];
      if (!body) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'content-length': body.length });
      res.end(body);
    });
    server.listen(0, '127.0.0.1', () => resolve(server));
  });
}

test('downloads, checks, retries, skips what is there', { timeout: 30000 }, async () => {
  const server = await serve();
  const base = `http://127.0.0.1:${server.address().port}`;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'gxl-dl-'));
  const items = [
    { url: `${base}/a.bin`, path: path.join(dir, 'x', 'a.bin'), sha1: sha1(files['/a.bin']), size: 100000 },
    { url: `${base}/flaky.bin`, path: path.join(dir, 'b.bin'), sha1: sha1(files['/b.bin']), size: 5000, executable: true },
  ];
  const progress = [];
  let r = await downloadAll(items, { onProgress: (p) => progress.push(p), concurrency: 2 });
  assert.deepEqual(r, { downloaded: 2, skipped: 0 });
  assert.deepEqual(fs.readFileSync(items[0].path), files['/a.bin']);
  assert.equal(progress.at(-1).bytes, 105000);
  assert.equal(progress.at(-1).done, 2);
  assert.equal(fs.existsSync(`${items[0].path}.part`), false);

  const before = hits;
  r = await downloadAll(items);
  assert.deepEqual(r, { downloaded: 0, skipped: 2 });
  assert.equal(hits, before);

  // A file changed on disk is fetched again.
  fs.writeFileSync(items[0].path, Buffer.alloc(100000));
  r = await downloadAll(items);
  assert.deepEqual(r, { downloaded: 1, skipped: 1 });

  // A wrong hash fails after its retries, and leaves nothing behind.
  const bad = { url: `${base}/a.bin`, path: path.join(dir, 'bad.bin'), sha1: '00', size: 100000 };
  await assert.rejects(downloadAll([bad], { retries: 2 }), /SHA-1 differs/);
  assert.equal(fs.existsSync(bad.path) || fs.existsSync(`${bad.path}.part`), false);
  await assert.rejects(downloadAll([{ url: `${base}/none`, path: path.join(dir, 'n') }], { retries: 1 }), /HTTP 404/);

  assert.deepEqual(await fetchJson(`${base}/c.json`), { x: 1 });
  server.close();
  fs.rmSync(dir, { recursive: true, force: true });
});
