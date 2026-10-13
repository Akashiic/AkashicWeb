'use strict';
const test = require('node:test');
const assert = require('node:assert');
const path = require('path');
const { parseArgs, findForbiddenSwitches, EXIT, ArgError } = require('../src/args');

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

test('exit codes are stable (HostSupervisor maps them)', () => {
  assert.deepStrictEqual(EXIT, {
    OK: 0, BAD_ARGS: 64, FORBIDDEN_SWITCH: 65, NO_TOKEN: 66, CONNECT_FAILED: 67, HANDSHAKE: 68, PROTOCOL: 69, INTERNAL: 70,
  });
});
