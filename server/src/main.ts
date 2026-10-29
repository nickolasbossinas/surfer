import { createReadStream, readFileSync } from 'node:fs';
import { stat } from 'node:fs/promises';
import { createServer as createHttpServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { createServer as createHttpsServer } from 'node:https';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { WebSocketServer } from 'ws';
import { config } from './config.js';
import { Connection } from './connection.js';
import { logger } from './log.js';
import { cleanStaleProfiles, SessionManager } from './session.js';
import { loadOrCreateTls } from './tls.js';

const log = logger('main');
const debugDir = join(dirname(fileURLToPath(import.meta.url)), '..', 'debug');
const DEBUG_FILES: Record<string, string> = {
  '/debug/': 'index.html',
  '/debug/index.html': 'index.html',
  '/debug/client.js': 'client.js',
};
const CONTENT_TYPES: Record<string, string> = {
  html: 'text/html; charset=utf-8',
  js: 'text/javascript; charset=utf-8',
};

function remoteIp(req: IncomingMessage): string {
  return (req.socket.remoteAddress ?? 'unknown').replace(/^::ffff:/, '');
}

async function sendFile(res: ServerResponse, path: string, contentType: string, extraHeaders: Record<string, string> = {}) {
  try {
    const info = await stat(path);
    res.writeHead(200, { 'Content-Type': contentType, 'Content-Length': info.size, ...extraHeaders });
    createReadStream(path).pipe(res);
  } catch {
    res.writeHead(404).end('Not found');
  }
}

async function main(): Promise<void> {
  const tls = await loadOrCreateTls();
  await cleanStaleProfiles();
  const sessions = new SessionManager();

  const https = createHttpsServer({ key: tls.key, cert: tls.cert, minVersion: 'TLSv1.2' }, (req, res) => {
    const path = new URL(req.url ?? '/', 'https://surfer').pathname;
    if (path === '/health') {
      res.writeHead(200, { 'Content-Type': 'text/plain' }).end('ok');
      return;
    }
    const file = DEBUG_FILES[path];
    if (config.debugClient && file && req.method === 'GET') {
      void sendFile(res, join(debugDir, file), CONTENT_TYPES[file.split('.').pop()!]!, {
        'Content-Security-Policy': "default-src 'self'; connect-src 'self'; img-src 'self' blob:; style-src 'self' 'unsafe-inline'",
        'Cache-Control': 'no-store',
      });
      return;
    }
    res.writeHead(404).end('Not found');
  });

  // Large enough for one origin's saved localStorage during a restore.
  const wss = new WebSocketServer({ noServer: true, maxPayload: 32 * 1024 * 1024, perMessageDeflate: false });
  https.on('upgrade', (req, socket, head) => {
    if (new URL(req.url ?? '/', 'https://surfer').pathname !== '/ws') {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      new Connection(ws, remoteIp(req), sessions);
    });
  });

  const { version } = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')) as { version: string };
  https.listen(config.port, () => log.info(`Surfer ${version} listening on https/wss port ${config.port} (in the container)`));

  // Plain HTTP, APK only: the tablet's old Chrome cannot open the self-signed HTTPS port.
  const install =
    config.installPort > 0
      ? createHttpServer((req, res) => {
          const path = new URL(req.url ?? '/', 'http://surfer').pathname;
          if (req.method === 'GET' && (path === '/' || path === '/app.apk' || path === '/surfer.apk')) {
            void sendFile(res, config.apkPath, 'application/vnd.android.package-archive', {
              'Content-Disposition': 'attachment; filename="surfer.apk"',
            });
            return;
          }
          res.writeHead(404).end('Not found');
        }).listen(config.installPort, () => log.info(`app download on http port ${config.installPort}`))
      : undefined;

  let shuttingDown = false;
  const shutdown = async (signal: string) => {
    if (shuttingDown) return;
    shuttingDown = true;
    log.info(`${signal}: saving sessions and stopping browsers`);
    for (const ws of wss.clients) ws.close(1001, 'server shutting down');
    https.close();
    install?.close();
    await sessions.stopAll();
    process.exit(0);
  };
  process.on('SIGTERM', () => void shutdown('SIGTERM'));
  process.on('SIGINT', () => void shutdown('SIGINT'));
}

main().catch((err) => {
  log.error('fatal', err);
  process.exit(1);
});
