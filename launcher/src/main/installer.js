'use strict';
// Installs and updates the game for players, from the GitHub Release's game.json: the module,
// this system's patched Dolphin and the mods, then the disc files rebuilt from the player's own
// Super Mario Galaxy 2, then Minecraft. A new version is made next to the one in use and only
// becomes the installed one once complete; the one before it is kept.
//
// Events: 'progress' ({ phase, label, done, total, bytes, totalBytes }), 'log' (line).
const { EventEmitter } = require('node:events');
const { execFile } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { Readable } = require('node:stream');
const { fileURLToPath } = require('node:url');
const tar = require('tar');
const gamepack = require('../core/gamepack');
const disc = require('../core/disc');
const soundtrack = require('../core/soundtrack');
const delta = require('../core/delta');
const { downloadAll, fetchJson } = require('../core/download');
const { ensureMinecraft } = require('./mcinstall');

const readJson = (f) => { try { return JSON.parse(fs.readFileSync(f, 'utf8')); } catch { return null; } };
const writeJson = (f, d) => { fs.mkdirSync(path.dirname(f), { recursive: true }); fs.writeFileSync(f, JSON.stringify(d, null, 2)); };

function run(cmd, args, { timeout = 120000 } = {}) {
  return new Promise((resolve, reject) => {
    execFile(cmd, args, { windowsHide: true, timeout, maxBuffer: 16 << 20 }, (err, stdout, stderr) => {
      if (err) reject(new Error(`${path.basename(cmd)}: ${(stderr || err.message).trim().split('\n').slice(-3).join(' ')}`));
      else resolve(stdout);
    });
  });
}

function findFile(dir, name) {
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      const found = findFile(p, name);
      if (found) return found;
    } else if (entry.name === name) return p;
  }
  return null;
}

/** A stamp of the disc file: when it changes, the disc files are made again. */
function discStamp(rom) {
  const st = fs.statSync(rom);
  return { rom, size: st.size, mtimeMs: Math.round(st.mtimeMs) };
}
const sameStamp = (a, b) => !!a && !!b && a.rom === b.rom && a.size === b.size && a.mtimeMs === b.mtimeMs;

/** A fetch Response for a file: URL. */
function fileResponse(url) {
  const file = fileURLToPath(url);
  if (!fs.existsSync(file)) return new Response('', { status: 404 });
  return new Response(Readable.toWeb(fs.createReadStream(file)), { status: 200 });
}

class Installer extends EventEmitter {
  constructor({ paths, fetchFn = globalThis.fetch, manifestUrl = gamepack.LATEST_URL, system = gamepack.systemKey(),
    ensureMinecraftFn = ensureMinecraft, soundtrackDefaults = path.join(__dirname, '..', '..', 'content', 'soundtrack.tsv') }) {
    super();
    this.soundtrackDefaults = soundtrackDefaults;
    this.ensureMinecraftFn = ensureMinecraftFn;
    this.paths = paths;
    // file: URLs (only a game.json on this machine has them, gamepack.localManifest) are read from disk.
    this.fetchFn = (url, opts) => (String(url).startsWith('file:') ? fileResponse(url) : fetchFn(url, opts));
    this.manifestUrl = manifestUrl;
    this.system = system;
    this.busy = false;
  }

  progress(p) { this.emit('progress', p); }
  log(line) { this.emit('log', line); }

  get installedFile() { return gamepack.layout(this.paths, '0').installed; }

  /** { version, manifest, disc } of the installed game, or null. */
  installed() {
    const i = readJson(this.installedFile);
    if (!i || !i.version || !i.manifest) return null;
    const lay = gamepack.layout(this.paths, i.version);
    return fs.existsSync(lay.root) ? { ...i, lay } : null;
  }

  /** The newest release's game.json (parsed), or an Error (offline, or a broken release). */
  async latest() {
    try {
      if (!/^https?:\/\//.test(this.manifestUrl)) {
        return gamepack.localManifest(JSON.parse(fs.readFileSync(this.manifestUrl, 'utf8')), this.manifestUrl);
      }
      return gamepack.parseManifest(await fetchJson(this.manifestUrl, this.fetchFn, 2));
    } catch (e) {
      return new Error(`Could not check for game updates (${e.message})`);
    }
  }

