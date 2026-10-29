'use strict';
const $ = (id) => document.getElementById(id);
const cards = new Map(); // serial -> element
let current = null;
let settingsOpen = false;
let onLogin = false;

// ---------- header ----------
$('close').onclick = () => window.bw.hide();
$('pin').onclick = () => window.bw.setAlwaysOnTop(!(current && current.alwaysOnTop));
$('gear').onclick = () => { settingsOpen = !settingsOpen; if (current) render(current); };
$('settings-done').onclick = () => { settingsOpen = false; if (current) render(current); };
$('add-account').onclick = () => window.bw.addAccount();
window.bw.onOpenSettings(() => { settingsOpen = true; if (current) render(current); });

// ---------- login ----------
const forms = ['f-login', 'f-code', 'f-token'];
function showForm(id) { forms.forEach((f) => { $(f).hidden = f !== id; }); $('login-error').textContent = ''; }
let codeMode = 'code';

async function run(form, fn) {
  const btn = form.querySelector('button[type=submit]');
  btn.disabled = true; $('login-error').textContent = '';
  try {
    const r = await fn();
    if (r && r.status === 'error') $('login-error').textContent = r.message;
    else if (r && (r.status === 'code' || r.status === 'tfa')) {
      codeMode = r.status;
      $('code-title').textContent = r.status === 'code' ? 'Code from email' : 'Authenticator code';
      $('code-hint').textContent = r.status === 'code' ? 'Bambu Lab has emailed you a one-time code.' : 'Enter the current code from your authenticator app (2FA).';
      $('resend').hidden = r.status !== 'code';
      $('code').value = ''; showForm('f-code'); $('code').focus();
    }
  } finally { btn.disabled = false; }
}

$('f-login').onsubmit = (e) => { e.preventDefault(); run(e.target, () => window.bw.login($('email').value, $('password').value, $('region').value)); };
$('f-code').onsubmit = (e) => { e.preventDefault(); run(e.target, () => (codeMode === 'code' ? window.bw.verifyCode($('code').value) : window.bw.verifyTfa($('code').value))); };
$('f-token').onsubmit = (e) => { e.preventDefault(); run(e.target, () => window.bw.useToken($('token').value, $('region2').value, $('token-label').value)); };
$('to-token').onclick = (e) => { e.preventDefault(); showForm('f-token'); };
$('resend').onclick = async (e) => { e.preventDefault(); const r = await window.bw.resendCode(); $('login-error').textContent = r.status === 'error' ? r.message : 'A new code has been sent.'; };
document.querySelectorAll('.back').forEach((a) => { a.onclick = (e) => { e.preventDefault(); showForm('f-login'); }; });
document.querySelectorAll('.cancel-add').forEach((a) => { a.onclick = (e) => { e.preventDefault(); window.bw.cancelAdd(); }; });

// ---------- printers ----------
const fmtTime = (min) => {
  if (min === null) return null;
  const h = Math.floor(min / 60); const m = min % 60;
  return h ? `${h} h ${m} min` : `${m} min`;
};
const finishAt = (min) => {
  const d = new Date(Date.now() + min * 60000);
  const same = d.toDateString() === new Date().toDateString();
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) + (same ? '' : ` (${d.toLocaleDateString([], { day: 'numeric', month: 'numeric' })})`);
};
const temp = (v, t) => (v === null ? null : `${Math.round(v)}${t ? `/${Math.round(t)}` : ''} °C`);

function makeCard(serial) {
  const el = $('t-card').content.firstElementChild.cloneNode(true);
  el.querySelector('.retry').onclick = (e) => { e.preventDefault(); window.bw.unlock(serial); };
  el.querySelectorAll('.actions button').forEach((b) => {
    b.onclick = () => {
      const cmd = b.dataset.cmd;
      if (cmd === 'stop' && !b.classList.contains('armed')) {
        // two-step confirmation for stop
        b.classList.add('armed'); b.textContent = '■ Really stop?';
        clearTimeout(b._t); b._t = setTimeout(() => { b.classList.remove('armed'); b.textContent = '■ Stop'; }, 4000);
        return;
      }
      if (cmd === 'stop') { b.classList.remove('armed'); b.textContent = '■ Stop'; }
      window.bw.command(serial, cmd);
    };
  });
  return el;
}

