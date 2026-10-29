import { join } from 'node:path';

function int(name: string, fallback: number): number {
  const raw = process.env[name];
  if (raw === undefined || raw === '') return fallback;
  const value = Number.parseInt(raw, 10);
  if (!Number.isFinite(value)) throw new Error(`${name} must be an integer, got "${raw}"`);
  return value;
}

const dataDir = process.env.DATA_DIR ?? '/data';

export const config = {
  port: int('PORT', 8686),
  /** Plain-HTTP port that serves only the APK. 0 disables it. */
  installPort: int('INSTALL_PORT', 8687),
  dataDir,
  tlsDir: join(dataDir, 'tls'),
  apkPath: join(dataDir, 'apk', 'surfer.apk'),
  chromePath: process.env.CHROME_PATH ?? '/usr/bin/chromium',
  /**
   * End a device's session (Chromium closed, profile deleted) after this many
   * minutes without a connection. 0 = keep until the server stops.
   */
  idleStopMinutes: int('IDLE_STOP_MIN', 30),
  acceptLanguage: process.env.ACCEPT_LANGUAGE ?? 'en-US,en',
  /** Format of the sharp frame sent once the page is still: lossless PNG or WebP (quality 100). */
  idleFormat: (process.env.IDLE_FORMAT === 'webp' ? 'webp' : 'png') as 'png' | 'webp',
  /** Serve the desktop debug client at /debug/. */
  debugClient: process.env.DEBUG_CLIENT !== '0',
  logLevel: (process.env.LOG_LEVEL ?? 'info') as 'debug' | 'info' | 'warn' | 'error',
};
