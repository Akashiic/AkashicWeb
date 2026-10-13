'use strict';
// One offscreen browser per in-world screen (ADR-002): CREATE/DESTROY/NAVIGATE/INPUT/AUDIO/RENDER/
// RESIZE/SYNC, STATE on change, EVENT on failures. Pure decisions live in policy.js, input.js,
// media.js, audio.js and frames.js; page-side work runs in the screen preload
// (page/screen-preload.js); this file is the Electron wiring.

const path = require('path');
const P = require('./protocol');
const { pageSizeProblem } = require('./messages');
const { checkNavigation, resolveVerdict } = require('./policy');
const { InputMapper } = require('./input');
const { AudioController } = require('./audio');
const { MediaController } = require('./media');
const { CHANNELS } = require('./page/screen-preload');
const { SpatialCapture } = require('./capture');
const { redactUrl } = require('./log');
const { EventLimiter } = require('./limiter');
const { monoMs } = require('./clock');

// Sandboxed, contextIsolation, contextBridge only; runs at document start in the main frame and,
// with nodeIntegrationInSubFrames (no Node there either: the renderer is sandboxed), in iframes.
const SCREEN_PRELOAD = path.join(__dirname, 'page', 'screen-preload.js');

const MAX_SCREENS = 16; // Java's browser budget is 2-4 (ADR-012 item 7); this is only a safety cap
const MAX_TITLE = 256;
const SUPPRESSED_REPORT_MS = 2000; // how often counts of rate-limited EVENTs are reported
// window.open is honoured only this soon after real input (INPUT mouseUp/keyDown) on the same screen:
// a stand-in for Chromium's user activation, which Electron's window-open handler does not report.
const POPUP_AFTER_INPUT_MS = 1000;
// Net errors that are our own doing or a user stop, not a load failure worth a placeholder.
const QUIET_LOAD_ERRORS = new Set([-3 /* ERR_ABORTED */, -20 /* ERR_BLOCKED_BY_CLIENT */]);

class Screen {
  constructor(id, spec, ctx) {
    this.id = id;
    this.spec = spec;
    this.ctx = ctx;
    this.painting = true;
    this.audible = false;
    this.cursor = 'default';
    this.lastState = '';
    this.stateQueued = false;
    this.destroyed = false;
    this.mapper = new InputMapper(spec.width, spec.height);
    this.capture = null;
    // Navigation generation: every navigation request (load, back/forward/reload/stop, window.open)
    // takes a new number; an answer that arrives later for an older number (a name resolution) is
    // dropped, so a slow DNS answer can never overtake a newer NAVIGATE.
    this.navGen = 0;
    this.lastActivationAt = -Infinity; // host monotonic ms of the last INPUT mouseUp/keyDown
    // Preload handshake per document: when the main frame's preload took the state (akw-init) and
    // when the last cross-document main-frame navigation started (audio.js awaitingPage).
    this.navStartAt = -Infinity;
    this.mainInitAt = -Infinity;
    // SPATIAL capture: 'none' | 'starting' | 'active' | 'failed' (engine-muted unless 'active').
    this.captureState = 'none';
  }

  now() {
    return this.ctx.now ? this.ctx.now() : monoMs();
  }

