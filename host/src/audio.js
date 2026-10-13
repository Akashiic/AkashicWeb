'use strict';
// OS-mode audio (ADR-007): Chromium plays to the default device; the game pushes one gain
// (distance x Minecraft master x records) that the screen preload (page/screen-preload.js) applies to
// every media element of every frame from document start, and the engine-level mute
// (webContents.setAudioMuted) is the only hard guarantee. Muting never depends on page JS.
//
// The engine mute is on when any of these holds (engineMuted):
//  - the game muted the screen, or its gain is 0;
//  - SPATIAL capture is not running (from CREATE until the capture has started, and after it stopped
//    or failed): local playback is then not suppressed by the capture (enableLocalEcho false), so the
//    page would play through the speakers at full page volume (wave-1 review);
//  - the current document's preload has not yet taken the gain (awaitingPage) and the gain is below 1:
//    whatever cannot be turned down yet is silenced instead (fail safe if the preload does not run).

const { CHANNELS } = require('./page/screen-preload');

/** Pure engine-mute decision. */
function engineMuted(s) {
  return !!(s.muted || !(s.volume > 0) || s.spatialHold || (s.awaitingPage && s.volume < 1));
}

/** Engine mute plus page volume for one screen. */
class AudioController {
  /**
   * @param wc webContents of the screen (setAudioMuted, isDestroyed)
   * @param options broadcast(channel, value): sends to every frame's preload
   */
  constructor(wc, options = {}) {
    this.wc = wc;
    this.broadcast = options.broadcast || (() => {});
    this.muted = false;
    this.volume = 1;
    this.spatialHold = false;
    this.awaitingPage = false;
    this.engine = null; // last value given to setAudioMuted
  }

  /** Game state from CREATE/AUDIO: engine mute decision, then the gain to every frame. */
  set(muted, volume) {
    this.muted = !!muted;
    const v = Number(volume);
    this.volume = Number.isFinite(v) ? Math.min(1, Math.max(0, v)) : 0;
    this._apply();
    this.broadcast(CHANNELS.VOLUME, this.volume);
  }

  setSpatialHold(on) {
    this.spatialHold = !!on;
    this._apply();
  }

  setAwaitingPage(on) {
    this.awaitingPage = !!on;
    this._apply();
  }

  isEngineMuted() {
    return this.engine === true;
  }

  _apply() {
    const m = engineMuted(this);
    if (m === this.engine || this.wc.isDestroyed()) return;
    this.engine = m;
    this.wc.setAudioMuted(m);
  }
}

module.exports = { engineMuted, AudioController };
