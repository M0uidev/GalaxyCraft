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
  assert.equal(await proc.countMatching(marker), 1);
  assert.equal(await proc.stopMatching(marker), 1);
  await new Promise((r) => setTimeout(r, 500));
  assert.equal(proc.alive(child.pid), false);
  assert.equal(await proc.stopMatching(marker), 0);
});

test('a watched Minecraft (started by the Minecraft Launcher): its end stops Dolphin', { timeout: 30000 }, async () => {
  let running = 0;
  const looks = [];
  const runner = new GameRunner({ seedFn: () => {}, stopMatching: async () => 0, watchMs: 50,
    countMatching: async (m) => { looks.push(m); return running; } });
  const states = [];
  runner.on('state', (s) => states.push(s));
  const stopped = waitFor(runner, 'stopped');
  await runner.start({ processes: [node('setInterval(() => {}, 1000)', 'Dolphin')], stopMatch: 'm', watch: { name: 'Minecraft', match: 'm' } });
  assert.equal(runner.info.waiting, 'Minecraft');
  const dolphin = runner.children[0].pid;
  await new Promise((r) => setTimeout(r, 200)); // not up yet: Dolphin keeps waiting
  assert.equal(runner.state, 'running');
  running = 1;
  await new Promise((r) => setTimeout(r, 200));
  assert.equal(runner.info.waiting, undefined);
  running = 0;
  const info = await stopped;
  assert.equal(info.by, 'Minecraft');
  assert.equal(info.crashed, false);
  assert.ok(looks.every((m) => m === 'm'));
  await new Promise((r) => setTimeout(r, 300));
  assert.equal(proc.alive(dolphin), false);
});

test('a watched Minecraft already up is kept at start', { timeout: 30000 }, async () => {
  let stopCalls = 0;
  const runner = new GameRunner({ seedFn: () => {}, stopMatching: async () => { stopCalls++; return 0; }, watchMs: 50, countMatching: async () => 1 });
  // Nothing started here (the Minecraft Launcher's Minecraft starts Dolphin itself), no seed.
  assert.equal(await runner.start({ seed: null, processes: [], stopMatch: 'm', watch: { name: 'Minecraft', match: 'm' } }), true);
  assert.equal(stopCalls, 0);
  await runner.stop(); // closing Dolphin's side stops the leftovers then
  assert.equal(stopCalls, 1);
});

test('a program this launcher did not start is stopped with its children (the Minecraft Launcher)', { timeout: 60000 }, async () => {
  const fs = require('node:fs');
  const os = require('node:os');
  const path = require('node:path');
  const { spawn } = require('node:child_process');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'smg-programs-'));
  try {
    // Windows finds it by its program's path (a copy of node in that folder), elsewhere by its command line.
    const exe = process.platform === 'win32' ? path.join(dir, 'FakeLauncher.exe') : process.execPath;
    if (exe !== process.execPath) fs.copyFileSync(process.execPath, exe);
    const marker = `smg-fake-launcher-${process.pid}`;
    const code = `require('child_process').spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)', '--', '${marker}-child'], { stdio: 'ignore' }); setInterval(() => {}, 1000)`;
    const top = spawn(exe, ['-e', code, '--', marker], { stdio: 'ignore', detached: process.platform !== 'win32' });
    await new Promise((r) => setTimeout(r, 1500));
    const pattern = process.platform === 'win32' ? `${dir}\\*` : marker;
    assert.equal(await proc.stopPrograms([pattern], 3000), 2);
    await new Promise((r) => setTimeout(r, 500));
    assert.equal(proc.alive(top.pid), false);
    assert.equal(await proc.countMatching(`${marker}-child`), 0);
    assert.equal(await proc.stopPrograms([pattern]), 0);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