  /**
   * Installs (or updates to) a version. rom: the player's checked Super Mario Galaxy 2.
   * minecraft: false when the Minecraft Launcher brings Minecraft (nothing of it downloaded here).
   */
  async install(manifest, rom, { minecraft = true } = {}) {
    if (this.busy) throw new Error('Already installing');
    this.busy = true;
    try {
      const sys = manifest.dolphin[this.system];
      if (!sys) throw new Error(`Super Minecraft Galaxy ${manifest.version} is not out for ${gamepack.SYSTEMS[this.system] || this.system} yet.`);
      const lay = gamepack.layout(this.paths, manifest.version);
      this.log(`Installing Super Minecraft Galaxy ${manifest.version}`);

      // 1. The game's own files.
      const files = [manifest.module, sys, ...manifest.mods];
      await downloadAll(files.map((f) => ({ url: f.url, path: path.join(lay.downloads, f.file), sha256: f.sha256, size: f.size })), {
        fetchFn: this.fetchFn,
        onProgress: (p) => this.progress({ phase: 'game', label: 'Super Minecraft Galaxy', ...p }),
      });
      for (const [archive, dir] of [[manifest.module.file, lay.module], [sys.file, lay.dolphin]]) {
        fs.rmSync(dir, { recursive: true, force: true });
        fs.mkdirSync(dir, { recursive: true });
        await tar.x({ file: path.join(lay.downloads, archive), cwd: dir, preservePaths: false });
      }
      fs.mkdirSync(lay.mods, { recursive: true });
      for (const m of manifest.mods) fs.copyFileSync(path.join(lay.downloads, m.file), path.join(lay.mods, m.file));
      if (process.platform !== 'win32') {
        for (const exe of [sys.exe, sys.tool]) fs.chmodSync(path.join(lay.dolphin, exe), 0o755);
      }

      // 2. The disc files, from the player's game.
      await this.makeDiscFiles(lay, sys, rom);
      await this.makeSoundtrack(lay, sys, rom);

      // 3. Minecraft, Fabric and Java.
      if (minecraft) await this.minecraft(manifest);

      writeJson(lay.descriptor, gamepack.descriptor(rom, lay.module));
      const before = this.installed();
      writeJson(this.installedFile, { version: manifest.version, manifest, installedAt: new Date().toISOString(),
        previous: before && before.version !== manifest.version ? before.version : (before && before.previous) || null });
      fs.rmSync(lay.downloads, { recursive: true, force: true });
      this.prune(manifest.version, before && before.version !== manifest.version ? before.version : null);
      this.log(`Super Minecraft Galaxy ${manifest.version} is installed`);
      return this.installed();
    } finally {
      this.busy = false;
    }
  }

  /** Rebuilds the module's disc files from the player's disc (skipped when already made from it). */
  async makeDiscFiles(lay, sys, rom) {
    const id = disc.identify(rom);
    if (!id.ok) throw new Error(id.reason);
    const stampFile = path.join(lay.module, '.disc.json');
    const stamp = discStamp(rom);
    const list = readJson(path.join(lay.module, 'patches.json')) || [];
    if (sameStamp(readJson(stampFile), stamp) && list.every((p) => fs.existsSync(path.join(lay.module, ...p.out.split('/'))))) return;
    const tool = path.join(lay.dolphin, sys.tool);
    const work = fs.mkdtempSync(path.join(os.tmpdir(), 'smg-disc-'));
    try {
      let n = 0;
      for (const p of list) {
        this.progress({ phase: 'disc', label: 'Your Super Mario Galaxy 2', done: n, total: list.length, bytes: 0, totalBytes: 0 });
        const out = path.join(work, String(n));
        fs.mkdirSync(out);
        await run(tool, ['extract', '-i', rom, '-s', p.disc, '-o', out, '-q']);
        const found = findFile(out, path.posix.basename(p.disc));
        if (!found) throw new Error(`${p.disc} is not on your disc`);
        let source = fs.readFileSync(found);
        if (p.yaz0) source = disc.yaz0Decompress(source);
        const made = delta.apply(source, fs.readFileSync(path.join(lay.module, ...p.patch.split('/'))));
        const to = path.join(lay.module, ...p.out.split('/'));
        fs.mkdirSync(path.dirname(to), { recursive: true });
        fs.writeFileSync(to, made);
        n++;
      }
      this.progress({ phase: 'disc', label: 'Your Super Mario Galaxy 2', done: n, total: list.length, bytes: 0, totalBytes: 0 });
      writeJson(stampFile, stamp);
      this.log(`Made ${n} files from your Super Mario Galaxy 2`);
    } finally {
      fs.rmSync(work, { recursive: true, force: true });
    }
  }

