'use strict';
const test = require('node:test');
const assert = require('node:assert');
const vm = require('vm');
const Md = require('../src/media');

function fakeEl(opts = {}) {
  const el = Object.assign({
    tagName: 'VIDEO', currentTime: 10, duration: 100, paused: false, ended: false, playbackRate: 1, readyState: 4, isConnected: true,
    rect: { width: 1280, height: 720 }, seekable: null, calls: [],
  }, opts);
  el.getBoundingClientRect = () => el.rect;
  el.pause = () => { el.calls.push('pause'); el.paused = true; };
  el.play = () => { el.calls.push('play'); el.paused = false; return Promise.resolve(); };
  return el;
}

function fakeDoc(els, selectors = {}, extra = {}) {
  return Object.assign({
    fullscreenElement: null,
    querySelectorAll: sel => (sel === 'video, audio' ? els : sel === '*' ? [] : []),
    querySelector: sel => (Object.keys(selectors).some(k => sel.includes(k)) ? {} : null),
  }, extra);
}

test('pickMedia prefers playing video, then any video, then larger', () => {
  const c = (o) => Object.assign({ connected: true, paused: true, ended: false, isVideo: true, readyState: 4, area: 1000 }, o);
  assert.strictEqual(Md.akwPickMedia([]), -1);
  assert.strictEqual(Md.akwPickMedia([c({ area: 9e5 }), c({ paused: false, area: 100 })]), 1);
  assert.strictEqual(Md.akwPickMedia([c({ isVideo: false, paused: false }), c({ paused: false })]), 1);
  assert.strictEqual(Md.akwPickMedia([c({ area: 10 }), c({ area: 5000 })]), 1);
  assert.strictEqual(Md.akwPickMedia([c({ connected: false, paused: false }), c({})]), 1);
  assert.strictEqual(Md.akwPickMedia([c({ paused: false, ended: true }), c({ isVideo: false, paused: false })]), 1);
});

test('readSample: VOD element', () => {
  const s = Md.akwReadSample(fakeEl({ currentTime: 12.5, playbackRate: 1.03 }), fakeDoc([]), Md.AD_SELECTORS);
  assert.deepStrictEqual(s, { mediaTime: 12.5, paused: false, rate: 1.03, duration: 100, live: false, behindLiveEdge: 0, adShowing: false, fullscreen: false });
});

test('readSample: live stream reports distance to the seekable end; Infinity is JSON-safe', () => {
  const el = fakeEl({ duration: Infinity, currentTime: 500, seekable: { length: 1, end: () => 503.25 } });
  const s = Md.akwReadSample(el, fakeDoc([]), Md.AD_SELECTORS);
  assert.strictEqual(s.live, true);
  assert.strictEqual(s.duration, -1);
  assert.strictEqual(s.behindLiveEdge, 3.25);
  assert.doesNotThrow(() => JSON.parse(JSON.stringify(s)));
});

test('readSample: YouTube live badge, ad overlay and fullscreen', () => {
  const doc = fakeDoc([], { '.ytp-live': true, 'ad-showing': true }, { fullscreenElement: {} });
  const s = Md.akwReadSample(fakeEl({ seekable: { length: 1, end: () => 30 }, currentTime: 29 }), doc, Md.AD_SELECTORS);
  assert.deepStrictEqual([s.live, s.adShowing, s.fullscreen, s.behindLiveEdge], [true, true, true, 1]);
});

test('findMedia walks open shadow roots', () => {
  const hidden = fakeEl({ rect: { width: 1920, height: 1080 } });
  const shadow = { querySelectorAll: sel => (sel === 'video, audio' ? [hidden] : []) };
  const doc = {
    querySelectorAll: sel => (sel === 'video, audio' ? [fakeEl({ tagName: 'AUDIO', paused: true })] : sel === '*' ? [{ shadowRoot: shadow }, {}] : []),
  };
  assert.strictEqual(Md.akwFindMedia(doc), hidden);
});

