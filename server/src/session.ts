import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import puppeteer, { type Browser, type CDPSession, type Page, type Target } from 'puppeteer-core';
import { config } from './config.js';
import { logger, type Logger } from './log.js';
import type { ClientMessage, ServerMessage, UserSettings } from './protocol.js';
import { writeStorage, type StorageData } from './restore.js';
import { Screencast, type FrameSink, type Viewport } from './screencast.js';
import { applySetting } from './settings.js';
import { CookieSync, isRestorableCookie, toCookieParam, type StorageChange } from './sync.js';
import { fetchSuggestions } from './suggest.js';
import { Tab, type DialogMessage } from './tab.js';
import { resolveInput } from './url.js';
import { userAgentFor } from './useragent.js';

const PROFILE_PREFIX = 'surfer-profile-';
/** Sync messages kept while no device is connected (delivered on reconnect). */
const MAX_QUEUED_SYNC = 20_000;

/** The device connection a session streams to. */
export interface Client extends FrameSink {
  send(msg: ServerMessage): void;
  /** Another connection of the same device took over; this one must close. */
  takenOver(): void;
}

/** What the device saved and sends back to start a session. */
export interface RestoreState {
  cookies: unknown[];
  local: StorageData;
  tabs: { url: string; session?: StorageData }[];
  active: number;
  settings: UserSettings;
}

function validViewport(v: Viewport | undefined): v is Viewport {
  return !!v && v.w >= 100 && v.w <= 4096 && v.h >= 100 && v.h <= 4096 && v.dpr >= 0.5 && v.dpr <= 4;
}

/** Removes profiles left behind by a previous run (crash, kill -9). */
export async function cleanStaleProfiles(): Promise<void> {
  const dir = tmpdir();
  for (const name of await readdir(dir).catch(() => [] as string[])) {
    if (name.startsWith(PROFILE_PREFIX)) await rm(join(dir, name), { recursive: true, force: true });
  }
}

/**
 * One device's browser: a Chromium process with a temporary profile, filled
 * from the device's saved state and wiped when the session ends. Every change
 * to cookies and DOM storage is streamed back to the device.
 */
export class Session {
  private browser: Browser | undefined;
  private browserCdp: CDPSession | undefined;
  private browserVersion = '';
  private profileDir = '';
  private readonly tabs = new Map<number, Tab>();
  private readonly adopting = new WeakMap<Page, Promise<Tab | undefined>>();
  private nextTabId = 1;
  private activeId = 0;
  private client: Client | undefined;
  private viewport: Viewport | undefined;
  private screencast: Screencast | undefined;
  /** Serializes screencast restarts (tab switch, viewport change). */
  private castChain: Promise<void> = Promise.resolve();
  private cookieSync: CookieSync | undefined;
  private readonly syncQueue: ServerMessage[] = [];
  private syncOverflow = false;
  private idleTimer: NodeJS.Timeout | undefined;
  private stopping = false;
  /** Restore finished: tab lists and sync messages may go to the device. */
  private launched = false;
  private settings: UserSettings;
  /** The suggestions request in progress; a newer one cancels it. */
  private suggestAbort: AbortController | undefined;
  private readonly log: Logger;
  readonly ready: Promise<void>;

  constructor(
    readonly key: string,
    state: RestoreState,
    viewport: Viewport | undefined,
    private readonly onStopped: (session: Session) => void,
  ) {
    this.log = logger(`session:${key.slice(0, 8)}`);
    this.settings = state.settings;
    if (validViewport(viewport)) this.viewport = viewport;
    this.ready = this.launch(state);
    this.ready.catch((err) => {
      this.log.error('launch failed', err);
      void this.stop();
    });
  }

  get settingsNow(): UserSettings {
    return this.settings;
  }

  // -------------------------------------------------------------------------
  // Lifecycle

