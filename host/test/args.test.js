'use strict';
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { parseArgs, findForbiddenSwitches, findDisallowedArgs, findForbiddenEnv, EXIT, ArgError } = require('../src/args');

const ABS = path.resolve('/tmp/akw-profile');
const LOG = path.resolve('/tmp/akw.log');
const base = ['electron', '--akw-port=50123', '--akw-parent-pid=4321', '--akw-user-data=' + ABS, '--akw-log=' + LOG];

test('parses the four --akw-* arguments', () => {
  const a = parseArgs(base);
  assert.strictEqual(a.port, 50123);
  assert.strictEqual(a.parentPid, 4321);
  assert.strictEqual(a.userData, ABS);
  assert.strictEqual(a.logFile, LOG);
  assert.strictEqual(a.gpu, false);
  assert.strictEqual(a.testAllowFile, false);
});

test('optional flags', () => {
  const a = parseArgs(base.concat(['--akw-gpu', '--akw-test-allow-file']));
  assert.strictEqual(a.gpu, true);
  assert.strictEqual(a.testAllowFile, true);
  assert.strictEqual(parseArgs(base.slice(0, 4)).logFile, null);
});

test('--akw-parent-pid=0 means "unknown" (the parent poll is skipped), not an error', () => {
  const a = parseArgs(base.map(s => (s.startsWith('--akw-parent-pid=') ? '--akw-parent-pid=0' : s)));
  assert.strictEqual(a.parentPid, 0);
  assert.throws(() => parseArgs(base.map(s => (s.startsWith('--akw-parent-pid=') ? '--akw-parent-pid=00' : s))), /bad-parent-pid/);
  assert.throws(() => parseArgs(base.map(s => (s.startsWith('--akw-parent-pid=') ? '--akw-parent-pid=-1' : s))), /bad-parent-pid/);
});

test('rejects missing or invalid values with a code', () => {
  const without = name => base.filter(s => !s.startsWith('--' + name + '='));
  for (const [argv, code] of [
    [without('akw-port'), 'bad-port'],
    [base.map(s => (s.startsWith('--akw-port=') ? '--akw-port=70000' : s)), 'bad-port'],
    [base.map(s => (s.startsWith('--akw-port=') ? '--akw-port=12ab' : s)), 'bad-port'],
    [base.map(s => (s.startsWith('--akw-port=') ? '--akw-port=0' : s)), 'bad-port'],
    [without('akw-parent-pid'), 'bad-parent-pid'],
    [without('akw-user-data'), 'bad-user-data'],
    [base.map(s => (s.startsWith('--akw-user-data=') ? '--akw-user-data=relative/dir' : s)), 'bad-user-data'],
    [base.map(s => (s.startsWith('--akw-log=') ? '--akw-log=host.log' : s)), 'bad-log'],
  ]) {
    assert.throws(() => parseArgs(argv), err => err instanceof ArgError && err.code === code, code);
  }
});

test('forbidden switches: debugging and web-security switches everywhere', () => {
  assert.deepStrictEqual(findForbiddenSwitches(base, 'linux'), []);
  assert.deepStrictEqual(findForbiddenSwitches(base.concat(['--remote-debugging-port=9222']), 'linux'), ['remote-debugging-port']);
  assert.deepStrictEqual(findForbiddenSwitches(['--inspect'], 'win32'), ['inspect']);
  assert.deepStrictEqual(findForbiddenSwitches(['--inspect-brk=9229'], 'darwin'), ['inspect-brk']);
  assert.deepStrictEqual(findForbiddenSwitches(['-disable-web-security'], 'linux'), ['disable-web-security']);
  assert.deepStrictEqual(findForbiddenSwitches(['--remote-debugging-pipe'], 'linux'), ['remote-debugging-pipe']);
});

test('forbidden switches: --no-sandbox only refused on Windows, any spelling', () => {
  assert.deepStrictEqual(findForbiddenSwitches(['--no-sandbox'], 'linux'), []);
  assert.deepStrictEqual(findForbiddenSwitches(['--no-sandbox'], 'win32'), ['no-sandbox']);
  assert.deepStrictEqual(findForbiddenSwitches(['/No-Sandbox'], 'win32'), ['no-sandbox']);
  assert.deepStrictEqual(findForbiddenSwitches(['--REMOTE-DEBUGGING-PORT=1'], 'win32'), ['remote-debugging-port']);
  // "/x" is a path, not a switch, outside Windows.
  assert.deepStrictEqual(findForbiddenSwitches(['/no-sandbox'], 'linux'), []);
});