test('the injected sample script is self-contained and runs in a bare page context', () => {
  const el = fakeEl({ currentTime: 3 });
  const ctx = vm.createContext({ document: fakeDoc([el]) });
  const result = vm.runInContext(Md.sampleScript(), ctx);
  assert.strictEqual(result.mediaTime, 3);
  assert.strictEqual(vm.runInContext(Md.sampleScript(), vm.createContext({ document: fakeDoc([]) })), null);
});

test('the injected apply script seeks, steers and plays', () => {
  const el = fakeEl({ paused: true });
  const ctx = vm.createContext({ document: fakeDoc([el]) });
  const ok = vm.runInContext(Md.applyScript({ rate: 1.04, seekTo: 42, play: true, pause: false }), ctx);
  assert.strictEqual(ok, true);
  assert.deepStrictEqual([el.currentTime, el.playbackRate, el.paused, el.calls], [42, 1.04, false, ['play']]);
});

test('applyPlan leaves an element alone when nothing changes', () => {
  const el = fakeEl({ playbackRate: 1 });
  Md.akwApplyPlan(el, { rate: 1, seekTo: null, play: false, pause: false });
  assert.deepStrictEqual([el.currentTime, el.calls], [10, []]);
  Md.akwApplyPlan(el, { rate: 1, seekTo: null, play: false, pause: true });
  assert.deepStrictEqual(el.calls, ['pause']);
});

const vod = { mediaTime: 50, paused: false, rate: 1, duration: 300, live: false, behindLiveEdge: 0, adShowing: false };
const live = { mediaTime: 1000, paused: false, rate: 1, duration: -1, live: true, behindLiveEdge: 4, adShowing: false };

test('planSync: rate clamped to 0.95..1.05', () => {
  assert.strictEqual(Md.planSync({ targetMediaTime: 50, rate: 1.5, paused: false, seek: false }, vod).rate, 1.05);
  assert.strictEqual(Md.planSync({ targetMediaTime: 50, rate: 0.5, paused: false, seek: false }, vod).rate, 0.95);
  assert.strictEqual(Md.planSync({ targetMediaTime: 50, rate: NaN, paused: false, seek: false }, vod).rate, 1);
  assert.strictEqual(Md.planSync({ targetMediaTime: 50, rate: 1.02, paused: false, seek: false }, vod).rate, 1.02);
});

test('planSync: VOD seek within the duration, never negative', () => {
  assert.strictEqual(Md.planSync({ targetMediaTime: 120, rate: 1, paused: false, seek: true }, vod).seekTo, 120);
  assert.strictEqual(Md.planSync({ targetMediaTime: 999, rate: 1, paused: false, seek: true }, vod).seekTo, 300);
  assert.strictEqual(Md.planSync({ targetMediaTime: -3, rate: 1, paused: false, seek: true }, vod).seekTo, 0);
  assert.strictEqual(Md.planSync({ targetMediaTime: 120, rate: 1, paused: false, seek: false }, vod).seekTo, null);
});

test('planSync: live never seeks past the edge minus a margin, never speeds up at the edge', () => {
  // Edge is at 1004; a target beyond it is pulled back to 1003.5.
  assert.strictEqual(Md.planSync({ targetMediaTime: 1010, rate: 1, paused: false, seek: true }, live).seekTo, 1004 - Md.LIVE_EDGE_MARGIN_S);
  assert.strictEqual(Md.planSync({ targetMediaTime: 995, rate: 1, paused: false, seek: true }, live).seekTo, 995);
  assert.strictEqual(Md.planSync({ targetMediaTime: 1000, rate: 1.05, paused: false, seek: false }, live).rate, 1.05, '4 s behind: may catch up');
  const atEdge = Object.assign({}, live, { behindLiveEdge: 0.2 });
  assert.strictEqual(Md.planSync({ targetMediaTime: 1000, rate: 1.05, paused: false, seek: false }, atEdge).rate, 1);
  assert.strictEqual(Md.planSync({ targetMediaTime: 1000, rate: 0.96, paused: false, seek: false }, atEdge).rate, 0.96, 'slowing down is fine');
});

