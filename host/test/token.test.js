'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { PassThrough } = require('stream');
const { readToken, isValidToken, takeEnvToken } = require('../src/token');

const TOKEN = '0123456789abcdef0123456789ABCDEF';

test('token format: exactly 32 hex chars', () => {
  assert.ok(isValidToken(TOKEN));
  assert.ok(!isValidToken(TOKEN.slice(1)));
  assert.ok(!isValidToken(TOKEN + '0'));
  assert.ok(!isValidToken('g'.repeat(32)));
  assert.ok(!isValidToken(undefined));
});

test('reads the first line of stdin, even when split across chunks', async () => {
  const s = new PassThrough();
  const p = readToken(s, {}, 1000);
  s.write(TOKEN.slice(0, 10));
  s.write(TOKEN.slice(10) + '\r\nignored');
  assert.deepStrictEqual(await p, { token: TOKEN.toLowerCase(), source: 'stdin' });
});

test('stdin token wins over AKW_TOKEN', async () => {
  const s = new PassThrough();
  const p = readToken(s, { AKW_TOKEN: 'f'.repeat(32) }, 1000);
  s.end(TOKEN + '\n');
  assert.strictEqual((await p).source, 'stdin');
});

test('falls back to AKW_TOKEN when stdin closes empty', async () => {
  const s = new PassThrough();
  const p = readToken(s, { AKW_TOKEN: 'f'.repeat(32) }, 1000);
  s.end();
  assert.deepStrictEqual(await p, { token: 'f'.repeat(32), source: 'env' });
});

test('falls back to AKW_TOKEN when stdin never answers (timeout)', async () => {
  const s = new PassThrough();
  const t0 = Date.now();
  const r = await readToken(s, { AKW_TOKEN: 'e'.repeat(32) }, 100);
  assert.strictEqual(r.source, 'env');
  assert.ok(Date.now() - t0 < 1000);
});

test('falls back when stdin carries garbage, and resolves null when nothing is valid', async () => {
  const s = new PassThrough();
  const p = readToken(s, { AKW_TOKEN: 'not-a-token' }, 1000);
  s.end('hello\n');
  assert.strictEqual(await p, null);
});

test('no stream at all (no stdin handle) uses the environment', async () => {
  assert.strictEqual((await readToken(null, { AKW_TOKEN: TOKEN }, 100)).source, 'env');
  assert.strictEqual(await readToken(null, {}, 100), null);
});

test('an endless line is cut at 256 bytes', async () => {
  const s = new PassThrough();
  const p = readToken(s, {}, 1000);
  s.write('a'.repeat(300));
  assert.strictEqual(await p, null);
});

test('takeEnvToken copies AKW_TOKEN and removes it from the environment in one synchronous step', () => {
  const env = { AKW_TOKEN: TOKEN, PATH: '/bin' };
  assert.strictEqual(takeEnvToken(env), TOKEN);
  assert.deepStrictEqual(env, { PATH: '/bin' }, 'gone before anything else can read or inherit it');
  assert.strictEqual(takeEnvToken(env), null);
  assert.strictEqual(takeEnvToken(null), null);
});

test('main.js takes the environment token before it loads anything else', () => {
  const fs = require('fs');
  const path = require('path');
  const src = fs.readFileSync(path.join(__dirname, '..', 'src', 'main.js'), 'utf8');
  const code = src.split('\n').filter(l => l.trim() && !l.trim().startsWith('//'));
  assert.strictEqual(code[0], "'use strict';");
  assert.match(code[1], /takeEnvToken\(process\.env\)/, 'second statement of main.js');
  assert.ok(!/delete process\.env\.AKW_TOKEN/.test(src), 'no late delete after awaiting stdin');
});
