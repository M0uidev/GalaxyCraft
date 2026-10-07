'use strict';
// The launcher's own state: its settings and the installations (profiles), kept as one JSON file
// in Electron's userData folder. Everything read from the file is normalized, so a file from an
// older or newer launcher, or edited by hand, never breaks it.

const ACCENTS = {
  grass: '#3c8527',
  starbit: '#2f9fd8',
  luma: '#e0a91b',
  redstone: '#c0392b',
  amethyst: '#8e5bd6',
  gold: '#d6a400',
};

const BACKGROUNDS = ['planet', 'nebula', 'night', 'sunrise'];
const ICONS = ['grass', 'planet', 'star', 'dirt', 'stone', 'diamond', 'tnt', 'ice', 'mushroom', 'crafting'];
const ON_PLAY = ['keep', 'hide', 'close'];

const DEFAULT_ID = 'default';

function defaultInstallation() {
  return {
    id: DEFAULT_ID,
    name: 'Super Minecraft Galaxy',
    icon: 'planet',
    gameDir: '', // empty: <data>/minecraft, the folder tools/gxplay.sh uses
    javaHome: '', // empty: found by the launcher
    dualCore: true,
    fullscreen: false,
    gradleArgs: '',
    dolphinArgs: '',
    created: '2026-10-07T00:00:00.000Z',
    lastPlayed: null,
  };
}

function defaultState() {
  return {
    version: 1,
    settings: {
      gameRoot: '', // empty: found by the launcher
      onPlay: 'keep',
      showLogOnPlay: false,
      accent: 'grass',
      customAccent: '',
      background: 'planet',
      customBackground: '',
      playSound: true,
      checkUpdates: true,
      animations: true,
      recentSkins: [],
    },
    selected: DEFAULT_ID,
    installations: [defaultInstallation()],
  };
}

const str = (v, fallback, max = 260) => (typeof v === 'string' ? v.slice(0, max) : fallback);
const bool = (v, fallback) => (typeof v === 'boolean' ? v : fallback);
const oneOf = (v, list, fallback) => (list.includes(v) ? v : fallback);
const isColor = (v) => typeof v === 'string' && /^#[0-9a-fA-F]{6}$/.test(v);

function normalizeInstallation(raw, fallbackId) {
  const d = defaultInstallation();
  const r = raw && typeof raw === 'object' ? raw : {};
  return {
    id: str(r.id, fallbackId, 64).replace(/[^a-zA-Z0-9_-]/g, '') || fallbackId,
    name: str(r.name, 'Installation', 48).trim() || 'Installation',
    icon: oneOf(r.icon, ICONS, d.icon),
    gameDir: str(r.gameDir, ''),
    javaHome: str(r.javaHome, ''),
    dualCore: bool(r.dualCore, d.dualCore),
    fullscreen: bool(r.fullscreen, d.fullscreen),
    gradleArgs: str(r.gradleArgs, '', 1000),
    dolphinArgs: str(r.dolphinArgs, '', 1000),
    created: str(r.created, new Date().toISOString(), 40),
    lastPlayed: typeof r.lastPlayed === 'string' ? r.lastPlayed : null,
  };
}

function normalize(raw) {
  const d = defaultState();
  const r = raw && typeof raw === 'object' ? raw : {};
  const s = r.settings && typeof r.settings === 'object' ? r.settings : {};
  const settings = {
    gameRoot: str(s.gameRoot, ''),
    onPlay: oneOf(s.onPlay, ON_PLAY, d.settings.onPlay),
    showLogOnPlay: bool(s.showLogOnPlay, d.settings.showLogOnPlay),
    accent: s.accent === 'custom' || (typeof s.accent === 'string' && Object.hasOwn(ACCENTS, s.accent)) ? s.accent : d.settings.accent,
    customAccent: isColor(s.customAccent) ? s.customAccent : '',
    background: s.background === 'custom' || BACKGROUNDS.includes(s.background) ? s.background : d.settings.background,
    customBackground: str(s.customBackground, '', 1000),
    playSound: bool(s.playSound, d.settings.playSound),
    checkUpdates: bool(s.checkUpdates, d.settings.checkUpdates),
    animations: bool(s.animations, d.settings.animations),
    recentSkins: (Array.isArray(s.recentSkins) ? s.recentSkins : [])
      .filter((n) => typeof n === 'string' && /^[A-Za-z0-9_]{1,16}$/.test(n)).slice(0, 8),
  };
  if (settings.accent === 'custom' && !settings.customAccent) settings.accent = d.settings.accent;
  if (settings.background === 'custom' && !settings.customBackground) settings.background = d.settings.background;

  const seen = new Set();
  const installations = [];
  for (const [i, inst] of (Array.isArray(r.installations) ? r.installations : []).entries()) {
    const n = normalizeInstallation(inst, `installation-${i}`);
    if (seen.has(n.id)) continue;
    seen.add(n.id);
    installations.push(n);
  }
  if (!installations.length) installations.push(defaultInstallation());
  const selected = installations.some((x) => x.id === r.selected) ? r.selected : installations[0].id;
  return { version: 1, settings, selected, installations };
}

function newId(state, base) {
  const slug = String(base || 'installation').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
    .slice(0, 32) || 'installation';
  let id = slug;
  for (let i = 2; state.installations.some((x) => x.id === id); i++) id = `${slug}-${i}`;
  return id;
}

/** Adds or replaces an installation; a new one gets an id from its name. Returns the new state. */
function saveInstallation(state, inst) {
  const exists = inst.id && state.installations.some((x) => x.id === inst.id);
  const id = exists ? inst.id : newId(state, inst.name);
  const n = normalizeInstallation({ ...inst, id }, id);
  const installations = exists
    ? state.installations.map((x) => (x.id === id ? n : x))
    : [...state.installations, n];
  return { ...state, installations, selected: exists ? state.selected : id };
}

function duplicateInstallation(state, id) {
  const src = state.installations.find((x) => x.id === id);
  if (!src) return state;
  const copy = { ...src, id: undefined, name: `${src.name} (copy)`.slice(0, 48), created: new Date().toISOString(), lastPlayed: null };
  return saveInstallation(state, copy);
}

function deleteInstallation(state, id) {
  if (state.installations.length <= 1) return state; // there is always one to play
  const installations = state.installations.filter((x) => x.id !== id);
  const selected = state.selected === id ? installations[0].id : state.selected;
  return { ...state, installations, selected };
}

/** The game directory of an installation: its own, or one per installation in the data folder. */
function gameDirOf(inst, paths) {
  if (inst.gameDir) return inst.gameDir;
  return inst.id === DEFAULT_ID ? paths.defaultGameDir : paths.path.join(paths.installationsDir, inst.id);
}

module.exports = {
  ACCENTS, BACKGROUNDS, ICONS, ON_PLAY, DEFAULT_ID,
  defaultState, defaultInstallation, normalize, normalizeInstallation,
  saveInstallation, duplicateInstallation, deleteInstallation, gameDirOf,
};
