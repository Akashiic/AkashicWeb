'use strict';
// First statement on purpose: the AKW_TOKEN fallback is copied and removed from the environment before
// any other code runs, so no child process that Chromium starts later can inherit it (token.js).
const ENV_TOKEN = require('./token').takeEnvToken(process.env);

// AkashicWebHost: the out-of-process browser of the AkashicWeb mod (ADR-001/002). It runs inside the
// official, unmodified Electron build from resources/app/, renders every in-world screen offscreen
// and talks to exactly one Minecraft client over one loopback TCP connection (docs/PROTOCOL.md).
//
// Lifetime: the host lives only as long as the game. It quits on socket EOF, when the parent PID
// disappears (polled every 2 s), on QUIT, and on any handshake or framing failure. Exit codes are in
// args.js (EXIT) so the game can map them to reason codes.

const fs = require('fs');
const net = require('net');
const path = require('path');
const crypto = require('crypto');
const { app, Menu } = require('electron');
const pkg = require('../package.json');
const P = require('./protocol');
const { EXIT, parseArgs, findForbiddenSwitches } = require('./args');
const { readToken } = require('./token');
const { createLogger } = require('./log');
const { Link } = require('./link');
const { FrameScheduler, imagePayloads } = require('./frames');
const { Router } = require('./router');
const { stockChromeUserAgent } = require('./messages');
const { monoMs } = require('./clock');
const { CaptureRegistry } = require('./capture');
const { SessionFactory } = require('./sessions');
const { ScreenManager } = require('./screens');

const ISOLATED_WORLD_ID = 1279; // >= 1000 as Electron recommends; 999 is contextIsolation's world
const PARENT_POLL_MS = 2000;
const CONNECT_TIMEOUT_MS = 5000;
const WELCOME_TIMEOUT_MS = 10000;
const STATS_EVERY_MS = 60000;
const EXIT_GRACE_MS = 3000;

// Runtime state, declared before the startup code below runs main().
const state = {
  link: null,
  screens: null,
  scheduler: null,
  shuttingDown: false,
  timers: [],
  policy: { mode: 'blocklist', allow: [], block: [] },
  userAgent: null,
  locale: 'pt-BR',
  frameErrors: 0,
};

// ---------------------------------------------------------------------------------------------------
// Synchronous setup: everything that must happen before Electron's 'ready'.

let args;
try {
  args = parseArgs(process.argv);
} catch (err) {
  process.stderr.write('AkashicWebHost: ' + err.message + '\n');
  app.exit(EXIT.BAD_ARGS);
}

const log = createLogger({ file: args && args.logFile, echo: app.isPackaged ? null : process.stderr });

// Electron shows a modal error dialog for uncaught main-process exceptions; on a player's PC that
// would pop over the game, so log and exit instead and let the game relaunch us. Installed first so
// that it also covers the setup below.
process.on('uncaughtException', err => {
  log.error('uncaught exception', err);
  shutdown('internal-error', EXIT.INTERNAL);
});
process.on('unhandledRejection', err => log.warn('unhandled rejection', err));
for (const sig of ['SIGTERM', 'SIGINT', 'SIGHUP']) process.on(sig, () => shutdown('signal-' + sig, EXIT.OK));

const forbidden = args ? findForbiddenSwitches(process.argv.concat(process.execArgv || []), process.platform) : [];
const allowFile = !!(args && args.testAllowFile && !app.isPackaged);

if (args && forbidden.length > 0) {
  log.error('refusing to run with forbidden switches: ' + forbidden.join(', '));
  app.exit(EXIT.FORBIDDEN_SWITCH);
} else if (args && configureApp()) {
  main().catch(err => {
    log.error('startup failed', err);
    shutdown('internal-error', EXIT.INTERNAL);
  });
}

function configureApp() {
  if (args.testAllowFile && !allowFile) log.warn('--akw-test-allow-file ignored: packaged host');
  try {
    fs.mkdirSync(args.userData, { recursive: true });
  } catch (err) {
    log.error('cannot create the profile directory: ' + err.message);
    app.exit(EXIT.BAD_ARGS);
    return false;
  }
  app.setPath('userData', args.userData);
  app.setPath('sessionData', path.join(args.userData, 'session'));
  app.setPath('crashDumps', path.join(args.userData, 'crashes'));
  app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
  // Media keys and the OS media overlay must not reach in-world screens (a player's keyboard play
  // key would toggle a public screen, and Windows would show our page title in its overlay).
  app.commandLine.appendSwitch('disable-features', 'HardwareMediaKeyHandling,MediaSessionService');
  app.commandLine.appendSwitch('lang', 'pt-BR'); // WELCOME's locale also sets Accept-Language per session
  if (!args.gpu) app.disableHardwareAcceleration();
  // Every renderer sandboxed, whatever a window asks for (ADR-013). The one exception is a Linux
  // tier-2 / test launch with --no-sandbox (refused on Windows by args.js): Chromium then cannot
  // start sandboxed utility processes as root and the network service would crash in a loop.
  if (!app.commandLine.hasSwitch('no-sandbox')) app.enableSandbox();
  app.userAgentFallback = stockChromeUserAgent(process.platform, process.versions.chrome);


  app.on('window-all-closed', () => { /* zero screens is a normal state: keep running */ });
  app.on('web-contents-created', (event, wc) => {
    // Defaults for any webContents, including ones Chromium creates on its own; screens.js and
    // capture.js replace the window-open handler with their own right after creation.
    wc.setWindowOpenHandler(() => ({ action: 'deny' }));
    wc.on('will-attach-webview', e => e.preventDefault());
  });
  app.on('login', (event, wc, details, authInfo, callback) => {
    event.preventDefault();
    callback();
  });
  app.on('select-client-certificate', (event, wc, url, list, callback) => {
    event.preventDefault();
    callback();
  });
  app.on('child-process-gone', (event, details) => childGone(details));
  return true;
}

