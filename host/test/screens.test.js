'use strict';
// Screen manager rules with fake Electron objects (test/fakes.js). The integration test covers the
// same paths against the real Electron build.
const test = require('node:test');
const assert = require('node:assert');
const { ScreenManager, MAX_SCREENS } = require('../src/screens');
const { LIMITS } = require('../src/messages');
const { fakeContext, flush } = require('./fakes');

function spec(o = {}) {
  return Object.assign({ width: 640, height: 360, fps: 30, url: '', session: 'shared', audio: 'os', volume: 1 }, o);
}

test('CREATE beyond the page cap is refused with EVENT screen-limit (reason size), no window', () => {
  const f = fakeContext();
  const sm = new ScreenManager(f.ctx);
  try {
    for (const [w, h] of [[4097, 100], [100, 4097], [2048, 2048], [4096, 1025], [65535, 65535]]) {
      assert.strictEqual(sm.create(7, spec({ width: w, height: h })), null, w + 'x' + h);
    }
    assert.strictEqual(f.windows.length, 0);
    const evs = f.events();
    assert.strictEqual(evs.length, 5);
    assert.ok(evs.every(e => e.kind === 'screen-limit' && e.detail.reason === 'size' && e.screenId === 7));
    assert.deepStrictEqual(evs[2].detail, { reason: 'size', width: 2048, height: 2048, maxWidth: 4096, maxHeight: 4096, maxPixels: LIMITS.MAX_PIXELS });
    // The largest pages that fit one FRAME are accepted.
    assert.ok(sm.create(8, spec({ width: 4096, height: 1024 - 1 })));
    assert.ok(sm.create(9, spec({ width: 2048, height: 2047 })));
  } finally {
    sm.destroyAll();
  }
});

test('the host-wide screen cap answers EVENT screen-limit (reason count), not load-error', () => {
  const f = fakeContext();
  const sm = new ScreenManager(f.ctx);
  try {
    for (let id = 1; id <= MAX_SCREENS; id++) assert.ok(sm.create(id, spec()), 'screen ' + id);
    assert.strictEqual(sm.create(MAX_SCREENS + 1, spec()), null);
    const ev = f.events().pop();
    assert.deepStrictEqual(ev, { screenId: MAX_SCREENS + 1, kind: 'screen-limit', detail: { reason: 'count', limit: MAX_SCREENS } });
    assert.ok(!f.events().some(e => e.kind === 'load-error'));
  } finally {
    sm.destroyAll();
  }
});

test('a blocked-url flood never hides renderer-gone or capture-error; dropped ones are reported as a count', () => {
  const f = fakeContext();
  const sm = new ScreenManager(f.ctx);
  try {
    sm.create(4, spec());
    for (let i = 0; i < 200; i++) sm.emitEvent(4, 'blocked-url', { url: 'http://192.168.0.' + (i % 250) + '/', reason: 'internal' });
    sm.emitEvent(4, 'renderer-gone', { reason: 'crashed', exitCode: 1 });
    sm.emitEvent(4, 'capture-error', { detail: 'x' });
    sm.emitEvent(4, 'unresponsive', {});
    sm.emitEvent(4, 'load-error', { code: -105, description: 'ERR_NAME_NOT_RESOLVED', url: 'https://a/' });
    const kinds = f.events().map(e => e.kind);
    assert.ok(kinds.includes('renderer-gone') && kinds.includes('capture-error') && kinds.includes('unresponsive') && kinds.includes('load-error'));
    assert.ok(kinds.filter(k => k === 'blocked-url').length <= 21, 'the flood itself is limited');
    sm.reportSuppressed();
    const summary = f.events().pop();
    assert.strictEqual(summary.kind, 'blocked-url');
    assert.strictEqual(summary.screenId, 4);
    assert.strictEqual(summary.detail.suppressed, 200 - kinds.filter(k => k === 'blocked-url').length);
  } finally {
    sm.destroyAll();
  }
});

const PUBLIC = { endpoints: [{ address: '93.184.216.34', family: 'ipv4' }] };

