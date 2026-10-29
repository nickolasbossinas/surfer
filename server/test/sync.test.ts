import type { Protocol } from 'puppeteer-core';
import { describe, expect, it } from 'vitest';
import { applySetting, DEFAULT_SETTINGS, sanitizeSettings } from '../src/settings.js';
import { diffCookies, isRestorableCookie, originOfStorageKey, toCookieParam } from '../src/sync.js';

function cookie(name: string, value: string, extra: Partial<Protocol.Network.Cookie> = {}): Protocol.Network.Cookie {
  return {
    name, value, domain: '.example.com', path: '/', expires: -1, size: name.length + value.length,
    httpOnly: true, secure: true, session: true, priority: 'Medium', sameParty: false,
    sourceScheme: 'Secure', sourcePort: 443, ...extra,
  } as Protocol.Network.Cookie;
}

describe('diffCookies', () => {
  it('reports added, changed and removed cookies', () => {
    const first = diffCookies(new Map(), [cookie('a', '1'), cookie('b', '1')]);
    expect(first.delta.set.map((c) => c.name)).toEqual(['a', 'b']);
    expect(first.delta.removed).toEqual([]);

    const second = diffCookies(first.next, [cookie('a', '1'), cookie('b', '2'), cookie('c', '1')]);
    expect(second.delta.set.map((c) => c.name)).toEqual(['b', 'c']);

    const third = diffCookies(second.next, [cookie('c', '1')]);
    expect(third.delta.set).toEqual([]);
    expect(third.delta.removed.map((c) => c.name).sort()).toEqual(['a', 'b']);
  });

  it('treats the same name on another domain or partition as another cookie', () => {
    const base = diffCookies(new Map(), [cookie('sid', '1')]).next;
    const next = diffCookies(base, [
      cookie('sid', '1'),
      cookie('sid', '1', { domain: 'other.com' }),
      cookie('sid', '1', { partitionKey: { topLevelSite: 'https://top.com', hasCrossSiteAncestor: true } }),
    ]);
    expect(next.delta.set).toHaveLength(2);
  });

  it('ignores derived fields such as size', () => {
    const base = diffCookies(new Map(), [cookie('a', '1')]).next;
    expect(diffCookies(base, [cookie('a', '1', { size: 999 })]).delta.set).toEqual([]);
  });
});

describe('cookie restore', () => {
  it('drops expiry for session cookies and keeps it otherwise', () => {
    expect(toCookieParam(cookie('s', '1'))).not.toHaveProperty('expires');
    expect(toCookieParam(cookie('p', '1', { session: false, expires: 2_000_000_000 })).expires).toBe(2_000_000_000);
  });

  it('rejects malformed and expired cookies', () => {
    expect(isRestorableCookie(cookie('a', '1'))).toBe(true);
    expect(isRestorableCookie({ name: 'a' })).toBe(false);
    expect(isRestorableCookie(null)).toBe(false);
    expect(isRestorableCookie(cookie('old', '1', { session: false, expires: 1000 }), 2000)).toBe(false);
  });
});

describe('originOfStorageKey', () => {
  it('accepts first-party http(s) keys only', () => {
    expect(originOfStorageKey('https://mail.google.com/')).toBe('https://mail.google.com');
    expect(originOfStorageKey('http://192.168.1.1:8080/')).toBe('http://192.168.1.1:8080');
    expect(originOfStorageKey('https://embed.com/^0https://top.com')).toBeNull();
    expect(originOfStorageKey('chrome://settings/')).toBeNull();
    expect(originOfStorageKey('file:///')).toBeNull();
  });
});

describe('settings', () => {
  it('validates values and falls back to defaults', () => {
    expect(applySetting(DEFAULT_SETTINGS, 'layout', 'phone')?.layout).toBe('phone');
    expect(applySetting(DEFAULT_SETTINGS, 'layout', 'huge')).toBeNull();
    expect(applySetting(DEFAULT_SETTINGS, 'searchEngine', 'javascript:%s')).toBeNull();
    expect(sanitizeSettings({ layout: 'desktop', searchEngine: 42, extra: 'x' })).toEqual({ ...DEFAULT_SETTINGS, layout: 'desktop' });
  });
});
