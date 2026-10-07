// The launcher's page: Play, Installations, Skins, Patch Notes, News and Settings. It talks to
// the launcher only through window.launcher (src/main/preload.js).
import { iconUrl, loadArt, sceneOn, scenePreview, SCENES } from './art.js';
import { esc, inline, block, cssUrl } from './md.js';

const api = window.launcher;
const $ = (sel, root = document) => root.querySelector(sel);
const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const ACCENTS = {
  grass: ['Grass', '#3c8527'],
  starbit: ['Star Bit', '#2f9fd8'],
  luma: ['Luma', '#e0a91b'],
  redstone: ['Redstone', '#c0392b'],
  amethyst: ['Amethyst', '#8e5bd6'],
  gold: ['Gold', '#d6a400'],
};
const ICONS = ['grass', 'planet', 'star', 'dirt', 'stone', 'diamond', 'tnt', 'ice', 'mushroom', 'crafting'];
const DISCORD = 'https://discord.gg/NhKmT6cVM7';
const AUTHOR = 'https://www.youtube.com/@m0uidev';

const SVG = {
  chat: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"/><path d="M8.5 11h.01M12 11h.01M15.5 11h.01"/></svg>',
  news: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="4" width="15" height="16" rx="1"/><path d="M18 8h3v10a2 2 0 0 1-2 2"/><path d="M7 8h7M7 12h7M7 16h4"/></svg>',
  gear: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/></svg>',
};

const ui = {
  info: null, // launcher.get(): state, version, platform, ...
  game: { state: 'idle' },
  check: null, // preflight of the selected installation (game folder)
  status: null, // api.status(): mode, account, disc, and for players what the button does
  progress: null, // install progress
  news: [],
  roadmap: { notes: [], upcoming: [], source: '' },
  log: [],
  skin: '', // the selected installation's skin
};
const S = () => ui.info.state;
const selected = () => S().installations.find((i) => i.id === S().selected) || S().installations[0];

// ---- helpers ---------------------------------------------------------------------------------

function toast(text, bad = false) {
  const t = document.createElement('div');
  t.className = `toast${bad ? ' bad' : ''}`;
  t.textContent = text;
  $('#toasts').append(t);
  setTimeout(() => t.remove(), bad ? 7000 : 3500);
}

function ago(iso) {
  if (!iso) return 'Never played';
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  if (s < 60) return 'Played just now';
  if (s < 3600) return `Played ${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `Played ${Math.floor(s / 3600)} h ago`;
  if (s < 86400 * 30) return `Played ${Math.floor(s / 86400)} days ago`;
  return `Played ${new Date(iso).toLocaleDateString()}`;
}

function niceDate(d) {
  const [y, m, day] = d.split('-').map(Number);
  return new Date(y, m - 1, day).toLocaleDateString(undefined, { year: 'numeric', month: 'long', day: 'numeric' });
}

function shade(hexColor, k) {
  const n = parseInt(hexColor.slice(1), 16);
  const ch = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => (k < 0 ? c * (1 + k) : c + (255 - c) * k));
  return `#${ch.map((c) => Math.round(Math.max(0, Math.min(255, c))).toString(16).padStart(2, '0')).join('')}`;
}

function modal(html, { small = false } = {}) {
  const back = document.createElement('div');
  back.className = 'modal-back';
  back.innerHTML = `<div class="modal${small ? ' small' : ''}" role="dialog" aria-modal="true">${html}</div>`;
  const close = () => { back.remove(); document.removeEventListener('keydown', onKey); };
  const onKey = (e) => { if (e.key === 'Escape') close(); };
  document.addEventListener('keydown', onKey);
  back.addEventListener('mousedown', (e) => { if (e.target === back) close(); });
  $('#modal-root').append(back);
  return { el: back, close };
}

function confirmBox(title, text, okLabel = 'Delete', danger = true) {
  return new Promise((resolve) => {
    const m = modal(`<div class="modal-head"><h2>${esc(title)}</h2></div>
      <div class="modal-body"><p>${esc(text)}</p></div>
      <div class="modal-foot"><button class="btn" data-no>Cancel</button><button class="btn ${danger ? 'danger' : 'primary'}" data-yes>${esc(okLabel)}</button></div>`,
    { small: true });
    $('[data-no]', m.el).onclick = () => { m.close(); resolve(false); };
    $('[data-yes]', m.el).onclick = () => { m.close(); resolve(true); };
  });
}

/** A little chime on PLAY, made by the browser (no sound files). */
function chime() {
  try {
    const ctx = new AudioContext();
    const notes = [523.25, 659.25, 783.99, 1046.5];
    notes.forEach((f, i) => {
      const o = ctx.createOscillator();
      const g = ctx.createGain();
      o.type = 'square';
      o.frequency.value = f;
      const t0 = ctx.currentTime + i * 0.07;
      g.gain.setValueAtTime(0.0001, t0);
      g.gain.exponentialRampToValueAtTime(0.06, t0 + 0.01);
      g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.25);
      o.connect(g).connect(ctx.destination);
      o.start(t0);
      o.stop(t0 + 0.3);
    });
    setTimeout(() => ctx.close(), 1200);
  } catch { /* no audio */ }
}

const skinHeadUrl = (name, size = 40) => `https://mc-heads.net/avatar/${encodeURIComponent(name)}/${size}`;

/** Shows `fallback` at once, and the picture at `url` only once it has loaded (offline: never). */
function remoteImage(img, url, fallback, onFail) {
  img.src = fallback;
  if (!url || ui.info.offline) { if (url && onFail) onFail(); return; }
  const probe = new Image();
  probe.onload = () => { if (img.dataset.want === url) img.src = url; };
  probe.onerror = () => { if (img.dataset.want === url && onFail) onFail(); };
  img.dataset.want = url;
  probe.src = url;
}

// ---- theme -----------------------------------------------------------------------------------

let scene = null;
function applyTheme() {
  const s = S().settings;
  const accent = s.accent === 'custom' ? s.customAccent : (ACCENTS[s.accent] || ACCENTS.grass)[1];
  const root = document.documentElement.style;
  root.setProperty('--accent', accent);
  root.setProperty('--accent-hover', shade(accent, -0.18));
  root.setProperty('--accent-shadow', shade(accent, -0.45));
  root.setProperty('--accent-text', shade(accent, 0.38));
  document.body.classList.toggle('no-anim', !s.animations);

  const custom = s.background === 'custom' && s.customBackground;
  $('#custom-bg').classList.toggle('hidden', !custom);
  if (custom) $('#custom-bg').style.backgroundImage = `url("${s.customBackground.replace(/"/g, '%22')}")`;
  if (!scene) scene = sceneOn($('#scene'));
  scene.set(custom ? 'planet' : s.background, s.animations && !custom);
}

