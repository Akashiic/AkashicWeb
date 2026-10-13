'use strict';
const test = require('node:test');
const assert = require('node:assert');
const P = require('../src/protocol');
const { Router } = require('../src/router');
const { EventLimiter } = require('../src/limiter');
const { redactUrl, createLogger } = require('../src/log');

function msg(type, screenId, payload) {
  const body = payload == null ? Buffer.alloc(0) : Buffer.isBuffer(payload) ? payload : Buffer.from(JSON.stringify(payload));
  return { type, flags: 0, screenId, seq: 0, payload: body };
}

function setup() {
  const calls = [];
  const warnings = [];
  const screen = {
    navigate: c => calls.push(['navigate', c]),
    input: e => calls.push(['input', e]),
    setAudio: a => calls.push(['audio', a]),
    render: r => calls.push(['render', r]),
    resize: r => calls.push(['resize', r]),
    sync: s => { calls.push(['sync', s]); return Promise.resolve(true); },
  };
  const screens = {
    known: new Set(),
    create: (id, spec) => { screens.known.add(id); calls.push(['create', id, spec.url]); },
    destroy: id => { screens.known.delete(id); calls.push(['destroy', id]); },
    get: id => (screens.known.has(id) ? screen : null),
  };
  const router = new Router({
    screens,
    log: { warn: w => warnings.push(w), info() {}, error() {} },
    onWelcome: w => calls.push(['welcome', w.protoMajor, w.policy.mode]),
    onBadWelcome: e => calls.push(['bad-welcome', e.code || e.name]),
    onPolicy: p => calls.push(['policy', p.mode]),
    onBadPolicy: e => calls.push(['bad-policy', e.code || e.name]),
    onPing: (id, nonce) => calls.push(['ping', id, nonce]),
    onQuit: () => calls.push(['quit']),
  });
  return { router, calls, warnings };
}

test('only WELCOME, PING and QUIT are accepted before WELCOME', () => {
  const { router, calls, warnings } = setup();
  router.route(msg(P.T.CREATE, 1, { width: 640, height: 360, url: 'https://a.com' }));
  router.route(msg(P.T.POLICY, 0, { mode: 'allowlist' }));
  router.route(msg(P.T.PING, 0, { nonce: 9 }));
  assert.deepStrictEqual(calls, [['ping', 0, 9]]);
  assert.strictEqual(warnings.length, 2);
  router.route(msg(P.T.WELCOME, 0, { protoMajor: 1, policy: { mode: 'blocklist' } }));
  router.route(msg(P.T.WELCOME, 0, { protoMajor: 1 }));
  assert.deepStrictEqual(calls.slice(1), [['welcome', 1, 'blocklist']]);
  assert.match(warnings[2], /duplicate-welcome/);
});

test('a malformed WELCOME fails the handshake and never opens the door to screen messages', () => {
  for (const bad of [
    { protoMajor: 1, policy: { mode: 'ALLOW_LIST', allow: ['youtube.com'] }, locale: 'pt-BR' }, // unknown mode
    { protoMajor: 1, policy: { mode: 'allowlist', allow: 'youtube.com' } }, // allow not a list
    { policy: null }, // no protoMajor
  ]) {
    const { router, calls } = setup();
    router.route(msg(P.T.WELCOME, 0, bad));
    assert.strictEqual(router.welcomed, false, JSON.stringify(bad));
    router.route(msg(P.T.CREATE, 7, { width: 640, height: 360, url: 'https://evil.example/' }));
    assert.deepStrictEqual(calls.map(c => c[0]), ['bad-welcome'], JSON.stringify(bad));
  }
  const { router, calls } = setup();
  router.route(msg(P.T.WELCOME, 0, Buffer.from('{not json')));
  assert.deepStrictEqual(calls, [['bad-welcome', 'SyntaxError']]);
});

test('a WELCOME without a policy means deny-all (an empty allow list), like M2 encodes null', () => {
  const { router, calls } = setup();
  router.route(msg(P.T.WELCOME, 0, { protoMajor: 1, policy: null }));
  assert.deepStrictEqual(calls, [['welcome', 1, 'allowlist']]);
});

