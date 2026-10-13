'use strict';
// Site policy and internal-address block for every screen session (ADR-013 and its amendment).
// The pure checks mirror the server's UrlPolicy (AkashicFM UrlPolicy.isInternal, copied in spirit:
// same ranges plus IPv6 ULA/link-local/NAT64/6to4). The attach* functions take Electron objects as
// arguments so this module never imports Electron and its pure part runs under plain Node.
//
// Why the host resolves every request itself: the ADR-013 amendment's measure 1 (deny Chromium's
// Local Network Access permissions so Chromium blocks public pages from reaching LAN/loopback) does
// nothing in the official Electron 44 build. Electron disables the LocalNetworkAccessChecks feature
// in every renderer and --enable-features cannot turn it back on (review of wave 1, AUTOMATED on
// Linux with Electron 44.7.0). So the host itself resolves the name of EVERY http/https/ws/wss
// request (frames and subresources: fetch, img, beacon, WebSocket, ...) with the session's resolver
// before the request may start, and cancels it when any answer is internal. Verdicts are cached per
// session and host for VERDICT_TTL_MS. Residual risk: DNS rebinding. The check and Chromium's own
// connection resolve separately, so a name with TTL 0 that answers a public address to the check and
// an internal one to the connection still gets through (within the cache window, or by racing the two
// lookups); only the response detector (measure 3) sees that, after the fact.

const MAX_URL = 8192;
// Verdict cache per session and host. A refused or allowed verdict lives 30 s; a failed resolution
// only 5 s, so a transient DNS failure does not blank a page's images for long (still fail closed).
const VERDICT_TTL_MS = 30000;
const FAILURE_TTL_MS = 5000;
const MAX_CACHED_HOSTS = 1024;

// ---------------------------------------------------------------------------------------------------
// Addresses

/** Dotted-quad decimal IPv4 (the only form WHATWG URL and the resolver produce) -> [a,b,c,d] or null. */
function parseIPv4(s) {
  if (typeof s !== 'string') return null;
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(s);
  if (!m) return null;
  const b = m.slice(1).map(Number);
  return b.every(x => x <= 255) ? b : null;
}

/** IPv6 text (optionally in brackets, with an IPv4 tail) -> 8 16-bit words, or null. */
function parseIPv6(input) {
  if (typeof input !== 'string') return null;
  let s = input;
  if (s.startsWith('[') && s.endsWith(']')) s = s.slice(1, -1);
  if (!s.includes(':') || !/^[0-9A-Fa-f:.]+$/.test(s)) return null;
  if (s.includes('.')) {
    const cut = s.lastIndexOf(':');
    const v4 = parseIPv4(s.slice(cut + 1));
    if (!v4) return null;
    s = s.slice(0, cut + 1) + ((v4[0] << 8) | v4[1]).toString(16) + ':' + ((v4[2] << 8) | v4[3]).toString(16);
  }
  const halves = s.split('::');
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(':') : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  let groups;
  if (halves.length === 1) {
    if (head.length !== 8) return null;
    groups = head;
  } else {
    if (head.length + tail.length > 7) return null;
    groups = head.concat(new Array(8 - head.length - tail.length).fill('0'), tail);
  }
  const words = [];
  for (const g of groups) {
    if (!/^[0-9A-Fa-f]{1,4}$/.test(g)) return null;
    words.push(parseInt(g, 16));
  }
  return words;
}

function isInternalIPv4(b) {
  const [a, c] = b;
  return a === 0 // 0.0.0.0/8 "this network"
    || a === 10 // 10.0.0.0/8
    || a === 127 // loopback
    || (a === 169 && c === 254) // link-local
    || (a === 172 && c >= 16 && c <= 31) // 172.16.0.0/12
    || (a === 192 && c === 168) // 192.168.0.0/16
    || (a === 100 && c >= 64 && c <= 127) // 100.64.0.0/10 CGNAT
    || (a === 192 && c === 0 && (b[2] === 0 || b[2] === 2)) // 192.0.0.0/24 IETF, 192.0.2.0/24 TEST-NET-1
    || (a === 198 && (c === 18 || c === 19)) // 198.18.0.0/15 benchmarking
    || (a === 198 && c === 51 && b[2] === 100) // TEST-NET-2
    || (a === 203 && c === 0 && b[2] === 113) // TEST-NET-3
    || a >= 224; // multicast, reserved, broadcast
}

