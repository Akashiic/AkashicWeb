'use strict';
// Media clock (MEDIA at 4 Hz) and follower steering (SYNC) on HTMLMediaElement, not on the YouTube
// IFrame API, whose setPlaybackRate rounds towards 1 (ADR-012).
//
// Sampling happens in the screen preload of each frame (page/screen-preload.js) from a cached set of
// media elements, without waiting for the page load; this module is the main-process side:
//  - sanitizeSample: a sample comes from a renderer, so only the 8 known fields are copied, numbers
//    must be finite and are clamped, booleans are coerced; anything else drops the sample;
//  - MediaController: keeps the latest sample of each frame, forwards the best frame's samples as
//    MEDIA (stamped with the host monotonic clock on arrival) and sends SYNC plans to that frame;
//  - planSync: the steering invariants the host enforces locally.
// MEDIA is sampled on every painted or audible screen: followers need their own clock to steer.

const { monoMs } = require('./clock');
const { CHANNELS } = require('./page/screen-preload');

const SAMPLE_INTERVAL_MS = 250; // the preload's sampling period
const FRESH_MS = 1000; // a frame's last sample competes for "best frame" this long
const RATE_MIN = 0.95;
const RATE_MAX = 1.05;
const LIVE_EDGE_MARGIN_S = 0.5;
const MAX_MEDIA_TIME = 1e9; // seconds; also bounds durations and live-edge distances
const MAX_RATE = 16; // Chromium's own playbackRate ceiling

// ---------------------------------------------------------------------------------------------------
// Sample sanitising (main process)

function finiteIn(v, min, max) {
  return typeof v === 'number' && Number.isFinite(v) ? Math.min(max, Math.max(min, v)) : null;
}

/** The 8 MEDIA fields of a renderer-provided sample, or null when it is unusable. */
function sanitizeSample(raw) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
  const mediaTime = finiteIn(raw.mediaTime, 0, MAX_MEDIA_TIME);
  const rate = finiteIn(raw.rate, 0, MAX_RATE);
  if (mediaTime === null || rate === null) return null;
  const duration = raw.duration === -1 ? -1 : finiteIn(raw.duration, 0, MAX_MEDIA_TIME);
  const behind = finiteIn(raw.behindLiveEdge, 0, MAX_MEDIA_TIME);
  return {
    mediaTime,
    paused: !!raw.paused,
    rate,
    duration: duration === null ? -1 : duration,
    live: !!raw.live,
    behindLiveEdge: behind === null ? 0 : behind,
    adShowing: !!raw.adShowing,
    fullscreen: !!raw.fullscreen,
  };
}

// ---------------------------------------------------------------------------------------------------
// Steering math (main process)

function clampRate(rate) {
  if (typeof rate !== 'number' || !Number.isFinite(rate)) return 1;
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
  if (cmd.seek && typeof cmd.targetMediaTime === 'number' && Number.isFinite(cmd.targetMediaTime)) {
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
// Main-process controller

function frameKey(frame) {
  return frame.processId + ':' + frame.routingId;
}

function frameGone(frame) {
  try {
    return !frame || frame.detached || (typeof frame.isDestroyed === 'function' && frame.isDestroyed());
  } catch (e) {
    return true;
  }
}

/**
 * MEDIA and SYNC for one screen. The preload of each frame with media sends {score, sample} at 4 Hz
 * while sampling is on; the frame with the best element (playing > paused, video > audio, larger)
 * among those heard from within FRESH_MS is the screen's clock, and SYNC goes to it.
 */
class MediaController {
  /**
   * options: onSample(sample) -> sends MEDIA; broadcast(channel, value) -> every frame's preload;
   * now() -> host monotonic ms (default clock.monoMs).
   */
  constructor(options) {
    this.onSample = options.onSample;
    this.broadcast = options.broadcast || (() => {});
    this.now = options.now || monoMs;
    this.frames = new Map(); // frameKey -> {frame, score, sample, at}
    this.target = null; // frameKey of the frame whose samples are forwarded
    this.last = null; // last MEDIA sent, with t
    this.fullscreen = false; // HTML fullscreen seen by the host (enter/leave-html-full-screen)
    this.sampling = false;
    this.rejected = 0;
  }

  /** Turns 4 Hz sampling on or off in every frame (new frames learn it from their INIT answer). */
  setSampling(on) {
    const v = !!on;
    if (v === this.sampling) return;
    this.sampling = v;
    if (!v) this.reset();
    this.broadcast(CHANNELS.SAMPLING, v);
  }

  /** Forget frames and the last sample (navigation). */
  reset() {
    this.frames.clear();
    this.target = null;
    this.last = null;
  }

  /** One message from a frame's preload (event.senderFrame, payload). */
  onFrameSample(frame, payload) {
    if (!this.sampling || frameGone(frame)) return;
    const key = frameKey(frame);
    if (!payload || typeof payload !== 'object' || !(typeof payload.score === 'number' && payload.score >= 0)) {
      this.frames.delete(key); // that frame has no media (any more)
      if (this.target === key) this.target = null;
      return;
    }
    const sample = sanitizeSample(payload.sample);
    if (!sample) {
      this.rejected++;
      return;
    }
    const t = this.now();
    this.frames.set(key, { frame, score: Math.min(100, payload.score), sample, at: t });
    let bestKey = null;
    let bestScore = -1;
    for (const [k, e] of this.frames) {
      if (t - e.at > FRESH_MS || frameGone(e.frame)) {
        this.frames.delete(k);
        continue;
      }
      if (e.score > bestScore) {
        bestKey = k;
        bestScore = e.score;
      }
    }
    if (bestKey !== key) return; // another frame holds the better element and sends its own samples
    this.target = key;
    if (this.fullscreen) sample.fullscreen = true;
    // Stamped on arrival: the renderer -> main hop is well under the ±40 ms SYNC deadband (ADR-012).
    this.last = Object.assign({ t }, sample);
    this.onSample(this.last);
  }

  /** Applies a SYNC command in the frame that holds the media; true when a plan was sent. */
  sync(cmd) {
    const plan = planSync(cmd, this.last);
    if (!plan) return false;
    const e = this.target ? this.frames.get(this.target) : null;
    if (!e || frameGone(e.frame)) return false;
    try {
      e.frame.send(CHANNELS.SYNC, plan);
      return true;
    } catch (err) {
      return false;
    }
  }
}

module.exports = {
  SAMPLE_INTERVAL_MS, FRESH_MS, RATE_MIN, RATE_MAX, LIVE_EDGE_MARGIN_S,
  sanitizeSample, clampRate, planSync, frameKey, MediaController,
};
