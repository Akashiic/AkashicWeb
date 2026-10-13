'use strict';
// Partition pool (sessions.js) with fake sessions; the integration test measures the memory effect.
const test = require('node:test');
const assert = require('node:assert');
const { SessionFactory, PRIVATE_PARTITION } = require('../src/sessions');

function fakeSession(name, behaviour = {}) {
  const ses = { name, calls: [] };
  for (const m of ['clearStorageData', 'clearCache', 'clearAuthCache', 'clearHostResolverCache', 'clearData', 'closeAllConnections']) {
    ses[m] = () => {
      ses.calls.push(m);
      if (behaviour.fail === m) return Promise.reject(new Error('boom'));
      return behaviour.gate ? behaviour.gate.then(() => undefined) : Promise.resolve();
    };
  }
  return ses;
}

function factory(behaviour) {
  const made = [];
  const caches = [];
  const warnings = [];
  const f = new SessionFactory({
    log: { warn: m => warnings.push(m), info() {} },
    fromPartition: name => {
      const s = fakeSession(name, behaviour && behaviour(name));
      made.push(s);
      return s;
    },
    prepare: ses => {
      const cache = { ses, cleared: 0, clear() { this.cleared++; }, verdictFor: () => Promise.resolve({ ok: true }) };
      caches.push(cache);
      return cache;
    },
  });
  return { f, made, caches, warnings };
}

test('a released partition is reused only after cookies/storage, cache, auth cache, DNS cache and connections are cleared', async () => {
  const { f, made, caches } = factory();
  const a = f.forScreen(1, 'shared');
  assert.strictEqual(a.ephemeral, true);
  assert.match(a.partition, /^akw-\d+$/, 'in-memory: no persist: prefix');
  assert.strictEqual(await f.release(a), true);
  assert.deepStrictEqual(made[0].calls.sort(), ['clearAuthCache', 'clearCache', 'clearData', 'clearHostResolverCache', 'clearStorageData', 'closeAllConnections']);
  assert.strictEqual(caches[0].cleared, 1, 'the host verdict cache too');
  const b = f.forScreen(2, 'shared');
  assert.strictEqual(b.session, a.session, 'reused');
  assert.strictEqual(made.length, 1, 'no new partition: memory stays flat under CREATE/DESTROY churn');
  assert.deepStrictEqual(f.stats, { created: 1, reused: 1, released: 1, broken: 0 });
});

test('a partition still being cleared is never handed out; a fresh one is made meanwhile', async () => {
  let open;
  const gate = new Promise(r => { open = r; });
  const { f, made } = factory(name => (name === 'akw-1' ? { gate } : {}));
  const a = f.forScreen(1, 'shared');
  const released = f.release(a);
  const b = f.forScreen(1, 'shared');
  assert.notStrictEqual(b.session, a.session);
  assert.strictEqual(made.length, 2);
  open();
  await released;
  const c = f.forScreen(3, 'shared');
  assert.strictEqual(c.session, a.session, 'reused once cleared');
});

test('a partition whose clearing fails is never reused', async () => {
  const { f, made, warnings } = factory(() => ({ fail: 'clearStorageData' }));
  const a = f.forScreen(1, 'shared');
  assert.strictEqual(await f.release(a), false);
  const b = f.forScreen(1, 'shared');
  assert.notStrictEqual(b.session, a.session);
  assert.strictEqual(made.length, 2);
  assert.strictEqual(f.stats.broken, 1);
  assert.match(warnings[0], /not reused: clearStorageData/);
});

test('PRIVATE screens use the persistent partition, which is never cleared or pooled', async () => {
  const { f, made } = factory();
  const p = f.forScreen(1, 'private');
  assert.strictEqual(p.partition, PRIVATE_PARTITION);
  assert.strictEqual(p.ephemeral, false);
  assert.strictEqual(await f.release(p), false);
  assert.deepStrictEqual(made[0].calls, []);
  assert.strictEqual(f.free.length, 0);
});
