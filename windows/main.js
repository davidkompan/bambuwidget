'use strict';
const { app, BrowserWindow, Tray, Menu, ipcMain, Notification, nativeImage, safeStorage, shell, screen } = require('electron');
const path = require('path');
const fs = require('fs');
const { Login, sessionFromToken } = require('./src/cloud');
const { Hub } = require('./src/hub');

if (!app.requestSingleInstanceLock()) { app.quit(); process.exit(0); }
app.setAppUserModelId('BambuWidget');

const file = (n) => path.join(app.getPath('userData'), n);
const asset = (n) => path.join(__dirname, 'assets', n);
// Started as electron.exe <app folder>, so autostart must pass the folder too.
const LOGIN_ITEM = { path: process.execPath, args: app.isPackaged ? [] : [path.resolve(__dirname)] };

let win = null;
let tray = null;
let login = null;
let view = { screen: 'login', connection: null, message: null, printers: [] };
// order: serials in display order; hidden: serials not shown in the widget.
let settings = { bounds: null, alwaysOnTop: false, notify: true, locked: [], order: [], hidden: [] };
const previous = new Map();
let accounts = [];        // {id, region, token, username, label, expiresAt, expired}
const runs = new Map();   // account id -> {hub, view, retry}
let locked = new Set();   // shared by all hubs
let adding = false;       // sign-in screen opened from Settings › Add account
let loginMessage = null;

// ---------- persistence ----------
function loadSettings() {
  try { settings = { ...settings, ...JSON.parse(fs.readFileSync(file('settings.json'), 'utf8')) }; } catch (_) { /* first run */ }
}
function saveSettings() {
  try { fs.writeFileSync(file('settings.json'), JSON.stringify(settings, null, 2)); } catch (_) { /* ignore */ }
}
function readSecret(n) {
  try {
    const buf = fs.readFileSync(file(n));
    return JSON.parse(safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(buf) : buf.toString('utf8'));
  } catch (_) { return null; }
}
function saveAccounts() {
  const data = JSON.stringify(accounts);
  const buf = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(data) : Buffer.from(data, 'utf8');
  try { fs.writeFileSync(file('accounts.bin'), buf); } catch (_) { /* ignore */ }
}
function loadAccounts() {
  let list = readSecret('accounts.bin');
  if (!Array.isArray(list)) {
    // Version 1.0 kept a single session in session.bin.
    const s = readSecret('session.bin');
    list = s && s.token ? [{ ...s, id: accountId(s), label: s.username }] : [];
    if (list.length) { accounts = list; saveAccounts(); }
    try { fs.unlinkSync(file('session.bin')); } catch (_) { /* none */ }
  }
  accounts = list.filter((a) => a && a.id).map((a) => (a.token && a.expiresAt > Date.now() ? a : { ...a, token: '', expired: true }));
}
const accountId = (s) => `${s.region}:${s.username}`;

// ---------- window & tray ----------
function createWindow() {
  const b = settings.bounds;
  const area = screen.getPrimaryDisplay().workArea;
  win = new BrowserWindow({
    width: b ? b.width : 380, height: b ? b.height : 520,
    x: b ? b.x : area.x + area.width - 400, y: b ? b.y : area.y + 20,
    minWidth: 300, minHeight: 200,
    frame: false, skipTaskbar: true, show: false, resizable: true,
    alwaysOnTop: settings.alwaysOnTop, backgroundColor: '#15171c',
    icon: asset('icon.png'), title: 'Bambu Widget',
    webPreferences: { preload: path.join(__dirname, 'preload.js'), contextIsolation: true, nodeIntegration: false, sandbox: true },
  });
  win.loadFile(path.join(__dirname, 'renderer', 'index.html'));
  win.once('ready-to-show', () => win.show());
  const remember = () => { if (win && !win.isMinimized()) { settings.bounds = win.getBounds(); saveSettings(); } };
  win.on('moved', remember);
  win.on('resized', remember);
  win.on('close', (e) => { if (!app.isQuitting) { e.preventDefault(); win.hide(); } });
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (e) => e.preventDefault());
}

