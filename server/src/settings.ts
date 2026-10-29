import type { UserSettings } from './protocol.js';

export const DEFAULT_SETTINGS: UserSettings = {
  layout: 'tablet',
  searchEngine: 'https://www.google.com/search?q=%s',
};

const LAYOUTS = new Set(['tablet', 'phone', 'desktop']);
const SEARCH_RE = /^https?:\/\/\S+%s/;

/** Validates one setting; returns the updated settings or null if invalid. */
export function applySetting(settings: UserSettings, key: string, value: unknown): UserSettings | null {
  if (key === 'layout' && typeof value === 'string' && LAYOUTS.has(value)) {
    return { ...settings, layout: value as UserSettings['layout'] };
  }
  if (key === 'searchEngine' && typeof value === 'string' && SEARCH_RE.test(value)) {
    return { ...settings, searchEngine: value };
  }
  return null;
}

/** Settings as saved by the device, with anything invalid replaced by defaults. */
export function sanitizeSettings(raw: Record<string, unknown> | undefined): UserSettings {
  let settings = { ...DEFAULT_SETTINGS };
  for (const [key, value] of Object.entries(raw ?? {})) {
    settings = applySetting(settings, key, value) ?? settings;
  }
  return settings;
}
