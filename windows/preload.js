'use strict';
const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('bw', {
  getView: () => ipcRenderer.invoke('getView'),
  onView: (cb) => ipcRenderer.on('view', (_e, v) => cb(v)),
  login: (email, password, region) => ipcRenderer.invoke('login', email, password, region),
  verifyCode: (code) => ipcRenderer.invoke('verifyCode', code),
  verifyTfa: (code) => ipcRenderer.invoke('verifyTfa', code),
  resendCode: () => ipcRenderer.invoke('resendCode'),
  useToken: (token, region, label) => ipcRenderer.invoke('useToken', token, region, label),
  addAccount: () => ipcRenderer.invoke('addAccount'),
  cancelAdd: () => ipcRenderer.invoke('cancelAdd'),
  removeAccount: (id) => ipcRenderer.invoke('removeAccount', id),
  setPrinters: (order, hidden) => ipcRenderer.invoke('setPrinters', order, hidden),
  onOpenSettings: (cb) => ipcRenderer.on('openSettings', () => cb()),
  command: (serial, cmd) => ipcRenderer.invoke('command', serial, cmd),
  unlock: (serial) => ipcRenderer.invoke('unlock', serial),
  setAlwaysOnTop: (on) => ipcRenderer.invoke('setAlwaysOnTop', on),
  hide: () => ipcRenderer.invoke('hide'),
  logout: () => ipcRenderer.invoke('logout'),
  openWiki: (url) => ipcRenderer.invoke('openWiki', url),
});
