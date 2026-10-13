'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { EventEmitter } = require('events');
const Pol = require('../src/policy');

const BLOCK = { mode: 'blocklist', allow: [], block: ['bad.example'] };
const ALLOW = { mode: 'allowlist', allow: ['youtube.com', 'twitch.tv'], block: ['ads.youtube.com'] };

test('IPv4 internal ranges (same set as AkashicFM UrlPolicy.isInternal)', () => {
  const internal = ['0.0.0.0', '0.1.2.3', '10.0.0.1', '127.0.0.1', '127.255.255.254', '169.254.169.254', '172.16.0.1', '172.31.255.255',
    '192.168.0.1', '100.64.0.1', '100.127.255.255', '192.0.0.8', '198.18.0.1', '198.19.255.255', '224.0.0.1', '239.255.255.250',
    '240.0.0.1', '255.255.255.255', '192.0.2.1', '198.51.100.7', '203.0.113.9'];
  const external = ['8.8.8.8', '1.1.1.1', '172.15.255.255', '172.32.0.1', '100.63.255.255', '100.128.0.0', '192.169.0.1', '142.250.78.14', '198.20.0.1'];
  for (const a of internal) assert.strictEqual(Pol.isInternalAddress(a), true, a);
  for (const a of external) assert.strictEqual(Pol.isInternalAddress(a), false, a);
});

test('IPv6: loopback, ULA, link-local, multicast, mapped/compat/NAT64/6to4 embedded IPv4', () => {
  const internal = ['::', '::1', 'fc00::1', 'fd12:3456::1', 'fe80::1', 'febf::1', 'fec0::1', 'ff02::1', '::ffff:127.0.0.1', '::ffff:7f00:1',
    '::ffff:192.168.1.1', '::127.0.0.1', '64:ff9b::10.0.0.1', '64:ff9b:1::5', '2002:c0a8:0101::1', '2001:db8::1', '100::1',
    '[::1]', 'fe80::1%eth0', '0:0:0:0:0:0:0:1'];
  const external = ['2001:4860:4860::8888', '2606:4700:4700::1111', '::ffff:8.8.8.8', '64:ff9b::808:808', '2002:0808:0808::1', '2800:3f0:4001:810::200e'];
  for (const a of internal) assert.strictEqual(Pol.isInternalAddress(a), true, a);
  for (const a of external) assert.strictEqual(Pol.isInternalAddress(a), false, a);
});

test('unparseable addresses fail closed', () => {
  for (const a of ['', 'not-an-ip', '1.2.3', '1::2::3', '12345::1', ':::', undefined]) assert.strictEqual(Pol.isInternalAddress(a), true, String(a));
});

test('parseIPv6 expands :: and IPv4 tails', () => {
  assert.deepStrictEqual(Pol.parseIPv6('::1'), [0, 0, 0, 0, 0, 0, 0, 1]);
  assert.deepStrictEqual(Pol.parseIPv6('[2001:db8::ff00:42:8329]'), [0x2001, 0xdb8, 0, 0, 0, 0xff00, 0x42, 0x8329]);
  assert.deepStrictEqual(Pol.parseIPv6('::ffff:1.2.3.4'), [0, 0, 0, 0, 0, 0xffff, 0x0102, 0x0304]);
  assert.strictEqual(Pol.parseIPv6('1:2:3:4:5:6:7'), null);
  assert.strictEqual(Pol.parseIPv6('1.2.3.4'), null);
});

test('hostnames: internal literals and internal-only names, no DNS', () => {
  for (const h of ['localhost', 'LOCALHOST.', 'app.localhost', 'printer.local', 'db.internal', 'nas.lan', 'x.home.arpa', 'wiki.intranet', 'router', '127.0.0.1', '[::1]', '[fd00::5]']) {
    assert.strictEqual(Pol.isInternalHostname(h), true, h);
  }
  for (const h of ['youtube.com', 'www.twitch.tv', '8.8.8.8', '[2001:4860:4860::8888]', 'local.example.com']) assert.strictEqual(Pol.isInternalHostname(h), false, h);
});