function v4FromWords(hi, lo) {
  return [hi >> 8, hi & 0xff, lo >> 8, lo & 0xff];
}

function isInternalIPv6(w) {
  const zeros = (from, to) => w.slice(from, to).every(x => x === 0);
  if (zeros(0, 8)) return true; // ::
  if (zeros(0, 7) && w[7] === 1) return true; // ::1
  if ((w[0] & 0xfe00) === 0xfc00) return true; // fc00::/7 unique local
  if ((w[0] & 0xffc0) === 0xfe80) return true; // fe80::/10 link-local
  if ((w[0] & 0xffc0) === 0xfec0) return true; // fec0::/10 old site-local
  if ((w[0] & 0xff00) === 0xff00) return true; // multicast
  if (zeros(0, 5) && w[5] === 0xffff) return isInternalIPv4(v4FromWords(w[6], w[7])); // ::ffff:a.b.c.d
  if (zeros(0, 6)) return isInternalIPv4(v4FromWords(w[6], w[7])); // ::a.b.c.d (deprecated compat)
  if (w[0] === 0x64 && w[1] === 0xff9b && zeros(2, 6)) return isInternalIPv4(v4FromWords(w[6], w[7])); // NAT64
  if (w[0] === 0x64 && w[1] === 0xff9b && w[2] === 1) return true; // 64:ff9b:1::/48 local-use NAT64
  if (w[0] === 0x2002) return isInternalIPv4(v4FromWords(w[1], w[2])); // 6to4
  if (w[0] === 0x2001 && w[1] === 0x0db8) return true; // documentation
  if (w[0] === 0x0100 && zeros(1, 4)) return true; // 100::/64 discard
  return false;
}

/**
 * True for a resolved or reported address (no brackets needed) that is not a public unicast address.
 * Anything that looks like an address but does not parse is treated as internal (fail closed).
 */
function isInternalAddress(addr) {
  if (typeof addr !== 'string' || addr === '') return true;
  const s = addr.trim();
  const v4 = parseIPv4(s);
  if (v4) return isInternalIPv4(v4);
  if (s.includes('%')) return true; // scoped (zone id) addresses are link-local by definition
  const v6 = parseIPv6(s);
  if (v6) return isInternalIPv6(v6);
  return true;
}

const INTERNAL_SUFFIXES = ['.localhost', '.local', '.internal', '.lan', '.home.arpa', '.intranet'];

/** True for a URL hostname that is an internal literal or an internal-only name. No DNS. */
function isInternalHostname(hostname) {
  if (typeof hostname !== 'string' || hostname === '') return true;
  const h = hostname.toLowerCase().replace(/\.+$/, '');
  if (h.startsWith('[')) {
    const v6 = parseIPv6(h);
    return v6 ? isInternalIPv6(v6) : true;
  }
  const v4 = parseIPv4(h);
  if (v4) return isInternalIPv4(v4);
  if (h === 'localhost' || INTERNAL_SUFFIXES.some(sfx => h.endsWith(sfx))) return true;
  // Single-label names ("router", "nas") are resolved by the LAN's DNS or NetBIOS.
  return !h.includes('.');
}

function isIpLiteral(hostname) {
  return parseIPv4(hostname) != null || (hostname.startsWith('[') && parseIPv6(hostname) != null);
}

// ---------------------------------------------------------------------------------------------------
// Site policy

function hostMatches(host, entry) {
  return host === entry || host.endsWith('.' + entry);
}

function matchesAny(host, list) {
  return Array.isArray(list) && list.some(e => hostMatches(host, e));
}

function deny(reason, host, url) {
  return { ok: false, reason, host: host || '', url: url || '' };
}

/**
 * Checks a navigation target. `frame` is 'main' (top-level document) or 'sub' (iframe).
 * Site policy: the block list applies to every frame; the allow list applies to the top-level
 * document only, because allowed sites embed third-party frames (consent, players, ads) that an
 * allow list of a few domains would break. Internal addresses are refused everywhere.
 * Reasons: malformed, too-long, scheme, credentials, internal, not-allowed, blocked.
 */
