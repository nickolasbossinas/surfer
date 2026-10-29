import { describe, expect, it } from 'vitest';
import { diffTiles, gridFor, mergeTiles, TILE, toPixels } from '../src/tiles.js';

function frame(width: number, height: number, fill = 0): Buffer {
  return Buffer.alloc(width * height * 3, fill);
}

function setPixel(buf: Buffer, width: number, x: number, y: number, value: number): void {
  buf[(y * width + x) * 3] = value;
}

describe('diffTiles', () => {
  it('finds nothing in identical frames', () => {
    const a = frame(100, 70);
    expect([...diffTiles(a, Buffer.from(a), 100, 70, 3)].every((v) => v === 0)).toBe(true);
  });

  it('marks exactly the tiles containing changed pixels, including partial edge tiles', () => {
    const w = 100, h = 70; // 4 x 3 tiles, last column and row partial
    const a = frame(w, h);
    const b = Buffer.from(a);
    setPixel(b, w, 5, 5, 255); // tile (0,0)
    setPixel(b, w, 99, 69, 255); // tile (3,2), the partial corner
    setPixel(b, w, TILE, TILE, 255); // tile (1,1)
    const dirty = diffTiles(a, b, w, h, 3);
    const { cols } = gridFor(w, h);
    const marked = [...dirty].flatMap((v, i) => (v ? [`${i % cols},${Math.floor(i / cols)}`] : []));
    expect(marked).toEqual(['0,0', '1,1', '3,2']);
  });
});

describe('mergeTiles', () => {
  it('merges runs and stacks equal runs vertically', () => {
    const grid = { cols: 4, rows: 3 };
    // row 0: tiles 1-2, row 1: tiles 1-2, row 2: tile 0 and tile 3
    const mask = Uint8Array.from([0, 1, 1, 0, 0, 1, 1, 0, 1, 0, 0, 1]);
    const rects = mergeTiles(mask, grid).sort((a, b) => a.ty - b.ty || a.tx - b.tx);
    expect(rects).toEqual([
      { tx: 1, ty: 0, tw: 2, th: 2 },
      { tx: 0, ty: 2, tw: 1, th: 1 },
      { tx: 3, ty: 2, tw: 1, th: 1 },
    ]);
  });

  it('covers every marked tile exactly once', () => {
    const grid = { cols: 7, rows: 5 };
    const mask = Uint8Array.from({ length: 35 }, (_, i) => (i * 7919) % 3 === 0 ? 1 : 0);
    const covered = new Uint8Array(35);
    for (const r of mergeTiles(mask, grid)) {
      for (let y = r.ty; y < r.ty + r.th; y++) for (let x = r.tx; x < r.tx + r.tw; x++) covered[y * 7 + x]++;
    }
    expect([...covered]).toEqual([...mask]);
  });
});

describe('toPixels', () => {
  it('clips edge rectangles to the frame', () => {
    expect(toPixels({ tx: 3, ty: 2, tw: 1, th: 1 }, 100, 70)).toEqual({ x: 96, y: 64, w: 4, h: 6 });
  });
});
