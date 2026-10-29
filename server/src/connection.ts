import { createHash } from 'node:crypto';
import type { WebSocket } from 'ws';
import { logger, type Logger } from './log.js';
import { parseClientMessage, PROTOCOL_VERSION, type ClientMessage, type ServerMessage } from './protocol.js';
import type { Viewport } from './screencast.js';
import type { Client, RestoreState, Session, SessionManager } from './session.js';
import { sanitizeSettings } from './settings.js';

/** A connection must finish the handshake (and restore) within this time. */
const HANDSHAKE_TIMEOUT_MS = 120_000;
/** WebSocket-level heartbeat; two missed pongs close the connection. */
const HEARTBEAT_MS = 15_000;
/** Upper bound for the state a device may send to restore a session. */
const MAX_RESTORE_BYTES = 200 * 1024 * 1024;
const MAX_TABS = 100;

export const CLOSE_PROTOCOL = 4000;
export const CLOSE_TAKEN_OVER = 4001;
export const CLOSE_FORGOTTEN = 4002;
export const CLOSE_HANDSHAKE_TIMEOUT = 4003;

type State = 'hello' | 'restoring' | 'ready' | 'closed';

/** The session key: a hash, so the device's secret is never kept in memory or logs. */
function sessionKey(deviceId: string): string {
  return createHash('sha256').update(deviceId).digest('hex');
}

/** One device connection: handshake, optional restore, then relaying to its session. */
export class Connection implements Client {
  private state: State = 'hello';
  private key = '';
  private viewport: Viewport | undefined;
  private session: Session | undefined;
  private restore: RestoreState | undefined;
  private restoreBytes = 0;
  private log: Logger;
  private readonly handshakeTimer: NodeJS.Timeout;
  private readonly heartbeat: NodeJS.Timeout;
  private alive = true;

  constructor(
    private readonly ws: WebSocket,
    private readonly ip: string,
    private readonly sessions: SessionManager,
  ) {
    this.log = logger(`conn:${ip}`);
    this.handshakeTimer = setTimeout(() => {
      if (this.state !== 'ready') this.close(CLOSE_HANDSHAKE_TIMEOUT, 'handshake timeout');
    }, HANDSHAKE_TIMEOUT_MS);
    this.heartbeat = setInterval(() => {
      if (!this.alive) {
        this.log.info('heartbeat lost');
        this.ws.terminate();
        return;
      }
      this.alive = false;
      this.ws.ping();
    }, HEARTBEAT_MS);
    ws.on('pong', () => (this.alive = true));
    ws.on('message', (data, isBinary) => {
      this.alive = true;
      if (isBinary) {
        this.close(CLOSE_PROTOCOL, 'unexpected binary message');
        return;
      }
      const text = data.toString();
      const msg = parseClientMessage(text);
      if (!msg) {
        this.send({ t: 'error', code: 'badMessage', message: 'Malformed message' });
        return;
      }
      if (this.state === 'restoring') this.restoreBytes += text.length;
      void this.onMessage(msg).catch((err) => this.log.warn(`handling ${msg.t} failed`, err));
    });
    ws.on('close', () => this.onClose());
    ws.on('error', (err) => this.log.debug('socket error', err));
    this.log.info('connected');
  }

  // Client interface -----------------------------------------------------------

  send(msg: ServerMessage): void {
    if (this.ws.readyState === this.ws.OPEN) this.ws.send(JSON.stringify(msg));
  }

  sendFrame(frame: Buffer): void {
    if (this.ws.readyState === this.ws.OPEN) this.ws.send(frame, { binary: true });
  }

  takenOver(): void {
    this.send({ t: 'sessionTakenOver' });
    this.session = undefined;
    this.close(CLOSE_TAKEN_OVER, 'session taken over');
  }

  // ---------------------------------------------------------------------------

  private async onMessage(msg: ClientMessage): Promise<void> {
    if (msg.t === 'ping') {
      this.send({ t: 'pong', t0: msg.t0, server: Date.now() });
      return;
    }
    switch (this.state) {
      case 'hello':
        if (msg.t === 'hello') this.onHello(msg);
        return;
      case 'restoring':
        await this.onRestore(msg);
        return;
      case 'ready':
        if (msg.t === 'forget') await this.onForget();
        else if (msg.t === 'log') this.log.info(`device: [${msg.level}] ${msg.msg.slice(0, 4000)}`);
        else if (msg.t === 'viewport') this.viewport = { w: msg.w, h: msg.h, dpr: msg.dpr };
        if (msg.t !== 'forget' && msg.t !== 'log') await this.session?.handle(msg);
        return;
      case 'closed':
        return;
    }
  }

