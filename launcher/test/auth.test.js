'use strict';
// The sign-in chain against fake Microsoft, Xbox and Minecraft services.
const test = require('node:test');
const assert = require('node:assert/strict');
const auth = require('../src/core/auth');

const json = (status, body) => ({ ok: status < 400, status, json: async () => body });

function services(over = {}) {
  const calls = [];
  const fetchFn = async (url, opts = {}) => {
    calls.push({ url, opts });
    const route = Object.keys(over).find((k) => url.includes(k));
    if (route) return over[route](url, opts);
    if (url.endsWith('/token')) {
      const body = new URLSearchParams(opts.body);
      if (body.get('grant_type') === 'refresh_token' && body.get('refresh_token') === 'dead') return json(400, { error: 'invalid_grant' });
      return json(200, { access_token: `ms-${body.get('grant_type')}`, refresh_token: 'refresh-2', expires_in: 3600 });
    }
    if (url.includes('user.auth.xboxlive.com')) {
      assert.equal(JSON.parse(opts.body).Properties.RpsTicket.startsWith('d=ms-'), true);
      return json(200, { Token: 'xbl', DisplayClaims: { xui: [{ uhs: 'uhs1' }] } });
    }
    if (url.includes('xsts.auth.xboxlive.com')) return json(200, { Token: 'xsts', DisplayClaims: { xui: [{ uhs: 'uhs1', xid: '2535' }] } });
    if (url.endsWith('/authentication/login_with_xbox')) {
      assert.equal(JSON.parse(opts.body).identityToken, 'XBL3.0 x=uhs1;xsts');
      return json(200, { access_token: 'mc-token', expires_in: 86400 });
    }
    if (url.endsWith('/entitlements/mcstore')) return json(200, { items: [{ name: 'product_minecraft' }, { name: 'game_minecraft' }] });
    if (url.endsWith('/minecraft/profile')) {
      assert.equal(opts.headers.Authorization, 'Bearer mc-token');
      return json(200, { id: 'abcdef0123456789abcdef0123456789', name: 'Moui', skins: [{ state: 'ACTIVE', url: 'https://textures.minecraft.net/texture/x' }] });
    }
    return json(404, {});
  };
  return { fetchFn, calls };
}

test('PKCE and the authorize URL', () => {
  const p = auth.pkce();
  assert.ok(p.verifier.length >= 43);
  const url = new URL(auth.authorizeUrl('client-1', p));
  assert.equal(url.searchParams.get('client_id'), 'client-1');
  assert.equal(url.searchParams.get('code_challenge_method'), 'S256');
  assert.equal(url.searchParams.get('redirect_uri'), auth.REDIRECT);
  assert.match(url.searchParams.get('scope'), /XboxLive\.signin offline_access/);
});

test('the redirect gives the code; errors and a wrong state are refused', () => {
  assert.equal(auth.codeFromRedirect('https://login.live.com/other', 's'), null);
  assert.equal(auth.codeFromRedirect(`${auth.REDIRECT}?code=C1&state=s`, 's'), 'C1');
  assert.throws(() => auth.codeFromRedirect(`${auth.REDIRECT}?code=C1&state=x`, 's'), /did not match/);
  assert.throws(() => auth.codeFromRedirect(`${auth.REDIRECT}?error=access_denied&state=s`, 's'), (e) => e.code === 'cancelled');
});

test('the whole chain: Microsoft -> Xbox -> XSTS -> Minecraft, owned, with a profile', async () => {
  const { fetchFn } = services();
  const ms = await auth.exchangeCode('client-1', 'C1', 'verifier', fetchFn, 1000);
  assert.equal(ms.msRefreshToken, 'refresh-2');
  const account = await auth.fromMicrosoft(ms, fetchFn, 1000);
  assert.equal(account.name, 'Moui');
  assert.equal(account.uuid, 'abcdef0123456789abcdef0123456789');
  assert.equal(account.mcAccessToken, 'mc-token');
  assert.equal(account.xuid, '2535');
  assert.equal(account.mcExpiresAt, 1000 + 86400 * 1000);
  assert.deepEqual(auth.publicAccount(account), { uuid: account.uuid, name: 'Moui', skinUrl: 'https://textures.minecraft.net/texture/x' });
});

test('tokens are refreshed only when they are about to expire', async () => {
  const { fetchFn, calls } = services();
  const account = { name: 'Moui', mcAccessToken: 'old', mcExpiresAt: 10 * 3600 * 1000, msRefreshToken: 'refresh-1', signedInAt: 5 };
  assert.equal(await auth.fresh(account, 'client-1', fetchFn, 0), account);
  assert.equal(calls.length, 0);
  const renewed = await auth.fresh(account, 'client-1', fetchFn, 10 * 3600 * 1000 - 60000);
  assert.equal(renewed.mcAccessToken, 'mc-token');
  assert.equal(renewed.msRefreshToken, 'refresh-2');
  assert.equal(renewed.signedInAt, 5);
  await assert.rejects(auth.fresh({ ...account, msRefreshToken: 'dead', mcExpiresAt: 0 }, 'client-1', fetchFn, 1), (e) => e.code === 'expired');
});

test('what can go wrong is said plainly', async () => {
  const ms = { msAccessToken: 'ms-code', msRefreshToken: 'r' };
  let s = services({ 'xsts.auth': () => json(401, { XErr: 2148916233 }) });
  await assert.rejects(auth.fromMicrosoft(ms, s.fetchFn), /no Xbox profile yet/);
  s = services({ 'xsts.auth': () => json(401, { XErr: 2148916238 }) });
  await assert.rejects(auth.fromMicrosoft(ms, s.fetchFn), /child account/);
  s = services({ login_with_xbox: () => json(403, { errorMessage: 'Invalid app registration, see https://aka.ms/AppRegInfo' }) });
  await assert.rejects(auth.fromMicrosoft(ms, s.fetchFn), (e) => e.code === 'app');
  s = services({ 'minecraft/profile': () => json(404, {}), mcstore: () => json(200, { items: [] }) });
  await assert.rejects(auth.fromMicrosoft(ms, s.fetchFn), (e) => e.code === 'ownership');
  s = services({ 'minecraft/profile': () => json(404, {}) });
  await assert.rejects(auth.fromMicrosoft(ms, s.fetchFn), (e) => e.code === 'profile');
});
