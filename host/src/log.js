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

  open();
  return {
    info: (...a) => write('INFO', a),
    warn: (...a) => write('WARN', a),
    error: (...a) => write('ERROR', a),
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

/** Logger that drops everything, for unit tests. */
const NULL_LOG = Object.freeze({ info() {}, warn() {}, error() {}, close() {} });

module.exports = { createLogger, redactUrl, NULL_LOG };