test('WHATWG URL normalises numeric host forms before the check (decimal, hex, octal, short)', () => {
  for (const u of ['http://2130706433/', 'http://0x7f000001/', 'http://0177.0.0.1/', 'http://127.1/', 'http://[::ffff:127.0.0.1]/', 'http://0/']) {
    const v = Pol.checkNavigation(u, BLOCK, 'main');
    assert.deepStrictEqual([v.ok, v.reason], [false, 'internal'], u);
  }
});

test('main-frame navigation: only http/https, no credentials, internal refused', () => {
  const cases = [
    ['https://www.youtube.com/watch?v=x', true, undefined],
    ['about:blank', true, undefined],
    ['file:///etc/passwd', false, 'scheme'],
    ['chrome://gpu', false, 'scheme'],
    ['devtools://devtools/bundled/inspector.html', false, 'scheme'],
    ['javascript:alert(1)', false, 'scheme'],
    ['data:text/html,hi', false, 'scheme'],
    ['view-source:https://a.com', false, 'scheme'],
    ['ftp://example.com/', false, 'scheme'],
    ['https://user:pw@example.com/', false, 'credentials'],
    ['http://192.168.0.1/admin', false, 'internal'],
    ['http://localhost:8080/', false, 'internal'],
    ['not a url', false, 'malformed'],
    ['', false, 'malformed'],
    ['https://a.com/' + 'x'.repeat(9000), false, 'too-long'],
  ];
  for (const [u, ok, reason] of cases) {
    const v = Pol.checkNavigation(u, BLOCK, 'main');
    assert.strictEqual(v.ok, ok, u);
    if (!ok) assert.strictEqual(v.reason, reason, u);
  }
});

test('file: is allowed only with the test flag', () => {
  assert.strictEqual(Pol.checkNavigation('file:///tmp/page.html', BLOCK, 'main', { allowFile: true }).ok, true);
  assert.strictEqual(Pol.checkRequest('file:///tmp/x.png', 'image', BLOCK, { allowFile: true }).ok, true);
  assert.strictEqual(Pol.checkRequest('file:///tmp/x.png', 'image', BLOCK, {}).reason, 'scheme');
});

test('blocklist: entries match the host and its subdomains, on every frame', () => {
  assert.strictEqual(Pol.checkNavigation('https://bad.example/', BLOCK, 'main').reason, 'blocked');
  assert.strictEqual(Pol.checkNavigation('https://cdn.bad.example/', BLOCK, 'sub').reason, 'blocked');
  assert.strictEqual(Pol.checkNavigation('https://notbad.example/', BLOCK, 'main').ok, true);
  assert.strictEqual(Pol.checkNavigation('https://bad.example.evil.com/', BLOCK, 'main').ok, true);
});

test('allowlist: top-level documents only; iframes of allowed pages still load; block wins', () => {
  assert.strictEqual(Pol.checkNavigation('https://m.youtube.com/', ALLOW, 'main').ok, true);
  assert.strictEqual(Pol.checkNavigation('https://twitch.tv/x', ALLOW, 'main').ok, true);
  assert.strictEqual(Pol.checkNavigation('https://example.com/', ALLOW, 'main').reason, 'not-allowed');
  assert.strictEqual(Pol.checkNavigation('https://youtube.com.evil.net/', ALLOW, 'main').reason, 'not-allowed');
  assert.strictEqual(Pol.checkNavigation('https://consent.google.com/', ALLOW, 'sub').ok, true);
  assert.strictEqual(Pol.checkNavigation('https://ads.youtube.com/', ALLOW, 'main').reason, 'blocked');
  assert.strictEqual(Pol.checkNavigation('http://10.0.0.1/', ALLOW, 'sub').reason, 'internal');
});

