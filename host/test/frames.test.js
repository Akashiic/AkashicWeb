'use strict';
const test = require('node:test');
const assert = require('node:assert');
const P = require('../src/protocol');
const F = require('../src/frames');

/** BGRA bitmap where every pixel encodes its own coordinates: B=x, G=y, R=0x7f, A=0xff. */
function bitmap(width, height, stride = width * 4) {
  const b = Buffer.alloc(stride * height, 0xee);
  for (let y = 0; y < height; y++) {
    for (let x = 0; x < width; x++) {
      const o = y * stride + x * 4;
      b[o] = x;
      b[o + 1] = y;
      b[o + 2] = 0x7f;
      b[o + 3] = 0xff;
    }
  }
  return b;
}

function fakeImage(width, height, tag = 0) {
  return { tag, getSize: () => ({ width, height }), toBitmap: () => bitmap(width, height) };
}

function payload(parts) {
  return Buffer.concat(parts);
}

test('clampRect accepts Electron {x,y,width,height}, clips to the page and drops empty rects', () => {
  assert.deepStrictEqual(F.clampRect({ x: 2, y: 3, width: 4, height: 5 }, 100, 100), { x: 2, y: 3, w: 4, h: 5 });
  assert.deepStrictEqual(F.clampRect({ x: -5, y: 90, width: 20, height: 20 }, 100, 100), { x: 0, y: 90, w: 15, h: 10 });
  assert.deepStrictEqual(F.clampRect({ x: 1.5, y: 1.2, w: 2, h: 2 }, 10, 10), { x: 1, y: 1, w: 3, h: 3 });
  assert.strictEqual(F.clampRect({ x: 100, y: 0, width: 5, height: 5 }, 100, 100), null);
  assert.strictEqual(F.clampRect({ x: 0, y: 0, width: 0, height: 5 }, 100, 100), null);
  assert.strictEqual(F.clampRect({ x: NaN, y: 0, width: 1, height: 1 }, 100, 100), null);
  assert.strictEqual(F.clampRect(null, 100, 100), null);
});

test('unionRect covers both rects', () => {
  assert.deepStrictEqual(F.unionRect({ x: 0, y: 0, w: 2, h: 2 }, { x: 5, y: 1, w: 1, h: 4 }), { x: 0, y: 0, w: 6, h: 5 });
  const a = { x: 1, y: 1, w: 1, h: 1 };
  assert.strictEqual(F.unionRect(a, null), a);
  assert.strictEqual(F.unionRect(null, a), a);
});

test('cropBgra copies exactly the rect, row by row', () => {
  const bmp = bitmap(16, 8);
  const out = F.cropBgra(bmp, 16 * 4, { x: 3, y: 2, w: 4, h: 3 });
  assert.strictEqual(out.length, 4 * 3 * 4);
  for (let r = 0; r < 3; r++) {
    for (let c = 0; c < 4; c++) {
      const o = (r * 4 + c) * 4;
      assert.deepStrictEqual([...out.subarray(o, o + 4)], [3 + c, 2 + r, 0x7f, 0xff]);
    }
  }
});

test('cropBgra honours a padded stride', () => {
  const bmp = bitmap(5, 4, 5 * 4 + 12);
  const out = F.cropBgra(bmp, 5 * 4 + 12, { x: 1, y: 1, w: 2, h: 2 });
  assert.deepStrictEqual([...out], [1, 1, 0x7f, 0xff, 2, 1, 0x7f, 0xff, 1, 2, 0x7f, 0xff, 2, 2, 0x7f, 0xff]);
});

test('buildFramePayloads: header plus BGRA rows, matching HostProtocol.FrameHeader.parse', () => {
  const [parts] = F.buildFramePayloads(bitmap(64, 32), 64, 32, { x: 10, y: 4, width: 6, height: 2 });
  const p = payload(parts);
  assert.deepStrictEqual([0, 2, 4, 6, 8, 10].map(o => p.readUInt16BE(o)), [64, 32, 10, 4, 6, 2]);
  assert.strictEqual(p.length, P.FRAME_HEADER_BYTES + 6 * 2 * 4);
  assert.deepStrictEqual([...p.subarray(12, 16)], [10, 4, 0x7f, 0xff]);
  assert.deepStrictEqual([...p.subarray(p.length - 4)], [15, 5, 0x7f, 0xff]);
});

test('buildFramePayloads: a full-width band is a zero-copy slice of the bitmap', () => {
  const bmp = bitmap(8, 8);
  const [parts] = F.buildFramePayloads(bmp, 8, 8, { x: 0, y: 2, width: 8, height: 3 });
  assert.strictEqual(parts[1].buffer, bmp.buffer, 'shares memory with the bitmap');
  assert.strictEqual(parts[1].length, 8 * 3 * 4);
  assert.strictEqual(parts[1][0 + 1], 2, 'starts at row 2');
});

