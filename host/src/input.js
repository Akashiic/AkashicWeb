'use strict';
// INPUT (docs/PROTOCOL.md) -> webContents.sendInputEvent objects. Pure and stateful only in the set
// of pressed mouse buttons, which Chromium needs on mouseMove to drag (text selection, scrollbars,
// sliders such as the YouTube seek bar).
//
// Input contract with the game (M5 KeyboardRouter/InputTranslator); each rule has a test:
//  - keyDown/keyUp carry a key name: a DOM `key` value ("ArrowLeft", "Enter", "a", " ") or an Electron
//    accelerator name ("Left", "PageUp", "Space"). Electron turns keyDown into a RawKeyDown, which runs
//    shortcuts and editing commands (Backspace, arrows, Ctrl+A) but never inserts text. In particular
//    keyDown "Space" inserts nothing: the game also sends char " ", which types the space.
//  - Text arrives only as `char`, accents already composed by the game. A char event may carry several
//    characters (a paste, up to MAX_CHARS code points): every one is typed, in order, one Chromium
//    char event per code point (surrogate pairs stay together).
//  - Enter: Blink inserts the newline / submits the form on the "\r" keypress, so keyDown Enter without
//    Ctrl/Alt/Meta is followed by a synthetic char "\r", and a "\r" in char text is dropped (the game
//    sends keyDown Enter plus char "\r" for one key press).
//  - Line breaks inside char text (a pasted multi-line text, normalised to "\n" by the game) are typed
//    as Shift+Enter ("\r" with Shift: Blink's InsertLineBreak), a tab as "\t"; other control
//    characters are dropped.
//  - wheel: deltaY > 0 scrolls up (towards the top of the page) and < 0 scrolls down, in pixels; the
//    game sends notches x 100. Same sign as LWJGL Mouse.getDWheel() and Chromium's mouseWheel.

const MOD_BITS = Object.freeze({ shift: 1, control: 2, alt: 4, meta: 8 }); // ScreenInput.MOD_* in Java
const MOD_ALIASES = Object.freeze({
  shift: 'shift', control: 'control', ctrl: 'control', alt: 'alt', meta: 'meta', cmd: 'meta', command: 'meta',
  super: 'meta', capslock: 'capslock', numlock: 'numlock',
});
const BUTTON_MODS = Object.freeze({ left: 'leftbuttondown', middle: 'middlebuttondown', right: 'rightbuttondown' });
const BUTTONS = ['left', 'middle', 'right'];
const MAX_COORD = 65535;
const MAX_WHEEL = 10000;
// Code points per char event. The game caps a paste at 512 UTF-16 units (M5 KeyboardRouter); the host
// cap only bounds a broken or hostile caller.
const MAX_CHARS = 4096;

// DOM KeyboardEvent.key values that differ from Electron accelerator names.
const DOM_TO_ACCELERATOR = Object.freeze({
  arrowup: 'Up', arrowdown: 'Down', arrowleft: 'Left', arrowright: 'Right',
  ' ': 'Space', spacebar: 'Space', esc: 'Escape', del: 'Delete', altgraph: 'AltGr', os: 'Meta',
});
// Electron accelerator key identifiers (shell/common/keyboard_util.cc, Electron 44), canonical case.
const ACCELERATOR_NAMES = [
  'Alt', 'AltGr', 'Backspace', 'CapsLock', 'Control', 'Delete', 'Down', 'End', 'Enter', 'Escape', 'Home', 'Insert',
  'Left', 'MediaNextTrack', 'MediaPlayPause', 'MediaPreviousTrack', 'MediaStop', 'Meta', 'NumLock', 'PageDown',
  'PageUp', 'Plus', 'PrintScreen', 'Return', 'Right', 'ScrollLock', 'Shift', 'Space', 'Super', 'Tab', 'Up',
  'num0', 'num1', 'num2', 'num3', 'num4', 'num5', 'num6', 'num7', 'num8', 'num9', 'numadd', 'numdec', 'numdiv',
  'nummult', 'numsub',
];
for (let i = 1; i <= 24; i++) ACCELERATOR_NAMES.push('F' + i);
const ACCELERATOR_BY_LOWER = new Map(ACCELERATOR_NAMES.map(n => [n.toLowerCase(), n]));

class InputError extends Error {
  constructor(code, detail) {
    super(code + (detail ? ': ' + detail : ''));
    this.code = code;
  }
}

/** Modifiers as Electron names, from an array of names or the Java ScreenInput.MOD_* bitmask. */
function normalizeModifiers(mods) {
  const out = [];
  const add = m => { if (m && !out.includes(m)) out.push(m); };
  if (typeof mods === 'number' && Number.isInteger(mods)) {
    for (const [name, bit] of Object.entries(MOD_BITS)) if (mods & bit) add(name);
  } else if (Array.isArray(mods)) {
    for (const m of mods) if (typeof m === 'string') add(MOD_ALIASES[m.toLowerCase()]);
  }
  return out;
}

