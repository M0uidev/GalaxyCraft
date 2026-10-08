'use strict';
// The report a player sends when something goes wrong: one zip with the launcher's, Minecraft's and
// Dolphin's logs, the crash reports, the settings and a summary of the system. Nothing here touches
// Electron: the file system comes in as an argument, so it is tested with fakes.
//
// It is never uploaded; the player saves it and decides who gets it. Home folders and anything that
// looks like a token or the session are blanked first.
const zlib = require('node:zlib');

const MAX_FILE_BYTES = 2 * 1024 * 1024; // each log keeps its last 2 MB: the zip fits in Discord's 10
const MAX_CRASH_REPORTS = 3;

// ---- zip ---------------------------------------------------------------------------------------

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (let i = 0; i < buf.length; i++) c = CRC_TABLE[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

/** A zip (deflated) of [{ name, data: string | Buffer }]. Names use "/" and are UTF-8. */
function buildZip(entries, when = new Date()) {
  const dosTime = (when.getHours() << 11) | (when.getMinutes() << 5) | (when.getSeconds() >> 1);
  const dosDate = (Math.max(0, when.getFullYear() - 1980) << 9) | ((when.getMonth() + 1) << 5) | when.getDate();
  const parts = [];
  const central = [];
  let offset = 0;
  for (const e of entries) {
    const name = Buffer.from(e.name, 'utf8');
    const raw = Buffer.isBuffer(e.data) ? e.data : Buffer.from(e.data, 'utf8');
    const packed = raw.length ? zlib.deflateRawSync(raw) : raw;
    const method = raw.length ? 8 : 0;
    const crc = crc32(raw);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6); // UTF-8 names
    local.writeUInt16LE(method, 8);
    local.writeUInt16LE(dosTime, 10);
    local.writeUInt16LE(dosDate, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(packed.length, 18);
    local.writeUInt32LE(raw.length, 22);
    local.writeUInt16LE(name.length, 26);
    parts.push(local, name, packed);
    const cd = Buffer.alloc(46);
    cd.writeUInt32LE(0x02014b50, 0);
    cd.writeUInt16LE(20, 4);
    cd.writeUInt16LE(20, 6);
    cd.writeUInt16LE(0x0800, 8);
    cd.writeUInt16LE(method, 10);
    cd.writeUInt16LE(dosTime, 12);
    cd.writeUInt16LE(dosDate, 14);
    cd.writeUInt32LE(crc, 16);
    cd.writeUInt32LE(packed.length, 20);
    cd.writeUInt32LE(raw.length, 24);
    cd.writeUInt16LE(name.length, 28);
    cd.writeUInt32LE(offset, 42);
    central.push(cd, name);
    offset += local.length + name.length + packed.length;
  }
  const cdSize = central.reduce((n, b) => n + b.length, 0);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(entries.length, 8);
  end.writeUInt16LE(entries.length, 10);
  end.writeUInt32LE(cdSize, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...parts, ...central, end]);
}

// ---- private data ------------------------------------------------------------------------------

