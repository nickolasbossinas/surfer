// Surfer wire protocol, version 1. See DESIGN.md §5.

export const PROTOCOL_VERSION = 1;

// ---------------------------------------------------------------------------
// Shared shapes

export interface TabInfo {
  id: number;
  title: string;
  url: string;
  loading: boolean;
  progress: number;
  canBack: boolean;
  canForward: boolean;
}

export interface TouchPoint {
  id: number;
  x: number;
  y: number;
  rx?: number;
  ry?: number;
  force?: number;
}

export interface Rect {
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface UserSettings {
  layout: 'tablet' | 'phone' | 'desktop';
  searchEngine: string;
}

// ---------------------------------------------------------------------------
// Client → server

export type ClientMessage =
  /** `deviceId`: random secret created when the app was installed; it identifies the session. */
  | { t: 'hello'; proto: number; app: string; appVersion: string; deviceId: string; device?: { model?: string; sdk?: number }; viewport?: { w: number; h: number; dpr: number }; caps?: { maxTouchPoints?: number; formats?: string[] } }
  /** Restoring a session from the device's saved state: begin, one restoreLocal per origin, end. */
  | { t: 'restoreBegin'; cookies: unknown[]; tabs: unknown[]; active: number; settings: Record<string, unknown> }
  | { t: 'restoreLocal'; key: string; items: Record<string, unknown> }
  | { t: 'restoreEnd' }
  /** Ends the session and wipes it on the server; the device clears its copy. */
  | { t: 'forget' }
  | { t: 'viewport'; w: number; h: number; dpr: number }
  | { t: 'navigate'; tabId: number; input: string }
  /** URL bar text changed: answered by `suggestions` with the same id. */
  | { t: 'suggest'; id: number; q: string }
  | { t: 'back' | 'forward' | 'reload' | 'stop'; tabId: number }
  | { t: 'tabNew'; url?: string }
  | { t: 'tabClose' | 'tabActivate'; tabId: number }
  /** `t` is the event time in server-clock milliseconds (client applies the ping offset). */
  | { t: 'touch'; phase: 'start' | 'move' | 'end' | 'cancel'; time: number; points: TouchPoint[] }
  | { t: 'fling'; x: number; y: number; vx: number; vy: number }
  | { t: 'wheel'; x: number; y: number; dx: number; dy: number }
  /** Edit operations carry `seq`, echoed in editState once applied (DESIGN.md §3.5). */
  | { t: 'key'; phase: 'down' | 'up'; key: string; code: string; keyCode: number; mods: number; seq?: number }
  | { t: 'commit'; text: string; cursor: number; seq?: number }
  | { t: 'compose'; text: string; selStart: number; selEnd: number; seq?: number }
  | { t: 'finishCompose'; seq?: number }
  | { t: 'deleteSurrounding'; before: number; after: number; seq?: number }
  | { t: 'setSelection'; start: number; end: number; seq?: number }
  | { t: 'setComposingRegion'; start: number; end: number; seq?: number }
  | { t: 'paste'; text: string; seq?: number }
  | { t: 'pickerReply'; pickerId: number; cancel?: boolean; value?: string; selected?: number[] }
  | { t: 'contextAction'; menuId: number; action: string }
  | { t: 'selectionDrag'; handle: 'start' | 'end'; x: number; y: number }
  | { t: 'selectionAction'; action: 'copy' | 'selectAll' | 'clear' }
  | { t: 'dialogReply'; dialogId: number; accept: boolean; text?: string }
  | { t: 'authReply'; authId: number; cancel?: boolean; user?: string; password?: string }
  | { t: 'frameAck'; frameId: number }
  | { t: 'setting'; key: string; value: string }
  | { t: 'log'; level: string; msg: string }
  | { t: 'ping'; t0: number };

export type ClientMessageType = ClientMessage['t'];

// ---------------------------------------------------------------------------
// Server → client

export type ServerMessage =
  /** No session is running for this device: send the saved state (restoreBegin ...). */
  | { t: 'needRestore' }
  | { t: 'ready'; resumed: boolean; settings: UserSettings }
  | { t: 'cookies'; set: unknown[]; removed: unknown[] }
  | { t: 'storage'; area: 'local' | 'session'; key: string; tabId?: number; op: 'set' | 'remove' | 'clear'; name?: string; value?: string }
  | { t: 'forgotten' }
  | { t: 'tabs'; tabs: TabInfo[]; activeId: number }
  | ({ t: 'tabUpdate' } & TabInfo)
  | { t: 'focus'; fieldId: number; inputType: string; inputMode: string; multiline: boolean; enterKeyHint: string; autocapitalize: string; autocorrect: boolean; rect: Rect; text: string; selStart: number; selEnd: number; compStart: number; compEnd: number; seq: number }
  | { t: 'editState'; fieldId: number; text: string; selStart: number; selEnd: number; compStart: number; compEnd: number; seq: number }
  | { t: 'blur' }
  | { t: 'suggestions'; id: number; q: string; items: { text: string; kind: 'search' | 'url' }[] }
  | { t: 'picker'; pickerId: number; kind: string; multiple?: boolean; options?: { label: string; value: string; selected: boolean; disabled: boolean; group?: string }[]; value?: string; min?: string; max?: string; step?: string }
  | { t: 'contextMenu'; menuId: number; x: number; y: number; link?: { href: string; text: string }; image?: { src: string; alt: string }; selectionText?: string; editable: boolean }
  | { t: 'selection'; active: boolean; text: string; start?: { x: number; y: number; h: number }; end?: { x: number; y: number; h: number } }
  | { t: 'clipboardCopy'; text: string }
  | { t: 'dialog'; dialogId: number; kind: string; message: string; defaultText?: string }
  | { t: 'authRequired'; authId: number; origin: string; realm: string; scheme: string }
  | { t: 'notice'; kind: string; text: string }
  | ({ t: 'settings' } & UserSettings)
  | { t: 'sessionTakenOver' }
  | { t: 'error'; code: string; message: string }
  | { t: 'pong'; t0: number; server: number };

// ---------------------------------------------------------------------------
// Validation of incoming client messages.
// Each field spec: type name, optionally suffixed with '?' when optional.

type FieldType = 'string' | 'number' | 'boolean' | 'object' | 'array';
type FieldSpec = FieldType | `${FieldType}?`;

const tabOnly = { tabId: 'number' } as const;

const schemas: Record<ClientMessageType, Record<string, FieldSpec>> = {
  hello: { proto: 'number', app: 'string', appVersion: 'string', deviceId: 'string', device: 'object?', viewport: 'object?', caps: 'object?' },
  restoreBegin: { cookies: 'array', tabs: 'array', active: 'number', settings: 'object' },
  restoreLocal: { key: 'string', items: 'object' },
  restoreEnd: {},
  forget: {},
  viewport: { w: 'number', h: 'number', dpr: 'number' },
  navigate: { tabId: 'number', input: 'string' },
  suggest: { id: 'number', q: 'string' },
  back: tabOnly,
  forward: tabOnly,
  reload: tabOnly,
  stop: tabOnly,
  tabNew: { url: 'string?' },
  tabClose: tabOnly,
  tabActivate: tabOnly,
  touch: { phase: 'string', time: 'number', points: 'array' },
  fling: { x: 'number', y: 'number', vx: 'number', vy: 'number' },
  wheel: { x: 'number', y: 'number', dx: 'number', dy: 'number' },
  key: { phase: 'string', key: 'string', code: 'string', keyCode: 'number', mods: 'number', seq: 'number?' },
  commit: { text: 'string', cursor: 'number', seq: 'number?' },
  compose: { text: 'string', selStart: 'number', selEnd: 'number', seq: 'number?' },
  finishCompose: { seq: 'number?' },
  deleteSurrounding: { before: 'number', after: 'number', seq: 'number?' },
  setSelection: { start: 'number', end: 'number', seq: 'number?' },
  setComposingRegion: { start: 'number', end: 'number', seq: 'number?' },
  paste: { text: 'string', seq: 'number?' },
  pickerReply: { pickerId: 'number', cancel: 'boolean?', value: 'string?', selected: 'array?' },
  contextAction: { menuId: 'number', action: 'string' },
  selectionDrag: { handle: 'string', x: 'number', y: 'number' },
  selectionAction: { action: 'string' },
  dialogReply: { dialogId: 'number', accept: 'boolean', text: 'string?' },
  authReply: { authId: 'number', cancel: 'boolean?', user: 'string?', password: 'string?' },
  frameAck: { frameId: 'number' },
  setting: { key: 'string', value: 'string' },
  log: { level: 'string', msg: 'string' },
  ping: { t0: 'number' },
};

const enums: Partial<Record<ClientMessageType, Record<string, readonly string[]>>> = {
  touch: { phase: ['start', 'move', 'end', 'cancel'] },
  key: { phase: ['down', 'up'] },
  selectionDrag: { handle: ['start', 'end'] },
  selectionAction: { action: ['copy', 'selectAll', 'clear'] },
};

/** At least 128 bits of base64url. */
export const DEVICE_ID_RE = /^[A-Za-z0-9_-]{22,128}$/;

function typeOf(value: unknown): FieldType | 'other' {
  if (Array.isArray(value)) return 'array';
  if (value === null) return 'other';
  const t = typeof value;
  if (t === 'number') return Number.isFinite(value) ? 'number' : 'other';
  if (t === 'string' || t === 'boolean' || t === 'object') return t;
  return 'other';
}

function validPoint(p: unknown): p is TouchPoint {
  if (typeOf(p) !== 'object') return false;
  const o = p as Record<string, unknown>;
  if (typeOf(o.id) !== 'number' || typeOf(o.x) !== 'number' || typeOf(o.y) !== 'number') return false;
  for (const k of ['rx', 'ry', 'force']) {
    if (o[k] !== undefined && typeOf(o[k]) !== 'number') return false;
  }
  return true;
}

/** Parses and validates a text frame. Returns null for anything malformed. */
export function parseClientMessage(raw: string): ClientMessage | null {
  let msg: unknown;
  try {
    msg = JSON.parse(raw);
  } catch {
    return null;
  }
  if (typeOf(msg) !== 'object') return null;
  const obj = msg as Record<string, unknown>;
  if (typeof obj.t !== 'string' || !Object.hasOwn(schemas, obj.t)) return null;
  const type = obj.t as ClientMessageType;
  for (const [field, spec] of Object.entries(schemas[type])) {
    const optional = spec.endsWith('?');
    const expected = (optional ? spec.slice(0, -1) : spec) as FieldType;
    const value = obj[field];
    if (value === undefined) {
      if (optional) continue;
      return null;
    }
    if (typeOf(value) !== expected) return null;
  }
  for (const [field, allowed] of Object.entries(enums[type] ?? {})) {
    if (!allowed.includes(obj[field] as string)) return null;
  }
  if (type === 'touch') {
    const points = obj.points as unknown[];
    if (points.length > 10 || !points.every(validPoint)) return null;
  }
  if (type === 'hello' && !DEVICE_ID_RE.test(obj.deviceId as string)) return null;
  if (type === 'restoreLocal' && !Object.values(obj.items as object).every((v) => typeof v === 'string')) return null;
  if (type === 'pickerReply' && obj.selected !== undefined) {
    if (!(obj.selected as unknown[]).every((n) => typeOf(n) === 'number')) return null;
  }
  return obj as ClientMessage;
}

// ---------------------------------------------------------------------------
// Binary screen update (server → client). DESIGN.md §5.4.
//
// One message = a 60-byte header followed by rectangles. Each rectangle is a
// 16-byte header plus an encoded image that the client paints at (x, y) onto
// the picture it already shows. A keyframe replaces the whole picture.

export const FRAME_HEADER_SIZE = 60;
export const RECT_HEADER_SIZE = 16;
export const MSG_FRAME = 1;

export enum ImageFormat {
  Jpeg = 0,
  Png = 1,
  Webp = 2,
}

export const FRAME_FLAG_KEYFRAME = 1;
export const FRAME_FLAG_SCROLL_TARGET = 2;
/** The update only sharpens areas sent lossy before; nothing on the page changed. */
export const FRAME_FLAG_REFINE = 4;

export interface ScrollTarget {
  id: number;
  rect: Rect;
  scrollLeft: number;
  scrollTop: number;
}

export interface FrameHeader {
  flags: number;
  frameId: number;
  tabId: number;
  /** Size of the whole picture in physical pixels. */
  width: number;
  height: number;
  pageScale: number;
  scrollX: number;
  scrollY: number;
  offsetTop: number;
  scrollTarget?: ScrollTarget;
}

export interface FrameRect {
  x: number;
  y: number;
  w: number;
  h: number;
  format: ImageFormat;
  data: Buffer;
}

export function encodeFrame(header: FrameHeader, rects: FrameRect[]): Buffer {
  const size = FRAME_HEADER_SIZE + rects.reduce((n, r) => n + RECT_HEADER_SIZE + r.data.length, 0);
  const out = Buffer.allocUnsafe(size);
  const st = header.scrollTarget;
  const flags = (header.flags & ~FRAME_FLAG_SCROLL_TARGET) | (st ? FRAME_FLAG_SCROLL_TARGET : 0);
  out.writeUInt8(MSG_FRAME, 0);
  out.writeUInt8(flags, 1);
  out.writeUInt16LE(rects.length, 2);
  out.writeUInt32LE(header.frameId >>> 0, 4);
  out.writeUInt32LE(header.tabId >>> 0, 8);
  out.writeUInt16LE(header.width, 12);
  out.writeUInt16LE(header.height, 14);
  out.writeFloatLE(header.pageScale, 16);
  out.writeFloatLE(header.scrollX, 20);
  out.writeFloatLE(header.scrollY, 24);
  out.writeFloatLE(header.offsetTop, 28);
  out.writeFloatLE(st?.rect.x ?? 0, 32);
  out.writeFloatLE(st?.rect.y ?? 0, 36);
  out.writeFloatLE(st?.rect.w ?? 0, 40);
  out.writeFloatLE(st?.rect.h ?? 0, 44);
  out.writeFloatLE(st?.scrollLeft ?? 0, 48);
  out.writeFloatLE(st?.scrollTop ?? 0, 52);
  out.writeUInt32LE((st?.id ?? 0) >>> 0, 56);
  let o = FRAME_HEADER_SIZE;
  for (const r of rects) {
    out.writeUInt16LE(r.x, o);
    out.writeUInt16LE(r.y, o + 2);
    out.writeUInt16LE(r.w, o + 4);
    out.writeUInt16LE(r.h, o + 6);
    out.writeUInt8(r.format, o + 8);
    out.writeUInt8(0, o + 9);
    out.writeUInt16LE(0, o + 10);
    out.writeUInt32LE(r.data.length, o + 12);
    r.data.copy(out, o + RECT_HEADER_SIZE);
    o += RECT_HEADER_SIZE + r.data.length;
  }
  return out;
}

export function decodeFrame(buf: Buffer): { header: FrameHeader; rects: FrameRect[] } {
  if (buf.length < FRAME_HEADER_SIZE || buf.readUInt8(0) !== MSG_FRAME) throw new Error('not a frame');
  const flags = buf.readUInt8(1);
  const header: FrameHeader = {
    flags,
    frameId: buf.readUInt32LE(4),
    tabId: buf.readUInt32LE(8),
    width: buf.readUInt16LE(12),
    height: buf.readUInt16LE(14),
    pageScale: buf.readFloatLE(16),
    scrollX: buf.readFloatLE(20),
    scrollY: buf.readFloatLE(24),
    offsetTop: buf.readFloatLE(28),
  };
  if (flags & FRAME_FLAG_SCROLL_TARGET) {
    header.scrollTarget = {
      rect: { x: buf.readFloatLE(32), y: buf.readFloatLE(36), w: buf.readFloatLE(40), h: buf.readFloatLE(44) },
      scrollLeft: buf.readFloatLE(48),
      scrollTop: buf.readFloatLE(52),
      id: buf.readUInt32LE(56),
    };
  }
  const rects: FrameRect[] = [];
  let o = FRAME_HEADER_SIZE;
  for (let i = 0; i < buf.readUInt16LE(2); i++) {
    const length = buf.readUInt32LE(o + 12);
    rects.push({
      x: buf.readUInt16LE(o),
      y: buf.readUInt16LE(o + 2),
      w: buf.readUInt16LE(o + 4),
      h: buf.readUInt16LE(o + 6),
      format: buf.readUInt8(o + 8),
      data: buf.subarray(o + RECT_HEADER_SIZE, o + RECT_HEADER_SIZE + length),
    });
    o += RECT_HEADER_SIZE + length;
  }
  return { header, rects };
}