// ---- navigation ------------------------------------------------------------------------------

function showPage(name) {
  $$('.page').forEach((p) => p.classList.toggle('active', p.id === `page-${name}`));
  $$('.nav-item').forEach((n) => n.classList.toggle('active', n.dataset.page === name));
  if (name === 'news') renderNews();
  if (name === 'settings') renderSettings();
}

function showTab(name) {
  $$('.tab-btn').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  $$('#page-game .tab').forEach((t) => t.classList.toggle('active', t.id === `tab-${name}`));
  if (name === 'installations') renderInstallations();
  if (name === 'skins') renderSkins();
  if (name === 'notes') renderNotes();
}

// ---- play ------------------------------------------------------------------------------------

async function refreshCheck() {
  ui.status = await api.status(S().selected);
  ui.check = ui.status.folder || null;
  renderAccount();
  renderPlay();
}

const release = () => ui.status && ui.status.mode === 'release';
/** Minecraft comes from the official Minecraft Launcher (the player signs in there). */
const viaOfficial = () => release() && ui.status.minecraftFrom === 'official';

function renderAccount() {
  const a = ui.status && ui.status.account;
  const name = a ? a.name : viaOfficial() ? 'Minecraft Launcher' : (release() ? 'Not signed in' : (ui.skin || 'Steve'));
  $('#account-name').textContent = name;
  $('#account-sub').textContent = a ? 'Minecraft account' : viaOfficial() ? 'Minecraft starts from there' : release() ? 'Click to sign in' : 'Super Minecraft Galaxy';
  const head = $('#account-head');
  if (a) remoteImage(head, `https://mc-heads.net/avatar/${encodeURIComponent(a.uuid)}/40`, iconUrl('grass', 40));
  else if (!ui.skin) remoteImage(head, '', iconUrl('grass', 40));
}

function toggleAccountMenu() {
  const menu = $('#account-menu');
  if (!menu.classList.contains('hidden')) { menu.classList.add('hidden'); return; }
  const a = ui.status && ui.status.account;
  menu.innerHTML = a
    ? `<div class="muted">Signed in as <strong>${esc(a.name)}</strong></div><button data-act="out">Sign out</button>`
    : `<button data-act="in">Sign in with Microsoft</button>${ui.status && ui.status.signInReady ? '' : '<div class="muted">Sign-in is not set up in this launcher yet.</div>'}`;
  menu.classList.remove('hidden');
  $$('[data-act]', menu).forEach((b) => {
    b.onclick = async () => {
      menu.classList.add('hidden');
      if (b.dataset.act === 'in') await signIn();
      else { await api.signOut(); toast('Signed out'); refreshCheck(); }
    };
  });
}

async function signIn() {
  const r = await api.signIn();
  if (r.ok) toast(`Signed in as ${r.account.name}`);
  else if (r.code !== 'cancelled') toast(r.error, true);
  await refreshCheck();
}

async function chooseRom() {
  const r = await api.chooseRom();
  if (!r) return;
  if (r.check.ok) toast(`Super Mario Galaxy 2 (${r.check.format}) chosen`);
  else toast(r.check.reason, true);
  ui.info.state = (await api.get()).state;
  await refreshCheck();
  if ($('#page-settings').classList.contains('active')) renderSettings();
}

async function install() {
  ui.progress = { phase: 'game', done: 0, total: 0, bytes: 0, totalBytes: 0 };
  renderPlay();
  const r = await api.install();
  ui.progress = null;
  if (r.ok) toast('Super Minecraft Galaxy is installed. Have fun!');
  else { toast(r.error, true); openLog(); }
  await refreshCheck();
}

function progressText(p) {
  if (!p) return '';
  const pct = p.totalBytes ? Math.floor((100 * p.bytes) / p.totalBytes) : p.total ? Math.floor((100 * p.done) / p.total) : 0;
  const what = { game: 'Downloading the game', disc: 'Making the game files from your Super Mario Galaxy 2', minecraft: 'Getting Minecraft ready' }[p.phase] || 'Installing';
  const mb = p.totalBytes ? ` · ${(p.bytes / 1e6).toFixed(0)} / ${(p.totalBytes / 1e6).toFixed(0)} MB` : p.total ? ` · ${p.done}/${p.total}` : '';
  return { pct, text: `${what}${p.label && p.phase === 'minecraft' && p.label !== 'Minecraft' ? ` (${p.label})` : ''}${mb}` };
}

async function refreshSkin() {
  try {
    ui.skin = (await api.gameSettings(S().selected)).values.skin || '';
  } catch { ui.skin = ''; }
  // A signed-in account shows itself; otherwise the installation's skin.
  if (ui.status && (ui.status.account || ui.status.mode === 'release')) { renderAccount(); return; }
  remoteImage($('#account-head'), ui.skin ? skinHeadUrl(ui.skin) : '', iconUrl('grass', 40));
  $('#account-name').textContent = ui.skin || 'Steve';
}

function renderPicker() {
  const inst = selected();
  $('#picker-icon').src = iconUrl(inst.icon);
  $('#picker-name').textContent = inst.name;
  $('#picker-sub').textContent = ago(inst.lastPlayed);
  const menu = $('#picker-menu');
  menu.innerHTML = S().installations.map((i) => `
    <button class="picker-item${i.id === inst.id ? ' sel' : ''}" data-id="${esc(i.id)}" role="option">
      <img class="pixel" src="${iconUrl(i.icon)}" alt=""><span><strong>${esc(i.name)}</strong><br>
      <span class="muted small">${esc(ago(i.lastPlayed))}</span></span></button>`).join('');
  $$('.picker-item', menu).forEach((b) => {
    b.onclick = async () => {
      menu.classList.add('hidden');
      await select(b.dataset.id);
    };
  });
}

async function select(id) {
  ui.info.state = await api.selectInstallation(id);
  renderPicker();
  refreshCheck();
  refreshSkin();
  if ($('#tab-installations').classList.contains('active')) renderInstallations();
  if ($('#tab-skins').classList.contains('active')) renderSkins();
}