test('navigation order: a slow DNS answer for an older NAVIGATE never overtakes a newer one', async () => {
  const f = fakeContext();
  const sm = new ScreenManager(f.ctx);
  try {
    const s = sm.create(1, spec());
    const wc = f.windows[0].webContents;
    s.navigate({ url: 'https://slow.example/first' }); // needs DNS: waits for resolveHost
    s.navigate({ url: 'https://8.8.8.8/second' }); // IP literal: loads at once
    f.resolveCalls[0].resolve(PUBLIC);
    await flush();
    await flush();
    assert.deepStrictEqual(wc.loads.filter(u => u !== 'about:blank'), ['https://8.8.8.8/second'], 'the stale first URL is never loaded');
    // Two names: the second resolves first, then the first; only the second loads.
    s.navigate({ url: 'https://a.example/1' });
    s.navigate({ url: 'https://b.example/2' });
    f.resolveCalls[2].resolve(PUBLIC);
    await flush();
    f.resolveCalls[1].resolve(PUBLIC);
    await flush();
    await flush();
    assert.deepStrictEqual(wc.loads.filter(u => u !== 'about:blank').slice(1), ['https://b.example/2']);
  } finally {
    sm.destroyAll();
  }
});

test('navigation order: back/forward/reload/stop cancel a load still waiting for DNS', async () => {
  const f = fakeContext();
  const sm = new ScreenManager(f.ctx);
  try {
    const s = sm.create(1, spec());
    const wc = f.windows[0].webContents;
    for (const action of ['back', 'forward', 'reload', 'stop']) {
      const n = f.resolveCalls.length;
      s.navigate({ url: 'https://late.example/' + action });
      s.navigate({ action });
      f.resolveCalls[n].resolve(PUBLIC);
      await flush();
      await flush();
    }
    assert.ok(!wc.loads.some(u => u.startsWith('https://late.example/')), wc.loads.join(','));
  } finally {
    sm.destroyAll();
  }
});

test('window.open without recent real input is denied with EVENT blocked-url reason popup', async () => {
  let now = 10000;
  const f = fakeContext({ now: () => now });
  const sm = new ScreenManager(f.ctx);
  try {
    sm.create(1, spec());
    const wc = f.windows[0].webContents;
    assert.deepStrictEqual(wc.windowOpen('https://ads.example/landing'), { action: 'deny' });
    await flush();
    assert.ok(!wc.loads.includes('https://ads.example/landing'), 'no takeover without input');
    const ev = f.events().pop();
    assert.deepStrictEqual(ev, { screenId: 1, kind: 'blocked-url', detail: { url: 'https://ads.example/landing', host: 'ads.example', reason: 'popup', via: 'window-open' } });
    // Input long ago does not count either.
    sm.get(1).input({ type: 'mouseUp', x: 1, y: 1, button: 'left', clickCount: 1 });
    now += 1500;
    wc.windowOpen('https://late.example/');
    await flush();
    assert.strictEqual(f.events().pop().detail.reason, 'popup');
  } finally {
    sm.destroyAll();
  }
});

test('window.open right after a mouseUp or keyDown on that screen loads in the same screen, once', async () => {
  let now = 10000;
  const f = fakeContext({ now: () => now });
  const sm = new ScreenManager(f.ctx);
  try {
    sm.create(1, spec());
    sm.create(2, spec());
    const wc1 = f.windows[0].webContents;
    const wc2 = f.windows[1].webContents;
    sm.get(1).input({ type: 'mouseDown', x: 1, y: 1, button: 'left', clickCount: 1 });
    sm.get(1).input({ type: 'mouseUp', x: 1, y: 1, button: 'left', clickCount: 1 });
    now += 300;
    wc2.windowOpen('https://8.8.8.8/other-screen'); // input on screen 1 does not activate screen 2
    wc1.windowOpen('https://8.8.8.8/allowed');
    wc1.windowOpen('https://8.8.8.8/second-popup'); // the input was consumed by the first popup
    await flush();
    assert.ok(wc1.loads.includes('https://8.8.8.8/allowed'));
    assert.ok(!wc1.loads.includes('https://8.8.8.8/second-popup'));
    assert.ok(!wc2.loads.includes('https://8.8.8.8/other-screen'));
    sm.get(1).input({ type: 'keyDown', keyCode: 'Enter', modifiers: [] });
    wc1.windowOpen('https://8.8.8.8/by-key');
    await flush();
    assert.ok(wc1.loads.includes('https://8.8.8.8/by-key'));
    // A mouseMove is not an activation.
    sm.get(1).input({ type: 'mouseMove', x: 3, y: 3 });
    wc1.windowOpen('https://8.8.8.8/by-move');
    await flush();
    assert.ok(!wc1.loads.includes('https://8.8.8.8/by-move'));
  } finally {
    sm.destroyAll();
  }
});

