'use strict';
// End-to-end test of AkashicWebHost inside the official Electron binary. Skipped unless AKW_ELECTRON
// points at the electron executable, e.g.
//   AKW_ELECTRON=/path/to/electron-v44.7.0-linux-x64/electron node --test test/integration.test.js
// Evidence grade of a pass: AUTOMATED, Linux/Xvfb only. It says nothing about Windows.

const test = require('node:test');
const assert = require('node:assert');
const fs = require('fs');
const net = require('net');
const path = require('path');
const { spawn } = require('child_process');
const { pathToFileURL } = require('url');
const P = require('../src/protocol');
const { Game, toneWav } = require('./harness');

const SKIP = process.env.AKW_ELECTRON ? false : 'AKW_ELECTRON is not set';
const W = 640;
const H = 360;
const STOCK_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36';

function frames(game, screenId, since) {
  return game.messages.filter(m => m.type === P.T.FRAME && m.screenId === screenId && m.idx >= since);
}

function frameHeader(m) {
  const b = m.payload;
  return { width: b.readUInt16BE(0), height: b.readUInt16BE(2), x: b.readUInt16BE(4), y: b.readUInt16BE(6), w: b.readUInt16BE(8), h: b.readUInt16BE(10) };
}

function sleep(ms) {
  return new Promise(r => setTimeout(r, ms));
}

/**
 * A loopback "router" that answers like a LAN web UI. Only the response detector test may reach it;
 * every other path must be refused before a connection is made.
 */
function sink() {
  return new Promise(resolve => {
    const s = { hits: 0 };
    s.server = net.createServer(c => {
      s.hits++;
      c.on('error', () => {});
      c.once('data', () => c.end('HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 6\r\nConnection: close\r\n\r\nrouter'));
    });
    s.server.listen(0, '127.0.0.1', () => { s.port = s.server.address().port; resolve(s); });
  });
}

function titleOf(game, screenId, pred, label, timeoutMs = 8000, since = 0) {
  return game.waitFor(m => m.type === P.T.STATE && m.screenId === screenId && m.json && pred(m.json.title || ''), timeoutMs, label, since);
}

function prepareSite(game) {
  const site = path.join(game.dir, 'site');
  fs.mkdirSync(site, { recursive: true });
  for (const f of fs.readdirSync(path.join(__dirname, 'fixtures'))) fs.copyFileSync(path.join(__dirname, 'fixtures', f), path.join(site, f));
  fs.writeFileSync(path.join(site, 'tone.wav'), toneWav());
  return pathToFileURL(path.join(site, 'page.html')).href;
}