test('a malformed POLICY is reported so the host can switch to deny-all; a good one still applies', () => {
  const { router, calls } = setup();
  router.route(msg(P.T.WELCOME, 0, { protoMajor: 1, policy: { mode: 'blocklist' } }));
  router.route(msg(P.T.POLICY, 0, { mode: 'everything' }));
  router.route(msg(P.T.POLICY, 0, Buffer.from('[')));
  router.route(msg(P.T.POLICY, 0, { mode: 'allowlist', allow: ['youtube.com'] }));
  assert.deepStrictEqual(calls, [['welcome', 1, 'blocklist'], ['bad-policy', 'bad-mode'], ['bad-policy', 'SyntaxError'], ['policy', 'allowlist']]);
});

test('screen messages reach the right screen with validated payloads', () => {
  const { router, calls } = setup();
  router.route(msg(P.T.WELCOME, 0, { protoMajor: 1 }));
  router.route(msg(P.T.CREATE, 4, { width: 640, height: 360, url: 'https://a.com' }));
  router.route(msg(P.T.NAVIGATE, 4, { action: 'reload' }));
  router.route(msg(P.T.INPUT, 4, { type: 'char', text: 'é' }));
  router.route(msg(P.T.AUDIO, 4, { muted: true, volume: 0.5 }));
  router.route(msg(P.T.RENDER, 4, { fps: 10, painting: false }));
  router.route(msg(P.T.RESIZE, 4, { width: 320, height: 240 }));
  router.route(msg(P.T.SYNC, 4, { targetMediaTime: 3, rate: 1.01, paused: false, seek: false }));
  router.route(msg(P.T.POLICY, 0, { mode: 'allowlist', allow: ['youtube.com'] }));
  router.route(msg(P.T.DESTROY, 4, null));
  router.route(msg(P.T.QUIT, 0, null));
  assert.deepStrictEqual(calls.map(c => c[0]), ['welcome', 'create', 'navigate', 'input', 'audio', 'render', 'resize', 'sync', 'policy', 'destroy', 'quit']);
  assert.deepStrictEqual(calls[2][1], { action: 'reload' });
  assert.deepStrictEqual(calls[4][1], { muted: true, volume: 0.5 });
});

test('unknown screens, unknown types, malformed JSON and screen 0 are dropped without throwing', () => {
  const { router, calls, warnings } = setup();
  router.route(msg(P.T.WELCOME, 0, { protoMajor: 1 }));
  router.route(msg(P.T.NAVIGATE, 77, { url: 'https://a.com' }));
  router.route(msg(200, 1, { future: true }));
  router.route(msg(P.T.CREATE, 1, Buffer.from('{not json')));
  router.route(msg(P.T.CREATE, 0, { width: 640, height: 360 }));
  router.route(msg(P.T.CREATE, 2, { width: 'big', height: 360 }));
  assert.deepStrictEqual(calls.map(c => c[0]), ['welcome']);
  assert.strictEqual(warnings.length, 4, warnings.join('\n'));
  assert.match(warnings[0], /unknown-screen/);
  assert.match(warnings[3], /bad-width/);
});

test('a throwing screen method is contained', () => {
  const { router, warnings } = setup();
  router.route(msg(P.T.WELCOME, 0, { protoMajor: 1 }));
  router.d.screens.create = () => { throw new Error('BrowserWindow failed'); };
  assert.doesNotThrow(() => router.route(msg(P.T.CREATE, 3, { width: 640, height: 360 })));
  assert.match(warnings.pop(), /BrowserWindow failed/);
});

test('rejection logging is rate limited after the first 20', () => {
  const { router, warnings } = setup();
  for (let i = 0; i < 250; i++) router.route(msg(P.T.CREATE, 1, { width: 1, height: 1 }));
  assert.strictEqual(router.rejected, 250);
  assert.strictEqual(warnings.length, 22, 'first 20, then the 100th and 200th');
});

