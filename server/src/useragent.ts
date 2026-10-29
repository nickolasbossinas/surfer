import type { Protocol } from 'puppeteer-core';
import type { UserSettings } from './protocol.js';

export interface UserAgentOverride {
  userAgent: string;
  acceptLanguage: string;
  platform: string;
  /** Client hints; without them none are sent (as Safari does). */
  userAgentMetadata?: Protocol.Emulation.UserAgentMetadata;
}

/**
 * Sites whose Android web version does not work in Surfer's browser. Google
 * Maps for Android never receives input from Chromium's DevTools touch events
 * (and blocks the tab's touch handling, which then stays stuck on every page of
 * the site). Its iPad version works with touch and keeps the tablet layout.
 */
const IPAD_SITES = [/^https:\/\/(www\.)?google\.[a-z.]+\/maps([/?#]|$)/, /^https:\/\/maps\.google\.[a-z.]+([/?#]|$)/];

export function needsIpadIdentity(url: string): boolean {
  return IPAD_SITES.some((re) => re.test(url));
}

/** Chrome on iPad: a touch tablet that is not Android. */
export function ipadUserAgent(browserVersion: string, acceptLanguage: string): UserAgentOverride {
  const major = /\/(\d+)/.exec(browserVersion)?.[1] ?? '130';
  return {
    userAgent: `Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/${major}.0.0.0 Mobile/15E148 Safari/604.1`,
    acceptLanguage,
    platform: 'iPad',
  };
}

/**
 * Builds a user agent (and matching client hints) for the chosen layout, in
 * Chrome's reduced-UA format, using the real Chromium version of the server.
 * `browserVersion` is what Browser.version() returns, e.g. "HeadlessChrome/131.0.6778.85".
 */
export function userAgentFor(
  layout: UserSettings['layout'],
  browserVersion: string,
  acceptLanguage: string,
): UserAgentOverride {
  const full = /\/(\d+(?:\.\d+)*)/.exec(browserVersion)?.[1] ?? '130.0.0.0';
  const major = full.split('.')[0] ?? '130';
  const brands = [
    { brand: 'Chromium', version: major },
    { brand: 'Not_A Brand', version: '24' },
  ];
  const fullVersionList = [
    { brand: 'Chromium', version: full },
    { brand: 'Not_A Brand', version: '24.0.0.0' },
  ];
  const engine = 'AppleWebKit/537.36 (KHTML, like Gecko)';

  if (layout === 'desktop') {
    return {
      userAgent: `Mozilla/5.0 (X11; Linux x86_64) ${engine} Chrome/${major}.0.0.0 Safari/537.36`,
      acceptLanguage,
      platform: 'Linux x86_64',
      userAgentMetadata: {
        brands, fullVersionList, fullVersion: full,
        platform: 'Linux', platformVersion: '', architecture: 'x86', model: '', mobile: false, bitness: '64',
      },
    };
  }
  const mobile = layout === 'phone';
  return {
    userAgent: `Mozilla/5.0 (Linux; Android 10; K) ${engine} Chrome/${major}.0.0.0 ${mobile ? 'Mobile ' : ''}Safari/537.36`,
    acceptLanguage,
    platform: 'Linux armv8l',
    userAgentMetadata: {
      brands, fullVersionList, fullVersion: full,
      platform: 'Android', platformVersion: '14.0.0', architecture: '', model: '', mobile, bitness: '',
    },
  };
}