function renderCard(el, p) {
  el.className = `card s-${p.state}${p.errors.some((e) => e.severity === 'fatal' || e.severity === 'serious') ? ' has-error' : ''}`;
  el.querySelector('.name').textContent = p.name;
  el.querySelector('.name').title = p.serial;
  el.querySelector('.model').textContent = [p.model, current && current.multi ? p.account : null].filter(Boolean).join(' · ');
  el.querySelector('.label').textContent = p.stage && p.state !== 'printing' ? p.stage : p.label;
  el.querySelector('.file').textContent = p.file || '';

  const prog = el.querySelector('.progress');
  prog.hidden = p.percent === null;
  if (p.percent !== null) {
    prog.querySelector('.fill').style.width = `${p.percent}%`;
    prog.querySelector('.pct').textContent = `${p.percent} %`;
  }

  const meta = [];
  if (p.remaining !== null) meta.push(`${fmtTime(p.remaining)} left`, `ends ${finishAt(p.remaining)}`);
  if (p.layer !== null && p.totalLayers) meta.push(`layer ${p.layer}/${p.totalLayers}`);
  if (p.state === 'printing' && p.stage) meta.push(p.stage);
  const n = temp(p.nozzle, p.nozzleTarget); const b = temp(p.bed, p.bedTarget);
  if (p.received && n) meta.push(`nozzle ${n}`);
  if (p.received && b) meta.push(`bed ${b}`);
  const metaEl = el.querySelector('.meta');
  metaEl.textContent = meta.join(' · ');
  metaEl.hidden = !meta.length;

  const errs = el.querySelector('.errors');
  errs.replaceChildren(...p.errors.map((e) => {
    const d = document.createElement('div');
    d.className = `err ${e.severity}`;
    const c = document.createElement('code'); c.textContent = e.code;
    d.append(c, document.createTextNode(` ${e.text}`));
    if (e.wiki) { const a = document.createElement('a'); a.textContent = 'Wiki'; a.onclick = () => window.bw.openWiki(e.wiki); d.append(a); }
    return d;
  }));

  const can = p.received && p.online && !p.locked ? p.can : {};
  el.querySelector('.locked').hidden = !p.locked;
  el.querySelectorAll('.actions button').forEach((btn) => { btn.hidden = !can[btn.dataset.cmd]; });

  const r = el.querySelector('.reply');
  const fresh = p.reply && Date.now() - p.reply.at < 30000;
  r.textContent = fresh ? p.reply.text : '';
  r.className = `reply${fresh && p.reply.ok === true ? ' ok' : ''}${fresh && p.reply.ok === false ? ' bad' : ''}`;
}

// ---------- settings ----------
function savePrinters(all) {
  window.bw.setPrinters(all.map((p) => p.serial), all.filter((p) => p.hidden).map((p) => p.serial));
}