test('no policy at all denies every top-level site (fail closed), internal checks still first', () => {
  assert.strictEqual(Pol.checkNavigation('https://youtube.com/', null, 'main').reason, 'not-allowed');
  assert.strictEqual(Pol.checkNavigation('https://youtube.com/', undefined, 'main').reason, 'not-allowed');
  assert.strictEqual(Pol.checkNavigation('http://10.0.0.1/', null, 'main').reason, 'internal');
  assert.strictEqual(Pol.checkNavigation('about:blank', null, 'main').ok, true);
});

test('sub frames may hold about:, data: and blob: documents; other schemes are refused', () => {
  for (const u of ['about:blank', 'about:srcdoc', 'data:text/html,x', 'blob:https://a.com/uuid']) assert.strictEqual(Pol.checkNavigation(u, BLOCK, 'sub').ok, true, u);
  assert.strictEqual(Pol.checkNavigation('chrome://settings', BLOCK, 'sub').reason, 'scheme');
});

test('needsDns is set for names, not for IP literals', () => {
  assert.strictEqual(Pol.checkNavigation('https://youtube.com/', BLOCK, 'main').needsDns, true);
  assert.strictEqual(Pol.checkNavigation('https://8.8.8.8/', BLOCK, 'main').needsDns, false);
  assert.strictEqual(Pol.checkNavigation('https://[2001:4860:4860::8888]/', BLOCK, 'main').needsDns, false);
});

test('subresources: literal internal hosts refused for http/https/ws/wss; names need DNS; policy lists not applied', () => {
  assert.strictEqual(Pol.checkRequest('http://127.0.0.1:8080/x', 'xhr', BLOCK).reason, 'internal');
  assert.strictEqual(Pol.checkRequest('ws://192.168.1.10/socket', 'webSocket', BLOCK).reason, 'internal');
  assert.strictEqual(Pol.checkRequest('https://[fe80::1]/a.png', 'image', BLOCK).reason, 'internal');
  assert.strictEqual(Pol.checkRequest('https://i.ytimg.com/vi/x.jpg', 'image', ALLOW).ok, true);
  assert.strictEqual(Pol.checkRequest('https://bad.example/a.js', 'script', BLOCK).ok, true);
  // Every name is resolved by the request filter (Electron 44 has Chromium's LNA check off).
  for (const [u, type] of [['https://i.ytimg.com/x.jpg', 'image'], ['wss://chat.example/ws', 'webSocket'], ['http://a.example/b', 'ping'], ['https://a.example/f', 'xhr']]) {
    assert.strictEqual(Pol.checkRequest(u, type, BLOCK).needsDns, true, u);
  }
  assert.strictEqual(Pol.checkRequest('https://8.8.8.8/x', 'xhr', BLOCK).needsDns, false, 'public literal: nothing to resolve');
  assert.strictEqual(Pol.checkRequest('blob:https://a.example/uuid', 'media', BLOCK).needsDns, false);
  assert.strictEqual(Pol.checkRequest('https://router/', 'subFrame', BLOCK).reason, 'internal');
  assert.strictEqual(Pol.checkRequest('https://youtube.com/', 'mainFrame', ALLOW).ok, true);
  assert.strictEqual(Pol.checkRequest('https://example.com/', 'mainFrame', ALLOW).reason, 'not-allowed');
});

test('permissions: everything denied, LNA explicitly, fullscreen allowed, capture only for the capture page', () => {
  for (const p of ['local-network', 'loopback-network', 'local-network-access']) {
    assert.strictEqual(Pol.decidePermission(p, false), false, p);
    assert.strictEqual(Pol.decidePermission(p, true), false, p + ' (capture page)');
  }
  for (const p of ['media', 'display-capture', 'geolocation', 'notifications', 'clipboard-read', 'clipboard-sanitized-write', 'hid', 'usb',
    'serial', 'midi', 'openExternal', 'pointerLock', 'keyboardLock', 'fileSystem', 'window-management', 'unknown']) {
    assert.strictEqual(Pol.decidePermission(p, false), false, p);
  }
  assert.strictEqual(Pol.decidePermission('fullscreen', false), true);
  assert.strictEqual(Pol.decidePermission('media', true), true);
  assert.strictEqual(Pol.decidePermission('display-capture', true), true);
  assert.strictEqual(Pol.decidePermission('geolocation', true), false);
});