test('host end to end (Linux/Xvfb)', { skip: SKIP, timeout: 180000 }, async t => {
  const game = new Game();
  const port = await game.listen();
  const pageUrl = prepareSite(game);
  const internal = await sink();
  const results = {};
  t.after(async () => {
    await game.close();
    internal.server.close();
    // Numbers for the report; the assertions below are the actual pass criteria.
    console.log('# integration numbers: ' + JSON.stringify(results));
  });

  const t0 = Date.now();
  game.spawnHost(port, { extraArgs: ['--akw-test-allow-file', '--host-resolver-rules=MAP rebind.akw.test 127.0.0.1, MAP slow.akw.test 93.184.216.34'] });

  await t.test('HELLO carries the stdin token and the engine identity', async () => {
    const hello = await game.waitFor(m => m.type === P.T.HELLO, 20000, 'HELLO');
    results.helloMs = hello.at - t0;
    assert.strictEqual(hello.json.token, game.token);
    assert.strictEqual(hello.json.protoMajor, P.PROTO_MAJOR);
    assert.strictEqual(hello.json.engine, 'electron');
    assert.match(hello.json.engineVersion, /^44\./);
    assert.match(hello.json.chromeVersion, /^152\./);
    // HELLO.pid is the host's own browser process (the stale-host sweep needs it on Java 8/Windows).
    assert.ok(Number.isInteger(hello.json.pid) && hello.json.pid > 0, 'pid ' + hello.json.pid);
    if (process.platform === 'linux') {
      const cmd = fs.readFileSync('/proc/' + hello.json.pid + '/cmdline', 'latin1');
      assert.ok(cmd.includes('--akw-port=' + port) && !cmd.includes('--type='), 'HELLO.pid is the browser process');
    }
  });

  game.send(P.T.WELCOME, 0, { protoMajor: 1, policy: { mode: 'blocklist', allow: [], block: ['blocked.example'] }, locale: 'pt-BR', userAgent: STOCK_UA });

  await t.test('PING -> PONG with the nonce and a host monotonic time', async () => {
    game.send(P.T.PING, 0, { nonce: 42 });
    const pong = await game.waitFor(m => m.type === P.T.PONG, 5000, 'PONG');
    assert.strictEqual(pong.json.nonce, 42);
    assert.strictEqual(typeof pong.json.hostTimeMs, 'number');
  });

  const createdAt = Date.now();
  game.send(P.T.CREATE, 1, { width: W, height: H, fps: 30, url: pageUrl, session: 'shared', audio: 'os', volume: 1 });

  await t.test('STATE reports the page, title and navigation flags', async () => {
    const st = await titleOf(game, 1, title => title.startsWith('v:'), 'STATE with page title', 15000);
    assert.strictEqual(st.json.url, pageUrl);
    assert.strictEqual(st.json.canGoBack, false);
    assert.strictEqual(typeof st.json.loading, 'boolean');
    assert.ok('audible' in st.json && 'cursor' in st.json);
    // The stock Chrome UA from WELCOME and pt-BR reach the page.
    await titleOf(game, 1, title => title.includes('|ua:chrome') && title.includes('|lang:pt-BR'), 'UA and language');
  });

  await t.test('FRAME: full first frame, BGRA pixels, at least 30 frames in 3 s', async () => {
    const first = await game.waitFor(m => m.type === P.T.FRAME && m.screenId === 1, 15000, 'first FRAME');
    results.firstFrameMs = first.at - createdAt;
    const h = frameHeader(first);
    assert.deepStrictEqual(h, { width: W, height: H, x: 0, y: 0, w: W, h: H });
    assert.strictEqual(first.payload.length, 12 + W * H * 4);
    // Wait until the page has painted its background, then check one pixel: rgb(10,200,30) in BGRA.
    const painted = await game.waitFor(m => {
      if (m.type !== P.T.FRAME || m.screenId !== 1) return false;
      const f = frameHeader(m);
      if (f.x > 20 || f.y > 20 || f.x + f.w < 21 || f.y + f.h < 21) return false;
      const off = 12 + ((20 - f.y) * f.w + (20 - f.x)) * 4;
      return m.payload[off] === 30 && m.payload[off + 1] === 200 && m.payload[off + 2] === 10;
    }, 10000, 'painted background pixel');
    const f = frameHeader(painted);
    assert.strictEqual(painted.payload[12 + ((20 - f.y) * f.w + (20 - f.x)) * 4 + 3], 255, 'opaque alpha');
    const since = game.mark();
    await sleep(3000);
    const got = frames(game, 1, since);
    results.framesIn3s = got.length;
    results.fps = +(got.length / 3).toFixed(1);
    results.avgFrameBytes = Math.round(got.reduce((a, m) => a + m.payload.length, 0) / Math.max(1, got.length));
    assert.ok(got.length >= 30, 'expected >= 30 frames in 3 s, got ' + got.length);
    for (const m of got) {
      const r = frameHeader(m);
      assert.ok(r.x + r.w <= r.width && r.y + r.h <= r.height, 'dirty rect inside the page');
      assert.strictEqual(m.payload.length, 12 + r.w * r.h * 4);
    }
  });

  await t.test('INPUT: click, accented text, Backspace round trip', async () => {
    const since = game.mark();
    const sentAt = Date.now();
    game.send(P.T.INPUT, 1, { type: 'mouseDown', x: 60, y: 215, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'mouseUp', x: 60, y: 215, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'char', text: 'ação ç $%&X' });
    game.send(P.T.INPUT, 1, { type: 'keyDown', keyCode: 'Backspace', modifiers: [] });
    game.send(P.T.INPUT, 1, { type: 'keyUp', keyCode: 'Backspace', modifiers: [] });
    const st = await titleOf(game, 1, title => title.startsWith('v:ação ç $%&|'), 'typed text in title', 8000, since);
    results.inputRoundTripMs = st.at - sentAt;
  });

  await t.test('INPUT: keyDown of a letter inserts nothing; Enter inserts one newline', async () => {
    const since = game.mark();
    game.send(P.T.INPUT, 1, { type: 'mouseDown', x: 60, y: 270, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'mouseUp', x: 60, y: 270, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'keyDown', keyCode: 'a', modifiers: 0 });
    game.send(P.T.INPUT, 1, { type: 'char', text: 'a' });
    game.send(P.T.INPUT, 1, { type: 'keyUp', keyCode: 'a', modifiers: 0 });
    game.send(P.T.INPUT, 1, { type: 'keyDown', keyCode: 'Enter', modifiers: [] });
    game.send(P.T.INPUT, 1, { type: 'char', text: '\r' }); // must be dropped: Enter already produced it
    game.send(P.T.INPUT, 1, { type: 'keyUp', keyCode: 'Enter', modifiers: [] });
    game.send(P.T.INPUT, 1, { type: 'char', text: 'b' });
    await titleOf(game, 1, title => title.includes('|ta:"a\\nb"|'), 'textarea "a\\nb"', 8000, since);
  });

  await t.test('INPUT: keyDown "Space" types nothing itself; with the game\'s char " " exactly one space', async () => {
    const since = game.mark();
    game.send(P.T.INPUT, 1, { type: 'mouseDown', x: 60, y: 270, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'mouseUp', x: 60, y: 270, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'keyDown', keyCode: 'End', modifiers: ['control'] }); // caret to the end
    game.send(P.T.INPUT, 1, { type: 'keyUp', keyCode: 'End', modifiers: ['control'] });
    game.send(P.T.INPUT, 1, { type: 'keyDown', keyCode: 'Space', modifiers: [] });
    game.send(P.T.INPUT, 1, { type: 'char', text: ' ' });
    game.send(P.T.INPUT, 1, { type: 'keyUp', keyCode: 'Space', modifiers: [] });
    game.send(P.T.INPUT, 1, { type: 'keyDown', keyCode: ' ', modifiers: [] }); // DOM name of the same key
    game.send(P.T.INPUT, 1, { type: 'keyUp', keyCode: ' ', modifiers: [] });
    game.send(P.T.INPUT, 1, { type: 'char', text: 'c' });
    await titleOf(game, 1, title => title.includes('|ta:"a\\nb c"|'), 'textarea "a\\nb c"', 8000, since);
  });

  await t.test('INPUT: a char event carrying a paste types every character, line breaks and tab included', async () => {
    const since = game.mark();
    // > 64 code points (the wave-1 cap), two line breaks, a tab, accents and an astral character.
    const paste = 'linha um, ação e café\nsegunda linha\tcom tab 😀\nterceira ' + 'z'.repeat(40);
    assert.ok([...paste].length > 64);
    game.send(P.T.INPUT, 1, { type: 'keyDown', keyCode: 'a', modifiers: ['control'] }); // select all
    game.send(P.T.INPUT, 1, { type: 'keyUp', keyCode: 'a', modifiers: ['control'] });
    game.send(P.T.INPUT, 1, { type: 'char', text: paste });
    await titleOf(game, 1, title => title.includes('|ta:' + JSON.stringify(paste) + '|'), 'pasted text in the textarea', 8000, since);
    // Empty the textarea again so the page title (cut at 256 characters in STATE) keeps every field.
    game.send(P.T.INPUT, 1, { type: 'keyDown', keyCode: 'a', modifiers: ['control'] });
    game.send(P.T.INPUT, 1, { type: 'keyDown', keyCode: 'Backspace', modifiers: [] });
    await titleOf(game, 1, title => title.includes('|ta:""|'), 'textarea emptied', 8000, game.mark());
  });

  await t.test('INPUT: wheel deltaY < 0 scrolls down and > 0 up, notches x 100', async () => {
    const since = game.mark();
    game.send(P.T.INPUT, 1, { type: 'wheel', x: 320, y: 180, deltaX: 0, deltaY: -100 }); // one notch down
    const st = await titleOf(game, 1, title => /\|sy:[1-9]/.test(title), 'scrolled down', 8000, since);
    results.wheelScrollY = Number(/\|sy:(\d+)/.exec(st.json.title)[1]);
    await sleep(600); // let the smooth scroll settle
    const settled = game.messages.filter(m => m.type === P.T.STATE && m.screenId === 1).pop();
    results.wheelOneNotchPx = Number(/\|sy:(\d+)/.exec(settled.json.title)[1]);
    game.send(P.T.INPUT, 1, { type: 'wheel', x: 320, y: 180, deltaX: 0, deltaY: 100 * 30 }); // 30 notches up
    await titleOf(game, 1, title => title.includes('|sy:0|'), 'scrolled back to top', 8000, game.mark());
  });

  await t.test('AUDIO: page volume follows the game gain in every media element, iframes included', async () => {
    const since = game.mark();
    game.send(P.T.AUDIO, 1, { muted: false, volume: 0.25 });
    await titleOf(game, 1, title => title.includes('|vol:0.25|') && title.includes('|fvol:0.25|'), 'volume 0.25 in page and iframe', 8000, since);
  });

  await t.test('page guard replaces print() in the main frame and in iframes', async () => {
    await titleOf(game, 1, title => title.includes('|pg:guarded|') && title.endsWith('|fpg:guarded'), 'guard installed', 8000);
  });

  await t.test('MEDIA at ~4 Hz and SYNC steering of the audio element', async () => {
    await game.waitFor(m => m.type === P.T.MEDIA && m.screenId === 1 && m.json && m.json.paused === false, 10000, 'MEDIA playing', game.mark());
    const since = game.mark();
    await sleep(2000);
    const samples = game.messages.filter(m => m.type === P.T.MEDIA && m.screenId === 1 && m.idx >= since);
    const span = (samples[samples.length - 1].json.t - samples[0].json.t) / 1000;
    results.mediaPerSecond = +((samples.length - 1) / span).toFixed(2);
    assert.ok(samples.length >= 6, 'expected >= 6 MEDIA in 2 s, got ' + samples.length);
    const last = samples[samples.length - 1].json;
    assert.ok(last.duration > 5 && last.duration < 7, 'tone duration ' + last.duration);
    assert.strictEqual(last.live, false);
    assert.strictEqual(typeof last.t, 'number');
    const syncAt = game.mark();
    game.send(P.T.SYNC, 1, { targetMediaTime: 1.0, rate: 1.04, paused: false, seek: true });
    const after = await game.waitFor(m => m.type === P.T.MEDIA && m.screenId === 1 && m.json && Math.abs(m.json.rate - 1.04) < 1e-6, 5000, 'MEDIA with rate 1.04', syncAt);
    assert.ok(after.json.mediaTime >= 1.0 && after.json.mediaTime < 2.0, 'seeked near 1.0 s, got ' + after.json.mediaTime);
    game.send(P.T.SYNC, 1, { targetMediaTime: 0, rate: 2.0, paused: true, seek: false });
    const paused = await game.waitFor(m => m.type === P.T.MEDIA && m.screenId === 1 && m.json && m.json.paused === true, 5000, 'MEDIA paused', game.mark());
    assert.strictEqual(paused.json.rate, 1, 'rate reset while paused');
    game.send(P.T.SYNC, 1, { targetMediaTime: 0, rate: 9, paused: false, seek: false });
    const clamped = await game.waitFor(m => m.type === P.T.MEDIA && m.screenId === 1 && m.json && m.json.paused === false && m.json.rate > 1, 5000, 'MEDIA clamped', game.mark());
    assert.strictEqual(clamped.json.rate, 1.05, 'rate clamped to 1.05');
  });

  await t.test('blocked URLs: literal loopback, policy blocklist, DNS answer to loopback, page fetch', async () => {
    let since = game.mark();
    game.send(P.T.NAVIGATE, 1, { url: 'http://127.0.0.1:' + internal.port + '/navigate' });
    let ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 1 && m.json.kind === 'blocked-url', 5000, 'blocked loopback', since);
    assert.strictEqual(ev.json.detail.reason, 'internal');
    since = game.mark();
    game.send(P.T.NAVIGATE, 1, { url: 'http://blocked.example/' });
    ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 1 && m.json.kind === 'blocked-url', 5000, 'blocked by policy', since);
    assert.strictEqual(ev.json.detail.reason, 'blocked');
    since = game.mark();
    game.send(P.T.NAVIGATE, 1, { url: 'http://rebind.akw.test:' + internal.port + '/dns' });
    ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 1 && m.json.kind === 'blocked-url', 8000, 'blocked by DNS answer', since);
    // --host-resolver-rules maps the name to 127.0.0.1; session.resolveHost honours it.
    assert.strictEqual(ev.json.detail.reason, 'internal-dns');
    assert.strictEqual(ev.json.detail.via, 'navigate');
    since = game.mark();
    // Ask the page itself to fetch the loopback sink (subresource path).
    game.send(P.T.NAVIGATE, 1, { url: pageUrl + '#probe=' + internal.port });
    await titleOf(game, 1, title => title.includes('|fetch:failed|'), 'page fetch failed', 8000, since);
    ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 1 && m.json.kind === 'blocked-url' && m.json.detail.via === 'request-xhr', 5000, 'blocked fetch event', since);
    assert.strictEqual(ev.json.detail.reason, 'internal');
    await sleep(500);
    assert.strictEqual(internal.hits, 0, 'nothing reached the loopback listener');
    const st = game.messages.filter(m => m.type === P.T.STATE && m.screenId === 1).pop();
    assert.ok(st.json.url.startsWith(pageUrl), 'still on the test page: ' + st.json.url);
  });

  await t.test('fetch, img, WebSocket and beacon to a NAME resolving to loopback are cancelled before connecting', async () => {
    // Electron 44 disables Chromium's Local Network Access checks, so denying the LNA permissions
    // protects nothing (wave-1 review). The host resolves every request's name itself: none of these
    // subresources may reach the loopback "router". (The details.ip detector, which only notices a
    // request after it reached the address, is covered by policy.test.js.)
    const since = game.mark();
    const hitsBefore = internal.hits;
    game.send(P.T.NAVIGATE, 1, { url: pageUrl + '#probe=' + internal.port + '&host=rebind.akw.test&kinds=fetch,img,ws,beacon' });
    await titleOf(game, 1, title => title.includes('|fetch:failed|') && title.includes('|img:failed|') && title.includes('|ws:failed|'), 'fetch, img and ws failed', 8000, since);
    const vias = new Set();
    await game.waitFor(m => {
      if (m.type !== P.T.EVENT || m.screenId !== 1 || m.json.kind !== 'blocked-url' || m.json.detail.reason !== 'internal-dns') return false;
      vias.add(m.json.detail.via);
      return ['request-xhr', 'request-image', 'request-webSocket', 'request-ping'].every(v => vias.has(v));
    }, 8000, 'internal-dns events for xhr, image, webSocket and ping', since);
    await sleep(800);
    results.lnaSinkHits = internal.hits - hitsBefore;
    assert.strictEqual(internal.hits - hitsBefore, 0, 'nothing reached the loopback listener');
    const st = game.messages.filter(m => m.type === P.T.STATE && m.screenId === 1).pop();
    assert.ok(st.json.url.startsWith(pageUrl), 'still on the test page: ' + st.json.url);
  });

  await t.test('window.open stays in the same window; links to internal hosts are refused', async () => {
    let since = game.mark();
    game.send(P.T.INPUT, 1, { type: 'mouseDown', x: 450, y: 215, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'mouseUp', x: 450, y: 215, button: 'left', clickCount: 1 });
    const st = await game.waitFor(m => m.type === P.T.STATE && m.screenId === 1 && m.json.url.endsWith('page2.html') && m.json.title === 'akw-page2', 8000, 'page2 in the same screen', since);
    assert.strictEqual(st.json.canGoBack, true);
    since = game.mark();
    game.send(P.T.INPUT, 1, { type: 'mouseDown', x: 50, y: 30, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'mouseUp', x: 50, y: 30, button: 'left', clickCount: 1 });
    let ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 1 && m.json.kind === 'blocked-url', 5000, 'internal link blocked', since);
    assert.strictEqual(ev.json.detail.via, 'will-navigate');
    assert.strictEqual(ev.json.detail.reason, 'internal');
    // A link to a name that resolves to loopback passes will-navigate and is cancelled by the request
    // filter after resolveHost (ADR-013 amendment, measure 2).
    since = game.mark();
    game.send(P.T.INPUT, 1, { type: 'mouseDown', x: 50, y: 130, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'mouseUp', x: 50, y: 130, button: 'left', clickCount: 1 });
    ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 1 && m.json.kind === 'blocked-url', 8000, 'rebind link blocked', since);
    assert.strictEqual(ev.json.detail.via, 'request-mainFrame');
    assert.strictEqual(ev.json.detail.reason, 'internal-dns');
    await sleep(300);
    assert.strictEqual(internal.hits, 0, 'the blocked link reached nothing');
    since = game.mark();
    game.send(P.T.NAVIGATE, 1, { url: pageUrl.replace('page.html', 'page2.html') });
    await game.waitFor(m => m.type === P.T.STATE && m.screenId === 1 && m.json.url.endsWith('page2.html') && !m.json.loading, 8000, 'page2 again', since);
    since = game.mark();
    game.send(P.T.NAVIGATE, 1, { url: pageUrl });
    await titleOf(game, 1, title => title.startsWith('v:'), 'back on page 1', 8000, since);
  });

  await t.test('window.open from an iframe without user input is denied (EVENT blocked-url, reason popup)', async () => {
    const since = game.mark();
    const popupPage = pageUrl.replace('page.html', 'popup.html');
    game.send(P.T.NAVIGATE, 1, { url: popupPage });
    const ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 1 && m.json.kind === 'blocked-url' && m.json.detail.reason === 'popup', 8000, 'popup refused', since);
    assert.strictEqual(ev.json.detail.via, 'window-open');
    assert.match(ev.json.detail.url, /page2\.html\?hijacked$/);
    await sleep(700);
    const urls = game.messages.filter(m => m.type === P.T.STATE && m.screenId === 1 && m.idx >= since).map(m => m.json.url);
    assert.ok(!urls.some(u => u.includes('hijacked')), 'the iframe did not take the screen over: ' + urls.join(' '));
    assert.strictEqual(urls[urls.length - 1], popupPage);
    game.send(P.T.NAVIGATE, 1, { url: pageUrl });
    await titleOf(game, 1, title => title.startsWith('v:'), 'back on page 1', 8000, game.mark());
  });

  await t.test('HTML fullscreen fills the page without resizing the offscreen view', async () => {
    let since = game.mark();
    game.send(P.T.INPUT, 1, { type: 'mouseDown', x: 450, y: 265, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'mouseUp', x: 450, y: 265, button: 'left', clickCount: 1 });
    await titleOf(game, 1, title => title.includes('|fs:1|'), 'fullscreen entered', 8000, since);
    since = game.mark();
    await sleep(500);
    for (const m of frames(game, 1, since)) assert.strictEqual(frameHeader(m).width, W, 'view not resized by fullscreen');
    since = game.mark();
    game.send(P.T.INPUT, 1, { type: 'keyDown', keyCode: 'Escape', modifiers: [] });
    game.send(P.T.INPUT, 1, { type: 'keyUp', keyCode: 'Escape', modifiers: [] });
    await titleOf(game, 1, title => title.includes('|fs:0|'), 'fullscreen left', 8000, since);
  });

  await t.test('file chooser click is cancelled by the page guard', async () => {
    const since = game.mark();
    game.send(P.T.INPUT, 1, { type: 'mouseDown', x: 470, y: 315, button: 'left', clickCount: 1 });
    game.send(P.T.INPUT, 1, { type: 'mouseUp', x: 470, y: 315, button: 'left', clickCount: 1 });
    await titleOf(game, 1, title => title.includes('|file:prevented|'), 'file input click prevented', 8000, since);
  });

  await t.test('NAVIGATE order: a newer NAVIGATE wins over an older one still waiting for its DNS answer', async () => {
    const since = game.mark();
    const second = pageUrl.replace('page.html', 'page2.html') + '?second';
    game.send(P.T.NAVIGATE, 1, { url: 'http://slow.akw.test/first' }); // resolved first (host-resolver-rules)
    game.send(P.T.NAVIGATE, 1, { url: second }); // no DNS: loads at once
    await game.waitFor(m => m.type === P.T.STATE && m.screenId === 1 && m.json.url === second && !m.json.loading, 8000, 'second page', since);
    await sleep(1500);
    const urls = game.messages.filter(m => m.type === P.T.STATE && m.screenId === 1 && m.idx >= since).map(m => m.json.url);
    assert.ok(!urls.some(u => u.includes('slow.akw.test')), 'the older NAVIGATE never loaded: ' + urls.join(' '));
    assert.strictEqual(urls[urls.length - 1], second);
    game.send(P.T.NAVIGATE, 1, { url: pageUrl });
    await titleOf(game, 1, title => title.startsWith('v:'), 'back on page 1', 8000, game.mark());
  });

  await t.test('slow game: full-page frames are replaced, not queued, while the game does not read for 4 s', async () => {
    game.send(P.T.NAVIGATE, 1, { url: pageUrl + '#busy' });
    await sleep(1000);
    const before = game.mark();
    game.socket.pause();
    await sleep(4000);
    game.socket.resume();
    await sleep(300);
    const got = frames(game, 1, before);
    const bytes = got.reduce((a, m) => a + m.payload.length, 0);
    results.framesAfterStall = got.length;
    results.mbAfterStall = +(bytes / 1048576).toFixed(1);
    // Queued, 4.3 s of 30 fps full 640x360 frames would be ~129 frames (~113 MiB). Latest-wins leaves
    // what the kernel socket buffers held, one pending frame, and the fresh frames after resume.
    assert.ok(got.length < 60, 'frames delivered after a 4 s stall: ' + got.length);
    assert.ok(got.every(m => frameHeader(m).w * frameHeader(m).h * 4 + 12 === m.payload.length));
  });

  await t.test('RENDER painting:false stops frames; resume sends a full frame equal to the canvas built from dirty rects', async () => {
    // Freeze the page, let the last paints arrive, and keep the canvas the dirty rects built.
    game.send(P.T.NAVIGATE, 1, { url: pageUrl + '#static' });
    await sleep(1500);
    const canvas = Buffer.from(game.canvases.get(1).data);
    game.send(P.T.RENDER, 1, { fps: 30, painting: false });
    await sleep(500);
    const quietSince = game.mark();
    await sleep(1500);
    const during = frames(game, 1, quietSince).length;
    results.framesWhileStopped = during;
    assert.ok(during <= 1, 'frames while stopped: ' + during);
    const resumeAt = game.mark();
    game.send(P.T.RENDER, 1, { fps: 30, painting: true });
    const f = await game.waitFor(m => m.type === P.T.FRAME && m.screenId === 1, 5000, 'frame after resume', resumeAt);
    assert.deepStrictEqual(frameHeader(f), { width: W, height: H, x: 0, y: 0, w: W, h: H });
    let diff = 0;
    for (let i = 0; i < canvas.length; i += 4) if (canvas.readUInt32LE(i) !== f.payload.readUInt32LE(12 + i)) diff++;
    results.canvasPixelsDifferent = diff;
    assert.strictEqual(diff, 0, 'canvas from dirty rects differs from a full repaint in ' + diff + ' pixels');
  });

  await t.test('RESIZE produces a full frame at the new size', async () => {
    const since = game.mark();
    game.send(P.T.RESIZE, 1, { width: 320, height: 240 });
    const f = await game.waitFor(m => m.type === P.T.FRAME && m.screenId === 1 && frameHeader(m).width === 320, 8000, 'resized frame', since);
    assert.deepStrictEqual(frameHeader(f), { width: 320, height: 240, x: 0, y: 0, w: 320, h: 240 });
  });

  await t.test('CREATE/RESIZE beyond the page cap (4096 per side, 4,194,301 px) answer EVENT screen-limit', async () => {
    let since = game.mark();
    game.send(P.T.CREATE, 5, { width: 2048, height: 2048, fps: 5, url: pageUrl, session: 'shared', audio: 'os', volume: 0 });
    let ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 5, 5000, 'screen-limit for CREATE', since);
    assert.strictEqual(ev.json.kind, 'screen-limit');
    assert.deepStrictEqual(ev.json.detail, { reason: 'size', width: 2048, height: 2048, maxWidth: 4096, maxHeight: 4096, maxPixels: 4194301 });
    since = game.mark();
    game.send(P.T.RESIZE, 1, { width: 4096, height: 1100 });
    ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 1 && m.json.kind === 'screen-limit', 5000, 'screen-limit for RESIZE', since);
    assert.strictEqual(ev.json.detail.reason, 'size');
    game.send(P.T.NAVIGATE, 1, { url: pageUrl + '#busy' }); // repaint continuously, to see the size
    await sleep(800);
    assert.strictEqual(frames(game, 5, since).length, 0, 'no screen 5');
    const after = frames(game, 1, since);
    assert.ok(after.length > 0, 'screen 1 still paints');
    for (const m of after) assert.strictEqual(frameHeader(m).width, 320, 'screen 1 kept its size');
  });

  await t.test('two screens render independently in separate sessions', async () => {
    const since = game.mark();
    game.send(P.T.CREATE, 2, { width: 256, height: 144, fps: 15, url: pageUrl, session: 'shared', audio: 'os', volume: 0 });
    const f = await game.waitFor(m => m.type === P.T.FRAME && m.screenId === 2, 15000, 'screen 2 frame', since);
    assert.deepStrictEqual(frameHeader(f), { width: 256, height: 144, x: 0, y: 0, w: 256, h: 144 });
    await titleOf(game, 2, title => title.includes('|vol:0.00|'), 'screen 2 volume 0', 8000, since);
    const s1 = frames(game, 1, since).length;
    assert.ok(s1 > 0, 'screen 1 still painting');
  });

  await t.test('DESTROY stops a screen', async () => {
    game.send(P.T.DESTROY, 2, null);
    await sleep(700);
    const since = game.mark();
    await sleep(1000);
    assert.strictEqual(frames(game, 2, since).length, 0);
  });

  await t.test('a malformed POLICY switches the host to deny-all and is reported (EVENT policy-error)', async () => {
    let since = game.mark();
    game.send(P.T.POLICY, 0, { mode: 'ALLOW_LIST', allow: ['example.com'] });
    const ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 0 && m.json.kind === 'policy-error', 5000, 'policy-error', since);
    assert.strictEqual(ev.json.detail.applied, 'deny-all');
    since = game.mark();
    game.send(P.T.NAVIGATE, 1, { url: 'http://example.com/' });
    const blocked = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 1 && m.json.kind === 'blocked-url', 5000, 'deny-all refusal', since);
    assert.strictEqual(blocked.json.detail.reason, 'not-allowed');
    game.send(P.T.POLICY, 0, { mode: 'blocklist', allow: [], block: ['blocked.example'] });
  });

  await t.test('the host exits when the game closes the socket', async () => {
    const closedAt = Date.now();
    game.socket.end();
    const exit = await Promise.race([game.exited, sleep(10000).then(() => null)]);
    assert.ok(exit, 'host still running 10 s after socket close');
    results.exitAfterCloseMs = Date.now() - closedAt;
    assert.strictEqual(exit.code, 0, 'exit code ' + JSON.stringify(exit) + '\n' + game.hostLog().slice(-2000));
  });
});

