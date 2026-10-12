'use strict';
// One offscreen browser per in-world screen (ADR-002): CREATE/DESTROY/NAVIGATE/INPUT/AUDIO/RENDER/
// RESIZE/SYNC, STATE on change, EVENT on failures. Pure decisions live in policy.js, input.js,
// media.js and frames.js; this file is the Electron wiring.

const { BrowserWindow } = require('electron');
const P = require('./protocol');
const { checkNavigation, resolveVerdict } = require('./policy');
const { InputMapper } = require('./input');
const { FrameInjector } = require('./inject');
const { AudioController } = require('./audio');
const { MediaController } = require('./media');
const { guardScript } = require('./guard');
const { SpatialCapture } = require('./capture');
const { redactUrl } = require('./log');
const { EventLimiter } = require('./limiter');

const MAX_SCREENS = 16; // Java's browser budget is 2-4 (ADR-012 item 7); this is only a safety cap
const MAX_TITLE = 256;
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
  }

  open() {
    const ctx = this.ctx;
    this.sessionEntry = ctx.sessions.forScreen(this.id, this.spec.session);
    this.win = new BrowserWindow({
      show: false,
      width: this.spec.width,
      height: this.spec.height,
      useContentSize: true,
      frame: false,
      skipTaskbar: true,
      paintWhenInitiallyHidden: true,
      backgroundColor: '#ffffff',
      webPreferences: {
        offscreen: true,
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false,
        nodeIntegrationInWorker: false,
        nodeIntegrationInSubFrames: false,
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

    this.injector = new FrameInjector(wc, { worldId: ctx.worldId, log: ctx.log });
    this.injector.set('guard', guardScript(ctx.pageKey + 'g'), true);
    this.audio = new AudioController(wc, this.injector, ctx.pageKey + 'v');
    this.audio.set(false, this.spec.volume);
    this.media = new MediaController(wc, {
      worldId: ctx.worldId,
      log: ctx.log,
      onSample: sample => ctx.link.send(P.T.MEDIA, this.id, sample),
    });
    this.media.start();

    this.load(this.spec.url || 'about:blank');
    if (this.spec.audio === 'spatial') this.startCapture();
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
    // No child windows (ADR-013): a popup becomes a navigation of this screen if the policy allows it.
    wc.setWindowOpenHandler(details => {
      const v = check(details.url, 'main');
      if (v.ok) setImmediate(() => this.load(v.url));
      else this.blocked(v, 'window-open');
      return { action: 'deny' };
    });
  }

  _wireState(wc) {
    const changed = () => this.stateChanged();
    for (const ev of ['did-start-loading', 'did-stop-loading', 'page-title-updated', 'audio-state-changed']) wc.on(ev, changed);
    wc.on('did-navigate', () => {
      this.media.reset();
      this.mapper.reset();
      changed();
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
    const v = checkNavigation(url, this.ctx.policy(), 'main', { allowFile: this.ctx.allowFile });
    if (!v.ok) {
      this.refuse(v, 'navigate');
      return;
    }
    if (!v.needsDns) {
      this._loadChecked(v.url);
      return;
    }
    resolveVerdict(this.sessionEntry.session, v.host).then(r => {
      if (this.destroyed) return;
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
    this.capture = new SpatialCapture({
      screenWc: this.wc,
      session: this.sessionEntry.session,
      registry: ctx.registry,
      log: ctx.log,
      onPcm: parts => ctx.link.send(P.T.PCM, this.id, parts),
      onError: detail => ctx.emitEvent(this.id, 'capture-error', { detail }),
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

  /** MEDIA sampling is only useful while someone can see or hear the screen. */
  _updateSampling() {
    if (this.destroyed) return;
    if (this.painting || this.audible) this.media.start();
    else this.media.stop();
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
    if (this.media) this.media.stop();
    if (this.capture) this.capture.stop();
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
    this.limiter = new EventLimiter();
  }

  emitEvent(screenId, kind, detail) {
    if (!this.limiter.allow(screenId)) return;
    this.ctx.link.send(P.T.EVENT, screenId, { kind, detail });
  }

  create(id, spec) {
    if (id === 0) throw new Error('screenId 0 is reserved for host messages');
    if (this.screens.has(id)) this.destroy(id); // CREATE resets a screen
    if (this.screens.size >= MAX_SCREENS) {
      this.emitEvent(id, 'load-error', { reason: 'screen-limit', limit: MAX_SCREENS });
      return null;
    }
    const s = new Screen(id, spec, this.ctx);
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

module.exports = { Screen, ScreenManager, MAX_SCREENS };