function renderPlay() {
  renderPicker();
  const btn = $('#play');
  const status = $('#play-status');
  const g = ui.game.state;
  btn.classList.toggle('running', g === 'running');
  btn.classList.toggle('starting', g === 'starting' || g === 'stopping');
  status.className = 'play-status';
  status.title = '';
  if (g === 'starting') {
    btn.textContent = 'STARTING';
    btn.disabled = true;
    status.textContent = viaOfficial() ? 'Opening the Minecraft Launcher...' : 'Starting Dolphin and Minecraft...';
  } else if (g === 'running' && ui.game.waiting) {
    btn.textContent = 'STOP';
    btn.disabled = false;
    $('#play-progress').classList.add('hidden');
    status.innerHTML = `Now press <strong>Play</strong> in the Minecraft Launcher; Dolphin opens with it. <a href="#" id="open-official">Open it</a>`;
    status.title = 'The "Super Minecraft Galaxy" installation is chosen there; Minecraft then shows up in the window Dolphin opens.';
    $('#open-official').onclick = async (e) => {
      e.preventDefault();
      if (!(await api.openMinecraftLauncher())) toast('The Minecraft Launcher was not found: open it yourself.', true);
    };
  } else if (g === 'running') {
    btn.textContent = 'STOP';
    btn.disabled = false;
    const name = (S().installations.find((i) => i.id === ui.game.installation) || selected()).name;
    status.textContent = `Playing ${name}. Close the game window or press STOP.`;
    status.classList.add('good');
  } else if (g === 'stopping') {
    btn.textContent = 'CLOSING';
    btn.disabled = true;
    status.textContent = 'Closing both sides...';
  } else if (release()) {
    const st = ui.status.player;
    const busy = ui.status.installing || ui.progress;
    const bar = $('#play-progress');
    bar.classList.toggle('hidden', !busy);
    if (busy) {
      const p = progressText(ui.progress);
      btn.textContent = `${ui.status.installing && ui.status.player.action === 'update' ? 'UPDATING' : 'INSTALLING'} ${p.pct || 0}%`;
      btn.disabled = true;
      btn.classList.add('starting');
      $('div', bar).style.width = `${p.pct || 0}%`;
      status.textContent = p.text || 'Installing...';
    } else {
      btn.textContent = st.label;
      btn.disabled = ['unsupported', 'unavailable', 'checking'].includes(st.action) || (st.action === 'signin' && !ui.status.signInReady);
      status.textContent = st.detail;
      if (!ui.status.account && ui.status.installedVersion && !viaOfficial()) {
        status.textContent += ui.status.minecraftLauncher
          ? ' Not signed in? Play from the Minecraft Launcher: choose "Super Minecraft Galaxy" and press PLAY.'
          : ' Install the Minecraft Launcher (minecraft.net), sign in, then press INSTALL again.';
      }
      if (st.action === 'play') status.classList.add('good');
      if (['unsupported', 'unavailable'].includes(st.action)) status.classList.add('bad');
    }
  } else {
    $('#play-progress').classList.add('hidden');
    btn.textContent = 'PLAY';
    const c = ui.check;
    btn.disabled = !c || !c.ok;
    if (!c) status.textContent = 'Checking the game...';
    else if (!c.ok) {
      status.textContent = `${c.reason.label}: ${c.reason.detail}`;
      status.classList.add('bad');
    } else {
      const warn = c.checks.find((x) => !x.ok);
      status.textContent = warn ? `Ready, but ${warn.label}: ${warn.detail}` : `Ready to play · ${c.root}`;
      if (!warn) status.classList.add('good');
    }
  }
  renderSetup();
}

function renderReleaseSetup(box) {
  const st = ui.status;
  const official = viaOfficial();
  const show = st.player.action !== 'play' || (official ? !st.officialLauncher.ok : !st.account);
  box.classList.toggle('hidden', !show);
  if (!show) return;
  const minecraftStep = official
    ? { ok: st.officialLauncher.ok, label: 'Minecraft Launcher',
      detail: st.officialLauncher.ok ? 'Minecraft starts from the official Minecraft Launcher, with the account signed in there.'
        : 'Install the official Minecraft Launcher, open it once and sign in with the account that owns Minecraft: Java Edition.',
      button: st.officialLauncher.ok ? '' : '<button class="btn" data-step="get-official">Get the Minecraft Launcher</button>' }
    : { ok: !!st.account, label: 'Minecraft account', detail: st.account ? st.account.name : 'The Microsoft account that owns Minecraft: Java Edition.',
      button: st.account ? '' : `<button class="btn primary" data-step="signin" ${st.signInReady ? '' : 'disabled'}>Sign in</button>` };
  const steps = [
    minecraftStep,
    { ok: !!(st.rom && st.rom.check.ok), label: 'Super Mario Galaxy 2', detail: st.rom ? (st.rom.check.ok ? `${st.rom.path} (${st.rom.check.format}, ${st.rom.check.gameId})` : st.rom.check.reason)
      : 'Your own copy, USA version (SB4E01): .iso, .rvz, .wbfs. It never leaves your computer.', button: '<button class="btn" data-step="rom">Choose file</button>' },
    { ok: !!st.installedVersion, label: 'The game', detail: st.installedVersion ? `Version ${st.installedVersion}${st.latestVersion && st.latestVersion !== st.installedVersion ? `, ${st.latestVersion} is out` : ''}`
      : st.latestVersion ? `Version ${st.latestVersion} is ready to install.` : 'Looking for the latest release...', button: '' },
  ];
  box.innerHTML = `<h3>Get ready to play</h3>${steps.map((x) => `
    <div class="check"><span class="mark ${x.ok ? 'ok' : 'warn'}">${x.ok ? '✓' : '•'}</span><strong>${esc(x.label)}</strong>
      <div class="detail">${esc(x.detail)}${x.button ? `<div style="margin-top:6px">${x.button}</div>` : ''}</div></div>`).join('')}`;
  const b1 = $('[data-step=signin]', box); if (b1) b1.onclick = signIn;
  const b3 = $('[data-step=get-official]', box); if (b3) b3.onclick = () => api.openUrl('https://www.minecraft.net/download');
  const b2 = $('[data-step=rom]', box); if (b2) b2.onclick = chooseRom;
}

function renderSetup() {
  const c = ui.check;
  const box = $('#setup');
  if (release()) { renderReleaseSetup(box); return; }
  const show = c && (!c.ok || c.checks.some((x) => !x.ok));
  box.classList.toggle('hidden', !show);
  if (!show) return;
  box.innerHTML = `<h3>${c.ok ? 'Almost there' : 'Set up the game to play'}</h3>
    ${c.checks.map((x) => `
      <div class="check">
        <span class="mark ${x.ok ? 'ok' : x.blocking ? 'bad' : 'warn'}">${x.ok ? '✓' : x.blocking ? '✗' : '!'}</span>
        <strong>${esc(x.label)}</strong>
        <div class="detail">${esc(x.detail)}${x.ok ? '' : `<div class="fix">${esc(x.fix)}</div>`}</div>
      </div>`).join('')}
    <div class="row" style="margin-top:12px">
      <button class="btn" data-choose-root>Choose game folder</button>
      <button class="btn" data-recheck>Check again</button>
    </div>`;
  $('[data-choose-root]', box).onclick = chooseGameRoot;
  $('[data-recheck]', box).onclick = refreshCheck;
}