  private onHello(msg: Extract<ClientMessage, { t: 'hello' }>): void {
    if (msg.proto !== PROTOCOL_VERSION) {
      this.send({ t: 'error', code: 'protocol', message: `Server speaks protocol ${PROTOCOL_VERSION}; please update the app.` });
      this.close(CLOSE_PROTOCOL, 'protocol version');
      return;
    }
    this.key = sessionKey(msg.deviceId);
    this.log = logger(`conn:${this.key.slice(0, 8)}@${this.ip}`);
    this.log.info(`hello from ${msg.app} ${msg.appVersion} (${msg.device?.model ?? 'unknown device'})`);
    const v = msg.viewport;
    if (v && typeof v.w === 'number' && typeof v.h === 'number' && typeof v.dpr === 'number') this.viewport = v;

    const live = this.sessions.get(this.key);
    if (live) {
      this.attach(live, true);
      return;
    }
    this.state = 'restoring';
    this.send({ t: 'needRestore' });
  }

  private async onRestore(msg: ClientMessage): Promise<void> {
    if (this.restoreBytes > MAX_RESTORE_BYTES) {
      this.send({ t: 'error', code: 'restoreTooLarge', message: 'Saved browser data is too large.' });
      this.close(CLOSE_PROTOCOL, 'restore too large');
      return;
    }
    switch (msg.t) {
      case 'restoreBegin':
        this.restore = {
          cookies: msg.cookies,
          local: {},
          tabs: msg.tabs
            .slice(0, MAX_TABS)
            .filter((t): t is { url: string; session?: Record<string, Record<string, string>> } =>
              typeof t === 'object' && t !== null && typeof (t as { url?: unknown }).url === 'string')
            .map((t) => ({ url: t.url, session: validStorage(t.session) })),
          active: msg.active,
          settings: sanitizeSettings(msg.settings),
        };
        return;
      case 'restoreLocal':
        if (this.restore) this.restore.local[msg.key] = msg.items as Record<string, string>;
        return;
      case 'restoreEnd': {
        if (!this.restore) {
          this.close(CLOSE_PROTOCOL, 'restoreEnd without restoreBegin');
          return;
        }
        // Another connection of this device may have started the session meanwhile.
        const session = this.sessions.get(this.key) ?? this.sessions.create(this.key, this.restore, this.viewport);
        this.restore = undefined;
        this.attach(session, false);
        return;
      }
      default:
        return;
    }
  }

  private attach(session: Session, resumed: boolean): void {
    clearTimeout(this.handshakeTimer);
    this.state = 'ready';
    this.session = session;
    this.send({ t: 'ready', resumed, settings: session.settingsNow });
    session.attach(this);
    if (this.viewport) void session.handle({ t: 'viewport', ...this.viewport });
    session.ready.catch(() => {
      this.send({ t: 'error', code: 'browserStart', message: 'The browser could not be started.' });
      this.close(1011, 'browser start failed');
    });
  }

  private async onForget(): Promise<void> {
    const session = this.session;
    this.session = undefined;
    await session?.stop();
    this.log.info('session forgotten');
    this.send({ t: 'forgotten' });
    this.close(CLOSE_FORGOTTEN, 'forgotten');
  }

  private close(code: number, reason: string): void {
    if (this.state === 'closed') return;
    this.onClose();
    this.ws.close(code, reason);
  }

  private onClose(): void {
    if (this.state === 'closed') return;
    this.state = 'closed';
    clearTimeout(this.handshakeTimer);
    clearInterval(this.heartbeat);
    this.session?.detach(this);
    this.session = undefined;
    this.log.info('disconnected');
  }
}

/** Keeps only string items of a storage-key → items map. */
function validStorage(raw: unknown): Record<string, Record<string, string>> | undefined {
  if (typeof raw !== 'object' || raw === null) return undefined;
  const out: Record<string, Record<string, string>> = {};
  for (const [key, items] of Object.entries(raw)) {
    if (typeof items !== 'object' || items === null) continue;
    const clean: Record<string, string> = {};
    for (const [name, value] of Object.entries(items)) if (typeof value === 'string') clean[name] = value;
    out[key] = clean;
  }
  return out;
}
