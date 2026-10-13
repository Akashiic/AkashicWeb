'use strict';
// A tiny stand-in for the Minecraft client's HostSupervisor, used by the integration test: listens on
// 127.0.0.1:0, spawns AkashicWebHost (the official Electron binary given by AKW_ELECTRON) the way the
// Java side will, writes the token to stdin, and records every message the host sends.

const net = require('net');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');
const { spawn } = require('child_process');
const P = require('../src/protocol');

const HOST_DIR = path.join(__dirname, '..');

function childEnv() {
  // Same rule as HostSupervisor (ADR-001 amendment): no NODE_* / ELECTRON_* leaks into the host.
  const env = {};
  for (const [k, v] of Object.entries(process.env)) {
    if (/^(NODE_|ELECTRON_)/i.test(k)) continue;
    env[k] = v;
  }
  return env;
}

function haveCommand(name) {
  return (process.env.PATH || '').split(path.delimiter).some(dir => {
    try {
      fs.accessSync(path.join(dir, name), fs.constants.X_OK);
      return true;
    } catch (e) {
      return false;
    }
  });
}

class Game {
  constructor() {
    this.canvases = new Map(); // screenId -> {width, height, data}: what the Java reader would hold
    this.messages = [];
    this.waiters = [];
    this.socket = null;
    this.seq = 0;
    this.token = crypto.randomBytes(16).toString('hex');
    this.dir = fs.mkdtempSync(path.join(os.tmpdir(), 'akw-host-test-'));
  }

  listen() {
    return new Promise(resolve => {
      this.server = net.createServer(sock => {
        if (this.socket) {
          sock.destroy(); // exactly one connection, like the Java side
          return;
        }
        this.socket = sock;
        const dec = new P.Decoder(m => this._onMessage(m));
        sock.on('data', d => dec.feed(d));
        sock.on('error', () => {});
      });
      this.server.listen(0, '127.0.0.1', () => resolve(this.server.address().port));
    });
  }

  /**
   * Spawns the host. Under Linux CI there is usually no display, so it runs under xvfb-run.
   * --no-sandbox is added only here, only when running as root on Linux (Chromium refuses to start a
   * sandbox as root); the shipped host never gets it and refuses it on Windows (args.js).
   */
  spawnHost(port, options = {}) {
    const electron = process.env.AKW_ELECTRON;
    const args = [
      options.hostDir || HOST_DIR,
      '--akw-port=' + port,
      '--akw-parent-pid=' + (options.parentPid != null ? options.parentPid : process.pid),
      '--akw-user-data=' + path.join(this.dir, 'profile'),
      '--akw-log=' + path.join(this.dir, 'host.log'),
    ].concat(options.extraArgs || []);
    if (process.platform === 'linux' && process.getuid && process.getuid() === 0) args.push('--no-sandbox');
    let cmd = electron;
    let cmdArgs = args;
    if (process.platform === 'linux' && !process.env.DISPLAY && haveCommand('xvfb-run')) {
      cmd = 'xvfb-run';
      cmdArgs = ['-a', '-s', '-screen 0 1920x1080x24', electron].concat(args);
    }
    this.child = spawn(cmd, cmdArgs, { env: Object.assign(childEnv(), options.env || {}), stdio: ['pipe', 'pipe', 'pipe'] });
    this.stderr = '';
    this.child.stderr.on('data', d => { this.stderr += d.toString(); });
    this.child.stdout.on('data', d => { this.stderr += d.toString(); });
    this.exited = new Promise(resolve => this.child.on('exit', (code, signal) => resolve({ code, signal })));
    // token: false = stdin closed empty; 'hold' = stdin left open and silent (the host waits for its
    // stdin timeout, then falls back to AKW_TOKEN); otherwise the token line, then EOF.
    if (options.token === 'hold') return this.child;
    if (options.token !== false) this.child.stdin.write((options.token || this.token) + '\n');
    this.child.stdin.end();
    return this.child;
  }

  /** Applies a FRAME crop to the screen's canvas, exactly as the Java reader thread does (ADR-004). */
  _applyFrame(m) {
    const b = m.payload;
    const [width, height, x, y, w, h] = [0, 2, 4, 6, 8, 10].map(o => b.readUInt16BE(o));
    let c = this.canvases.get(m.screenId);
    if (!c || c.width !== width || c.height !== height) {
      c = { width, height, data: Buffer.alloc(width * height * 4) };
      this.canvases.set(m.screenId, c);
    }
    for (let r = 0; r < h; r++) b.copy(c.data, ((y + r) * width + x) * 4, 12 + r * w * 4, 12 + (r + 1) * w * 4);
  }

  send(type, screenId, payload) {
    const parts = P.encode(type, screenId, this.seq++, payload);
    for (const p of parts) this.socket.write(p);
  }

  /** Position in the message log; waitFor(..., mark) only looks at messages received after it. */
  mark() {
    return this.messages.length;
  }

