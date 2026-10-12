'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { EventEmitter } = require('events');
const { FrameInjector } = require('../src/inject');

function frame(name) {
  const f = new EventEmitter();
  f.name = name;
  f.runs = [];
  f.detached = false;
  f.isDestroyed = () => false;
  f.executeJavaScript = async code => { f.runs.push(code); return true; };
  return f;
}

function fakeWc() {
  const wc = new EventEmitter();
  wc.mainFrame = frame('main');
  wc.child = frame('child');
  wc.mainFrame.framesInSubtree = [wc.mainFrame, wc.child];
  wc.isolated = [];
  wc.isDestroyed = () => false;
  wc.executeJavaScriptInIsolatedWorld = async (world, scripts) => { wc.isolated.push([world, scripts[0].code]); return true; };
  return wc;
}

const flush = () => new Promise(r => setImmediate(r));

test('main frame runs in the isolated world, iframes in their main world; mainWorld scripts everywhere in main world', async () => {
  const wc = fakeWc();
  const inj = new FrameInjector(wc, { worldId: 1279, log: null });
  inj.set('volume', 'VOL');
  inj.set('guard', 'GUARD', true);
  await flush();
  assert.deepStrictEqual(wc.isolated, [[1279, 'VOL']]);
  assert.deepStrictEqual(wc.mainFrame.runs, ['GUARD']);
  assert.deepStrictEqual(wc.child.runs, ['VOL', 'GUARD']);
});

test('scripts are re-run on dom-ready, frame loads and new frames', async () => {
  const wc = fakeWc();
  const inj = new FrameInjector(wc, { worldId: 7, log: null });
  inj.set('volume', 'V1');
  await flush();
  inj.set('volume', 'V2'); // replaces, re-runs
  wc.emit('dom-ready');
  wc.emit('did-frame-finish-load', {}, true, 1, 1);
  const late = frame('late');
  wc.emit('frame-created', {}, { frame: late });
  late.emit('dom-ready');
  await flush();
  assert.deepStrictEqual(wc.isolated.map(x => x[1]), ['V1', 'V2', 'V2', 'V2']);
  assert.deepStrictEqual(late.runs, ['V2']);
});

test('destroyed or detached frames are skipped; failures are contained and logged a few times', async () => {
  const wc = fakeWc();
  wc.child.detached = true;
  const logged = [];
  const inj = new FrameInjector(wc, { worldId: 7, log: { info: m => logged.push(m) } });
  wc.executeJavaScriptInIsolatedWorld = async () => { throw new Error('frame gone'); };
  for (let i = 0; i < 15; i++) inj.set('volume', 'V' + i);
  await flush();
  assert.deepStrictEqual(wc.child.runs, []);
  assert.strictEqual(logged.length, 10);
  assert.match(logged[0], /frame gone/);
});
