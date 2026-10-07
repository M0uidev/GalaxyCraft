'use strict';
// Signing in with a Microsoft account, as the Minecraft Launcher does: Microsoft (OAuth with
// PKCE, in a launcher window) -> Xbox Live -> XSTS -> Minecraft's services, then whether the
// account owns Minecraft: Java Edition and its profile (name, UUID). Tokens are refreshed
// without asking again. fetchFn is injected, so every step is tested without the network.
//
// Needs an Azure app ("Mobile and desktop" redirect https://login.microsoftonline.com/common/
// oauth2/nativeclient) approved by Mojang for Minecraft's services: its client id goes in
// launcher/content/config.json (msaClientId). See launcher/README.md.
const crypto = require('node:crypto');

const MS = 'https://login.microsoftonline.com/consumers/oauth2/v2.0';
const REDIRECT = 'https://login.microsoftonline.com/common/oauth2/nativeclient';
const SCOPE = 'XboxLive.signin offline_access';
const XBL = 'https://user.auth.xboxlive.com/user/authenticate';
const XSTS = 'https://xsts.auth.xboxlive.com/xsts/authorize';
const MC = 'https://api.minecraftservices.com';

class AuthError extends Error {
  constructor(message, code) {
    super(message);
    this.code = code;
  }
}

const b64url = (buf) => buf.toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

function pkce() {
  const verifier = b64url(crypto.randomBytes(48));
  return { verifier, challenge: b64url(crypto.createHash('sha256').update(verifier).digest()), state: b64url(crypto.randomBytes(16)) };
}

function authorizeUrl(clientId, { challenge, state }) {
  const q = new URLSearchParams({
    client_id: clientId, response_type: 'code', redirect_uri: REDIRECT, scope: SCOPE,
    code_challenge: challenge, code_challenge_method: 'S256', state, prompt: 'select_account',
  });
  return `${MS}/authorize?${q}`;
}

/** The code Microsoft sends back to the redirect, or null if the URL is not the redirect yet. */
function codeFromRedirect(url, state) {
  if (!url.startsWith(REDIRECT)) return null;
  const q = new URL(url).searchParams;
  if (q.get('error')) {
    const cancelled = q.get('error') === 'access_denied';
    throw new AuthError(cancelled ? 'Sign-in was cancelled.' : `Microsoft said: ${q.get('error_description') || q.get('error')}`,
      cancelled ? 'cancelled' : 'microsoft');
  }
  if (q.get('state') !== state) throw new AuthError('The sign-in answer did not match (state).', 'state');
  return q.get('code');
}

async function post(fetchFn, url, body, { form = false, headers = {} } = {}) {
  const res = await fetchFn(url, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json', ...headers },
    body: form ? new URLSearchParams(body).toString() : JSON.stringify(body),
  });
  let data = null;
  try { data = await res.json(); } catch { /* empty body */ }
  return { ok: res.ok, status: res.status, data: data || {} };
}

function msTokens(data, now) {
  return { msAccessToken: data.access_token, msRefreshToken: data.refresh_token, msExpiresAt: now + (data.expires_in || 3600) * 1000 };
}

async function exchangeCode(clientId, code, verifier, fetchFn, now = Date.now()) {
  const r = await post(fetchFn, `${MS}/token`, {
    client_id: clientId, grant_type: 'authorization_code', code, redirect_uri: REDIRECT, code_verifier: verifier, scope: SCOPE,
  }, { form: true });
  if (!r.ok) throw new AuthError(`Microsoft sign-in failed: ${r.data.error_description || r.status}`, 'microsoft');
  return msTokens(r.data, now);
}

async function refreshMicrosoft(clientId, refreshToken, fetchFn, now = Date.now()) {
  const r = await post(fetchFn, `${MS}/token`, {
    client_id: clientId, grant_type: 'refresh_token', refresh_token: refreshToken, scope: SCOPE,
  }, { form: true });
  if (!r.ok) throw new AuthError('Your sign-in expired: sign in again.', 'expired');
  return msTokens(r.data, now);
}

const XERR = {
  2148916227: 'This account is banned from Xbox Live.',
  2148916233: 'This Microsoft account has no Xbox profile yet. Sign in once at minecraft.net (or xbox.com) to make one, then try again.',
  2148916235: 'Xbox Live is not available in your country.',
  2148916236: 'This account needs adult verification on xbox.com first.',
  2148916237: 'This account needs adult verification on xbox.com first.',
  2148916238: 'This is a child account: an adult has to add it to a Microsoft Family to play.',
};

