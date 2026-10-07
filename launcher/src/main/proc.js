'use strict';
// Processes on each system: starting one so its whole tree can be stopped later, stopping a
// tree, and stopping leftovers by their command line (tools/gxplay.sh's pkill -f).
const { spawn, execFile } = require('node:child_process');

const WIN = process.platform === 'win32';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * Starts proc ({ cmd, args, cwd, env, hideWindow }); onLine(stream, line) gets its output line by
 * line. On Linux it leads its own process group, so stopping it stops its children too.
 */
function start(proc, onLine) {
  const child = spawn(proc.cmd, proc.args, {
    cwd: proc.cwd,
    env: proc.env,
    detached: !WIN,
    windowsHide: !!proc.hideWindow,
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  for (const stream of ['stdout', 'stderr']) {
    let buf = '';
    child[stream].setEncoding('utf8');
    child[stream].on('data', (chunk) => {
      buf += chunk;
      let i;
      while ((i = buf.search(/\r?\n/)) >= 0) {
        onLine(stream, buf.slice(0, i));
        buf = buf.slice(i + (buf[i] === '\r' ? 2 : 1));
      }
      if (buf.length > 64 * 1024) { onLine(stream, buf); buf = ''; }
    });
    child[stream].on('end', () => { if (buf) onLine(stream, buf); buf = ''; });
  }
  return child;
}

function alive(pid) {
  try { process.kill(pid, 0); return true; } catch (e) { return e.code === 'EPERM'; }
}

function run(cmd, args) {
  return new Promise((resolve) => {
    execFile(cmd, args, { windowsHide: true, timeout: 30000 }, (err, stdout) => resolve({ err, stdout: String(stdout || '') }));
  });
}

/** Stops a started process and everything it started: TERM, then KILL after graceMs. */
async function stopTree(child, graceMs = 5000) {
  if (!child || !child.pid) return;
  const exited = child.exitCode !== null || child.signalCode !== null;
  if (WIN) {
    if (exited) return;
    // Asked first (WM_CLOSE: Dolphin closes as from its window), forced after graceMs.
    await run('taskkill', ['/pid', String(child.pid), '/T']);
    for (let waited = 0; waited < graceMs; waited += 100) {
      if (child.exitCode !== null || child.signalCode !== null) return;
      await sleep(100);
    }
    await run('taskkill', ['/pid', String(child.pid), '/T', '/F']);
    return;
  }
  // Its group may outlive it (children it started): stopped even when it already ended.
  const group = -child.pid;
  try { process.kill(group, 'SIGTERM'); } catch { return; }
  for (let waited = 0; waited < graceMs; waited += 100) {
    if (!alive(group)) return;
    await sleep(100);
  }
  try { process.kill(group, 'SIGKILL'); } catch { /* gone */ }
}

/**
 * Stops every process whose command line holds `match` (a hidden Minecraft left over by Gradle's
 * daemon would still be linked to the game through the shared memory). Resolves to how many
 * were found. The game test ignores TERM, so KILL follows on Linux.
 */
async function stopMatching(match) {
  if (WIN) {
    const like = match.replace(/'/g, "''").replace(/([[\]*?`])/g, '`$1');
    const script = '$n = 0; Get-CimInstance Win32_Process | Where-Object { $_.ProcessId -ne $PID -and '
      + `$_.CommandLine -like '*${like}*' } | ForEach-Object { $n++; `
      + '& taskkill /pid $_.ProcessId /T /F | Out-Null }; Write-Output $n';
    const { stdout } = await run('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', script]);
    return parseInt(stdout.trim(), 10) || 0;
  }
  const found = async () => (await run('pgrep', ['-f', match])).stdout.split('\n').filter(Boolean)
    .filter((pid) => Number(pid) !== process.pid).length;
  const n = await found();
  if (!n) return 0;
  await run('pkill', ['-f', match]);
  for (let i = 0; i < 50; i++) {
    if (!(await found())) return n;
    await sleep(100);
  }
  await run('pkill', ['-KILL', '-f', match]);
  return n;
}

/** Resolves once the child has exited (or after ms, whichever comes first). */
function waitExit(child, ms) {
  if (!child || child.exitCode !== null || child.signalCode !== null || !child.pid) return Promise.resolve();
  return new Promise((resolve) => {
    const t = setTimeout(resolve, ms);
    child.once('exit', () => { clearTimeout(t); resolve(); });
  });
}

module.exports = { start, stopTree, stopMatching, waitExit, alive };
