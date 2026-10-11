'use strict';
// The five SMG2 models the Toad ship is built from, copied once from the player's own disc into
// <data>/ship. Nothing of the disc is shipped; the mod reads these files at run time.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

/** Where on the disc the models are (extract's -s is DISC_DIR/<arc>). */
const DISC_DIR = 'ObjectData';
const ARCS = ['MiniPlayerRocket.arc', 'MarioFaceShipJet.arc', 'MarioFaceShipRudder.arc',
  'MarioFaceShipChimney.arc', 'SpaceShipStep.arc'];

function find(dir, name) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      const r = find(p, name);
      if (r) return r;
    } else if (e.name === name) return p;
  }
  return null;
}

/**
 * extract(outDir, arcs): puts those arcs (names) from the disc somewhere under outDir (async);
 * yaz0(buffer): the decompressed buffer. Returns { copied, missing }.
 */
async function install({ dir, extract, yaz0 }) {
  const work = fs.mkdtempSync(path.join(os.tmpdir(), 'smg-ship-'));
  try {
    await extract(work, ARCS);
    fs.mkdirSync(dir, { recursive: true });
    let copied = 0;
    const missing = [];
    for (const name of ARCS) {
      const from = find(work, name);
      if (!from) { missing.push(name); continue; }
      let data = fs.readFileSync(from);
      if (data.subarray(0, 4).toString() === 'Yaz0') data = yaz0(data);
      fs.writeFileSync(path.join(dir, name), data);
      copied++;
    }
    return { copied, missing };
  } finally {
    fs.rmSync(work, { recursive: true, force: true });
  }
}

module.exports = { ARCS, DISC_DIR, install };