  /**
   * The soundtrack folder (<data>/soundtrack, shared by every installation): the songs of the
   * default catalog copied from the player's disc once, and tracks.tsv. The music is optional: a
   * failure here is logged and never stops the install or PLAY.
   */
  async makeSoundtrack(lay, sys, rom) {
    try {
      const defaults = fs.readFileSync(this.soundtrackDefaults, 'utf8');
      const tool = path.join(lay.dolphin, sys.tool);
      const r = await soundtrack.install({
        dir: path.join(this.paths.dataDir, 'soundtrack'), defaults,
        extract: async (out) => {
          this.progress({ phase: 'disc', label: 'Your Super Mario Galaxy 2 songs', done: 0, total: 1, bytes: 0, totalBytes: 0 });
          await run(tool, ['extract', '-i', rom, '-s', soundtrack.DISC_DIR, '-o', out, '-q'], { timeout: 600000 });
        },
        onProgress: (p) => this.progress({ phase: 'disc', label: 'Your Super Mario Galaxy 2 songs', ...p, bytes: 0, totalBytes: 0 }),
      });
      if (r.copied) this.log(`Copied ${r.copied} songs from your Super Mario Galaxy 2`);
      if (r.missing.length) this.log(`${r.missing.length} songs are not on your disc: ${r.missing.slice(0, 3).join(', ')}...`);
    } catch (e) {
      this.log(`The soundtrack could not be set up (${e.message}); the game works without it`);
    }
  }

  /** Minecraft ready to run for a manifest: { version, java, dirs }. Quick when nothing is missing. */
  minecraft(manifest, { verify = 'size' } = {}) {
    const lay = gamepack.layout(this.paths, manifest.version);
    return this.ensureMinecraftFn(lay.mc, manifest.minecraft, {
      fetchFn: this.fetchFn, verify, log: (l) => this.log(l),
      onProgress: (p) => this.progress({ ...p, phase: 'minecraft' }),
    });
  }

  /** Before PLAY: the disc files still match the chosen disc (made again if it changed), the descriptor too. */
  async prepare(rom) {
    const i = this.installed();
    if (!i) throw new Error('The game is not installed');
    const sys = i.manifest.dolphin[this.system];
    await this.makeDiscFiles(i.lay, sys, rom);
    await this.makeSoundtrack(i.lay, sys, rom);
    writeJson(i.lay.descriptor, gamepack.descriptor(rom, i.lay.module));
    return { ...i, sys };
  }

  /** The game's mods into an installation's mods folder (the ones we put there before are replaced). */
  syncMods(i, gameDir) {
    const mods = path.join(gameDir, 'mods');
    fs.mkdirSync(mods, { recursive: true });
    const managedFile = path.join(mods, gamepack.MANAGED);
    const before = (readJson(managedFile) || {}).files || [];
    const { add, remove } = gamepack.modChanges(i.manifest, before);
    for (const f of remove) fs.rmSync(path.join(mods, f), { force: true });
    for (const f of add) fs.copyFileSync(path.join(i.lay.mods, f), path.join(mods, f));
    writeJson(managedFile, { files: add, version: i.version });
  }

  /** Keeps the installed version and the one before it. */
  prune(keep, previous) {
    const dir = path.join(this.paths.dataDir, 'game');
    for (const name of fs.readdirSync(dir)) {
      if (!/^\d+\.\d+\.\d+$/.test(name) || name === keep || name === previous) continue;
      fs.rmSync(path.join(dir, name), { recursive: true, force: true });
    }
  }
}

module.exports = { Installer, discStamp };
