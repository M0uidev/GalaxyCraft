'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const notes = require('../src/core/notes');

const ROADMAP = `# R
## Now
| Feature | Status | Notes |
|---|---|---|
| Swimming | now | in the \`water\` |
## Next
| Feature | Status | Notes |
|---|---|---|
| (nothing in progress) | | |
| Space stations | next | flat |
## Done
| When | Feature | Commit |
|---|---|---|
| 2026-10-02 | Mario mode | |
| 2026-10-06 | Signs \`a|b\` | 1a73a2b |
| 2026-10-06 | **Beds** | 40c23e9 |
---
## Inbox
| 2026-10-07 | not done | |
`;

test('Done table: newest day first, commits left out', () => {
  assert.deepEqual(notes.patchNotesFromRoadmap(ROADMAP), [
    { date: '2026-10-06', items: ['Signs `a|b`', '**Beds**'] },
    { date: '2026-10-02', items: ['Mario mode'] },
  ]);
  assert.deepEqual(notes.patchNotesFromRoadmap('nothing'), []);
});

test('Now and Next', () => {
  assert.deepEqual(notes.upcomingFromRoadmap(ROADMAP), [
    { feature: 'Swimming', status: 'now', notes: 'in the `water`' },
    { feature: 'Space stations', status: 'next', notes: 'flat' },
  ]);
});

test('the real ROADMAP.md parses', () => {
  const text = fs.readFileSync(path.join(__dirname, '..', '..', 'ROADMAP.md'), 'utf8');
  const days = notes.patchNotesFromRoadmap(text);
  assert.ok(days.length >= 3);
  assert.ok(days.every((d) => /^\d{4}-\d{2}-\d{2}$/.test(d.date) && d.items.length));
  assert.ok(notes.upcomingFromRoadmap(text).length >= 1);
});

test('news: broken entries out, pinned first, only https links', () => {
  const n = notes.normalizeNews({ news: [
    { title: 'Old', date: '2026-01-01', link: 'javascript:alert(1)', tag: 'weird' },
    { title: 'New', date: '2026-10-07', image: 'https://x/y.png' },
    { title: 'Pinned', date: '2025-01-01', pinned: true },
    { title: '', date: '2026-10-07' },
    { title: 'No date' },
    'junk',
  ] });
  assert.deepEqual(n.map((x) => x.title), ['Pinned', 'New', 'Old']);
  assert.equal(n[2].link, '');
  assert.equal(n[2].tag, 'news');
  assert.equal(n[1].image, 'https://x/y.png');
});

test('the bundled news.json is valid', () => {
  const raw = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'content', 'news.json'), 'utf8'));
  assert.equal(notes.normalizeNews(raw).length, raw.news.length);
});
