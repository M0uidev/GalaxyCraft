'use strict';
// The game's soundtrack on disk: the songs of Super Mario Galaxy 2 that the default catalog
// (content/soundtrack.tsv) lists, taken from the player's own disc into one shared folder
// (<data>/soundtrack, 1 GB once, whatever the installations), and tracks.tsv beside them, the
// catalog the game reads. It starts as the default catalog; after that it is the player's: their
// rows are kept as they are, and only songs an update brings are added. The disc is read by the
// `extract` function the caller gives (the installer runs dolphin-tool).
//
// tracks.tsv: one song a line, tab separated: id, title, file, source (smg2|minecraft), mood
// (space|planet|empty), tags, enabled. Lines starting with # are notes (same format as the mod's
// music/Catalog.java).
const fs = require('node:fs');
const path = require('node:path');

const HEADER = '# id\ttitle\tfile\tsource\tmood\ttags\tenabled';
/** Where on the disc the songs are (extract's -s). */
const DISC_DIR = 'AudioRes/Stream';

/** The song lines of a catalog: { line, id, file, source } for each line with seven fields and a known source. */
function rows(text) {
  const out = [];
  for (const line of String(text).split(/\r?\n/)) {
    if (!line.trim() || line.startsWith('#')) continue;
    const f = line.split('\t');
    if (f.length < 7) continue;
    const source = f[3].trim().toLowerCase();
    if (source !== 'smg2' && source !== 'minecraft') continue;
    out.push({ line, id: f[0].trim(), file: f[2].trim(), source });
  }
  return out;
}

function readText(file) {
  try { return fs.readFileSync(file, 'utf8'); } catch { return ''; }
}

function writeAtomic(file, data) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, data);
  fs.renameSync(tmp, file);
}

/** Every file under dir by name (the disc tool keeps the folders it was asked for). */
function index(dir, into = new Map()) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) index(p, into); else into.set(e.name, p);
  }
  return into;
}

/**
 * Brings the soundtrack folder up to date. dir: where the songs go; defaults: the default
 * catalog's text; extract(outDir): puts the disc's AudioRes/Stream under outDir (async).
 * Returns { copied, missing }: how many songs were copied, and the files the disc does not have.
 */
async function install({ dir, defaults, extract, onProgress = () => {} }) {
  const wanted = rows(defaults).filter((r) => r.source === 'smg2');
  if (!wanted.length) return { copied: 0, missing: [] };
  fs.mkdirSync(dir, { recursive: true });

  const need = wanted.filter((r) => !fs.existsSync(path.join(dir, r.file)));
  let copied = 0;
  const missing = [];
  if (need.length) {
    // Beside the destination (the same disk): songs are moved in, not copied, and the peak is 1 GB, not 2.
    const work = fs.mkdtempSync(path.join(path.dirname(dir), '.songs-'));
    try {
      await extract(work);
      const found = index(work);
      for (const r of need) {
        const from = found.get(r.file);
        if (!from) { missing.push(r.file); continue; }
        const to = path.join(dir, r.file);
        fs.renameSync(from, to); // never a half-copied song under its real name
        copied++;
        onProgress({ done: copied, total: need.length });
      }
    } finally {
      fs.rmSync(work, { recursive: true, force: true });
    }
  }

  // The catalog: their rows as they are, then the default rows for songs they do not have yet
  // (only those whose file is here).
  const file = path.join(dir, 'tracks.tsv');
  const mine = rows(readText(file));
  const haveFile = new Set(mine.map((r) => r.file));
  const haveId = new Set(mine.map((r) => r.id));
  const added = wanted.filter((r) => !haveFile.has(r.file) && !haveId.has(r.id) && fs.existsSync(path.join(dir, r.file)));
  const text = `${[HEADER, ...mine.map((r) => r.line), ...added.map((r) => r.line)].join('\n')}\n`;
  if (text !== readText(file)) writeAtomic(file, text);
  return { copied, missing };
}

module.exports = { install, rows, DISC_DIR, HEADER };
