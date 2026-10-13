'use strict';
// Wire format between AkashicWebHost and the game. Mirrors
// src/main/java/com/akashiic/web/client/host/HostProtocol.java; see docs/PROTOCOL.md.

const MAGIC = 0x414b5731; // "AKW1"
const HEADER_BYTES = 20;
const MAX_PAYLOAD = 16 * 1024 * 1024;
const PROTO_MAJOR = 1;
const PROTO_MINOR = 0;
const FRAME_HEADER_BYTES = 12;
const PCM_HEADER_BYTES = 16;

const T = Object.freeze({
  // host -> game
  HELLO: 1, FRAME: 2, PCM: 3, STATE: 4, MEDIA: 5, EVENT: 6, PONG: 7, BYE: 8,
  // game -> host
  WELCOME: 32, CREATE: 33, DESTROY: 34, NAVIGATE: 35, INPUT: 36, POLICY: 37,
  AUDIO: 38, RENDER: 39, RESIZE: 40, SYNC: 41, PING: 42, QUIT: 43,
});

/** Name of a message type for logs; unknown types print as their number. */
function typeName(type) {
  for (const k of Object.keys(T)) if (T[k] === type) return k;
  return String(type);
}

function header(type, screenId, seq, length) {
  if (length > MAX_PAYLOAD) throw new RangeError('payload too large: ' + length);
  const h = Buffer.alloc(HEADER_BYTES);
  h.writeUInt32BE(MAGIC, 0);
  h.writeUInt16BE(type, 4);
  h.writeUInt16BE(0, 6);
  h.writeUInt32BE(screenId >>> 0, 8);
  h.writeUInt32BE(seq >>> 0, 12);
  h.writeUInt32BE(length, 16);
  return h;
}

function encode(type, screenId, seq, payload) {
  const body = payload == null ? Buffer.alloc(0)
    : Buffer.isBuffer(payload) ? payload : Buffer.from(JSON.stringify(payload), 'utf8');
  return [header(type, screenId, seq, body.length), body];
}

/**
 * Like encode(), but the payload is the concatenation of `parts`. The parts are not copied, so a
 * large frame row slice goes to the socket without an extra memcpy.
 */
function encodeParts(type, screenId, seq, parts) {
  let length = 0;
  for (const p of parts) length += p.length;
  return [header(type, screenId, seq, length), ...parts];
}

/** Incremental decoder: feed() chunks, receive complete messages through the callback. */
class Decoder {
  constructor(onMessage) {
    this.onMessage = onMessage;
    this.chunks = [];
    this.buffered = 0;
  }

  feed(chunk) {
    this.chunks.push(chunk);
    this.buffered += chunk.length;
    for (;;) {
      if (this.buffered < HEADER_BYTES) return;
      const head = this._peek(HEADER_BYTES);
      if (head.readUInt32BE(0) !== MAGIC) throw new Error('bad magic');
      const length = head.readUInt32BE(16);
      if (length > MAX_PAYLOAD) throw new Error('bad length ' + length);
      if (this.buffered < HEADER_BYTES + length) return;
      const all = this._take(HEADER_BYTES + length);
      this.onMessage({
        type: all.readUInt16BE(4),
        flags: all.readUInt16BE(6),
        screenId: all.readUInt32BE(8),
        seq: all.readUInt32BE(12),
        payload: all.subarray(HEADER_BYTES),
      });
    }
  }

  _peek(n) {
    if (this.chunks[0].length >= n) return this.chunks[0].subarray(0, n);
    return Buffer.concat(this.chunks).subarray(0, n);
  }

  _take(n) {
    const all = this.chunks.length === 1 ? this.chunks[0] : Buffer.concat(this.chunks);
    this.chunks = all.length > n ? [all.subarray(n)] : [];
    this.buffered = all.length - n;
    return all.subarray(0, n);
  }
}

/** FRAME payload: u16 width,height,x,y,w,h + BGRA rows of the dirty rect. */
function frameHeader(width, height, x, y, w, h) {
  const b = Buffer.alloc(FRAME_HEADER_BYTES);
  [width, height, x, y, w, h].forEach((v, i) => b.writeUInt16BE(v, i * 2));
  return b;
}

/** PCM payload header: i64 ptsMicros, u32 sampleRate, u16 channels, u16 frames (then s16 LE samples). */
function pcmHeader(ptsMicros, sampleRate, channels, frames) {
  const b = Buffer.alloc(PCM_HEADER_BYTES);
  b.writeBigInt64BE(BigInt(Math.trunc(ptsMicros)), 0);
  b.writeUInt32BE(sampleRate >>> 0, 8);
  b.writeUInt16BE(channels, 12);
  b.writeUInt16BE(frames, 14);
  return b;
}

/** Parses a JSON payload. Throws SyntaxError on malformed input; the caller decides what to do. */
function decodeJson(payload) {
  if (!payload || payload.length === 0) return {};
  return JSON.parse(payload.toString('utf8'));
}

module.exports = {
  MAGIC, HEADER_BYTES, MAX_PAYLOAD, PROTO_MAJOR, PROTO_MINOR, FRAME_HEADER_BYTES, PCM_HEADER_BYTES, T,
  typeName, encode, encodeParts, Decoder, frameHeader, pcmHeader, decodeJson,
};
