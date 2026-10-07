'use strict';
// Carries out a plan's seed (launchplan.js): Dolphin's folder made once from the templates and
// the player's own Dolphin, SMG2's save copied while there is none. Never overwrites anything.
const fs = require('node:fs');
const path = require('node:path');

function seed(plan, log = () => {}) {
  const s = plan.seed;
  for (const dir of s.mkdirs) fs.mkdirSync(dir, { recursive: true });

  const cfg = s.dolphinConfig;
  if (!fs.existsSync(cfg.dir)) {
    fs.mkdirSync(cfg.dir, { recursive: true });
    if (fs.existsSync(cfg.templates)) {
      for (const name of fs.readdirSync(cfg.templates)) {
        if (name.endsWith('.ini')) fs.copyFileSync(path.join(cfg.templates, name), path.join(cfg.dir, name));
      }
    }
    for (const { name, candidates } of cfg.fromUser) {
      const from = candidates.find((c) => fs.existsSync(c));
      if (from) {
        fs.copyFileSync(from, path.join(cfg.dir, name));
        log(`Dolphin: your ${name} copied from ${from}`);
      }
    }
    log(`Dolphin's folder made: ${path.dirname(cfg.dir)}`);
  }

  const save = s.smg2Save;
  if (!fs.existsSync(path.join(save.to, 'data'))) {
    const from = save.candidates.find((c) => fs.existsSync(path.join(c, 'data')));
    if (from) {
      fs.mkdirSync(save.to, { recursive: true });
      fs.cpSync(from, save.to, { recursive: true, force: false, errorOnExist: false });
      log(`SMG2's save copied from ${from}`);
    }
  }
}

module.exports = { seed };