  private async launch(state: RestoreState): Promise<void> {
    this.profileDir = await mkdtemp(join(tmpdir(), PROFILE_PREFIX));
    const language = config.acceptLanguage.split(',')[0] ?? 'en-US';
    // Puppeteer's default flags turn off popup blocking, phishing protection and
    // background networking, and mark the browser as automated. Surfer is a real
    // browser, so it passes its own complete flag set instead.
    this.browser = await puppeteer.launch({
      executablePath: config.chromePath,
      headless: true,
      pipe: true,
      ignoreDefaultArgs: true,
      defaultViewport: null,
      handleSIGINT: false,
      handleSIGTERM: false,
      handleSIGHUP: false,
      args: [
        '--headless',
        `--user-data-dir=${this.profileDir}`,
        '--no-first-run',
        '--no-default-browser-check',
        '--password-store=basic',
        '--disable-blink-features=AutomationControlled',
        '--mute-audio',
        '--hide-scrollbars',
        '--force-color-profile=srgb',
        '--disable-features=Translate,MediaRouter',
        `--lang=${language}`,
        // Without this the screencast renders at CSS size while the page moves
        // and only the still screenshots use the emulated pixel ratio.
        ...(this.viewport ? [`--force-device-scale-factor=${this.viewport.dpr}`] : []),
      ],
    });
    this.browserVersion = await this.browser.version();
    this.browserCdp = await this.browser.target().createCDPSession();
    this.log.info(`started ${this.browserVersion}`);
    this.browser.on('disconnected', () => {
      if (!this.stopping) {
        this.log.error('Chromium exited unexpectedly');
        this.client?.send({ t: 'error', code: 'browserCrashed', message: 'The browser stopped. Reconnecting restarts it.' });
        void this.stop();
      }
    });
    await this.browserCdp.send('Browser.setDownloadBehavior', { behavior: 'deny', eventsEnabled: true });

    // 1. Cookies, before anything loads.
    const cookies = state.cookies.filter((c) => isRestorableCookie(c));
    if (cookies.length > 0) {
      await this.browserCdp.send('Storage.setCookies', { cookies: cookies.map(toCookieParam) }).catch((err) =>
        this.log.warn('restoring cookies failed', err),
      );
    }

    // 2. localStorage, through a helper page that is never shown or synced.
    const [initialPage] = await this.browser.pages();
    if (Object.keys(state.local).length > 0) {
      const helper = await this.browser.newPage();
      const helperCdp = await helper.createCDPSession();
      const restored = await writeStorage(helperCdp, 'local', state.local);
      await helper.close();
      this.log.info(`restored ${cookies.length} cookies, localStorage of ${restored} origins`);
    }

    // 3. From here on every page is a tab and every change is synced.
    this.browser.on('targetcreated', (target: Target) => void this.onTargetCreated(target));
    this.browser.on('targetdestroyed', (target: Target) => this.onTargetDestroyed(target));
    this.cookieSync = new CookieSync(this.browserCdp, (delta) => this.sync({ t: 'cookies', ...delta }));
    await this.cookieSync.start();

    // 4. Tabs, each with its sessionStorage.
    const saved = state.tabs.filter((t) => /^(https?|about|chrome):/.test(t.url));
    const first = initialPage ? await this.adopt(initialPage) : undefined;
    const tabs: Tab[] = [];
    for (let i = 0; i < Math.max(saved.length, 1); i++) {
      const tab = i === 0 && first ? first : await this.adopt(await this.browser.newPage());
      if (tab) tabs.push(tab);
    }
    await Promise.all(tabs.map((tab, i) => (saved[i] ? tab.restore(saved[i].url, saved[i].session) : undefined)));
    const active = tabs[Math.min(Math.max(state.active, 0), tabs.length - 1)] ?? tabs[0];
    if (active) await this.activate(active.id);
    this.launched = true;
  }

