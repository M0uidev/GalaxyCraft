'use strict';
// Opens the real launcher (Electron + the page) with Playwright and goes through it: every tab
// and page, a new installation with game settings, a skin, the theme, and on Linux and macOS a
// PLAY of a fake game (stand-ins for Dolphin and Java) from start to stop. Runs on Linux and on
// Windows in CI, from source and packaged. Screenshots go to smoke-shots/.
//   npm run smoke                                   (Linux without a display: xvfb-run -a npm run smoke)
//   SMOKE_EXE=dist/linux-unpacked/super-minecraft-galaxy-launcher npm run smoke   (a packaged build)
const { _electron: electron } = require('playwright-core');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const APP = path.join(__dirname, '..');
const SHOTS = path.join(APP, 'smoke-shots');
const WIN = process.platform === 'win32';

// A stand-in for Dolphin and Java on Windows, compiled with the .NET Framework's csc (on every
// Windows): named Dolphin.exe it runs 3 s, named java.exe until it is stopped.
const FAKE_CS = `using System; using System.IO; using System.Threading;
class Fake { static void Main(string[] a) {
  string me = Path.GetFileNameWithoutExtension(Environment.GetCommandLineArgs()[0]).ToLowerInvariant();
  string args = string.Join(" ", a);
  if (me == "java") { Console.WriteLine("fake minecraft: " + args); Console.Out.Flush(); while (true) Thread.Sleep(1000); }
  Console.WriteLine("fake dolphin " + Environment.GetEnvironmentVariable("GALAXYCRAFT_BOOT") + " " + args); Console.Out.Flush();
  Thread.Sleep(3000); Console.WriteLine("fake dolphin closes"); } }`;

function compileFakeExe(dir) {
  const { execFileSync } = require('node:child_process');
  const windir = process.env.WINDIR || 'C:\\Windows';
  const csc = ['Framework64', 'Framework'].map((f) => path.join(windir, 'Microsoft.NET', f, 'v4.0.30319', 'csc.exe'))
    .find((c) => fs.existsSync(c));
  if (!csc) return null;
  const src = path.join(dir, 'Fake.cs');
  const exe = path.join(dir, 'Fake.exe');
  fs.writeFileSync(src, FAKE_CS);
  execFileSync(csc, ['/nologo', `/out:${exe}`, src], { stdio: 'inherit' });
  return exe;
}

function fakeGame(dir) {
  // A folder the launcher takes for a built game: the protocol, the mod, Gradle's wrapper jar,
  // the module, a "Dolphin" that runs for 3 s, and a "JDK 25" whose java runs until stopped.
  const w = (p, text = '', mode) => {
    fs.mkdirSync(path.dirname(p), { recursive: true });
    fs.writeFileSync(p, text);
    if (mode) fs.chmodSync(p, mode);
  };
  const root = path.join(dir, 'GalaxyCraft');
  const jdk = path.join(dir, 'jdk-25');
  w(path.join(root, 'protocol', 'galaxycraft_protocol.h'));
  w(path.join(root, 'fabric', 'build.gradle'));
  w(path.join(root, 'fabric', 'gradle', 'wrapper', 'gradle-wrapper.jar'));
  w(path.join(root, 'syati', 'build', 'galaxycraft.json'), '{}');
  w(path.join(root, 'tools', 'dolphin-play', 'Dolphin.ini'), '[Core]\n');
  w(path.join(root, 'ROADMAP.md'), '## Done\n| When | Feature | Commit |\n|---|---|---|\n| 2026-10-07 | Smoke test feature | |\n');
  w(path.join(jdk, 'release'), 'JAVA_VERSION="25.0.1"\n');
  if (WIN) {
    const exe = compileFakeExe(dir);
    if (!exe) return null;
    fs.mkdirSync(path.join(root, 'dolphin', 'build', 'Binaries'), { recursive: true });
    fs.mkdirSync(path.join(jdk, 'bin'), { recursive: true });
    fs.copyFileSync(exe, path.join(root, 'dolphin', 'build', 'Binaries', 'Dolphin.exe'));
    fs.copyFileSync(exe, path.join(jdk, 'bin', 'java.exe'));
  } else {
    w(path.join(root, 'dolphin', 'build', 'Binaries', 'dolphin-emu'),
      '#!/bin/sh\necho "fake dolphin $GALAXYCRAFT_BOOT $*"\nsleep 3\necho "fake dolphin closes"\n', 0o755);
    w(path.join(jdk, 'bin', 'java'), '#!/bin/sh\necho "fake minecraft: $*"\nwhile true; do sleep 1; done\n', 0o755);
  }
  return { root, jdk };
}

/** The app to open: this folder with Electron, or a packaged build ($SMOKE_EXE). */
function target() {
  if (process.env.SMOKE_EXE) return { executablePath: path.resolve(process.env.SMOKE_EXE), args: [] };
  return { executablePath: require('electron'), args: [APP] };
}