test('EventLimiter: burst then steady rate per screen and per noisy kind', () => {
  let now = 0;
  const lim = new EventLimiter(3, 2, () => now);
  assert.deepStrictEqual([1, 2, 3, 4].map(() => lim.allow(1, 'blocked-url')), [true, true, true, false]);
  assert.strictEqual(lim.allow(2, 'blocked-url'), true, 'screens are independent');
  assert.strictEqual(lim.allow(1, 'permission-denied'), true, 'kinds have their own bucket');
  now += 500; // +1 token
  assert.deepStrictEqual([lim.allow(1, 'blocked-url'), lim.allow(1, 'blocked-url')], [true, false]);
  now += 10000;
  assert.deepStrictEqual([1, 2, 3, 4].map(() => lim.allow(1, 'blocked-url')), [true, true, true, false], 'never more than the burst');
  assert.strictEqual(lim.dropped, 3);
  assert.deepStrictEqual(lim.takeSuppressed(), [{ screenId: 1, kind: 'blocked-url', count: 3 }]);
  assert.deepStrictEqual(lim.takeSuppressed(), [], 'counts reset once taken');
  lim.forget(1);
  assert.strictEqual(lim.allow(1, 'blocked-url'), true);
});

test('EventLimiter: failures the game must see are never dropped, whatever floods before them', () => {
  const lim = new EventLimiter(2, 1, () => 0);
  for (let i = 0; i < 100; i++) lim.allow(1, 'blocked-url');
  for (const kind of ['renderer-gone', 'capture-error', 'unresponsive', 'responsive', 'load-error', 'screen-limit', 'policy-error']) {
    for (let i = 0; i < 50; i++) assert.strictEqual(lim.allow(1, kind), true, kind);
  }
  assert.strictEqual(lim.takeSuppressed()[0].count, 98);
});

test('redactUrl keeps scheme, host and path only', () => {
  assert.strictEqual(redactUrl('https://accounts.example.com/o/oauth2?code=SECRET#frag'), 'https://accounts.example.com/o/oauth2?…');
  assert.strictEqual(redactUrl('https://a.com/'), 'https://a.com/');
  assert.strictEqual(redactUrl('data:text/html,<script>'), 'data:');
  assert.strictEqual(redactUrl('nonsense'), '<unparseable url>');
  assert.strictEqual(redactUrl('https://a.com/' + 'p'.repeat(100)).length < 100, true);
});

test('file logger appends lines and rotates past the size cap', () => {
  const fs = require('fs');
  const os = require('os');
  const path = require('path');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'akw-log-'));
  const file = path.join(dir, 'sub', 'host.log');
  const log = createLogger({ file, maxBytes: 200 });
  for (let i = 0; i < 10; i++) log.info('line ' + i, { i });
  log.close();
  const current = fs.readFileSync(file, 'utf8');
  const rotated = fs.readFileSync(file + '.1', 'utf8');
  assert.match(rotated + current, /INFO line 9 \{"i":9\}/);
  assert.ok(Buffer.byteLength(rotated) < 300 && Buffer.byteLength(current) < 300, 'each file stays near the cap');
  const errLog = createLogger({ file: path.join(dir, 'err.log') });
  errLog.warn(new Error('boom'));
  errLog.close();
  assert.match(fs.readFileSync(path.join(dir, 'err.log'), 'utf8'), /WARN Error: boom\n {4}at /);
  fs.rmSync(dir, { recursive: true, force: true });
});

test('logger without a file (or with an unwritable one) does not throw', () => {
  const fs = require('fs');
  const os = require('os');
  const path = require('path');
  const log = createLogger({});
  assert.doesNotThrow(() => log.error('x'));
  // A regular file where the log directory should be: mkdir fails with ENOTDIR.
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'akw-log-'));
  fs.writeFileSync(path.join(dir, 'not-a-dir'), '');
  const bad = createLogger({ file: path.join(dir, 'not-a-dir', 'host.log') });
  assert.doesNotThrow(() => bad.info('x'));
  bad.close();
  fs.rmSync(dir, { recursive: true, force: true });
});