function toggleWindow() {
  if (!win) return;
  if (win.isVisible() && win.isFocused()) win.hide();
  else { win.show(); win.focus(); }
}

function trayIcon(kind) {
  const img = nativeImage.createFromPath(asset(`tray-${kind}.png`));
  img.addRepresentation({ scaleFactor: 2, buffer: fs.readFileSync(asset(`tray-${kind}@2x.png`)) });
  return img;
}

function buildTrayMenu() {
  const autoStart = app.getLoginItemSettings(LOGIN_ITEM).openAtLogin;
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Show / hide', click: toggleWindow },
    { label: 'Settings…', click: openSettings },
    { label: 'Refresh status', click: refreshAll },
    { type: 'separator' },
    { label: 'Always on top', type: 'checkbox', checked: settings.alwaysOnTop, click: (i) => setAlwaysOnTop(i.checked) },
    { label: 'Notifications', type: 'checkbox', checked: settings.notify, click: (i) => { settings.notify = i.checked; saveSettings(); } },
    { label: 'Start with Windows', type: 'checkbox', checked: autoStart, click: (i) => app.setLoginItemSettings({ ...LOGIN_ITEM, openAtLogin: i.checked }) },
    { label: 'Reset window position', click: () => { settings.bounds = null; saveSettings(); const a = screen.getPrimaryDisplay().workArea; win.setBounds({ x: a.x + a.width - 400, y: a.y + 20, width: 380, height: 520 }); win.show(); } },
    { type: 'separator' },
    { label: accounts.length > 1 ? 'Sign out of all accounts' : 'Sign out', click: logout },
    { label: 'Quit', click: () => { app.isQuitting = true; app.quit(); } },
  ]));
}

function setAlwaysOnTop(on) {
  settings.alwaysOnTop = !!on; saveSettings();
  if (win) win.setAlwaysOnTop(settings.alwaysOnTop);
  buildTrayMenu(); send();
}

function updateTray() {
  if (!tray) return;
  const ps = view.printers || [];
  let kind = 'idle';
  if (ps.some((p) => p.state === 'printing' || p.state === 'preparing')) kind = 'busy';
  if (ps.some((p) => p.state === 'finished')) kind = ps.some((p) => p.state === 'printing') ? 'busy' : 'ok';
  if (ps.some((p) => p.state === 'paused')) kind = 'warn';
  if (ps.some((p) => p.state === 'failed' || p.errors.some((e) => e.severity === 'fatal' || e.severity === 'serious'))) kind = 'error';
  tray.setImage(trayIcon(kind));
  const lines = ps.map((p) => `${p.name}: ${p.label}${p.percent !== null && p.state !== 'idle' ? ` ${p.percent} %` : ''}${p.errors.length ? ' ⚠' : ''}`);
  tray.setToolTip((lines.join('\n') || 'Bambu Widget').slice(0, 127));
}

// ---------- notifications ----------
function notify(title, body) {
  if (!settings.notify || !Notification.isSupported()) return;
  const n = new Notification({ title, body, icon: asset('icon.png') });
  n.on('click', () => { win.show(); win.focus(); });
  n.show();
}

function checkTransitions() {
  for (const p of view.printers) {
    const prev = previous.get(p.serial);
    previous.set(p.serial, p);
    if (!prev || !prev.received || !p.received) continue;
    if (prev.state !== p.state) {
      if (p.state === 'failed') notify(`${p.name}: print failed`, p.errors[0] ? `${p.errors[0].code}: ${p.errors[0].text}` : (p.file || ''));
      else if (p.state === 'finished') notify(`${p.name}: print finished`, p.file || '');
      else if (p.state === 'paused') notify(`${p.name}: print paused`, p.stage || (p.errors[0] ? p.errors[0].text : ''));
    }
    const before = new Set(prev.errors.map((e) => e.code));
    for (const e of p.errors) if (!before.has(e.code)) notify(`${p.name}: ${e.code}`, e.text);
  }
}

