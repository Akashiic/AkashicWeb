'use strict';
// Host monotonic clock. MEDIA.t, PONG.hostTimeMs and PCM.ptsMicros all come from this one source so
// the game can map every host timestamp with a single offset estimated from PING/PONG (ADR-008/012).
// process.hrtime is monotonic and unaffected by wall-clock changes.

function monoMicros() {
  return Number(process.hrtime.bigint() / 1000n);
}

function monoMs() {
  return Number(process.hrtime.bigint() / 1000n) / 1000;
}

module.exports = { monoMicros, monoMs };
