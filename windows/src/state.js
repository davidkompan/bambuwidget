'use strict';
// Turns accumulated report fields into what the widget shows.
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

// Error texts and wiki links from ha-bambulab (MIT, ha-bambulab contributors).
const ERR = JSON.parse(zlib.gunzipSync(fs.readFileSync(path.join(__dirname, '..', 'assets', 'errors.json.gz'))).toString('utf8'));

const CANCELLED = 50348044; // 0x0300400C, "task canceled" after a user stop; not a fault

const STATES = {
  IDLE: ['idle', 'Idle'], INIT: ['preparing', 'Preparing'], SLICING: ['preparing', 'Preparing'],
  PREPARE: ['preparing', 'Preparing'], RUNNING: ['printing', 'Printing'], PAUSE: ['paused', 'Paused'],
  FINISH: ['finished', 'Finished'], FAILED: ['failed', 'Failed'],
};

const STAGES = {
  1: 'Auto bed levelling', 2: 'Heating the bed', 3: 'Vibration compensation', 4: 'Changing filament',
  5: 'Paused (M400)', 6: 'Paused: filament ran out', 7: 'Heating the nozzle', 8: 'Calibrating extrusion',
  9: 'Scanning the bed surface', 10: 'Inspecting the first layer', 11: 'Identifying the build plate', 12: 'Calibrating the micro lidar',
  13: 'Homing the toolhead', 14: 'Cleaning the nozzle tip', 15: 'Checking extruder temperature', 16: 'Paused by user',
  17: 'Paused: front cover fell off', 18: 'Calibrating the micro lidar', 19: 'Calibrating extrusion flow', 20: 'Paused: nozzle temperature problem',
  21: 'Paused: bed temperature problem', 22: 'Unloading filament', 23: 'Paused: skipped step', 24: 'Loading filament',
  25: 'Calibrating motor noise', 26: 'Paused: AMS lost', 27: 'Paused: heatbreak fan too slow', 28: 'Paused: chamber temperature problem',
  29: 'Cooling the chamber', 30: 'Paused by G-code', 31: 'Motor noise check', 32: 'Paused: filament on the nozzle',
  33: 'Paused: filament cutter problem', 34: 'Paused: first layer problem', 35: 'Paused: nozzle clog',
};

const SEVERITY = { 1: 'fatal', 2: 'serious', 3: 'common', 4: 'info' };

const num = (v) => {
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  if (typeof v === 'string' && v.trim() !== '') { const n = Number(v); return Number.isFinite(n) ? n : null; }
  return null;
};
const hex = (n, w) => (n >>> 0).toString(16).toUpperCase().padStart(w, '0');
const split4 = (s) => s.match(/.{4}/g).join('_');

function fileName(f) {
  if (typeof f.subtask_name === 'string' && f.subtask_name.trim()) return f.subtask_name.trim();
  if (typeof f.gcode_file !== 'string' || !f.gcode_file.trim()) return null;
  return f.gcode_file.split(/[\\/]/).pop().replace(/\.gcode(\.3mf)?$/i, '').replace(/\.3mf$/i, '');
}

function derive(p) {
  const f = p.fields;
  const [state, label] = p.received ? (STATES[String(f.gcode_state || '').toUpperCase()] || ['unknown', 'Unknown']) : ['unknown', p.info.online ? 'Waiting for data…' : 'Offline'];
  const active = state === 'printing' || state === 'paused' || state === 'preparing';
  const hasJob = active || state === 'finished' || state === 'failed';

  let percent = hasJob ? num(f.mc_percent) : null;
  if (percent !== null) percent = Math.max(0, Math.min(100, Math.round(percent)));
  if (state === 'finished') percent = 100;
  const remaining = active ? num(f.mc_remaining_time) : null;

  const errors = [];
  const pe = num(f.print_error);
  if (pe && pe !== CANCELLED) {
    const code = hex(pe, 8);
    errors.push({ code: split4(code), text: ERR.error[code] || 'Unknown print error', wiki: null, severity: 'serious', kind: 'print' });
  }
  if (Array.isArray(f.hms)) {
    for (const h of f.hms) {
      const attr = num(h.attr); const code = num(h.code);
      if (attr === null || code === null) continue;
      const key = hex(attr, 8) + hex(code, 8);
      const wiki = ERR.wiki[key];
      errors.push({
        code: `HMS_${split4(key)}`, text: ERR.hms[key] || 'Unknown HMS message',
        wiki: wiki ? `https://wiki.bambulab.com${wiki}` : 'https://wiki.bambulab.com/en/hms/home',
        severity: SEVERITY[code >>> 16] || 'common', kind: 'hms',
      });
    }
  }

  const stage = num(f.stg_cur);
  let stageText = null;
  if (active && stage !== null && stage > 0 && stage !== 255) stageText = STAGES[stage] || `Stage ${stage}`;

  return {
    serial: p.info.serial,
    name: p.info.name,
    model: p.info.model,
    online: p.info.online,
    received: p.received,
    state, label, stage: stageText,
    percent,
    remaining: remaining !== null && remaining >= 0 ? Math.round(remaining) : null,
    layer: hasJob ? num(f.layer_num) : null,
    totalLayers: hasJob ? num(f.total_layer_num) || null : null,
    file: hasJob ? fileName(f) : null,
    nozzle: num(f.nozzle_temper), nozzleTarget: num(f.nozzle_target_temper),
    bed: num(f.bed_temper), bedTarget: num(f.bed_target_temper),
    errors,
    printError: pe && pe !== CANCELLED ? pe : 0,
    lastUpdate: p.lastUpdate,
    reply: p.reply,
    can: {
      pause: state === 'printing' || state === 'preparing',
      resume: state === 'paused',
      stop: active,
      clear: errors.length > 0 || state === 'failed',
    },
  };
}

module.exports = { derive };