  /** Closes Chromium and deletes its profile. The device keeps its copy. */
  async stop(): Promise<void> {
    if (this.stopping) return;
    this.stopping = true;
    clearTimeout(this.idleTimer);
    this.cookieSync?.stop();
    await this.screencast?.stop().catch(() => {});
    if (this.browser?.connected) {
      await this.cookieSync?.poll();
      await this.browser.close().catch((err) => this.log.warn('close failed', err));
    }
    if (this.profileDir) await rm(this.profileDir, { recursive: true, force: true }).catch(() => {});
    this.log.info('stopped, profile deleted');
    this.onStopped(this);
  }

  // -------------------------------------------------------------------------
  // Device attach / detach

  attach(client: Client): void {
    clearTimeout(this.idleTimer);
    if (this.client && this.client !== client) this.client.takenOver();
    this.client = client;
    void this.ready.then(() => {
      if (this.client !== client) return;
      // Changes made while no device was connected (or during the restore), then the tab list.
      for (const msg of this.syncQueue.splice(0)) client.send(msg);
      if (this.syncOverflow) {
        this.log.warn('some storage changes made while disconnected were not kept');
        this.syncOverflow = false;
      }
      this.sendTabs();
      this.restartCast();
      this.resendDialog();
    });
  }

  detach(client: Client): void {
    if (this.client !== client) return;
    this.client = undefined;
    this.restartCast();
    if (config.idleStopMinutes > 0) {
      this.idleTimer = setTimeout(() => {
        this.log.info('no device connected, ending session');
        void this.stop();
      }, config.idleStopMinutes * 60_000);
    }
  }

  // -------------------------------------------------------------------------
  // Messages from the device

  async handle(msg: ClientMessage): Promise<void> {
    await this.ready;
    if (this.stopping) return;
    const active = this.tabs.get(this.activeId);
    switch (msg.t) {
      case 'viewport':
        await this.setViewport({ w: msg.w, h: msg.h, dpr: msg.dpr });
        return;
      case 'frameAck':
        this.screencast?.clientAck(msg.frameId);
        return;
      case 'touch':
        this.traceTouch(msg);
        active?.input.touch(msg, this.screencast?.pageScale);
        return;
      case 'wheel':
        active?.input.wheel(msg, this.screencast?.pageScale);
        return;
      case 'fling':
        active?.input.fling(msg);
        return;
      case 'key':
      case 'commit':
      case 'paste':
      case 'compose':
      case 'finishCompose':
      case 'deleteSurrounding':
      case 'setSelection':
      case 'setComposingRegion':
        await active?.ime.handle(msg);
        return;
      case 'navigate': {
        const url = resolveInput(msg.input, this.settings.searchEngine);
        if (url === null) {
          this.client?.send({ t: 'notice', kind: 'blockedUrl', text: 'This address cannot be opened.' });
          return;
        }
        this.tabs.get(msg.tabId)?.navigate(url);
        return;
      }
      case 'suggest':
        // Not awaited: the next keystroke's request must not wait behind this one.
        void this.suggest(msg.id, msg.q);
        return;
      case 'back':
        await this.tabs.get(msg.tabId)?.back();
        return;
      case 'forward':
        await this.tabs.get(msg.tabId)?.forward();
        return;
      case 'reload':
        this.tabs.get(msg.tabId)?.reload();
        return;
      case 'stop':
        this.tabs.get(msg.tabId)?.stop();
        return;
      case 'tabNew':
        await this.newTab(msg.url);
        return;
      case 'tabClose':
        await this.tabs.get(msg.tabId)?.close();
        return;
      case 'tabActivate':
        await this.activate(msg.tabId);
        return;
      case 'setting':
        await this.applySetting(msg.key, msg.value);
        return;
      case 'dialogReply':
        for (const tab of this.tabs.values()) if (tab.answerDialog(msg.dialogId, msg.accept, msg.text)) break;
        return;
      default:
        // Messages of later phases (IME details, pickers, dialogs, ...).
        this.log.debug(`unhandled message ${msg.t}`);
    }
  }

