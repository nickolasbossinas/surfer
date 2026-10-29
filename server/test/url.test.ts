import { describe, expect, it } from 'vitest';
import { resolveInput } from '../src/url.js';

const SEARCH = 'https://search.example/?q=%s';

describe('resolveInput', () => {
  it.each([
    ['example.com', 'https://example.com'],
    ['mail.google.com/mail/u/0', 'https://mail.google.com/mail/u/0'],
    ['https://example.com/a?b=c', 'https://example.com/a?b=c'],
    ['http://example.com', 'http://example.com/'],
    ['192.168.1.1', 'http://192.168.1.1'],
    ['192.168.1.1:8080/admin', 'http://192.168.1.1:8080/admin'],
    ['localhost:3000', 'http://localhost:3000'],
    ['router.lan:80', 'http://router.lan:80'],
    ['about:blank', 'about:blank'],
    ['chrome://sandbox', 'chrome://sandbox'],
    ['  example.org  ', 'https://example.org'],
  ])('%s → %s', (input, expected) => {
    expect(resolveInput(input, SEARCH)).toBe(expected);
  });

  it('searches for anything that is not an address', () => {
    expect(resolveInput('weather in athens', SEARCH)).toBe('https://search.example/?q=weather%20in%20athens');
    expect(resolveInput('hello', SEARCH)).toBe('https://search.example/?q=hello');
    expect(resolveInput('c++', SEARCH)).toBe('https://search.example/?q=c%2B%2B');
  });

  it('refuses dangerous schemes', () => {
    expect(resolveInput('file:///etc/passwd', SEARCH)).toBeNull();
    expect(resolveInput('javascript:alert(1)', SEARCH)).toBeNull();
    expect(resolveInput('data:text/html,hi', SEARCH)).toBeNull();
  });

  it('ignores empty input', () => {
    expect(resolveInput('   ', SEARCH)).toBeNull();
  });
});
