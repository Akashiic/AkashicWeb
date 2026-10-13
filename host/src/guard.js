'use strict';
// Best-effort guard against native OS dialogs. Electron opens the system print dialog for
// window.print() and the system file chooser for <input type=file> from an offscreen window too
// (shell/browser/file_select_helper.cc, PrintViewManagerElectron), and offers no API to veto them.
// On a public screen that is a dialog popping over the player's game. This script runs in the main
// world of every frame (it has to override page globals) after each load, so a page script that
// calls print() before the guard is installed still gets through: see host/README.md, open issues.

/** Page-side, serialised. Idempotent per document. */
function akwGuard(key) {
  const g = globalThis;
  if (g[key]) return false;
  Object.defineProperty(g, key, { value: true, enumerable: false, configurable: false, writable: false });
  const noop = function print() {};
  try { Object.defineProperty(g, 'print', { value: noop, writable: false, configurable: false }); } catch (e) { /* already locked */ }
  const reject = () => Promise.reject(new DOMException('File pickers are disabled on in-world screens', 'AbortError'));
  ['showOpenFilePicker', 'showSaveFilePicker', 'showDirectoryPicker'].forEach(n => {
    if (n in g) {
      try { Object.defineProperty(g, n, { value: reject, writable: false, configurable: false }); } catch (e) { /* locked */ }
    }
  });
  const proto = g.HTMLInputElement && g.HTMLInputElement.prototype;
  if (proto && typeof proto.showPicker === 'function') {
    const original = proto.showPicker;
    try {
      Object.defineProperty(proto, 'showPicker', {
        value: function showPicker() {
          if (this.type === 'file') return undefined;
          return original.call(this);
        },
        writable: false,
        configurable: false,
      });
    } catch (e) { /* locked */ }
  }
  // Clicks (real or scripted, including <label for>) on a file input open the chooser by default.
  g.addEventListener('click', e => {
    const t = e.target;
    if (t && t.tagName === 'INPUT' && String(t.type).toLowerCase() === 'file') e.preventDefault();
  }, true);
  return true;
}

function guardScript(key) {
  return '(' + akwGuard.toString() + ')(' + JSON.stringify(key) + ')';
}

module.exports = { akwGuard, guardScript };