/** Minimal stand-in for an Electron Session, enough to drive the request wiring. */
function fakeSession(resolve, proxyFor = () => 'DIRECT') {
  const ses = new EventEmitter();
  ses.handlers = {};
  ses.webRequest = {};
  ses.resolved = [];
  for (const n of ['onBeforeRequest', 'onBeforeRedirect', 'onResponseStarted', 'onCompleted', 'onHeadersReceived', 'onBeforeSendHeaders']) {
    ses.webRequest[n] = (a, b) => { ses.handlers[n] = typeof a === 'function' ? a : b; ses.filters = ses.filters || {}; ses.filters[n] = typeof a === 'function' ? null : a; };
  }
  ses.resolveHost = host => {
    ses.resolved.push(host);
    return resolve(host);
  };
  ses.resolveProxy = url => Promise.resolve(proxyFor(url));
  return ses;
}

function decide(ses, details) {
  return new Promise(r => ses.handlers.onBeforeRequest(details, resp => r(resp)));
}

test('request wiring: literal block, DNS answer check (fail closed), capture page exempt', async () => {
  const blocked = [];
  const ses = fakeSession(host => {
    if (host === 'rebind.test') return Promise.resolve({ endpoints: [{ address: '93.184.216.34', family: 'ipv4' }, { address: '127.0.0.1', family: 'ipv4' }] });
    if (host === 'good.test') return Promise.resolve({ endpoints: [{ address: '93.184.216.34', family: 'ipv4' }] });
    if (host === 'empty.test') return Promise.resolve({ endpoints: [] });
    if (host === 'slow.test') return new Promise(() => {});
    return Promise.reject(new Error('ERR_NAME_NOT_RESOLVED'));
  });
  Pol.attachRequestPolicy(ses, {
    policy: () => BLOCK, allowFile: false, isExempt: id => id === 99, dnsTimeoutMs: 50,
    onBlocked: (id, info) => blocked.push(info.reason), onInternalIp: () => {},
  });
  assert.deepStrictEqual(await decide(ses, { url: 'http://127.0.0.1/', resourceType: 'mainFrame', webContentsId: 1 }), { cancel: true });
  assert.deepStrictEqual(await decide(ses, { url: 'https://rebind.test/', resourceType: 'mainFrame', webContentsId: 1 }), { cancel: true });
  assert.deepStrictEqual(await decide(ses, { url: 'https://good.test/', resourceType: 'subFrame', webContentsId: 1 }), {});
  assert.deepStrictEqual(await decide(ses, { url: 'https://nx.test/', resourceType: 'mainFrame', webContentsId: 1 }), { cancel: true });
  assert.deepStrictEqual(await decide(ses, { url: 'https://empty.test/', resourceType: 'mainFrame', webContentsId: 1 }), { cancel: true });
  assert.deepStrictEqual(await decide(ses, { url: 'https://slow.test/', resourceType: 'mainFrame', webContentsId: 1 }), { cancel: true });
  assert.deepStrictEqual(await decide(ses, { url: 'file:///x', resourceType: 'mainFrame', webContentsId: 99 }), {});
  assert.deepStrictEqual(blocked, ['internal', 'internal-dns', 'dns-failed', 'dns-failed', 'dns-failed']);
});

