'use strict';
// Link over a real loopback socket pair: framing, per-screen sequence numbers, backpressure,
// protocol errors and the queue cap.
const test = require('node:test');
const assert = require('node:assert');
const net = require('net');
const P = require('../src/protocol');
const { Link } = require('../src/link');

function pair() {
  return new Promise(resolve => {
    const server = net.createServer(game => {
      const host = client;
      resolve({ server, game, host });
    });
    let client;
    server.listen(0, '127.0.0.1', () => { client = net.connect(server.address().port, '127.0.0.1'); });
  });
}

function collect(socket) {
  const got = [];
  const dec = new P.Decoder(m => got.push(m));
  socket.on('data', d => dec.feed(d));
  return got;
}

const until = async (pred, ms = 2000) => {
  const t0 = Date.now();
  while (!pred()) {
    if (Date.now() - t0 > ms) throw new Error('timeout');
    await new Promise(r => setTimeout(r, 5));
  }
};

test('sends JSON, Buffer and multi-part payloads with per-screen sequence numbers', async () => {
  const { server, game, host } = await pair();
  const link = new Link(host, { onMessage() {}, onClose() {}, onProtocolError() {} });
  const got = collect(game);
  link.send(P.T.STATE, 3, { url: 'https://a/' });
  link.send(P.T.STATE, 3, { url: 'https://b/' });
  link.send(P.T.HELLO, 0, { token: 'x' });
  link.send(P.T.FRAME, 3, [P.frameHeader(2, 1, 0, 0, 2, 1), Buffer.alloc(8, 7)]);
  link.send(P.T.BYE, 0, null);
  await until(() => got.length === 5);
  assert.deepStrictEqual(got.map(m => [m.type, m.screenId, m.seq]), [[4, 3, 0], [4, 3, 1], [1, 0, 0], [2, 3, 2], [8, 0, 1]]);
  assert.strictEqual(got[3].payload.length, 20);
  assert.strictEqual(link.bytesOut, 5 * 20 + got.reduce((a, m) => a + m.payload.length, 0));
  host.destroy();
  game.destroy();
  server.close();
});

test('delivers game messages and reports EOF once', async () => {
  const { server, game, host } = await pair();
  const msgs = [];
  const closes = [];
  const link = new Link(host, { onMessage: m => msgs.push(m.type), onClose: r => closes.push(r), onProtocolError() {} });
  for (const part of P.encode(P.T.PING, 0, 0, { nonce: 1 })) game.write(part);
  for (const part of P.encode(P.T.QUIT, 0, 1, null)) game.write(part);
  await until(() => msgs.length === 2);
  game.end();
  await until(() => closes.length === 1);
  assert.deepStrictEqual(msgs, [P.T.PING, P.T.QUIT]);
  assert.deepStrictEqual(closes, ['eof']);
  assert.strictEqual(link.closed, true);
  server.close();
});

test('bad framing from the game raises a protocol error', async () => {
  const { server, game, host } = await pair();
  const errors = [];
  const link = new Link(host, { onMessage() {}, onClose() {}, onProtocolError: e => errors.push(e.message) });
  game.write(Buffer.from('GET / HTTP/1.1\r\nHost: x\r\n\r\n'));
  await until(() => errors.length === 1);
  assert.match(errors[0], /bad magic/);
  link.end();
  game.destroy();
  server.close();
});

test('reports blocked while the game is not reading, and drain once it reads again', async () => {
  const { server, game, host } = await pair();
  let drains = 0;
  const link = new Link(host, { onMessage() {}, onClose() {}, onProtocolError() {}, onDrain: () => drains++ });
  game.pause();
  const big = Buffer.alloc(4 * 1024 * 1024);
  let ok = true;
  for (let i = 0; i < 8 && ok; i++) ok = link.send(P.T.FRAME, 1, [big]);
  assert.strictEqual(ok, false);
  assert.strictEqual(link.blocked, true);
  game.resume();
  await until(() => drains > 0, 5000);
  assert.strictEqual(link.blocked, false);
  host.destroy();
  game.destroy();
  server.close();
});

test('drops the connection when the game stops reading past the queue cap', async () => {
  const { server, game, host } = await pair();
  const closes = [];
  const link = new Link(host, { onMessage() {}, onClose: r => closes.push(r), onProtocolError() {} }, { maxQueuedBytes: 2 * 1024 * 1024 });
  game.pause();
  const big = Buffer.alloc(1024 * 1024);
  let sent = 0;
  while (!link.closed && !host.destroyed && sent < 200) {
    link.send(P.T.PCM, 1, [big]);
    sent++;
  }
  await until(() => closes.length === 1);
  assert.deepStrictEqual(closes, ['game-not-reading']);
  assert.strictEqual(link.send(P.T.STATE, 1, {}), false, 'sending after close is a no-op');
  game.destroy();
  server.close();
});

test('end() calls back once the socket is finished, also when already closed', async () => {
  const { server, game, host } = await pair();
  const link = new Link(host, { onMessage() {}, onClose() {}, onProtocolError() {} });
  game.resume();
  await new Promise(resolve => link.end(resolve));
  await new Promise(resolve => link.end(resolve));
  game.destroy();
  server.close();
});