test('buildFramePayloads splits rects bigger than the payload cap into row bands', () => {
  const bmp = bitmap(10, 10);
  // A cap of 12 + 3 rows * 40 bytes forces bands of 3 rows: 3+3+3+1.
  const list = F.buildFramePayloads(bmp, 10, 10, { x: 0, y: 0, width: 10, height: 10 }, P.FRAME_HEADER_BYTES + 3 * 40);
  assert.deepStrictEqual(list.map(parts => payload(parts).readUInt16BE(10)), [3, 3, 3, 1]);
  assert.deepStrictEqual(list.map(parts => payload(parts).readUInt16BE(6)), [0, 3, 6, 9]);
  for (const parts of list) assert.ok(payload(parts).length <= P.FRAME_HEADER_BYTES + 3 * 40);
});

test('a 4096x4096 page (64 MiB) never produces a payload above 16 MiB', () => {
  const bands = F.splitIntoBands({ x: 0, y: 0, w: 4096, h: 4096 }, P.MAX_PAYLOAD - P.FRAME_HEADER_BYTES);
  assert.strictEqual(bands.reduce((a, b) => a + b.h, 0), 4096);
  for (const b of bands) assert.ok(P.FRAME_HEADER_BYTES + b.w * b.h * 4 <= P.MAX_PAYLOAD);
});

test('buildFramePayloads rejects a bitmap that does not match the page size', () => {
  assert.throws(() => F.buildFramePayloads(Buffer.alloc(10), 4, 4, { x: 0, y: 0, w: 4, h: 4 }), RangeError);
  assert.throws(() => F.buildFramePayloads(Buffer.alloc(4 * 4 * 4), 70000, 1, { x: 0, y: 0, w: 1, h: 1 }), RangeError);
  assert.deepStrictEqual(F.buildFramePayloads(bitmap(4, 4), 4, 4, { x: 9, y: 9, w: 1, h: 1 }), []);
});

test('buildFramePayloads with an origin: the bitmap is a crop of the page', () => {
  // Bitmap of the 6x3 region at (10,4) of a 64x32 page; pixels carry their local coordinates.
  const crop = bitmap(6, 3);
  const [parts] = F.buildFramePayloads(crop, 64, 32, { x: 10, y: 4, w: 6, h: 3 }, P.MAX_PAYLOAD, { x: 10, y: 4, w: 6, h: 3 });
  const p = payload(parts);
  assert.deepStrictEqual([0, 2, 4, 6, 8, 10].map(o => p.readUInt16BE(o)), [64, 32, 10, 4, 6, 3]);
  assert.strictEqual(parts[1].buffer, crop.buffer, 'whole crop sent without copying');
  // A rect partly outside the crop is clipped to it.
  const [p2] = F.buildFramePayloads(crop, 64, 32, { x: 12, y: 5, w: 100, h: 100 }, P.MAX_PAYLOAD, { x: 10, y: 4, w: 6, h: 3 });
  assert.deepStrictEqual([4, 6, 8, 10].map(o => payload(p2).readUInt16BE(o)), [12, 5, 4, 2]);
  assert.deepStrictEqual([...payload(p2).subarray(12, 16)], [2, 1, 0x7f, 0xff]);
});

function croppableImage(width, height) {
  const calls = [];
  const full = bitmap(width, height);
  const img = {
    calls,
    getSize: () => ({ width, height }),
    toBitmap: () => { calls.push('toBitmap'); return full; },
    crop: r => {
      calls.push('crop');
      const out = Buffer.alloc(r.width * r.height * 4);
      for (let y = 0; y < r.height; y++) full.copy(out, y * r.width * 4, ((r.y + y) * width + r.x) * 4, ((r.y + y) * width + r.x + r.width) * 4);
      return { getSize: () => ({ width: r.width, height: r.height }), toBitmap: () => out };
    },
  };
  return img;
}

test('imagePayloads: small rects are cropped natively, large ones use the full bitmap; same bytes either way', () => {
  const small = croppableImage(64, 32);
  const viaCrop = payload(F.imagePayloads(small, 64, 32, { x: 5, y: 6, width: 7, height: 8 })[0]);
  assert.deepStrictEqual(small.calls, ['crop']);
  const direct = payload(F.buildFramePayloads(bitmap(64, 32), 64, 32, { x: 5, y: 6, w: 7, h: 8 })[0]);
  assert.deepStrictEqual(viaCrop, direct);
  const big = croppableImage(64, 32);
  F.imagePayloads(big, 64, 32, { x: 0, y: 0, width: 64, height: 20 });
  assert.deepStrictEqual(big.calls, ['toBitmap']);
  const odd = croppableImage(64, 32);
  odd.crop = () => ({ getSize: () => ({ width: 1, height: 1 }), toBitmap: () => Buffer.alloc(4) });
  const safe = payload(F.imagePayloads(odd, 64, 32, { x: 5, y: 6, width: 7, height: 8 })[0]);
  assert.deepStrictEqual(safe, direct, 'falls back to the exact path when a crop has the wrong size');
  assert.deepStrictEqual(F.imagePayloads(small, 64, 32, { x: 99, y: 0, width: 1, height: 1 }), []);
});

