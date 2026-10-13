'use strict';
// Validation of game -> host JSON payloads (docs/PROTOCOL.md section 1). The game is trusted code,
// but its values come from players and server packets, so every field is type-checked and clamped
// here once, and the Electron wiring only ever sees normalised values.

const LIMITS = Object.freeze({
  MIN_SIZE: 16,
  MAX_SIZE: 4096,
  MIN_FPS: 1,
  MAX_FPS: 60,
  MAX_URL: 8192,
  MAX_POLICY_ENTRIES: 1024,
  MAX_HOST: 253,
  MAX_UA: 512,
  MAX_NONCE: 64,
});

class MessageError extends Error {
  constructor(code, detail) {
    super(code + (detail ? ': ' + detail : ''));
    this.code = code;
  }
}

function obj(v) {
  if (v == null || typeof v !== 'object' || Array.isArray(v)) throw new MessageError('not-object');
  return v;
}

function int(v, name, min, max) {
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new MessageError('bad-' + name, String(v));
  return Math.min(max, Math.max(min, Math.round(v)));
}

function num(v, name) {
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new MessageError('bad-' + name, String(v));
  return v;
}

function bool(v, dflt) {
  return typeof v === 'boolean' ? v : dflt;
}

function unit(v, name) {
  return Math.min(1, Math.max(0, num(v, name)));
}

function urlString(v) {
  if (v == null) return '';
  if (typeof v !== 'string') throw new MessageError('bad-url');
  if (v.length > LIMITS.MAX_URL) throw new MessageError('url-too-long', String(v.length));
  return v.trim();
}

function oneOf(v, allowed, dflt, name) {
  if (v == null) return dflt;
  const s = String(v).toLowerCase();
  if (!allowed.includes(s)) throw new MessageError('bad-' + name, s);
  return s;
}

/** Normalises a policy host entry: lower case, punycode, no scheme/port/path, no leading "*." or dots. */
function normalizeHostEntry(raw) {
  if (typeof raw !== 'string') return null;
  let h = raw.trim().toLowerCase();
  if (!h || h.length > LIMITS.MAX_HOST) return null;
  h = h.replace(/^\*\./, '').replace(/^\.+/, '').replace(/\.+$/, '');
  if (!h) return null;
  try {
    const u = new URL('http://' + h + '/');
    if (u.pathname !== '/' || u.search || u.hash || u.username || u.port) return null;
    return u.hostname.replace(/\.+$/, '');
  } catch (e) {
    return null;
  }
}

function hostList(v) {
  if (v == null) return [];
  if (!Array.isArray(v)) throw new MessageError('bad-host-list');
  const out = [];
  for (const raw of v.slice(0, LIMITS.MAX_POLICY_ENTRIES)) {
    const h = normalizeHostEntry(raw);
    if (h && !out.includes(h)) out.push(h);
  }
  return out;
}

/** POLICY / WELCOME.policy. Java sends the SitePolicy enum names or the lower-case wire names. */
function parsePolicy(v) {
  if (v == null) return { mode: 'blocklist', allow: [], block: [] };
  const o = obj(v);
  return {
    mode: oneOf(o.mode, ['allowlist', 'blocklist'], 'blocklist', 'mode'),
    allow: hostList(o.allow),
    block: hostList(o.block),
  };
}

function parseLocale(v) {
  return typeof v === 'string' && /^[a-z]{2,3}(-[A-Za-z]{2,4})?$/.test(v) ? v : 'pt-BR';
}

function parseUserAgent(v) {
  return typeof v === 'string' && v.length > 0 && v.length <= LIMITS.MAX_UA && /^[\x20-\x7e]+$/.test(v) ? v : null;
}

function parseWelcome(v) {
  const o = obj(v);
  return {
    protoMajor: int(o.protoMajor, 'protoMajor', 0, 0xffff),
    policy: parsePolicy(o.policy),
    locale: parseLocale(o.locale),
    userAgent: parseUserAgent(o.userAgent),
  };
}