  /** Diagnostics: one log line per gesture with more than one finger. */
  private gestureFingers = 0;
  private traceTouch(msg: Extract<ClientMessage, { t: 'touch' }>): void {
    if (msg.phase === 'start') this.gestureFingers = 0;
    this.gestureFingers = Math.max(this.gestureFingers, msg.points.length);
    const lag = Date.now() - msg.time;
    if (msg.phase === 'start' && Math.abs(lag) > 1000) this.log.info(`touch time is ${lag} ms off the server clock`);
    if ((msg.phase === 'end' || msg.phase === 'cancel') && this.gestureFingers > 1) {
      this.log.info(`${this.gestureFingers}-finger gesture`);
    }
  }

  private async suggest(id: number, q: string): Promise<void> {
    this.suggestAbort?.abort();
    const abort = new AbortController();
    this.suggestAbort = abort;
    const items = await fetchSuggestions(q, this.settings.searchEngine, abort.signal);
    if (abort.signal.aborted) return;
    this.suggestAbort = undefined;
    this.client?.send({ t: 'suggestions', id, q, items });
  }

  private async applySetting(key: string, value: string): Promise<void> {
    const next = applySetting(this.settings, key, value);
    if (!next) {
      this.client?.send({ t: 'error', code: 'badSetting', message: `Invalid setting ${key}` });
      return;
    }
    this.settings = next;
    this.client?.send({ t: 'settings', ...this.settings });
    if (key === 'layout') {
      await this.emulateAll();
      this.tabs.get(this.activeId)?.reload();
    }
  }

  // -------------------------------------------------------------------------
  // Viewport and screencast

  private async setViewport(viewport: Viewport): Promise<void> {
    this.log.info(`viewport ${viewport.w}x${viewport.h}@${viewport.dpr}`);
    if (!validViewport(viewport)) {
      this.client?.send({ t: 'error', code: 'badViewport', message: 'Viewport out of range' });
      return;
    }
    this.viewport = viewport;
    await this.emulateAll();
    this.restartCast();
    // The keyboard opening shrinks the viewport: keep the field being typed into visible.
    const active = this.tabs.get(this.activeId);
    if (active?.ime.active) await active.ime.reveal();
  }

  private async emulateAll(): Promise<void> {
    await Promise.all([...this.tabs.values()].map((tab) => this.emulate(tab)));
  }

  private async emulate(tab: Tab): Promise<void> {
    if (!this.viewport) return;
    const ua = userAgentFor(this.settings.layout, this.browserVersion, config.acceptLanguage);
    await tab.applyEmulation(this.viewport, this.settings.layout, ua).catch((err) => this.log.warn('emulation failed', err));
  }

  private restartCast(): void {
    this.castChain = this.castChain
      .then(async () => {
        await this.screencast?.stop();
        this.screencast = undefined;
        const tab = this.tabs.get(this.activeId);
        if (!this.client || !this.viewport || !tab || this.stopping) return;
        const cast = new Screencast(tab.cdp, tab.id, this.client, this.viewport);
        this.screencast = cast;
        await cast.start();
      })
      .catch((err) => this.log.warn('screencast restart failed', err));
  }

  // -------------------------------------------------------------------------
  // Tabs

  private adopt(page: Page): Promise<Tab | undefined> {
    let pending = this.adopting.get(page);
    if (!pending) {
      pending = (async () => {
        const id = this.nextTabId++;
        const tab = await Tab.create(
          id,
          page,
          (t) => this.onTabChange(t),
          (t, change) => this.onStorageChange(t, change),
          (t, msg) => {
            // Keyboard focus only matters for the tab on screen.
            if (t.id === this.activeId && this.launched) this.client?.send(msg);
          },
          (t, msg) => this.onDialog(t, msg),
        );
        this.tabs.set(id, tab);
        await this.emulate(tab);
        return tab;
      })().catch((err) => {
        this.log.warn('could not attach to page', err);
        return undefined;
      });
      this.adopting.set(page, pending);
    }
    return pending;
  }

