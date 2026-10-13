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