function checkNavigation(raw, policy, frame, options = {}) {
  if (typeof raw !== 'string' || raw === '') return deny('malformed');
  if (raw.length > MAX_URL) return deny('too-long');
  if (frame === 'main' && raw === 'about:blank') return { ok: true, host: '', url: raw, needsDns: false };
  let u;
  try {
    u = new URL(raw);
  } catch (e) {
    return deny('malformed');
  }
  if (u.protocol === 'file:' && options.allowFile) return { ok: true, host: '', url: u.href, needsDns: false };
  if (u.protocol !== 'http:' && u.protocol !== 'https:') {
    // Frames may hold documents that never touch the network.
    if (frame === 'sub' && (u.protocol === 'about:' || u.protocol === 'data:' || u.protocol === 'blob:')) {
      return { ok: true, host: '', url: u.href, needsDns: false };
    }
    return deny('scheme', u.protocol, u.href);
  }
  const host = u.hostname.toLowerCase().replace(/\.+$/, '');
  if (frame === 'main' && (u.username || u.password)) return deny('credentials', host, u.href);
  if (isInternalHostname(host)) return deny('internal', host, u.href);
  // No policy (not received or unreadable) is deny-all, never allow-all (fail closed).
  const p = policy || { mode: 'allowlist', allow: [], block: [] };
  if (matchesAny(host, p.block)) return deny('blocked', host, u.href);
  if (frame === 'main' && p.mode === 'allowlist' && !matchesAny(host, p.allow)) return deny('not-allowed', host, u.href);
  return { ok: true, host, url: u.href, needsDns: !isIpLiteral(host) };
}

const NETWORK_SCHEMES = new Set(['http:', 'https:', 'ws:', 'wss:']);

/** Checks one request seen by webRequest.onBeforeRequest (resourceType as Electron reports it). */
function checkRequest(raw, resourceType, policy, options = {}) {
  if (resourceType === 'mainFrame') return checkNavigation(raw, policy, 'main', options);
  if (resourceType === 'subFrame') return checkNavigation(raw, policy, 'sub', options);
  let u;
  try {
    u = new URL(raw);
  } catch (e) {
    return deny('malformed');
  }
  if (u.protocol === 'file:') return options.allowFile ? { ok: true, host: '', url: u.href, needsDns: false } : deny('scheme', 'file:', u.href);
  if (!NETWORK_SCHEMES.has(u.protocol)) return { ok: true, host: '', url: u.href, needsDns: false };
  const host = u.hostname.toLowerCase().replace(/\.+$/, '');
  // Literal internal targets are refused at once for every resource type; names are resolved by the
  // request filter (attachRequestPolicy), because Chromium's own LNA check is off in Electron.
  if (isInternalHostname(host)) return deny('internal', host, u.href);
  return { ok: true, host, url: u.href, needsDns: !isIpLiteral(host) };
}

// ---------------------------------------------------------------------------------------------------
// Permissions

// Every permission is denied except HTML fullscreen, which in offscreen rendering only changes the
// page layout (the window never resizes, see disableHtmlFullscreenWindowResize in screens.js).
// Denying it breaks element.requestFullscreen(), i.e. the YouTube/Twitch fullscreen button
// (observed with Electron 44.7.0 on Linux: the request is refused and the player stays small).
const BENIGN_PERMISSIONS = new Set(['fullscreen']);
// Chromium's Local Network Access permissions, denied by name. This protects NOTHING in Electron 44:
// the LocalNetworkAccessChecks feature is disabled there, so Chromium never asks. It stays denied in
// case a later Electron turns LNA on; the real block is the host's own resolution of every request.
const LNA_PERMISSIONS = Object.freeze(['local-network', 'loopback-network', 'local-network-access']);
const CAPTURE_PERMISSIONS = new Set(['media', 'display-capture']);

/** Decision for a permission request or check. `isCapturePage` is true only for our own capture page. */
function decidePermission(permission, isCapturePage) {
  if (LNA_PERMISSIONS.includes(permission)) return false;
  if (isCapturePage && CAPTURE_PERMISSIONS.has(permission)) return true;
  return BENIGN_PERMISSIONS.has(permission);
}

