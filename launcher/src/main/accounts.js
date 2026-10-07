'use strict';
// The player's Minecraft accounts: signed in through Microsoft in a launcher window, kept in
// the launcher's folder with their tokens encrypted by the system's keychain (Electron's
// safeStorage), refreshed before PLAY.
const { app, BrowserWindow, safeStorage, session } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const auth = require('../core/auth');

class Accounts {
  constructor({ file, clientId, fetchFn, parent }) {
    this.file = file;
    this.clientId = clientId;
    this.fetchFn = fetchFn;
    this.parent = parent;
    this.data = { selected: null, accounts: [] };
    try { this.data = { ...this.data, ...JSON.parse(fs.readFileSync(file, 'utf8')) }; } catch { /* none yet */ }
    // Tests (smoke, from source only) sign in a made-up account without the network. Never in an
    // installed launcher: that would be playing without owning Minecraft.
    if (process.env.GXL_TEST_ACCOUNT && !app.isPackaged) {
      const a = JSON.parse(process.env.GXL_TEST_ACCOUNT);
      this.test = { ...a, mcExpiresAt: Date.now() + 86400000 };
    }
  }

  get available() { return !!this.clientId || !!this.test; }

  seal(secret) {
    const text = JSON.stringify(secret);
    if (safeStorage.isEncryptionAvailable()) return { enc: 'safe', data: safeStorage.encryptString(text).toString('base64') };
    return { enc: 'plain', data: Buffer.from(text).toString('base64') };
  }

  open(sealed) {
    const buf = Buffer.from(sealed.data, 'base64');
    return JSON.parse(sealed.enc === 'safe' ? safeStorage.decryptString(buf) : buf.toString());
  }

  save() {
    fs.mkdirSync(path.dirname(this.file), { recursive: true });
    const tmp = `${this.file}.tmp`;
    fs.writeFileSync(tmp, JSON.stringify(this.data, null, 2), { mode: 0o600 });
    fs.renameSync(tmp, this.file);
  }

  /** The signed-in account, without its tokens, or null. */
  current() {
    if (this.test) return auth.publicAccount(this.test);
    const a = this.data.accounts.find((x) => x.uuid === this.data.selected);
    return a ? auth.publicAccount(a) : null;
  }

  list() { return this.data.accounts.map(auth.publicAccount); }

  remember(account) {
    const { mcAccessToken, mcExpiresAt, msRefreshToken, xuid } = account;
    const entry = { ...auth.publicAccount(account), secret: this.seal({ mcAccessToken, mcExpiresAt, msRefreshToken, xuid }) };
    this.data.accounts = [entry, ...this.data.accounts.filter((x) => x.uuid !== account.uuid)];
    this.data.selected = account.uuid;
    this.save();
  }

  select(uuid) {
    if (this.data.accounts.some((x) => x.uuid === uuid)) { this.data.selected = uuid; this.save(); }
  }

  signOut(uuid = this.data.selected) {
    this.data.accounts = this.data.accounts.filter((x) => x.uuid !== uuid);
    if (this.data.selected === uuid) this.data.selected = this.data.accounts[0] ? this.data.accounts[0].uuid : null;
    this.save();
  }

  /** The current account with fresh tokens, for PLAY: { name, uuid, accessToken, xuid, clientId }. */
  async session() {
    if (this.test) return { name: this.test.name, uuid: this.test.uuid, accessToken: 'test', xuid: '0', clientId: '' };
    const entry = this.data.accounts.find((x) => x.uuid === this.data.selected);
    if (!entry) throw new auth.AuthError('Sign in with your Microsoft account first.', 'signin');
    let account = { ...entry, ...this.open(entry.secret) };
    const renewed = await auth.fresh(account, this.clientId, this.fetchFn);
    if (renewed !== account) { this.remember(renewed); account = renewed; }
    return { name: account.name, uuid: account.uuid, accessToken: account.mcAccessToken, xuid: account.xuid || '0', clientId: this.clientId };
  }

  /** Opens Microsoft's sign-in in a launcher window; resolves to the signed-in account (public part). */
  signIn() {
    if (!this.clientId) {
      return Promise.reject(new auth.AuthError('Microsoft sign-in is not set up in this build of the launcher yet.', 'config'));
    }
    const p = auth.pkce();
    const win = new BrowserWindow({
      parent: this.parent || undefined, modal: !!this.parent, width: 520, height: 700, title: 'Sign in with Microsoft',
      autoHideMenuBar: true, backgroundColor: '#ffffff',
      webPreferences: { session: session.fromPartition(`msa-${Date.now()}`), sandbox: true, contextIsolation: true, nodeIntegration: false },
    });
    win.setMenu(null);
    return new Promise((resolve, reject) => {
      let done = false;
      const finish = (err, value) => {
        if (done) return;
        done = true;
        if (!win.isDestroyed()) win.close();
        if (err) reject(err); else resolve(value);
      };
      const onUrl = async (event, url) => {
        let code;
        try { code = auth.codeFromRedirect(url, p.state); } catch (e) { finish(e); return; }
        if (!code) return;
        if (event && event.preventDefault) event.preventDefault();
        try {
          const ms = await auth.exchangeCode(this.clientId, code, p.verifier, this.fetchFn);
          const account = await auth.fromMicrosoft(ms, this.fetchFn);
          this.remember(account);
          finish(null, auth.publicAccount(account));
        } catch (e) {
          finish(e);
        }
      };
      win.webContents.on('will-redirect', onUrl);
      win.webContents.on('will-navigate', onUrl);
      win.webContents.on('did-navigate', onUrl);
      win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
      win.on('closed', () => finish(new auth.AuthError('Sign-in was cancelled.', 'cancelled')));
      win.loadURL(auth.authorizeUrl(this.clientId, p));
    });
  }
}

module.exports = { Accounts };