  open() {
    const ctx = this.ctx;
    this.sessionEntry = ctx.sessions.forScreen(this.id, this.spec.session);
    this.win = (ctx.createWindow || createBrowserWindow)({
      show: false,
      width: this.spec.width,
      height: this.spec.height,
      useContentSize: true,
      frame: false,
      skipTaskbar: true,
      paintWhenInitiallyHidden: true,
      backgroundColor: '#ffffff',
      webPreferences: {
        // Device scale factor 1: FRAME width/height always equal the CREATE/RESIZE size, whatever the
        // Windows display scaling (125%/150%) (also forced process-wide in main.js).
        offscreen: { deviceScaleFactor: 1 },
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false,
        nodeIntegrationInWorker: false,
        preload: SCREEN_PRELOAD,
        // Runs the (sandboxed, Node-free) preload in iframes too, so embedded players get the gain and
        // the guard at document start. It grants no Node integration: the renderer is sandboxed.
        nodeIntegrationInSubFrames: true,
        webviewTag: false,
        backgroundThrottling: false,
        session: this.sessionEntry.session,
        devTools: false,
        spellcheck: false,
        disableDialogs: true,
        navigateOnDragDrop: false,
        autoplayPolicy: 'no-user-gesture-required',
        disableHtmlFullscreenWindowResize: true,
        enableWebSQL: false,
        webSecurity: true,
        allowRunningInsecureContent: false,
        experimentalFeatures: false,
        plugins: false,
      },
    });
    const wc = this.win.webContents;
    this.wc = wc;
    this.wcId = wc.id;
    ctx.byWebContents.set(wc.id, this);
    wc.setFrameRate(this.spec.fps);
    wc.setIgnoreMenuShortcuts(true);
    // Public pages are IP loggers' favourite place; WebRTC must not reveal the player's LAN address.
    wc.setWebRTCIPHandlingPolicy('default_public_interface_only');
    this._wireNavigationPolicy(wc);
    this._wireState(wc);
    this._wireHardening(wc);
    wc.on('paint', (event, dirty, image) => {
      if (this.painting && !this.destroyed) ctx.scheduler.offer(this.id, image, dirty);
    });

    const broadcast = (channel, value) => this.broadcast(channel, value);
    this.audio = new AudioController(wc, { broadcast });
    this.media = new MediaController({ broadcast, now: () => this.now(), onSample: sample => ctx.link.send(P.T.MEDIA, this.id, sample) });
    this._wirePreload(wc);
    if (this.spec.audio === 'spatial') {
      // Silent until the capture runs: before getDisplayMedia resolves, local playback is not muted.
      this.captureState = 'starting';
      this.audio.setSpatialHold(true);
    }
    this.audio.set(false, this.spec.volume);
    this._updateSampling();

    this.load(this.spec.url || 'about:blank');
    if (this.spec.audio === 'spatial') this.startCapture();
  }

  /** IPC with the screen preload of every frame (page/screen-preload.js). */
  _wirePreload(wc) {
    // A document's preload asks for the current state as soon as it starts (before page scripts).
    wc.ipc.handle(CHANNELS.INIT, event => {
      const frame = event && event.senderFrame;
      if (frame && !frame.parent) {
        this.mainInitAt = this.now();
        if (this.audio.awaitingPage) this.audio.setAwaitingPage(false);
      }
      return { volume: this.audio.volume, sampling: this.media.sampling };
    });
    wc.ipc.on(CHANNELS.MEDIA, (event, payload) => this.media.onFrameSample(event && event.senderFrame, payload));
    wc.on('did-start-navigation', (details, url, isInPlace, isMainFrame) => {
      const main = details && typeof details.isMainFrame === 'boolean' ? details.isMainFrame : !!isMainFrame;
      const sameDoc = details && typeof details.isSameDocument === 'boolean' ? details.isSameDocument : !!isInPlace;
      if (main && !sameDoc) this.navStartAt = this.now();
    });
  }

  /** Sends to the preload of every frame (frames being torn down are skipped). */
  broadcast(channel, value) {
    if (this.destroyed || !this.wc || this.wc.isDestroyed()) return;
    let frames;
    try {
      frames = this.wc.mainFrame.framesInSubtree;
    } catch (e) {
      return; // main frame being swapped: the new document asks for the state itself (akw-init)
    }
    for (const f of frames) {
      try {
        if (!f.detached && !(typeof f.isDestroyed === 'function' && f.isDestroyed())) f.send(channel, value);
      } catch (e) { /* frame went away */ }
    }
  }

