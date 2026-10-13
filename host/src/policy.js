'use strict';
// Site policy and internal-address block for every screen session (ADR-013 and its amendment).
// The pure checks mirror the server's UrlPolicy (AkashicFM UrlPolicy.isInternal, copied in spirit:
// same ranges plus IPv6 ULA/link-local/NAT64/6to4). The attach* functions take Electron objects as
// arguments so this module never imports Electron and its pure part runs under plain Node.

const MAX_URL = 8192;

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
  // Literal internal targets are refused for every resource type. Names that resolve to internal
  // addresses are left to Chromium's Local Network Access check, which runs after DNS resolution
  // and is armed by denying the LNA permissions below (ADR-013 amendment, measure 1).
  if (isInternalHostname(host)) return deny('internal', host, u.href);
  return { ok: true, host, url: u.href, needsDns: false };
}

// ---------------------------------------------------------------------------------------------------
// Permissions

// Every permission is denied except HTML fullscreen, which in offscreen rendering only changes the
// page layout (the window never resizes, see disableHtmlFullscreenWindowResize in screens.js).
// Denying it breaks element.requestFullscreen(), i.e. the YouTube/Twitch fullscreen button
// (observed with Electron 44.7.0 on Linux: the request is refused and the player stays small).
const BENIGN_PERMISSIONS = new Set(['fullscreen']);
// Named so the deny-all intent is visible and tested: Chromium's Local Network Access permissions.
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

/**
 * Installs the network part of the policy on a session.
 * ctx: policy() -> current normalised policy; allowFile; isExempt(webContentsId) for our own capture
 * page; onBlocked(webContentsId, info); onInternalIp(webContentsId, info); dnsTimeoutMs.
 */
function attachRequestPolicy(ses, ctx) {
  const timeoutMs = ctx.dnsTimeoutMs || 5000;
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
    const isFrame = details.resourceType === 'mainFrame' || details.resourceType === 'subFrame';
    if (!isFrame || !verdict.needsDns) return answer(false);
    // ADR-013 amendment, measure 2: resolve frame targets and refuse internal answers.
    resolveVerdict(ses, verdict.host, timeoutMs).then(r => {
      answer(!r.ok);
      if (!r.ok) ctx.onBlocked(wcId, { url: details.url, reason: r.reason, host: verdict.host, address: r.address, resourceType: details.resourceType });
    });
  });

  // Measure 3: a detector, not a preventer. Electron fills details.ip from the response for these
  // events (shell/browser/api/electron_api_web_request.cc, ToDictionary). By the time it fires the
  // request has reached the address, so the screen is stopped and blanked.
  const detect = details => {
    if (!details || !details.ip) return;
    const wcId = details.webContentsId;
    if (wcId != null && ctx.isExempt(wcId)) return;
    if (!isInternalAddress(details.ip)) return;
    ctx.onInternalIp(wcId, { url: details.url, ip: details.ip, resourceType: details.resourceType });
  };
  ses.webRequest.onBeforeRedirect(detect);
  ses.webRequest.onResponseStarted(detect);
  ses.webRequest.onCompleted(detect);
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
  MAX_URL, parseIPv4, parseIPv6, isInternalIPv4, isInternalIPv6, isInternalAddress, isInternalHostname, isIpLiteral,
  hostMatches, checkNavigation, checkRequest, decidePermission, LNA_PERMISSIONS, BENIGN_PERMISSIONS,
  attachRequestPolicy, attachPermissionPolicy, resolveVerdict, withTimeout,
};