test('spatial capture produces 48 kHz stereo PCM (Linux only, optional feature)', { skip: SKIP, timeout: 90000 }, async t => {
  const game = new Game();
  const port = await game.listen();
  const pageUrl = prepareSite(game);
  t.after(() => game.close());
  game.spawnHost(port, { extraArgs: ['--akw-test-allow-file'] });
  await game.waitFor(m => m.type === P.T.HELLO, 20000, 'HELLO');
  game.send(P.T.WELCOME, 0, { protoMajor: 1, policy: null, locale: 'pt-BR', userAgent: STOCK_UA });
  game.send(P.T.CREATE, 3, { width: 320, height: 180, fps: 10, url: pageUrl, session: 'shared', audio: 'spatial', volume: 1 });
  const first = await game.waitFor(m => m.type === P.T.PCM && m.screenId === 3, 20000, 'first PCM');
  const since = game.mark();
  await sleep(2000);
  const packets = game.messages.filter(m => m.type === P.T.PCM && m.screenId === 3 && m.idx >= since);
  let sumsq = 0;
  let n = 0;
  for (const m of packets) {
    const b = m.payload;
    assert.strictEqual(b.readUInt32BE(8), 48000);
    assert.strictEqual(b.readUInt16BE(12), 2);
    const fr = b.readUInt16BE(14);
    assert.strictEqual(b.length, 16 + fr * 4);
    for (let i = 16; i < b.length; i += 2) {
      const v = b.readInt16LE(i) / 32768;
      sumsq += v * v;
      n++;
    }
  }
  const rms = Math.sqrt(sumsq / Math.max(1, n));
  console.log('# spatial numbers: ' + JSON.stringify({ packetsPerSecond: packets.length / 2, rms: +rms.toFixed(4), firstPts: Number(first.payload.readBigInt64BE(0)) }));
  assert.ok(packets.length >= 150, 'expected ~100 packets/s, got ' + packets.length + ' in 2 s');
  assert.ok(rms > 0.05, 'tone should be audible in the capture, rms ' + rms);
  const ptsA = Number(packets[0].payload.readBigInt64BE(0));
  const ptsB = Number(packets[packets.length - 1].payload.readBigInt64BE(0));
  assert.ok(ptsB > ptsA, 'pts increases');
});

