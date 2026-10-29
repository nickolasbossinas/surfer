// Keeps the device's copy of the browser state current: cookies (polled and
// diffed) and DOM storage (from DevTools events). See DESIGN.md §3.2.

import type { CDPSession, Protocol } from 'puppeteer-core';

type Cookie = Protocol.Network.Cookie;

export interface CookieRef {
  name: string;
  domain: string;
  path: string;
  partitionKey?: Protocol.Network.CookiePartitionKey;
}

export interface CookieDelta {
  set: Cookie[];
  removed: CookieRef[];
}

const COOKIE_POLL_MS = 1000;

export function cookieKey(c: CookieRef): string {
  const partition = c.partitionKey ? `${c.partitionKey.topLevelSite}|${c.partitionKey.hasCrossSiteAncestor}` : '';
  return `${c.name}\u0001${c.domain}\u0001${c.path}\u0001${partition}`;
}

/** The fields whose change matters to a site; `size` etc. are derived. */
function cookieValue(c: Cookie): string {
  return JSON.stringify([c.value, c.expires, c.session, c.secure, c.httpOnly, c.sameSite, c.priority, c.sourceScheme, c.sourcePort]);
}

/** Compares a snapshot with the previous one. Returns the delta and the new state. */
export function diffCookies(previous: Map<string, Cookie>, snapshot: Cookie[]): { delta: CookieDelta; next: Map<string, Cookie> } {
  const next = new Map<string, Cookie>();
  const set: Cookie[] = [];
  for (const c of snapshot) {
    const key = cookieKey(c);
    next.set(key, c);
    const old = previous.get(key);
    if (!old || cookieValue(old) !== cookieValue(c)) set.push(c);
  }
  const removed: CookieRef[] = [];
  for (const [key, c] of previous) {
    if (!next.has(key)) {
      removed.push({ name: c.name, domain: c.domain, path: c.path, ...(c.partitionKey ? { partitionKey: c.partitionKey } : {}) });
    }
  }
  return { delta: { set, removed }, next };
}

/** A cookie as stored on the device, converted for Storage.setCookies. */
export function toCookieParam(c: Cookie): Protocol.Network.CookieParam {
  return {
    name: c.name,
    value: c.value,
    domain: c.domain,
    path: c.path,
    secure: c.secure,
    httpOnly: c.httpOnly,
    sameSite: c.sameSite,
    priority: c.priority,
    sourceScheme: c.sourceScheme,
    sourcePort: c.sourcePort,
    partitionKey: c.partitionKey,
    ...(c.session ? {} : { expires: c.expires }),
  };
}

/** Rejects device-supplied cookies that are malformed or already expired. */
export function isRestorableCookie(c: unknown, nowSeconds = Date.now() / 1000): c is Cookie {
  if (typeof c !== 'object' || c === null) return false;
  const o = c as Record<string, unknown>;
  if (typeof o.name !== 'string' || typeof o.value !== 'string' || typeof o.domain !== 'string' || typeof o.path !== 'string') {
    return false;
  }
  if (o.session !== true && typeof o.expires === 'number' && o.expires > 0 && o.expires < nowSeconds) return false;
  return true;
}

/**
 * Polls the browser's cookie jar and reports changes. Chromium has no cookie
 * change event, so a 1 s diff is the "real time" here; it is cheap (a few
 * hundred cookies) and also catches cookies set by scripts and redirects.
 */
export class CookieSync {
  private state = new Map<string, Cookie>();
  private timer: NodeJS.Timeout | undefined;
  private polling = false;

  constructor(
    private readonly browserCdp: CDPSession,
    private readonly onDelta: (delta: CookieDelta) => void,
  ) {}

  /** Takes the current jar as the baseline (what the device already has). */
  async start(): Promise<void> {
    const { cookies } = await this.browserCdp.send('Storage.getCookies');
    this.state = diffCookies(new Map(), cookies).next;
    this.timer = setInterval(() => void this.poll(), COOKIE_POLL_MS);
  }

  /** Sends any pending change now (also used before shutting down). */
  async poll(): Promise<void> {
    if (this.polling) return;
    this.polling = true;
    try {
      const { cookies } = await this.browserCdp.send('Storage.getCookies');
      const { delta, next } = diffCookies(this.state, cookies);
      this.state = next;
      if (delta.set.length > 0 || delta.removed.length > 0) this.onDelta(delta);
    } catch {
      // Browser closing; the final state was already sent.
    } finally {
      this.polling = false;
    }
  }

  stop(): void {
    clearInterval(this.timer);
  }
}

// ---------------------------------------------------------------------------
// DOM storage

export type StorageArea = 'local' | 'session';

export interface StorageChange {
  area: StorageArea;
  /** Chromium storage key, e.g. "https://mail.google.com/". */
  key: string;
  op: 'set' | 'remove' | 'clear';
  name?: string;
  value?: string;
}

/** Subscribes to DOM storage changes of one page target. */
export async function watchStorage(cdp: CDPSession, onChange: (change: StorageChange) => void): Promise<void> {
  const id = (s: Protocol.DOMStorage.StorageId) => ({
    area: (s.isLocalStorage ? 'local' : 'session') as StorageArea,
    key: s.storageKey ?? s.securityOrigin ?? '',
  });
  cdp.on('DOMStorage.domStorageItemAdded', (e) => onChange({ ...id(e.storageId), op: 'set', name: e.key, value: e.newValue }));
  cdp.on('DOMStorage.domStorageItemUpdated', (e) => onChange({ ...id(e.storageId), op: 'set', name: e.key, value: e.newValue }));
  cdp.on('DOMStorage.domStorageItemRemoved', (e) => onChange({ ...id(e.storageId), op: 'remove', name: e.key }));
  cdp.on('DOMStorage.domStorageItemsCleared', (e) => onChange({ ...id(e.storageId), op: 'clear' }));
  await cdp.send('DOMStorage.enable');
}

/**
 * The origin to load in order to write a storage key, or null for keys that
 * cannot be restored this way (partitioned third-party storage, opaque origins).
 */
export function originOfStorageKey(key: string): string | null {
  const m = /^(https?:\/\/[^/^\s]+)\/?$/.exec(key);
  return m ? m[1]! : null;
}