  _wireNavigationPolicy(wc) {
    const ctx = this.ctx;
    const check = (url, frame) => checkNavigation(url, ctx.policy(), frame, { allowFile: ctx.allowFile });
    wc.on('will-navigate', details => {
      const v = check(details.url, 'main');
      if (!v.ok) {
        details.preventDefault();
        this.blocked(v, 'will-navigate');
      }
    });
    wc.on('will-frame-navigate', details => {
      if (details.isMainFrame) return; // will-navigate covers the main frame
      const v = check(details.url, 'sub');
      if (!v.ok) {
        details.preventDefault();
        this.blocked(v, 'will-frame-navigate');
      }
    });
    wc.on('will-redirect', details => {
      const v = check(details.url, details.isMainFrame ? 'main' : 'sub');
      if (!v.ok) {
        details.preventDefault();
        this.blocked(v, 'will-redirect');
      }
    });
    // No child windows (ADR-013): a popup becomes a navigation of this screen if the policy allows it,
    // and only right after real input on this screen. Electron has no popup blocker, so without the
    // input rule any (ad) iframe could take the whole screen over at any time, on every client
    // independently, away from the server's URL authority.
    wc.setWindowOpenHandler(details => {
      const recentInput = this.now() - this.lastActivationAt <= POPUP_AFTER_INPUT_MS;
      if (!recentInput) {
        let host = '';
        try { host = new URL(details.url).hostname; } catch (e) { /* malformed: reported as is */ }
        this.blocked({ url: details.url, host, reason: 'popup' }, 'window-open');
        return { action: 'deny' };
      }
      const v = check(details.url, 'main');
      if (v.ok) {
        this.lastActivationAt = -Infinity; // one popup per input, like Chromium's activation consumption
        setImmediate(() => this.load(v.url));
      } else {
        this.blocked(v, 'window-open');
      }
      return { action: 'deny' };
    });
  }

  _wireState(wc) {
    const changed = () => this.stateChanged();
    for (const ev of ['did-start-loading', 'did-stop-loading', 'page-title-updated', 'audio-state-changed']) wc.on(ev, changed);
    wc.on('did-navigate', () => {
      this.media.reset();
      this.mapper.reset();
      // Like Chromium's user activation, input on the old document does not carry over to a new one.
      this.lastActivationAt = -Infinity;
      // A new document: if its preload has not taken the gain yet (it asks at document start, which
      // can arrive before or after this event), engine-mute until it has (audio.js awaitingPage).
      if (this.mainInitAt < this.navStartAt) this.audio.setAwaitingPage(true);
      changed();
    });
    wc.on('did-stop-loading', () => {
      // Still waiting for the main document's preload: ask it again (covers an INIT that raced a
      // navigation). If no preload answers, the screen stays engine-muted: fail safe.
      if (this.audio.awaitingPage) {
        try { wc.mainFrame.send(CHANNELS.REINIT); } catch (e) { /* frame gone */ }
      }
    });
    wc.on('did-navigate-in-page', (event, url, isMainFrame) => { if (isMainFrame) changed(); });
    wc.on('cursor-changed', (event, type) => {
      this.cursor = typeof type === 'string' ? type : 'default';
      changed();
    });
    wc.on('enter-html-full-screen', () => { this.media.fullscreen = true; });
    wc.on('leave-html-full-screen', () => { this.media.fullscreen = false; });
    wc.on('did-fail-load', (event, code, description, url, isMainFrame) => {
      if (!isMainFrame || QUIET_LOAD_ERRORS.has(code)) return;
      this.ctx.emitEvent(this.id, 'load-error', { code, description, url });
    });
    wc.on('render-process-gone', (event, details) => {
      this.ctx.emitEvent(this.id, 'renderer-gone', { reason: details && details.reason, exitCode: details && details.exitCode });
      changed();
    });
    wc.on('unresponsive', () => this.ctx.emitEvent(this.id, 'unresponsive', {}));
    wc.on('responsive', () => this.ctx.emitEvent(this.id, 'responsive', {}));
  }

