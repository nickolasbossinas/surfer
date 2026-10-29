import type { CDPSession, Protocol } from 'puppeteer-core';
import sharp from 'sharp';
import { config } from './config.js';
import { logger } from './log.js';
import {
  encodeFrame,
  FRAME_FLAG_KEYFRAME,
  FRAME_FLAG_REFINE,
  ImageFormat,
  type FrameRect,
  type ScrollTarget,
} from './protocol.js';
import { diffTiles, gridFor, mergeTiles, toPixels, type Grid, type PixelRect } from './tiles.js';

const log = logger('screencast');

/** Updates sent to the client but not yet acknowledged by it. */
const MAX_IN_FLIGHT = 2;
/** A tile sent lossy is resent lossless once unchanged for this long. */
const REFINE_AFTER_MS = 300;
/** Above this share of changed tiles (e.g. scrolling), the whole picture goes as one JPEG. */
const FULL_FRAME_SHARE = 0.35;
/** A single changed area above this share of the screen is sent as JPEG (refined later). */
const LOSSY_RECT_SHARE = 0.15;
const JPEG_QUALITY = 80;
const STILL_CAPTURE_TIMEOUT_MS = 1500;
/** An update the client has not acknowledged after this long is taken as lost. */
const ACK_TIMEOUT_MS = 4000;
const CHANNELS = 3;

export interface FrameSink {
  sendFrame(frame: Buffer): void;
}

export interface Viewport {
  w: number;
  h: number;
  dpr: number;
}

interface FrameMeta {
  pageScale: number;
  scrollX: number;
  scrollY: number;
  offsetTop: number;
}

interface RawFrame {
  data: Buffer;
  width: number;
  height: number;
}

/**
 * Streams one tab to one client as incremental updates.
 *
 * Chromium sends lossless PNG frames. Each frame is compared with the previous
 * one in tiles; only changed tiles are sent: small areas losslessly, large ones
 * (scrolling) as JPEG. Tiles sent as JPEG are resent losslessly once they have
 * been still for REFINE_AFTER_MS, so the picture is pixel-exact whenever it is
 * not moving, even while an animation runs elsewhere on the page.
 *
 * Chromium's frame ack is held while the client has MAX_IN_FLIGHT updates
 * unacknowledged, so a slow link or device slows the screencast down instead
 * of queueing stale frames.
 */
export class Screencast {
  private nextFrameId = 1;
  /** Update id → when it was sent. */
  private readonly inFlight = new Map<number, number>();
  private ackWatchdog: NodeJS.Timeout | undefined;
  private readonly heldAcks: number[] = [];
  private pending: Protocol.Page.ScreencastFrameEvent | undefined;
  private processing = false;
  private stopped = false;
  private meta: FrameMeta = { pageScale: 1, scrollX: 0, scrollY: 0, offsetTop: 0 };
  private scrollTarget: ScrollTarget | undefined;

  /** The latest frame from Chromium: what the page looks like now. */
  private current: RawFrame | undefined;
  private grid: Grid = { cols: 0, rows: 0 };
  /** Per tile: 1 if the client holds a lossy version. */
  private lossy = new Uint8Array(0);
  /** Per tile: when it last changed (ms). */
  private changedAt = new Float64Array(0);
  private refineTimer: NodeJS.Timeout | undefined;
  private lastShape = '';
  private readonly onFrame = (e: Protocol.Page.ScreencastFrameEvent) => this.handleFrame(e);

  constructor(
    private readonly cdp: CDPSession,
    private readonly tabId: number,
    private readonly sink: FrameSink,
    private readonly viewport: Viewport,
  ) {}

  async start(): Promise<void> {
    this.cdp.on('Page.screencastFrame', this.onFrame);
    await this.cdp.send('Page.startScreencast', {
      format: 'png',
      maxWidth: Math.round(this.viewport.w * this.viewport.dpr),
      maxHeight: Math.round(this.viewport.h * this.viewport.dpr),
      everyNthFrame: 1,
    });
    // A lost acknowledgement must not stop the picture for good.
    this.ackWatchdog = setInterval(() => this.checkAcks(), 1000);
    // Chromium sends frames only when something changes; ask for the current one.
    // Not awaited: on a page that is still loading the capture can take long, and
    // tab switches and viewport changes must not wait behind it.
    void this.sendStillPicture();
  }

