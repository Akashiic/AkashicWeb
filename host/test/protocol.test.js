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

test('encodeParts frames the concatenation without copying the parts', () => {
  const a = Buffer.from([1, 2, 3]);
  const b = Buffer.from([4, 5]);
  const parts = P.encodeParts(P.T.FRAME, 7, 1, [a, b]);
  assert.strictEqual(parts.length, 3);
  assert.strictEqual(parts[1], a);
  assert.strictEqual(parts[2], b);
  assert.strictEqual(parts[0].readUInt32BE(16), 5);
  const got = [];
  new P.Decoder(m => got.push(m)).feed(Buffer.concat(parts));
  assert.deepStrictEqual([...got[0].payload], [1, 2, 3, 4, 5]);
  assert.strictEqual(got[0].screenId, 7);
});

test('header refuses payloads above 16 MiB', () => {
  assert.throws(() => P.encodeParts(P.T.FRAME, 1, 0, [Buffer.alloc(P.MAX_PAYLOAD + 1)]), RangeError);
  assert.doesNotThrow(() => P.encodeParts(P.T.FRAME, 1, 0, [Buffer.alloc(P.MAX_PAYLOAD)]));
});

test('decoder rejects an announced length above the cap before buffering it', () => {
  const h = Buffer.alloc(P.HEADER_BYTES);
  h.writeUInt32BE(P.MAGIC, 0);
  h.writeUInt16BE(P.T.CREATE, 4);
  h.writeUInt32BE(P.MAX_PAYLOAD + 1, 16);
  assert.throws(() => new P.Decoder(() => {}).feed(h), /bad length/);
});

test('decoder handles several messages in one chunk and a split header', () => {
  const got = [];
  const d = new P.Decoder(m => got.push(m.type));
  const msgs = Buffer.concat([...P.encode(P.T.PING, 0, 1, { nonce: 1 }), ...P.encode(P.T.QUIT, 0, 2, null), ...P.encode(P.T.DESTROY, 3, 3, null)]);
  d.feed(msgs.subarray(0, 30));
  d.feed(msgs.subarray(30, 45));
  d.feed(msgs.subarray(45));
  assert.deepStrictEqual(got, [P.T.PING, P.T.QUIT, P.T.DESTROY]);
});

test('seq and screenId are unsigned 32-bit on the wire', () => {
  const [h] = P.encode(P.T.PONG, 0xffffffff, -1, null);
  assert.strictEqual(h.readUInt32BE(8), 0xffffffff);
  assert.strictEqual(h.readUInt32BE(12), 0xffffffff);
});

test('pcm header layout: i64 pts, u32 rate, u16 channels, u16 frames (big-endian)', () => {
  const b = P.pcmHeader(1234567890123, 48000, 2, 480);
  assert.strictEqual(b.length, P.PCM_HEADER_BYTES);
  assert.strictEqual(b.readBigInt64BE(0), 1234567890123n);
  assert.strictEqual(b.readUInt32BE(8), 48000);
  assert.strictEqual(b.readUInt16BE(12), 2);
  assert.strictEqual(b.readUInt16BE(14), 480);
});

test('decodeJson treats an empty payload as {} and throws on garbage', () => {
  assert.deepStrictEqual(P.decodeJson(Buffer.alloc(0)), {});
  assert.deepStrictEqual(P.decodeJson(Buffer.from('{"a":1}')), { a: 1 });
  assert.throws(() => P.decodeJson(Buffer.from('{oops')), SyntaxError);
});

test('typeName names known types and passes unknown numbers through', () => {
  assert.strictEqual(P.typeName(P.T.WELCOME), 'WELCOME');
  assert.strictEqual(P.typeName(999), '999');
});

test('message type numbers match docs/PROTOCOL.md and HostProtocol.java', () => {
  assert.deepStrictEqual(
    [P.T.HELLO, P.T.FRAME, P.T.PCM, P.T.STATE, P.T.MEDIA, P.T.EVENT, P.T.PONG, P.T.BYE],
    [1, 2, 3, 4, 5, 6, 7, 8]);
  assert.deepStrictEqual(
    [P.T.WELCOME, P.T.CREATE, P.T.DESTROY, P.T.NAVIGATE, P.T.INPUT, P.T.POLICY, P.T.AUDIO, P.T.RENDER, P.T.RESIZE, P.T.SYNC, P.T.PING, P.T.QUIT],
    [32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43]);
  assert.strictEqual(P.MAGIC, 0x414b5731);
  assert.strictEqual(P.HEADER_BYTES, 20);
  assert.strictEqual(P.MAX_PAYLOAD, 16 * 1024 * 1024);
});