  _wireHardening(wc) {
    // A beforeunload prompt cannot be answered on an in-world screen and would block NAVIGATE.
    wc.on('will-prevent-unload', event => event.preventDefault());
    // No HTTP auth prompts and no client certificates sent from a public screen.
    wc.on('login', (event, details, authInfo, callback) => {
      event.preventDefault();
      callback();
    });
    wc.on('select-client-certificate', (event, url, list, callback) => {
      event.preventDefault();
      callback();
    });
    wc.on('select-bluetooth-device', (event, devices, callback) => {
      event.preventDefault();
      callback('');
    });
    wc.on('will-attach-webview', event => event.preventDefault());
    wc.on('devtools-opened', () => wc.closeDevTools());
  }

  blocked(verdict, via) {
    this.ctx.log.info('screen ' + this.id + ' blocked ' + verdict.reason + ' ' + redactUrl(verdict.url) + ' via ' + via);
    this.ctx.emitEvent(this.id, 'blocked-url', { url: verdict.url, host: verdict.host, reason: verdict.reason, via });
  }

  /**
   * Loads a URL after the policy check (CREATE, NAVIGATE, window.open). Names are resolved first so a
   * name that points inside the LAN is refused before navigating: the screen keeps its current page
   * instead of committing an error page. The request filter repeats the check for navigations the
   * page starts itself.
   */
  load(url) {
    if (this.destroyed) return;
    const gen = ++this.navGen;
    this.lastActivationAt = -Infinity; // a navigation the host starts is not a user gesture on the next page
    const v = checkNavigation(url, this.ctx.policy(), 'main', { allowFile: this.ctx.allowFile });
    if (!v.ok) {
      this.refuse(v, 'navigate');
      return;
    }
    if (!v.needsDns) {
      this._loadChecked(v.url);
      return;
    }
    const entry = this.sessionEntry;
    const lookup = entry.verdict ? entry.verdict(v.host, v.url) : resolveVerdict(entry.session, v.host);
    lookup.then(r => {
      if (this.destroyed || gen !== this.navGen) return; // a newer navigation was requested meanwhile
      if (r.ok) this._loadChecked(v.url);
      else this.refuse({ ok: false, reason: r.reason, host: v.host, url: v.url }, 'navigate');
    });
  }

  _loadChecked(url) {
    // loadURL rejects on aborts and failures; did-fail-load already reports them.
    this.wc.loadURL(url).catch(() => {});
  }

  refuse(verdict, via) {
    this.blocked(verdict, via);
    if (!this.wc.getURL()) this.wc.loadURL('about:blank').catch(() => {});
  }

  navigate(cmd) {
    if (cmd.url) return this.load(cmd.url);
    this.navGen++; // back/forward/reload/stop also cancel a load still waiting for its DNS answer
    const nh = this.wc.navigationHistory;
    switch (cmd.action) {
      case 'back': if (nh.canGoBack()) nh.goBack(); break;
      case 'forward': if (nh.canGoForward()) nh.goForward(); break;
      case 'reload': this.wc.reload(); break;
      case 'stop': this.wc.stop(); break;
      default: break;
    }
    return undefined;
  }

  input(evt) {
    const events = this.mapper.map(evt);
    if (evt.type === 'mouseUp' || evt.type === 'keyDown') this.lastActivationAt = this.now();
    if (events.length === 0) return;
    // sendInputEvent needs a focused view for keyboard input (web-contents.md); focusing per event
    // is cheap and keeps several offscreen screens independent.
    if (!this.wc.isFocused()) this.wc.focus();
    for (const e of events) this.wc.sendInputEvent(e);
  }

  setAudio(cmd) {
    this.audio.set(cmd.muted, cmd.volume);
  }

  render(cmd) {
    this.wc.setFrameRate(cmd.fps);
    if (cmd.painting && !this.painting) {
      this.painting = true;
      this.wc.startPainting();
      // The game kept the last frame; whatever changed meanwhile is unknown, so repaint everything.
      this.ctx.scheduler.requestFull(this.id);
      this.wc.invalidate();
    } else if (!cmd.painting && this.painting) {
      this.painting = false;
      this.wc.stopPainting();
    }
    this._updateSampling();
  }