test('LNA replacement: SUBRESOURCES whose name resolves inside are cancelled before connecting (fetch, img, ws, beacon)', async () => {
  // Wave 1 left these to Chromium's Local Network Access check, which Electron 44 has switched off,
  // so a public page could reach the player's router by name. Each one must now be cancelled.
  const blocked = [];
  const ses = fakeSession(host => Promise.resolve(host.endsWith('.lan-by-name.test')
    ? { endpoints: [{ address: '192.168.0.1', family: 'ipv4' }] } : { endpoints: [{ address: '93.184.216.34', family: 'ipv4' }] }));
  Pol.attachRequestPolicy(ses, {
    policy: () => BLOCK, allowFile: false, isExempt: () => false, onInternalIp: () => {},
    onBlocked: (id, info) => blocked.push([info.resourceType, info.reason]),
  });
  for (const [url, type] of [
    ['http://router.lan-by-name.test/a.png', 'image'],
    ['http://router.lan-by-name.test/apply.cgi', 'xhr'],
    ['ws://router.lan-by-name.test:8080/', 'webSocket'],
    ['https://nas.lan-by-name.test/beacon', 'ping'],
    ['https://nas.lan-by-name.test/x.js', 'script'],
  ]) {
    assert.deepStrictEqual(await decide(ses, { url, resourceType: type, webContentsId: 1 }), { cancel: true }, url);
  }
  assert.deepStrictEqual(await decide(ses, { url: 'https://cdn.public.test/a.png', resourceType: 'image', webContentsId: 1 }), {});
  assert.deepStrictEqual(blocked, [['image', 'internal-dns'], ['xhr', 'internal-dns'], ['webSocket', 'internal-dns'], ['ping', 'internal-dns'], ['script', 'internal-dns']]);
});

test('verdict cache: one lookup per session and host for the TTL, shared while in flight, failures retried sooner', async () => {
  let now = 1000;
  let fail = false;
  const ses = fakeSession(() => (fail ? Promise.reject(new Error('ERR_NAME_NOT_RESOLVED')) : Promise.resolve({ endpoints: [{ address: '93.184.216.34' }] })));
  const cache = Pol.attachRequestPolicy(ses, {
    policy: () => BLOCK, allowFile: false, isExempt: () => false, onInternalIp: () => {}, onBlocked: () => {}, now: () => now,
  });
  const many = await Promise.all([1, 2, 3, 4].map(i => decide(ses, { url: 'https://cdn.test/' + i + '.png', resourceType: 'image', webContentsId: 1 })));
  assert.deepStrictEqual(many, [{}, {}, {}, {}]);
  assert.strictEqual(ses.resolved.length, 1, 'concurrent requests share one lookup');
  now += Pol.VERDICT_TTL_MS - 1;
  await decide(ses, { url: 'https://cdn.test/5.png', resourceType: 'image', webContentsId: 1 });
  assert.strictEqual(ses.resolved.length, 1, 'cached within the TTL');
  now += 2;
  fail = true;
  assert.deepStrictEqual(await decide(ses, { url: 'https://cdn.test/6.png', resourceType: 'image', webContentsId: 1 }), { cancel: true }, 'fail closed');
  assert.strictEqual(ses.resolved.length, 2, 'looked up again after the TTL');
  fail = false;
  now += Pol.FAILURE_TTL_MS + 1;
  assert.deepStrictEqual(await decide(ses, { url: 'https://cdn.test/7.png', resourceType: 'image', webContentsId: 1 }), {});
  assert.strictEqual(ses.resolved.length, 3, 'a failure is retried after the shorter failure TTL');
  cache.clear();
  await decide(ses, { url: 'https://cdn.test/8.png', resourceType: 'image', webContentsId: 1 });
  assert.strictEqual(ses.resolved.length, 4, 'clear() forgets everything (session reuse)');
});

test('verdict cache is bounded', async () => {
  const ses = fakeSession(() => Promise.resolve({ endpoints: [{ address: '93.184.216.34' }] }));
  const cache = new Pol.SessionNetCache(ses);
  for (let i = 0; i < Pol.MAX_CACHED_HOSTS + 50; i++) await cache.verdictFor('h' + i + '.test');
  assert.strictEqual(cache.verdicts.size, Pol.MAX_CACHED_HOSTS);
  assert.ok(!cache.verdicts.has('h0.test') && cache.verdicts.has('h' + (Pol.MAX_CACHED_HOSTS + 49) + '.test'), 'oldest evicted');
});

