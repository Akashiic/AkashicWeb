'use strict';
const test = require('node:test');
const assert = require('node:assert');
const vm = require('vm');
const pcm = require('../src/pcm');
const P = require('../src/protocol');

test('floatToS16: full scale, clipping, rounding, NaN', () => {
  assert.strictEqual(pcm.floatToS16(0), 0);
  assert.strictEqual(pcm.floatToS16(1), 32767);
  assert.strictEqual(pcm.floatToS16(-1), -32768);
  assert.strictEqual(pcm.floatToS16(2.5), 32767);
  assert.strictEqual(pcm.floatToS16(-7), -32768);
  assert.strictEqual(pcm.floatToS16(0.5), 16384);
  assert.strictEqual(pcm.floatToS16(-0.5), -16384);
  assert.strictEqual(pcm.floatToS16(NaN), 0);
});

/** Runs the worklet source in a sandbox with a stand-in AudioWorkletProcessor. */
function loadWorklet(framesPerPacket) {
  const posted = [];
  let Proc = null;
  const ctx = vm.createContext({
    AudioWorkletProcessor: class { constructor() { this.port = { postMessage: (buf, transfer) => posted.push({ buf, transfer }) }; } },
    registerProcessor: (name, cls) => { assert.strictEqual(name, 'akw-capture'); Proc = cls; },
    Int16Array, Math,
  });
  vm.runInContext(pcm.workletSource(framesPerPacket), ctx);
  return { proc: new Proc(), posted };
}

test('worklet: batches 128-frame quanta into fixed packets of interleaved stereo s16', () => {
  const { proc, posted } = loadWorklet(480);
  const quantum = () => {
    const l = new Float32Array(128);
    const r = new Float32Array(128);
    for (let i = 0; i < 128; i++) {
      l[i] = 0.25;
      r[i] = -0.25;
    }
    return [[l, r]];
  };
  for (let i = 0; i < 4; i++) assert.strictEqual(proc.process(quantum()), true);
  assert.strictEqual(posted.length, 1, '512 frames in, one 480-frame packet out');
  const s = new Int16Array(posted[0].buf);
  assert.strictEqual(s.length, 960);
  assert.deepStrictEqual([s[0], s[1], s[958], s[959]], [8192, -8192, 8192, -8192]);
  assert.strictEqual(posted[0].transfer[0], posted[0].buf, 'buffer is transferred, not copied');
  for (let i = 0; i < 4; i++) proc.process(quantum());
  assert.strictEqual(posted.length, 2);
});

test('worklet: mono input is duplicated, empty input is tolerated', () => {
  const { proc, posted } = loadWorklet(128);
  assert.strictEqual(proc.process([[]]), true);
  assert.strictEqual(proc.process([]), true);
  proc.process([[new Float32Array(128).fill(0.5)]]);
  const s = new Int16Array(posted[0].buf);
  assert.deepStrictEqual([s[0], s[1]], [16384, 16384]);
});

test('pcmPayload: header + little-endian samples, whole frames only', () => {
  const samples = new Int16Array([1, -1, 300, -300]);
  const [h, body] = pcm.pcmPayload(123456789, 48000, 2, samples.buffer);
  assert.strictEqual(h.length, P.PCM_HEADER_BYTES);
  assert.strictEqual(h.readBigInt64BE(0), 123456789n);
  assert.strictEqual(h.readUInt16BE(14), 2, '2 stereo frames');
  assert.deepStrictEqual([body.readInt16LE(0), body.readInt16LE(2), body.readInt16LE(4), body.readInt16LE(6)], [1, -1, 300, -300]);
  assert.throws(() => pcm.pcmPayload(0, 48000, 2, Buffer.alloc(6)), RangeError);
  assert.throws(() => pcm.pcmPayload(0, 48000, 2, Buffer.alloc(0)), RangeError);
});
