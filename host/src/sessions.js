'use strict';
// Session per screen (ADR-012): every SHARED screen gets its own in-memory partition (no "persist:"
// prefix, documented as in-memory in Electron's session.md), so a follower's browser never holds a
// login and a relayed click cannot act as the player. PRIVATE screens (post-event) share one
// persistent profile under userData.
//
// Electron caches sessions by partition name for the life of the process, so reusing "akw-<id>"
// after DESTROY would hand the next CREATE the previous cookie jar. The partition name therefore
// carries a per-host generation counter, and released sessions have their storage cleared.

const { session, webContents } = require('electron');
const { attachRequestPolicy, attachPermissionPolicy, attachDocumentPolicy } = require('./policy');
const { acceptLanguages } = require('./messages');

const PRIVATE_PARTITION = 'persist:akw-private';

class SessionFactory {
  /**
   * ctx: policy() -> normalised policy, allowFile, userAgent() -> string, locale() -> string,
   * registry (CaptureRegistry), onBlocked(wcId, info), onInternalIp(wcId, info), onDenied(wcId, permission),
   * onDownload(wcId, info), log
   */
  constructor(ctx) {
    this.ctx = ctx;
    this.generation = 0;
    this.prepared = new WeakMap(); // session -> its SessionNetCache (verdicts, proxy decisions)
  }

  partitionFor(screenId, kind) {
    if (kind === 'private') return PRIVATE_PARTITION;
    this.generation++;
    return 'akw-' + screenId + '-' + this.generation;
  }

  /** Returns {session, partition, ephemeral, verdict(host, url)} for a new screen. */
  forScreen(screenId, kind) {
    const partition = this.partitionFor(screenId, kind);
    const ses = session.fromPartition(partition);
    let cache = this.prepared.get(ses);
    if (!cache) {
      cache = this.prepare(ses);
      this.prepared.set(ses, cache);
    }
    return { session: ses, partition, ephemeral: kind !== 'private', verdict: (host, url) => cache.verdictFor(host, url) };
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
    ses.setDisplayMediaRequestHandler(ctx.registry.handler(webContents));
    ses.on('will-download', (event, item, wc) => {
      event.preventDefault();
      ctx.onDownload(wc ? wc.id : null, { url: item.getURL(), filename: item.getFilename() });
    });
    return cache;
  }

  /** Clears an ephemeral session's data so the in-memory cache and cookies do not accumulate. */
  release(entry) {
    if (!entry || !entry.ephemeral) return Promise.resolve();
    const ses = entry.session;
    const cache = this.prepared.get(ses);
    if (cache) cache.clear();
    return Promise.all([
      ses.clearStorageData().catch(() => {}),
      ses.clearCache().catch(() => {}),
      ses.clearHostResolverCache().catch(() => {}),
    ]).then(() => undefined, err => this.ctx.log.warn('session release failed', String(err)));
  }
}

module.exports = { SessionFactory, PRIVATE_PARTITION };
