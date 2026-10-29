import type { CDPSession, Protocol } from 'puppeteer-core';
import { logger } from './log.js';
import type { ClientMessage } from './protocol.js';

const log = logger('input');

type TouchMsg = Extract<ClientMessage, { t: 'touch' }>;
type KeyMsg = Extract<ClientMessage, { t: 'key' }>;
type FlingMsg = Extract<ClientMessage, { t: 'fling' }>;
type WheelMsg = Extract<ClientMessage, { t: 'wheel' }>;

const TOUCH_TYPES = {
  start: 'touchStart',
  move: 'touchMove',
  end: 'touchEnd',
  cancel: 'touchCancel',
} as const;

/** Event times further than this from the server clock are replaced by "now". */
const MAX_CLOCK_SKEW_MS = 5000;
/** A touch Chromium has not answered after this long is stuck. */
const TOUCH_HANG_MS = 3000;

function fail(what: string) {
  return (err: unknown) => log.debug(`${what} failed`, err);
}

/** Converts a client event time (server-clock ms) to a CDP timestamp (s since epoch). */
function timestamp(timeMs: number): number {
  const now = Date.now();
  return (Math.abs(timeMs - now) > MAX_CLOCK_SKEW_MS ? now : timeMs) / 1000;
}

/**
 * Input for one tab. CDP commands on a session are processed in send order, so
 * events are fired without awaiting each one.
 *
 * Device coordinates are CSS pixels of the screen. CDP's input coordinates are
 * CSS pixels of the page, which Chromium multiplies by the page scale (a page
 * without a mobile viewport is shown zoomed out, e.g. 0.6). So device
 * coordinates are divided by the page scale of the picture the user touched.
 */
export class InputController {
  private flingTimer: NodeJS.Timeout | undefined;

  constructor(private readonly cdp: CDPSession) {}

  touch(msg: TouchMsg, pageScale = 1): void {
    const k = 1 / (pageScale > 0 ? pageScale : 1);
    if (msg.phase === 'start') this.stopFling();
    const type = TOUCH_TYPES[msg.phase];
    // CDP: touchEnd/touchCancel carry no points; start/move carry all active points.
    const touchPoints: Protocol.Input.TouchPoint[] =
      msg.phase === 'end' || msg.phase === 'cancel'
        ? []
        : msg.points.map((p) => ({
            x: p.x * k,
            y: p.y * k,
            id: p.id,
            radiusX: (p.rx ?? 1) * k,
            radiusY: (p.ry ?? 1) * k,
            force: p.force ?? 1,
          }));
    if ((type === 'touchStart' || type === 'touchMove') && touchPoints.length === 0) return;
    let answered = false;
    this.cdp
      .send('Input.dispatchTouchEvent', { type, touchPoints, timestamp: timestamp(msg.time) })
      .catch(fail('touch'))
      .finally(() => (answered = true));
    // Chromium answers once the page has handled the touch. A touch that is never
    // answered blocks every later one (seen with Google Maps' Android version).
    setTimeout(() => {
      if (answered || this.reportedHang) return;
      this.reportedHang = true;
      void this.cdp
        .send('Runtime.evaluate', { expression: 'location.origin', returnByValue: true })
        .then((r) => log.warn(`touches are not being handled on ${String(r.result.value)}; input is stuck on this page`))
        .catch(() => {});
    }, TOUCH_HANG_MS);
  }

  private reportedHang = false;

  wheel(msg: WheelMsg, pageScale = 1): void {
    const k = 1 / (pageScale > 0 ? pageScale : 1);
    this.cdp
      .send('Input.dispatchMouseEvent', { type: 'mouseWheel', x: msg.x * k, y: msg.y * k, deltaX: msg.dx, deltaY: msg.dy })
      .catch(fail('wheel'));
  }

  /**
   * Fallback momentum scroll: wheel events at 60 Hz with exponential decay.
   * Only used if Chromium does not fling on its own after a touch swipe.
   */
  fling(msg: FlingMsg): void {
    this.stopFling();
    let vx = msg.vx;
    let vy = msg.vy;
    const frameMs = 16;
    const decay = 0.95;
    this.flingTimer = setInterval(() => {
      vx *= decay;
      vy *= decay;
      if (Math.hypot(vx, vy) < 20) {
        this.stopFling();
        return;
      }
      // Finger moving down (positive velocity) scrolls content up: negative wheel delta.
      this.wheel({ t: 'wheel', x: msg.x, y: msg.y, dx: (-vx * frameMs) / 1000, dy: (-vy * frameMs) / 1000 });
    }, frameMs);
  }

  stopFling(): void {
    clearInterval(this.flingTimer);
    this.flingTimer = undefined;
  }

  key(msg: KeyMsg): void {
    const base = {
      key: msg.key,
      code: msg.code,
      windowsVirtualKeyCode: msg.keyCode,
      nativeVirtualKeyCode: msg.keyCode,
      modifiers: msg.mods,
    };
    if (msg.phase === 'up') {
      this.cdp.send('Input.dispatchKeyEvent', { type: 'keyUp', ...base }).catch(fail('key'));
      return;
    }
    this.cdp.send('Input.dispatchKeyEvent', { type: 'rawKeyDown', ...base }).catch(fail('key'));
    // Ctrl (2) or Meta (4) chords are commands, not text.
    const text = msg.key === 'Enter' ? '\r' : msg.key.length === 1 && (msg.mods & 6) === 0 ? msg.key : undefined;
    if (text !== undefined) {
      this.cdp
        .send('Input.dispatchKeyEvent', { type: 'char', ...base, text, unmodifiedText: text })
        .catch(fail('key'));
    }
  }

  dispose(): void {
    this.stopFling();
  }
}
