'use strict';
// One MQTT connection to Bambu Cloud carrying all printers of the account.
const { EventEmitter } = require('events');
const mqtt = require('mqtt');
const { listPrinters, mqttHost } = require('./cloud');
const { derive } = require('./state');

const DEVICE_REFRESH_MS = 10 * 60 * 1000;
const PUSHALL_MS = 10 * 60 * 1000; // P1/A1 must not be asked more often than every few minutes
const COMMAND_TIMEOUT_MS = 12000;

class Hub extends EventEmitter {
  constructor(sess, locked = []) {
    super();
    this.sess = sess;
    // Printers whose firmware rejects unsigned commands (Bambu Authorization Control).
    this.locked = new Set(locked);
    this.printers = new Map(); // serial -> {info, fields, received, lastUpdate, reply}
    this.client = null;
    this.connection = 'connecting';
    this.message = 'Connecting to Bambu Cloud…';
    this.seq = 1;
    this.timers = [];
    this.pending = new Map(); // serial -> {command, timer}
  }

  async start() {
    await this.refreshDevices();
    this.connect();
    this.timers.push(setInterval(() => this.refreshDevices().catch((e) => this.fail(e)), DEVICE_REFRESH_MS));
    this.timers.push(setInterval(() => this.pushAllEverywhere(), PUSHALL_MS));
  }

  stop() {
    this.timers.forEach(clearInterval);
    this.timers = [];
    for (const p of this.pending.values()) clearTimeout(p.timer);
    this.pending.clear();
    if (this.client) this.client.end(true);
    this.client = null;
  }

  fail(e) {
    if (e && e.auth) { this.connection = 'auth'; this.message = e.message; this.stop(); }
    else { this.message = e ? e.message : null; }
    this.changed();
  }

  async refreshDevices() {
    const list = await listPrinters(this.sess);
    const seen = new Set();
    for (const info of list) {
      seen.add(info.serial);
      const p = this.printers.get(info.serial);
      if (p) p.info = info;
      else {
        this.printers.set(info.serial, { info, fields: {}, received: false, lastUpdate: null, reply: null });
        this.subscribe(info.serial);
      }
    }
    for (const serial of [...this.printers.keys()]) if (!seen.has(serial)) this.printers.delete(serial);
    this.changed();
  }

  connect() {
    const c = mqtt.connect({
      protocol: 'mqtts', host: mqttHost(this.sess.region), port: 8883,
      username: this.sess.username, password: this.sess.token,
      clientId: `bambu-widget-${Math.random().toString(16).slice(2, 10)}`,
      protocolVersion: 4, clean: true, keepalive: 30,
      reconnectPeriod: 5000, connectTimeout: 15000, rejectUnauthorized: true,
    });
    this.client = c;
    c.on('connect', () => {
      this.connection = 'online'; this.message = null;
      for (const serial of this.printers.keys()) this.subscribe(serial);
      this.changed();
    });
    c.on('message', (topic, payload) => this.onMessage(topic, payload));
    c.on('error', (err) => {
      const t = `${err.code || ''} ${err.message}`.toLowerCase();
      if (err.code === 4 || err.code === 5 || t.includes('not authorized') || t.includes('bad username')) {
        this.fail(Object.assign(new Error('Bambu Cloud rejected the sign-in (the token has probably expired). Please sign in again.'), { auth: true }));
      } else { this.connection = 'offline'; this.message = `Connection error: ${err.message}`; this.changed(); }
    });
    c.on('close', () => {
      if (this.connection === 'online') { this.connection = 'offline'; this.message = 'Connection lost, reconnecting…'; this.changed(); }
    });
  }

  subscribe(serial) {
    if (!this.client || !this.client.connected) return;
    this.client.subscribe(`device/${serial}/report`, (err) => {
      if (err) { this.message = `Subscribing to ${serial} failed: ${err.message}`; this.changed(); return; }
      this.pushAll(serial);
    });
  }

  publish(serial, payload, qos = 0) {
    if (!this.client || !this.client.connected) return false;
    this.client.publish(`device/${serial}/request`, JSON.stringify(payload), { qos });
    return true;
  }