test('planSync: suspended while this client shows an ad; play/pause convergence otherwise', () => {
  const ad = Object.assign({}, vod, { adShowing: true });
  assert.deepStrictEqual(Md.planSync({ targetMediaTime: 120, rate: 1.05, paused: true, seek: true }, ad), { rate: 1, seekTo: null, play: false, pause: false });
  assert.deepStrictEqual(Md.planSync({ targetMediaTime: 50, rate: 1.03, paused: true, seek: false }, vod), { rate: 1, seekTo: null, play: false, pause: true });
  const paused = Object.assign({}, vod, { paused: true });
  assert.deepStrictEqual(Md.planSync({ targetMediaTime: 50, rate: 1, paused: false, seek: false }, paused), { rate: 1, seekTo: null, play: true, pause: false });
  assert.strictEqual(Md.planSync({ targetMediaTime: 1, rate: 1, paused: false, seek: false }, null), null);
});

/** webContents stand-in: main frame plus optional iframes, each with its own media sample. */
function fakeWc(mainSample, frameSamples = []) {
  const main = { isDestroyed: () => false, detached: false };
  const frames = frameSamples.map(s => ({ isDestroyed: () => false, detached: false, executeJavaScript: async () => s }));
  main.framesInSubtree = [main].concat(frames);
  return {
    mainFrame: main,
    isDestroyed: () => false,
    executeJavaScriptInIsolatedWorld: async (world, scripts) => {
      assert.strictEqual(world, 1279);
      assert.strictEqual(typeof scripts[0].code, 'string');
      return mainSample;
    },
  };
}

test('MediaController: samples the main frame, stamps t, forwards to the game', async () => {
  const got = [];
  const mc = new Md.MediaController(fakeWc({ mediaTime: 5, paused: false, rate: 1, duration: 9, live: false, behindLiveEdge: 0, adShowing: false, fullscreen: false }),
    { worldId: 1279, onSample: s => got.push(s), log: { warn() {} } });
  await mc.tick();
  assert.strictEqual(got.length, 1);
  assert.strictEqual(got[0].mediaTime, 5);
  assert.strictEqual(typeof got[0].t, 'number');
  mc.fullscreen = true;
  await mc.tick();
  assert.strictEqual(got[1].fullscreen, true, 'HTML fullscreen seen by the host overrides the page sample');
});

test('MediaController: finds media inside an iframe after the periodic scan', async () => {
  const got = [];
  const mc = new Md.MediaController(fakeWc(null, [null, { mediaTime: 7, paused: false, rate: 1, duration: -1, live: true, behindLiveEdge: 1, adShowing: false, fullscreen: false }]),
    { worldId: 1279, onSample: s => got.push(s), log: { warn() {} } });
  for (let i = 0; i < 8; i++) await mc.tick();
  assert.strictEqual(got.length, 1, 'found on the 8th tick (2 s)');
  assert.ok(mc.target, 'remembers the iframe');
  await mc.tick();
  assert.strictEqual(got.length, 2, 'then sampled every tick');
});

test('MediaController.sync applies the plan in the frame that holds the media', async () => {
  const wc = fakeWc({ mediaTime: 5, paused: false, rate: 1, duration: 9, live: false, behindLiveEdge: 0, adShowing: false, fullscreen: false });
  const codes = [];
  wc.executeJavaScriptInIsolatedWorld = async (world, scripts) => { codes.push(scripts[0].code); return codes.length === 1 ? { mediaTime: 5, paused: false, rate: 1, duration: 9, live: false, behindLiveEdge: 0, adShowing: false } : true; };
  const mc = new Md.MediaController(wc, { worldId: 1279, onSample: () => {}, log: { warn() {} } });
  assert.strictEqual(await mc.sync({ targetMediaTime: 1, rate: 1.01, paused: false, seek: false }), false, 'no sample yet');
  await mc.tick();
  assert.strictEqual(await mc.sync({ targetMediaTime: 1, rate: 1.01, paused: false, seek: false }), true);
  assert.match(codes[1], /"rate":1\.01/);
});