test('proxy: a name that fails to resolve behind a proxy is refused as dns-via-proxy, directly as dns-failed', async () => {
  const reasons = [];
  const mk = proxy => {
    const ses = fakeSession(() => Promise.reject(new Error('ERR_NAME_NOT_RESOLVED')), () => proxy);
    Pol.attachRequestPolicy(ses, { policy: () => BLOCK, allowFile: false, isExempt: () => false, onInternalIp: () => {}, onBlocked: (id, i) => reasons.push(i.reason) });
    return ses;
  };
  assert.deepStrictEqual(await decide(mk('PROXY 127.0.0.1:3128'), { url: 'https://only-the-proxy-knows.test/', resourceType: 'mainFrame', webContentsId: 1 }), { cancel: true });
  assert.deepStrictEqual(await decide(mk('DIRECT'), { url: 'https://nx.test/', resourceType: 'mainFrame', webContentsId: 1 }), { cancel: true });
  assert.deepStrictEqual(reasons, ['dns-via-proxy', 'dns-failed']);
  assert.strictEqual(Pol.isProxiedAnswer('PROXY 10.0.0.1:8080; DIRECT'), true);
  assert.strictEqual(Pol.isProxiedAnswer('SOCKS5 127.0.0.1:1080'), true);
  assert.strictEqual(Pol.isProxiedAnswer('DIRECT'), false);
  assert.strictEqual(Pol.isProxiedAnswer(' direct '), false);
  assert.strictEqual(Pol.isProxiedAnswer(undefined), false);
});

test('proxy: the response detector ignores details.ip of proxied requests (it is the proxy, often loopback)', async () => {
  const seen = [];
  const ses = fakeSession(() => Promise.resolve({ endpoints: [] }), url => (url.includes('via-proxy') ? 'PROXY 127.0.0.1:3128' : 'DIRECT'));
  Pol.attachRequestPolicy(ses, { policy: () => BLOCK, allowFile: false, isExempt: () => false, onBlocked: () => {}, onInternalIp: (id, info) => seen.push(info.url) });
  ses.handlers.onResponseStarted({ url: 'https://via-proxy.test/', ip: '127.0.0.1', webContentsId: 1 });
  ses.handlers.onCompleted({ url: 'https://direct.test/', ip: '127.0.0.1', webContentsId: 1 });
  await new Promise(r => setTimeout(r, 10));
  assert.deepStrictEqual(seen, ['https://direct.test/']);
});

test('response detector: reports internal details.ip on redirect, response start and completion', async () => {
  const seen = [];
  const ses = fakeSession(() => Promise.reject(new Error('unused')));
  Pol.attachRequestPolicy(ses, {
    policy: () => BLOCK, allowFile: false, isExempt: id => id === 99, onBlocked: () => {},
    onInternalIp: (id, info) => seen.push([id, info.ip]),
  });
  ses.handlers.onResponseStarted({ url: 'https://a/', ip: '10.1.2.3', webContentsId: 1, resourceType: 'xhr' });
  ses.handlers.onBeforeRedirect({ url: 'https://a/', ip: '8.8.8.8', webContentsId: 1 });
  ses.handlers.onCompleted({ url: 'https://a/', webContentsId: 1 });
  ses.handlers.onCompleted({ url: 'https://a/', ip: '::1', webContentsId: 2 });
  ses.handlers.onCompleted({ url: 'https://a/', ip: '127.0.0.1', webContentsId: 99 });
  await new Promise(r => setTimeout(r, 10)); // the proxy decision is asynchronous
  assert.deepStrictEqual(seen, [[1, '10.1.2.3'], [2, '::1']]);
});

