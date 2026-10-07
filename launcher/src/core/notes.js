'use strict';
// Patch notes and news. Patch notes come from ROADMAP.md's Done table (what players see, without
// the commits); news from launcher/content/news.json. Both are read from GitHub's master when
// online, so posting news or finishing a feature needs no new launcher.

const TAGS = ['news', 'update', 'dev', 'event'];

/** Splits a markdown table row into its cells (pipes inside `code` stay in their cell). */
function cells(line) {
  const out = [];
  let cur = '';
  let code = false;
  const body = line.trim().replace(/^\|/, '').replace(/\|$/, '');
  for (let i = 0; i < body.length; i++) {
    const c = body[i];
    if (c === '`') code = !code;
    if (c === '\\' && body[i + 1] === '|') { cur += '|'; i++; continue; }
    if (c === '|' && !code) { out.push(cur.trim()); cur = ''; continue; }
    cur += c;
  }
  out.push(cur.trim());
  return out;
}

/**
 * The Done table of ROADMAP.md as patch notes, newest first, grouped by day:
 * [{ date: 'YYYY-MM-DD', items: ['markdown', ...] }].
 */
function patchNotesFromRoadmap(markdown) {
  const lines = String(markdown || '').split(/\r?\n/);
  const start = lines.findIndex((l) => /^##\s+Done\s*$/i.test(l.trim()));
  if (start < 0) return [];
  const byDate = new Map();
  for (let i = start + 1; i < lines.length; i++) {
    const line = lines[i];
    if (/^##\s/.test(line) || /^---\s*$/.test(line.trim())) break;
    if (!line.trim().startsWith('|')) continue;
    const [date, feature] = cells(line);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date || '') || !feature) continue;
    if (!byDate.has(date)) byDate.set(date, []);
    byDate.get(date).push(feature);
  }
  return [...byDate.entries()]
    .sort((a, b) => (a[0] < b[0] ? 1 : a[0] > b[0] ? -1 : 0))
    .map(([date, items]) => ({ date, items }));
}

/** The "Now" and "Next" tables: what is coming, for the Play tab. [{ feature, status, notes }] */
function upcomingFromRoadmap(markdown) {
  const lines = String(markdown || '').split(/\r?\n/);
  const out = [];
  for (const section of ['Now', 'Next']) {
    const start = lines.findIndex((l) => new RegExp(`^##\\s+${section}\\s*$`, 'i').test(l.trim()));
    if (start < 0) continue;
    for (let i = start + 1; i < lines.length; i++) {
      const line = lines[i];
      if (/^##\s/.test(line) || /^---\s*$/.test(line.trim())) break;
      if (!line.trim().startsWith('|')) continue;
      const [feature, status, notes] = cells(line);
      if (!feature || /^-+$/.test(feature) || feature === 'Feature' || /^\(nothing/i.test(feature)) continue;
      out.push({ feature, status: (status || section).toLowerCase(), notes: notes || '' });
    }
  }
  return out;
}

/** News entries made safe to show, newest first; broken entries are left out. */
function normalizeNews(raw) {
  const list = Array.isArray(raw) ? raw : raw && Array.isArray(raw.news) ? raw.news : [];
  const out = [];
  for (const n of list) {
    if (!n || typeof n !== 'object') continue;
    if (typeof n.title !== 'string' || !n.title.trim()) continue;
    if (typeof n.date !== 'string' || !/^\d{4}-\d{2}-\d{2}/.test(n.date)) continue;
    const https = (u) => (typeof u === 'string' && /^https:\/\//.test(u) ? u : '');
    out.push({
      id: typeof n.id === 'string' ? n.id : `${n.date}-${n.title}`,
      title: n.title.trim().slice(0, 120),
      date: n.date.slice(0, 10),
      tag: TAGS.includes(n.tag) ? n.tag : 'news',
      body: typeof n.body === 'string' ? n.body.slice(0, 4000) : '',
      image: https(n.image),
      link: https(n.link),
      pinned: n.pinned === true,
    });
  }
  return out.sort((a, b) => (b.pinned - a.pinned) || (a.date < b.date ? 1 : a.date > b.date ? -1 : 0));
}

module.exports = { TAGS, cells, patchNotesFromRoadmap, upcomingFromRoadmap, normalizeNews };
