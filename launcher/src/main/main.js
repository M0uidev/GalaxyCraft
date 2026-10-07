'use strict';
// The launcher's main process: its window, its saved state, PLAY, news and updates. The page
// (src/renderer) talks to it only through the bridge in preload.js.
const { app, BrowserWindow, ipcMain, dialog, shell, net, nativeTheme } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { pathToFileURL } = require('node:url');

if (process.env.GXL_USER_DATA) app.setPath('userData', path.resolve(process.env.GXL_USER_DATA));

const store = require('../core/store');
const gamesettings = require('../core/gamesettings');
const notes = require('../core/notes');
const { platformPaths } = require('../core/paths');
const { preflight } = require('../core/preflight');
const { buildPlan } = require('../core/launchplan');
const gamepack = require('../core/gamepack');
const minecraft = require('../core/minecraft');
const disc = require('../core/disc');
const { playerState } = require('../core/playstate');
const { GameRunner } = require('./runner');
const { Installer } = require('./installer');
const { Accounts } = require('./accounts');
const updater = require('./updater');

const OFFLINE = process.env.GXL_OFFLINE === '1';
const APP_DIR = path.join(__dirname, '..', '..');
const CONTENT = path.join(APP_DIR, 'content');
const REPO_RAW = 'https://raw.githubusercontent.com/M0uidev/GalaxyCraft/master';
const LOG_KEEP = 5000;

let win = null;
let state = null;
let quitWhenGameEnds = false;
const runner = new GameRunner();
const logBuffer = [];
let accounts = null;
let installer = null;
let latest = null; // the newest release's game.json (parsed), an Error, or null before the first check
let installing = null; // { version } while installing

function config() {
  let c = {};
  try { c = JSON.parse(fs.readFileSync(path.join(CONTENT, 'config.json'), 'utf8')); } catch { /* defaults */ }
  return { msaClientId: process.env.GXL_MSA_CLIENT_ID || c.msaClientId || '' };
}

/** Where PLAY takes the game from: a game folder (developers) or the released game (players). */
function mode() {
  const s = state.settings;
  if (s.playFrom === 'folder' || s.playFrom === 'release') return s.playFrom;
  return !app.isPackaged || s.gameRoot ? 'folder' : 'release';
}

function romInfo() {
  const rom = state.settings.rom;
  if (!rom) return null;
  return { path: rom, check: fs.existsSync(rom) ? disc.identify(rom) : { ok: false, reason: `The file is gone: ${rom}` } };
}

// ---- state ---------------------------------------------------------------------------------

const stateFile = () => path.join(app.getPath('userData'), 'launcher.json');

function loadState() {
  try {
    state = store.normalize(JSON.parse(fs.readFileSync(stateFile(), 'utf8')));
  } catch {
    state = store.defaultState();
  }
}