test('permission wiring: request and check handlers agree; denials are reported', () => {
  const ses = new EventEmitter();
  let request;
  let check;
  ses.setPermissionRequestHandler = fn => { request = fn; };
  ses.setPermissionCheckHandler = fn => { check = fn; };
  const denied = [];
  Pol.attachPermissionPolicy(ses, { isCapturePage: id => id === 5, onDenied: (id, p) => denied.push([id, p]) });
  const answers = [];
  request({ id: 1 }, 'loopback-network', ok => answers.push(ok));
  request({ id: 1 }, 'fullscreen', ok => answers.push(ok));
  request({ id: 5 }, 'media', ok => answers.push(ok));
  assert.deepStrictEqual(answers, [false, true, true]);
  assert.deepStrictEqual(denied, [[1, 'loopback-network']]);
  assert.strictEqual(check({ id: 1 }, 'local-network-access'), false);
  assert.strictEqual(check(null, 'notifications'), false);
  let picked = 'unset';
  ses.emit('select-hid-device', { preventDefault() {} }, {}, id => { picked = id; });
  assert.strictEqual(picked, undefined);
});

test('document policy: every main-frame and iframe response gets a CSP sandbox without allow-modals', () => {
  const ses = fakeSession(() => Promise.resolve({ endpoints: [] }));
  Pol.attachDocumentPolicy(ses, { isExempt: id => id === 99, log: null });
  assert.deepStrictEqual(ses.filters.onHeadersReceived.types, ['mainFrame', 'subFrame']);
  let got;
  ses.handlers.onHeadersReceived({ webContentsId: 1, responseHeaders: { 'content-security-policy': ["default-src 'self'"], 'Content-Type': ['text/html'] } }, r => { got = r; });
  assert.deepStrictEqual(got.responseHeaders['content-security-policy'], ["default-src 'self'", Pol.DOCUMENT_SANDBOX], 'the site CSP is kept, ours added');
  assert.deepStrictEqual(got.responseHeaders['Content-Type'], ['text/html']);
  ses.handlers.onHeadersReceived({ webContentsId: 1, responseHeaders: undefined }, r => { got = r; });
  assert.deepStrictEqual(got.responseHeaders, { 'Content-Security-Policy': [Pol.DOCUMENT_SANDBOX] });
  ses.handlers.onHeadersReceived({ webContentsId: 99, responseHeaders: {} }, r => { got = r; });
  assert.deepStrictEqual(got, {}, 'our own capture page is exempt');
  const flags = Pol.DOCUMENT_SANDBOX.split(/\s+/);
  assert.strictEqual(flags[0], 'sandbox');
  assert.ok(!flags.includes('allow-modals'), 'print/alert/confirm/prompt blocked in every frame');
  for (const f of ['allow-scripts', 'allow-same-origin', 'allow-forms', 'allow-popups']) assert.ok(flags.includes(f), f);
  assert.ok(flags.includes('allow-downloads'), 'downloads still reach will-download (cancelled and reported, not dropped silently)');
  assert.ok(!flags.includes('allow-top-navigation'), 'frames may move the top page only after user activation');
});

test('document policy: service worker scripts are refused (they could serve documents without the header)', () => {
  const ses = fakeSession(() => Promise.resolve({ endpoints: [] }));
  Pol.attachDocumentPolicy(ses, { isExempt: () => false, log: null });
  assert.deepStrictEqual(ses.filters.onBeforeSendHeaders.types, ['script']);
  const answers = [];
  ses.handlers.onBeforeSendHeaders({ url: 'https://a.example/sw.js', requestHeaders: { 'Service-Worker': 'script' } }, r => answers.push(r));
  ses.handlers.onBeforeSendHeaders({ url: 'https://a.example/app.js', requestHeaders: { Accept: '*/*' } }, r => answers.push(r));
  ses.handlers.onBeforeSendHeaders({ url: 'https://a.example/sw2.js', requestHeaders: { 'service-worker': 'Script' } }, r => answers.push(r));
  assert.deepStrictEqual(answers, [{ cancel: true }, {}, { cancel: true }]);
});

test('withTimeout rejects slow promises and passes fast ones through', async () => {
  assert.strictEqual(await Pol.withTimeout(Promise.resolve(3), 50), 3);
  await assert.rejects(Pol.withTimeout(new Promise(() => {}), 20), /timeout/);
});
