'use strict';
// Command line of AkashicWebHost (docs/PROTOCOL.md section 1, "Transport and handshake").
// Pure functions: no Electron import, so they are unit-tested under plain Node.

const path = require('path');

/** Process exit codes. HostSupervisor (Java) maps them to reason codes; keep them stable. */
const EXIT = Object.freeze({
  OK: 0,
  BAD_ARGS: 64,        // missing or invalid --akw-* argument
  FORBIDDEN_SWITCH: 65, // a debugging or security-weakening switch was present
  NO_TOKEN: 66,        // no valid token on stdin nor in AKW_TOKEN
  CONNECT_FAILED: 67,  // could not reach 127.0.0.1:<port>
  HANDSHAKE: 68,       // no WELCOME, or protocol major mismatch
  PROTOCOL: 69,        // the game sent bytes that are not protocol v1
  INTERNAL: 70,        // uncaught exception in the main process
});

class ArgError extends Error {
  constructor(code, detail) {
    super(code + (detail ? ': ' + detail : ''));
    this.code = code;
  }
}

function argValue(argv, name) {
  const prefix = '--' + name + '=';
  let found = null;
  for (const a of argv) {
    if (typeof a === 'string' && a.startsWith(prefix)) found = a.slice(prefix.length);
  }
  return found;
}

function hasFlag(argv, name) {
  return argv.some(a => a === '--' + name || a === '--' + name + '=1' || a === '--' + name + '=true');
}

function positiveInt(raw, max) {
  if (raw == null || !/^[0-9]{1,10}$/.test(raw)) return null;
  const n = Number(raw);
  return n >= 1 && n <= max ? n : null;
}

/**
 * Parses the --akw-* arguments. Throws ArgError on anything missing or malformed: the host must not
 * guess where to connect or where to store a profile.
 */
function parseArgs(argv) {
  const port = positiveInt(argValue(argv, 'akw-port'), 65535);
  if (port == null) throw new ArgError('bad-port', argValue(argv, 'akw-port') || 'missing');
  // 0 means "unknown" (the game could not read its own PID): the host then skips the parent poll.
  const rawPid = argValue(argv, 'akw-parent-pid');
  const parentPid = rawPid === '0' ? 0 : positiveInt(rawPid, 0x7fffffff);
  if (parentPid == null) throw new ArgError('bad-parent-pid', rawPid || 'missing');
  const userData = argValue(argv, 'akw-user-data');
  if (!userData || !path.isAbsolute(userData)) throw new ArgError('bad-user-data', userData || 'missing');
  const logFile = argValue(argv, 'akw-log');
  if (logFile != null && logFile !== '' && !path.isAbsolute(logFile)) throw new ArgError('bad-log', logFile);
  return {
    port,
    parentPid,
    userData: path.normalize(userData),
    logFile: logFile ? path.normalize(logFile) : null,
    // GPU OSR vs software OSR is decided by the G1-E measurements (ADR-002 amendment); software is the
    // default because it is the configuration the Linux probe verified.
    gpu: hasFlag(argv, 'akw-gpu'),
    // Test-only: lets file: pages load so the integration test needs no network. main.js honours it
    // only when the executable is not the renamed production host (app.isPackaged === false).
    testAllowFile: hasFlag(argv, 'akw-test-allow-file'),
  };
}