/** Microsoft token -> Xbox Live user token -> XSTS token for Minecraft's services. */
async function xbox(msAccessToken, fetchFn) {
  const xbl = await post(fetchFn, XBL, {
    Properties: { AuthMethod: 'RPS', SiteName: 'user.auth.xboxlive.com', RpsTicket: `d=${msAccessToken}` },
    RelyingParty: 'http://auth.xboxlive.com', TokenType: 'JWT',
  });
  if (!xbl.ok) throw new AuthError(`Xbox Live sign-in failed (${xbl.status}).`, 'xbox');
  const xsts = await post(fetchFn, XSTS, {
    Properties: { SandboxId: 'RETAIL', UserTokens: [xbl.data.Token] },
    RelyingParty: 'rp://api.minecraftservices.com/', TokenType: 'JWT',
  });
  if (!xsts.ok) {
    const code = xsts.data.XErr;
    throw new AuthError(XERR[code] || `Xbox Live refused the sign-in (${code || xsts.status}).`, 'xsts');
  }
  const claims = (xsts.data.DisplayClaims && xsts.data.DisplayClaims.xui && xsts.data.DisplayClaims.xui[0]) || {};
  return { uhs: claims.uhs, xuid: claims.xid || '', xstsToken: xsts.data.Token };
}

/** Xbox -> Minecraft: the token, whether the account owns the game, and its profile. */
async function minecraft({ uhs, xstsToken }, fetchFn, now = Date.now()) {
  const login = await post(fetchFn, `${MC}/authentication/login_with_xbox`, { identityToken: `XBL3.0 x=${uhs};${xstsToken}` });
  if (!login.ok) {
    const msg = String(login.data.errorMessage || login.data.error || '');
    if (login.status === 403 || /app registration/i.test(msg)) {
      throw new AuthError('Minecraft refused this launcher\'s sign-in: its app registration is not approved by Mojang yet.', 'app');
    }
    throw new AuthError(`Minecraft sign-in failed (${login.status}).`, 'minecraft');
  }
  const token = login.data.access_token;
  const auth = { headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' } };
  const ent = await fetchFn(`${MC}/entitlements/mcstore`, auth);
  const items = ent.ok ? ((await ent.json()).items || []) : [];
  const owns = items.some((i) => i.name === 'game_minecraft' || i.name === 'product_minecraft');
  const prof = await fetchFn(`${MC}/minecraft/profile`, auth);
  if (prof.status === 404) {
    throw new AuthError(owns ? 'Choose your Minecraft name at minecraft.net first (this account has no profile yet).'
      : 'This account does not own Minecraft: Java Edition.', owns ? 'profile' : 'ownership');
  }
  if (!prof.ok) throw new AuthError(`Could not read your Minecraft profile (${prof.status}).`, 'profile');
  const p = await prof.json();
  if (!owns && !p.id) throw new AuthError('This account does not own Minecraft: Java Edition.', 'ownership');
  const skin = (p.skins || []).find((s) => s.state === 'ACTIVE') || (p.skins || [])[0];
  return {
    uuid: p.id,
    name: p.name,
    skinUrl: skin ? skin.url : '',
    mcAccessToken: token,
    mcExpiresAt: now + (login.data.expires_in || 86400) * 1000,
  };
}

/** The whole chain from a Microsoft token: the account to keep. */
async function fromMicrosoft(ms, fetchFn, now = Date.now()) {
  const x = await xbox(ms.msAccessToken, fetchFn);
  const m = await minecraft(x, fetchFn, now);
  return { ...m, xuid: x.xuid, msRefreshToken: ms.msRefreshToken, signedInAt: now };
}

/** The account, with a Minecraft token good for at least 10 more minutes (refreshed if needed). */
async function fresh(account, clientId, fetchFn, now = Date.now()) {
  if (account.mcAccessToken && account.mcExpiresAt - now > 10 * 60 * 1000) return account;
  if (!account.msRefreshToken) throw new AuthError('Your sign-in expired: sign in again.', 'expired');
  const ms = await refreshMicrosoft(clientId, account.msRefreshToken, fetchFn, now);
  const next = await fromMicrosoft(ms, fetchFn, now);
  return { ...account, ...next, signedInAt: account.signedInAt };
}

/**
 * fetchFn that also logs each sign-in request: where it went, its status and, when refused, what
 * the service said (error bodies carry no tokens; capped anyway). For the launcher's log.
 */
function loggingFetch(fetchFn, log) {
  return async (url, opts = {}) => {
    const u = new URL(url);
    const where = `${opts.method || 'GET'} ${u.host}${u.pathname}`;
    let res;
    try { res = await fetchFn(url, opts); } catch (e) { log(`Sign-in: ${where} failed: ${e.message}`); throw e; }
    let said = '';
    if (!res.ok && res.clone) {
      try { said = (await res.clone().text()).replace(/\s+/g, ' ').slice(0, 400); } catch { /* no body */ }
    }
    log(`Sign-in: ${where} -> ${res.status}${said ? ` ${said}` : ''}`);
    return res;
  };
}

/** What may be shown and kept in the clear (no tokens). */
const publicAccount = (a) => ({ uuid: a.uuid, name: a.name, skinUrl: a.skinUrl || '' });

module.exports = {
  REDIRECT, AuthError, pkce, authorizeUrl, codeFromRedirect, exchangeCode, refreshMicrosoft, xbox, minecraft,
  fromMicrosoft, fresh, publicAccount, loggingFetch,
};
