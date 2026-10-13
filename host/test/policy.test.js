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

test('subresources: literal internal hosts refused for http/https/ws/wss; policy lists not applied', () => {
  assert.strictEqual(Pol.checkRequest('http://127.0.0.1:8080/x', 'xhr', BLOCK).reason, 'internal');
  assert.strictEqual(Pol.checkRequest('ws://192.168.1.10/socket', 'webSocket', BLOCK).reason, 'internal');
  assert.strictEqual(Pol.checkRequest('https://[fe80::1]/a.png', 'image', BLOCK).reason, 'internal');
  assert.strictEqual(Pol.checkRequest('https://i.ytimg.com/vi/x.jpg', 'image', ALLOW).ok, true);
  assert.strictEqual(Pol.checkRequest('https://bad.example/a.js', 'script', BLOCK).ok, true);
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
function fakeSession(resolve) {
  const ses = new EventEmitter();
  ses.handlers = {};
  ses.webRequest = {};
  for (const n of ['onBeforeRequest', 'onBeforeRedirect', 'onResponseStarted', 'onCompleted']) ses.webRequest[n] = fn => { ses.handlers[n] = fn; };
  ses.resolveHost = host => resolve(host);
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
  // Subresources by name are left to Chromium's LNA check: no DNS round trip per image.
  assert.deepStrictEqual(await decide(ses, { url: 'https://rebind.test/a.png', resourceType: 'image', webContentsId: 1 }), {});
  assert.deepStrictEqual(await decide(ses, { url: 'file:///x', resourceType: 'mainFrame', webContentsId: 99 }), {});
  assert.deepStrictEqual(blocked, ['internal', 'internal-dns', 'dns-failed', 'dns-failed', 'dns-failed']);
});

test('response detector: reports internal details.ip on redirect, response start and completion', () => {
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

test('withTimeout rejects slow promises and passes fast ones through', async () => {
  assert.strictEqual(await Pol.withTimeout(Promise.resolve(3), 50), 3);
  await assert.rejects(Pol.withTimeout(new Promise(() => {}), 20), /timeout/);
});
