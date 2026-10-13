'use strict';
// Screen preload: the host's agent inside every page (wave-1 review, high finding 2; ADR-007/012/013).
//
// Electron runs this file at document start in the isolated world of the main frame and, because the
// screen window sets nodeIntegrationInSubFrames, of every iframe too (srcdoc, data:, same- and
// cross-origin frames; NOT initial about:blank iframes, which the parent frame's agent watches
// instead, see watchChildFrame). The window is sandboxed with contextIsolation on: this file sees the
// DOM but none of the page's JavaScript objects, and it exposes NOTHING to the page.
//
// What it does, without waiting for the page load (webContents.executeJavaScript* waits for
// did-stop-loading, so in wave 1 a slow page played at full volume and sent no MEDIA until loaded):
//  - volume (ADR-007): every <video>/<audio> follows the game's gain from the first moment. Until the
//    host has told this document the gain, it is 0 (silent), never the page's default 1;
//  - MEDIA (ADR-012): while the host asks for samples, 4 Hz samples of this frame's main media element,
//    from a cached element set (rescanned on DOM mutations and every 3 s, not a full DOM walk per tick);
//  - SYNC: applies the host's steering plan to that element;
//  - guard (ADR-013): print(), file pickers and file-input clicks neutralised in the page's main world
//    before any page script runs (contextBridge.executeInMainWorld). The document's CSP sandbox header
//    (policy.js) is the stronger print block, including fresh iframes.
//
// Nothing here is a security boundary for audio: the engine mute (webContents.setAudioMuted) is.
// The file must stay self-contained: a sandboxed preload cannot require local modules. Under plain Node
// (unit tests) it only exports its parts; it starts itself only in an Electron renderer.

const CHANNELS = Object.freeze({
  INIT: 'akw-init', // invoke: {top} -> {volume, sampling}
  VOLUME: 'akw-volume', // host -> frame: number 0..1
  SAMPLING: 'akw-sampling', // host -> frame: boolean
  MEDIA: 'akw-media', // frame -> host: {score, sample} | {score: -1}
  SYNC: 'akw-sync', // host -> frame: plan {rate, seekTo, play, pause}
  REINIT: 'akw-reinit', // host -> frame: ask again for the state
});

const ENFORCE_MS = 100; // sites rewrite element.volume; re-applied this often (cheap: cached set only)
const RESCAN_EVERY = 30; // enforce ticks between full rescans (3 s)
const SAMPLE_MS = 250; // MEDIA at 4 Hz
const MEDIA_EVENTS = ['play', 'playing', 'loadstart', 'loadedmetadata', 'durationchange', 'volumechange'];
// Player roots, to look for ad and live markers inside the player only (no whole-document query).
const PLAYER_ROOTS = '.html5-video-player, [data-a-target="video-player"], .video-player';
const AD_MARKERS = '.ad-showing, [data-a-target="video-ad-label"], [data-a-target="video-ad-countdown"]';
const LIVE_MARKERS = '.ytp-live';

// ---------------------------------------------------------------------------------------------------
// Pure helpers (unit-tested with fake elements)

function isMediaElement(n) {
  return !!n && (n.tagName === 'VIDEO' || n.tagName === 'AUDIO');
}

function clampUnit(v) {
  const n = Number(v);
  return Number.isFinite(n) ? Math.min(1, Math.max(0, n)) : 0;
}

/** Index of the media element that drives the screen's clock, or -1. Candidates are plain records. */
function pickMedia(cands) {
  let best = -1;
  let bestScore = -1;
  for (let i = 0; i < cands.length; i++) {
    const s = scoreOf(cands[i]);
    if (s > bestScore) {
      best = i;
      bestScore = s;
    }
  }
  return best;
}

/** Playing beats paused, video beats audio, ready beats loading; area breaks ties. -1 if unusable. */
function scoreOf(c) {
  if (!c || !c.connected) return -1;
  let score = 0;
  if (!c.paused && !c.ended) score += 4;
  if (c.isVideo) score += 2;
  if (c.readyState >= 2) score += 1;
  return score + Math.min(0.99, Math.max(0, c.area) / 1e7);
}

function candidateOf(el) {
  let r = { width: 0, height: 0 };
  try {
    if (el.getBoundingClientRect) r = el.getBoundingClientRect();
  } catch (e) { /* detached */ }
  return {
    connected: el.isConnected !== false,
    paused: !!el.paused,
    ended: !!el.ended,
    isVideo: el.tagName === 'VIDEO',
    readyState: el.readyState,
    area: Math.max(0, r.width || 0) * Math.max(0, r.height || 0),
  };
}