// Two rules, chosen by main.js from app.isPackaged:
//
// PACKAGED (the renamed AkashicWebHost(.exe) running from resources/app of an installed engine, i.e.
// every player's host): an ALLOWLIST. Java passes nothing but --akw-* switches, so any other argument
// (a Chromium switch, a Node option, a positional argument) means the launch was tampered with and the
// host refuses to run. A denylist cannot keep up with Chromium's switches (--host-resolver-rules,
// --proxy-server, --ignore-certificate-errors, --renderer-cmd-prefix, ...).
//
// UNPACKAGED (`electron <dir>`: development and the integration test, which needs --no-sandbox as root,
// --host-resolver-rules, --proxy-server and --unsafely-treat-insecure-origin-as-secure for its local
// http test site): a DENYLIST of the switches that open a debugging channel, launch other code or
// switch web security off.
const AKW_SWITCH_RE = /^--akw-[a-z0-9-]+(=[\s\S]*)?$/;
const FORBIDDEN_EVERYWHERE = [
  'remote-debugging-port', 'remote-debugging-pipe', 'remote-debugging-address', 'remote-allow-origins',
  'inspect', 'inspect-brk', 'inspect-port', 'inspect-publish-uid', 'debug', 'debug-brk',
  'disable-web-security', 'allow-file-access-from-files', 'user-data-dir', 'load-extension',
  'ignore-certificate-errors', 'ignore-certificate-errors-spki-list', 'disable-site-isolation-trials',
  'renderer-cmd-prefix', 'gpu-launcher', 'utility-cmd-prefix', 'browser-subprocess-path', 'js-flags',
];
// The renderer sandbox is a hard requirement on Windows (ADR-013). The Linux test harness needs
// --no-sandbox when running as root, so in unpackaged mode it is only forbidden on win32.
const FORBIDDEN_ON_WINDOWS = ['no-sandbox', 'disable-gpu-sandbox', 'no-zygote'];
// Environment variables that make Electron/Node log to stdout/stderr without a cap or change how the
// app runs. Java builds the host environment without any NODE_*/ELECTRON_* variable (ADR-001
// amendment), so in a packaged host their presence is a broken or tampered launch.
const FORBIDDEN_ENV_PACKAGED = ['ELECTRON_ENABLE_LOGGING', 'ELECTRON_LOG_FILE', 'ELECTRON_ENABLE_STACK_DUMPING',
  'ELECTRON_RUN_AS_NODE', 'NODE_OPTIONS', 'NODE_DEBUG'];

/** Name of a switch argument ("--x=1" -> "x"), for logs that must not echo values. */
function switchName(raw) {
  const m = /^(?:--|-|\/)([A-Za-z0-9_-]+)/.exec(raw);
  return m ? m[1] : '<argument>';
}

/**
 * Arguments the host must refuse, as names safe to log. `args` is process.argv without argv[0] (the
 * executable) plus process.execArgv; options: packaged, platform.
 */
function findDisallowedArgs(args, options) {
  const opts = options || {};
  if (!opts.packaged) return findForbiddenSwitches(args, opts.platform);
  const found = [];
  for (const raw of args) {
    if (typeof raw === 'string' && AKW_SWITCH_RE.test(raw)) continue;
    const name = typeof raw === 'string' ? switchName(raw) : '<argument>';
    if (!found.includes(name)) found.push(name);
  }
  return found;
}

/** Names of forbidden environment variables (packaged hosts only). */
function findForbiddenEnv(env, packaged) {
  if (!packaged || !env) return [];
  return FORBIDDEN_ENV_PACKAGED.filter(k => Object.prototype.hasOwnProperty.call(env, k));
}

/** Returns the forbidden switch names present in argv (unpackaged denylist rule). */
function findForbiddenSwitches(argv, platform) {
  const names = FORBIDDEN_EVERYWHERE.concat(platform === 'win32' ? FORBIDDEN_ON_WINDOWS : []);
  const win = platform === 'win32';
  const found = [];
  for (const raw of argv) {
    if (typeof raw !== 'string') continue;
    // Chromium accepts "--x", "-x" and, on Windows, "/x"; switch names are case-insensitive on Windows.
    const m = /^(?:--|-|\/)([A-Za-z0-9-]+)(?:=.*)?$/s.exec(raw);
    if (!m || (raw.startsWith('/') && !win)) continue;
    const name = win ? m[1].toLowerCase() : m[1];
    if (names.includes(name) && !found.includes(name)) found.push(name);
  }
  return found;
}

module.exports = { EXIT, ArgError, parseArgs, findForbiddenSwitches, findDisallowedArgs, findForbiddenEnv };