  pushAll(serial) {
    this.publish(serial, { pushing: { sequence_id: String(this.seq++), command: 'pushall', version: 1, push_target: 1 } });
  }

  pushAllEverywhere() { for (const s of this.printers.keys()) this.pushAll(s); }

  onMessage(topic, payload) {
    const serial = topic.split('/')[1];
    const p = this.printers.get(serial);
    if (!p) return;
    let msg;
    try { msg = JSON.parse(payload.toString('utf8')); } catch (_) { return; }
    const print = msg && msg.print;
    if (!print || typeof print !== 'object') return;
    if (print.command && print.command !== 'push_status') {
      // Reply to a command we (or another client) sent.
      const pend = this.pending.get(serial);
      if (pend && pend.command === print.command) {
        clearTimeout(pend.timer);
        this.pending.delete(serial);
        const ok = String(print.result || '').toLowerCase() === 'success';
        if (!ok && /verif/i.test(String(print.reason || ''))) this.lock(serial);
        p.reply = { ok, text: ok ? 'Command accepted.' : `The printer rejected the command${print.reason ? `: ${print.reason}` : ''}.`, at: Date.now() };
        this.changed();
      }
      return;
    }
    // HMS 0500_0500_0001_0007 = "MQTT command verification failed": unsigned commands are refused.
    // Lock only when it newly appears, so "Try controls again" is not undone by the same old message.
    const verifyFail = (hms) => Array.isArray(hms) && hms.some((h) => Number(h.attr) === 0x05000500 && Number(h.code) === 0x00010007);
    if (verifyFail(print.hms) && !verifyFail(p.fields.hms)) this.lock(serial);
    p.fields = { ...p.fields, ...print };
    p.received = true;
    p.lastUpdate = Date.now();
    this.changed();
  }

  command(serial, cmd) {
    const p = this.printers.get(serial);
    if (!p) return;
    const f = p.fields;
    const seq = String(this.seq++);
    let payload;
    switch (cmd) {
      case 'pause': case 'resume': case 'stop':
        payload = { print: { sequence_id: seq, command: cmd, param: '' } }; break;
      case 'clear':
        // Same command Bambu Studio sends when the user dismisses a print error.
        payload = { print: { sequence_id: seq, command: 'clean_print_error', subtask_id: String(f.subtask_id || ''), print_error: Number(f.print_error) || 0 } };
        break;
      default: return;
    }
    const sent = this.publish(serial, payload, 1);
    const prev = this.pending.get(serial);
    if (prev) clearTimeout(prev.timer);
    if (!sent) { p.reply = { ok: false, text: 'Not connected to Bambu Cloud.', at: Date.now() }; this.changed(); return; }
    p.reply = { ok: null, text: 'Sending command…', at: Date.now() };
    const timer = setTimeout(() => {
      this.pending.delete(serial);
      p.reply = { ok: null, text: 'No confirmation from the printer. Check its status.', at: Date.now() };
      this.pushAll(serial);
      this.changed();
    }, COMMAND_TIMEOUT_MS);
    this.pending.set(serial, { command: payload.print.command, timer });
    this.changed();
  }

  lock(serial) {
    if (this.locked.has(serial)) return;
    this.locked.add(serial);
    this.emit('locked', [...this.locked]);
  }

  unlock(serial) {
    if (!this.locked.delete(serial)) return;
    const p = this.printers.get(serial);
    if (p) p.reply = null;
    this.emit('locked', [...this.locked]);
    this.changed();
  }

  view() {
    return {
      connection: this.connection,
      message: this.message,
      printers: [...this.printers.values()].map((p) => {
        const v = derive(p);
        if (!this.locked.has(p.info.serial)) return { ...v, locked: false };
        // Already explained by the read-only note: hide the verification-failed HMS and the rejection text.
        const errors = v.errors.filter((e) => e.code !== 'HMS_0500_0500_0001_0007');
        const reply = v.reply && v.reply.ok === false ? null : v.reply;
        return { ...v, errors, reply, locked: true, can: { ...v.can, clear: errors.length > 0 || v.state === 'failed' } };
      }),
    };
  }

  changed() {
    clearTimeout(this._emitTimer);
    this._emitTimer = setTimeout(() => this.emit('update', this.view()), 150);
  }
}

module.exports = { Hub };
