// Change detection between two raw frames, in fixed-size tiles, and grouping
// of changed tiles into rectangles. See DESIGN.md §3.3.

export const TILE = 32;

export interface Grid {
  cols: number;
  rows: number;
}

/** A rectangle in tile units. */
export interface TileRect {
  tx: number;
  ty: number;
  tw: number;
  th: number;
}

/** A rectangle in pixels. */
export interface PixelRect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export function gridFor(width: number, height: number): Grid {
  return { cols: Math.ceil(width / TILE), rows: Math.ceil(height / TILE) };
}

/**
 * Marks every tile whose pixels differ between `prev` and `next` (raw, same
 * size, `channels` bytes per pixel). Returns one byte per tile, row-major.
 */
export function diffTiles(prev: Buffer, next: Buffer, width: number, height: number, channels: number): Uint8Array {
  const { cols, rows } = gridFor(width, height);
  const dirty = new Uint8Array(cols * rows);
  const stride = width * channels;
  const tileBytes = TILE * channels;
  for (let y = 0; y < height; y++) {
    const rowStart = y * stride;
    // Fast path: most rows are unchanged.
    if (prev.compare(next, rowStart, rowStart + stride, rowStart, rowStart + stride) === 0) continue;
    const ty = Math.floor(y / TILE);
    for (let tx = 0; tx < cols; tx++) {
      const i = ty * cols + tx;
      if (dirty[i]) continue;
      const a = rowStart + tx * tileBytes;
      const b = Math.min(a + tileBytes, rowStart + stride);
      if (prev.compare(next, a, b, a, b) !== 0) dirty[i] = 1;
    }
  }
  return dirty;
}

/**
 * Groups marked tiles into rectangles: horizontal runs per tile row, extended
 * downwards while the next row has the same run.
 */
export function mergeTiles(mask: Uint8Array, grid: Grid): TileRect[] {
  const done: TileRect[] = [];
  let open = new Map<string, TileRect>();
  for (let ty = 0; ty < grid.rows; ty++) {
    const next = new Map<string, TileRect>();
    let tx = 0;
    while (tx < grid.cols) {
      if (!mask[ty * grid.cols + tx]) {
        tx++;
        continue;
      }
      const start = tx;
      while (tx < grid.cols && mask[ty * grid.cols + tx]) tx++;
      const key = `${start}:${tx}`;
      const rect = open.get(key);
      if (rect) {
        rect.th++;
        open.delete(key);
        next.set(key, rect);
      } else {
        next.set(key, { tx: start, ty, tw: tx - start, th: 1 });
      }
    }
    for (const rect of open.values()) done.push(rect);
    open = next;
  }
  for (const rect of open.values()) done.push(rect);
  return done;
}

/** Converts a tile rectangle to pixels, clipped to the frame. */
export function toPixels(r: TileRect, width: number, height: number): PixelRect {
  const x = r.tx * TILE;
  const y = r.ty * TILE;
  return { x, y, w: Math.min(r.tw * TILE, width - x), h: Math.min(r.th * TILE, height - y) };
}