/** The player element around `el` (YouTube, Twitch, generic), or its parent; bounded queries only. */
function playerRoot(el) {
  try {
    return (el.closest && el.closest(PLAYER_ROOTS)) || el.parentElement || null;
  } catch (e) {
    return null;
  }
}

function hasMarker(root, selector) {
  if (!root) return false;
  try {
    return !!((root.matches && root.matches(selector)) || (root.querySelector && root.querySelector(selector)));
  } catch (e) {
    return false;
  }
}

/** Clock sample of one element, JSON-safe (duration -1 when unknown or infinite). */
function readSample(el, doc) {
  const duration = el.duration;
  const finite = typeof duration === 'number' && Number.isFinite(duration);
  let seekEnd = NaN;
  try {
    if (el.seekable && el.seekable.length > 0) seekEnd = el.seekable.end(el.seekable.length - 1);
  } catch (e) { /* seekable may be unavailable mid-load */ }
  const root = playerRoot(el);
  const live = duration === Infinity || hasMarker(root, LIVE_MARKERS);
  const behind = live && Number.isFinite(seekEnd) ? Math.max(0, seekEnd - el.currentTime) : 0;
  return {
    mediaTime: el.currentTime,
    paused: !!el.paused,
    rate: el.playbackRate,
    duration: finite ? duration : -1,
    live,
    behindLiveEdge: behind,
    adShowing: hasMarker(root, AD_MARKERS),
    fullscreen: !!(doc && doc.fullscreenElement),
  };
}

/** Applies a steering plan (media.js planSync) to an element. */
function applyPlan(el, plan) {
  if (!plan || typeof plan !== 'object') return false;
  if (typeof plan.seekTo === 'number' && Number.isFinite(plan.seekTo)) el.currentTime = plan.seekTo;
  if (typeof plan.rate === 'number' && Number.isFinite(plan.rate) && Math.abs(el.playbackRate - plan.rate) > 1e-4) el.playbackRate = plan.rate;
  if (plan.pause && !el.paused) el.pause();
  if (plan.play && el.paused) {
    const p = el.play();
    if (p && typeof p.catch === 'function') p.catch(() => {});
  }
  return true;
}

// ---------------------------------------------------------------------------------------------------
// Main-world guard. Serialised by contextBridge.executeInMainWorld, so it must stay self-contained.

/** Page-side: runs once per document in the page's main world, before any page script. */
function akwGuard(key) {
  const g = globalThis;
  if (g[key]) return false;
  Object.defineProperty(g, key, { value: true, enumerable: false, configurable: false, writable: false });
  const noop = function print() {};
  try { Object.defineProperty(g, 'print', { value: noop, writable: false, configurable: false }); } catch (e) { /* already locked */ }
  const reject = () => Promise.reject(new DOMException('File pickers are disabled on in-world screens', 'AbortError'));
  ['showOpenFilePicker', 'showSaveFilePicker', 'showDirectoryPicker'].forEach(n => {
    if (n in g) {
      try { Object.defineProperty(g, n, { value: reject, writable: false, configurable: false }); } catch (e) { /* locked */ }
    }
  });
  const proto = g.HTMLInputElement && g.HTMLInputElement.prototype;
  if (proto && typeof proto.showPicker === 'function') {
    const original = proto.showPicker;
    try {
      Object.defineProperty(proto, 'showPicker', {
        value: function showPicker() {
          if (this.type === 'file') return undefined;
          return original.call(this);
        },
        writable: false,
        configurable: false,
      });
    } catch (e) { /* locked */ }
  }
  // Service workers could serve documents that never pass the host's response-header policy; the
  // network filter refuses their scripts too (policy.js), this only fails fast.
  const sw = g.navigator && g.navigator.serviceWorker;
  if (sw && g.ServiceWorkerContainer && g.ServiceWorkerContainer.prototype) {
    try {
      Object.defineProperty(g.ServiceWorkerContainer.prototype, 'register', {
        value: function register() { return Promise.reject(new DOMException('Service workers are disabled on in-world screens', 'SecurityError')); },
        writable: false,
        configurable: false,
      });
    } catch (e) { /* locked */ }
  }
  // Clicks (real or scripted, including <label for>) on a file input open the chooser by default.
  // Registered before any page listener, so a page cannot stop it from running.
  g.addEventListener('click', e => {
    const t = e.target;
    if (t && t.tagName === 'INPUT' && String(t.type).toLowerCase() === 'file') e.preventDefault();
  }, true);
  return true;
}

