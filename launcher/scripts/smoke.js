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
  if (me == "dolphin-tool") { // extract -i ROM -s PATH -o OUT -q: from the fake disc folder next to the ROM
    string to = Path.Combine(a[6], "DATA", "files", a[4].Replace('/', Path.DirectorySeparatorChar));
    Directory.CreateDirectory(Path.GetDirectoryName(to));
    File.Copy(a[2] + ".files" + Path.DirectorySeparatorChar + a[4].Replace('/', Path.DirectorySeparatorChar), to, true);
    return; }
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
  if (!process.env.SMOKE_EXE) await playerMode(tmp, errors);
  fs.rmSync(tmp, { recursive: true, force: true });
  if (errors.length) throw new Error(`The page reported errors:\n${errors.join('\n')}`);
  console.log(`smoke: ok, screenshots in ${SHOTS}`);
}

// ---- a player: the released game, installed and played -------------------------------------

async function playerMode(tmp, errors) {
  const http = require('node:http');
  const crypto = require('node:crypto');
  const tar = require('tar');
  const delta = require('../src/core/delta');
  const step = (s) => console.log(`smoke: player: ${s}`);
  const dir = path.join(tmp, 'player');
  fs.mkdirSync(dir, { recursive: true });

  // The player's disc (a header, and the one file the patch needs, beside it for the fake tool).
  const rom = path.join(dir, 'Super Mario Galaxy 2.iso');
  const head = Buffer.alloc(0x400);
  head.write('SB4E01', 0, 'latin1'); head.writeUInt32BE(0x5d1c9ea3, 0x18); head.write('SUPER MARIO GALAXY 2', 0x20);
  fs.writeFileSync(rom, head);
  const discArc = crypto.randomBytes(30000);
  fs.mkdirSync(`${rom}.files/ObjectData`, { recursive: true });
  fs.writeFileSync(`${rom}.files/ObjectData/Mario.arc`, discArc);

  // A release: module (with its disc patch), Dolphin for Linux (scripts), the mod.
  const mod = path.join(dir, 'module');
  fs.mkdirSync(path.join(mod, 'patches', 'ObjectData'), { recursive: true });
  fs.mkdirSync(path.join(mod, 'dolphin-play'), { recursive: true });
  fs.writeFileSync(path.join(mod, 'galaxycraft.xml'), '<wiidisc/>');
  fs.writeFileSync(path.join(mod, 'dolphin-play', 'Dolphin.ini'), '[Core]\n');
  fs.writeFileSync(path.join(mod, 'patches', 'ObjectData', 'Mario.arc.gxd'), delta.encode(discArc, Buffer.concat([discArc, Buffer.from('steve')])));
  fs.writeFileSync(path.join(mod, 'patches.json'), JSON.stringify([{ out: 'ObjectData/Mario.arc', disc: 'ObjectData/Mario.arc', yaz0: false, patch: 'patches/ObjectData/Mario.arc.gxd' }]));
  const dol = path.join(dir, 'dolphin');
  fs.mkdirSync(dol, { recursive: true });
  const names = WIN ? { exe: 'Dolphin.exe', tool: 'dolphin-tool.exe' } : { exe: 'dolphin-emu', tool: 'dolphin-tool' };
  let java;
  if (WIN) {
    const fake = compileFakeExe(dir);
    if (!fake) { console.log('smoke: player: no C# compiler, skipped'); return; }
    fs.copyFileSync(fake, path.join(dol, names.exe));
    fs.copyFileSync(fake, path.join(dol, names.tool));
    java = path.join(dir, 'java.exe');
    fs.copyFileSync(fake, java);
  } else {
    fs.writeFileSync(path.join(dol, names.exe), '#!/bin/sh\necho "fake dolphin $GALAXYCRAFT_BOOT $*"\nsleep 3\n', { mode: 0o755 });
    fs.writeFileSync(path.join(dol, names.tool), '#!/bin/sh\nmkdir -p "$7/DATA/files/$(dirname "$5")"\ncp "$3.files/$5" "$7/DATA/files/$5"\n', { mode: 0o755 });
    java = path.join(dir, 'java');
    fs.writeFileSync(java, '#!/bin/sh\necho "fake minecraft: $*"\nwhile true; do sleep 1; done\n', { mode: 0o755 });
  }
  const files = {};
  await tar.c({ gzip: true, file: path.join(dir, 'module-0.2.0.tar.gz'), cwd: mod }, ['.']);
  await tar.c({ gzip: true, file: path.join(dir, 'dolphin-linux-x64-0.2.0.tar.gz'), cwd: dol }, ['.']);
  fs.writeFileSync(path.join(dir, 'galaxycraft-0.2.0.jar'), 'jar');
  for (const f of ['module-0.2.0.tar.gz', 'dolphin-linux-x64-0.2.0.tar.gz', 'galaxycraft-0.2.0.jar']) files[f] = fs.readFileSync(path.join(dir, f));
  const server = http.createServer((req, res) => {
    const name = req.url.slice(1);
    const body = name === 'game.json' ? Buffer.from(JSON.stringify(manifest)) : files[name];
    if (!body) { res.writeHead(404); res.end(); return; }
    res.writeHead(200, { 'content-length': body.length }); res.end(body);
  });
  await new Promise((r) => server.listen(0, '127.0.0.1', r));
  const base = `http://127.0.0.1:${server.address().port}`;
  const entry = (f) => ({ file: f, url: `${base}/${f}`, sha256: crypto.createHash('sha256').update(files[f]).digest('hex'), size: files[f].length });
  const manifest = { format: 1, version: '0.2.0', minecraft: { version: '26.3', fabricLoader: '0.19.5' },
    module: entry('module-0.2.0.tar.gz'), mods: [entry('galaxycraft-0.2.0.jar')],
    dolphin: { [`${process.platform}-${process.arch}`]: { ...entry('dolphin-linux-x64-0.2.0.tar.gz'), ...names } } };

  const userData = path.join(dir, 'userData');
  fs.mkdirSync(userData, { recursive: true });
  fs.writeFileSync(path.join(userData, 'launcher.json'), JSON.stringify({ settings: { playFrom: 'release', rom } }));
  const env = { ...process.env, GXL_USER_DATA: userData, GXL_OFFLINE: '1', GXC_DATA_DIR: path.join(dir, 'data'),
    GXL_GAME_MANIFEST: `${base}/game.json`, GXL_TEST_ACCOUNT: JSON.stringify({ name: 'Tester', uuid: '0123456789abcdef0123456789abcdef' }),
    GXL_TEST_MINECRAFT: java };
  delete env.ELECTRON_RUN_AS_NODE;
  const app = await electron.launch({ executablePath: require('electron'), args: [APP, ...(WIN ? [] : ['--no-sandbox'])], env });
  const page = await app.firstWindow();
  page.on('pageerror', (e) => errors.push(`player pageerror: ${e.message}`));
  await page.setViewportSize({ width: 1280, height: 780 });
  await page.waitForSelector('body[data-ready="1"]');
  const shot = (name) => page.screenshot({ path: path.join(SHOTS, `${name}.png`) });

  step('INSTALL');
  await page.waitForFunction(() => document.querySelector('#play').textContent === 'INSTALL', null, { timeout: 15000 });
  assert.equal(await page.textContent('#account-name'), 'Tester');
  await page.waitForTimeout(300);
  await shot('20-player-install');
  await page.click('#play');
  await page.waitForFunction(() => document.querySelector('#play').textContent === 'PLAY', null, { timeout: 30000 });
  await shot('21-player-ready');
  assert.ok(fs.existsSync(path.join(dir, 'data', 'game', '0.2.0', 'module', 'ObjectData', 'Mario.arc')), 'disc file made');

  step('PLAY');
  await page.click('#play');
  await page.waitForFunction(() => document.querySelector('#play').textContent === 'STOP', null, { timeout: 15000 });
  await page.click('#log-btn');
  await page.waitForFunction(() => /fake minecraft/.test(document.querySelector('#log').textContent), null, { timeout: 15000 });
  await shot('22-player-playing');
  const log = await page.textContent('#log');
  assert.match(log, /fake dolphin space -u .* -e .*game[\\/]0\.2\.0[\\/]galaxycraft\.json/);
  assert.match(log, /--username Tester/);
  assert.match(log, /-Dgalaxycraft\.hidden=true/);
  assert.match(log, /--accessToken \*{8}/);
  // The launcher's own lines never show the token (the fake Java echoing its arguments does).
  const launcherLines = log.split('\n').filter((l) => l.startsWith('[Launcher]')).join('\n');
  assert.match(launcherLines, /--accessToken \*{8}/);
  assert.doesNotMatch(launcherLines, /--accessToken test/);
  assert.ok(fs.existsSync(path.join(dir, 'data', 'minecraft', 'mods', 'galaxycraft-0.2.0.jar')), 'mod in the game folder');
  const desc = JSON.parse(fs.readFileSync(path.join(dir, 'data', 'game', '0.2.0', 'galaxycraft.json'), 'utf8'));
  assert.equal(desc['base-file'], rom);
  await page.waitForFunction(() => document.querySelector('#play').textContent === 'PLAY', null, { timeout: 30000 });
  await app.close();

  await officialMode({ dir, rom, errors, step });
  server.close();
}