let settingsKey = null;
function renderSettings(v) {
  // Rebuilt only when something in it changed, so a pending "Really remove?" survives status updates.
  const key = JSON.stringify([v.all, v.accounts]);
  if (key === settingsKey) return;
  settingsKey = key;
  const all = (v.all || []).map((p) => ({ ...p }));
  $('s-printers').replaceChildren(...all.map((p, i) => {
    const row = document.createElement('div');
    row.className = 's-row';
    const box = document.createElement('input');
    box.type = 'checkbox'; box.checked = !p.hidden; box.title = 'Show in the widget';
    box.onchange = () => { all[i].hidden = !box.checked; savePrinters(all); };
    const txt = document.createElement('label');
    txt.className = 's-text';
    const name = document.createElement('span'); name.className = 's-name'; name.textContent = p.name;
    const sub = document.createElement('span'); sub.className = 's-sub';
    sub.textContent = [p.model, v.accounts && v.accounts.length > 1 ? p.account : null].filter(Boolean).join(' · ');
    txt.append(name, sub);
    txt.onclick = () => box.click();
    const move = (d) => {
      const j = i + d;
      if (j < 0 || j >= all.length) return;
      [all[i], all[j]] = [all[j], all[i]];
      savePrinters(all);
    };
    const up = document.createElement('button'); up.className = 'icon'; up.textContent = '▲'; up.title = 'Move up'; up.disabled = i === 0; up.onclick = () => move(-1);
    const down = document.createElement('button'); down.className = 'icon'; down.textContent = '▼'; down.title = 'Move down'; down.disabled = i === all.length - 1; down.onclick = () => move(1);
    row.append(box, txt, up, down);
    return row;
  }));
  if (!all.length) $('s-printers').textContent = 'No printers loaded yet.';

  const status = { online: 'Connected', connecting: 'Connecting…', offline: 'Offline', expired: 'Sign-in expired' };
  $('s-accounts').replaceChildren(...(v.accounts || []).map((a) => {
    const row = document.createElement('div');
    row.className = `s-row acc ${a.status}`;
    const txt = document.createElement('div');
    txt.className = 's-text';
    const name = document.createElement('span'); name.className = 's-name'; name.textContent = a.label;
    const sub = document.createElement('span'); sub.className = 's-sub';
    sub.textContent = [a.region === 'china' ? 'China' : 'Global', status[a.status] || a.status,
      a.status === 'expired' ? null : `${a.printers} printer${a.printers === 1 ? '' : 's'}`].filter(Boolean).join(' · ');
    txt.append(name, sub);
    row.append(txt);
    if (a.status === 'expired') {
      const again = document.createElement('button'); again.className = 'small'; again.textContent = 'Sign in';
      again.onclick = () => window.bw.addAccount();
      row.append(again);
    }
    const rm = document.createElement('button'); rm.className = 'small danger'; rm.textContent = 'Remove';
    rm.onclick = () => {
      if (!rm.classList.contains('armed')) {
        rm.classList.add('armed'); rm.textContent = 'Really remove?';
        setTimeout(() => { rm.classList.remove('armed'); rm.textContent = 'Remove'; }, 4000);
        return;
      }
      window.bw.removeAccount(a.id);
    };
    row.append(rm);
    return row;
  }));
}

function render(v) {
  current = v;
  $('pin').classList.toggle('on', !!v.alwaysOnTop);
  const loginScreen = v.screen === 'login';
  const showSettings = settingsOpen && !loginScreen;
  $('login').hidden = !loginScreen;
  $('main').hidden = loginScreen || showSettings;
  $('settings').hidden = !showSettings;
  $('gear').hidden = loginScreen;
  $('gear').classList.toggle('on', showSettings);
  $('title').textContent = loginScreen ? (v.adding ? 'Add account' : 'Printers') : showSettings ? 'Settings' : 'Printers';
  if (loginScreen) {
    // Reset the forms only when the sign-in screen opens, not on every status update behind it.
    if (!onLogin) { showForm('f-login'); ['password', 'token', 'token-label'].forEach((id) => { $(id).value = ''; }); }
    onLogin = true;
    document.querySelectorAll('.cancel-add').forEach((a) => { a.hidden = !v.adding; });
    $('f-login').querySelector('.login-title').textContent = v.adding ? 'Sign in to another Bambu Lab account' : 'Sign in to Bambu Lab';
    if (v.message) $('login-error').textContent = v.message;
    $('conn').className = 'conn';
    return;
  }
  if (onLogin) { onLogin = false; settingsOpen = settingsOpen && (v.accounts || []).length > 0; }
  if (showSettings) renderSettings(v); else settingsKey = null;
  $('conn').className = `conn ${v.connection || ''}`;
  $('conn').title = v.connection === 'online' ? 'Connected to Bambu Cloud' : (v.message || '');
  $('notice').hidden = !v.message;
  $('notice').textContent = v.message || '';

  const list = $('list');
  const seen = new Set();
  for (const p of v.printers) {
    seen.add(p.serial);
    let el = cards.get(p.serial);
    if (!el) { el = makeCard(p.serial); cards.set(p.serial, el); }
    renderCard(el, p);
    list.append(el); // keeps order
  }
  for (const [s, el] of cards) if (!seen.has(s)) { el.remove(); cards.delete(s); }
}

window.bw.onView(render);
window.bw.getView().then(render);
setInterval(() => { if (current && current.screen === 'main') render(current); }, 30000); // refresh finish times / stale replies