const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** The text with the home folder replaced by "~" and tokens, ids and the session blanked. */
function redact(text, { home = '' } = {}) {
  let out = String(text);
  if (home) {
    const h = home.replace(/[\\/]+$/, '');
    // either slash, any case on Windows paths
    const re = new RegExp(escapeRe(h).replace(/\\\\|\//g, '[\\\\/]'), 'gi');
    out = out.replace(re, '~');
  }
  return out
    .replace(/(--(?:accessToken|uuid|xuid|session|clientId)[ =])\S+/gi, '$1********')
    .replace(/(Bearer\s+)[\w.~+/=-]+/gi, '$1********')
    .replace(/("?(?:access_?token|refresh_?token|id_?token|mcAccessToken|msRefreshToken|xstsToken|client_?secret)"?\s*[:=]\s*)"[^"]*"/gi, '$1"********"')
    .replace(/\beyJ[\w-]{8,}\.[\w-]{8,}\.[\w-]{8,}/g, '********');
}

/** The last maxBytes of the text, from a whole line, with a first line saying it was cut. */
function tail(text, maxBytes = MAX_FILE_BYTES) {
  if (Buffer.byteLength(text) <= maxBytes) return text;
  let cut = Buffer.from(text, 'utf8').subarray(-maxBytes).toString('utf8').replace(/^\uFFFD+/, '');
  const nl = cut.indexOf('\n');
  if (nl >= 0) cut = cut.slice(nl + 1);
  return `[... cut: only the end of this file is included ...]\n${cut}`;
}

// ---- what goes in ------------------------------------------------------------------------------

/**
 * The report's files: [{ name, data }], redacted and cut. Files that do not exist are skipped.
 *   fs        { exists, list, read, mtime? } (mtime orders the crash reports)
 *   paths     platformPaths()
 *   gameDir   the installation's Minecraft folder
 *   stateFile the launcher's settings file
 */
function collect({ fs, paths, gameDir, stateFile, maxBytes = MAX_FILE_BYTES }) {
  const p = paths.path;
  const home = paths.home;
  const out = [];
  const add = (name, file) => {
    try {
      if (!fs.exists(file)) return;
      out.push({ name, data: redact(tail(fs.read(file), maxBytes), { home }) });
    } catch {
      /* unreadable: leave it out */
    }
  };
  const list = (dir) => {
    try {
      return fs.exists(dir) ? fs.list(dir) : [];
    } catch {
      return [];
    }
  };

  const logs = p.join(paths.dataDir, 'logs');
  add('launcher/launcher.log', p.join(logs, 'launcher.log'));
  add('launcher/launcher.old.log', p.join(logs, 'launcher.old.log'));

  add('minecraft/latest.log', p.join(gameDir, 'logs', 'latest.log'));
  add('minecraft/debug.log', p.join(gameDir, 'logs', 'debug.log'));
  const newestFirst = (dir, names) => {
    const t = (n) => (fs.mtime ? fs.mtime(p.join(dir, n)) || 0 : 0);
    return [...names].sort((a, b) => t(b) - t(a) || (a < b ? 1 : -1));
  };
  const crashDir = p.join(gameDir, 'crash-reports');
  for (const n of newestFirst(crashDir, list(crashDir).filter((n) => /\.(txt|log)$/i.test(n))).slice(0, MAX_CRASH_REPORTS)) {
    add(`minecraft/crash-reports/${n}`, p.join(crashDir, n));
  }
  for (const n of newestFirst(gameDir, list(gameDir).filter((n) => /^hs_err_pid\d+\.log$/.test(n))).slice(0, MAX_CRASH_REPORTS)) {
    add(`minecraft/${n}`, p.join(gameDir, n));
  }
  add('config/galaxycraft.properties', p.join(gameDir, 'config', 'galaxycraft.properties'));

  const dolphinLogs = p.join(paths.dolphinDir, 'Logs');
  add('dolphin/dolphin.log', p.join(dolphinLogs, 'dolphin.log'));
  add('dolphin/galaxycraft.log', p.join(dolphinLogs, 'galaxycraft.log'));

  if (stateFile) add('config/launcher.json', stateFile);
  return out;
}

const gb = (bytes) => `${Math.round(bytes / 2 ** 30)} GB`;

/** system.txt: what is needed to know where the logs come from. */
function systemText({ version, platform, arch, osRelease, electron, totalMem, cpus, gpu, game, installation, settings, files, when = new Date(), home = '' }) {
  const lines = [
    'Super Minecraft Galaxy: report',
    `Made: ${when.toISOString()}`,
    '',
    `Launcher ${version} (Electron ${electron || '?'})`,
    `System: ${platform} ${arch} ${osRelease || ''}`.trim(),
    `CPU: ${cpus || '?'}`,
    `GPU: ${gpu || '?'}`,
    `Memory: ${totalMem ? gb(totalMem) : '?'}`,
    '',
  ];
  if (game) {
    const g = game.state === 'stopped' && game.crashed ? `crashed (${game.by || '?'}, code ${game.code ?? '?'})` : game.state;
    lines.push(`Game: ${g}`);
    if (game.error) lines.push(`Error: ${game.error}`);
  }
  if (installation) lines.push('', 'Installation:', JSON.stringify(installation, null, 2));
  if (settings) {
    // not the sign-in app's id: it is not a secret, but it is not needed either
    const { msaClientId, ...rest } = settings; // eslint-disable-line no-unused-vars
    lines.push('', 'Settings:', JSON.stringify(rest, null, 2));
  }
  lines.push('', 'Files in this report:', ...(files || []).map((f) => `  ${f}`));
  lines.push('', 'Home folders are shown as "~"; tokens and the session are blanked. Nothing was sent anywhere.');
  return redact(lines.join('\n'), { home });
}

const pad = (n) => String(n).padStart(2, '0');

function defaultName(when = new Date()) {
  const d = `${when.getUTCFullYear()}-${pad(when.getUTCMonth() + 1)}-${pad(when.getUTCDate())}`;
  const t = `${pad(when.getUTCHours())}${pad(when.getUTCMinutes())}${pad(when.getUTCSeconds())}`;
  return `smg-report-${d}-${t}.zip`;
}

module.exports = { buildZip, crc32, redact, tail, collect, systemText, defaultName, MAX_FILE_BYTES };
