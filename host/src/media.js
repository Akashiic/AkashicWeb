'use strict';
// Media clock sampling (MEDIA at 4 Hz) and follower steering (SYNC) on HTMLMediaElement, not on the
// YouTube IFrame API, whose setPlaybackRate rounds towards 1 (ADR-012).
//
// The page-side functions below are serialised with Function.prototype.toString() and run inside the
// page, so they must stay self-contained: no references to anything outside their own body except
// their arguments and standard DOM/JS globals. They are unit-tested here with fake elements.

const { monoMs } = require('./clock');

const SAMPLE_INTERVAL_MS = 250;
const SUBFRAME_SCAN_EVERY = 8; // ticks (2 s): looking into iframes costs one IPC per frame
const RATE_MIN = 0.95;
const RATE_MAX = 1.05;
const LIVE_EDGE_MARGIN_S = 0.5;
const AD_SELECTORS = [
  '.html5-video-player.ad-showing', // YouTube (unverified until the sync spike, ADR-012)
  '[data-a-target="video-ad-label"]', // Twitch
  '[data-a-target="video-ad-countdown"]',
].join(',');

// ---------------------------------------------------------------------------------------------------
// Page-side (serialised)

/** Index of the media element that drives the screen's clock, or -1. Candidates are plain records. */
function akwPickMedia(cands) {
  let best = -1;
  let bestScore = -1;
  for (let i = 0; i < cands.length; i++) {
    const c = cands[i];
    if (!c.connected) continue;
    let score = 0;
    if (!c.paused && !c.ended) score += 4;
    if (c.isVideo) score += 2;
    if (c.readyState >= 2) score += 1;
    // Area breaks ties and favours the visible player over thumbnails and preview videos.
    score += Math.min(0.99, c.area / 1e7);
    if (score > bestScore) {
      best = i;
      bestScore = score;
    }
  }
  return best;
}

/** Clock sample of one element, JSON-safe (duration -1 when unknown or infinite). */
function akwReadSample(el, doc, adSelectors) {
  const duration = el.duration;
  const finite = typeof duration === 'number' && isFinite(duration);
  let seekEnd = NaN;
  try {
    if (el.seekable && el.seekable.length > 0) seekEnd = el.seekable.end(el.seekable.length - 1);
  } catch (e) { /* seekable may be unavailable mid-load */ }
  const live = duration === Infinity || !!(doc.querySelector && doc.querySelector('.ytp-live'));
  const behind = live && isFinite(seekEnd) ? Math.max(0, seekEnd - el.currentTime) : 0;
  return {
    mediaTime: el.currentTime,
    paused: !!el.paused,
    rate: el.playbackRate,
    duration: finite ? duration : -1,
    live,
    behindLiveEdge: behind,
    adShowing: !!(doc.querySelector && doc.querySelector(adSelectors)),
    fullscreen: !!doc.fullscreenElement,
  };
}

/** Finds the main media element of `doc` (open shadow roots included). */
function akwFindMedia(doc) {
  const els = [];
  const visit = root => {
    const list = root.querySelectorAll('video, audio');
    for (let i = 0; i < list.length; i++) els.push(list[i]);
    const all = root.querySelectorAll('*');
    for (let i = 0; i < all.length; i++) if (all[i].shadowRoot) visit(all[i].shadowRoot);
  };
  visit(doc);
  const cands = els.map(el => {
    const r = el.getBoundingClientRect ? el.getBoundingClientRect() : { width: 0, height: 0 };
    return {
      connected: el.isConnected !== false,
      paused: el.paused,
      ended: el.ended,
      isVideo: el.tagName === 'VIDEO',
      readyState: el.readyState,
      area: Math.max(0, r.width) * Math.max(0, r.height),
    };
  });
  const i = akwPickMedia(cands); // defined next to this function in the injected script
  return i < 0 ? null : els[i];
}

/** Applies a steering plan (from planSync) to an element. */
function akwApplyPlan(el, plan) {
  if (plan.seekTo !== null && isFinite(plan.seekTo)) el.currentTime = plan.seekTo;
  if (Math.abs(el.playbackRate - plan.rate) > 1e-4) el.playbackRate = plan.rate;
  if (plan.pause && !el.paused) el.pause();
  if (plan.play && el.paused) {
    const p = el.play();
    if (p && typeof p.catch === 'function') p.catch(() => {});
  }
  return true;
}

const PAGE_LIB = [akwPickMedia, akwReadSample, akwFindMedia, akwApplyPlan].map(f => f.toString()).join('\n');

function sampleScript() {
  return '(() => {' + PAGE_LIB + '\nconst el = akwFindMedia(document);\nreturn el ? akwReadSample(el, document, '
    + JSON.stringify(AD_SELECTORS) + ') : null;\n})()';
}

function applyScript(plan) {
  return '(() => {' + PAGE_LIB + '\nconst el = akwFindMedia(document);\nreturn el ? akwApplyPlan(el, '
    + JSON.stringify(plan) + ') : false;\n})()';
}

