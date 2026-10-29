import { execFile } from 'node:child_process';
import { chmod, mkdir, readFile, access } from 'node:fs/promises';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { X509Certificate } from 'node:crypto';
import { config } from './config.js';
import { logger } from './log.js';

const log = logger('tls');
const run = promisify(execFile);

export interface TlsMaterial {
  key: Buffer;
  cert: Buffer;
  fingerprint256: string;
}

async function exists(path: string): Promise<boolean> {
  try {
    await access(path);
    return true;
  } catch {
    return false;
  }
}

/**
 * Loads the server certificate, creating it on first start.
 * RSA-2048/SHA-256 because Android 4.4 handles it everywhere; the app pins this
 * exact certificate, so its subject and SAN are informational only.
 */
export async function loadOrCreateTls(): Promise<TlsMaterial> {
  const keyPath = join(config.tlsDir, 'server.key');
  const certPath = join(config.tlsDir, 'server.crt');
  if (!(await exists(keyPath)) || !(await exists(certPath))) {
    await mkdir(config.tlsDir, { recursive: true });
    log.info('generating self-signed certificate');
    await run('openssl', [
      'req', '-x509', '-newkey', 'rsa:2048', '-sha256', '-days', '3650', '-nodes',
      '-keyout', keyPath, '-out', certPath,
      '-subj', '/CN=Surfer',
      '-addext', 'subjectAltName=DNS:surfer',
      '-addext', 'basicConstraints=critical,CA:TRUE',
      '-addext', 'keyUsage=critical,digitalSignature,keyEncipherment,keyCertSign',
      '-addext', 'extendedKeyUsage=serverAuth',
    ]);
    await chmod(keyPath, 0o600);
  }
  const key = await readFile(keyPath);
  const cert = await readFile(certPath);
  const fingerprint256 = new X509Certificate(cert).fingerprint256;
  log.info(`certificate SHA-256 ${fingerprint256}`);
  return { key, cert, fingerprint256 };
}