// ---------------------------------------------------------------------------------------------------
// Electron wiring

function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('timeout')), ms);
    promise.then(v => { clearTimeout(t); resolve(v); }, e => { clearTimeout(t); reject(e); });
  });
}

/**
 * Resolves `host` with the session's own resolver (so proxies, host rules and the DNS cache match
 * what Chromium will use) and refuses internal answers. Fail closed: a name that cannot be resolved
 * in time is refused too. Resolves {ok, reason, address}; never rejects.
 */
function resolveVerdict(ses, host, timeoutMs = 5000) {
  return withTimeout(ses.resolveHost(host), timeoutMs).then(result => {
    const endpoints = (result && result.endpoints) || [];
    const bad = endpoints.find(e => isInternalAddress(e.address));
    if (bad) return { ok: false, reason: 'internal-dns', address: bad.address };
    if (endpoints.length === 0) return { ok: false, reason: 'dns-failed', address: '' };
    return { ok: true, reason: '', address: '' };
  }, () => ({ ok: false, reason: 'dns-failed', address: '' }));
}

/** True when a ses.resolveProxy() answer routes the request through a proxy ("PROXY h:p", "SOCKS5 ..."). */
function isProxiedAnswer(answer) {
  if (typeof answer !== 'string') return false;
  const first = answer.split(';')[0].trim().toUpperCase();
  return first !== '' && first !== 'DIRECT';
}

/**
 * Per-session caches of host verdicts and proxy decisions (TTL, bounded, shared in-flight lookups).
 * `now` is injectable for tests.
 */
class SessionNetCache {
  constructor(ses, options = {}) {
    this.ses = ses;
    this.timeoutMs = options.dnsTimeoutMs || 5000;
    this.now = options.now || Date.now;
    this.verdicts = new Map(); // host -> {promise, expires}
    this.proxies = new Map(); // "scheme//host" -> {promise, expires}
    this.lookups = 0; // resolveHost calls made (tests and stats)
  }

  static _get(map, key, now) {
    const e = map.get(key);
    if (e && e.expires > now) return e.promise;
    if (e) map.delete(key);
    return null;
  }

  static _put(map, key, entry) {
    map.delete(key); // re-insert at the end: Map order is the eviction order
    map.set(key, entry);
    while (map.size > MAX_CACHED_HOSTS) map.delete(map.keys().next().value);
    return entry;
  }

  /** Whether requests to `url` go through a proxy (cached per scheme and host). Never rejects. */
  isProxied(url) {
    let key;
    try {
      const u = new URL(url);
      key = u.protocol + '//' + u.host;
    } catch (e) {
      return Promise.resolve(false);
    }
    const now = this.now();
    const hit = SessionNetCache._get(this.proxies, key, now);
    if (hit) return hit;
    const p = typeof this.ses.resolveProxy !== 'function' ? Promise.resolve(false)
      : withTimeout(Promise.resolve(this.ses.resolveProxy(url)), this.timeoutMs).then(isProxiedAnswer, () => false);
    SessionNetCache._put(this.proxies, key, { promise: p, expires: now + VERDICT_TTL_MS });
    return p;
  }

  /**
   * Verdict {ok, reason, address} for a host name, cached. A resolution failure behind a proxy reports
   * 'dns-via-proxy' (the proxy might reach the name, the host cannot check it: still refused), so
   * `/web doctor` can explain a proxy-only network. Never rejects.
   */
  verdictFor(host, url) {
    const now = this.now();
    const hit = SessionNetCache._get(this.verdicts, host, now);
    if (hit) return hit;
    this.lookups++;
    // In flight: concurrent requests for the host share this lookup.
    const entry = SessionNetCache._put(this.verdicts, host, { promise: null, expires: now + this.timeoutMs + VERDICT_TTL_MS });
    entry.promise = resolveVerdict(this.ses, host, this.timeoutMs).then(v => {
      if (v.ok || v.reason !== 'dns-failed') return v;
      return this.isProxied(url || 'https://' + host + '/').then(proxied => (proxied ? Object.assign({}, v, { reason: 'dns-via-proxy' }) : v));
    }).then(v => {
      // A failed lookup is retried sooner than a definite answer.
      entry.expires = this.now() + (v.ok || v.reason === 'internal-dns' ? VERDICT_TTL_MS : FAILURE_TTL_MS);
      return v;
    });
    return entry.promise;
  }