// ---------------------------------------------------------------------------------------------------
// Runtime

async function main() {
  log.info('AkashicWebHost ' + pkg.version + ' electron ' + process.versions.electron + ' chrome ' + process.versions.chrome
    + ' ' + process.platform + '-' + process.arch + (args.gpu ? ' gpu' : ' software') + (allowFile ? ' TEST-allow-file' : ''));
  watchParent();
  const tokenResult = await readToken(stdinOrNull(), { AKW_TOKEN: ENV_TOKEN });
  if (!tokenResult) {
    log.error('no valid token on stdin or in AKW_TOKEN');
    return shutdown('no-token', EXIT.NO_TOKEN);
  }
  log.info('token received via ' + tokenResult.source);
  await app.whenReady();
  if (state.shuttingDown) return undefined;
  Menu.setApplicationMenu(null); // no default menu, hence no DevTools/reload accelerators

  let socket;
  try {
    socket = await connect(args.port);
  } catch (err) {
    log.error('cannot connect to 127.0.0.1:' + args.port + ': ' + err.message);
    return shutdown('connect-failed', EXIT.CONNECT_FAILED);
  }
  wire(socket, tokenResult.token);
  return undefined;
}

function wire(socket, token) {
  const registry = new CaptureRegistry();
  // Declared before the link so its callbacks never see them uninitialised; socket data is only
  // delivered after this function returns.
  let router = null;
  let welcomeTimer = null;
  const link = new Link(socket, {
    onMessage: msg => router.route(msg),
    onDrain: () => state.scheduler.flush(),
    onClose: reason => shutdown('socket-' + reason, EXIT.OK),
    onProtocolError: err => {
      log.error('protocol error from game: ' + err.message);
      shutdown('protocol-error', EXIT.PROTOCOL);
    },
  });
  state.link = link;
  state.scheduler = new FrameScheduler({ blocked: () => link.blocked || link.closed, send: sendFrame });
  const sessions = new SessionFactory({
    policy: () => state.policy,
    allowFile,
    userAgent: () => state.userAgent || app.userAgentFallback,
    locale: () => state.locale,
    registry,
    log,
    onBlocked: (wcId, info) => {
      const s = state.screens.byWebContentsId(wcId);
      if (s) s.blocked({ url: info.url, host: info.host, reason: info.reason }, 'request-' + info.resourceType);
      else log.info('blocked request outside a screen: ' + info.reason);
    },
    onInternalIp: (wcId, info) => {
      const s = state.screens.byWebContentsId(wcId);
      log.warn('internal address reached: ' + info.ip + ' (' + info.resourceType + ')');
      if (s) s.internalIpReached(info);
    },
    onDenied: (wcId, permission) => {
      const s = state.screens.byWebContentsId(wcId);
      if (s) state.screens.emitEvent(s.id, 'permission-denied', { permission });
    },
    onDownload: (wcId, info) => {
      const s = state.screens.byWebContentsId(wcId);
      if (s) state.screens.emitEvent(s.id, 'download-cancelled', { filename: info.filename });
    },
  });
  state.screens = new ScreenManager({
    link,
    scheduler: state.scheduler,
    sessions,
    registry,
    policy: () => state.policy,
    allowFile,
    worldId: ISOLATED_WORLD_ID,
    // Per-run random key for the state that host scripts keep inside pages (inject.js).
    pageKey: '__akw' + crypto.randomBytes(8).toString('hex'),
    log,
  });
  router = new Router({
    screens: state.screens,
    log,
    onWelcome: welcome => {
      clearTimeout(welcomeTimer);
      if (welcome.protoMajor !== P.PROTO_MAJOR) {
        log.error('protocol major mismatch: game ' + welcome.protoMajor + ', host ' + P.PROTO_MAJOR);
        return shutdown('protocol-mismatch', EXIT.HANDSHAKE);
      }
      state.policy = welcome.policy;
      state.locale = welcome.locale;
      state.userAgent = welcome.userAgent || app.userAgentFallback;
      app.userAgentFallback = state.userAgent;
      log.info('welcome: policy ' + welcome.policy.mode + ' (' + welcome.policy.allow.length + ' allow, '
        + welcome.policy.block.length + ' block), locale ' + welcome.locale);
      return undefined;
    },
    onPolicy: policy => {
      state.policy = policy;
      state.screens.setPolicyNow();
    },
    onPing: (screenId, nonce) => link.send(P.T.PONG, screenId, { nonce, hostTimeMs: monoMs() }),
    onQuit: () => shutdown('quit', EXIT.OK),
  });
  welcomeTimer = setTimeout(() => shutdown('welcome-timeout', EXIT.HANDSHAKE), WELCOME_TIMEOUT_MS);
  state.timers.push(welcomeTimer);
  state.timers.push(setInterval(logStats, STATS_EVERY_MS));

  link.send(P.T.HELLO, 0, {
    token,
    // The game's stale-host sweep needs the host's own PID; Java 8 on Windows cannot read a child's PID.
    pid: process.pid,
    protoMajor: P.PROTO_MAJOR,
    protoMinor: P.PROTO_MINOR,
    hostVersion: pkg.version,
    engine: 'electron',
    engineVersion: process.versions.electron,
    chromeVersion: process.versions.chrome,
    platform: process.platform + '-' + process.arch,
  });
  log.info('connected, HELLO sent');
}

