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
