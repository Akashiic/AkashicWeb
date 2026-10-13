'use strict';
// Page-side scripts (volume, guard) run in a vm context with a fake DOM, through the exact string the
// host injects, so serialisation mistakes (a reference to module scope) fail here and not in a page.
const test = require('node:test');
const assert = require('node:assert');
const vm = require('vm');
const { volumeScript, AudioController } = require('../src/audio');
const { guardScript } = require('../src/guard');

function media(tag, volume = 1) {
  const el = { tagName: tag, isConnected: true, paused: false, sets: 0 };
  let v = volume;
  Object.defineProperty(el, 'volume', {
    get: () => v,
    set: x => {
      if (x < 0 || x > 1) throw new RangeError('IndexSizeError');
      el.sets++;
      v = x;
    },
  });
  return el;
}

function fakePage(initial = [], shadowEls = []) {
  const listeners = {};
  const page = { els: initial.slice(), observer: null, interval: null, listeners };
  const shadowRoot = { querySelectorAll: sel => (sel === 'video, audio' ? shadowEls : []) };
  page.document = {
    querySelectorAll: sel => (sel === 'video, audio' ? page.els : sel === '*' ? (shadowEls.length ? [{ shadowRoot }] : []) : []),
    addEventListener: (type, fn, capture) => { listeners[type] = { fn, capture }; },
  };
  page.ctx = vm.createContext({
    document: page.document,
    MutationObserver: class {
      constructor(cb) { page.observer = cb; }
      observe() {}
    },
    setInterval: fn => { page.interval = fn; return 1; },
    Math, Number, Object, Set, JSON,
  });
  page.run = code => vm.runInContext(code, page.ctx);
  return page;
}

test('volume script: applies the gain to existing, shadow-DOM and later elements', () => {
  const a = media('VIDEO');
  const b = media('AUDIO', 0.7);
  const hidden = media('VIDEO');
  const page = fakePage([a, b], [hidden]);
  assert.strictEqual(page.run(volumeScript('__k', 0.3)), 3);
  assert.deepStrictEqual([a.volume, b.volume, hidden.volume], [0.3, 0.3, 0.3]);
  // Added later: seen by the MutationObserver at once.
  const c = media('VIDEO');
  page.observer([{ addedNodes: [c] }]);
  assert.strictEqual(c.volume, 0.3);
  // Nested inside an added container.
  const d = media('AUDIO');
  page.observer([{ addedNodes: [{ querySelectorAll: () => [d] }] }]);
  assert.strictEqual(d.volume, 0.3);
});

test('volume script: the page resetting volume is undone, by event and by the 100 ms tick', () => {
  const a = media('VIDEO');
  const page = fakePage([a]);
  page.run(volumeScript('__k', 0.5));
  a.volume = 1; // the site's own player
  page.listeners.volumechange.fn({ target: a });
  assert.strictEqual(a.volume, 0.5);
  assert.strictEqual(page.listeners.volumechange.capture, true);
  a.volume = 0.9;
  page.interval();
  assert.strictEqual(a.volume, 0.5);
  const sets = a.sets;
  page.interval();
  assert.strictEqual(a.sets, sets, 'no write when already right (no volumechange loop)');
});

test('volume script: idempotent per document; a second run only changes the target, clamped', () => {
  const a = media('VIDEO');
  const page = fakePage([a]);
  page.run(volumeScript('__k', 0.5));
  const firstInterval = page.interval;
  page.run(volumeScript('__k', 7));
  assert.strictEqual(page.interval, firstInterval, 'no second timer');
  assert.strictEqual(a.volume, 1);
  page.run(volumeScript('__k', -1));
  assert.strictEqual(a.volume, 0);
  assert.strictEqual(Object.keys(page.ctx).includes('__k'), false, 'state is not enumerable on the page global');
});

test('volume script: detached paused elements are forgotten', () => {
  const a = media('AUDIO');
  const page = fakePage([a]);
  page.run(volumeScript('__k', 0.4));
  page.els = [];
  a.isConnected = false;
  a.paused = true;
  page.interval();
  assert.strictEqual(page.run(volumeScript('__k', 0.4)), 0);
});

test('AudioController: volume 0 or muted is an engine mute; otherwise unmuted with the page gain', () => {
  const calls = [];
  const wc = { isDestroyed: () => false, setAudioMuted: m => calls.push(['mute', m]) };
  const injector = { set: (name, code) => calls.push([name, code.includes('0.25')]) };
  const ac = new AudioController(wc, injector, '__k');
  ac.set(false, 0.25);
  ac.set(false, 0);
  ac.set(true, 0.8);
  assert.deepStrictEqual(calls, [['mute', false], ['volume', true], ['mute', true], ['volume', false], ['mute', true], ['volume', false]]);
});

function guardPage() {
  const listeners = [];
  const proto = { showPicker() { return 'native-picker'; } };
  const g = {
    print: function print() { return 'native-print'; },
    showOpenFilePicker: () => 'native-open',
    HTMLInputElement: { prototype: proto },
    addEventListener: (type, fn, capture) => listeners.push({ type, fn, capture }),
    DOMException: class extends Error { constructor(m, n) { super(m); this.name = n; } },
    Promise, Object, String,
  };
  const ctx = vm.createContext(g);
  return { ctx, proto, listeners, run: code => vm.runInContext(code, ctx) };
}

test('guard: print and file pickers neutralised, file-input clicks cancelled, idempotent', async () => {
  const p = guardPage();
  assert.strictEqual(p.run(guardScript('__g')), true);
  assert.strictEqual(p.run(guardScript('__g')), false, 'second run is a no-op');
  assert.strictEqual(p.run('print()'), undefined);
  await assert.rejects(p.run('showOpenFilePicker()'), err => err.name === 'AbortError');
  assert.strictEqual(p.proto.showPicker.call({ type: 'file' }), undefined);
  assert.strictEqual(p.proto.showPicker.call({ type: 'date' }), 'native-picker');
  assert.strictEqual(p.listeners.length, 1);
  assert.deepStrictEqual([p.listeners[0].type, p.listeners[0].capture], ['click', true]);
  let prevented = 0;
  p.listeners[0].fn({ target: { tagName: 'INPUT', type: 'FILE' }, preventDefault: () => prevented++ });
  p.listeners[0].fn({ target: { tagName: 'INPUT', type: 'text' }, preventDefault: () => prevented++ });
  p.listeners[0].fn({ target: { tagName: 'A' }, preventDefault: () => prevented++ });
  assert.strictEqual(prevented, 1);
  // The page cannot put the native print back.
  p.run('try { globalThis.print = 1 } catch (e) {}');
  assert.strictEqual(typeof p.run('print'), 'function');
});