function parseCreate(v) {
  const o = obj(v);
  return {
    width: int(o.width, 'width', LIMITS.MIN_SIZE, LIMITS.MAX_SIZE),
    height: int(o.height, 'height', LIMITS.MIN_SIZE, LIMITS.MAX_SIZE),
    fps: o.fps == null ? 30 : int(o.fps, 'fps', LIMITS.MIN_FPS, LIMITS.MAX_FPS),
    url: urlString(o.url),
    session: oneOf(o.session, ['shared', 'private'], 'shared', 'session'),
    audio: oneOf(o.audio, ['os', 'spatial'], 'os', 'audio'),
    volume: o.volume == null ? 1 : unit(o.volume, 'volume'),
  };
}

const NAV_ACTIONS = ['back', 'forward', 'reload', 'stop'];

function parseNavigate(v) {
  const o = obj(v);
  if (o.action != null) return { action: oneOf(o.action, NAV_ACTIONS, null, 'action') };
  const url = urlString(o.url);
  if (!url) throw new MessageError('empty-url');
  return { url };
}

function parseRender(v) {
  const o = obj(v);
  return { fps: int(o.fps, 'fps', LIMITS.MIN_FPS, LIMITS.MAX_FPS), painting: bool(o.painting, true) };
}

function parseResize(v) {
  const o = obj(v);
  return {
    width: int(o.width, 'width', LIMITS.MIN_SIZE, LIMITS.MAX_SIZE),
    height: int(o.height, 'height', LIMITS.MIN_SIZE, LIMITS.MAX_SIZE),
  };
}

function parseAudio(v) {
  const o = obj(v);
  return { muted: bool(o.muted, false), volume: o.volume == null ? 1 : unit(o.volume, 'volume') };
}

function parseSync(v) {
  const o = obj(v);
  return {
    targetMediaTime: num(o.targetMediaTime, 'targetMediaTime'),
    rate: o.rate == null ? 1 : num(o.rate, 'rate'),
    paused: bool(o.paused, false),
    seek: bool(o.seek, false),
  };
}

function parsePing(v) {
  const o = v == null ? {} : obj(v);
  const n = o.nonce;
  if (typeof n === 'number' && Number.isFinite(n)) return { nonce: n };
  if (typeof n === 'string' && n.length <= LIMITS.MAX_NONCE) return { nonce: n };
  return { nonce: null };
}

/**
 * A stock Chrome user agent for the running Chromium (reduced-UA format: only the major version).
 * Sites such as YouTube degrade or refuse unknown browsers, and the default Electron UA names the app.
 */
function stockChromeUserAgent(platform, chromeVersion) {
  const major = String(chromeVersion || '').split('.')[0] || '152';
  const os = platform === 'win32' ? 'Windows NT 10.0; Win64; x64'
    : platform === 'darwin' ? 'Macintosh; Intel Mac OS X 10_15_7'
      : 'X11; Linux x86_64';
  return 'Mozilla/5.0 (' + os + ') AppleWebKit/537.36 (KHTML, like Gecko) Chrome/' + major + '.0.0.0 Safari/537.36';
}

/** Accept-Language for a locale, e.g. pt-BR -> "pt-BR,pt,en-US,en". */
function acceptLanguages(locale) {
  const out = [locale];
  const base = locale.split('-')[0];
  if (base !== locale) out.push(base);
  for (const l of ['en-US', 'en']) if (!out.includes(l)) out.push(l);
  return out.join(',');
}

module.exports = {
  LIMITS, MessageError, normalizeHostEntry, parsePolicy, parseWelcome, parseCreate, parseNavigate, parseRender,
  parseResize, parseAudio, parseSync, parsePing, parseLocale, parseUserAgent, stockChromeUserAgent, acceptLanguages,
};
