'use strict';
// Rate limiting for host -> game EVENT messages ("failure is never silent", AGENTS.md).
//
// Only the kinds a page can trigger at will are limited: blocked-url (a page requesting LAN addresses
// in a loop), permission-denied and download-cancelled, each with its own token bucket per screen.
// What is dropped is counted and reported later as one EVENT of the same kind with
// {suppressed: n} (see ScreenManager). Failures the game must always see are never limited:
// renderer-gone, capture-error, unresponsive/responsive, main-frame load-error, screen-limit,
// policy-error. Unknown kinds share one limited bucket per screen.

const EVENT_BURST = 20;
const EVENT_PER_SECOND = 5;

const NEVER_LIMITED = Object.freeze(['renderer-gone', 'capture-error', 'unresponsive', 'responsive', 'load-error',
  'screen-limit', 'policy-error']);
const LIMITED = Object.freeze(['blocked-url', 'permission-denied', 'download-cancelled']);

class EventLimiter {
  constructor(burst = EVENT_BURST, perSecond = EVENT_PER_SECOND, now = Date.now) {
    this.burst = burst;
    this.perSecond = perSecond;
    this.now = now;
    this.buckets = new Map(); // "screenId|kind" -> {tokens, at}
    this.suppressed = new Map(); // "screenId|kind" -> {screenId, kind, count}
    this.dropped = 0;
  }

  static isLimited(kind) {
    return !NEVER_LIMITED.includes(kind);
  }

  /** True when an EVENT of `kind` for `screenId` may be sent now; otherwise it is counted. */
  allow(screenId, kind) {
    if (!EventLimiter.isLimited(kind)) return true;
    const k = LIMITED.includes(kind) ? kind : 'other';
    const key = screenId + '|' + k;
    const t = this.now();
    let b = this.buckets.get(key);
    if (!b) {
      b = { tokens: this.burst, at: t };
      this.buckets.set(key, b);
    }
    b.tokens = Math.min(this.burst, b.tokens + ((t - b.at) / 1000) * this.perSecond);
    b.at = t;
    if (b.tokens >= 1) {
      b.tokens -= 1;
      return true;
    }
    this.dropped++;
    const s = this.suppressed.get(key);
    if (s) s.count++;
    else this.suppressed.set(key, { screenId, kind: k === 'other' ? kind : k, count: 1 });
    return false;
  }

  /** Counts suppressed since the last call, as [{screenId, kind, count}]; resets them. */
  takeSuppressed() {
    const out = [...this.suppressed.values()];
    this.suppressed.clear();
    return out;
  }

  hasSuppressed() {
    return this.suppressed.size > 0;
  }

  /** Forgets a screen (DESTROY): its buckets and any pending count. */
  forget(screenId) {
    const prefix = screenId + '|';
    for (const key of [...this.buckets.keys()]) if (key.startsWith(prefix)) this.buckets.delete(key);
    for (const key of [...this.suppressed.keys()]) if (key.startsWith(prefix)) this.suppressed.delete(key);
  }
}

module.exports = { EventLimiter, EVENT_BURST, EVENT_PER_SECOND, NEVER_LIMITED, LIMITED };
