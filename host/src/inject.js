'use strict';
// Runs host scripts in every frame of a screen (ADR-007 amendment): preloads only reach the main
// frame, while YouTube/Twitch players embedded in third-party pages live in cross-origin iframes.
//
// World choice: the main frame runs scripts in an isolated world (page JS cannot see or tamper with
// them); iframes are reached through WebFrameMain.executeJavaScript, which Electron 44 only offers in
// the page's main world. Scripts must therefore be idempotent and keep their state under a key the
// page cannot guess. Nothing here is a security boundary: hard mute is webContents.setAudioMuted.

const { webFrameMain } = safeElectron();

function safeElectron() {
  try {
    return require('electron');
  } catch (e) {
    return {}; // plain Node (unit tests): FrameInjector is not used there
  }
}

class FrameInjector {
  /**
   * @param wc webContents of the screen
   * @param options worldId (isolated world for the main frame), log
   */
  constructor(wc, options) {
    this.wc = wc;
    this.worldId = options.worldId;
    this.log = options.log;
    this.scripts = new Map(); // name -> {code, mainWorld}
    this.failures = 0;

    wc.on('dom-ready', () => this.runInFrame(wc.mainFrame));
    wc.on('did-frame-finish-load', (event, isMainFrame, processId, routingId) => {
      const frame = isMainFrame ? wc.mainFrame : fromId(processId, routingId);
      if (frame) this.runInFrame(frame);
    });
    wc.on('frame-created', (event, details) => {
      const frame = details && details.frame;
      if (frame && typeof frame.once === 'function') frame.once('dom-ready', () => this.runInFrame(frame));
    });
  }

  /**
   * Sets (or replaces) a script and runs it at once in every current frame.
   * `mainWorld` forces the main world in the main frame too (needed to override page globals).
   */
  set(name, code, mainWorld = false) {
    this.scripts.set(name, { code, mainWorld });
    this.runEverywhere(name);
  }

  runEverywhere(onlyName) {
    if (this.wc.isDestroyed() || !this.wc.mainFrame) return;
    let frames;
    try {
      frames = this.wc.mainFrame.framesInSubtree;
    } catch (e) {
      return; // main frame is being swapped (navigation); dom-ready will run the scripts again
    }
    for (const frame of frames) this.runInFrame(frame, onlyName);
  }

  runInFrame(frame, onlyName) {
    if (!frame || this.wc.isDestroyed()) return;
    for (const [name, s] of this.scripts) {
      if (onlyName && name !== onlyName) continue;
      this._exec(frame, s).catch(err => {
        // Frames die and navigate all the time; a failed injection is retried on the next load event.
        // Ad-heavy pages fail often, so only the first few are logged.
        if (++this.failures <= 10 && this.log) this.log.info('inject ' + name + ' skipped: ' + String(err && err.message).slice(0, 120));
      });
    }
  }

  _exec(frame, s) {
    try {
      if (frame.isDestroyed && frame.isDestroyed()) return Promise.resolve();
      if (frame.detached) return Promise.resolve();
      if (frame === this.wc.mainFrame && !s.mainWorld) {
        return this.wc.executeJavaScriptInIsolatedWorld(this.worldId, [{ code: s.code }]);
      }
      return frame.executeJavaScript(s.code);
    } catch (e) {
      return Promise.reject(e);
    }
  }
}

function fromId(processId, routingId) {
  try {
    return webFrameMain ? webFrameMain.fromId(processId, routingId) : null;
  } catch (e) {
    return null;
  }
}

module.exports = { FrameInjector };
