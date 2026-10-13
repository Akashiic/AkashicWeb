'use strict';
// Frame relay: dirty-rect crops of the OSR bitmap and latest-wins scheduling per screen
// (ADR-003 backpressure, ADR-004 damage). Pure logic; the Electron NativeImage is only used through
// getSize()/toBitmap(), so tests pass a fake image.

const P = require('./protocol');

/** Clamps `r` ({x,y,width,height} or {x,y,w,h}) to the page; returns {x,y,w,h} or null if empty. */
function clampRect(r, width, height) {
  if (!r) return null;
  const rw = r.w != null ? r.w : r.width;
  const rh = r.h != null ? r.h : r.height;
  if (![r.x, r.y, rw, rh].every(Number.isFinite)) return null;
  const x0 = Math.max(0, Math.floor(r.x));
  const y0 = Math.max(0, Math.floor(r.y));
  const x1 = Math.min(width, Math.ceil(r.x + rw));
  const y1 = Math.min(height, Math.ceil(r.y + rh));
  if (x1 <= x0 || y1 <= y0) return null;
  return { x: x0, y: y0, w: x1 - x0, h: y1 - y0 };
}

function unionRect(a, b) {
  if (!a) return b;
  if (!b) return a;
  const x = Math.min(a.x, b.x);
  const y = Math.min(a.y, b.y);
  return { x, y, w: Math.max(a.x + a.w, b.x + b.w) - x, h: Math.max(a.y + a.h, b.y + b.h) - y };
}

function fullRect(width, height) {
  return { x: 0, y: 0, w: width, h: height };
}

/**
 * Splits a rect into horizontal bands whose BGRA data fits `maxPixelBytes`. Only pages larger than
 * about 4.1 megapixels need more than one band with the 16 MiB payload cap.
 */
function splitIntoBands(rect, maxPixelBytes) {
  const rowBytes = rect.w * 4;
  const rowsPerBand = Math.floor(maxPixelBytes / rowBytes);
  if (rowsPerBand < 1) throw new RangeError('row too wide for one FRAME: ' + rect.w);
  const bands = [];
  for (let y = rect.y; y < rect.y + rect.h; y += rowsPerBand) {
    bands.push({ x: rect.x, y, w: rect.w, h: Math.min(rowsPerBand, rect.y + rect.h - y) });
  }
  return bands;
}

/** Copies the rect out of a BGRA bitmap with `stride` bytes per row into a tight w*h*4 buffer. */
function cropBgra(bitmap, stride, rect) {
  const rowBytes = rect.w * 4;
  const out = Buffer.allocUnsafe(rowBytes * rect.h);
  for (let r = 0; r < rect.h; r++) {
    const src = (rect.y + r) * stride + rect.x * 4;
    bitmap.copy(out, r * rowBytes, src, src + rowBytes);
  }
  return out;
}

/**
 * Builds the FRAME payloads (each an array of Buffers) for `rect` of a pageW x pageH page.
 * `bitmap` holds BGRA pixels (as NativeImage.toBitmap() returns on little-endian machines) of the
 * region `origin` of the page: the whole page by default, or just a crop of it. Bands that span the
 * full width of the bitmap are sliced without copying because their rows are contiguous.
 */
function buildFramePayloads(bitmap, pageW, pageH, rect, maxPayload = P.MAX_PAYLOAD, origin = null) {
  if (pageW < 1 || pageH < 1 || pageW > 0xffff || pageH > 0xffff) throw new RangeError('bad page size');
  const o = origin || fullRect(pageW, pageH);
  const stride = bitmap.length / o.h;
  if (!Number.isInteger(stride) || stride < o.w * 4) throw new RangeError('bitmap size ' + bitmap.length + ' does not match ' + o.w + 'x' + o.h);
  const page = clampRect(rect, pageW, pageH);
  const r = page && clampRect({ x: page.x - o.x, y: page.y - o.y, w: page.w, h: page.h }, o.w, o.h);
  if (!r) return [];
  return splitIntoBands(r, maxPayload - P.FRAME_HEADER_BYTES).map(b => {
    const head = P.frameHeader(pageW, pageH, o.x + b.x, o.y + b.y, b.w, b.h);
    if (b.x === 0 && b.w === o.w && stride === o.w * 4) {
      return [head, bitmap.subarray(b.y * stride, (b.y + b.h) * stride)];
    }
    return [head, cropBgra(bitmap, stride, b)];
  });
}