test('behind a loopback proxy: pages load (detector skips the proxy address); DNS failure is dns-via-proxy', { skip: SKIP, timeout: 90000 }, async t => {
  // A local filtering proxy, antivirus web shield or "game booster" answers on 127.0.0.1. Wave 1 took the
  // proxy's address in details.ip for the site's and blanked every page as 'internal-ip'.
  let proxyHits = 0;
  const proxy = net.createServer(c => {
    proxyHits++;
    c.on('error', () => {});
    c.once('data', () => c.end('HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 38\r\nConnection: close\r\n\r\n<title>public-page</title><p>hello</p>'));
  });
  await new Promise(r => proxy.listen(0, '127.0.0.1', r));
  const game = new Game();
  const port = await game.listen();
  t.after(async () => {
    await game.close();
    proxy.close();
  });
  game.spawnHost(port, { extraArgs: ['--proxy-server=http://127.0.0.1:' + proxy.address().port,
    '--host-resolver-rules=MAP public.akw.test 93.184.216.34, MAP nx.akw.test ~NOTFOUND'] });
  await game.waitFor(m => m.type === P.T.HELLO, 20000, 'HELLO');
  game.send(P.T.WELCOME, 0, { protoMajor: 1, policy: { mode: 'blocklist', allow: [], block: [] }, locale: 'pt-BR' });
  game.send(P.T.CREATE, 1, { width: 320, height: 180, fps: 10, url: 'http://public.akw.test/', session: 'shared', audio: 'os', volume: 0 });
  await titleOf(game, 1, title => title === 'public-page', 'page through the proxy', 15000);
  await sleep(1500);
  const internalIp = game.messages.filter(m => m.type === P.T.EVENT && m.json.detail && m.json.detail.reason === 'internal-ip');
  assert.deepStrictEqual(internalIp.map(m => m.json), [], 'not mistaken for an internal hit');
  const last = game.messages.filter(m => m.type === P.T.STATE && m.screenId === 1).pop();
  assert.strictEqual(last.json.url, 'http://public.akw.test/', 'not blanked');
  assert.ok(proxyHits >= 1);
  const since = game.mark();
  game.send(P.T.NAVIGATE, 1, { url: 'http://nx.akw.test/' });
  const ev = await game.waitFor(m => m.type === P.T.EVENT && m.screenId === 1 && m.json.kind === 'blocked-url', 8000, 'dns-via-proxy', since);
  assert.strictEqual(ev.json.detail.reason, 'dns-via-proxy');
});