function saveState() {
  const file = stateFile();
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp`;
  fs.writeFileSync(tmp, JSON.stringify(state, null, 2));
  fs.renameSync(tmp, file);
}

function setState(next) {
  state = store.normalize(next);
  saveState();
  return state;
}

const paths = () => platformPaths();
const instOf = (id) => state.installations.find((x) => x.id === id) || state.installations[0];

const realFs = {
  exists: (p) => fs.existsSync(p),
  list: (d) => fs.readdirSync(d),
  read: (p) => fs.readFileSync(p, 'utf8'),
};

function check(instId) {
  return preflight({ settings: state.settings, inst: instOf(instId), appDir: app.isPackaged ? null : APP_DIR }, realFs);
}

function send(channel, payload) {
  if (win && !win.isDestroyed()) win.webContents.send(channel, payload);
}

// ---- content -------------------------------------------------------------------------------

async function fetchText(url, timeoutMs = 6000) {
  if (OFFLINE) throw new Error('offline');
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeoutMs);
  try {
    const res = await net.fetch(url, { signal: ctl.signal, cache: 'no-cache' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.text();
  } finally {
    clearTimeout(t);
  }
}

async function news() {
  try {
    return { source: 'online', news: notes.normalizeNews(JSON.parse(await fetchText(`${REPO_RAW}/launcher/content/news.json`))) };
  } catch {
    try {
      return { source: 'bundled', news: notes.normalizeNews(JSON.parse(fs.readFileSync(path.join(CONTENT, 'news.json'), 'utf8'))) };
    } catch {
      return { source: 'none', news: [] };
    }
  }
}

/** The roadmap: GitHub's master (what is released), else the game folder's, else the bundled notes. */
async function roadmap() {
  try {
    const text = await fetchText(`${REPO_RAW}/ROADMAP.md`);
    return { source: 'online', notes: notes.patchNotesFromRoadmap(text), upcoming: notes.upcomingFromRoadmap(text) };
  } catch { /* offline */ }
  const root = check(state.selected).root;
  if (root) {
    try {
      const text = fs.readFileSync(path.join(root, 'ROADMAP.md'), 'utf8');
      return { source: 'game folder', notes: notes.patchNotesFromRoadmap(text), upcoming: notes.upcomingFromRoadmap(text) };
    } catch { /* not there */ }
  }
  try {
    const bundled = JSON.parse(fs.readFileSync(path.join(CONTENT, 'patch-notes.json'), 'utf8'));
    return { source: 'bundled', notes: bundled.notes || [], upcoming: bundled.upcoming || [] };
  } catch {
    return { source: 'none', notes: [], upcoming: [] };
  }
}

// ---- the game ------------------------------------------------------------------------------

function settingsFile(inst) {
  return path.join(store.gameDirOf(inst, paths()), 'config', 'galaxycraft.properties');
}

/** Everything the Play tab shows for an installation, in either mode. */
function status(instId) {
  const m = mode();
  const rom = romInfo();
  const base = { mode: m, rom, account: accounts.current(), signInReady: accounts.available,
    installing, latestVersion: latest && !(latest instanceof Error) ? latest.version : null };
  if (m === 'folder') return { ...base, folder: check(instId) };
  const installed = installer.installed();
  return { ...base, installedVersion: installed ? installed.version : null,
    player: playerState({ account: base.account, signInReady: accounts.available, rom, installed, latest, system: gamepack.systemKey() }) };
}

async function checkLatest() {
  if (OFFLINE && !process.env.GXL_GAME_MANIFEST) { latest = new Error('offline'); return latest; }
  latest = await installer.latest();
  send('game:latest', { version: latest instanceof Error ? null : latest.version, error: latest instanceof Error ? latest.message : null });
  return latest;
}

async function installLatest() {
  if (installing) return { ok: false, error: 'Already installing.' };
  const rom = romInfo();
  if (!rom || !rom.check.ok) return { ok: false, error: 'Choose your Super Mario Galaxy 2 first.' };
  const target = latest && !(latest instanceof Error) ? latest : await checkLatest();
  if (target instanceof Error) return { ok: false, error: target.message };
  installing = { version: target.version };
  send('install:state', installing);
  try {
    await installer.install(target, rom.path);
    return { ok: true };
  } catch (e) {
    runner.log('Launcher', `Install failed: ${e.message}`, 'stderr');
    return { ok: false, error: e.message };
  } finally {
    installing = null;
    send('install:state', null);
  }
}

/** PLAY for players: the installed release, the player's disc and Minecraft account. */
async function playRelease(inst) {
  const st = status(inst.id).player;
  if (st.action !== 'play' && st.action !== 'update') return { ok: false, error: st.detail };
  const sessionInfo = await accounts.session();
  const rom = romInfo();
  const i = await installer.prepare(rom.path);
  const ready = await installer.minecraft(i.manifest);
  const gameDir = store.gameDirOf(inst, paths());
  installer.syncMods(i, gameDir);
  const mcCommand = minecraft.command({
    version: ready.version, java: ready.java, dirs: { ...ready.dirs, game: gameDir }, auth: sessionInfo,
    jvmArgs: gamepack.minecraftJvmArgs(i.manifest, inst), launcher: { name: 'super-minecraft-galaxy', version: app.getVersion() },
  });
  return gamepack.playerPlan({ paths: paths(), inst, lay: i.lay, dolphinExe: path.join(i.lay.dolphin, i.sys.exe), mcCommand });
}

/** PLAY from a game folder (developers): as tools/gxplay.sh, with the chosen disc if there is one. */
function playFolder(inst) {
  const pf = check(inst.id);
  if (!pf.ok) throw new Error(`${pf.reason.label}: ${pf.reason.detail}. ${pf.reason.fix}`);
  let descriptor = null;
  const rom = romInfo();
  if (rom && rom.check.ok) {
    descriptor = path.join(paths().dataDir, 'dev-galaxycraft.json');
    fs.mkdirSync(path.dirname(descriptor), { recursive: true });
    fs.writeFileSync(descriptor, JSON.stringify(gamepack.devDescriptor(rom.path, pf.root), null, 2));
  }
  return buildPlan({ root: pf.root, paths: paths(), inst, javaHome: pf.java.home, dolphinBin: pf.dolphinBin, descriptor });
}

async function play(instId) {
  if (runner.busy) return { ok: false, error: 'The game is already running.' };
  const inst = instOf(instId);
  let plan;
  try {
    plan = mode() === 'folder' ? playFolder(inst) : await playRelease(inst);
    if (plan && plan.ok === false) return plan;
  } catch (e) {
    runner.log('Launcher', e.message, 'stderr');
    return { ok: false, error: e.message, code: e.code };
  }
  setState({ ...state, selected: inst.id,
    installations: state.installations.map((x) => (x.id === inst.id ? { ...x, lastPlayed: new Date().toISOString() } : x)) });
  const started = await runner.start(plan, { installation: inst.id });
  if (started && win) {
    if (state.settings.onPlay === 'hide' || state.settings.onPlay === 'close') win.hide();
    if (state.settings.onPlay === 'close') quitWhenGameEnds = true;
  }
  return { ok: started, error: started ? null : runner.info.error || 'The game did not start; see the log.' };
}

runner.on('log', (entry) => {
  logBuffer.push(entry);
  if (logBuffer.length > LOG_KEEP) logBuffer.splice(0, logBuffer.length - LOG_KEEP);
  send('game:log', entry);
});

runner.on('state', (info) => {
  send('game:state', info);
  if (info.state !== 'stopped') return;
  if (quitWhenGameEnds) { app.quit(); return; }
  if (win && !win.isVisible()) { win.show(); win.focus(); }
});

// ---- window --------------------------------------------------------------------------------

function createWindow() {
  win = new BrowserWindow({
    width: 1280,
    height: 780,
    minWidth: 960,
    minHeight: 600,
    show: false,
    backgroundColor: '#141418',
    title: 'Super Minecraft Galaxy Launcher',
    icon: path.join(APP_DIR, 'build', 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: false,
    },
  });
  win.setMenu(null);
  win.once('ready-to-show', () => win.show());
  win.loadFile(path.join(APP_DIR, 'src', 'renderer', 'index.html'));
  // Links open in the browser, never in the launcher.
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', (e) => e.preventDefault());
  // Closing the window while the game runs: the launcher stays in the background to close
  // both sides together, then quits.
  win.on('close', (e) => {
    if (runner.busy && !quitWhenGameEnds) {
      e.preventDefault();
      quitWhenGameEnds = true;
      win.hide();
    }
  });
  win.on('closed', () => { win = null; });
}

// ---- bridge --------------------------------------------------------------------------------

function handle(channel, fn) {
  ipcMain.handle(channel, async (_e, ...args) => fn(...args));
}

handle('launcher:get', () => ({
  state,
  version: app.getVersion(),
  platform: process.platform,
  packaged: app.isPackaged,
  offline: OFFLINE,
  dataDir: paths().dataDir,
  mode: mode(),
  game: runner.info.state ? runner.info : { state: 'idle' },
  update: updater.status(),
}));

handle('settings:set', (patch) => setState({ ...state, settings: { ...state.settings, ...patch } }));
handle('inst:select', (id) => setState({ ...state, selected: id }));
handle('inst:save', (inst) => setState(store.saveInstallation(state, inst)));
handle('inst:duplicate', (id) => setState(store.duplicateInstallation(state, id)));
handle('inst:delete', (id) => setState(store.deleteInstallation(state, id)));
handle('inst:gameDir', (id) => store.gameDirOf(instOf(id), paths()));

handle('preflight', (id) => check(id));
handle('status', (id) => status(id));
handle('game:checkLatest', async () => { await checkLatest(); return status(state.selected); });
handle('game:install', () => installLatest());
handle('account:signIn', async () => {
  try { return { ok: true, account: await accounts.signIn() }; } catch (e) { return { ok: false, error: e.message, code: e.code }; }
});
handle('account:signOut', () => { accounts.signOut(); return accounts.current(); });
handle('rom:choose', async () => {
  const r = await dialog.showOpenDialog(win, {
    title: 'Choose your Super Mario Galaxy 2 (USA)', properties: ['openFile'],
    filters: [{ name: 'Wii games', extensions: ['iso', 'rvz', 'wbfs', 'wia', 'ciso', 'gcz'] }, { name: 'All files', extensions: ['*'] }],
  });
  if (r.canceled) return null;
  const check = disc.identify(r.filePaths[0]);
  if (check.ok) setState({ ...state, settings: { ...state.settings, rom: r.filePaths[0] } });
  return { path: r.filePaths[0], check };
});
handle('game:play', (id) => play(id));
handle('game:stop', () => runner.stop());
handle('game:log', () => logBuffer);

handle('gamesettings:get', (id) => {
  let text = '';
  try { text = fs.readFileSync(settingsFile(instOf(id)), 'utf8'); } catch { /* none yet: defaults */ }
  return { schema: gamesettings.SCHEMA, values: gamesettings.read(text), file: settingsFile(instOf(id)) };
});
handle('gamesettings:set', (id, values) => {
  const file = settingsFile(instOf(id));
  let old = '';
  try { old = fs.readFileSync(file, 'utf8'); } catch { /* new file */ }
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, gamesettings.write(old, values));
  return gamesettings.read(fs.readFileSync(file, 'utf8'));
});

handle('content:news', () => news());
handle('content:roadmap', () => roadmap());

handle('dialog:folder', async (title, defaultPath) => {
  const r = await dialog.showOpenDialog(win, { title, defaultPath: defaultPath || undefined, properties: ['openDirectory', 'createDirectory'] });
  return r.canceled ? null : r.filePaths[0];
});
handle('dialog:background', async () => {
  const r = await dialog.showOpenDialog(win, {
    title: 'Choose a background', properties: ['openFile'],
    filters: [{ name: 'Images', extensions: ['png', 'jpg', 'jpeg', 'webp', 'gif'] }],
  });
  if (r.canceled) return null;
  // Copied into the launcher's folder, so it stays when the original moves.
  const dir = path.join(app.getPath('userData'), 'backgrounds');
  fs.mkdirSync(dir, { recursive: true });
  const to = path.join(dir, `background-${Date.now()}${path.extname(r.filePaths[0]).toLowerCase()}`);
  fs.copyFileSync(r.filePaths[0], to);
  for (const old of fs.readdirSync(dir)) if (path.join(dir, old) !== to) fs.rmSync(path.join(dir, old), { force: true });
  return pathToFileURL(to).href;
});

handle('open:url', (url) => { if (/^https:\/\//.test(url)) shell.openExternal(url); });
handle('open:path', async (p) => {
  if (typeof p !== 'string' || !p) return 'No folder';
  fs.mkdirSync(p, { recursive: true });
  return shell.openPath(p);
});

handle('update:check', () => updater.check(true));
handle('update:install', () => updater.install());

// ---- app -----------------------------------------------------------------------------------

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (!win) return;
    if (!win.isVisible()) win.show();
    if (win.isMinimized()) win.restore();
    win.focus();
  });

  app.whenReady().then(() => {
    nativeTheme.themeSource = 'dark';
    loadState();
    createWindow();
    accounts = new Accounts({ file: path.join(app.getPath('userData'), 'accounts.json'), clientId: config().msaClientId,
      fetchFn: (url, opts) => net.fetch(url, opts), parent: win });
    let ensureMinecraftFn;
    if (process.env.GXL_TEST_MINECRAFT && !app.isPackaged) {
      // Smoke test from source: a stand-in Java instead of Minecraft's download.
      const mcv = require('../core/minecraft');
      const fx = (f) => JSON.parse(fs.readFileSync(path.join(APP_DIR, 'test', 'fixtures', f), 'utf8'));
      ensureMinecraftFn = async (dirs) => ({ version: mcv.merge(fx('fabric-profile.json'), fx('version-26.3.json')),
        java: process.env.GXL_TEST_MINECRAFT, dirs: { ...dirs, natives: dirs.natives } });
    }
    installer = new Installer({ paths: paths(), fetchFn: (url, opts) => net.fetch(url, opts),
      manifestUrl: process.env.GXL_GAME_MANIFEST || gamepack.LATEST_URL, ensureMinecraftFn });
    installer.on('log', (l) => runner.log('Launcher', l));
    let lastProgress = 0;
    installer.on('progress', (p) => {
      const now = Date.now();
      if (now - lastProgress < 100 && p.done !== p.total) return;
      lastProgress = now;
      send('install:progress', p);
    });
    checkLatest();
    setInterval(checkLatest, 30 * 60 * 1000).unref();
    updater.init({
      enabled: app.isPackaged && !OFFLINE && state.settings.checkUpdates,
      onStatus: (s) => send('update:state', s),
    });
  });

  app.on('window-all-closed', () => {
    if (!runner.busy) app.quit();
  });

  app.on('before-quit', (e) => {
    // Quitting with the game open stops it first (both sides), as closing gxplay.sh does.
    if (runner.busy) {
      e.preventDefault();
      quitWhenGameEnds = true;
      runner.stop();
    }
  });
}
