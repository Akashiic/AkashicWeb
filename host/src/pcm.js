'use strict';
// PCM math for SPATIAL audio (ADR-008): 48 kHz, 2 channels, signed 16-bit little-endian, about 100
// packets per second. floatToS16 is also serialised into the AudioWorklet (see workletSource), so it
// must stay self-contained.

const os = require('os');
const P = require('./protocol');

const SAMPLE_RATE = 48000;
const CHANNELS = 2;
const FRAMES_PER_PACKET = 480; // 10 ms

function floatToS16(x) {
  if (x !== x) return 0; // NaN
  if (x <= -1) return -32768;
  if (x >= 1) return 32767;
  return x < 0 ? Math.round(x * 32768) : Math.round(x * 32767);
}

/**
 * Source of the AudioWorklet module. It batches render quanta (128 frames) into packets of
 * `framesPerPacket` stereo frames and transfers each packet to the capture page.
 */
function workletSource(framesPerPacket = FRAMES_PER_PACKET) {
  return floatToS16.toString() + '\n' + `
class AkwCaptureProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.n = ${framesPerPacket | 0};
    this.buf = new Int16Array(this.n * 2);
    this.fill = 0;
  }
  process(inputs) {
    const input = inputs[0];
    if (!input || input.length === 0) return true;
    const l = input[0];
    const r = input.length > 1 ? input[1] : input[0];
    for (let i = 0; i < l.length; i++) {
      this.buf[this.fill * 2] = floatToS16(l[i]);
      this.buf[this.fill * 2 + 1] = floatToS16(r[i]);
      if (++this.fill === this.n) {
        this.port.postMessage(this.buf.buffer, [this.buf.buffer]);
        this.buf = new Int16Array(this.n * 2);
        this.fill = 0;
      }
    }
    return true;
  }
}
registerProcessor('akw-capture', AkwCaptureProcessor);
`;
}

/**
 * PCM payload parts for one packet. `samples` holds interleaved s16 in the platform's byte order
 * (an ArrayBuffer from the worklet); the wire is little-endian, so big-endian hosts swap.
 */
function pcmPayload(ptsMicros, sampleRate, channels, samples) {
  const bytes = Buffer.isBuffer(samples) ? samples : Buffer.from(samples.buffer || samples, samples.byteOffset || 0, samples.byteLength);
  if (bytes.length % (2 * channels) !== 0) throw new RangeError('PCM length ' + bytes.length + ' is not whole frames');
  const frames = bytes.length / (2 * channels);
  if (frames < 1 || frames > 0xffff) throw new RangeError('PCM frames ' + frames);
  let body = bytes;
  if (os.endianness() === 'BE') {
    body = Buffer.from(bytes);
    body.swap16();
  }
  return [P.pcmHeader(ptsMicros, sampleRate, channels, frames), body];
}

module.exports = { SAMPLE_RATE, CHANNELS, FRAMES_PER_PACKET, floatToS16, workletSource, pcmPayload };
