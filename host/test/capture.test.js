'use strict';
// SPATIAL capture start-up (capture.js) with fake windows: the IPC/JS calls it awaits have timeouts,
// so a capture page that never answers ends in capture-error instead of a screen waiting (engine-
// muted) forever. The real capture runs in the integration test.
const test = require('node:test');
const assert = require('node:assert');
const { SpatialCapture, CaptureRegistry } = require('../src/capture');
const { FakeWindow, FakeWebContents } = require('./fakes');

function capture(setup) {
  const events = [];
  const registry = new CaptureRegistry();
  let win;
  const c = new SpatialCapture({
    screenWc: new FakeWebContents(),
    session: {},
    registry,
    log: { info() {}, warn() {} },
    onPcm: () => {},
    onStarted: info => events.push(['started', info]),
    onError: detail => events.push(['error', detail]),
    loadTimeoutMs: 30,
    startTimeoutMs: 30,
    createWindow: opts => {
      win = new FakeWindow(opts);
      setup(win);
      return win;
    },
  });
  return { c, events, registry, win: () => win };
}

test('capture: a capture page that never loads ends in capture-error after the timeout, window destroyed', async () => {
  const { c, events, registry, win } = capture(w => { w.loadFileResult = new Promise(() => {}); });
  assert.strictEqual(await c.start(), null);
  assert.deepStrictEqual(events, [['error', 'capture start failed: timeout']]);
  assert.strictEqual(win().destroyed, true);
  assert.strictEqual(registry.isCapturePage(win().webContents.id), false);
});

test('capture: getDisplayMedia that never resolves ends in capture-error after the timeout', async () => {
  const { c, events } = capture(w => { w.webContents.executeJavaScript = () => new Promise(() => {}); });
  assert.strictEqual(await c.start(), null);
  assert.deepStrictEqual(events, [['error', 'capture start failed: timeout']]);
});

test('capture: a started capture reports onStarted with the page answer', async () => {
  const { c, events, registry, win } = capture(w => { w.webContents.executeJavaScript = () => Promise.resolve({ sampleRate: 48000, channels: 2 }); });
  assert.deepStrictEqual(await c.start(), { sampleRate: 48000, channels: 2 });
  assert.deepStrictEqual(events, [['started', { sampleRate: 48000, channels: 2 }]]);
  assert.strictEqual(registry.isCapturePage(win().webContents.id), true);
  c.stop();
  assert.strictEqual(registry.isCapturePage(win().webContents.id), false);
});