// ---------------------------------------------------------------------------------------------------
// The per-frame agent

class FrameAgent {
  /**
   * env: window, document, ipc {send(channel, data), invoke(channel, data) -> Promise, on(channel, fn)},
   * runInMainWorld(fn, args) (optional), setInterval, clearInterval, MutationObserver (optional),
   * guardKey.
   */
  constructor(env) {
    this.env = env;
    this.volume = 0; // silent until the host says otherwise (fail safe)
    this.els = new Set();
    this.docs = new Set();
    this.sampling = false;
    this.sampleTimer = null;
    this.current = null; // element of the last sample, target of SYNC
    this.reported = false; // whether the host holds a sample of this frame
    this.ticks = 0;
    this.initialised = false;
  }

  start() {
    const env = this.env;
    if (env.runInMainWorld) {
      try { env.runInMainWorld(akwGuard, [env.guardKey || '__akwGuard']); } catch (e) { /* page world unavailable: CSP sandbox still blocks print */ }
    }
    env.ipc.on(CHANNELS.VOLUME, (event, v) => this.setVolume(v));
    env.ipc.on(CHANNELS.SAMPLING, (event, on) => this.setSampling(on));
    env.ipc.on(CHANNELS.SYNC, (event, plan) => this.sync(plan));
    env.ipc.on(CHANNELS.REINIT, () => this.init());
    if (env.document) this.watchDocument(env.document, false);
    env.setInterval(() => this.tick(), ENFORCE_MS);
    this.init();
  }

  init() {
    const env = this.env;
    let top = true;
    try { top = env.window === env.window.top; } catch (e) { top = false; }
    return Promise.resolve(env.ipc.invoke(CHANNELS.INIT, { top })).then(state => {
      this.initialised = true;
      if (state && typeof state === 'object') {
        this.setVolume(state.volume);
        this.setSampling(!!state.sampling);
      }
    }, () => { /* host gone or page unloading: stay silent */ });
  }

  setVolume(v) {
    this.volume = clampUnit(v);
    this.enforceAll();
  }

  /** Starts watching a document: media events, DOM mutations, child frames. `child` = no own agent. */
  watchDocument(doc, child) {
    if (!doc || this.docs.has(doc)) return;
    this.docs.add(doc);
    const onEvent = e => { if (isMediaElement(e.target)) this.track(e.target); };
    MEDIA_EVENTS.forEach(t => doc.addEventListener(t, onEvent, true));
    const MO = this.env.MutationObserver;
    if (typeof MO === 'function') {
      new MO(records => {
        for (const r of records) {
          for (const n of r.addedNodes || []) this.adopt(n);
        }
      }).observe(doc, { childList: true, subtree: true });
    }
    if (child) {
      // A child document without its own agent: also cancel file-input clicks there (the main-world
      // guard of that document never ran).
      const win = doc.defaultView;
      if (win && win.addEventListener) {
        win.addEventListener('click', e => {
          const t = e.target;
          if (t && t.tagName === 'INPUT' && String(t.type).toLowerCase() === 'file') e.preventDefault();
        }, true);
      }
    }
    this.scan(doc);
  }

  /** A node added to a watched document: media, a subtree holding media, or a frame. */
  adopt(n) {
    if (!n || n.nodeType !== 1) return;
    if (isMediaElement(n)) {
      this.track(n);
      return;
    }
    if (n.tagName === 'IFRAME' || n.tagName === 'FRAME') this.watchChildFrame(n);
    if (n.querySelectorAll) {
      n.querySelectorAll('video, audio').forEach(el => this.track(el));
      n.querySelectorAll('iframe, frame').forEach(f => this.watchChildFrame(f));
    }
  }

  /**
   * Initial about:blank documents of child frames get no agent of their own (Electron skips preloads
   * there), but they are same-origin with this document, so this agent watches them. Frames that load
   * a real URL get their own agent and are left alone.
   *
   * A frame with a src or srcdoc is never touched: touching a child's initial document while its first
   * navigation is pending (from any world, even by the page itself) makes Electron 44 skip the preload
   * in the document that navigation commits (measured, Linux). Touching an idle about:blank child
   * does not affect a later navigation.
   */
  watchChildFrame(frameEl) {
    try {
      if (frameEl.hasAttribute && frameEl.hasAttribute('srcdoc')) return;
      const src = frameEl.getAttribute ? String(frameEl.getAttribute('src') || '').trim() : '';
      if (src !== '' && src !== 'about:blank') return;
    } catch (e) {
      return;
    }
    let doc = null;
    try {
      doc = frameEl.contentDocument;
    } catch (e) {
      doc = null; // cross-origin: has its own agent
    }
    if (!doc) return;
    let href = '';
    try { href = String(doc.location && doc.location.href); } catch (e) { href = ''; }
    if (href !== 'about:blank' && href !== '') return;
    this.watchDocument(doc, true);
  }

