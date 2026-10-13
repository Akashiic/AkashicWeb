'use strict';
// The screen preload (src/page/screen-preload.js) with a fake DOM. Ported from the wave-1 page-script
// tests (volume, guard) and extended for the preload design: volume from document start, sampling from
// a cached element set, SYNC, about:blank child frames. The real thing runs in the integration test.
const test = require('node:test');
const assert = require('node:assert');
const vm = require('vm');
const S = require('../src/page/screen-preload');

function media(tag, opts = {}) {
  const el = Object.assign({
    tagName: tag, nodeType: 1, isConnected: true, paused: false, ended: false, readyState: 4, sets: 0,
    currentTime: 10, duration: 100, playbackRate: 1, seekable: null, rect: { width: 640, height: 360 }, calls: [],
    root: null, ownerDocument: null,
  }, opts);
  let v = opts.volume == null ? 1 : opts.volume;
  Object.defineProperty(el, 'volume', {
    get: () => v,
    set: x => {
      if (x < 0 || x > 1) throw new RangeError('IndexSizeError');
      el.sets++;
      v = x;
    },
  });
  el.getBoundingClientRect = () => el.rect;
  el.closest = () => el.root;
  el.parentElement = null;
  el.pause = () => { el.calls.push('pause'); el.paused = true; };
  el.play = () => { el.calls.push('play'); el.paused = false; return Promise.resolve(); };
  return el;
}

function fakeDoc(els = [], extra = {}) {
  const doc = {
    els,
    shadowHosts: [],
    frames: [],
    listeners: {},
    fullscreenElement: null,
    location: { href: 'https://site.example/' },
    querySelectorAll(sel) {
      if (sel === 'video, audio') return this.els;
      if (sel === '*') return this.shadowHosts;
      if (sel === 'iframe, frame') return this.frames;
      return [];
    },
    addEventListener(type, fn, capture) { this.listeners[type] = { fn, capture }; },
  };
  return Object.assign(doc, extra);
}

/** An agent on a fake page; returns handles to drive it. */
function agentOn(doc, initState = { volume: 0.25, sampling: false }) {
  const sent = [];
  const handlers = {};
  const intervals = [];
  let observer = null;
  let mainWorld = null;
  let answer;
  const answered = new Promise(r => { answer = r; });
  const env = {
    window: { top: null },
    document: doc,
    ipc: {
      send: (ch, data) => sent.push([ch, data]),
      invoke: () => answered,
      on: (ch, fn) => { handlers[ch] = fn; },
    },
    runInMainWorld: (fn, args) => { mainWorld = { fn, args }; },
    setInterval: (fn, ms) => { intervals.push({ fn, ms }); return intervals.length; },
    clearInterval: id => { intervals[id - 1].cleared = true; },
    MutationObserver: class {
      constructor(cb) { observer = cb; this.cb = cb; }
      observe() {}
    },
    guardKey: '__g',
  };
  env.window.top = env.window;
  const agent = new S.FrameAgent(env);
  agent.start();
  return {
    agent, sent, handlers, intervals, env,
    observer: () => observer,
    mainWorld: () => mainWorld,
    init: async () => { answer(initState); await new Promise(r => setImmediate(r)); },
    tick: () => intervals.filter(i => i.ms === S.ENFORCE_MS && !i.cleared).forEach(i => i.fn()),
  };
}

test('volume: silent from document start until the host answers, then the host gain, before any load', async () => {
  const a = media('VIDEO');
  const page = agentOn(fakeDoc([a]), { volume: 0.1, sampling: false });
  assert.strictEqual(a.volume, 0, 'never the page default 1 while the gain is unknown');
  await page.init();
  assert.strictEqual(a.volume, 0.1);
});