  resize(cmd) {
    const tooBig = pageSizeProblem(cmd.width, cmd.height);
    if (tooBig) {
      // The page keeps its current size; a bigger one could not travel as one FRAME.
      this.ctx.emitEvent(this.id, 'screen-limit', tooBig);
      return;
    }
    this.spec = Object.assign({}, this.spec, { width: cmd.width, height: cmd.height });
    this.mapper.setSize(cmd.width, cmd.height);
    this.win.setContentSize(cmd.width, cmd.height);
    this.ctx.scheduler.requestFull(this.id);
    this.wc.invalidate();
  }

  sync(cmd) {
    return this.media.sync(cmd);
  }

  startCapture() {
    const ctx = this.ctx;
    const Capture = ctx.CaptureClass || SpatialCapture;
    this.capture = new Capture({
      screenWc: this.wc,
      session: this.sessionEntry.session,
      registry: ctx.registry,
      log: ctx.log,
      onPcm: parts => ctx.link.send(P.T.PCM, this.id, parts),
      onStarted: () => {
        if (this.destroyed || this.captureState !== 'starting') return;
        // The capture now keeps local playback silent (enableLocalEcho false): lift the engine mute.
        this.captureState = 'active';
        this.audio.setSpatialHold(false);
      },
      onError: detail => {
        if (this.destroyed) return;
        // Engine-muted again: after a capture stops, the page would play through the speakers. The
        // screen stays muted until the game re-creates it (in OS mode, ADR-008 fallback).
        this.captureState = 'failed';
        this.audio.setSpatialHold(true);
        ctx.emitEvent(this.id, 'capture-error', { detail });
      },
    });
    this.capture.start();
  }

  stateChanged() {
    if (this.stateQueued || this.destroyed) return;
    this.stateQueued = true;
    setImmediate(() => {
      this.stateQueued = false;
      this.sendState();
    });
  }

  sendState() {
    if (this.destroyed || this.wc.isDestroyed()) return;
    const nh = this.wc.navigationHistory;
    const state = {
      url: this.wc.getURL(),
      title: this.wc.getTitle().slice(0, MAX_TITLE),
      loading: this.wc.isLoading(),
      canGoBack: nh.canGoBack(),
      canGoForward: nh.canGoForward(),
      audible: this.wc.isCurrentlyAudible(),
      cursor: this.cursor,
    };
    const json = JSON.stringify(state);
    if (json === this.lastState) return;
    this.lastState = json;
    this.audible = state.audible;
    this.ctx.link.send(P.T.STATE, this.id, state);
    this._updateSampling();
  }

  /**
   * MEDIA sampling (4 Hz, in the preloads) on every painted or audible screen: followers need their
   * own clock to steer, not only the controller.
   */
  _updateSampling() {
    if (this.destroyed) return;
    this.media.setSampling(this.painting || this.audible);
  }

  /** Internal address reached (detector): stop and blank the screen. */
  internalIpReached(info) {
    if (this.destroyed) return;
    this.wc.stop();
    this.wc.loadURL('about:blank').catch(() => {});
    this.ctx.emitEvent(this.id, 'blocked-url', { url: info.url, reason: 'internal-ip', ip: info.ip, via: 'response' });
  }

  destroy() {
    if (this.destroyed) return;
    this.destroyed = true;
    if (this.media) this.media.reset();
    if (this.capture) this.capture.stop();
    if (this.wc && !this.wc.isDestroyed()) {
      try {
        this.wc.ipc.removeHandler(CHANNELS.INIT);
        this.wc.ipc.removeAllListeners(CHANNELS.MEDIA);
      } catch (e) { /* closing anyway */ }
    }
    this.ctx.scheduler.remove(this.id);
    this.ctx.byWebContents.delete(this.wcId);
    if (this.win && !this.win.isDestroyed()) this.win.destroy();
    this.ctx.sessions.release(this.sessionEntry);
  }
}