  async stop(): Promise<void> {
    if (this.stopped) return;
    this.stopped = true;
    clearTimeout(this.refineTimer);
    clearInterval(this.ackWatchdog);
    this.cdp.off('Page.screencastFrame', this.onFrame);
    this.heldAcks.length = 0;
    await this.cdp.send('Page.stopScreencast').catch(() => {});
  }

  /** Page scale of the latest picture (below 1 when a desktop page is zoomed out to fit). */
  get pageScale(): number {
    return this.meta.pageScale;
  }

  setScrollTarget(target: ScrollTarget | undefined): void {
    this.scrollTarget = target;
  }

  /** The client acknowledged an update: release one held Chromium ack, if any. */
  clientAck(frameId: number): void {
    if (!this.inFlight.delete(frameId)) return;
    const held = this.heldAcks.shift();
    if (held !== undefined) this.ackChromium(held);
  }

  private checkAcks(): void {
    const now = Date.now();
    for (const [frameId, sentAt] of this.inFlight) {
      if (now - sentAt < ACK_TIMEOUT_MS) continue;
      log.warn(`tab ${this.tabId}: update ${frameId} not acknowledged after ${now - sentAt} ms; continuing`);
      this.clientAck(frameId);
    }
  }

  // ---------------------------------------------------------------------------

  private handleFrame(e: Protocol.Page.ScreencastFrameEvent): void {
    if (this.stopped) return;
    // Only the newest frame matters; an older unprocessed one is skipped.
    if (this.pending) this.ackChromium(this.pending.sessionId);
    this.pending = e;
    void this.pump();
  }

  private async pump(): Promise<void> {
    if (this.processing) return;
    this.processing = true;
    try {
      while (this.pending && !this.stopped) {
        const e = this.pending;
        this.pending = undefined;
        try {
          await this.process(e);
        } catch (err) {
          log.warn('frame processing failed', err);
        }
        if (this.inFlight.size < MAX_IN_FLIGHT) this.ackChromium(e.sessionId);
        else this.heldAcks.push(e.sessionId);
      }
    } finally {
      this.processing = false;
    }
  }

  private async process(e: Protocol.Page.ScreencastFrameEvent): Promise<void> {
    const m = e.metadata;
    this.meta = { pageScale: m.pageScaleFactor, scrollX: m.scrollOffsetX, scrollY: m.scrollOffsetY, offsetTop: m.offsetTop };
    await this.ingest(Buffer.from(e.data, 'base64'));
  }

  /** Decodes a PNG of the page and sends what changed. */
  private async ingest(png: Buffer): Promise<void> {
    const { data, info } = await sharp(png).removeAlpha().raw().toBuffer({ resolveWithObject: true });
    if (this.stopped) return;
    const next: RawFrame = { data, width: info.width, height: info.height };
    const prev = this.current;
    this.current = next;
    this.logShape(next);
    const now = Date.now();

    if (!prev || prev.width !== next.width || prev.height !== next.height) {
      this.grid = gridFor(next.width, next.height);
      this.lossy = new Uint8Array(this.grid.cols * this.grid.rows).fill(1);
      this.changedAt = new Float64Array(this.lossy.length).fill(now);
      await this.send(FRAME_FLAG_KEYFRAME, [await this.encode(next, { x: 0, y: 0, w: next.width, h: next.height }, ImageFormat.Jpeg)]);
      this.scheduleRefine();
      return;
    }

    const dirty = diffTiles(prev.data, next.data, next.width, next.height, CHANNELS);
    let count = 0;
    for (let i = 0; i < dirty.length; i++) {
      if (dirty[i]) {
        count++;
        this.changedAt[i] = now;
      }
    }
    if (count === 0) return;

    const total = dirty.length;
    if (count / total > FULL_FRAME_SHARE) {
      this.lossy.fill(1);
      await this.send(0, [await this.encode(next, { x: 0, y: 0, w: next.width, h: next.height }, ImageFormat.Jpeg)]);
    } else {
      const rects: FrameRect[] = [];
      for (const tr of mergeTiles(dirty, this.grid)) {
        const lossyRect = (tr.tw * tr.th) / total > LOSSY_RECT_SHARE;
        this.markTiles(tr, lossyRect ? 1 : 0);
        rects.push(await this.encode(next, toPixels(tr, next.width, next.height), lossyRect ? ImageFormat.Jpeg : this.losslessFormat()));
      }
      await this.send(0, rects);
    }
    this.scheduleRefine();
  }