async function main() {
  fs.rmSync(SHOTS, { recursive: true, force: true });
  fs.mkdirSync(SHOTS, { recursive: true });
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'gxl-smoke-'));
  const userData = path.join(tmp, 'userData');
  const dataDir = path.join(tmp, 'data');
  const fake = fakeGame(tmp);
  if (!fake) console.log('smoke: no C# compiler, PLAY is not tried');

  const env = { ...process.env, GXL_USER_DATA: userData, GXL_OFFLINE: '1', GXC_DATA_DIR: dataDir, ELECTRON_ENABLE_LOGGING: '1' };
  delete env.ELECTRON_RUN_AS_NODE;
  if (fake) env.GXC_ROOT = fake.root;
  const { executablePath, args } = target();
  if (process.platform === 'linux') args.push('--no-sandbox');
  console.log(`smoke: ${executablePath}`);
  const app = await electron.launch({ executablePath, args, env });
  const errors = [];
  const page = await app.firstWindow();
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  page.on('console', (m) => { if (m.type() === 'error' && !/mc-heads\.net|ERR_|net::/.test(m.text())) errors.push(`console: ${m.text()}`); });
  await page.setViewportSize({ width: 1280, height: 780 });
  await page.waitForSelector('body[data-ready="1"]', { timeout: 30000 });
  const shot = (name) => page.screenshot({ path: path.join(SHOTS, `${name}.png`) });
  const step = (s) => console.log(`smoke: ${s}`);

  step('play tab');
  await page.waitForFunction(() => !/Checking/.test(document.querySelector('#play-status').textContent));
  await page.waitForTimeout(400);
  await shot('01-play');

  step('installations: a new one with game settings');
  await page.click('[data-tab=installations]');
  await page.click('#inst-new');
  await page.fill('#ed-name', 'Creative Builds');
  await page.click('.icon-opt[data-icon=diamond]');
  if (fake) {
    await page.click('details.more:last-of-type summary');
    await page.fill('#ed-java', fake.jdk);
  }
  await shot('02-new-installation');
  await page.click('[data-save]');
  await page.waitForSelector('.inst[data-id=creative-builds]');
  await page.click('.inst[data-id=creative-builds] [data-act=edit]');
  await page.waitForSelector('#gs-blockDistance');
  await page.selectOption('#gs-movement', 'MINECRAFT');
  await page.$eval('#gs-blockDistance', (r) => { r.value = '128'; r.dispatchEvent(new Event('input')); });
  await shot('03-edit-installation');
  await page.click('[data-save]');
  await page.waitForSelector('.modal-back', { state: 'detached' });
  const props = fs.readFileSync(path.join(dataDir, 'installations', 'creative-builds', 'config', 'galaxycraft.properties'), 'utf8');
  assert.match(props, /^movement=MINECRAFT$/m);
  assert.match(props, /^blockDistance=128$/m);
  await shot('04-installations');

  step('skins');
  await page.click('[data-tab=skins]');
  await page.fill('#skin-name', 'jeb_');
  await page.click('#skin-save');
  await page.waitForFunction(() => document.querySelector('#account-name').textContent === 'jeb_');
  assert.match(fs.readFileSync(path.join(dataDir, 'installations', 'creative-builds', 'config', 'galaxycraft.properties'), 'utf8'), /^skin=jeb_$/m);
  await shot('05-skins');

  step('patch notes, news, settings');
  await page.click('[data-tab=notes]');
  await page.waitForSelector('.note');
  await shot('06-patch-notes');
  await page.click('.nav-item[data-page=news]');
  await page.waitForSelector('.post');
  await shot('07-news');
  await page.click('.nav-item[data-page=settings]');
  await page.click('[data-accent=starbit]');
  await page.click('[data-bg=nebula]');
  await page.waitForTimeout(200);
  await shot('08-settings');
  const saved = JSON.parse(fs.readFileSync(path.join(userData, 'launcher.json'), 'utf8'));
  assert.equal(saved.settings.accent, 'starbit');
  assert.equal(saved.settings.background, 'nebula');
  assert.equal(saved.selected, 'creative-builds');
  assert.deepEqual(saved.settings.recentSkins, ['jeb_']);

  if (fake) {
    step('PLAY a fake game, then it closes by itself');
    await page.click('.nav-item[data-page=game]');
    await page.click('[data-tab=play]');
    await page.waitForFunction(() => !document.querySelector('#play').disabled, null, { timeout: 10000 });
    await shot('09-ready');
    await page.click('#play');
    await page.waitForFunction(() => document.querySelector('#play').textContent === 'STOP', null, { timeout: 15000 });
    await page.click('#log-btn');
    await page.waitForFunction(() => /fake minecraft/.test(document.querySelector('#log').textContent), null, { timeout: 15000 });
    await shot('10-playing');
    // Dolphin closes after 3 s: Minecraft is stopped with it.
    await page.waitForFunction(() => document.querySelector('#play').textContent === 'PLAY', null, { timeout: 30000 });
    const log = await page.textContent('#log');
    assert.match(log, /fake dolphin space -u /);
    assert.match(log, /-PgalaxycraftGameDir=.*creative-builds/);
    assert.match(log, /Dolphin ended/);
    assert.ok(fs.existsSync(path.join(dataDir, 'dolphin', 'Config', 'Dolphin.ini')), 'Dolphin\'s folder seeded');
    await shot('11-after');
  }

  await app.close();
  fs.rmSync(tmp, { recursive: true, force: true });
  if (errors.length) throw new Error(`The page reported errors:\n${errors.join('\n')}`);
  console.log(`smoke: ok, screenshots in ${SHOTS}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
