'use strict';
// Main-process side of MEDIA/SYNC. The page-side sampling (pickMedia, readSample, applyPlan) moved into
// the screen preload and is tested in preload.test.js.
const test = require('node:test');
const assert = require('node:assert');
const Md = require('../src/media');
const { CHANNELS } = require('../src/page/screen-preload');

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

test('sanitizeSample: only the 8 known fields, finite numbers clamped, booleans coerced; junk dropped', () => {
  const hostile = {
    mediaTime: 12.5, paused: 'yes', rate: 99, duration: Infinity, live: 1, behindLiveEdge: -4, adShowing: 0, fullscreen: null,
    extra: 'x'.repeat(1e6), __proto__: { polluted: true }, t: 5,
  };
  assert.deepStrictEqual(Md.sanitizeSample(hostile), {
    mediaTime: 12.5, paused: true, rate: 16, duration: -1, live: true, behindLiveEdge: 0, adShowing: false, fullscreen: false,
  });
  assert.deepStrictEqual(Object.keys(Md.sanitizeSample(vod)), ['mediaTime', 'paused', 'rate', 'duration', 'live', 'behindLiveEdge', 'adShowing', 'fullscreen']);
  for (const bad of [null, 'x', [], {}, { mediaTime: NaN, rate: 1 }, { mediaTime: 1, rate: '1' }, { mediaTime: Infinity, rate: 1 }, { mediaTime: 1 }]) {
    assert.strictEqual(Md.sanitizeSample(bad), null, JSON.stringify(bad));
  }
  assert.strictEqual(Md.sanitizeSample({ mediaTime: -5, rate: 1 }).mediaTime, 0);
  assert.strictEqual(Md.sanitizeSample({ mediaTime: 1, rate: 1, duration: -1 }).duration, -1);
  assert.strictEqual(Md.sanitizeSample({ mediaTime: 1, rate: 1, duration: 'long' }).duration, -1);
});

function frame(id) {
  const f = { processId: 1, routingId: id, detached: false, sent: [], isDestroyed: () => false };
  f.send = (ch, v) => f.sent.push([ch, v]);
  return f;
}

function controller(now = () => 1000) {
  const out = [];
  const broadcasts = [];
  const mc = new Md.MediaController({ onSample: s => out.push(s), broadcast: (ch, v) => broadcasts.push([ch, v]), now });
  return { mc, out, broadcasts };
}

test('MediaController: forwards the best frame\'s samples, stamped with the host clock, sanitised', () => {
  let now = 1000;
  const { mc, out, broadcasts } = controller(() => now);
  const main = frame(1);
  mc.onFrameSample(main, { score: 7, sample: vod });
  assert.strictEqual(out.length, 0, 'nothing while sampling is off');
  mc.setSampling(true);
  assert.deepStrictEqual(broadcasts, [[CHANNELS.SAMPLING, true]]);
  mc.onFrameSample(main, { score: 7, sample: Object.assign({ evil: 1 }, vod) });
  assert.strictEqual(out.length, 1);
  assert.strictEqual(out[0].t, 1000);
  assert.strictEqual(out[0].evil, undefined);
  mc.fullscreen = true;
  now += 250;
  mc.onFrameSample(main, { score: 7, sample: vod });
  assert.strictEqual(out[1].fullscreen, true, 'HTML fullscreen seen by the host overrides the page sample');
  mc.onFrameSample(main, { score: 7, sample: { mediaTime: 'NaN', rate: 1 } });
  assert.strictEqual(out.length, 2, 'unusable samples are dropped');
});

test('MediaController: an embedded player in an iframe wins over a paused main-frame element; stale frames drop out', () => {
  let now = 1000;
  const { mc, out } = controller(() => now);
  mc.setSampling(true);
  const main = frame(1);
  const player = frame(2);
  mc.onFrameSample(main, { score: 2.5, sample: Object.assign({}, vod, { paused: true, mediaTime: 1 }) });
  mc.onFrameSample(player, { score: 7.1, sample: Object.assign({}, vod, { mediaTime: 77 }) });
  now += 250;
  mc.onFrameSample(main, { score: 2.5, sample: Object.assign({}, vod, { paused: true, mediaTime: 1 }) });
  assert.deepStrictEqual(out.map(s => s.mediaTime), [1, 77], 'the main frame is not forwarded while the player is better');
  now += Md.FRESH_MS + 1; // the player frame went quiet (navigated away)
  mc.onFrameSample(main, { score: 2.5, sample: Object.assign({}, vod, { paused: true, mediaTime: 2 }) });
  assert.strictEqual(out[out.length - 1].mediaTime, 2);
  mc.onFrameSample(main, { score: -1 });
  assert.strictEqual(mc.target, null);
});

test('MediaController.sync sends the plan to the frame that holds the media', () => {
  const { mc } = controller();
  mc.setSampling(true);
  assert.strictEqual(mc.sync({ targetMediaTime: 1, rate: 1.01, paused: false, seek: false }), false, 'no sample yet');
  const player = frame(9);
  mc.onFrameSample(player, { score: 7, sample: vod });
  assert.strictEqual(mc.sync({ targetMediaTime: 1, rate: 1.01, paused: false, seek: false }), true);
  assert.deepStrictEqual(player.sent, [[CHANNELS.SYNC, { rate: 1.01, seekTo: null, play: false, pause: false }]]);
  player.detached = true;
  assert.strictEqual(mc.sync({ targetMediaTime: 1, rate: 1.01, paused: false, seek: false }), false);
});
