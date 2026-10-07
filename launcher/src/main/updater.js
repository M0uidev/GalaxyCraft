'use strict';
// Updates from GitHub Releases (the launcher-v* tags CI publishes): checked at start and every
// few hours, downloaded in the background, installed when the player restarts the launcher.
// Only in packaged builds; a failure (offline, a .deb) just says so in Settings.

let autoUpdater = null;
let current = { state: 'disabled' };
let notify = () => {};

function set(s) {
  current = s;
  notify(s);
}

function init({ enabled, onStatus }) {
  notify = onStatus || notify;
  if (!enabled) return;
  try {
    ({ autoUpdater } = require('electron-updater'));
  } catch (e) {
    set({ state: 'error', message: e.message });
    return;
  }
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.on('checking-for-update', () => set({ state: 'checking' }));
  autoUpdater.on('update-not-available', () => set({ state: 'latest' }));
  autoUpdater.on('update-available', (i) => set({ state: 'downloading', version: i.version, percent: 0 }));
  autoUpdater.on('download-progress', (p) => set({ ...current, state: 'downloading', percent: Math.round(p.percent) }));
  autoUpdater.on('update-downloaded', (i) => set({ state: 'ready', version: i.version }));
  autoUpdater.on('error', (e) => set({ state: 'error', message: String((e && e.message) || e).split('\n')[0] }));
  set({ state: 'idle' });
  check(false);
  setInterval(() => check(false), 4 * 60 * 60 * 1000).unref();
}

async function check() {
  if (!autoUpdater) return current;
  try {
    await autoUpdater.checkForUpdates();
  } catch (e) {
    set({ state: 'error', message: String(e.message || e).split('\n')[0] });
  }
  return current;
}

function install() {
  if (autoUpdater && current.state === 'ready') autoUpdater.quitAndInstall();
}

const status = () => current;

module.exports = { init, check, install, status };
