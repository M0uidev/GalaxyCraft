'use strict';
// Downloads files the way a launcher must: several at once, each checked against its hash,
// written to a temporary name then moved, retried, with progress. A file already there with the
// right size (and hash, unless `quick`) is not downloaded again.
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

function hashFile(file, algo) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash(algo);
    fs.createReadStream(file).on('data', (d) => h.update(d)).on('error', reject).on('end', () => resolve(h.digest('hex')));
  });
}

/** Whether an item is already on disk as it should be. */
async function present(item, { verify = 'full' } = {}) {
  let st;
  try { st = fs.statSync(item.path); } catch { return false; }
  if (!st.isFile()) return false;
  if (item.size != null && st.size !== item.size) return false;
  if (verify === 'size' || item.quick) return true;
  if (item.sha256) return (await hashFile(item.path, 'sha256')) === item.sha256;
  if (item.sha1) return (await hashFile(item.path, 'sha1')) === item.sha1;
  return item.size != null;
}

async function fetchOne(item, fetchFn, onBytes) {
  const res = await fetchFn(item.url);
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${item.url}`);
  fs.mkdirSync(path.dirname(item.path), { recursive: true });
  const tmp = `${item.path}.part`;
  const out = fs.createWriteStream(tmp);
  const h1 = crypto.createHash('sha1');
  const h256 = crypto.createHash('sha256');
  let size = 0;
  try {
    const reader = res.body.getReader();
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      const chunk = Buffer.from(value);
      size += chunk.length;
      h1.update(chunk);
      h256.update(chunk);
      onBytes(chunk.length);
      if (!out.write(chunk)) await new Promise((r) => out.once('drain', r));
    }
    await new Promise((resolve, reject) => out.end((e) => (e ? reject(e) : resolve())));
  } catch (e) {
    out.destroy();
    fs.rmSync(tmp, { force: true });
    throw e;
  }
  const bad = (item.size != null && size !== item.size) ? `size ${size}, expected ${item.size}`
    : (item.sha1 && h1.digest('hex') !== item.sha1) ? 'SHA-1 differs'
      : (item.sha256 && h256.digest('hex') !== item.sha256) ? 'SHA-256 differs' : null;
  if (bad) {
    fs.rmSync(tmp, { force: true });
    throw new Error(`${path.basename(item.path)}: ${bad}`);
  }
  fs.renameSync(tmp, item.path);
  if (item.executable && process.platform !== 'win32') fs.chmodSync(item.path, 0o755);
}

/**
 * Downloads every item ({ url, path, sha1?, sha256?, size?, quick?, executable? }).
 * onProgress({ done, total, bytes, totalBytes, file }). Resolves to { downloaded, skipped }.
 */
async function downloadAll(items, { fetchFn = globalThis.fetch, concurrency = 8, retries = 3, verify = 'full',
  onProgress = () => {}, signal } = {}) {
  const totalBytes = items.reduce((n, i) => n + (i.size || 0), 0);
  let bytes = 0;
  let done = 0;
  let downloaded = 0;
  let skipped = 0;
  const queue = [...items];
  const report = (file) => onProgress({ done, total: items.length, bytes, totalBytes, file });
  async function worker() {
    for (;;) {
      if (signal && signal.aborted) throw new Error('Cancelled');
      const item = queue.shift();
      if (!item) return;
      if (await present(item, { verify })) {
        skipped++;
        bytes += item.size || 0;
      } else {
        let got = 0;
        for (let attempt = 1; ; attempt++) {
          try {
            await fetchOne(item, fetchFn, (n) => { got += n; bytes += n; });
            break;
          } catch (e) {
            bytes -= got;
            got = 0;
            if (attempt >= retries) throw new Error(`Download failed: ${e.message}`);
            await new Promise((r) => setTimeout(r, 500 * attempt));
          }
        }
        downloaded++;
      }
      done++;
      report(item.path);
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length || 1) }, worker));
  report(null);
  return { downloaded, skipped };
}

/** GETs JSON (with retries). */
async function fetchJson(url, fetchFn = globalThis.fetch, retries = 3) {
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetchFn(url);
      if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
      return await res.json();
    } catch (e) {
      if (attempt >= retries) throw e;
      await new Promise((r) => setTimeout(r, 500 * attempt));
    }
  }
}

module.exports = { downloadAll, fetchJson, present, hashFile };