async function chooseGameRoot() {
  const dir = await api.chooseFolder('Choose the GalaxyCraft folder (the cloned repository)', S().settings.gameRoot);
  if (!dir) return;
  ui.info.state = await api.setSettings({ gameRoot: dir });
  await refreshCheck();
  if (!ui.check.root) toast('That folder is not a Super Minecraft Galaxy checkout.', true);
  if ($('#page-settings').classList.contains('active')) renderSettings();
}

async function onPlay() {
  if (ui.game.state === 'running') {
    api.stop();
    return;
  }
  if (release()) {
    const a = ui.status.player.action;
    if (a === 'signin') return signIn();
    if (a === 'rom') return chooseRom();
    if (a === 'install' || a === 'update') return install();
  }
  if (S().settings.playSound) chime();
  ui.game = { state: 'starting' };
  renderPlay();
  if (S().settings.showLogOnPlay) openLog();
  const r = await api.play(S().selected);
  if (!r.ok) {
    if (r.code === 'expired' || r.code === 'signin') await api.signOut();
    toast(r.error || 'The game did not start.', true);
    ui.game = { state: 'idle' };
    renderPlay();
    refreshCheck();
  }
  ui.info.state = (await api.get()).state;
  renderPicker();
}

function renderPlayBelow() {
  const cards = ui.news.slice(0, 3);
  $('#play-news').innerHTML = cards.length ? cards.map(newsCard).join('') : '<div class="empty">No news yet.</div>';
  $$('#play-news .card').forEach((c) => { c.onclick = () => showPage('news'); });
  const next = ui.roadmap.upcoming;
  $('#play-next').innerHTML = next.length ? next.map((n) => `
    <div class="next-item"><span class="tag ${n.status === 'now' ? 'update' : ''}">${esc(n.status)}</span>
      <div class="card-title">${inline(n.feature)}</div><div class="muted">${inline(n.notes)}</div></div>`).join('')
    : '<div class="empty">Nothing announced yet.</div>';
}

function newsCard(n) {
  const art = n.image ? `style="background-image:url('${cssUrl(n.image)}')"` : `style="background-image:url('${scenePreview(['planet', 'nebula', 'night', 'sunrise'][n.title.length % 4], 320, 110)}')"`;
  return `<div class="card"><div class="card-art" ${art}></div><div class="card-body">
    <span class="tag ${esc(n.tag)}">${esc(n.tag)}</span><span class="date">${esc(niceDate(n.date))}</span>
    <div class="card-title">${esc(n.title)}</div><div class="card-text">${inline(n.body.split('\n')[0])}</div></div></div>`;
}

// ---- installations ---------------------------------------------------------------------------

function renderInstallations() {
  const q = $('#inst-search').value.trim().toLowerCase();
  const sort = $('#inst-sort').value;
  const list = S().installations.filter((i) => i.name.toLowerCase().includes(q));
  list.sort(sort === 'name' ? (a, b) => a.name.localeCompare(b.name)
    : (a, b) => (b.lastPlayed || '').localeCompare(a.lastPlayed || ''));
  const box = $('#inst-list');
  box.innerHTML = list.length ? list.map((i) => `
    <div class="inst" data-id="${esc(i.id)}">
      <img class="pixel" src="${iconUrl(i.icon)}" alt="">
      <div style="min-width:0">
        <div class="inst-name">${esc(i.name)}${i.id === S().selected ? '<span class="selected-pill">SELECTED</span>' : ''}</div>
        <div class="inst-meta" data-dir>${esc(ago(i.lastPlayed))}</div>
      </div>
      <div class="inst-actions">
        <button class="btn primary" data-act="play">Play</button>
        <button class="btn" data-act="folder" title="Open its game folder">Folder</button>
        <button class="btn" data-act="edit">Edit</button>
        <button class="btn" data-act="dup" title="Duplicate">Copy</button>
        <button class="btn" data-act="del" title="Delete" ${S().installations.length <= 1 ? 'disabled' : ''}>Delete</button>
      </div>
    </div>`).join('') : '<div class="empty">No installation matches.</div>';
  $$('.inst', box).forEach(async (row) => {
    const id = row.dataset.id;
    const dir = await api.gameDirOf(id);
    const meta = $('[data-dir]', row);
    meta.textContent = `${meta.textContent} · ${dir}`;
    $$('[data-act]', row).forEach((b) => {
      b.onclick = async () => {
        const act = b.dataset.act;
        if (act === 'play') { await select(id); showTab('play'); onPlay(); }
        if (act === 'folder') api.openPath(dir);
        if (act === 'edit') editInstallation(S().installations.find((i) => i.id === id));
        if (act === 'dup') { ui.info.state = await api.duplicateInstallation(id); renderInstallations(); renderPicker(); }
        if (act === 'del') {
          const inst = S().installations.find((i) => i.id === id);
          if (await confirmBox('Delete installation', `Delete "${inst.name}"? Its worlds stay in ${dir}.`)) {
            ui.info.state = await api.deleteInstallation(id);
            renderInstallations();
            renderPicker();
            refreshCheck();
          }
        }
      };
    });
  });
}

function settingField(s, value) {
  const id = `gs-${s.key}`;
  if (s.type === 'toggle') {
    return `<label class="switch"><input type="checkbox" id="${id}" ${value ? 'checked' : ''}>
      <span><span class="label">${esc(s.label)}</span><span class="help">${esc(s.help)}</span></span></label>`;
  }
  if (s.type === 'range') {
    return `<div class="field"><label for="${id}">${esc(s.label)}</label>
      <div class="range-row"><input type="range" id="${id}" min="${s.min}" max="${s.max}" step="${s.step}" value="${value}">
      <output>${value}${esc(s.unit)}</output></div><div class="help">${esc(s.help)}</div></div>`;
  }
  if (s.type === 'choice') {
    return `<div class="field"><label for="${id}">${esc(s.label)}</label>
      <select class="input select" id="${id}">${s.options.map((o) => `<option value="${esc(o.value)}" ${o.value === value ? 'selected' : ''}>${esc(o.label)}</option>`).join('')}</select>
      <div class="help">${esc(s.help)}</div></div>`;
  }
  return `<div class="field"><label for="${id}">${esc(s.label)}</label>
    <input class="input" id="${id}" maxlength="${s.maxLength || 64}" value="${esc(value)}" spellcheck="false">
    <div class="help">${esc(s.help)}</div></div>`;
}