class ScreenManager {
  /**
   * ctx: link, scheduler, sessions, registry, policy(), allowFile, worldId, pageKey, log
   */
  constructor(ctx) {
    this.ctx = Object.assign({}, ctx, {
      byWebContents: new Map(),
      emitEvent: (screenId, kind, detail) => this.emitEvent(screenId, kind, detail),
    });
    this.screens = new Map();
    this.limiter = ctx.limiter || new EventLimiter();
    this.reportTimer = null;
  }

  /**
   * Sends an EVENT. Only page-triggerable kinds are rate limited (limiter.js); what is dropped is
   * reported every SUPPRESSED_REPORT_MS as one EVENT of the same kind with detail {suppressed: n}.
   */
  emitEvent(screenId, kind, detail) {
    if (!this.limiter.allow(screenId, kind)) {
      this._scheduleSuppressedReport();
      return;
    }
    this.ctx.link.send(P.T.EVENT, screenId, { kind, detail });
  }

  _scheduleSuppressedReport() {
    if (this.reportTimer) return;
    this.reportTimer = setTimeout(() => {
      this.reportTimer = null;
      this.reportSuppressed();
    }, SUPPRESSED_REPORT_MS);
    if (typeof this.reportTimer.unref === 'function') this.reportTimer.unref();
  }

  /** Sends the pending {suppressed: n} counts (also called by tests). */
  reportSuppressed() {
    for (const s of this.limiter.takeSuppressed()) {
      if (s.screenId !== 0 && !this.screens.has(s.screenId)) continue;
      this.ctx.link.send(P.T.EVENT, s.screenId, { kind: s.kind, detail: { suppressed: s.count } });
    }
  }

  create(id, spec) {
    if (id === 0) throw new Error('screenId 0 is reserved for host messages');
    if (this.screens.has(id)) this.destroy(id); // CREATE resets a screen
    const tooBig = pageSizeProblem(spec.width, spec.height);
    if (tooBig) {
      this.emitEvent(id, 'screen-limit', tooBig);
      return null;
    }
    if (this.screens.size >= MAX_SCREENS) {
      this.emitEvent(id, 'screen-limit', { reason: 'count', limit: MAX_SCREENS });
      return null;
    }
    const s = new (this.ctx.ScreenClass || Screen)(id, spec, this.ctx);
    this.screens.set(id, s);
    try {
      s.open();
    } catch (err) {
      this.screens.delete(id);
      s.destroy();
      throw err;
    }
    return s;
  }

  get(id) {
    return this.screens.get(id) || null;
  }

  byWebContentsId(wcId) {
    return wcId == null ? null : this.ctx.byWebContents.get(wcId) || null;
  }

  destroy(id) {
    const s = this.screens.get(id);
    if (!s) return;
    this.screens.delete(id);
    this.limiter.forget(id);
    s.destroy();
  }

  destroyAll() {
    for (const id of [...this.screens.keys()]) this.destroy(id);
    if (this.reportTimer) clearTimeout(this.reportTimer);
    this.reportTimer = null;
  }

  setPolicyNow() {
    // A stricter policy must apply to what is already on screen, not only to the next navigation.
    for (const s of this.screens.values()) {
      const url = s.wc.getURL();
      if (!url || url === 'about:blank') continue;
      const v = checkNavigation(url, this.ctx.policy(), 'main', { allowFile: this.ctx.allowFile });
      if (!v.ok) {
        s.blocked(v, 'policy-change');
        s.wc.loadURL('about:blank').catch(() => {});
      }
    }
  }
}

/** Real offscreen window; tests pass ctx.createWindow instead, so this module loads without Electron. */
function createBrowserWindow(options) {
  const { BrowserWindow } = require('electron');
  return new BrowserWindow(options);
}

module.exports = { Screen, ScreenManager, MAX_SCREENS };