  _onMessage(m) {
    m.at = Date.now();
    m.idx = this.messages.length;
    if (m.type === P.T.FRAME) this._applyFrame(m);
    if (m.type !== P.T.FRAME && m.type !== P.T.PCM) {
      try { m.json = JSON.parse(m.payload.toString('utf8')); } catch (e) { m.json = null; }
    }
    this.messages.push(m);
    this.waiters = this.waiters.filter(w => {
      if (m.idx >= w.since && w.pred(m)) {
        clearTimeout(w.timer);
        w.resolve(m);
        return false;
      }
      return true;
    });
  }

  /** Resolves with the first message (already received after mark `since`, or future) matching `pred`. */
  waitFor(pred, timeoutMs = 10000, label = 'message', since = 0) {
    const hit = this.messages.find(m => m.idx >= since && pred(m));
    if (hit) return Promise.resolve(hit);
    return new Promise((resolve, reject) => {
      const w = { pred, resolve, since };
      w.timer = setTimeout(() => {
        this.waiters = this.waiters.filter(x => x !== w);
        const recent = this.messages.filter(m => m.type === P.T.STATE || m.type === P.T.EVENT).slice(-4).map(m => P.typeName(m.type) + ' ' + JSON.stringify(m.json));
        reject(new Error('timed out waiting for ' + label + '\n--- last STATE/EVENT ---\n' + recent.join('\n') + '\n--- host log ---\n' + this.hostLog().slice(-4000) + '\n--- stderr ---\n' + this.stderr.slice(-3000)));
      }, timeoutMs);
      this.waiters.push(w);
    });
  }

  hostLog() {
    try {
      return fs.readFileSync(path.join(this.dir, 'host.log'), 'utf8');
    } catch (e) {
      return '';
    }
  }

  async close() {
    if (this.socket) this.socket.destroy();
    if (this.server) this.server.close();
    if (this.child && this.child.exitCode == null && this.child.signalCode == null) this.child.kill('SIGKILL');
  }
}

/** Linux only: PIDs of every descendant of `root` (from /proc), not including `root`. */
function descendants(root) {
  const parents = new Map();
  for (const d of fs.readdirSync('/proc')) {
    if (!/^\d+$/.test(d)) continue;
    try {
      const st = fs.readFileSync('/proc/' + d + '/stat', 'utf8');
      parents.set(Number(d), Number(st.slice(st.lastIndexOf(')') + 2).split(' ')[1]));
    } catch (e) { /* exited meanwhile */ }
  }
  const out = new Set([root]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const [pid, ppid] of parents) {
      if (out.has(ppid) && !out.has(pid)) {
        out.add(pid);
        grew = true;
      }
    }
  }
  out.delete(root);
  return [...out];
}

/** Linux only: one {pid, type, rssKb, env} record per descendant process of `root`. */
function processTree(root) {
  const out = [];
  for (const pid of descendants(root)) {
    try {
      // Chromium children rewrite their title, so arguments may be separated by spaces, not NULs.
      const cmd = fs.readFileSync('/proc/' + pid + '/cmdline', 'latin1').split(/[\0 ]/);
      const comm = fs.readFileSync('/proc/' + pid + '/comm', 'utf8').trim();
      const status = fs.readFileSync('/proc/' + pid + '/status', 'utf8');
      const typeArg = cmd.find(a => a.startsWith('--type='));
      const sub = cmd.find(a => a.startsWith('--utility-sub-type='));
      const type = typeArg ? typeArg.slice(7) + (sub ? '/' + sub.slice(19) : '') : comm;
      out.push({ pid, type, comm, rssKb: Number((/VmRSS:\s+(\d+)/.exec(status) || [0, 0])[1]), env: fs.readFileSync('/proc/' + pid + '/environ', 'latin1') });
    } catch (e) { /* exited meanwhile */ }
  }
  return out;
}

/** 16-bit stereo PCM WAV with a sine tone, so the test page has real media without any download. */
function toneWav(seconds = 6, rate = 48000, freq = 440) {
  const frames = seconds * rate;
  const data = Buffer.alloc(frames * 4);
  for (let i = 0; i < frames; i++) {
    const v = Math.round(Math.sin((2 * Math.PI * freq * i) / rate) * 0.3 * 32767);
    data.writeInt16LE(v, i * 4);
    data.writeInt16LE(v, i * 4 + 2);
  }
  const h = Buffer.alloc(44);
  h.write('RIFF', 0);
  h.writeUInt32LE(36 + data.length, 4);
  h.write('WAVE', 8);
  h.write('fmt ', 12);
  h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20);
  h.writeUInt16LE(2, 22);
  h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 4, 28);
  h.writeUInt16LE(4, 32);
  h.writeUInt16LE(16, 34);
  h.write('data', 36);
  h.writeUInt32LE(data.length, 40);
  return Buffer.concat([h, data]);
}

module.exports = { Game, toneWav, descendants, processTree };
