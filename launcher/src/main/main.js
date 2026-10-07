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
const { GameRunner } = require('./runner');
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

async function play(instId) {
  if (runner.busy) return { ok: false, error: 'The game is already running.' };
  const inst = instOf(instId);
  const pf = check(inst.id);
  if (!pf.ok) return { ok: false, error: `${pf.reason.label}: ${pf.reason.detail}. ${pf.reason.fix}` };
  const plan = buildPlan({ root: pf.root, paths: paths(), inst, javaHome: pf.java.home, dolphinBin: pf.dolphinBin });
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
