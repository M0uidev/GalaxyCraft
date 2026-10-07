'use strict';
// Real processes (node itself), so this runs the same on Linux and on Windows' CI.
const test = require('node:test');
const assert = require('node:assert/strict');
const { GameRunner } = require('../src/main/runner');
const proc = require('../src/main/proc');

const node = (code, name, extra = []) => ({ name, cmd: process.execPath, args: ['-e', code, ...extra], cwd: __dirname, env: process.env });
const waitFor = (emitter, state) => new Promise((resolve) => {
  const on = (info) => { if (info.state === state) { emitter.off('state', on); resolve(info); } };
  emitter.on('state', on);
});

test('when one side ends, the other is stopped', { timeout: 30000 }, async () => {
  const runner = new GameRunner({ seedFn: () => {}, stopMatching: async () => 0 });
  const logs = [];
  runner.on('log', (l) => logs.push(l));
  const stopped = waitFor(runner, 'stopped');
  const ok = await runner.start({
    processes: [
      node('console.log("dolphin up"); setTimeout(() => process.exit(0), 500)', 'Dolphin'),
      node('console.log("minecraft up"); console.error("warn"); setInterval(() => {}, 1000)', 'Minecraft'),
    ],
    stopMatch: null,
  }, { installation: 'default' });
  assert.equal(ok, true);
  const info = await stopped;
  assert.equal(info.by, 'Dolphin');
  assert.equal(info.crashed, false);
  assert.ok(logs.some((l) => l.source === 'Dolphin' && l.line === 'dolphin up'));
  assert.ok(logs.some((l) => l.source === 'Minecraft' && l.stream === 'stderr' && l.line === 'warn'));
  assert.ok(logs.some((l) => l.source === 'Launcher' && /Minecraft ended/.test(l.line)));
  assert.equal(runner.busy, false);
});

test('a side failing at once is a crash; a missing program is reported', { timeout: 30000 }, async () => {
  const runner = new GameRunner({ seedFn: () => {}, stopMatching: async () => 0 });
  let stopped = waitFor(runner, 'stopped');
  await runner.start({ processes: [node('process.exit(3)', 'Dolphin'), node('setInterval(() => {}, 1000)', 'Minecraft')] });
  let info = await stopped;
  assert.equal(info.crashed, true);
  assert.equal(info.code, 3);

  stopped = waitFor(runner, 'stopped');
  await runner.start({ processes: [{ name: 'Dolphin', cmd: '/no/such/dolphin-emu', args: [], cwd: __dirname, env: process.env }] });
  info = await stopped;
  assert.equal(info.crashed, true);
  assert.ok(info.error);
});

test('stop() stops both', { timeout: 30000 }, async () => {
  const runner = new GameRunner({ seedFn: () => {}, stopMatching: async () => 0 });
  await runner.start({ processes: [node('setInterval(() => {}, 1000)', 'Dolphin'), node('setInterval(() => {}, 1000)', 'Minecraft')] });
  assert.equal(runner.state, 'running');
  const pids = runner.children.map((c) => c.pid);
  await runner.stop();
  assert.equal(runner.state, 'stopped');
  await new Promise((r) => setTimeout(r, 300));
  for (const pid of pids) assert.equal(proc.alive(pid), false);
});

test('leftovers are found and stopped by their command line', { timeout: 60000 }, async () => {
  const marker = `galaxycraft.launcherTest${process.pid}=true`;
  const child = proc.start(node('setInterval(() => {}, 1000)', 'x', ["--", `-Dmarker=${marker}`]), () => {});
  await new Promise((r) => setTimeout(r, 500));
  assert.equal(await proc.stopMatching(marker), 1);
  await new Promise((r) => setTimeout(r, 500));
  assert.equal(proc.alive(child.pid), false);
  assert.equal(await proc.stopMatching(marker), 0);
});
