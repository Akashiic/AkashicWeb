'use strict';
// Small file logger for the host. The host has no console on Windows (GUI subsystem), so the log
// file named by --akw-log is the only post-mortem source; `/web doctor` on the Java side reads it.
// Lines are written synchronously: they are few, and a crash must not lose the last ones.

const fs = require('fs');
const path = require('path');

const DEFAULT_MAX_BYTES = 2 * 1024 * 1024;

/**
 * URLs can carry session tokens in query strings or fragments (OAuth, signed CDN links), so logs keep
 * only scheme, host and path, and cut long paths.
 */
function redactUrl(raw) {
  if (typeof raw !== 'string' || raw === '') return '';
  try {
    const u = new URL(raw);
    if (u.protocol === 'data:' || u.protocol === 'blob:') return u.protocol;
    const p = u.pathname.length > 64 ? u.pathname.slice(0, 64) + '...' : u.pathname;
    return u.protocol + '//' + u.host + p + (u.search || u.hash ? '?…' : '');
  } catch (e) {
    return '<unparseable url>';
  }
}

function createLogger(options = {}) {
  const file = options.file || null;
  const maxBytes = options.maxBytes || DEFAULT_MAX_BYTES;
  const echo = options.echo ? options.echo : null; // a writable (stderr) in dev/test runs
  let fd = null;
  let size = 0;

  const open = () => {
    if (!file) return;
    try {
      fs.mkdirSync(path.dirname(file), { recursive: true });
      fd = fs.openSync(file, 'a');
      size = fs.fstatSync(fd).size;
    } catch (e) {
      fd = null; // logging is best effort: an unwritable log must not stop the browser host
    }
  };

  const rotate = () => {
    try { fs.closeSync(fd); } catch (e) { /* already closed */ }
    fd = null;
    try { fs.renameSync(file, file + '.1'); } catch (e) { /* keep appending to the same file */ }
    open();
  };

  const write = (level, parts) => {
    const msg = parts.map(p => (p instanceof Error ? (p.stack || p.message) : typeof p === 'string' ? p : safeJson(p))).join(' ');
    const line = new Date().toISOString() + ' ' + level + ' ' + msg + '\n';
    if (echo) {
      try { echo.write('[akw-host] ' + line); } catch (e) { /* closed stderr */ }
    }
    if (fd == null) return;
    try {
      const buf = Buffer.from(line, 'utf8');
      fs.writeSync(fd, buf);
      size += buf.length;
      if (size > maxBytes) rotate();
    } catch (e) {
      fd = null;
    }
  };

  /** Text written to stdout/stderr (captureStdio): file only, the caller decides about echoing. */
  const raw = (source, text) => {
    if (fd == null) return;
    const body = String(text).replace(/\n+$/, '');
    if (!body) return;
    try {
      const buf = Buffer.from(new Date().toISOString() + ' ' + (source === 'stdout' ? 'OUT' : 'ERR') + ' ' + body + '\n', 'utf8');
      fs.writeSync(fd, buf);
      size += buf.length;
      if (size > maxBytes) rotate();
    } catch (e) {
      fd = null;
    }
  };

  open();
  return {
    info: (...a) => write('INFO', a),
    warn: (...a) => write('WARN', a),
    error: (...a) => write('ERROR', a),
    raw,
    close: () => {
      if (fd != null) {
        try { fs.closeSync(fd); } catch (e) { /* already closed */ }
        fd = null;
      }
    },
  };
}

function safeJson(v) {
  try { return JSON.stringify(v); } catch (e) { return String(v); }
}

/**
 * The host owns its output (M2 writes the host's stdout/stderr to a file it does not cap): everything
 * JavaScript writes to process.stdout/process.stderr (Electron and Node warnings, stray console output)
 * goes into the size-capped --akw-log file instead. With `echo` (development and test runs only) it is
 * also passed through to the real stream. Native writes by Chromium to the same file descriptors cannot
 * be caught from JavaScript: main.js turns Chromium's own logging down to FATAL (--log-level=3).
 * Returns a function that restores the streams.
 */
function captureStdio(logger, options = {}) {
  const streams = options.streams || { stdout: stdStream('stdout'), stderr: stdStream('stderr') };
  const restore = [];
  for (const name of Object.keys(streams)) {
    const stream = streams[name];
    if (!stream || typeof stream.write !== 'function') continue;
    const original = stream.write;
    const pass = options.echo ? original.bind(stream) : null;
    stream.write = function write(chunk, encoding, callback) {
      try {
        logger.raw(name, Buffer.isBuffer(chunk) ? chunk.toString('utf8') : String(chunk));
      } catch (e) { /* logging is best effort */ }
      if (pass) return pass(chunk, encoding, callback);
      const cb = typeof encoding === 'function' ? encoding : callback;
      if (typeof cb === 'function') process.nextTick(cb);
      return true;
    };
    restore.push(() => { stream.write = original; });
  }
  return () => restore.forEach(r => r());
}

/**
 * process.stdout/stderr, or null: a GUI-subsystem executable on Windows may have no usable handle, and
 * Node creates these streams lazily, so the first access can throw there.
 */
function stdStream(name) {
  try {
    return process[name] || null;
  } catch (e) {
    return null;
  }
}

/** Logger that drops everything, for unit tests. */
const NULL_LOG = Object.freeze({ info() {}, warn() {}, error() {}, raw() {}, close() {} });

module.exports = { createLogger, captureStdio, stdStream, redactUrl, NULL_LOG };