function scheduler(blocked = false) {
  const sent = [];
  const io = { blocked: () => io.isBlocked, isBlocked: blocked, send: (id, image, rect) => { sent.push({ id, tag: image.tag, rect }); return !io.isBlocked; } };
  return { s: new F.FrameScheduler(io), io, sent };
}

test('scheduler: first frame of a screen is always full, later ones are dirty rects', () => {
  const { s, sent } = scheduler();
  s.offer(1, fakeImage(100, 50, 'a'), { x: 10, y: 10, width: 5, height: 5 });
  s.offer(1, fakeImage(100, 50, 'b'), { x: 10, y: 10, width: 5, height: 5 });
  assert.deepStrictEqual(sent.map(x => x.rect), [{ x: 0, y: 0, w: 100, h: 50 }, { x: 10, y: 10, w: 5, h: 5 }]);
});

test('scheduler: while blocked, a newer paint replaces the pending one and damage is merged', () => {
  const { s, io, sent } = scheduler();
  s.offer(1, fakeImage(100, 50, 'first'), { x: 0, y: 0, width: 100, height: 50 });
  io.isBlocked = true;
  s.offer(1, fakeImage(100, 50, 'a'), { x: 0, y: 0, width: 10, height: 10 });
  s.offer(1, fakeImage(100, 50, 'b'), { x: 50, y: 40, width: 10, height: 10 });
  s.offer(1, fakeImage(100, 50, 'c'), { x: 20, y: 20, width: 1, height: 1 });
  assert.strictEqual(sent.length, 1, 'nothing sent while blocked');
  assert.ok(s.hasPending(1));
  io.isBlocked = false;
  s.flush();
  assert.strictEqual(sent.length, 2);
  assert.strictEqual(sent[1].tag, 'c', 'latest image wins');
  assert.deepStrictEqual(sent[1].rect, { x: 0, y: 0, w: 60, h: 50 }, 'union of all skipped damage');
  assert.strictEqual(s.stats.merged, 2);
  assert.ok(!s.hasPending(1));
});

test('scheduler: at most one pending frame per screen, round-robin across screens on drain', () => {
  const { s, io, sent } = scheduler(true);
  for (let i = 0; i < 5; i++) {
    s.offer(1, fakeImage(10, 10, 's1-' + i), { x: 0, y: 0, width: 1, height: 1 });
    s.offer(2, fakeImage(10, 10, 's2-' + i), { x: 0, y: 0, width: 1, height: 1 });
  }
  io.isBlocked = false;
  s.flush();
  assert.deepStrictEqual(sent.map(x => x.tag), ['s1-4', 's2-4']);
});

test('scheduler: flush stops as soon as the link blocks again and resumes fairly', () => {
  const sent = [];
  const io = { isBlocked: true, blocked: () => io.isBlocked, send: (id, image) => { sent.push(id); io.isBlocked = true; return false; } };
  const s = new F.FrameScheduler(io);
  s.offer(1, fakeImage(10, 10), null);
  s.offer(2, fakeImage(10, 10), null);
  s.offer(3, fakeImage(10, 10), null);
  io.isBlocked = false;
  s.flush();
  io.isBlocked = false;
  s.flush();
  io.isBlocked = false;
  s.flush();
  assert.deepStrictEqual(sent, [1, 2, 3]);
});

test('scheduler: a size change or requestFull forces a full frame', () => {
  const { s, sent } = scheduler();
  s.offer(1, fakeImage(100, 50), { x: 0, y: 0, width: 100, height: 50 });
  s.offer(1, fakeImage(200, 80), { x: 1, y: 1, width: 1, height: 1 });
  s.requestFull(1);
  s.offer(1, fakeImage(200, 80), { x: 1, y: 1, width: 1, height: 1 });
  s.offer(1, fakeImage(200, 80), { x: 1, y: 1, width: 1, height: 1 });
  assert.deepStrictEqual(sent.map(x => x.rect), [
    { x: 0, y: 0, w: 100, h: 50 }, { x: 0, y: 0, w: 200, h: 80 }, { x: 0, y: 0, w: 200, h: 80 }, { x: 1, y: 1, w: 1, h: 1 },
  ]);
});

test('scheduler: remove() forgets the screen; a recreated screen starts with a full frame', () => {
  const { s, io, sent } = scheduler();
  s.offer(7, fakeImage(10, 10), null);
  io.isBlocked = true;
  s.offer(7, fakeImage(10, 10), { x: 1, y: 1, width: 1, height: 1 });
  s.remove(7);
  io.isBlocked = false;
  s.flush();
  assert.strictEqual(sent.length, 1);
  s.offer(7, fakeImage(10, 10), { x: 1, y: 1, width: 1, height: 1 });
  assert.deepStrictEqual(sent[1].rect, { x: 0, y: 0, w: 10, h: 10 });
});

test('scheduler ignores empty images and out-of-page damage', () => {
  const { s, sent } = scheduler();
  s.offer(1, fakeImage(0, 0), { x: 0, y: 0, width: 1, height: 1 });
  s.offer(1, fakeImage(10, 10), null);
  s.offer(1, fakeImage(10, 10), { x: 50, y: 50, width: 1, height: 1 });
  assert.strictEqual(sent.length, 1);
});
