'use strict';
// The hash of everything the module (release/module) is built from: when the sources change,
// release/module is out of date and `npm run pack-game` makes it again. CI checks it on release.
//   node scripts/module-hash.js           prints it
//   node scripts/module-hash.js --check   fails if release/module was made from other sources
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.join(__dirname, '..', '..');
const SOURCES = ['syati/src', 'syati/riivolution', 'syati/symbols_extra.txt', 'syati/build.py', 'protocol',
  'tools/steve', 'tools/space_galaxy.py', 'tools/rarc.py', 'tools/bcsv.py', 'tools/hostexe.py', 'tools/dolphin-play'];
const SKIP = /(^|\/)(__pycache__|build|\.pytest_cache)(\/|$)|\.pyc$/;

function files(rel) {
  const abs = path.join(ROOT, rel);
  if (!fs.existsSync(abs)) return [];
  if (fs.statSync(abs).isFile()) return [rel];
  return fs.readdirSync(abs).flatMap((n) => {
    const r = `${rel}/${n}`;
    return SKIP.test(r) ? [] : files(r);
  });
}

function moduleHash() {
  const h = crypto.createHash('sha256');
  for (const rel of SOURCES.flatMap(files).sort()) {
    // Line endings as Git stores them, so a Windows checkout hashes the same.
    const text = fs.readFileSync(path.join(ROOT, rel));
    h.update(rel).update('\0').update(text.includes(0) ? text : Buffer.from(text.toString('latin1').replace(/\r\n/g, '\n'), 'latin1')).update('\0');
  }
  return h.digest('hex');
}

module.exports = { moduleHash, SOURCES, ROOT };

if (require.main === module) {
  const hash = moduleHash();
  if (process.argv.includes('--check')) {
    let made = null;
    try { made = JSON.parse(fs.readFileSync(path.join(ROOT, 'release', 'module', 'module.json'), 'utf8')).sourceHash; } catch { /* none */ }
    if (made !== hash) {
      console.error('release/module was not made from these sources: run `npm run pack-game` (in launcher/) on the machine with the toolchain and your disc, and commit release/module.');
      process.exit(1);
    }
    console.log('release/module is up to date');
  } else {
    console.log(hash);
  }
}
