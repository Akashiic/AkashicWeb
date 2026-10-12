'use strict';
// Rate limiting for host -> game EVENT messages.

const EVENT_BURST = 20;
const EVENT_PER_SECOND = 5;

/** Token bucket per screen so a hostile page cannot flood the game with EVENT messages. */
class EventLimiter {
  constructor(burst = EVENT_BURST, perSecond = EVENT_PER_SECOND, now = Date.now) {
    this.burst = burst;
    this.perSecond = perSecond;
    this.now = now;
    this.buckets = new Map();
    this.dropped = 0;
  }

  allow(key) {
    const t = this.now();
    let b = this.buckets.get(key);
    if (!b) {
      b = { tokens: this.burst, at: t };
      this.buckets.set(key, b);
    }
    b.tokens = Math.min(this.burst, b.tokens + ((t - b.at) / 1000) * this.perSecond);
    b.at = t;
    if (b.tokens < 1) {
      this.dropped++;
      return false;
    }
    b.tokens -= 1;
    return true;
  }

  forget(key) {
    this.buckets.delete(key);
  }
}

module.exports = { EventLimiter, EVENT_BURST, EVENT_PER_SECOND };
