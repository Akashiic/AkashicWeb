'use strict';
// Preload of the capture window: exposes exactly two one-way calls to the capture page through
// contextBridge (contextIsolation stays on, ADR-008/013 amendments). No Node API reaches the page.

const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('akwCapture', {
  pcm: buffer => {
    if (buffer instanceof ArrayBuffer && buffer.byteLength > 0 && buffer.byteLength <= 65536) ipcRenderer.send('akw-pcm', buffer);
  },
  ended: () => ipcRenderer.send('akw-capture-ended'),
});
