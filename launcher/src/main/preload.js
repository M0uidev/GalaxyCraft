'use strict';
// The only door between the page and the launcher: a fixed list of calls and events.
const { contextBridge, ipcRenderer } = require('electron');

const call = (channel) => (...args) => ipcRenderer.invoke(channel, ...args);
const on = (channel) => (fn) => {
  const listener = (_e, payload) => fn(payload);
  ipcRenderer.on(channel, listener);
  return () => ipcRenderer.off(channel, listener);
};

contextBridge.exposeInMainWorld('launcher', {
  get: call('launcher:get'),
  setSettings: call('settings:set'),
  selectInstallation: call('inst:select'),
  saveInstallation: call('inst:save'),
  duplicateInstallation: call('inst:duplicate'),
  deleteInstallation: call('inst:delete'),
  gameDirOf: call('inst:gameDir'),
  preflight: call('preflight'),
  status: call('status'),
  checkLatest: call('game:checkLatest'),
  install: call('game:install'),
  signIn: call('account:signIn'),
  signOut: call('account:signOut'),
  chooseRom: call('rom:choose'),
  play: call('game:play'),
  openMinecraftLauncher: call('official:open'),
  stop: call('game:stop'),
  log: call('game:log'),
  exportReport: call('report:export'),
  gameSettings: call('gamesettings:get'),
  setGameSettings: call('gamesettings:set'),
  news: call('content:news'),
  roadmap: call('content:roadmap'),
  chooseFolder: call('dialog:folder'),
  chooseBackground: call('dialog:background'),
  openUrl: call('open:url'),
  openPath: call('open:path'),
  checkUpdate: call('update:check'),
  installUpdate: call('update:install'),
  onGameState: on('game:state'),
  onGameLog: on('game:log'),
  onUpdate: on('update:state'),
  onInstallProgress: on('install:progress'),
  onInstallState: on('install:state'),
  onLatest: on('game:latest'),
});