async function editInstallation(inst) {
  const isNew = !inst;
  const draft = inst ? { ...inst } : {
    name: 'New installation', icon: 'grass', gameDir: '', javaHome: '', dualCore: true, fullscreen: false, gradleArgs: '', dolphinArgs: '',
  };
  const gs = isNew ? null : await api.gameSettings(inst.id);
  const defaultDir = isNew ? 'its own folder in the launcher\'s data' : await api.gameDirOf(inst.id);
  const m = modal(`
    <div class="modal-head"><img class="pixel" id="ed-icon-preview" src="${iconUrl(draft.icon)}" width="40" height="40" alt="">
      <h2>${isNew ? 'New installation' : 'Edit installation'}</h2></div>
    <div class="modal-body">
      <div class="field"><label for="ed-name">Name</label><input class="input" id="ed-name" maxlength="48" value="${esc(draft.name)}"></div>
      <div class="field"><span class="field-label">Icon</span><div class="icon-grid">
        ${ICONS.map((ic) => `<button class="icon-opt${ic === draft.icon ? ' sel' : ''}" data-icon="${ic}" title="${ic}"><img class="pixel" src="${iconUrl(ic)}" alt="${ic}"></button>`).join('')}
      </div></div>
      <div class="field"><label for="ed-dir">Game directory</label>
        <div class="row"><input class="input" id="ed-dir" value="${esc(draft.gameDir)}" placeholder="${esc(defaultDir)}" spellcheck="false">
        <button class="btn" data-browse="ed-dir">Browse</button></div>
        <div class="help">Where this installation keeps its worlds, options and settings. Empty: ${esc(defaultDir)}.</div></div>
      <div class="two">
        <label class="switch"><input type="checkbox" id="ed-dual" ${draft.dualCore ? 'checked' : ''}>
          <span><span class="label">Dual core</span><span class="help">Dolphin's CPU and GPU on their own threads: smoother on planets.</span></span></label>
        <label class="switch"><input type="checkbox" id="ed-full" ${draft.fullscreen ? 'checked' : ''}>
          <span><span class="label">Fullscreen</span><span class="help">Open Dolphin's window in fullscreen.</span></span></label>
      </div>
      ${gs ? `<details class="more" open><summary>Game settings</summary>
        <p class="help muted small" style="margin-top:-6px">The same settings as Super Minecraft Galaxy... in the pause menu. Saved to ${esc(gs.file)}.</p>
        <div id="ed-gs">${gs.schema.map((s) => settingField(s, gs.values[s.key])).join('')}</div></details>`
        : '<p class="muted small">Game settings can be changed once the installation is saved.</p>'}
      <details class="more"><summary>More options</summary>
        <div class="field"><label for="ed-java">Java (JDK 25)</label>
          <div class="row"><input class="input" id="ed-java" value="${esc(draft.javaHome)}" placeholder="Found by the launcher" spellcheck="false">
          <button class="btn" data-browse="ed-java">Browse</button></div>
          <div class="help">A JDK 25 folder (the one holding bin/). Empty: the launcher finds one.</div></div>
        <div class="field"><label for="ed-javaargs">Java arguments</label>
          <input class="input" id="ed-javaargs" value="${esc(draft.javaArgs || '')}" placeholder="-Xmx4G" spellcheck="false">
          <div class="help">For Minecraft in the released game: memory and the like. Empty: the release's (-Xmx4G).</div></div>
        <div class="field"><label for="ed-dolphin">Dolphin arguments</label>
          <input class="input" id="ed-dolphin" value="${esc(draft.dolphinArgs)}" placeholder="-C Dolphin.Display.RenderToMain=True" spellcheck="false"></div>
        <div class="field"><label for="ed-gradle">Gradle arguments</label>
          <input class="input" id="ed-gradle" value="${esc(draft.gradleArgs)}" placeholder="--offline" spellcheck="false"></div>
      </details>
    </div>
    <div class="modal-foot"><button class="btn" data-cancel>Cancel</button><button class="btn primary" data-save>${isNew ? 'Create' : 'Save'}</button></div>`);
  const el = m.el;
  $$('.icon-opt', el).forEach((b) => {
    b.onclick = () => {
      draft.icon = b.dataset.icon;
      $$('.icon-opt', el).forEach((x) => x.classList.toggle('sel', x === b));
      $('#ed-icon-preview', el).src = iconUrl(draft.icon);
    };
  });
  $$('[data-browse]', el).forEach((b) => {
    b.onclick = async () => {
      const input = $(`#${b.dataset.browse}`, el);
      const dir = await api.chooseFolder(b.dataset.browse === 'ed-java' ? 'Choose a JDK 25 folder' : 'Choose a game directory', input.value);
      if (dir) input.value = dir;
    };
  });
  $$('input[type=range]', el).forEach((r) => {
    const s = gs.schema.find((x) => `gs-${x.key}` === r.id);
    r.oninput = () => { r.nextElementSibling.textContent = `${r.value}${s.unit}`; };
  });
  $('[data-cancel]', el).onclick = m.close;
  $('[data-save]', el).onclick = async () => {
    const saved = {
      ...draft,
      name: $('#ed-name', el).value.trim() || 'Installation',
      gameDir: $('#ed-dir', el).value.trim(),
      javaHome: $('#ed-java', el).value.trim(),
      dualCore: $('#ed-dual', el).checked,
      fullscreen: $('#ed-full', el).checked,
      dolphinArgs: $('#ed-dolphin', el).value,
      javaArgs: $('#ed-javaargs', el).value,
      gradleArgs: $('#ed-gradle', el).value,
    };
    ui.info.state = await api.saveInstallation(saved);
    if (gs) {
      const values = {};
      for (const s of gs.schema) {
        const input = $(`#gs-${s.key}`, el);
        values[s.key] = s.type === 'toggle' ? input.checked : s.type === 'range' ? Number(input.value) : input.value;
      }
      try { await api.setGameSettings(inst.id, values); } catch (e) { toast(`Game settings not saved: ${e.message}`, true); }
    }
    m.close();
    toast(isNew ? `Installation "${saved.name}" created` : 'Saved');
    renderInstallations();
    renderPicker();
    refreshCheck();
    refreshSkin();
  };
  $('#ed-name', el).focus();
}

// ---- skins -----------------------------------------------------------------------------------

function showSkin(name) {
  const img = $('#skin-body');
  $('#skin-fallback').classList.add('hidden');
  img.classList.remove('hidden');
  remoteImage(img, name ? `https://mc-heads.net/body/${encodeURIComponent(name)}/right` : '', iconUrl('grass', 160), () => {
    $('#skin-msg').textContent = 'No preview (offline, or no such account). The game will still try it.';
  });
}