function sendFrame(screenId, image, rect) {
  const link = state.link;
  try {
    const size = image.getSize();
    const payloads = imagePayloads(image, size.width, size.height, rect);
    let ok = true;
    for (const parts of payloads) ok = link.send(P.T.FRAME, screenId, parts);
    return ok;
  } catch (err) {
    if (++state.frameErrors <= 5) log.warn('frame dropped: ' + err.message);
    return !link.blocked;
  }
}

function logStats() {
  const s = state.scheduler ? state.scheduler.stats : {};
  log.info('stats: screens=' + (state.screens ? state.screens.screens.size : 0) + ' frames offered=' + s.offered
    + ' sent=' + s.sent + ' merged=' + s.merged + ' bytesOut=' + (state.link ? state.link.bytesOut : 0)
    + ' eventsDropped=' + (state.screens ? state.screens.limiter.dropped : 0));
}

// A GPU or network service crash is restarted by Chromium; a crash loop means the host cannot work,
// so it exits and lets the game's backoff and crash-loop guard take over (ADR-001).
const childCrashes = [];
function childGone(details) {
  const now = Date.now();
  childCrashes.push(now);
  while (childCrashes.length && now - childCrashes[0] > 10000) childCrashes.shift();
  if (childCrashes.length <= 3) log.warn('child process gone', JSON.stringify(details));
  if (childCrashes.length >= 10) shutdown('child-crash-loop-' + (details && details.type), EXIT.INTERNAL);
}

function connect(port) {
  return new Promise((resolve, reject) => {
    const socket = net.connect({ host: '127.0.0.1', port });
    const timer = setTimeout(() => {
      socket.destroy();
      reject(new Error('connect timeout'));
    }, CONNECT_TIMEOUT_MS);
    socket.once('connect', () => {
      clearTimeout(timer);
      resolve(socket);
    });
    socket.once('error', err => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

function stdinOrNull() {
  try {
    return process.stdin;
  } catch (err) {
    return null; // no stdin handle (possible for a GUI-subsystem exe); AKW_TOKEN is the fallback
  }
}

function watchParent() {
  if (args.parentPid === 0) {
    // 0 = the game could not tell its own PID: rely on socket EOF alone.
    log.info('parent PID unknown: parent poll disabled');
    return;
  }
  const check = () => {
    try {
      process.kill(args.parentPid, 0);
    } catch (err) {
      // EPERM means the process exists but belongs to someone else: still alive.
      if (err.code === 'ESRCH') shutdown('parent-gone', EXIT.OK);
    }
  };
  check();
  state.timers.push(setInterval(check, PARENT_POLL_MS));
}

function shutdown(reason, code) {
  if (state.shuttingDown) return;
  state.shuttingDown = true;
  log.info('shutdown: ' + reason + ' (exit ' + code + ')');
  for (const t of state.timers) clearInterval(t);
  try {
    if (state.screens) state.screens.destroyAll();
  } catch (err) {
    log.error('destroying screens failed', err);
  }
  let finished = false;
  const finish = () => {
    if (finished) return;
    finished = true;
    log.close();
    app.exit(code);
  };
  setTimeout(finish, EXIT_GRACE_MS).unref();
  const link = state.link;
  if (link && !link.closed) {
    link.send(P.T.BYE, 0, { reason });
    link.end(finish);
  } else {
    finish();
  }
}
