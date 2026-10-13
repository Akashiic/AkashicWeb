'use strict';
// Session per screen (ADR-012): every SHARED screen gets its own in-memory partition (no "persist:"
// prefix, documented as in-memory in Electron's session.md), so a follower's browser never holds a
// login and a relayed click cannot act as the player. PRIVATE screens (post-event) share one
// persistent profile under userData.
//
// Electron never frees a session or its network context once created, so a new partition per CREATE
// grew the host by about 0.7 MB per CREATE/DESTROY (wave-1 review, measured on Linux). Ephemeral
// partitions are therefore POOLED: a released partition goes back to the free list only after its
// cookies and storage, HTTP cache, HTTP auth cache, host resolver cache, pooled connections and the
// host's own verdict cache have all been cleared; a fresh partition (new generation number, never a
// reused name) is made only when no cleared one is free. A partition whose clearing fails is never
// handed out again. The pool size follows the number of screens alive at once (at most MAX_SCREENS)
// plus the ones still being cleared.

const { attachRequestPolicy, attachPermissionPolicy, attachDocumentPolicy } = require('./policy');
const { acceptLanguages } = require('./messages');

const PRIVATE_PARTITION = 'persist:akw-private';

class SessionFactory {
  /**
   * ctx: policy() -> normalised policy, allowFile, userAgent() -> string, locale() -> string,
   * registry (CaptureRegistry), onBlocked(wcId, info), onInternalIp(wcId, info), onDenied(wcId, permission),
   * onDownload(wcId, info), log; for tests: fromPartition(name), prepare(session) -> cache.
   */
  constructor(ctx) {
    this.ctx = ctx;
    this.generation = 0;
    this.prepared = new WeakMap(); // session -> its SessionNetCache (verdicts, proxy decisions)
    this.free = []; // cleared ephemeral partitions ready for reuse: [{partition, session}]
    this.stats = { created: 0, reused: 0, released: 0, broken: 0 };
  }

  _fromPartition(name) {
    if (this.ctx.fromPartition) return this.ctx.fromPartition(name);
    return require('electron').session.fromPartition(name);
  }

  _prepared(ses) {
    let cache = this.prepared.get(ses);
    if (!cache) {
      cache = this.ctx.prepare ? this.ctx.prepare(ses) : this.prepare(ses);
      this.prepared.set(ses, cache);
    }
    return cache;
  }

  /** Returns {session, partition, ephemeral, verdict(host, url)} for a new screen. */
  forScreen(screenId, kind) {
    let rec;
    if (kind === 'private') {
      rec = { partition: PRIVATE_PARTITION, session: this._fromPartition(PRIVATE_PARTITION) };
    } else if (this.free.length > 0) {
      rec = this.free.shift();
      this.stats.reused++;
    } else {
      this.generation++;
      const partition = 'akw-' + this.generation;
      rec = { partition, session: this._fromPartition(partition) };
      this.stats.created++;
      if (this.ctx.log) this.ctx.log.info('new partition ' + partition + ' (created ' + this.stats.created + ', reused ' + this.stats.reused + ')');
    }
    const cache = this._prepared(rec.session);
    return {
      session: rec.session,
      partition: rec.partition,
      ephemeral: kind !== 'private',
      verdict: (host, url) => cache.verdictFor(host, url),
    };
  }

  /** Installs handlers and policy on a new session; returns its request-policy cache. */
  prepare(ses) {
    const ctx = this.ctx;
    ses.setUserAgent(ctx.userAgent(), acceptLanguages(ctx.locale()));
    // The spellchecker downloads dictionaries from a Google CDN; a screen has no use for it.
    ses.setSpellCheckerEnabled(false);
    const cache = attachRequestPolicy(ses, {
      policy: ctx.policy,
      allowFile: ctx.allowFile,
      isExempt: wcId => ctx.registry.isCapturePage(wcId),
      onBlocked: ctx.onBlocked,
      onInternalIp: ctx.onInternalIp,
    });
    attachDocumentPolicy(ses, {
      isExempt: wcId => ctx.registry.isCapturePage(wcId),
      log: ctx.log,
    });
    attachPermissionPolicy(ses, {
      isCapturePage: wcId => ctx.registry.isCapturePage(wcId),
      onDenied: ctx.onDenied,
    });
    ses.setDisplayMediaRequestHandler(ctx.registry.handler(require('electron').webContents));
    ses.on('will-download', (event, item, wc) => {
      event.preventDefault();
      ctx.onDownload(wc ? wc.id : null, { url: item.getURL(), filename: item.getFilename() });
    });
    return cache;
  }

  /**
   * Clears a released ephemeral partition and, once EVERYTHING is cleared, returns it to the pool.
   * Resolves true when it went back to the pool.
   */
  release(entry) {
    if (!entry || !entry.ephemeral) return Promise.resolve(false);
    const ses = entry.session;
    const cache = this.prepared.get(ses);
    if (cache) cache.clear();
    this.stats.released++;
    const step = (name, fn) => {
      if (typeof fn !== 'function') return Promise.reject(new Error(name + ' unavailable'));
      return Promise.resolve().then(() => fn.call(ses)).catch(err => { throw new Error(name + ': ' + String(err && err.message)); });
    };
    const steps = [
      step('clearStorageData', ses.clearStorageData), // cookies, local/session storage, IndexedDB, workers, ...
      step('clearCache', ses.clearCache),
      step('clearAuthCache', ses.clearAuthCache),
      step('clearHostResolverCache', ses.clearHostResolverCache),
    ];
    // Newer, more thorough clearing and idle keep-alive sockets: used when the API exists.
    if (typeof ses.clearData === 'function') steps.push(step('clearData', ses.clearData));
    if (typeof ses.closeAllConnections === 'function') steps.push(step('closeAllConnections', ses.closeAllConnections));
    return Promise.all(steps).then(() => {
      this.free.push({ partition: entry.partition, session: ses });
      return true;
    }, err => {
      // Not handed out again: a partly cleared partition could carry the last screen's state.
      this.stats.broken++;
      this.ctx.log.warn('session ' + entry.partition + ' not reused: ' + err.message);
      return false;
    });
  }
}

module.exports = { SessionFactory, PRIVATE_PARTITION };
