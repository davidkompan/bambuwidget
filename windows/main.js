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
let hub = null;
let login = null;
let view = { screen: 'login', connection: null, message: null, printers: [] };
let settings = { bounds: null, alwaysOnTop: false, notify: true, locked: [] };
const previous = new Map();

// ---------- persistence ----------
function loadSettings() {
  try { settings = { ...settings, ...JSON.parse(fs.readFileSync(file('settings.json'), 'utf8')) }; } catch (_) { /* first run */ }
}
function saveSettings() {
  try { fs.writeFileSync(file('settings.json'), JSON.stringify(settings, null, 2)); } catch (_) { /* ignore */ }
}
function saveSession(s) {
  const data = JSON.stringify(s);
  const buf = safeStorage.isEncryptionAvailable() ? safeStorage.encryptString(data) : Buffer.from(data, 'utf8');
  fs.writeFileSync(file('session.bin'), buf);
}
function loadSession() {
  try {
    const buf = fs.readFileSync(file('session.bin'));
    const s = JSON.parse(safeStorage.isEncryptionAvailable() ? safeStorage.decryptString(buf) : buf.toString('utf8'));
    return s && s.token && s.expiresAt > Date.now() ? s : null;
  } catch (_) { return null; }
}
function clearSession() { try { fs.unlinkSync(file('session.bin')); } catch (_) { /* none */ } }

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
    { label: 'Refresh status', click: () => hub && hub.refreshDevices().then(() => hub.pushAllEverywhere()).catch(() => {}) },
    { type: 'separator' },
    { label: 'Always on top', type: 'checkbox', checked: settings.alwaysOnTop, click: (i) => setAlwaysOnTop(i.checked) },
    { label: 'Notifications', type: 'checkbox', checked: settings.notify, click: (i) => { settings.notify = i.checked; saveSettings(); } },
    { label: 'Start with Windows', type: 'checkbox', checked: autoStart, click: (i) => app.setLoginItemSettings({ ...LOGIN_ITEM, openAtLogin: i.checked }) },
    { label: 'Reset window position', click: () => { settings.bounds = null; saveSettings(); const a = screen.getPrimaryDisplay().workArea; win.setBounds({ x: a.x + a.width - 400, y: a.y + 20, width: 380, height: 520 }); win.show(); } },
    { type: 'separator' },
    { label: 'Sign out', click: logout },
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

// ---------- hub ----------
function send() {
  if (win && !win.isDestroyed()) win.webContents.send('view', { ...view, alwaysOnTop: settings.alwaysOnTop });
}

async function startHub(sess) {
  if (hub) hub.stop();
  hub = new Hub(sess, settings.locked);
  hub.on('locked', (list) => { settings.locked = list; saveSettings(); });
  view = { screen: 'main', connection: 'connecting', message: 'Loading printers…', printers: [] };
  send();
  hub.on('update', (v) => {
    if (v.connection === 'auth') { clearSession(); view = { screen: 'login', connection: null, message: v.message, printers: [] }; }
    else view = { screen: 'main', ...v };
    checkTransitions(); updateTray(); send();
  });
  try { await hub.start(); }
  catch (e) {
    if (e.auth) { clearSession(); view = { screen: 'login', message: e.message, printers: [] }; }
    else { view = { ...view, message: e.message }; setTimeout(() => startHub(sess), 60000); }
    send();
  }
}

function logout() {
  if (hub) hub.stop();
  hub = null; clearSession(); previous.clear();
  view = { screen: 'login', connection: null, message: null, printers: [] };
  updateTray(); send();
}

async function signedIn(sess) {
  saveSession(sess);
  await startHub(sess);
  return { status: 'ok' };
}

// ---------- IPC ----------
const wrap = (fn) => async (_e, ...args) => {
  try { return await fn(...args); } catch (e) { return { status: 'error', message: e.message }; }
};
ipcMain.handle('getView', () => ({ ...view, alwaysOnTop: settings.alwaysOnTop }));
ipcMain.handle('login', wrap(async (email, password, region) => {
  login = new Login();
  const r = await login.start(email, password, region);
  return r.status === 'ok' ? signedIn(r.session) : r;
}));
ipcMain.handle('verifyCode', wrap(async (code) => signedIn(await login.verifyCode(code))));
ipcMain.handle('verifyTfa', wrap(async (code) => signedIn(await login.verifyTfa(code))));
ipcMain.handle('resendCode', wrap(async () => { await login.sendCode(); return { status: 'code' }; }));
ipcMain.handle('useToken', wrap(async (token, region) => signedIn(await sessionFromToken(region === 'china' ? 'china' : 'global', token, 0))));
ipcMain.handle('command', (_e, serial, cmd) => { if (hub) hub.command(String(serial), String(cmd)); });
ipcMain.handle('unlock', (_e, serial) => { if (hub) hub.unlock(String(serial)); });
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
  createWindow();
  tray = new Tray(trayIcon('idle'));
  tray.setToolTip('Bambu Widget');
  tray.on('click', toggleWindow);
  buildTrayMenu();
  const sess = loadSession();
  if (sess) startHub(sess);
});