// ---------------------------------------------------------------------------------------------------
// Steering math (main process)

function clampRate(rate) {
  if (typeof rate !== 'number' || !isFinite(rate)) return 1;
  return Math.min(RATE_MAX, Math.max(RATE_MIN, rate));
}

/**
 * Turns a SYNC command into concrete actions on the element, given the latest local sample.
 * Java computes the steering (ADR-012); the host only enforces the invariants it can see locally:
 * rate within 0.95..1.05, no steering while this client's own ad plays, never seek or run ahead of a
 * live edge. Returns null when there is no media element yet.
 */
function planSync(cmd, sample) {
  if (!sample) return null;
  if (sample.adShowing) return { rate: 1, seekTo: null, play: false, pause: false };
  const plan = {
    rate: cmd.paused ? 1 : clampRate(cmd.rate),
    seekTo: null,
    play: !cmd.paused && sample.paused,
    pause: !!cmd.paused && !sample.paused,
  };
  if (cmd.seek && typeof cmd.targetMediaTime === 'number' && isFinite(cmd.targetMediaTime)) {
    let target = Math.max(0, cmd.targetMediaTime);
    if (sample.live) {
      const edge = sample.mediaTime + Math.max(0, sample.behindLiveEdge || 0);
      target = Math.min(target, Math.max(0, edge - LIVE_EDGE_MARGIN_S));
    } else if (sample.duration > 0) {
      target = Math.min(target, sample.duration);
    }
    plan.seekTo = target;
  }
  if (sample.live && (sample.behindLiveEdge || 0) <= LIVE_EDGE_MARGIN_S && plan.rate > 1) plan.rate = 1;
  return plan;
}

// ---------------------------------------------------------------------------------------------------
// Electron wiring

/**
 * Samples one screen's main media element at 4 Hz and applies SYNC plans to it. The main frame is
 * read in an isolated world; iframes (embedded players) can only be reached through
 * WebFrameMain.executeJavaScript, which runs in the page's main world (Electron 44 has no
 * isolated-world variant on WebFrameMain).
 */
class MediaController {
  constructor(wc, options) {
    this.wc = wc;
    this.worldId = options.worldId;
    this.onSample = options.onSample;
    this.log = options.log;
    this.timer = null;
    this.busy = false;
    this.ticks = 0;
    this.target = null; // WebFrameMain of an iframe holding the media, or null for the main frame
    this.last = null;
    this.fullscreen = false;
  }

  start() {
    if (this.timer) return;
    this.timer = setInterval(() => this.tick(), SAMPLE_INTERVAL_MS);
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
  }

  /** Forget the iframe target and last sample (navigation). */
  reset() {
    this.target = null;
    this.last = null;
  }

  _run(frame, code) {
    if (this.wc.isDestroyed()) return Promise.resolve(null);
    if (!frame) return this.wc.executeJavaScriptInIsolatedWorld(this.worldId, [{ code }]);
    if (frame.isDestroyed() || frame.detached) return Promise.resolve(null);
    return frame.executeJavaScript(code);
  }

  async tick() {
    if (this.busy || this.wc.isDestroyed()) return;
    this.busy = true;
    try {
      this.ticks++;
      const t0 = monoMs();
      let sample = await this._run(this.target, sampleScript()).catch(() => null);
      if (!sample && this.target) this.target = null;
      if (!sample && this.ticks % SUBFRAME_SCAN_EVERY === 0) sample = await this._scanSubframes();
      const t1 = monoMs();
      if (sample && typeof sample === 'object') {
        if (this.fullscreen) sample.fullscreen = true;
        // The sample was taken somewhere between t0 and t1; the midpoint halves the IPC error.
        this.last = Object.assign({ t: (t0 + t1) / 2 }, sample);
        this.onSample(this.last);
      } else {
        this.last = null;
      }
    } finally {
      this.busy = false;
    }
  }

  async _scanSubframes() {
    const main = this.wc.mainFrame;
    if (!main) return null;
    for (const frame of main.framesInSubtree) {
      if (frame === main) continue;
      const s = await this._run(frame, sampleScript()).catch(() => null);
      if (s) {
        this.target = frame;
        return s;
      }
    }
    return null;
  }

  /** Applies a SYNC command; resolves true when a media element received it. */
  async sync(cmd) {
    const plan = planSync(cmd, this.last);
    if (!plan) return false;
    const ok = await this._run(this.target, applyScript(plan)).catch(err => {
      this.log.warn('sync failed', String(err && err.message));
      return false;
    });
    return !!ok;
  }
}

module.exports = {
  SAMPLE_INTERVAL_MS, RATE_MIN, RATE_MAX, LIVE_EDGE_MARGIN_S, AD_SELECTORS,
  akwPickMedia, akwReadSample, akwFindMedia, akwApplyPlan, sampleScript, applyScript, clampRate, planSync, MediaController,
};