function renderSkins() {
  $('#skin-inst').textContent = selected().name;
  $('#skin-name').value = ui.skin;
  $('#skin-msg').textContent = '';
  showSkin(ui.skin);
  const recent = S().settings.recentSkins;
  $('#skin-recent').innerHTML = recent.length ? recent.map((n) => `
    <button class="skin-chip" data-name="${esc(n)}"><img class="pixel" alt="">${esc(n)}</button>`).join('')
    : '<span class="muted small">Skins you use show here.</span>';
  $$('.skin-chip').forEach((c) => {
    remoteImage($('img', c), skinHeadUrl(c.dataset.name, 24), iconUrl('grass', 24));
    c.onclick = () => { $('#skin-name').value = c.dataset.name; showSkin(c.dataset.name); };
  });
}

async function saveSkin() {
  const name = $('#skin-name').value.trim();
  if (name && !/^[A-Za-z0-9_]{1,16}$/.test(name)) {
    $('#skin-msg').textContent = 'A Minecraft name: letters, numbers and _, up to 16.';
    return;
  }
  await api.setGameSettings(S().selected, { skin: name });
  if (name) {
    const recent = [name, ...S().settings.recentSkins.filter((n) => n.toLowerCase() !== name.toLowerCase())].slice(0, 8);
    ui.info.state = await api.setSettings({ recentSkins: recent });
  }
  await refreshSkin();
  renderSkins();
  toast(name ? `Skin set to ${name} for ${selected().name}` : 'Back to Steve');
}

// ---- patch notes and news --------------------------------------------------------------------

function renderNotes() {
  const r = ui.roadmap;
  $('#notes').innerHTML = r.notes.length ? r.notes.map((d) => `
    <div class="note"><h3>${esc(niceDate(d.date).toUpperCase())}</h3>
    <ul>${d.items.map((i) => `<li>${inline(i)}</li>`).join('')}</ul></div>`).join('')
    : '<div class="empty">No patch notes yet.</div>';
  $('#notes-source').textContent = r.source ? `From the roadmap (${r.source}).` : '';
  $$('#notes a[data-external]').forEach((a) => a.onclick = (e) => { e.preventDefault(); api.openUrl(a.href); });
}

function renderNews() {
  $('#news').innerHTML = ui.news.length ? ui.news.map((n) => `
    <article class="post">${n.image ? `<div class="post-art" style="background-image:url('${cssUrl(n.image)}')"></div>` : ''}
      <div class="post-body"><span class="tag ${esc(n.tag)}">${esc(n.tag)}</span><span class="date">${esc(niceDate(n.date))}</span>
      ${n.pinned ? '<span class="pin">PINNED</span>' : ''}
      <h2>${esc(n.title)}</h2>${block(n.body)}
      ${n.link ? `<a href="${esc(n.link)}" data-external>Read more</a>` : ''}</div></article>`).join('')
    : '<div class="empty">No news yet.</div>';
  $$('#news a[data-external]').forEach((a) => a.onclick = (e) => { e.preventDefault(); api.openUrl(a.href); });
}

// ---- settings --------------------------------------------------------------------------------

function updateText(u) {
  switch (u.state) {
    case 'checking': return 'Checking for updates...';
    case 'latest': return 'You have the latest version.';
    case 'downloading': return `Downloading ${u.version || 'the update'}... ${u.percent || 0}%`;
    case 'ready': return `Version ${u.version} is ready: restart the launcher to update.`;
    case 'error': return `Could not check for updates: ${u.message}`;
    case 'idle': return 'Updates are checked automatically.';
    default: return ui.info.packaged ? 'Automatic updates are off.' : 'Updates work in the installed launcher (this one runs from source).';
  }
}