test('input on one document does not allow a popup from the next one (navigation clears it)', async () => {
  let now = 10000;
  const f = fakeContext({ now: () => now });
  const sm = new ScreenManager(f.ctx);
  try {
    const s = sm.create(1, spec());
    const wc = f.windows[0].webContents;
    s.input({ type: 'mouseUp', x: 1, y: 1, button: 'left', clickCount: 1 });
    wc.emit('did-navigate', {}, 'https://next.example/', 200, 'OK');
    wc.windowOpen('https://8.8.8.8/from-next-page');
    s.input({ type: 'keyDown', keyCode: 'a', modifiers: [] });
    s.navigate({ url: 'https://8.8.4.4/game-navigate' });
    wc.windowOpen('https://8.8.8.8/after-game-navigate');
    await flush();
    assert.ok(!wc.loads.some(u => u.startsWith('https://8.8.8.8/')), wc.loads.join(' '));
    assert.deepStrictEqual(f.events().filter(e => e.detail.reason === 'popup').length, 2);
  } finally {
    sm.destroyAll();
  }
});

const { CHANNELS } = require('../src/page/screen-preload');
const { engineMuted } = require('../src/audio');

test('engineMuted: game mute, zero gain, spatial hold, and a document that has not taken a gain below 1', () => {
  const base = { muted: false, volume: 0.5, spatialHold: false, awaitingPage: false };
  assert.strictEqual(engineMuted(base), false);
  assert.strictEqual(engineMuted(Object.assign({}, base, { muted: true })), true);
  assert.strictEqual(engineMuted(Object.assign({}, base, { volume: 0 })), true);
  assert.strictEqual(engineMuted(Object.assign({}, base, { volume: NaN })), true);
  assert.strictEqual(engineMuted(Object.assign({}, base, { spatialHold: true, volume: 1 })), true);
  assert.strictEqual(engineMuted(Object.assign({}, base, { awaitingPage: true })), true);
  assert.strictEqual(engineMuted(Object.assign({}, base, { awaitingPage: true, volume: 1 })), false, 'gain 1: nothing to turn down');
});

test('a new document is engine-muted until its preload has taken the gain (did-navigate before akw-init)', () => {
  let now = 1000;
  const f = fakeContext({ now: () => now });
  const sm = new ScreenManager(f.ctx);
  try {
    sm.create(1, spec({ volume: 0.3 }));
    const wc = f.windows[0].webContents;
    const init = wc.ipcHandlers.get(CHANNELS.INIT);
    assert.deepStrictEqual(init({ senderFrame: wc.mainFrame }), { volume: 0.3, sampling: true });
    assert.strictEqual(wc.muted, false);
    // Navigation: started, committed, the new document's preload not heard yet -> muted.
    now += 10;
    wc.emit('did-start-navigation', { isMainFrame: true, isSameDocument: false, url: 'https://b.example/' });
    now += 50;
    wc.emit('did-navigate', {}, 'https://b.example/');
    assert.strictEqual(wc.muted, true, 'muted while the gain cannot be applied');
    now += 5;
    init({ senderFrame: { parent: wc.mainFrame } }); // an iframe's preload does not count
    assert.strictEqual(wc.muted, true);
    init({ senderFrame: wc.mainFrame });
    assert.strictEqual(wc.muted, false, 'unmuted once the document holds the gain');
    // INIT before did-navigate (IPC order is not guaranteed): never muted.
    now += 10;
    wc.emit('did-start-navigation', { isMainFrame: true, isSameDocument: false, url: 'https://c.example/' });
    now += 10;
    init({ senderFrame: wc.mainFrame });
    wc.emit('did-navigate', {}, 'https://c.example/');
    assert.strictEqual(wc.muted, false);
    // Same-document navigations do not create a new document.
    now += 10;
    wc.emit('did-start-navigation', { isMainFrame: true, isSameDocument: true, url: 'https://c.example/#x' });
    wc.emit('did-navigate', {}, 'https://c.example/');
    assert.strictEqual(wc.muted, false);
    // Still waiting when loading stops: the main frame is asked again.
    now += 10;
    wc.emit('did-start-navigation', { isMainFrame: true, isSameDocument: false, url: 'https://d.example/' });
    wc.emit('did-navigate', {}, 'https://d.example/');
    wc.emit('did-stop-loading');
    assert.deepStrictEqual(wc.mainFrame.sent.filter(s => s[0] === CHANNELS.REINIT), [[CHANNELS.REINIT]]);
    assert.strictEqual(wc.muted, true, 'stays muted while no preload answers (fail safe)');
  } finally {
    sm.destroyAll();
  }
});