test('volume: existing, shadow-DOM and later elements follow the gain (MutationObserver)', async () => {
  const a = media('VIDEO');
  const b = media('AUDIO', { volume: 0.7 });
  const hidden = media('VIDEO');
  const doc = fakeDoc([a, b]);
  doc.shadowHosts = [{ shadowRoot: { querySelectorAll: sel => (sel === 'video, audio' ? [hidden] : []) } }];
  const page = agentOn(doc, { volume: 0.3 });
  await page.init();
  assert.deepStrictEqual([a.volume, b.volume, hidden.volume], [0.3, 0.3, 0.3]);
  const c = media('VIDEO');
  page.observer()([{ addedNodes: [c] }]);
  assert.strictEqual(c.volume, 0.3);
  const d = media('AUDIO');
  page.observer()([{ addedNodes: [{ nodeType: 1, tagName: 'DIV', querySelectorAll: sel => (sel === 'video, audio' ? [d] : []) }] }]);
  assert.strictEqual(d.volume, 0.3);
});

test('volume: AUDIO updates (akw-volume) apply at once; the page resetting volume is undone by event and tick', async () => {
  const a = media('VIDEO');
  const doc = fakeDoc([a]);
  const page = agentOn(doc, { volume: 0.5 });
  await page.init();
  page.handlers[S.CHANNELS.VOLUME]({}, 0.2);
  assert.strictEqual(a.volume, 0.2);
  a.volume = 1; // the site's own player
  doc.listeners.volumechange.fn({ target: a });
  assert.strictEqual(a.volume, 0.2);
  assert.strictEqual(doc.listeners.volumechange.capture, true);
  a.volume = 0.9;
  page.tick();
  assert.strictEqual(a.volume, 0.2);
  const sets = a.sets;
  page.tick();
  assert.strictEqual(a.sets, sets, 'no write when already right (no volumechange loop)');
  page.handlers[S.CHANNELS.VOLUME]({}, 7);
  assert.strictEqual(a.volume, 1, 'clamped');
  page.handlers[S.CHANNELS.VOLUME]({}, 'junk');
  assert.strictEqual(a.volume, 0, 'junk is silence');
});

test('volume: detached paused elements are forgotten; a rescan every few seconds finds missed ones', async () => {
  const a = media('AUDIO');
  const doc = fakeDoc([a]);
  const page = agentOn(doc, { volume: 0.4 });
  await page.init();
  doc.els = [];
  a.isConnected = false;
  a.paused = true;
  page.tick();
  assert.strictEqual(page.agent.els.size, 0);
  const late = media('VIDEO'); // added without any event or mutation record reaching the agent
  doc.els = [late];
  for (let i = 0; i < S.RESCAN_EVERY; i++) page.tick();
  assert.strictEqual(late.volume, 0.4);
});

test('about:blank child frames (no preload of their own) are watched by the parent agent', async () => {
  const inner = media('AUDIO');
  const childDoc = fakeDoc([inner], { location: { href: 'about:blank' } });
  const clickGuards = [];
  childDoc.defaultView = { addEventListener: (type, fn, capture) => clickGuards.push({ type, fn, capture }) };
  const iframe = (attrs, docGetter) => ({
    nodeType: 1, tagName: 'IFRAME', querySelectorAll: () => [],
    hasAttribute: n => n in attrs, getAttribute: n => (n in attrs ? attrs[n] : null),
    get contentDocument() { touched.push(attrs); return docGetter(); },
  });
  const touched = [];
  const fresh = iframe({}, () => childDoc);
  const crossOrigin = iframe({}, () => null);
  const ownAgent = iframe({}, () => fakeDoc([media('VIDEO')], { location: { href: 'https://site.example/embed' } }));
  // Frames whose first navigation may still be pending must not even be touched (it would stop
  // Electron from running their own preload).
  const withSrc = iframe({ src: 'https://player.example/embed/1' }, () => fakeDoc([]));
  const withSrcdoc = iframe({ srcdoc: '<video></video>' }, () => fakeDoc([]));
  const doc = fakeDoc([]);
  const page = agentOn(doc, { volume: 0.15 });
  await page.init();
  page.observer()([{ addedNodes: [fresh, crossOrigin, ownAgent, withSrc, withSrcdoc] }]);
  assert.strictEqual(inner.volume, 0.15, 'media in the fresh iframe follows the gain');
  assert.strictEqual(page.agent.docs.size, 2, 'own document + the about:blank child only');
  assert.ok(!touched.some(a => 'src' in a || 'srcdoc' in a), 'frames with a src/srcdoc are never touched');
  assert.strictEqual(clickGuards.length, 1);
  let prevented = 0;
  clickGuards[0].fn({ target: { tagName: 'INPUT', type: 'file' }, preventDefault: () => prevented++ });
  assert.strictEqual(prevented, 1, 'file-input clicks cancelled in the child too');
  // The iframe is removed: its document loses its window and is forgotten at the next rescan.
  childDoc.defaultView = null;
  for (let i = 0; i < S.RESCAN_EVERY; i++) page.tick();
  assert.strictEqual(page.agent.docs.size, 1);
});

