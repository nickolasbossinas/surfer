// Search suggestions for the URL bar, fetched from the configured search
// engine's suggest service the way Chrome's omnibox does. See DESIGN.md §3.6.

import { config } from './config.js';
import { logger } from './log.js';

const log = logger('suggest');

const TIMEOUT_MS = 2500;
const MAX_ITEMS = 6;
export const MAX_QUERY_LENGTH = 512;

export interface Suggestion {
  /** The suggested query, or for a site suggestion its URL. */
  text: string;
  kind: 'search' | 'url';
}

interface Provider {
  url(query: string, language: string): string;
}

/** OpenSearch suggestion services of the search engines Surfer knows. */
const PROVIDERS: { host: RegExp; provider: Provider }[] = [
  {
    host: /(^|\.)google\.[a-z.]+$/,
    provider: {
      url: (q, lang) =>
        `https://www.google.com/complete/search?client=chrome&ie=UTF-8&oe=UTF-8&hl=${encodeURIComponent(lang)}&q=${encodeURIComponent(q)}`,
    },
  },
  {
    host: /(^|\.)bing\.com$/,
    provider: { url: (q, lang) => `https://api.bing.com/osjson.aspx?market=${encodeURIComponent(lang)}&query=${encodeURIComponent(q)}` },
  },
  {
    host: /(^|\.)duckduckgo\.com$/,
    provider: { url: (q) => `https://duckduckgo.com/ac/?type=list&q=${encodeURIComponent(q)}` },
  },
];

/** The suggest service for a search URL template, if Surfer knows one. */
export function providerFor(searchTemplate: string): Provider | undefined {
  let host: string;
  try {
    host = new URL(searchTemplate.replace('%s', 'x')).hostname;
  } catch {
    return undefined;
  }
  return PROVIDERS.find((p) => p.host.test(host))?.provider;
}

/**
 * Parses an OpenSearch suggestions response: [query, [texts], [descriptions],
 * [urls], extras]. Google's extras mark site suggestions as NAVIGATION.
 */
export function parseSuggestions(body: unknown, query: string): Suggestion[] {
  if (!Array.isArray(body) || !Array.isArray(body[1])) return [];
  const texts = body[1] as unknown[];
  const extras = body[4] as Record<string, unknown> | undefined;
  const marked = typeof extras === 'object' && extras !== null ? extras['google:suggesttype'] : undefined;
  const types: unknown[] = Array.isArray(marked) ? marked : [];
  const out: Suggestion[] = [];
  const seen = new Set<string>([query.trim().toLowerCase()]);
  texts.forEach((text, i) => {
    if (typeof text !== 'string' || text.length === 0 || text.length > 2048) return;
    const kind = types[i] === 'NAVIGATION' ? 'url' : 'search';
    if (kind === 'url' && !/^https?:\/\//i.test(text)) return;
    const key = text.toLowerCase();
    if (seen.has(key)) return;
    seen.add(key);
    out.push({ text, kind });
  });
  return out.slice(0, MAX_ITEMS);
}

/** Suggestions for what the user typed; empty on any failure. */
export async function fetchSuggestions(query: string, searchTemplate: string, signal: AbortSignal): Promise<Suggestion[]> {
  const provider = providerFor(searchTemplate);
  const q = query.trim();
  if (!provider || q.length === 0 || q.length > MAX_QUERY_LENGTH) return [];
  const language = config.acceptLanguage.split(',')[0]?.split(';')[0]?.trim() || 'en-US';
  try {
    const res = await fetch(provider.url(q, language), {
      // No cookies are sent: suggestions are not tied to the user's accounts.
      headers: { 'Accept-Language': config.acceptLanguage, Accept: 'application/json, text/javascript' },
      signal: AbortSignal.any([signal, AbortSignal.timeout(TIMEOUT_MS)]),
    });
    if (!res.ok) {
      log.debug(`suggest service answered ${res.status}`);
      return [];
    }
    return parseSuggestions(JSON.parse(await res.text()), q);
  } catch (err) {
    if (!signal.aborted) log.debug('suggestions failed', err);
    return [];
  }
}
