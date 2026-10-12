'use strict';
// OS-mode audio (ADR-007): Chromium plays to the default device; the game pushes one gain
// (distance x Minecraft master x records) that the page applies to every media element, and the
// engine-level mute (webContents.setAudioMuted) is the only hard guarantee. Muting never depends on
// page JS: volume 0 also mutes at the engine.

/**
 * Page-side, serialised and run in every frame (see inject.js). Installs once per document under
 * `key`, then only updates the target volume. Re-applies every 100 ms because sites rewrite
 * element.volume, rescans open shadow roots every second, and tracks new elements immediately via a
 * MutationObserver and capture-phase media events. Detached `new Audio()` objects and WebAudio are
 * not reachable from here; only the engine mute covers them.
 */
function akwVolume(key, volume) {
  const g = globalThis;
  let st = g[key];
  if (!st) {
    st = { v: 1, els: new Set() };
    Object.defineProperty(g, key, { value: st, enumerable: false, configurable: false, writable: false });
    const isMedia = n => !!n && (n.tagName === 'VIDEO' || n.tagName === 'AUDIO');
    const enforce = el => {
      try {
        if (Math.abs(el.volume - st.v) > 0.001) el.volume = st.v;
      } catch (e) { /* element in a bad state; next tick retries */ }
    };
    const track = el => {
      st.els.add(el);
      enforce(el);
    };
    const scan = root => {
      root.querySelectorAll('video, audio').forEach(track);
      root.querySelectorAll('*').forEach(n => { if (n.shadowRoot) scan(n.shadowRoot); });
    };
    st.scan = () => { if (g.document) scan(g.document); };
    st.enforceAll = () => {
      st.els.forEach(el => {
        if (!el.isConnected && el.paused) st.els.delete(el);
        else enforce(el);
      });
    };
    if (g.document) {
      const onEvent = e => { if (isMedia(e.target)) track(e.target); };
      ['play', 'playing', 'loadstart', 'loadedmetadata', 'volumechange'].forEach(t => g.document.addEventListener(t, onEvent, true));
      if (typeof g.MutationObserver === 'function') {
        new g.MutationObserver(records => {
          records.forEach(r => r.addedNodes.forEach(n => {
            if (isMedia(n)) track(n);
            else if (n.querySelectorAll) n.querySelectorAll('video, audio').forEach(track);
          }));
        }).observe(g.document, { childList: true, subtree: true });
      }
    }
    let ticks = 0;
    setInterval(() => {
      st.enforceAll();
      if (++ticks % 10 === 0) st.scan();
    }, 100);
  }
  st.v = Math.min(1, Math.max(0, Number(volume) || 0));
  st.scan();
  st.enforceAll();
  return st.els.size;
}

function volumeScript(key, volume) {
  return '(' + akwVolume.toString() + ')(' + JSON.stringify(key) + ',' + JSON.stringify(volume) + ')';
}

/** Engine mute plus page volume for one screen. */
class AudioController {
  constructor(wc, injector, key) {
    this.wc = wc;
    this.injector = injector;
    this.key = key;
    this.muted = false;
    this.volume = 1;
  }

  set(muted, volume) {
    this.muted = !!muted;
    this.volume = Math.min(1, Math.max(0, volume));
    if (!this.wc.isDestroyed()) this.wc.setAudioMuted(this.muted || this.volume <= 0);
    this.injector.set('volume', volumeScript(this.key, this.volume));
  }
}

module.exports = { akwVolume, volumeScript, AudioController };
