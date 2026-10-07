'use strict';
// Bundles the patch notes (ROADMAP.md's Done, Now and Next) into content/patch-notes.json, for a
// launcher that is offline and has no game folder. Run by `npm run dist`.
const fs = require('node:fs');
const path = require('node:path');
const notes = require('../src/core/notes');

const text = fs.readFileSync(path.join(__dirname, '..', '..', 'ROADMAP.md'), 'utf8');
const out = { notes: notes.patchNotesFromRoadmap(text), upcoming: notes.upcomingFromRoadmap(text) };
fs.writeFileSync(path.join(__dirname, '..', 'content', 'patch-notes.json'), JSON.stringify(out, null, 2) + '\n');
console.log(`patch notes: ${out.notes.length} days, ${out.upcoming.length} upcoming`);