test('lifecycle: parent PID gone, missing token, forbidden switch', { skip: SKIP, timeout: 90000 }, async t => {
  await t.test('exits within ~2 s poll after the parent process dies', async () => {
    const game = new Game();
    const port = await game.listen();
    const parent = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
    try {
      game.spawnHost(port, { parentPid: parent.pid });
      await game.waitFor(m => m.type === P.T.HELLO, 20000, 'HELLO');
      game.send(P.T.WELCOME, 0, { protoMajor: 1, policy: null, locale: 'pt-BR', userAgent: STOCK_UA });
      const killedAt = Date.now();
      parent.kill('SIGKILL');
      const exit = await Promise.race([game.exited, sleep(8000).then(() => null)]);
      assert.ok(exit, 'host still alive 8 s after the parent died');
      console.log('# parent-gone exit after ms: ' + (Date.now() - killedAt));
      const bye = game.messages.find(m => m.type === P.T.BYE);
      assert.ok(bye && bye.json.reason === 'parent-gone', 'BYE parent-gone');
    } finally {
      parent.kill('SIGKILL');
      await game.close();
    }
  });

  await t.test('--akw-parent-pid=0 (unknown) runs without the parent poll and quits on QUIT', async () => {
    const game = new Game();
    const port = await game.listen();
    try {
      game.spawnHost(port, { parentPid: 0 });
      await game.waitFor(m => m.type === P.T.HELLO, 20000, 'HELLO with parent pid 0');
      game.send(P.T.WELCOME, 0, { protoMajor: 1, policy: null, locale: 'pt-BR' });
      await sleep(2500); // longer than one poll period
      assert.strictEqual(game.child.exitCode, null, 'still running');
      assert.match(game.hostLog(), /parent poll disabled/);
      game.send(P.T.QUIT, 0, null);
      const exit = await Promise.race([game.exited, sleep(10000).then(() => null)]);
      assert.ok(exit && exit.code === 0, 'QUIT exit ' + JSON.stringify(exit));
    } finally {
      await game.close();
    }
  });

  await t.test('exits with NO_TOKEN when stdin and AKW_TOKEN are empty', async () => {
    const game = new Game();
    const port = await game.listen();
    try {
      game.spawnHost(port, { token: false });
      const exit = await Promise.race([game.exited, sleep(15000).then(() => null)]);
      assert.ok(exit, 'host did not exit');
      assert.strictEqual(exit.code, 66);
      assert.strictEqual(game.socket, null, 'never connected');
    } finally {
      await game.close();
    }
  });

  await t.test('uses AKW_TOKEN when stdin carries nothing', async () => {
    const game = new Game();
    const port = await game.listen();
    try {
      game.spawnHost(port, { token: false, env: { AKW_TOKEN: game.token } });
      const hello = await game.waitFor(m => m.type === P.T.HELLO, 20000, 'HELLO via env');
      assert.strictEqual(hello.json.token, game.token);
      game.send(P.T.QUIT, 0, null);
      const exit = await Promise.race([game.exited, sleep(10000).then(() => null)]);
      assert.ok(exit && exit.code === 0, 'QUIT exit ' + JSON.stringify(exit));
      assert.ok(game.messages.some(m => m.type === P.T.BYE && m.json.reason === 'quit'));
    } finally {
      await game.close();
    }
  });

  await t.test('refuses --remote-debugging-port', async () => {
    const game = new Game();
    const port = await game.listen();
    try {
      game.spawnHost(port, { extraArgs: ['--remote-debugging-port=9222'] });
      const exit = await Promise.race([game.exited, sleep(15000).then(() => null)]);
      assert.ok(exit, 'host did not exit');
      assert.strictEqual(exit.code, 65);
    } finally {
      await game.close();
    }
  });

  await t.test('a malformed WELCOME ends the host with HANDSHAKE at once (fail closed)', async () => {
    const game = new Game();
    const port = await game.listen();
    try {
      game.spawnHost(port);
      await game.waitFor(m => m.type === P.T.HELLO, 20000, 'HELLO');
      const sentAt = Date.now();
      game.send(P.T.WELCOME, 0, { protoMajor: 1, policy: { mode: 'ALLOW_LIST', allow: ['youtube.com'] }, locale: 'pt-BR' });
      game.send(P.T.CREATE, 9, { width: 320, height: 180, url: 'https://evil.example/' });
      const exit = await Promise.race([game.exited, sleep(10000).then(() => null)]);
      assert.ok(exit, 'host did not exit');
      assert.strictEqual(exit.code, 68);
      assert.ok(Date.now() - sentAt < 5000, 'exited at once, not after the 10 s WELCOME timeout');
      assert.ok(game.messages.some(m => m.type === P.T.BYE && m.json.reason === 'bad-welcome'));
      assert.ok(!game.messages.some(m => m.screenId === 9), 'the CREATE was never served');
    } finally {
      await game.close();
    }
  });

  await t.test('protocol major mismatch ends the host with HANDSHAKE', async () => {
    const game = new Game();
    const port = await game.listen();
    try {
      game.spawnHost(port);
      await game.waitFor(m => m.type === P.T.HELLO, 20000, 'HELLO');
      game.send(P.T.WELCOME, 0, { protoMajor: 2, policy: null, locale: 'pt-BR', userAgent: STOCK_UA });
      const exit = await Promise.race([game.exited, sleep(10000).then(() => null)]);
      assert.ok(exit, 'host did not exit');
      assert.strictEqual(exit.code, 68);
      assert.ok(game.messages.some(m => m.type === P.T.BYE && m.json.reason === 'protocol-mismatch'));
    } finally {
      await game.close();
    }
  });
});