/** Electron keyCode for a key name, or null when Electron would not recognise it. */
function toAcceleratorKey(key) {
  if (typeof key !== 'string' || key.length === 0 || key.length > 32) return null;
  if (key.length === 1) {
    const c = key.charCodeAt(0);
    return c >= 0x21 && c <= 0x7e ? key : key === ' ' ? 'Space' : null;
  }
  const lower = key.toLowerCase();
  if (DOM_TO_ACCELERATOR[lower]) return DOM_TO_ACCELERATOR[lower];
  return ACCELERATOR_BY_LOWER.get(lower) || null;
}

function isControlCodePoint(cp) {
  return cp < 0x20 || (cp >= 0x7f && cp <= 0x9f);
}

/**
 * Chromium char events for `text`, in order: one per printable code point (surrogate pairs stay
 * together), "\n" as Shift+"\r" (a line break), "\t" as a tab; "\r" and other controls are dropped.
 */
function charEvents(text, modifiers) {
  if (typeof text !== 'string') throw new InputError('bad-text');
  const out = [];
  let count = 0;
  for (const ch of text) {
    if (++count > MAX_CHARS) break;
    if (ch === '\n') {
      out.push({ type: 'char', keyCode: '\r', modifiers: modifiers.includes('shift') ? modifiers : modifiers.concat(['shift']) });
      continue;
    }
    if (ch === '\t') {
      out.push({ type: 'char', keyCode: '\t', modifiers });
      continue;
    }
    if (isControlCodePoint(ch.codePointAt(0))) continue;
    out.push({ type: 'char', keyCode: ch, modifiers });
  }
  return out;
}

function coord(v, max, name) {
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new InputError('bad-' + name, String(v));
  return Math.min(Math.max(0, max - 1), Math.max(0, Math.round(v)));
}

function delta(v) {
  if (v == null) return 0;
  if (typeof v !== 'number' || !Number.isFinite(v)) throw new InputError('bad-delta', String(v));
  return Math.max(-MAX_WHEEL, Math.min(MAX_WHEEL, Math.round(v)));
}

class InputMapper {
  constructor(width = MAX_COORD, height = MAX_COORD) {
    this.width = width;
    this.height = height;
    this.pressed = new Set();
  }

  setSize(width, height) {
    this.width = width;
    this.height = height;
  }

  /** Forgets pressed buttons, e.g. after the controller changes or the page navigates. */
  reset() {
    this.pressed.clear();
  }

  _buttonMods() {
    return BUTTONS.filter(b => this.pressed.has(b)).map(b => BUTTON_MODS[b]);
  }

  /** Maps one INPUT payload to the list of events to send, in order. Throws InputError if invalid. */
  map(evt) {
    if (evt == null || typeof evt !== 'object') throw new InputError('not-object');
    const mods = normalizeModifiers(evt.modifiers);
    switch (evt.type) {
      case 'mouseMove': {
        const x = coord(evt.x, this.width, 'x');
        const y = coord(evt.y, this.height, 'y');
        return [{ type: 'mouseMove', x, y, modifiers: mods.concat(this._buttonMods()) }];
      }
      case 'mouseDown':
      case 'mouseUp': {
        const x = coord(evt.x, this.width, 'x');
        const y = coord(evt.y, this.height, 'y');
        const button = BUTTONS.includes(evt.button) ? evt.button : 'left';
        const clickCount = Number.isInteger(evt.clickCount) ? Math.min(3, Math.max(1, evt.clickCount)) : 1;
        if (evt.type === 'mouseDown') this.pressed.add(button);
        else this.pressed.delete(button);
        return [{ type: evt.type, x, y, button, clickCount, modifiers: mods.concat(this._buttonMods()) }];
      }
      case 'wheel': {
        const x = coord(evt.x, this.width, 'x');
        const y = coord(evt.y, this.height, 'y');
        // Passed through: deltaY > 0 scrolls up (towards the top of the page), the sign of LWJGL's
        // Mouse.getDWheel() and Chromium's mouseWheel; pixels, the game sends notches x 100.
        return [{ type: 'mouseWheel', x, y, deltaX: delta(evt.deltaX), deltaY: delta(evt.deltaY), modifiers: mods }];
      }
      case 'keyDown':
      case 'keyUp': {
        const keyCode = toAcceleratorKey(evt.keyCode);
        if (!keyCode) return [];
        const out = [{ type: evt.type, keyCode, modifiers: mods }];
        const commandMod = mods.includes('control') || mods.includes('alt') || mods.includes('meta');
        if (evt.type === 'keyDown' && (keyCode === 'Enter' || keyCode === 'Return') && !commandMod) {
          out.push({ type: 'char', keyCode: '\r', modifiers: mods });
        }
        return out;
      }
      case 'char':
        return charEvents(evt.text, mods);
      default:
        throw new InputError('bad-type', String(evt.type));
    }
  }
}

module.exports = { MOD_BITS, MAX_CHARS, InputError, InputMapper, normalizeModifiers, toAcceleratorKey, charEvents };