/**
 * Pixels for `rect` of an Electron NativeImage of size pageW x pageH, as FRAME payloads. A small
 * dirty rect is cropped by Chromium (NativeImage.crop) so only its pixels are copied; a large one
 * takes the whole bitmap, which avoids a second copy. The probe measured 2.2-3.2 ms for a full
 * 720p toBitmap() on the Node main thread, which also carries input and navigation.
 */
function imagePayloads(image, pageW, pageH, rect, maxPayload = P.MAX_PAYLOAD) {
  const r = clampRect(rect, pageW, pageH);
  if (!r) return [];
  if (r.w * r.h * 2 >= pageW * pageH) return buildFramePayloads(image.toBitmap(), pageW, pageH, r, maxPayload);
  const sub = image.crop({ x: r.x, y: r.y, width: r.w, height: r.h });
  const size = sub.getSize();
  if (size.width !== r.w || size.height !== r.h) {
    // Not expected with deviceScaleFactor 1; fall back to the exact path rather than send a wrong crop.
    return buildFramePayloads(image.toBitmap(), pageW, pageH, r, maxPayload);
  }
  return buildFramePayloads(sub.toBitmap(), pageW, pageH, r, maxPayload, r);
}

/**
 * Latest-wins frame scheduling. At most one frame per screen waits while the socket is blocked; a
 * newer paint replaces the waiting image and its dirty rect is merged, so the union is cropped from
 * the newest image and no damage is ever lost (the Java canvas only applies what it receives).
 */
class FrameScheduler {
  /**
   * @param {object} io send(screenId, image, rect) -> boolean (true if the link can take more),
   *   blocked() -> boolean
   */
  constructor(io) {
    this.io = io;
    this.screens = new Map();
    this.stats = { offered: 0, sent: 0, merged: 0 };
  }

  _state(screenId) {
    let st = this.screens.get(screenId);
    if (!st) {
      st = { needFull: true, w: 0, h: 0, pending: null };
      this.screens.set(screenId, st);
    }
    return st;
  }

  /** Next frame of this screen is sent whole (new canvas on the Java side, or painting resumed). */
  requestFull(screenId) {
    this._state(screenId).needFull = true;
  }

  offer(screenId, image, dirtyRect) {
    const size = image.getSize();
    if (!size || size.width < 1 || size.height < 1) return;
    const st = this._state(screenId);
    this.stats.offered++;
    if (st.w !== size.width || st.h !== size.height) {
      st.w = size.width;
      st.h = size.height;
      st.needFull = true;
    }
    const rect = st.needFull ? fullRect(st.w, st.h) : clampRect(dirtyRect, st.w, st.h);
    if (!rect) return;
    if (st.pending) {
      this.stats.merged++;
      st.pending = { image, damage: clampRect(unionRect(st.pending.damage, rect), st.w, st.h) };
    } else {
      st.pending = { image, damage: rect };
    }
    st.needFull = false;
    if (!this.io.blocked()) this._sendPending(screenId, st);
  }

  /** Called when the socket drains: sends every waiting frame, round-robin, until it blocks again. */
  flush() {
    const ids = [...this.screens.keys()];
    if (ids.length === 0) return;
    const start = this.lastFlushed != null ? (ids.indexOf(this.lastFlushed) + 1) % ids.length : 0;
    for (let i = 0; i < ids.length; i++) {
      if (this.io.blocked()) return;
      const id = ids[(start + i) % ids.length];
      const st = this.screens.get(id);
      if (st.pending) this._sendPending(id, st);
    }
  }

  remove(screenId) {
    this.screens.delete(screenId);
    if (this.lastFlushed === screenId) this.lastFlushed = null;
  }

  hasPending(screenId) {
    const st = this.screens.get(screenId);
    return !!(st && st.pending);
  }

  _sendPending(screenId, st) {
    const { image, damage } = st.pending;
    st.pending = null;
    this.lastFlushed = screenId;
    this.stats.sent++;
    this.io.send(screenId, image, damage);
  }
}

module.exports = { clampRect, unionRect, fullRect, splitIntoBands, cropBgra, buildFramePayloads, imagePayloads, FrameScheduler };
