'use strict';
// Runs a plan (src/core/launchplan.js): seeds Dolphin's folder, stops leftovers, starts Dolphin
// and Minecraft, and when either ends, stops the other, as tools/gxplay.sh does. A plan may
// instead watch for a Minecraft it did not start (`plan.watch`: one the Minecraft Launcher starts,
// which starts Dolphin itself), found by its command line: when that one ends, the game ended.
//
// Events: 'state' ({ state, ... }) with state idle | starting | running | stopping | stopped,
//         'log' ({ source, stream, line, time }). While a watched side has not shown up yet,
//         'running' carries waiting: '<name>'.
const { EventEmitter } = require('node:events');
const proc = require('./proc');
const { seed } = require('../core/seed');
const { describe } = require('../core/launchplan');

/** A side that ends this soon after starting, with an error, crashed rather than was closed. */
const CRASH_WINDOW_MS = 15000;
/** How often a watched side is looked for. */
const WATCH_MS = 2500;

class GameRunner extends EventEmitter {
  constructor({ seedFn = seed, stopMatching = proc.stopMatching, countMatching = proc.countMatching, watchMs = WATCH_MS } = {}) {
    super();
    this.seedFn = seedFn;
    this.stopMatching = stopMatching;
    this.countMatching = countMatching;
    this.watchMs = watchMs;
    this.state = 'idle';
    this.children = [];
    this.startedAt = 0;
    this.info = {};
  }

  get busy() {
    return this.state === 'starting' || this.state === 'running' || this.state === 'stopping';
  }

  setState(state, extra = {}) {
    this.state = state;
    this.info = { state, ...extra };
    this.emit('state', this.info);
  }

  log(source, line, stream = 'stdout') {
    this.emit('log', { source, stream, line, time: Date.now() });
  }

  /** Starts the plan; resolves once both processes started (or failed to). */
  async start(plan, { installation } = {}) {
    if (this.busy) throw new Error('The game is already running');
    this.setState('starting', { installation });
    try {
      if (plan.seed) this.seedFn(plan, (l) => this.log('Launcher', l));
      // A watched Minecraft may already be up (started first from the Minecraft Launcher): kept.
      if (plan.stopMatch && !plan.watch) {
        const n = await this.stopMatching(plan.stopMatch);
        if (n) this.log('Launcher', `Stopped ${n} Minecraft left over from an earlier run`);
      }
      for (const l of describe(plan)) this.log('Launcher', l);
    } catch (e) {
      this.log('Launcher', `Could not prepare the game: ${e.message}`, 'stderr');
      this.setState('stopped', { installation, error: e.message });
      return false;
    }

    this.startedAt = Date.now();
    this.ended = null;
    this.plan = plan;
    this.children = [];
    for (const p of plan.processes) {
      let child;
      try {
        child = proc.start(p, (stream, line) => this.log(p.name, line, stream));
      } catch (e) {
        this.log('Launcher', `${p.name} did not start: ${e.message}`, 'stderr');
        this.finish({ source: p.name, error: e.message });
        return false;
      }
      this.children.push(child);
      child.on('error', (e) => {
        this.log('Launcher', `${p.name} did not start: ${e.message}`, 'stderr');
        this.finish({ source: p.name, error: e.message });
      });
      child.on('exit', (code, signal) => {
        this.log('Launcher', `${p.name} ended (${signal || `exit code ${code}`})`);
        this.finish({ source: p.name, code, signal });
      });
    }
    if (this.state === 'starting') {
      this.setState('running', { installation, startedAt: this.startedAt, ...(plan.watch ? { waiting: plan.watch.name } : {}) });
      if (plan.watch) this.watch(plan.watch, installation);
    }
    return this.state === 'running';
  }

  /** Looks for a side this runner did not start; once it was seen and is gone, the game ends. */
  watch(w, installation) {
    let seen = false;
    const tick = async () => {
      this.watchTimer = null;
      if (this.ended) return;
      let n;
      try { n = await this.countMatching(w.match); } catch { n = seen ? 1 : 0; } // a failed look changes nothing
      if (this.ended) return;
      if (n && !seen) {
        seen = true;
        this.log('Launcher', `${w.name} is running`);
        this.setState('running', { installation, startedAt: this.startedAt });
      } else if (!n && seen) {
        this.log('Launcher', `${w.name} ended`);
        this.finish({ source: w.name, code: 0 });
        return;
      }
      this.watchTimer = setTimeout(tick, this.watchMs);
    };
    tick();
  }

  /** One side ended: stop the other, and leftovers. */
  async finish(why) {
    if (this.ended) return this.ended;
    const installation = this.info.installation;
    const crashed = !!why.error || (why.code !== 0 && why.code !== null && Date.now() - this.startedAt < CRASH_WINDOW_MS);
    if (this.watchTimer) { clearTimeout(this.watchTimer); this.watchTimer = null; }
    this.ended = (async () => {
      this.setState('stopping', { installation });
      await Promise.all(this.children.map((c) => proc.stopTree(c)));
      // Stopped is only said once each side has really exited (taskkill returns before that).
      await Promise.all(this.children.map((c) => proc.waitExit(c, 10000)));
      if (this.plan && this.plan.stopMatch) await this.stopMatching(this.plan.stopMatch);
      this.children = [];
      const playedMs = Date.now() - this.startedAt;
      this.setState('stopped', { installation, crashed, by: why.source, code: why.code ?? null, error: why.error || null, playedMs });
    })();
    return this.ended;
  }

  /** Stops the game (both sides). */
  async stop() {
    if (!this.busy) return;
    await this.finish({ source: 'Launcher', code: 0 });
  }
}

module.exports = { GameRunner, CRASH_WINDOW_MS, WATCH_MS };
