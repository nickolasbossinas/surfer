import { describe, expect, it } from 'vitest';
import { parseSuggestions, providerFor } from '../src/suggest.js';

describe('providerFor', () => {
  it('knows the common search engines', () => {
    expect(providerFor('https://www.google.com/search?q=%s')?.url('a b', 'en-US')).toContain('q=a%20b');
    expect(providerFor('https://www.google.gr/search?q=%s')).toBeDefined();
    expect(providerFor('https://www.bing.com/search?q=%s')).toBeDefined();
    expect(providerFor('https://duckduckgo.com/?q=%s')).toBeDefined();
  });

  it('has nothing for unknown engines', () => {
    expect(providerFor('https://search.example/?q=%s')).toBeUndefined();
    expect(providerFor('not a url %s')).toBeUndefined();
  });
});

describe('parseSuggestions', () => {
  it('reads Google results with site suggestions', () => {
    const body = [
      'wea',
      ['weather', 'https://weather.com/', 'Wea', 'weather tomorrow'],
      ['', 'The Weather Channel', '', ''],
      [],
      { 'google:suggesttype': ['QUERY', 'NAVIGATION', 'QUERY', 'QUERY'] },
    ];
    expect(parseSuggestions(body, 'wea')).toEqual([
      { text: 'weather', kind: 'search' },
      { text: 'https://weather.com/', kind: 'url' },
      { text: 'weather tomorrow', kind: 'search' },
    ]);
  });

  it('reads plain OpenSearch results', () => {
    expect(parseSuggestions(['x', ['xbox', 'xkcd']], 'x')).toEqual([
      { text: 'xbox', kind: 'search' },
      { text: 'xkcd', kind: 'search' },
    ]);
  });

  it('ignores malformed responses', () => {
    expect(parseSuggestions({}, 'x')).toEqual([]);
    expect(parseSuggestions(['x', 'nope'], 'x')).toEqual([]);
    expect(parseSuggestions(['x', [1, null, 'ok']], 'x')).toEqual([{ text: 'ok', kind: 'search' }]);
    // A "site" that is not an http(s) URL is dropped.
    expect(parseSuggestions(['x', ['javascript:alert(1)'], [], [], { 'google:suggesttype': ['NAVIGATION'] }], 'x')).toEqual([]);
  });

  it('keeps at most six', () => {
    const texts = Array.from({ length: 10 }, (_, i) => `q${i}`);
    expect(parseSuggestions(['q', texts], 'q')).toHaveLength(6);
  });
});