  /** Resends tiles that were sent lossy and have been still since. */
  private async refine(): Promise<void> {
    this.refineTimer = undefined;
    const frame = this.current;
    if (this.stopped || !frame) return;
    if (this.inFlight.size >= MAX_IN_FLIGHT || this.processing) {
      this.scheduleRefine(50);
      return;
    }
    const now = Date.now();
    const ready = new Uint8Array(this.lossy.length);
    let waiting = false;
    let count = 0;
    for (let i = 0; i < ready.length; i++) {
      if (!this.lossy[i]) continue;
      if (now - this.changedAt[i]! >= REFINE_AFTER_MS) {
        ready[i] = 1;
        count++;
      } else {
        waiting = true;
      }
    }
    if (count > 0) {
      const tileRects = mergeTiles(ready, this.grid);
      const rects: FrameRect[] = [];
      for (const tr of tileRects) {
        rects.push(await this.encode(frame, toPixels(tr, frame.width, frame.height), this.losslessFormat()));
      }
      if (this.stopped || frame !== this.current) {
        // The page changed while encoding; the next refine pass picks it up.
        this.scheduleRefine();
        return;
      }
      for (const tr of tileRects) this.markTiles(tr, 0);
      await this.send(FRAME_FLAG_REFINE, rects);
    }
    if (waiting) this.scheduleRefine();
  }

  private scheduleRefine(delay = REFINE_AFTER_MS): void {
    if (this.refineTimer || this.stopped) return;
    this.refineTimer = setTimeout(() => void this.refine().catch((err) => log.warn('refine failed', err)), delay);
  }

  /** A full, lossless picture of the page as it is now (start, tab switch). */
  private async sendStillPicture(): Promise<void> {
    let timer: NodeJS.Timeout | undefined;
    try {
      // Chromium sometimes never answers this while the page has not painted yet;
      // the screencast delivers the picture as soon as it does.
      const timeout = new Promise<null>((resolve) => (timer = setTimeout(() => resolve(null), STILL_CAPTURE_TIMEOUT_MS)));
      const shot = await Promise.race([this.cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false }), timeout]);
      if (shot && !this.stopped && !this.current) await this.ingest(Buffer.from(shot.data, 'base64'));
    } catch (err) {
      if (!this.stopped) log.debug('initial capture failed', err);
    } finally {
      clearTimeout(timer);
    }
  }

  private markTiles(r: { tx: number; ty: number; tw: number; th: number }, value: number): void {
    for (let y = r.ty; y < r.ty + r.th; y++) this.lossy.fill(value, y * this.grid.cols + r.tx, y * this.grid.cols + r.tx + r.tw);
  }

  private losslessFormat(): ImageFormat {
    return config.idleFormat === 'webp' ? ImageFormat.Webp : ImageFormat.Png;
  }

  private async encode(frame: RawFrame, r: PixelRect, format: ImageFormat): Promise<FrameRect> {
    let img = sharp(frame.data, { raw: { width: frame.width, height: frame.height, channels: CHANNELS } });
    if (r.w !== frame.width || r.h !== frame.height) img = img.extract({ left: r.x, top: r.y, width: r.w, height: r.h });
    const data =
      format === ImageFormat.Jpeg
        ? await img.jpeg({ quality: JPEG_QUALITY, chromaSubsampling: '4:2:0' }).toBuffer()
        : format === ImageFormat.Webp
          ? await img.webp({ lossless: true, effort: 1 }).toBuffer()
          : await img.png({ compressionLevel: 3, adaptiveFiltering: false }).toBuffer();
    return { ...r, format, data };
  }

  private async send(flags: number, rects: FrameRect[]): Promise<void> {
    const frame = this.current;
    if (this.stopped || !frame || rects.length === 0) return;
    const frameId = this.nextFrameId++;
    this.inFlight.set(frameId, Date.now());
    this.sink.sendFrame(
      encodeFrame(
        { flags, frameId, tabId: this.tabId, width: frame.width, height: frame.height, ...this.meta, scrollTarget: this.scrollTarget },
        rects,
      ),
    );
  }

  private ackChromium(sessionId: number): void {
    this.cdp.send('Page.screencastFrameAck', { sessionId }).catch(() => {});
  }

  private logShape(frame: RawFrame): void {
    const shape = `${frame.width}x${frame.height} pageScale=${this.meta.pageScale.toFixed(3)}`;
    if (shape !== this.lastShape) {
      log.info(`tab ${this.tabId}: picture ${shape} (viewport ${this.viewport.w}x${this.viewport.h}@${this.viewport.dpr})`);
      this.lastShape = shape;
    }
  }
}
