'use strict';
const test = require('node:test');
const assert = require('node:assert');
const M = require('../src/messages');

test('CREATE: defaults, clamps and enum names from Java (SHARED/OS) or the wire (shared/os)', () => {
  assert.deepStrictEqual(M.parseCreate({ width: 1280, height: 720, url: 'https://a.com' }),
    { width: 1280, height: 720, fps: 30, url: 'https://a.com', session: 'shared', audio: 'os', volume: 1 });
  assert.deepStrictEqual(M.parseCreate({ width: 99999, height: 2, fps: 500, url: '  https://a.com/  ', session: 'PRIVATE', audio: 'SPATIAL', volume: 3 }),
    { width: 4096, height: 16, fps: 60, url: 'https://a.com/', session: 'private', audio: 'spatial', volume: 1 });
  assert.strictEqual(M.parseCreate({ width: 640.6, height: 360, url: null }).width, 641);
  assert.strictEqual(M.parseCreate({ width: 640, height: 360 }).url, '');
});

test('CREATE: wrong types are rejected with a code', () => {
  for (const [bad, code] of [
    [null, 'not-object'], [[1], 'not-object'], [{ width: '640', height: 360 }, 'bad-width'], [{ width: 640, height: NaN }, 'bad-height'],
    [{ width: 640, height: 360, session: 'shared-ish' }, 'bad-session'], [{ width: 640, height: 360, audio: 'loud' }, 'bad-audio'],
    [{ width: 640, height: 360, url: 7 }, 'bad-url'], [{ width: 640, height: 360, url: 'x'.repeat(9000) }, 'url-too-long'],
    [{ width: 640, height: 360, volume: 'max' }, 'bad-volume'],
  ]) {
    assert.throws(() => M.parseCreate(bad), err => err instanceof M.MessageError && err.code === code, code);
  }
});

test('NAVIGATE: url or one of four actions', () => {
  assert.deepStrictEqual(M.parseNavigate({ url: 'https://x.com' }), { url: 'https://x.com' });
  assert.deepStrictEqual(M.parseNavigate({ action: 'BACK' }), { action: 'back' });
  for (const a of ['forward', 'reload', 'stop']) assert.deepStrictEqual(M.parseNavigate({ action: a }), { action: a });
  assert.throws(() => M.parseNavigate({ action: 'devtools' }), M.MessageError);
  assert.throws(() => M.parseNavigate({ url: '' }), M.MessageError);
  assert.throws(() => M.parseNavigate({}), M.MessageError);
});

test('RENDER, RESIZE, AUDIO, SYNC, PING', () => {
  assert.deepStrictEqual(M.parseRender({ fps: 0, painting: false }), { fps: 1, painting: false });
  assert.deepStrictEqual(M.parseRender({ fps: 15 }), { fps: 15, painting: true });
  assert.deepStrictEqual(M.parseResize({ width: 1920, height: 1080 }), { width: 1920, height: 1080 });
  assert.deepStrictEqual(M.parseAudio({ muted: true, volume: -2 }), { muted: true, volume: 0 });
  assert.deepStrictEqual(M.parseAudio({}), { muted: false, volume: 1 });
  assert.deepStrictEqual(M.parseSync({ targetMediaTime: 12.5, rate: 1.02, paused: false, seek: true }), { targetMediaTime: 12.5, rate: 1.02, paused: false, seek: true });
  assert.throws(() => M.parseSync({ rate: 1 }), M.MessageError);
  assert.deepStrictEqual(M.parsePing({ nonce: 5 }), { nonce: 5 });
  assert.deepStrictEqual(M.parsePing({ nonce: 'abc' }), { nonce: 'abc' });
  assert.deepStrictEqual(M.parsePing({ nonce: 'x'.repeat(65) }), { nonce: null });
  assert.deepStrictEqual(M.parsePing(null), { nonce: null });
});

test('POLICY: modes from Java enum names, hosts normalised (case, *., dots, punycode), junk dropped', () => {
  const p = M.parsePolicy({
    mode: 'ALLOWLIST',
    allow: ['YouTube.com', '*.twitch.tv', '.ytimg.com.', 'açaí.com.br', 'https://x.com/path', 'a.com:8080', '', 5, 'youtube.com'],
    block: null,
  });
  assert.deepStrictEqual(p, { mode: 'allowlist', allow: ['youtube.com', 'twitch.tv', 'ytimg.com', 'xn--aa-4iaz.com.br'], block: [] });
  // Fail closed: no policy is deny-all (an empty allow list), the same encoding as M2's policyMap(null).
  assert.deepStrictEqual(M.parsePolicy(null), { mode: 'allowlist', allow: [], block: [] });
  assert.deepStrictEqual(M.parsePolicy(undefined), M.denyAllPolicy());
  assert.deepStrictEqual(M.parseWelcome({ protoMajor: 1 }).policy, M.denyAllPolicy());
  assert.throws(() => M.parsePolicy({ mode: 'everything' }), M.MessageError);
  assert.throws(() => M.parsePolicy({ allow: 'youtube.com' }), M.MessageError);
  assert.strictEqual(M.parsePolicy({ block: new Array(5000).fill(0).map((_, i) => 'h' + i + '.com') }).block.length, M.LIMITS.MAX_POLICY_ENTRIES);
});

test('WELCOME: locale and user agent fall back to safe values', () => {
  const w = M.parseWelcome({ protoMajor: 1, policy: { mode: 'blocklist', block: ['a.com'] }, locale: 'pt-BR', userAgent: 'Mozilla/5.0 X' });
  assert.deepStrictEqual(w, { protoMajor: 1, policy: { mode: 'blocklist', allow: [], block: ['a.com'] }, locale: 'pt-BR', userAgent: 'Mozilla/5.0 X' });
  const bad = M.parseWelcome({ protoMajor: 1, locale: 'pt_BR;q=1', userAgent: 'evil\r\nHeader: 1' });
  assert.strictEqual(bad.locale, 'pt-BR');
  assert.strictEqual(bad.userAgent, null);
  assert.strictEqual(M.parseWelcome({ protoMajor: 1, userAgent: 'x'.repeat(600) }).userAgent, null);
  assert.throws(() => M.parseWelcome({ policy: null }), M.MessageError);
});

test('stock Chrome user agent uses the reduced format and the running Chromium major', () => {
  assert.strictEqual(M.stockChromeUserAgent('win32', '152.0.7977.130'),
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36');
  assert.match(M.stockChromeUserAgent('linux', '152.0.1.2'), /^Mozilla\/5\.0 \(X11; Linux x86_64\) .* Chrome\/152\.0\.0\.0 Safari\/537\.36$/);
  assert.match(M.stockChromeUserAgent('darwin', undefined), /Macintosh.*Chrome\/152\.0\.0\.0/);
  assert.ok(!M.stockChromeUserAgent('win32', '152').includes('Electron'));
});

test('Accept-Language from the locale', () => {
  assert.strictEqual(M.acceptLanguages('pt-BR'), 'pt-BR,pt,en-US,en');
  assert.strictEqual(M.acceptLanguages('en-US'), 'en-US,en');
  assert.strictEqual(M.acceptLanguages('es'), 'es,en-US,en');
});