async function renderSettings() {
  const s = S().settings;
  const c = ui.check || {};
  const platform = { linux: 'Linux', win32: 'Windows', darwin: 'macOS' }[ui.info.platform] || ui.info.platform;
  $('#settings').innerHTML = `
    <section class="set-section"><h2>Game</h2>
      <div class="field"><span class="field-label">Super Mario Galaxy 2</span>
        <div class="row"><input class="input" id="st-rom" value="${esc(s.rom)}" placeholder="Your own copy, USA version (.iso, .rvz, .wbfs)" readonly>
        <button class="btn" id="st-rom-choose">Choose</button></div>
        <div class="help">${ui.status && ui.status.rom ? esc(ui.status.rom.check.ok ? `${ui.status.rom.check.title} · ${ui.status.rom.check.gameId} · ${ui.status.rom.check.format}` : ui.status.rom.check.reason) : 'The launcher makes Steve and the space galaxy from it on your computer; nothing of it is uploaded.'}</div></div>
      <div class="field"><span class="field-label">Play</span><div class="radio-group">
        ${[['auto', 'Automatic: my game folder if I chose one, else the released game'], ['release', 'The released game (installed and updated by the launcher)'], ['folder', 'My game folder (a GalaxyCraft checkout I build myself)']]
          .map(([v, l]) => `<label class="radio"><input type="radio" name="playfrom" value="${v}" ${s.playFrom === v ? 'checked' : ''}>${l}</label>`).join('')}
      </div><div class="help">Now: ${ui.status && ui.status.mode === 'release' ? `the released game${ui.status.installedVersion ? ` ${esc(ui.status.installedVersion)}` : ''}${ui.status.latestVersion ? ` (latest ${esc(ui.status.latestVersion)})` : ''}` : 'your game folder'}.
        <a href="#" id="st-check-game">Check for game updates</a></div></div>
      <div class="field"><span class="field-label">Minecraft</span><div class="radio-group">
        ${[['auto', 'Automatic: this launcher if I signed in here, else the Minecraft Launcher'], ['official', 'The official Minecraft Launcher (sign in there; PLAY here opens Dolphin, then press Play there)'], ['launcher', 'This launcher (sign in here with Microsoft)']]
          .map(([v, l]) => `<label class="radio"><input type="radio" name="mcfrom" value="${v}" ${s.minecraftFrom === v ? 'checked' : ''}>${l}</label>`).join('')}
      </div><div class="help">For the released game. ${ui.status && ui.status.minecraftFrom ? `Now: ${ui.status.minecraftFrom === 'official' ? 'the Minecraft Launcher' : 'this launcher'}.` : ''}
        ${ui.status && ui.status.officialLauncher ? (ui.status.officialLauncher.ok ? `Minecraft Launcher folder: ${esc(ui.status.officialLauncher.dir)}.` : 'The Minecraft Launcher was not found: install it and open it once.') : ''}</div>
        <label class="switch" style="margin-top:8px"><input type="checkbox" id="st-close-official" ${s.closeMinecraftLauncher ? 'checked' : ''}>
          <span><span class="label">Close the Minecraft Launcher when the game closes</span><span class="help">When Minecraft or Dolphin closes, everything closes but this launcher.</span></span></label></div>
      <div class="field"><label for="st-root">Game folder</label>
        <div class="row"><input class="input" id="st-root" value="${esc(s.gameRoot)}" placeholder="${esc(c.root || 'Not found: choose the GalaxyCraft folder')}" spellcheck="false">
        <button class="btn" id="st-root-browse">Browse</button><button class="btn" id="st-root-auto">Find it</button></div>
        <div class="help">The cloned GalaxyCraft repository, with Dolphin and the module built. ${c.root ? `Using ${esc(c.root)}.` : ''}</div></div>
      <div class="field"><span class="field-label">When the game starts</span><div class="radio-group">
        ${[['keep', 'Keep the launcher open'], ['hide', 'Hide the launcher, show it again when the game closes'], ['close', 'Close the launcher']]
          .map(([v, l]) => `<label class="radio"><input type="radio" name="onplay" value="${v}" ${s.onPlay === v ? 'checked' : ''}>${l}</label>`).join('')}
      </div></div>
      <label class="switch"><input type="checkbox" id="st-log" ${s.showLogOnPlay ? 'checked' : ''}>
        <span><span class="label">Open the log when the game starts</span><span class="help">Dolphin's and Minecraft's output, for when something goes wrong.</span></span></label>
    </section>
    <section class="set-section"><h2>Appearance</h2>
      <div class="field"><span class="field-label">Accent color</span><div class="swatches">
        ${Object.entries(ACCENTS).map(([k, [label, col]]) => `<button class="swatch${s.accent === k ? ' sel' : ''}" data-accent="${k}" title="${label}" style="background:${col}"></button>`).join('')}
        <label class="swatch-custom" title="Any color"><input type="color" id="st-color" value="${esc(s.customAccent || '#3c8527')}">${s.accent === 'custom' ? '<strong>Custom</strong>' : 'Custom'}</label>
      </div></div>
      <div class="field"><span class="field-label">Background</span><div class="bg-grid">
        ${Object.entries(SCENES).map(([k, sc]) => `<button class="bg-opt${s.background === k ? ' sel' : ''}" data-bg="${k}"><img src="${scenePreview(k)}" alt=""><span>${sc.label}</span></button>`).join('')}
        <button class="bg-opt${s.background === 'custom' ? ' sel' : ''}" data-bg="custom">${s.customBackground
          ? `<div class="bg-img" style="background-image:url('${cssUrl(s.customBackground)}')"></div>` : '<div class="bg-add">+</div>'}<span>Your picture</span></button>
      </div></div>
      <label class="switch"><input type="checkbox" id="st-anim" ${s.animations ? 'checked' : ''}>
        <span><span class="label">Animations</span><span class="help">Twinkling stars, floating planets and shooting stars.</span></span></label>
      <label class="switch"><input type="checkbox" id="st-sound" ${s.playSound ? 'checked' : ''}>
        <span><span class="label">Sound on PLAY</span></span></label>
    </section>
    <section class="set-section"><h2>Updates</h2>
      <label class="switch"><input type="checkbox" id="st-upd" ${s.checkUpdates ? 'checked' : ''}>
        <span><span class="label">Update the launcher automatically</span><span class="help">New versions download in the background and install when the launcher restarts.</span></span></label>
      <div class="row" style="margin-top:8px"><span id="st-upd-text" class="muted grow">${esc(updateText(ui.info.update || {}))}</span>
        <button class="btn" id="st-upd-check" ${ui.info.packaged ? '' : 'disabled'}>Check now</button>
        ${(ui.info.update || {}).state === 'ready' ? '<button class="btn primary" id="st-upd-install">Restart and update</button>' : ''}</div>
    </section>
    <section class="set-section"><h2>About</h2>
      <div class="kv">
        <div>Made by</div><div><a href="#" id="st-author">@M0uiDev</a></div>
        <div>Launcher</div><div>${esc(ui.info.version)} · ${esc(platform)}${ui.info.packaged ? '' : ' · from source'}</div>
        <div>Game data</div><div>${esc(ui.info.dataDir)}</div>
        <div>Java</div><div>${esc(c.java ? `${c.java.home} (Java ${c.java.version})` : 'Not found')}</div>
        <div>Dolphin</div><div>${esc(c.dolphinBin || 'Not built')}</div>
      </div>
      <div class="row" style="margin-top:14px">
        <button class="btn" id="st-open-data">Open the game data folder</button>
        <button class="btn" id="st-open-repo">GalaxyCraft on GitHub</button>
        <button class="btn" id="st-discord">Join the Discord</button>
        <button class="btn" id="st-youtube">@M0uiDev on YouTube</button>
      </div>
      <p class="muted small">Not affiliated with Nintendo, Mojang or Microsoft. You need your own Super Mario Galaxy 2 and Minecraft.</p>
    </section>`;

  const set = async (patch) => {
    ui.info.state = await api.setSettings(patch);
    applyTheme();
  };
  $('#st-root-browse').onclick = chooseGameRoot;
  $('#st-rom-choose').onclick = chooseRom;
  $$('input[name=playfrom]').forEach((r) => { r.onchange = async () => { await set({ playFrom: r.value }); await refreshCheck(); renderSettings(); }; });
  $('#st-check-game').onclick = async (e) => { e.preventDefault(); ui.status = await api.checkLatest(); renderPlay(); renderSettings();
    toast(ui.status.latestVersion ? `Latest game: ${ui.status.latestVersion}` : 'Could not reach the game\'s releases', !ui.status.latestVersion); };
  $('#st-root-auto').onclick = async () => { await set({ gameRoot: '' }); await refreshCheck(); renderSettings(); };
  $('#st-root').onchange = async (e) => { await set({ gameRoot: e.target.value.trim() }); await refreshCheck(); renderSettings(); };
  $('#st-close-official').onchange = (e) => set({ closeMinecraftLauncher: e.target.checked });
  $$('input[name=mcfrom]').forEach((r) => { r.onchange = async () => { await set({ minecraftFrom: r.value }); await refreshCheck(); renderSettings(); }; });
  $$('input[name=onplay]').forEach((r) => { r.onchange = () => set({ onPlay: r.value }); });
  $('#st-log').onchange = (e) => set({ showLogOnPlay: e.target.checked });
  $$('[data-accent]').forEach((b) => { b.onclick = async () => { await set({ accent: b.dataset.accent }); renderSettings(); }; });
  $('#st-color').oninput = (e) => {
    const root = document.documentElement.style;
    root.setProperty('--accent', e.target.value);
  };
  $('#st-color').onchange = async (e) => { await set({ accent: 'custom', customAccent: e.target.value }); renderSettings(); };
  $$('[data-bg]').forEach((b) => {
    b.onclick = async () => {
      if (b.dataset.bg === 'custom') {
        const url = await api.chooseBackground();
        if (url) await set({ background: 'custom', customBackground: url });
        else if (S().settings.customBackground) await set({ background: 'custom' });
      } else {
        await set({ background: b.dataset.bg });
      }
      renderSettings();
    };
  });
  $('#st-anim').onchange = (e) => set({ animations: e.target.checked });
  $('#st-sound').onchange = (e) => set({ playSound: e.target.checked });
  $('#st-upd').onchange = (e) => set({ checkUpdates: e.target.checked });
  $('#st-upd-check').onclick = async () => { $('#st-upd-text').textContent = updateText(await api.checkUpdate()); };
  const inst = $('#st-upd-install');
  if (inst) inst.onclick = () => api.installUpdate();
  $('#st-open-data').onclick = () => api.openPath(ui.info.dataDir);
  $('#st-open-repo').onclick = () => api.openUrl('https://github.com/M0uidev/GalaxyCraft');
  $('#st-discord').onclick = () => api.openUrl(DISCORD);
  $('#st-youtube').onclick = () => api.openUrl(AUTHOR);
  $('#st-author').onclick = (e) => { e.preventDefault(); api.openUrl(AUTHOR); };
}

