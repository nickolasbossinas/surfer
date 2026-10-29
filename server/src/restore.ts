// Puts the device's saved DOM storage back into a fresh browser.
//
// Storage can only be written from a document of the right origin. For each
// origin, the page navigates to it with every request intercepted and answered
// with an empty document, so nothing goes over the network; the items are then
// written with a script. See DESIGN.md §3.2.

import type { CDPSession } from 'puppeteer-core';
import { logger } from './log.js';
import type { StorageArea } from './sync.js';
import { originOfStorageKey } from './sync.js';

const log = logger('restore');
const LOAD_TIMEOUT_MS = 5000;

/** storage key → item name → value */
export type StorageData = Record<string, Record<string, string>>;

function waitForLoad(cdp: CDPSession): Promise<void> {
  return new Promise((resolve) => {
    const done = () => {
      clearTimeout(timer);
      cdp.off('Page.loadEventFired', done);
      resolve();
    };
    const timer = setTimeout(done, LOAD_TIMEOUT_MS);
    cdp.on('Page.loadEventFired', done);
  });
}

/**
 * Writes `data` into `area` storage using the page behind `cdp`. The page is
 * left on the last blank origin document; the caller navigates it afterwards.
 */
export async function writeStorage(cdp: CDPSession, area: StorageArea, data: StorageData): Promise<number> {
  const entries = Object.entries(data)
    .map(([key, items]) => [originOfStorageKey(key), items] as const)
    .filter((e): e is readonly [string, Record<string, string>] => e[0] !== null && Object.keys(e[1]).length > 0);
  if (entries.length === 0) return 0;

  const fulfill = (e: { requestId: string }) => {
    cdp
      .send('Fetch.fulfillRequest', {
        requestId: e.requestId,
        responseCode: 200,
        responseHeaders: [{ name: 'Content-Type', value: 'text/html; charset=utf-8' }],
        body: '',
      })
      .catch(() => {});
  };
  await cdp.send('Page.enable');
  cdp.on('Fetch.requestPaused', fulfill);
  await cdp.send('Fetch.enable', { patterns: [{ urlPattern: '*', requestStage: 'Request' }] });
  let written = 0;
  try {
    const storage = area === 'local' ? 'localStorage' : 'sessionStorage';
    for (const [origin, items] of entries) {
      try {
        const loaded = waitForLoad(cdp);
        const nav = await cdp.send('Page.navigate', { url: `${origin}/` });
        if (nav.errorText) throw new Error(nav.errorText);
        await loaded;
        // The items are embedded as JSON, which is a valid JS expression.
        const { exceptionDetails } = await cdp.send('Runtime.evaluate', {
          expression: `(() => { const d = ${JSON.stringify(items)}; for (const k of Object.keys(d)) ${storage}.setItem(k, d[k]); })()`,
        });
        if (exceptionDetails) throw new Error(exceptionDetails.exception?.description ?? exceptionDetails.text);
        written++;
      } catch (err) {
        log.warn(`could not restore ${area} storage of ${origin}`, (err as Error).message);
      }
    }
  } finally {
    cdp.off('Fetch.requestPaused', fulfill);
    await cdp.send('Fetch.disable').catch(() => {});
  }
  return written;
}