  clear() {
    this.verdicts.clear();
    this.proxies.clear();
  }
}

/**
 * Installs the network part of the policy on a session and returns its SessionNetCache.
 * ctx: policy() -> current normalised policy; allowFile; isExempt(webContentsId) for our own capture
 * page; onBlocked(webContentsId, info); onInternalIp(webContentsId, info); dnsTimeoutMs; now.
 */
function attachRequestPolicy(ses, ctx) {
  const cache = new SessionNetCache(ses, { dnsTimeoutMs: ctx.dnsTimeoutMs, now: ctx.now });
  ses.webRequest.onBeforeRequest((details, callback) => {
    let answered = false;
    const answer = cancel => {
      if (answered) return;
      answered = true;
      callback(cancel ? { cancel: true } : {});
    };
    const wcId = details.webContentsId;
    if (wcId != null && ctx.isExempt(wcId)) return answer(false);
    const verdict = checkRequest(details.url, details.resourceType, ctx.policy(), { allowFile: ctx.allowFile });
    if (!verdict.ok) {
      answer(true);
      ctx.onBlocked(wcId, { url: details.url, reason: verdict.reason, host: verdict.host, resourceType: details.resourceType });
      return;
    }
    if (!verdict.needsDns) return answer(false);
    // Every http/https/ws/wss request with a name, frames and subresources alike: resolve it with the
    // session's resolver and refuse internal answers, failing closed (see the top of this file).
    cache.verdictFor(verdict.host, details.url).then(r => {
      answer(!r.ok);
      if (!r.ok) ctx.onBlocked(wcId, { url: details.url, reason: r.reason, host: verdict.host, address: r.address, resourceType: details.resourceType });
    }, () => {
      answer(true);
      ctx.onBlocked(wcId, { url: details.url, reason: 'dns-failed', host: verdict.host, resourceType: details.resourceType });
    });
  });

  // Measure 3: a detector, not a preventer (DNS rebinding gets past the resolution above). Electron
  // fills details.ip from the response for these events (electron_api_web_request.cc, ToDictionary).
  // By the time it fires the request has reached the address, so the screen is stopped and blanked.
  // Through a proxy, details.ip is the PROXY's address (often loopback for local filtering proxies,
  // antivirus web shields and "game boosters"), not the site's: such requests are skipped.
  const detect = details => {
    if (!details || !details.ip) return;
    const wcId = details.webContentsId;
    if (wcId != null && ctx.isExempt(wcId)) return;
    if (!isInternalAddress(details.ip)) return;
    cache.isProxied(details.url).then(proxied => {
      if (proxied) return;
      ctx.onInternalIp(wcId, { url: details.url, ip: details.ip, resourceType: details.resourceType });
    });
  };
  ses.webRequest.onBeforeRedirect(detect);
  ses.webRequest.onResponseStarted(detect);
  ses.webRequest.onCompleted(detect);
  return cache;
}

// ---------------------------------------------------------------------------------------------------
// Document policy: print block for every frame, no service workers

// window.print() opens a native OS print dialog even from an offscreen window, over the player's game,
// and Electron has no API to veto it. The preload's guard replaces print() before page scripts, but a
// fresh about:blank iframe gets no preload, so `frames[0].print()` used to reach the dialog (wave-1
// review). A CSP sandbox WITHOUT allow-modals makes Blink ignore print() (and alert/confirm/prompt) in
// the document and in every frame it creates, because sandbox flags are inherited. Everything else is
// kept: scripts, same origin (cookies, storage), forms, popups (still gated by the window-open
// handler), pointer lock, presentation, orientation lock, top navigation after user activation,
// storage access, and downloads (so they still reach will-download, which cancels them and tells the
// game, instead of Blink dropping them silently). What a sandbox still takes away and no keyword
// restores: setting document.domain (throws SecurityError) and plugins (off anyway). Measured on local
// pages only (AUTOMATED, Linux); YouTube/Twitch compatibility needs a live check.
const DOCUMENT_SANDBOX = 'sandbox allow-scripts allow-same-origin allow-forms allow-popups allow-popups-to-escape-sandbox '
  + 'allow-pointer-lock allow-presentation allow-orientation-lock allow-top-navigation-by-user-activation '
  + 'allow-storage-access-by-user-activation allow-downloads';