// ---------- accounts & hubs ----------
function send() {
  if (win && !win.isDestroyed()) win.webContents.send('view', { ...view, alwaysOnTop: settings.alwaysOnTop });
}

function openSettings() {
  if (!win) return;
  win.show(); win.focus();
  win.webContents.send('openSettings');
}

const RANK = { online: 0, connecting: 1, offline: 2 };

/** Merges the accounts' printers into one list in the user's order and builds what the window shows. */
function compose() {
  const accountList = accounts.map((a) => {
    const r = runs.get(a.id);
    return { id: a.id, label: a.label, region: a.region, status: a.expired ? 'expired' : (r ? r.view.connection : 'connecting'),
      printers: r ? r.view.printers.length : 0 };
  });
  if (adding || !accounts.some((a) => !a.expired)) {
    view = { screen: 'login', adding: adding && accounts.some((a) => !a.expired), connection: null,
      message: loginMessage, printers: [], all: [], accounts: accountList };
    return;
  }
  const multi = accounts.length > 1;
  const bySerial = new Map();
  const notes = [];
  let connection = 'online';
  for (const a of accounts) {
    if (a.expired) { notes.push(`${a.label}: sign-in expired. Open Settings to sign in again.`); continue; }
    const r = runs.get(a.id);
    const v = r ? r.view : { connection: 'connecting', message: null, printers: [] };
    if ((RANK[v.connection] || 0) > RANK[connection]) connection = v.connection;
    if (v.message) notes.push(multi ? `${a.label}: ${v.message}` : v.message);
    // A printer shared with several of the accounts is shown once.
    for (const p of v.printers) if (!bySerial.has(p.serial)) bySerial.set(p.serial, { ...p, account: a.label });
  }
  let added = false;
  for (const s of bySerial.keys()) if (!settings.order.includes(s)) { settings.order.push(s); added = true; }
  if (added) saveSettings();
  const all = settings.order.filter((s) => bySerial.has(s)).map((s) => bySerial.get(s));
  const hidden = new Set(settings.hidden);
  view = {
    screen: 'main', connection, message: notes.join('\n') || null, multi,
    printers: all.filter((p) => !hidden.has(p.serial)),
    all: all.map((p) => ({ serial: p.serial, name: p.name, model: p.model, account: p.account, hidden: hidden.has(p.serial) })),
    accounts: accountList,
  };
}

function refresh() { compose(); checkTransitions(); updateTray(); send(); }

function stopAccount(id) {
  const r = runs.get(id);
  if (!r) return;
  r.hub.stop(); clearTimeout(r.retry); runs.delete(id);
}

function expire(a, message) {
  stopAccount(a.id);
  a.expired = true; a.token = '';
  saveAccounts();
  loginMessage = accounts.length > 1 ? `${a.label}: ${message}` : message;
  buildTrayMenu(); refresh();
}

async function startAccount(a) {
  stopAccount(a.id);
  const hub = new Hub(a, locked);
  const run = { hub, view: { connection: 'connecting', message: 'Loading printers…', printers: [] }, retry: null };
  runs.set(a.id, run);
  hub.on('locked', () => { settings.locked = [...locked]; saveSettings(); });
  hub.on('update', (v) => {
    if (runs.get(a.id) !== run) return;
    if (v.connection === 'auth') { expire(a, v.message); return; }
    run.view = v; refresh();
  });
  refresh();
  try { await hub.start(); }
  catch (e) {
    if (runs.get(a.id) !== run) return;
    if (e.auth) { expire(a, e.message); return; }
    run.view = { ...run.view, connection: 'offline', message: e.message }; refresh();
    run.retry = setTimeout(() => { if (runs.get(a.id) === run) startAccount(a); }, 60000);
  }
}

function refreshAll() {
  for (const r of runs.values()) r.hub.refreshDevices().then(() => r.hub.pushAllEverywhere()).catch(() => {});
}

