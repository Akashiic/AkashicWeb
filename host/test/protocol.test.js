'use strict';
const test = require('node:test');
const assert = require('node:assert');
const P = require('../src/protocol');

test('encode/decode round trip across chunk boundaries', () => {
  const got = [];
  const d = new P.Decoder(m => got.push(m));
  const [h1, b1] = P.encode(P.T.STATE, 5, 9, { url: 'https://example.com/ação' });
  const [h2, b2] = P.encode(P.T.BYE, 0, 10, null);
  const all = Buffer.concat([h1, b1, h2, b2]);
  for (let i = 0; i < all.length; i += 3) d.feed(all.subarray(i, i + 3));
  assert.strictEqual(got.length, 2);
  assert.strictEqual(got[0].type, P.T.STATE);
  assert.strictEqual(got[0].screenId, 5);
  assert.deepStrictEqual(JSON.parse(got[0].payload.toString('utf8')), { url: 'https://example.com/ação' });
  assert.strictEqual(got[1].payload.length, 0);
});

test('rejects bad magic', () => {
  const d = new P.Decoder(() => {});
  assert.throws(() => d.feed(Buffer.alloc(20)));
});

test('frame header layout matches Java', () => {
  const b = P.frameHeader(1280, 720, 10, 20, 2, 3);
  assert.strictEqual(b.readUInt16BE(0), 1280);
  assert.strictEqual(b.readUInt16BE(10), 3);
});