// ---- the same player, with Minecraft from the official Minecraft Launcher ------------------

async function officialMode({ dir, rom, errors, step }) {
  const { spawn } = require('node:child_process');
  step('Minecraft Launcher: PLAY opens it, its Play starts the game');
  // The player's Minecraft Launcher: its folder (with an installation of their own) and a stand-in for it.
  const home = path.join(dir, 'home');
  const mcDir = WIN ? path.join(home, 'AppData', 'Roaming', '.minecraft') : path.join(home, '.minecraft');
  fs.mkdirSync(mcDir, { recursive: true });
  fs.writeFileSync(path.join(mcDir, 'launcher_profiles.json'), JSON.stringify({ profiles: { mine: { name: 'Vanilla', type: 'custom' } }, version: 3 }));
  // Fabric's version as Fabric's servers give it (kept, so nothing is fetched).
  const fabricId = 'fabric-loader-0.19.5-26.3';
  const kept = path.join(dir, 'data', 'minecraft-files', 'versions', fabricId, `${fabricId}.json`);
  fs.mkdirSync(path.dirname(kept), { recursive: true });
  fs.writeFileSync(kept, JSON.stringify({ id: fabricId, inheritsFrom: '26.3' }));
  const opened = path.join(dir, 'official-opened');
  let fakeLauncher;
  if (WIN) {
    fakeLauncher = path.join(dir, 'MinecraftLauncher.exe'); // the stand-in (as Dolphin: up for 3 s)
    fs.copyFileSync(path.join(dir, 'Fake.exe'), fakeLauncher);
  } else {
    fakeLauncher = path.join(dir, 'minecraft-launcher');
    fs.writeFileSync(fakeLauncher, `#!/bin/sh\necho opened > '${opened}'\n`, { mode: 0o755 });
  }

  const userData = path.join(dir, 'userData-official');
  fs.mkdirSync(userData, { recursive: true });
  fs.writeFileSync(path.join(userData, 'launcher.json'), JSON.stringify({ settings: { playFrom: 'release', minecraftFrom: 'official', rom } }));
  const env = { ...process.env, GXL_USER_DATA: userData, GXL_OFFLINE: '1', GXC_DATA_DIR: path.join(dir, 'data'),
    GXL_MINECRAFT_LAUNCHER: fakeLauncher, HOME: home, USERPROFILE: home, APPDATA: path.join(home, 'AppData', 'Roaming') };
  delete env.ELECTRON_RUN_AS_NODE;
  delete env.GXL_TEST_ACCOUNT;
  delete env.GXL_GAME_MANIFEST; // offline: the installed game is played
  const app = await electron.launch({ executablePath: require('electron'), args: [APP, ...(WIN ? [] : ['--no-sandbox'])], env });
  const page = await app.firstWindow();
  page.on('pageerror', (e) => errors.push(`official pageerror: ${e.message}`));
  await page.setViewportSize({ width: 1280, height: 780 });
  await page.waitForSelector('body[data-ready="1"]');

  // Installed already (by the player above), and no account here: PLAY, not SIGN IN.
  await page.waitForFunction(() => document.querySelector('#play').textContent === 'PLAY', null, { timeout: 15000 });
  assert.equal(await page.textContent('#account-name'), 'Minecraft Launcher');
  assert.match(await page.textContent('#play-status'), /Minecraft Launcher/);
  await page.click('#play');
  await page.waitForFunction(() => /press Play in the Minecraft Launcher/.test(document.querySelector('#play-status').textContent), null, { timeout: 15000 });
  await page.screenshot({ path: path.join(SHOTS, '23-official-waiting.png') });
  const profiles = JSON.parse(fs.readFileSync(path.join(mcDir, 'launcher_profiles.json'), 'utf8'));
  assert.equal(profiles.profiles.mine.name, 'Vanilla');
  const ours = profiles.profiles['super-minecraft-galaxy'];
  assert.equal(ours.lastVersionId, fabricId);
  assert.equal(ours.gameDir, path.join(dir, 'data', 'minecraft'));
  assert.match(ours.javaArgs, /-Dgalaxycraft\.hidden=true -Dgalaxycraft\.startDolphin=true/);
  assert.ok(fs.existsSync(path.join(dir, 'data', 'play.json')), 'play.json for the mod');
  assert.ok(fs.existsSync(path.join(mcDir, 'versions', fabricId, `${fabricId}.json`)), 'Fabric in the Minecraft Launcher');
  assert.ok(fs.existsSync(path.join(dir, 'data', 'minecraft', 'mods', 'galaxycraft-0.2.0.jar')), 'mod in the game folder');

  // The player presses Play there: a Minecraft with our installation's arguments shows up, and later closes.
  const minecraft = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)', '--', '-Dgalaxycraft.hidden=true'], { stdio: 'ignore' });
  await page.waitForFunction(() => /Playing/.test(document.querySelector('#play-status').textContent), null, { timeout: 15000 });
  minecraft.kill();
  await page.waitForFunction(() => document.querySelector('#play').textContent === 'PLAY', null, { timeout: 30000 });
  await page.click('#log-btn');
  const log = await page.textContent('#log');
  assert.match(log, /Opened the Minecraft Launcher/);
  assert.match(log, /Minecraft is running/);
  assert.match(log, /Minecraft ended/);
  assert.doesNotMatch(log, /fake dolphin|fake minecraft/); // nothing started by this launcher
  if (!WIN) assert.ok(fs.existsSync(opened), 'the Minecraft Launcher was opened');
  await app.close();
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