function renderUpdateChip() {
  const u = ui.info.update || {};
  const chip = $('#update-chip');
  chip.classList.toggle('hidden', u.state !== 'ready');
  chip.textContent = `Restart to update to ${u.version || 'the new version'}`;
  chip.onclick = () => api.installUpdate();
}

// ---- log -------------------------------------------------------------------------------------

function logLine(e) {
  const err = e.stream === 'stderr' ? ' err' : '';
  return `<span class="src ${esc(e.source)}">[${esc(e.source)}]</span><span class="${err}">${esc(e.line)}</span>\n`;
}

function renderLog() {
  const f = $('#log-filter').value;
  const pre = $('#log');
  pre.innerHTML = ui.log.filter((e) => !f || e.source === f).map(logLine).join('') || '<span class="muted">Nothing yet: press PLAY.</span>';
  pre.scrollTop = pre.scrollHeight;
}

function openLog() {
  $('#console').classList.remove('hidden');
  renderLog();
}

// ---- start -----------------------------------------------------------------------------------

async function main() {
  await loadArt();
  ui.info = await api.get();
  ui.game = ui.info.game;
  $('#version').textContent = `v${ui.info.version}${ui.info.packaged ? '' : ' (dev)'}`;
  $$('[data-icon]').forEach((el) => { if (SVG[el.dataset.icon]) el.innerHTML = SVG[el.dataset.icon]; });
  $$('[data-block]').forEach((el) => { el.src = iconUrl(el.dataset.block); });
  applyTheme();

  $$('.nav-item[data-page]').forEach((b) => { b.onclick = () => showPage(b.dataset.page); });
  $('#discord-btn').onclick = () => api.openUrl(DISCORD);
  $('#author-link').onclick = (e) => { e.preventDefault(); api.openUrl(AUTHOR); };
  $$('.tab-btn').forEach((b) => { b.onclick = () => showTab(b.dataset.tab); });
  $('#play').onclick = onPlay;
  $('#account').onclick = toggleAccountMenu;
  $('#account').onkeydown = (e) => { if (e.key === 'Enter') toggleAccountMenu(); };
  api.onInstallProgress((p) => { ui.progress = p; if (ui.status) ui.status.installing = ui.status.installing || { version: '' }; renderPlay(); });
  api.onInstallState((st) => { if (ui.status) ui.status.installing = st; if (!st) ui.progress = null; renderPlay(); });
  api.onLatest(() => refreshCheck());
  $('#picker').onclick = (e) => { e.stopPropagation(); $('#picker-menu').classList.toggle('hidden'); };
  document.addEventListener('click', (e) => { if (!e.target.closest('.picker-wrap')) $('#picker-menu').classList.add('hidden'); });
  $('#log-btn').onclick = () => ($('#console').classList.contains('hidden') ? openLog() : $('#console').classList.add('hidden'));
  $('#log-close').onclick = () => $('#console').classList.add('hidden');
  $('#log-clear').onclick = () => { ui.log = []; renderLog(); };
  $('#log-folder').onclick = () => api.openPath(ui.info.logsDir);
  $('#log-copy').onclick = () => { navigator.clipboard.writeText(ui.log.map((e) => `[${e.source}] ${e.line}`).join('\n')); toast('Log copied'); };
  $('#log-filter').onchange = renderLog;
  $('#folder-btn').onclick = async () => api.openPath(await api.gameDirOf(S().selected));
  $('#inst-new').onclick = () => editInstallation(null);
  $('#inst-search').oninput = renderInstallations;
  $('#inst-sort').onchange = renderInstallations;
  $('#skin-preview-btn').onclick = () => { $('#skin-msg').textContent = ''; showSkin($('#skin-name').value.trim()); };
  $('#skin-save').onclick = saveSkin;
  $('#skin-name').onkeydown = (e) => { if (e.key === 'Enter') saveSkin(); };

  let pending = null;
  api.onGameLog((e) => {
    ui.log.push(e);
    if (ui.log.length > 5000) ui.log.splice(0, ui.log.length - 5000);
    if ($('#console').classList.contains('hidden')) return;
    // Lines come in bursts: draw them together.
    if (!pending) pending = requestAnimationFrame(() => { pending = null; renderLog(); });
  });
  api.onGameState((g) => {
    ui.game = g;
    renderPlay();
    if (g.state === 'stopped') {
      if (g.crashed) {
        toast(`The game closed with an error (${g.by}${g.code != null ? `, code ${g.code}` : ''}). See the log.`, true);
        openLog();
      }
      refreshCheck();
    }
  });
  api.onUpdate((u) => {
    ui.info.update = u;
    renderUpdateChip();
    if ($('#page-settings').classList.contains('active') && $('#st-upd-text')) $('#st-upd-text').textContent = updateText(u);
    if (u.state === 'ready') toast(`Launcher ${u.version} downloaded: it installs when you restart the launcher.`);
  });
  window.addEventListener('focus', () => { if (ui.game.state === 'idle' || ui.game.state === 'stopped') refreshCheck(); });

  ui.log = await api.log();
  renderPlay();
  renderUpdateChip();
  refreshCheck();
  refreshSkin();
  const [news, roadmap] = await Promise.all([api.news(), api.roadmap()]);
  ui.news = news.news;
  ui.roadmap = roadmap;
  renderPlayBelow();
  document.body.dataset.ready = '1';
}

main().catch((e) => {
  document.body.innerHTML = `<pre style="padding:20px;color:#f88">The launcher could not start:\n${esc(e.stack || e)}</pre>`;
});
