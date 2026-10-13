'use strict';
const test = require('node:test');
const assert = require('node:assert');
const { InputMapper, InputError, normalizeModifiers, toAcceleratorKey, charEvents, MOD_BITS } = require('../src/input');

test('modifiers: Java bitmask and name arrays both map to Electron names', () => {
  assert.deepStrictEqual(normalizeModifiers(0), []);
  assert.deepStrictEqual(normalizeModifiers(MOD_BITS.shift | MOD_BITS.control), ['shift', 'control']);
  assert.deepStrictEqual(normalizeModifiers(15), ['shift', 'control', 'alt', 'meta']);
  assert.deepStrictEqual(normalizeModifiers(['Ctrl', 'shift', 'cmd', 'bogus', 'shift']), ['control', 'shift', 'meta']);
  assert.deepStrictEqual(normalizeModifiers(undefined), []);
});

test('key names: DOM names and accelerator names become Electron keyCodes', () => {
  assert.strictEqual(toAcceleratorKey('ArrowLeft'), 'Left');
  assert.strictEqual(toAcceleratorKey('ArrowUp'), 'Up');
  assert.strictEqual(toAcceleratorKey('Enter'), 'Enter');
  assert.strictEqual(toAcceleratorKey('return'), 'Return');
  assert.strictEqual(toAcceleratorKey('Backspace'), 'Backspace');
  assert.strictEqual(toAcceleratorKey(' '), 'Space');
  assert.strictEqual(toAcceleratorKey('Escape'), 'Escape');
  assert.strictEqual(toAcceleratorKey('Esc'), 'Escape');
  assert.strictEqual(toAcceleratorKey('pagedown'), 'PageDown');
  assert.strictEqual(toAcceleratorKey('F12'), 'F12');
  assert.strictEqual(toAcceleratorKey('f24'), 'F24');
  assert.strictEqual(toAcceleratorKey('AltGraph'), 'AltGr');
  assert.strictEqual(toAcceleratorKey('a'), 'a');
  assert.strictEqual(toAcceleratorKey('A'), 'A');
  assert.strictEqual(toAcceleratorKey('$'), '$');
});

test('unknown or non-ASCII key names are dropped (their text arrives as char)', () => {
  for (const k of ['ç', 'Dead', 'Unidentified', 'F25', '', null, 'x'.repeat(40), '\u0007']) assert.strictEqual(toAcceleratorKey(k), null, String(k));
});

test('char: one event per code point, accents and astral characters intact, controls dropped', () => {
  const ev = charEvents('ação ç $%&\r\n\t\u0008😀', []);
  assert.deepStrictEqual(ev.map(e => e.keyCode), ['a', 'ç', 'ã', 'o', ' ', 'ç', ' ', '$', '%', '&', '😀']);
  assert.ok(ev.every(e => e.type === 'char'));
  assert.strictEqual(charEvents('x'.repeat(200), []).length, 64);
  assert.throws(() => charEvents(5, []), InputError);
});

test('mouse: buttons, click count and held-button modifiers during drags', () => {
  const m = new InputMapper(640, 360);
  assert.deepStrictEqual(m.map({ type: 'mouseDown', x: 10, y: 20, button: 'left', clickCount: 2 }),
    [{ type: 'mouseDown', x: 10, y: 20, button: 'left', clickCount: 2, modifiers: ['leftbuttondown'] }]);
  assert.deepStrictEqual(m.map({ type: 'mouseMove', x: 30, y: 40, modifiers: 1 }),
    [{ type: 'mouseMove', x: 30, y: 40, modifiers: ['shift', 'leftbuttondown'] }]);
  assert.deepStrictEqual(m.map({ type: 'mouseUp', x: 30, y: 40, button: 'left', clickCount: 2 }),
    [{ type: 'mouseUp', x: 30, y: 40, button: 'left', clickCount: 2, modifiers: [] }]);
  assert.deepStrictEqual(m.map({ type: 'mouseMove', x: 31, y: 41 }), [{ type: 'mouseMove', x: 31, y: 41, modifiers: [] }]);
});

test('mouse: coordinates are rounded and clamped to the page; bad buttons default to left', () => {
  const m = new InputMapper(640, 360);
  const [e] = m.map({ type: 'mouseDown', x: 9999.7, y: -4, button: 'back', clickCount: 9 });
  assert.deepStrictEqual([e.x, e.y, e.button, e.clickCount], [639, 0, 'left', 3]);
  m.setSize(100, 50);
  assert.deepStrictEqual(m.map({ type: 'mouseMove', x: 12.4, y: 70 })[0], { type: 'mouseMove', x: 12, y: 49, modifiers: ['leftbuttondown'] });
  m.reset();
  assert.deepStrictEqual(m.map({ type: 'mouseMove', x: 1, y: 1 })[0].modifiers, []);
});

test('wheel: Chromium sign passed through, deltas rounded and capped', () => {
  const m = new InputMapper(640, 360);
  assert.deepStrictEqual(m.map({ type: 'wheel', x: 5, y: 6, deltaX: 0, deltaY: -120 }),
    [{ type: 'mouseWheel', x: 5, y: 6, deltaX: 0, deltaY: -120, modifiers: [] }]);
  const [e] = m.map({ type: 'wheel', x: 5, y: 6, deltaY: 1e9, modifiers: ['control'] });
  assert.deepStrictEqual([e.deltaX, e.deltaY, e.modifiers], [0, 10000, ['control']]);
});

test('keys: keyDown/keyUp with modifiers; Enter adds a "\\r" char unless a command modifier is held', () => {
  const m = new InputMapper();
  assert.deepStrictEqual(m.map({ type: 'keyDown', keyCode: 'a', modifiers: ['control'] }), [{ type: 'keyDown', keyCode: 'a', modifiers: ['control'] }]);
  assert.deepStrictEqual(m.map({ type: 'keyDown', keyCode: 'Enter', modifiers: 1 }), [
    { type: 'keyDown', keyCode: 'Enter', modifiers: ['shift'] },
    { type: 'char', keyCode: '\r', modifiers: ['shift'] },
  ]);
  assert.deepStrictEqual(m.map({ type: 'keyDown', keyCode: 'Enter', modifiers: ['control'] }), [{ type: 'keyDown', keyCode: 'Enter', modifiers: ['control'] }]);
  assert.deepStrictEqual(m.map({ type: 'keyUp', keyCode: 'Enter' }), [{ type: 'keyUp', keyCode: 'Enter', modifiers: [] }]);
  assert.deepStrictEqual(m.map({ type: 'keyDown', keyCode: 'Dead' }), []);
});

test('invalid INPUT payloads throw InputError (the router logs and drops them)', () => {
  const m = new InputMapper();
  for (const bad of [null, 'click', { type: 'teleport' }, { type: 'mouseMove', x: 'a', y: 1 }, { type: 'mouseDown', x: 1 },
    { type: 'wheel', x: 1, y: 1, deltaY: 'up' }, { type: 'char', text: 3 }]) {
    assert.throws(() => m.map(bad), InputError, JSON.stringify(bad));
  }
});
