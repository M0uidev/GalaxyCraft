'use strict';
// A fake file system for the pure modules: a set of paths (files and folders).
const path = require('node:path');

function fakeFs(files = {}, platform = 'linux') {
  const p = platform === 'win32' ? path.win32 : path.posix;
  const all = new Map(Object.entries(files));
  for (const f of [...all.keys()]) {
    for (let d = p.dirname(f); d && d !== p.dirname(d); d = p.dirname(d)) if (!all.has(d)) all.set(d, null);
  }
  return {
    exists: (x) => all.has(x),
    list: (dir) => {
      if (!all.has(dir)) throw new Error('ENOENT ' + dir);
      return [...all.keys()].filter((k) => p.dirname(k) === dir && k !== dir).map((k) => p.basename(k));
    },
    read: (x) => {
      const v = all.get(x);
      if (typeof v !== 'string') throw new Error('ENOENT ' + x);
      return v;
    },
  };
}

module.exports = { fakeFs };
