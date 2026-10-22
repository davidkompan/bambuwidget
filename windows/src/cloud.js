'use strict';
// Bambu Lab account API (same endpoints Bambu Studio / OrcaSlicer use).
// Sign-in flow adapted from bambu-taskbar (MIT, Dawn-Of-Justice).
const { session } = require('electron');

const HEADERS = {
  'Accept': 'application/json',
  'Content-Type': 'application/json',
  'X-BBL-Client-Name': 'OrcaSlicer',
  'X-BBL-Client-Type': 'slicer',
  'X-BBL-Client-Version': '01.09.05.51',
  'X-BBL-Language': 'en-US',
  'X-BBL-OS-Type': 'windows',
};
const TOKEN_LIFETIME_S = 90 * 24 * 3600;

const apiBase = (r) => (r === 'china' ? 'https://api.bambulab.cn' : 'https://api.bambulab.com');
const webBase = (r) => (r === 'china' ? 'https://bambulab.cn' : 'https://bambulab.com');
const mqttHost = (r) => (r === 'china' ? 'cn.mqtt.bambulab.com' : 'us.mqtt.bambulab.com');

let ses = null;
function http() {
  // Chromium network stack: Bambu's API is behind Cloudflare, which prefers browser-like clients.
  if (!ses) {
    ses = session.fromPartition('bambu-cloud', { cache: false });
    ses.setUserAgent(ses.getUserAgent().replace(/ Electron\/\S+/, '').replace(/ bambu-widget\/\S+/i, ''));
  }
  return ses;
}

async function request(url, { method = 'GET', headers = HEADERS, body } = {}) {
  let res;
  try {
    res = await http().fetch(url, {
      method, headers, credentials: 'include', redirect: 'follow',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch (e) {
    throw new Error(`Cannot reach Bambu Lab (${e.message}). Check your internet connection.`);
  }
  const text = await res.text();
  if ((res.status === 403 || res.status === 429) && /cloudflare/i.test(text)) {
    throw new Error('Bambu Lab servers blocked the request for now. Wait a few minutes and try again.');
  }
  let json = {};
  try { json = JSON.parse(text) || {}; } catch (_) { /* not JSON */ }
  const cookies = {};
  for (const c of await http().cookies.get({ url })) cookies[c.name] = c.value;
  return { status: res.status, json, cookies };
}

function apiError(res, fallback) {
  const d = [res.json.message, res.json.error].find((v) => typeof v === 'string' && v && v !== 'success');
  return new Error(d ? `${fallback}: ${d}` : `${fallback} (HTTP ${res.status}).`);
}

function usernameFromToken(token) {
  const p = String(token).split('.');
  if (p.length !== 3) return null;
  try {
    const j = JSON.parse(Buffer.from(p[1].replace(/-/g, '+').replace(/_/g, '/'), 'base64').toString('utf8'));
    return typeof j.username === 'string' && /^u_\d+$/.test(j.username) ? j.username : null;
  } catch (_) { return null; }
}

async function sessionFromToken(region, token, expiresIn) {
  token = String(token || '').trim();
  if (!token) throw new Error('The token is empty.');
  let username = usernameFromToken(token);
  if (!username) {
    const res = await request(`${apiBase(region)}/v1/design-user-service/my/preference`,
      { headers: { ...HEADERS, Authorization: `Bearer ${token}` } });
    if (res.status === 401 || res.status === 403) throw new Error('Bambu Lab did not accept the token.');
    if (res.json.uid !== undefined) username = `u_${res.json.uid}`;
  }
  if (!username) throw new Error('Could not read your Bambu user ID.');
  const life = expiresIn > 0 ? expiresIn : TOKEN_LIFETIME_S;
  return { region, token, username, expiresAt: Date.now() + life * 1000 };
}

class Login {
  constructor() { this.email = ''; this.region = 'global'; this.tfaKey = null; }

  async start(email, password, region) {
    this.email = String(email || '').trim();
    this.region = region === 'china' ? 'china' : 'global';
    this.tfaKey = null;
    if (!this.email || !password) throw new Error('Enter your email and password.');
    await http().clearStorageData({ storages: ['cookies'] });
    const res = await request(`${apiBase(this.region)}/v1/user-service/user/login`,
      { method: 'POST', body: { account: this.email, password, apiError: '' } });
    if (res.status >= 400) throw apiError(res, 'Sign-in failed');
    if (res.json.accessToken) return { status: 'ok', session: await sessionFromToken(this.region, res.json.accessToken, +res.json.expiresIn) };
    if (res.json.loginType === 'verifyCode') { await this.sendCode(); return { status: 'code' }; }
    if (res.json.loginType === 'tfa' && res.json.tfaKey) { this.tfaKey = res.json.tfaKey; return { status: 'tfa' }; }
    throw new Error('Unexpected sign-in response from Bambu Lab.');
  }

  async sendCode() {
    const res = await request(`${apiBase(this.region)}/v1/user-service/user/sendemail/code`,
      { method: 'POST', body: { email: this.email, type: 'codeLogin' } });
    if (res.status >= 400) throw apiError(res, 'Could not send the code');
  }

  async verifyCode(code) {
    const res = await request(`${apiBase(this.region)}/v1/user-service/user/login`,
      { method: 'POST', body: { account: this.email, code: String(code).trim() } });
    if (res.status === 400 && res.json.code === 1) { await this.sendCode(); throw new Error('The code has expired. A new one has been emailed to you.'); }
    if (res.status === 400 && res.json.code === 2) throw new Error('Wrong code.');
    if (res.status >= 400 || !res.json.accessToken) throw apiError(res, 'Code verification failed');
    return sessionFromToken(this.region, res.json.accessToken, +res.json.expiresIn);
  }

  async verifyTfa(code) {
    if (!this.tfaKey) throw new Error('Start signing in again.');
    const web = webBase(this.region);
    const csrf = await request(`${web}/api/csrf`);
    const token = csrf.cookies.bbl_csrf_token;
    if (!token) throw new Error('Could not start two-factor sign-in.');
    const res = await request(`${web}/api/sign-in/tfa`, {
      method: 'POST', headers: { ...HEADERS, 'x-bbl-csrf-token': token },
      body: { tfaKey: this.tfaKey, tfaCode: String(code).trim() },
    });
    if (res.status >= 400 || !res.cookies.token) throw new Error('Wrong authenticator code.');
    return sessionFromToken(this.region, res.cookies.token, 0);
  }
}

async function listPrinters(sess) {
  const res = await request(`${apiBase(sess.region)}/v1/iot-service/api/user/bind`,
    { headers: { ...HEADERS, Authorization: `Bearer ${sess.token}` } });
  if (res.status === 401 || res.status === 403) {
    const e = new Error('Your sign-in has expired. Please sign in again.'); e.auth = true; throw e;
  }
  if (res.status >= 400 || !Array.isArray(res.json.devices)) throw apiError(res, 'Could not load the printer list');
  return res.json.devices.filter((d) => d.dev_id).map((d) => ({
    serial: String(d.dev_id),
    name: d.name || String(d.dev_id),
    model: d.dev_product_name || d.dev_model_name || '',
    online: d.online === true,
  }));
}

module.exports = { Login, sessionFromToken, listPrinters, mqttHost };
