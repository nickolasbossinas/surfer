import { describe, expect, it } from 'vitest';
import { ipadUserAgent, needsIpadIdentity, userAgentFor } from '../src/useragent.js';

describe('needsIpadIdentity', () => {
  it('matches Google Maps addresses', () => {
    for (const url of [
      'https://www.google.com/maps',
      'https://www.google.com/maps/@38.1,23.8,10z',
      'https://www.google.gr/maps?q=athens',
      'https://maps.google.com/',
      'https://maps.google.com',
    ]) {
      expect(needsIpadIdentity(url), url).toBe(true);
    }
  });

  it('leaves everything else alone', () => {
    for (const url of [
      'https://www.google.com/',
      'https://www.google.com/search?q=maps',
      'https://www.google.com/mapsearch',
      'https://example.com/www.google.com/maps',
      'http://maps.google.com.evil.example/',
    ]) {
      expect(needsIpadIdentity(url), url).toBe(false);
    }
  });
});

describe('user agents', () => {
  it('uses the server Chromium major version', () => {
    expect(ipadUserAgent('HeadlessChrome/156.0.8078.73', 'en-US').userAgent).toContain('CriOS/156.0.0.0');
    expect(ipadUserAgent('HeadlessChrome/156.0.8078.73', 'en-US').userAgentMetadata).toBeUndefined();
    expect(userAgentFor('tablet', 'HeadlessChrome/156.0.8078.73', 'en-US').userAgent).toContain('Android');
  });
});