  /** Remembers a media element and applies the gain now (also on every media event: sites reset it). */
  track(el) {
    this.els.add(el);
    this.enforce(el);
  }

  enforce(el) {
    try {
      if (Math.abs(el.volume - this.volume) > 0.001) el.volume = this.volume;
    } catch (e) { /* element in a bad state; next tick retries */ }
  }

  enforceAll() {
    for (const el of this.els) {
      if (!el.isConnected && el.paused) this.els.delete(el);
      else this.enforce(el);
    }
  }

  /** Full rescan of one document: media (open shadow roots included) and child frames. */
  scan(doc) {
    const visit = root => {
      if (!root || !root.querySelectorAll) return;
      root.querySelectorAll('video, audio').forEach(el => this.track(el));
      root.querySelectorAll('*').forEach(n => { if (n.shadowRoot) visit(n.shadowRoot); });
    };
    try {
      visit(doc);
      if (doc.querySelectorAll) doc.querySelectorAll('iframe, frame').forEach(f => this.watchChildFrame(f));
    } catch (e) { /* document going away */ }
  }

  tick() {
    this.ticks++;
    if (this.ticks % RESCAN_EVERY === 0) {
      for (const doc of this.docs) this.scan(doc);
    }
    if (this.els.size > 0) this.enforceAll();
  }

  setSampling(on) {
    this.sampling = !!on;
    if (this.sampling && !this.sampleTimer) {
      this.sampleTimer = this.env.setInterval(() => this.sample(), SAMPLE_MS);
    } else if (!this.sampling && this.sampleTimer) {
      this.env.clearInterval(this.sampleTimer);
      this.sampleTimer = null;
    }
  }

  /** Best element of this frame, or null. */
  best() {
    const els = [...this.els];
    const i = pickMedia(els.map(candidateOf));
    return i < 0 ? null : els[i];
  }

  /** One MEDIA sample to the host (the host picks the best frame). */
  sample() {
    const el = this.best();
    this.current = el;
    if (!el) {
      if (this.reported) this.env.ipc.send(CHANNELS.MEDIA, { score: -1 });
      this.reported = false;
      return null;
    }
    const doc = el.ownerDocument || this.env.document;
    const msg = { score: scoreOf(candidateOf(el)), sample: readSample(el, doc) };
    this.env.ipc.send(CHANNELS.MEDIA, msg);
    this.reported = true;
    return msg;
  }

  sync(plan) {
    const el = this.current && this.current.isConnected !== false ? this.current : this.best();
    if (!el) return false;
    try {
      return applyPlan(el, plan);
    } catch (e) {
      return false;
    }
  }
}

// ---------------------------------------------------------------------------------------------------
// Start inside an Electron renderer only.

if (typeof process === 'object' && process && process.type === 'renderer' && typeof window === 'object') {
  const { contextBridge, ipcRenderer } = require('electron');
  const agent = new FrameAgent({
    window,
    document: typeof document === 'object' ? document : null,
    ipc: {
      send: (channel, data) => ipcRenderer.send(channel, data),
      invoke: (channel, data) => ipcRenderer.invoke(channel, data),
      on: (channel, fn) => ipcRenderer.on(channel, fn),
    },
    runInMainWorld: (func, args) => contextBridge.executeInMainWorld({ func, args }),
    setInterval: (fn, ms) => setInterval(fn, ms),
    clearInterval: t => clearInterval(t),
    MutationObserver: typeof MutationObserver === 'function' ? MutationObserver : null,
    guardKey: '__akwGuard' + Math.random().toString(36).slice(2),
  });
  agent.start();
}

if (typeof module === 'object' && module) {
  module.exports = {
    CHANNELS, ENFORCE_MS, RESCAN_EVERY, SAMPLE_MS, AD_MARKERS, LIVE_MARKERS, PLAYER_ROOTS,
    isMediaElement, clampUnit, pickMedia, scoreOf, candidateOf, readSample, applyPlan, akwGuard, FrameAgent,
  };
}