test('non-switch arguments and look-alikes are not flagged', () => {
  assert.deepStrictEqual(findForbiddenSwitches(['--inspector-mode', '--debugging', 'inspect', '--akw-log=/tmp/--inspect'], 'linux'), []);
});

test('packaged host: only --akw-* arguments are accepted (allowlist), anything else is named', () => {
  const javaArgs = base.slice(1).concat(['--akw-gpu']);
  assert.deepStrictEqual(findDisallowedArgs(javaArgs, { packaged: true, platform: 'win32' }), []);
  for (const [extra, name] of [
    ['--host-resolver-rules=MAP * 127.0.0.1', 'host-resolver-rules'],
    ['--proxy-server=http://127.0.0.1:8080', 'proxy-server'],
    ['--ignore-certificate-errors', 'ignore-certificate-errors'],
    ['--renderer-cmd-prefix=calc.exe', 'renderer-cmd-prefix'],
    ['--no-sandbox', 'no-sandbox'],
    ['--enable-logging', 'enable-logging'],
    ['--log-file=C:\\x.log', 'log-file'],
    ['--js-flags=--allow-natives-syntax', 'js-flags'],
    ['/remote-debugging-port=9222', 'remote-debugging-port'],
    ['-inspect', 'inspect'],
    ['C:\\some\\app', '<argument>'],
    ['--AKW-PORT=1', 'AKW-PORT'],
    ['--akw_port=1', 'akw_port'],
  ]) {
    assert.deepStrictEqual(findDisallowedArgs(javaArgs.concat([extra]), { packaged: true, platform: 'win32' }), [name], extra);
  }
  // Node options reach process.execArgv; they are not --akw-* either.
  assert.deepStrictEqual(findDisallowedArgs(javaArgs.concat(['--inspect=9229']), { packaged: true, platform: 'linux' }), ['inspect']);
});

test('unpackaged (test) host: permissive denylist; the test harness switches still pass', () => {
  const harness = ['/path/to/host', ...base.slice(1), '--akw-test-allow-file', '--host-resolver-rules=MAP a.test 127.0.0.1',
    '--proxy-server=http://127.0.0.1:1', '--force-device-scale-factor=2', '--no-sandbox', '--unsafely-treat-insecure-origin-as-secure=http://a.test'];
  assert.deepStrictEqual(findDisallowedArgs(harness, { packaged: false, platform: 'linux' }), []);
  assert.deepStrictEqual(findDisallowedArgs(harness.slice(1), { packaged: true, platform: 'linux' }).length, 5, 'none of them in a packaged host');
  for (const s of ['--ignore-certificate-errors', '--disable-site-isolation-trials',
    '--renderer-cmd-prefix=gdb', '--gpu-launcher=x', '--utility-cmd-prefix=x', '--browser-subprocess-path=/x', '--js-flags=--x']) {
    assert.strictEqual(findDisallowedArgs([s], { packaged: false, platform: 'linux' }).length, 1, s);
  }
});

test('packaged host: logging and Node environment variables are refused, unpackaged ignores them', () => {
  assert.deepStrictEqual(findForbiddenEnv({ PATH: 'x', ELECTRON_ENABLE_LOGGING: '1', NODE_OPTIONS: '--inspect' }, true), ['ELECTRON_ENABLE_LOGGING', 'NODE_OPTIONS']);
  assert.deepStrictEqual(findForbiddenEnv({ ELECTRON_ENABLE_LOGGING: '1' }, false), []);
  assert.deepStrictEqual(findForbiddenEnv({ PATH: 'x' }, true), []);
});

test('exit codes are stable (HostSupervisor maps them)', () => {
  assert.deepStrictEqual(EXIT, {
    OK: 0, BAD_ARGS: 64, FORBIDDEN_SWITCH: 65, NO_TOKEN: 66, CONNECT_FAILED: 67, HANDSHAKE: 68, PROTOCOL: 69, INTERNAL: 70,
  });
});