test('AUDIO reaches every frame\'s preload at once; volume 0 or muted is an engine mute', () => {
  const f = fakeContext();
  const sm = new ScreenManager(f.ctx);
  try {
    const s = sm.create(1, spec());
    const wc = f.windows[0].webContents;
    const child = { send: (...a) => child.sent.push(a), sent: [], detached: false, isDestroyed: () => false };
    wc.mainFrame.framesInSubtree = [wc.mainFrame, child];
    s.setAudio({ muted: false, volume: 0.25 });
    assert.deepStrictEqual(child.sent.pop(), [CHANNELS.VOLUME, 0.25]);
    assert.deepStrictEqual(wc.mainFrame.sent.pop(), [CHANNELS.VOLUME, 0.25]);
    assert.strictEqual(wc.muted, false);
    s.setAudio({ muted: false, volume: 0 });
    assert.strictEqual(wc.muted, true);
    s.setAudio({ muted: true, volume: 0.8 });
    assert.strictEqual(wc.muted, true);
    s.setAudio({ muted: false, volume: 0.8 });
    assert.strictEqual(wc.muted, false);
  } finally {
    sm.destroyAll();
  }
});

test('MEDIA sampling follows painting or audible, on every screen (followers too)', () => {
  const f = fakeContext();
  const sm = new ScreenManager(f.ctx);
  try {
    const s = sm.create(1, spec());
    const wc = f.windows[0].webContents;
    s.render({ fps: 5, painting: false });
    assert.deepStrictEqual(wc.mainFrame.sent.filter(m => m[0] === CHANNELS.SAMPLING).pop(), [CHANNELS.SAMPLING, false]);
    s.render({ fps: 30, painting: true });
    assert.deepStrictEqual(wc.mainFrame.sent.filter(m => m[0] === CHANNELS.SAMPLING).pop(), [CHANNELS.SAMPLING, true]);
    // A preload's sample becomes MEDIA for the game.
    wc.ipcListeners.get(CHANNELS.MEDIA)({ senderFrame: wc.mainFrame }, { score: 7, sample: { mediaTime: 3, rate: 1, paused: false } });
    const media = f.sent.filter(m => m.name === 'MEDIA');
    assert.strictEqual(media.length, 1);
    assert.strictEqual(media[0].payload.mediaTime, 3);
  } finally {
    sm.destroyAll();
  }
});

class FakeCapture {
  constructor(o) {
    this.o = o;
    FakeCapture.last = this;
  }

  start() {}

  stop() { this.stopped = true; }
}

test('SPATIAL: engine-muted from CREATE until the capture runs, and again after a capture error', () => {
  const f = fakeContext({ CaptureClass: FakeCapture });
  const sm = new ScreenManager(f.ctx);
  try {
    const s = sm.create(1, spec({ audio: 'spatial', volume: 1 }));
    const wc = f.windows[0].webContents;
    assert.strictEqual(wc.muted, true, 'before getDisplayMedia resolves, local playback is not suppressed');
    assert.strictEqual(wc.mutes[0], true, 'muted before anything could play');
    s.setAudio({ muted: false, volume: 1 });
    assert.strictEqual(wc.muted, true, 'AUDIO does not lift it while the capture is starting');
    FakeCapture.last.o.onStarted({ sampleRate: 48000 });
    assert.strictEqual(wc.muted, false, 'capturing: local echo off keeps the speakers silent');
    FakeCapture.last.o.onError('capture track ended');
    assert.strictEqual(wc.muted, true, 'muted again at once');
    assert.strictEqual(f.events().pop().kind, 'capture-error');
    s.setAudio({ muted: false, volume: 1 });
    assert.strictEqual(wc.muted, true, 'stays muted until the game re-creates the screen (OS mode)');
    sm.create(1, spec({ audio: 'os', volume: 0.5 }));
    assert.strictEqual(f.windows[1].webContents.muted, false);
  } finally {
    sm.destroyAll();
  }
});

test('RESIZE beyond the cap keeps the current size and answers EVENT screen-limit', async () => {
  const f = fakeContext();
  const sm = new ScreenManager(f.ctx);
  try {
    const s = sm.create(3, spec());
    s.resize({ width: 4096, height: 1100 });
    assert.deepStrictEqual(f.windows[0].contentSize, [640, 360]);
    assert.deepStrictEqual([s.spec.width, s.spec.height], [640, 360]);
    const ev = f.events().pop();
    assert.strictEqual(ev.kind, 'screen-limit');
    assert.strictEqual(ev.detail.reason, 'size');
    s.resize({ width: 1280, height: 720 });
    assert.deepStrictEqual(f.windows[0].contentSize, [1280, 720]);
    await flush();
  } finally {
    sm.destroyAll();
  }
});