function hubFor(serial) {
  for (const r of runs.values()) if (r.hub.printers.has(serial)) return r.hub;
  return null;
}

function removeAccount(id) {
  stopAccount(id);
  accounts = accounts.filter((a) => a.id !== id);
  saveAccounts(); buildTrayMenu(); refresh();
}

function logout() {
  for (const id of [...runs.keys()]) stopAccount(id);
  accounts = []; saveAccounts(); previous.clear();
  adding = false; loginMessage = null;
  buildTrayMenu(); refresh();
}

function signedIn(sess, label) {
  const id = accountId(sess);
  const old = accounts.find((a) => a.id === id);
  const a = { ...sess, id, label: String(label || '').trim() || (old && old.label) || sess.username };
  accounts = old ? accounts.map((x) => (x.id === id ? a : x)) : [...accounts, a];
  saveAccounts();
  adding = false; loginMessage = null;
  buildTrayMenu();
  startAccount(a);
  return { status: 'ok' };
}

function setPrinters(order, hidden) {
  const strings = (l) => (Array.isArray(l) ? l.filter((s) => typeof s === 'string') : []);
  const o = strings(order);
  settings.order = [...o, ...settings.order.filter((s) => !o.includes(s))];
  settings.hidden = strings(hidden);
  saveSettings(); refresh();
}

// ---------- IPC ----------
const wrap = (fn) => async (_e, ...args) => {
  try { return await fn(...args); } catch (e) { return { status: 'error', message: e.message }; }
};
ipcMain.handle('getView', () => ({ ...view, alwaysOnTop: settings.alwaysOnTop }));
ipcMain.handle('login', wrap(async (email, password, region) => {
  login = new Login();
  const r = await login.start(email, password, region);
  return r.status === 'ok' ? signedIn(r.session, login.email) : r;
}));
ipcMain.handle('verifyCode', wrap(async (code) => signedIn(await login.verifyCode(code), login.email)));
ipcMain.handle('verifyTfa', wrap(async (code) => signedIn(await login.verifyTfa(code), login.email)));
ipcMain.handle('resendCode', wrap(async () => { await login.sendCode(); return { status: 'code' }; }));
ipcMain.handle('useToken', wrap(async (token, region, label) => signedIn(await sessionFromToken(region === 'china' ? 'china' : 'global', token, 0), label)));
ipcMain.handle('addAccount', () => { adding = true; loginMessage = null; refresh(); });
ipcMain.handle('cancelAdd', () => { adding = false; loginMessage = null; refresh(); });
ipcMain.handle('removeAccount', (_e, id) => removeAccount(String(id)));
ipcMain.handle('setPrinters', (_e, order, hidden) => setPrinters(order, hidden));
ipcMain.handle('command', (_e, serial, cmd) => { const h = hubFor(String(serial)); if (h) h.command(String(serial), String(cmd)); });
ipcMain.handle('unlock', (_e, serial) => { const h = hubFor(String(serial)); if (h) h.unlock(String(serial)); });
ipcMain.handle('setAlwaysOnTop', (_e, on) => setAlwaysOnTop(on));
ipcMain.handle('hide', () => win && win.hide());
ipcMain.handle('logout', () => logout());
ipcMain.handle('openWiki', (_e, url) => {
  if (typeof url === 'string' && /^https:\/\/wiki\.bambulab\.com\//.test(url)) shell.openExternal(url);
});

// ---------- lifecycle ----------
app.on('second-instance', () => { if (win) { win.show(); win.focus(); } });
app.on('window-all-closed', (e) => e.preventDefault());

app.whenReady().then(() => {
  loadSettings();
  locked = new Set(settings.locked);
  loadAccounts();
  createWindow();
  tray = new Tray(trayIcon('idle'));
  tray.setToolTip('Bambu Widget');
  tray.on('click', toggleWindow);
  buildTrayMenu();
  for (const a of accounts) if (!a.expired) startAccount(a);
  refresh();
});