test('MEDIA: off until the host turns sampling on; then 4 Hz samples of the best element; none -> one {score:-1}', async () => {
  const pausedAudio = media('AUDIO', { paused: true, currentTime: 3 });
  const video = media('VIDEO', { currentTime: 42.5, playbackRate: 1.02 });
  const doc = fakeDoc([pausedAudio, video]);
  const page = agentOn(doc, { volume: 1, sampling: false });
  await page.init();
  assert.ok(!page.intervals.some(i => i.ms === S.SAMPLE_MS), 'no sampling timer while off');
  page.handlers[S.CHANNELS.SAMPLING]({}, true);
  const timer = page.intervals.find(i => i.ms === S.SAMPLE_MS);
  assert.ok(timer, '250 ms sampling timer');
  timer.fn();
  const [ch, msg] = page.sent.pop();
  assert.strictEqual(ch, S.CHANNELS.MEDIA);
  assert.strictEqual(msg.sample.mediaTime, 42.5, 'the playing video, not the paused audio');
  assert.ok(msg.score > 6);
  doc.els = [];
  video.isConnected = false;
  pausedAudio.isConnected = false;
  page.agent.els.clear();
  timer.fn();
  timer.fn();
  assert.deepStrictEqual(page.sent.filter(s => s[0] === S.CHANNELS.MEDIA).slice(-1), [[S.CHANNELS.MEDIA, { score: -1 }]]);
  assert.strictEqual(page.sent.filter(s => s[1] && s[1].score === -1).length, 1, 'reported once');
  page.handlers[S.CHANNELS.SAMPLING]({}, false);
  assert.ok(timer.cleared);
});

test('SYNC: the plan is applied to the element of the last sample', async () => {
  const v = media('VIDEO', { paused: true });
  const page = agentOn(fakeDoc([v]), { volume: 1, sampling: true });
  await page.init();
  page.intervals.find(i => i.ms === S.SAMPLE_MS).fn();
  page.handlers[S.CHANNELS.SYNC]({}, { rate: 1.04, seekTo: 42, play: true, pause: false });
  assert.deepStrictEqual([v.currentTime, v.playbackRate, v.paused, v.calls], [42, 1.04, false, ['play']]);
});

test('pickMedia prefers playing video, then any video, then larger', () => {
  const c = (o) => Object.assign({ connected: true, paused: true, ended: false, isVideo: true, readyState: 4, area: 1000 }, o);
  assert.strictEqual(S.pickMedia([]), -1);
  assert.strictEqual(S.pickMedia([c({ area: 9e5 }), c({ paused: false, area: 100 })]), 1);
  assert.strictEqual(S.pickMedia([c({ isVideo: false, paused: false }), c({ paused: false })]), 1);
  assert.strictEqual(S.pickMedia([c({ area: 10 }), c({ area: 5000 })]), 1);
  assert.strictEqual(S.pickMedia([c({ connected: false, paused: false }), c({})]), 1);
  assert.strictEqual(S.pickMedia([c({ paused: false, ended: true }), c({ isVideo: false, paused: false })]), 1);
});