  private async onTargetCreated(target: Target): Promise<void> {
    if (target.type() !== 'page') return;
    const page = await target.page().catch(() => null);
    if (!page) return;
    const known = this.adopting.has(page);
    const tab = await this.adopt(page);
    // A page the site opened itself (target=_blank, window.open) becomes the active tab.
    if (tab && !known && target.opener()) {
      this.log.info(`page opened a new tab ${tab.id}`);
      await this.activate(tab.id);
    }
    else this.sendTabs();
  }

  private onTargetDestroyed(target: Target): void {
    for (const tab of this.tabs.values()) {
      if (tab.page.target() !== target) continue;
      tab.markClosed();
      this.tabs.delete(tab.id);
      if (this.stopping) return;
      if (this.tabs.size === 0) void this.newTab();
      else if (tab.id === this.activeId) void this.activate([...this.tabs.keys()].at(-1)!);
      else this.sendTabs();
      return;
    }
  }

  private async newTab(url?: string): Promise<Tab | undefined> {
    if (!this.browser) return undefined;
    const tab = await this.adopt(await this.browser.newPage());
    if (!tab) return undefined;
    await this.activate(tab.id);
    const target = url ? resolveInput(url, this.settings.searchEngine) : null;
    if (target) tab.navigate(target);
    return tab;
  }

  private async activate(id: number): Promise<void> {
    const tab = this.tabs.get(id);
    if (!tab) return;
    if (id !== this.activeId) this.client?.send({ t: 'blur' });
    this.activeId = id;
    await tab.page.bringToFront().catch(() => {});
    this.restartCast();
    this.sendTabs();
    this.resendDialog();
  }

  /**
   * A page dialog blocks its tab until answered. The device answers it for the
   * tab on screen; in a background tab (or with no device) alerts and "leave
   * page?" are accepted and confirm/prompt are cancelled, so no tab stays stuck.
   */
  private onDialog(tab: Tab, msg: DialogMessage): void {
    if (tab.id === this.activeId && this.client && this.launched) {
      this.client.send(msg);
      return;
    }
    tab.answerDialog(msg.dialogId, msg.kind === 'alert' || msg.kind === 'beforeunload');
  }

  /** The device (re)connected or switched tabs: show the active tab's open dialog. */
  private resendDialog(): void {
    const dialog = this.tabs.get(this.activeId)?.pendingDialog;
    if (dialog && this.launched) this.client?.send(dialog);
  }

  private onTabChange(tab: Tab): void {
    if (this.launched) this.client?.send({ t: 'tabUpdate', ...tab.info });
  }

  private onStorageChange(tab: Tab, change: StorageChange): void {
    this.sync({ t: 'storage', ...change, ...(change.area === 'session' ? { tabId: tab.id } : {}) });
  }

  /** Sends a state change to the device, or keeps it until the device reconnects. */
  private sync(msg: ServerMessage): void {
    if (this.client && this.launched) {
      this.client.send(msg);
    } else if (this.syncQueue.length < MAX_QUEUED_SYNC) {
      this.syncQueue.push(msg);
    } else {
      this.syncOverflow = true;
    }
  }

  private sendTabs(): void {
    if (!this.launched) return;
    this.client?.send({ t: 'tabs', tabs: [...this.tabs.values()].map((t) => ({ ...t.info })), activeId: this.activeId });
  }
}

/** One Session per device, keyed by a hash of the device ID. */
export class SessionManager {
  private readonly sessions = new Map<string, Session>();

  get(key: string): Session | undefined {
    return this.sessions.get(key);
  }

  create(key: string, state: RestoreState, viewport: Viewport | undefined): Session {
    const session = new Session(key, state, viewport, (s) => {
      if (this.sessions.get(s.key) === s) this.sessions.delete(s.key);
    });
    this.sessions.set(key, session);
    return session;
  }

  async stopAll(): Promise<void> {
    await Promise.all([...this.sessions.values()].map((s) => s.stop()));
  }
}