/** Response headers of a document with the sandbox policy added as one more CSP (each is enforced). */
function withSandboxCsp(headers) {
  const out = Object.assign({}, headers || {});
  const key = Object.keys(out).find(k => k.toLowerCase() === 'content-security-policy') || 'Content-Security-Policy';
  const existing = out[key] == null ? [] : Array.isArray(out[key]) ? out[key] : [String(out[key])];
  out[key] = existing.concat([DOCUMENT_SANDBOX]);
  return out;
}

/** True for the fetch of a service worker's main script (it carries "Service-Worker: script"). */
function isServiceWorkerScript(requestHeaders) {
  if (!requestHeaders) return false;
  for (const k of Object.keys(requestHeaders)) {
    if (k.toLowerCase() === 'service-worker' && String(requestHeaders[k]).toLowerCase() === 'script') return true;
  }
  return false;
}

/**
 * Adds the sandbox CSP to every document response (main frame and iframes) and refuses service worker
 * scripts: a service worker serves documents from inside the renderer, which never pass through
 * onHeadersReceived, so it could undo the print block (and it outlives the page). ctx: isExempt(wcId),
 * log.
 */
function attachDocumentPolicy(ses, ctx) {
  let swRefused = 0;
  ses.webRequest.onHeadersReceived({ urls: ['<all_urls>'], types: ['mainFrame', 'subFrame'] }, (details, callback) => {
    const wcId = details.webContentsId;
    if (wcId != null && ctx.isExempt(wcId)) return callback({});
    return callback({ responseHeaders: withSandboxCsp(details.responseHeaders) });
  });
  ses.webRequest.onBeforeSendHeaders({ urls: ['<all_urls>'], types: ['script'] }, (details, callback) => {
    if (!isServiceWorkerScript(details.requestHeaders)) return callback({});
    callback({ cancel: true });
    // Not an EVENT: sites register workers on every page load and the player can do nothing about it.
    if (++swRefused <= 3 && ctx.log) ctx.log.info('service worker script refused (' + swRefused + ')');
    return undefined;
  });
}

/** Deny-all permissions (both handlers, as Electron requires for complete handling). */
function attachPermissionPolicy(ses, ctx) {
  ses.setPermissionRequestHandler((wc, permission, callback) => {
    const allowed = decidePermission(permission, !!(wc && ctx.isCapturePage(wc.id)));
    callback(allowed);
    if (!allowed) ctx.onDenied(wc ? wc.id : null, permission);
  });
  ses.setPermissionCheckHandler((wc, permission) => decidePermission(permission, !!(wc && ctx.isCapturePage(wc.id))));
  if (typeof ses.setDevicePermissionHandler === 'function') ses.setDevicePermissionHandler(() => false);
  if (typeof ses.setBluetoothPairingHandler === 'function') ses.setBluetoothPairingHandler((details, cb) => cb({ confirmed: false }));
  // Device pickers would otherwise wait for a chooser that never comes; answer "no device" at once.
  ses.on('select-hid-device', (event, details, callback) => { event.preventDefault(); callback(); });
  ses.on('select-serial-port', (event, portList, wc, callback) => { event.preventDefault(); callback(''); });
  ses.on('select-usb-device', (event, details, callback) => { event.preventDefault(); callback(); });
}

module.exports = {
  MAX_URL, VERDICT_TTL_MS, FAILURE_TTL_MS, MAX_CACHED_HOSTS, parseIPv4, parseIPv6, isInternalIPv4, isInternalIPv6,
  isInternalAddress, isInternalHostname, isIpLiteral, hostMatches, checkNavigation, checkRequest, decidePermission,
  LNA_PERMISSIONS, BENIGN_PERMISSIONS, attachRequestPolicy, attachPermissionPolicy, resolveVerdict, withTimeout,
  isProxiedAnswer, SessionNetCache, DOCUMENT_SANDBOX, withSandboxCsp, isServiceWorkerScript, attachDocumentPolicy,
};
