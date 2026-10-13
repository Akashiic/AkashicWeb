'use strict';
// SPATIAL audio capture for one screen (ADR-008, stretch goal, optional in this wave).
// Per the reviewer amendment the hidden capture window lives in the screen's OWN session partition,
// because setDisplayMediaRequestHandler is per session and cross-partition capture is unverified.
// One extra renderer per audible spatial screen. Verified only on Linux (see host/README.md);
// silent-source behaviour on Windows (enableLocalEcho=false) is untested.

const path = require('path');
const { monoMicros } = require('./clock');
const { withTimeout } = require('./policy');
const pcm = require('./pcm');

const CAPTURE_HTML = path.join(__dirname, 'capture', 'capture.html');
const CAPTURE_PRELOAD = path.join(__dirname, 'capture', 'capture-preload.js');
// A capture page that never answers must not leave the screen waiting (and engine-muted) forever.
const LOAD_TIMEOUT_MS = 10000;
const START_TIMEOUT_MS = 10000;

/**
 * Which screen each capture window may capture. The session's display-media handler consults it, so
 * neither a screen page nor anything else in the partition can obtain a capture stream.
 */
class CaptureRegistry {
  constructor() {
    this.targets = new Map(); // capture webContents id -> screen webContents
  }

  add(captureWcId, screenWc) {
    this.targets.set(captureWcId, screenWc);
  }

  remove(captureWcId) {
    this.targets.delete(captureWcId);
  }

  isCapturePage(wcId) {
    return this.targets.has(wcId);
  }

  /** Display-media handler for a session: grants the registered screen to its capture page only. */
  handler(webContentsModule) {
    return (request, callback) => {
      let requester = null;
      try {
        requester = request.frame ? webContentsModule.fromFrame(request.frame) : null;
      } catch (e) {
        requester = null;
      }
      const target = requester ? this.targets.get(requester.id) : null;
      if (!target || target.isDestroyed() || request.frame !== requester.mainFrame) {
        callback(null);
        return;
      }
      // enableLocalEcho stays false: while captured, the screen's own OS output is muted.
      callback({ video: target.mainFrame, audio: target.mainFrame });
    };
  }
}

class SpatialCapture {
  /**
   * options: screenWc, session, registry, onPcm(parts), onStarted(info), onError(detail), log
   */
  constructor(options) {
    this.o = options;
    this.win = null;
    this.packets = 0;
    this.stopped = false;
  }

  async start() {
    const o = this.o;
    const { BrowserWindow } = require('electron'); // loaded here so plain-Node unit tests can import this file
    const win = new BrowserWindow({
      show: false,
      width: 64,
      height: 64,
      webPreferences: {
        session: o.session,
        offscreen: true,
        sandbox: true,
        contextIsolation: true,
        nodeIntegration: false,
        preload: CAPTURE_PRELOAD,
        backgroundThrottling: false,
        devTools: false,
        spellcheck: false,
        autoplayPolicy: 'no-user-gesture-required',
      },
    });
    this.win = win;
    const wc = win.webContents;
    this.wcId = wc.id;
    o.registry.add(wc.id, o.screenWc);
    wc.setFrameRate(1);
    wc.setWindowOpenHandler(() => ({ action: 'deny' }));
    wc.on('will-navigate', e => e.preventDefault());
    wc.on('render-process-gone', (e, details) => this._fail('capture renderer gone: ' + (details && details.reason)));
    wc.ipc.on('akw-pcm', (event, data) => this._onPcm(event, data));
    wc.ipc.on('akw-capture-ended', () => this._fail('capture track ended'));
    try {
      await withTimeout(win.loadFile(CAPTURE_HTML), LOAD_TIMEOUT_MS);
      if (this.stopped) return null;
      wc.stopPainting();
      // userGesture=true: getDisplayMedia needs transient activation.
      const info = await withTimeout(wc.executeJavaScript('window.akwStartCapture(' + JSON.stringify(pcm.workletSource()) + ')', true), START_TIMEOUT_MS);
      if (this.stopped) return null;
      o.log.info('spatial capture started', JSON.stringify(info));
      if (o.onStarted) o.onStarted(info);
      return info;
    } catch (err) {
      this._fail('capture start failed: ' + String(err && err.message));
      return null;
    }
  }

  _onPcm(event, data) {
    if (this.stopped || !this.win || event.sender !== this.win.webContents) return;
    if (event.senderFrame && event.senderFrame !== this.win.webContents.mainFrame) return;
    const bytes = Buffer.from(data);
    const frames = bytes.length / (2 * pcm.CHANNELS);
    if (!Number.isInteger(frames) || frames < 1) return;
    // pts of the first sample: arrival time minus the packet's duration. Jitter of the worklet ->
    // main-process hop is not compensated here; Java's tap min-filters the offset (ADR-008 step 4).
    const pts = monoMicros() - Math.round((frames * 1e6) / pcm.SAMPLE_RATE);
    this.packets++;
    this.o.onPcm(pcm.pcmPayload(pts, pcm.SAMPLE_RATE, pcm.CHANNELS, bytes));
  }

  _fail(detail) {
    if (this.stopped) return;
    this.o.log.warn('spatial capture error:', detail);
    this.o.onError(detail);
    this.stop();
  }

  stop() {
    if (this.stopped) return;
    this.stopped = true;
    if (this.wcId != null) this.o.registry.remove(this.wcId);
    if (this.win && !this.win.isDestroyed()) this.win.destroy();
    this.win = null;
  }
}

module.exports = { CaptureRegistry, SpatialCapture };
