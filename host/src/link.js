'use strict';
// The single loopback connection to the game (ADR-003). Framing, per-screen sequence numbers and
// backpressure tracking live here; what to send and when is decided by the callers.

const P = require('./protocol');

const DEFAULT_MAX_QUEUED_BYTES = 64 * 1024 * 1024;

class Link {
  /**
   * @param {import('net').Socket} socket connected socket
   * @param {object} handlers onMessage(msg), onDrain(), onClose(reason), onProtocolError(err)
   * @param {object} [options] maxQueuedBytes: if the game stops reading and this much piles up in
   *   Node's write queue, the connection is dropped instead of growing memory without bound.
   */
  constructor(socket, handlers, options = {}) {
    this.socket = socket;
    this.handlers = handlers;
    this.maxQueuedBytes = options.maxQueuedBytes || DEFAULT_MAX_QUEUED_BYTES;
    this.seq = new Map();
    this.blocked = false;
    this.closed = false;
    this.bytesOut = 0;
    this.decoder = new P.Decoder(msg => this.handlers.onMessage(msg));

    socket.setNoDelay(true);
    socket.on('data', chunk => {
      if (this.closed) return;
      try {
        this.decoder.feed(chunk);
      } catch (err) {
        // Framing is broken: nothing after this point can be trusted (docs/PROTOCOL.md).
        this.handlers.onProtocolError(err);
      }
    });
    socket.on('drain', () => {
      this.blocked = false;
      if (this.handlers.onDrain) this.handlers.onDrain();
    });
    socket.on('error', err => { this.lastError = err; });
    socket.on('close', () => {
      if (this.closed) return;
      this.closed = true;
      this.handlers.onClose(this.lastError ? this.lastError.code || this.lastError.message : 'eof');
    });
  }

  nextSeq(screenId) {
    const s = (this.seq.get(screenId) || 0) >>> 0;
    this.seq.set(screenId, (s + 1) >>> 0);
    return s;
  }

  /**
   * Sends one message. `payload` is an object (JSON), a Buffer, null, or an array of Buffers that
   * form the payload together. Returns true while the socket can take more without queueing.
   */
  send(type, screenId, payload) {
    if (this.closed || this.socket.destroyed) return false;
    const seq = this.nextSeq(screenId);
    const parts = Array.isArray(payload) ? P.encodeParts(type, screenId, seq, payload) : P.encode(type, screenId, seq, payload);
    let ok = true;
    this.socket.cork();
    for (const part of parts) {
      if (part.length === 0) continue;
      ok = this.socket.write(part);
      this.bytesOut += part.length;
    }
    this.socket.uncork();
    if (!ok) this.blocked = true;
    if (this.socket.writableLength > this.maxQueuedBytes) {
      this.lastError = new Error('game-not-reading');
      this.lastError.code = 'game-not-reading';
      this.socket.destroy();
      return false;
    }
    return ok;
  }

  /** Ends the connection after queued data is flushed; calls `done` once the socket is finished. */
  end(done) {
    if (this.closed || this.socket.destroyed) {
      if (done) done();
      return;
    }
    let called = false;
    const once = () => { if (!called) { called = true; if (done) done(); } };
    this.socket.end(once);
    this.socket.once('close', once);
  }
}

module.exports = { Link };