test('readSample: VOD element; live edge distance; Infinity is JSON-safe', () => {
  const s = S.readSample(media('VIDEO', { currentTime: 12.5, playbackRate: 1.03 }), fakeDoc());
  assert.deepStrictEqual(s, { mediaTime: 12.5, paused: false, rate: 1.03, duration: 100, live: false, behindLiveEdge: 0, adShowing: false, fullscreen: false });
  const live = S.readSample(media('VIDEO', { duration: Infinity, currentTime: 500, seekable: { length: 1, end: () => 503.25 } }), fakeDoc());
  assert.deepStrictEqual([live.live, live.duration, live.behindLiveEdge], [true, -1, 3.25]);
  assert.doesNotThrow(() => JSON.parse(JSON.stringify(live)));
});

test('readSample: ad and live markers are looked up inside the player only, not in the whole page', () => {
  const queried = [];
  const player = {
    matches: sel => sel === S.AD_MARKERS && player.cls.includes('ad-showing'),
    querySelector: sel => { queried.push(sel); return sel === S.LIVE_MARKERS ? {} : null; },
    cls: 'html5-video-player ad-showing',
  };
  const el = media('VIDEO', { root: player, seekable: { length: 1, end: () => 30 }, currentTime: 29 });
  const doc = fakeDoc([], { fullscreenElement: {}, querySelector: () => { throw new Error('whole-document query'); } });
  const s = S.readSample(el, doc);
  assert.deepStrictEqual([s.live, s.adShowing, s.fullscreen, s.behindLiveEdge], [true, true, true, 1]);
  assert.ok(queried.length > 0);
});

test('applyPlan leaves an element alone when nothing changes; ignores junk', () => {
  const el = media('VIDEO', { playbackRate: 1 });
  S.applyPlan(el, { rate: 1, seekTo: null, play: false, pause: false });
  assert.deepStrictEqual([el.currentTime, el.calls], [10, []]);
  S.applyPlan(el, { rate: 1, seekTo: null, play: false, pause: true });
  assert.deepStrictEqual(el.calls, ['pause']);
  assert.strictEqual(S.applyPlan(el, null), false);
});

function guardPage() {
  const listeners = [];
  const proto = { showPicker() { return 'native-picker'; } };
  class ServiceWorkerContainer {}
  ServiceWorkerContainer.prototype.register = () => Promise.resolve('registered');
  const g = {
    print: function print() { return 'native-print'; },
    showOpenFilePicker: () => 'native-open',
    HTMLInputElement: { prototype: proto },
    ServiceWorkerContainer,
    navigator: { serviceWorker: new ServiceWorkerContainer() },
    addEventListener: (type, fn, capture) => listeners.push({ type, fn, capture }),
    DOMException: class extends Error { constructor(m, n) { super(m); this.name = n; } },
    Promise, Object, String,
  };
  const ctx = vm.createContext(g);
  return { ctx, proto, listeners, run: code => vm.runInContext(code, ctx) };
}

test('guard: installed in the page main world at document start, through runInMainWorld', () => {
  const page = agentOn(fakeDoc([]));
  assert.strictEqual(page.mainWorld().fn, S.akwGuard);
  assert.deepStrictEqual(page.mainWorld().args, ['__g']);
});

test('guard (serialised as executeInMainWorld does): print and file pickers neutralised, file-input clicks cancelled, SW off, idempotent', async () => {
  const p = guardPage();
  const code = '(' + S.akwGuard.toString() + ')';
  assert.strictEqual(p.run(code + '("__g")'), true);
  assert.strictEqual(p.run(code + '("__g")'), false, 'second run is a no-op');
  assert.strictEqual(p.run('print()'), undefined);
  await assert.rejects(p.run('showOpenFilePicker()'), err => err.name === 'AbortError');
  await assert.rejects(p.run('navigator.serviceWorker.register("/sw.js")'), err => err.name === 'SecurityError');
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

test('the preload file starts itself only inside an Electron renderer', () => {
  // Required under plain Node (as here) it only exports; no electron module is needed.
  assert.strictEqual(typeof S.FrameAgent, 'function');
  assert.strictEqual(process.type, undefined);
});
