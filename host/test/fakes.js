'use strict';
// Plain-Node stand-ins for the Electron objects screens.js touches, so the screen manager's rules
// (caps, EVENT limiting, popup gating, navigation ordering, audio decisions) are unit-tested without
// Electron. They model only what the host uses; the integration test covers the real thing.

const { EventEmitter } = require('events');
const P = require('../src/protocol');

let nextWcId = 100;

class FakeFrame extends EventEmitter {
  constructor(wc, url = 'about:blank', parent = null) {
    super();
    this.wc = wc;
    this.url = url;
    this.parent = parent;
    this.detached = false;
    this.sent = [];
    this.processId = 1;
    this.routingId = FakeFrame.next++;
    this.framesInSubtree = [this];
  }

  isDestroyed() { return false; }

  send(channel, ...args) { this.sent.push([channel, ...args]); }

  executeJavaScript() { return Promise.resolve(null); }
}
FakeFrame.next = 1;

class FakeWebContents extends EventEmitter {
  constructor() {
    super();
    this.id = nextWcId++;
    this.url = '';
    this.title = '';
    this.loads = [];
    this.inputs = [];
    this.muted = false;
    this.mutes = [];
    this.painting = true;
    this.frameRate = 0;
    this.destroyed = false;
    this.focused = false;
    this.windowOpenHandler = null;
    this.mainFrame = new FakeFrame(this);
    this.ipcHandlers = new Map();
    this.ipcListeners = new Map();
    const self = this;
    this.ipc = {
      handle(channel, fn) { self.ipcHandlers.set(channel, fn); },
      removeHandler(channel) { self.ipcHandlers.delete(channel); },
      on(channel, fn) { self.ipcListeners.set(channel, fn); },
      removeAllListeners() { self.ipcListeners.clear(); },
    };
    this.navigationHistory = {
      canGoBack: () => false,
      canGoForward: () => false,
      goBack: () => this.loads.push('<back>'),
      goForward: () => this.loads.push('<forward>'),
    };
  }

  loadURL(url) {
    this.loads.push(url);
    this.url = url;
    return Promise.resolve();
  }

  getURL() { return this.url; }

  getTitle() { return this.title; }

  isLoading() { return false; }

  isLoadingMainFrame() { return false; }

  isCurrentlyAudible() { return false; }

  isDestroyed() { return this.destroyed; }

  isFocused() { return this.focused; }

  focus() { this.focused = true; }

  sendInputEvent(e) { this.inputs.push(e); }

  setAudioMuted(m) {
    this.muted = !!m;
    this.mutes.push(!!m);
  }

  isAudioMuted() { return this.muted; }

  setFrameRate(fps) { this.frameRate = fps; }

  setIgnoreMenuShortcuts() {}

  setWebRTCIPHandlingPolicy() {}

  setWindowOpenHandler(fn) { this.windowOpenHandler = fn; }

  startPainting() { this.painting = true; }

  stopPainting() { this.painting = false; }

  invalidate() {}

  stop() { this.loads.push('<stop>'); }

  reload() { this.loads.push('<reload>'); }

  executeJavaScript() { return Promise.resolve(null); }

  executeJavaScriptInIsolatedWorld() { return Promise.resolve(null); }

  /** Simulates the page calling window.open: returns the handler's answer. */
  windowOpen(url, disposition = 'foreground-tab') {
    return this.windowOpenHandler({ url, disposition, frameName: '', features: '' });
  }
}

class FakeWindow {
  constructor(options) {
    this.options = options;
    this.webContents = new FakeWebContents();
    this.contentSize = [options.width, options.height];
    this.destroyed = false;
  }

  setContentSize(w, h) { this.contentSize = [w, h]; }

  loadFile(file) {
    this.loadedFile = file;
    return this.loadFileResult || Promise.resolve();
  }

  isDestroyed() { return this.destroyed; }

  destroy() {
    this.destroyed = true;
    this.webContents.destroyed = true;
  }
}

/** A ScreenManager context with fakes everywhere; `sent` records what would reach the game. */
function fakeContext(overrides = {}) {
  const sent = [];
  const windows = [];
  const resolveCalls = [];
  const ctx = {
    link: { send: (type, screenId, payload) => { sent.push({ type, name: P.typeName(type), screenId, payload }); return true; } },
    scheduler: { offer() {}, requestFull() {}, remove() {}, flush() {} },
    sessions: {
      forScreen: () => ({
        session: {
          resolveHost: host => {
            const call = {};
            const p = new Promise((resolve, reject) => { call.resolve = resolve; call.reject = reject; });
            call.host = host;
            resolveCalls.push(call);
            return p;
          },
        },
        partition: 'akw-test',
        ephemeral: true,
      }),
      release: () => Promise.resolve(),
    },
    registry: { isCapturePage: () => false },
    policy: () => ({ mode: 'blocklist', allow: [], block: [] }),
    allowFile: false,
    worldId: 1279,
    pageKey: '__akwtest',
    log: { info() {}, warn() {}, error() {} },
    createWindow: options => {
      const w = new FakeWindow(options);
      windows.push(w);
      return w;
    },
  };
  Object.assign(ctx, overrides);
  return { ctx, sent, windows, resolveCalls, events: () => sent.filter(m => m.type === P.T.EVENT).map(m => Object.assign({ screenId: m.screenId }, m.payload)) };
}

const flush = () => new Promise(r => setImmediate(r));

module.exports = { FakeWebContents, FakeWindow, FakeFrame, fakeContext, flush };
