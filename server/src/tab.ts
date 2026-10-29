import type { CDPSession, Page, Protocol } from 'puppeteer-core';
import { Ime } from './ime.js';
import { InputController } from './input.js';
import { logger } from './log.js';
import type { ServerMessage, TabInfo, UserSettings } from './protocol.js';
import { writeStorage, type StorageData } from './restore.js';
import type { Viewport } from './screencast.js';
import { watchStorage, type StorageChange } from './sync.js';
import { needsIpadIdentity, type UserAgentOverride } from './useragent.js';

const log = logger('tab');

/** Only the site goes to the log, not the full address. */
function originOf(url: string): string {
  try {
    return new URL(url).origin;
  } catch {
    return '(invalid url)';
  }
}

export type DialogMessage = Extract<ServerMessage, { t: 'dialog' }>;
let nextDialogId = 1;

/**
 * One browser tab: its page, a dedicated CDP session, emulation and the
 * TabInfo the client sees.
 */
export class Tab {
  readonly info: TabInfo;
  readonly input: InputController;
  readonly ime: Ime;
  private mainFrameId = '';
  private closed = false;
  /** An alert/confirm/prompt/beforeunload the page is waiting on (it is frozen until answered). */
  private dialog: DialogMessage | undefined;
  /** The identity for the layout, and the iPad one for sites that need it (see useragent.ts). */
  private ua: UserAgentOverride | undefined;
  private ipadUa: UserAgentOverride | undefined;
  private usingIpad = false;
  /** writeStorage owns the Fetch domain while it runs. */
  private restoringStorage = false;

  private constructor(
    readonly id: number,
    readonly page: Page,
    readonly cdp: CDPSession,
    private readonly onChange: (tab: Tab) => void,
    private readonly onStorage: (tab: Tab, change: StorageChange) => void,
    onIme: (tab: Tab, msg: ServerMessage) => void,
    private readonly onDialog: (tab: Tab, msg: DialogMessage) => void,
  ) {
    this.info = { id, title: '', url: page.url(), loading: false, progress: 1, canBack: false, canForward: false };
    this.input = new InputController(cdp);
    this.ime = new Ime(cdp, this.input, (msg) => {
      if (!this.closed) onIme(this, msg);
    });
  }

  static async create(
    id: number,
    page: Page,
    onChange: (tab: Tab) => void,
    onStorage: (tab: Tab, change: StorageChange) => void,
    onIme: (tab: Tab, msg: ServerMessage) => void,
    onDialog: (tab: Tab, msg: DialogMessage) => void,
  ): Promise<Tab> {
    const cdp = await page.createCDPSession();
    const tab = new Tab(id, page, cdp, onChange, onStorage, onIme, onDialog);
    await tab.init();
    return tab;
  }

  private async init(): Promise<void> {
    const { cdp } = this;
    await Promise.all([cdp.send('Page.enable'), cdp.send('Runtime.enable')]);
    await this.ime.init();
    const { frameTree } = await cdp.send('Page.getFrameTree');
    this.mainFrameId = frameTree.frame.id;

    cdp.on('Page.frameStartedLoading', (e) => {
      if (e.frameId !== this.mainFrameId) return;
      this.update({ loading: true, progress: 0.1 });
    });
    cdp.on('Page.frameNavigated', (e: Protocol.Page.FrameNavigatedEvent) => {
      if (e.frame.parentId) return;
      this.mainFrameId = e.frame.id;
      log.info(`tab ${this.id}: opened ${originOf(e.frame.url)}`);
      this.update({ url: e.frame.url + (e.frame.urlFragment ?? ''), progress: Math.max(this.info.progress, 0.3) });
      void this.refreshHistory();
    });
    cdp.on('Page.navigatedWithinDocument', (e) => {
      if (e.frameId !== this.mainFrameId) return;
      this.update({ url: e.url });
      void this.refreshHistory();
      void this.refreshTitle();
    });
    cdp.on('Page.domContentEventFired', () => {
      if (this.info.loading) this.update({ progress: 0.7 });
      void this.refreshTitle();
    });
    cdp.on('Page.frameStoppedLoading', (e) => {
      if (e.frameId !== this.mainFrameId) return;
      this.update({ loading: false, progress: 1 });
      void this.refreshTitle();
      void this.refreshHistory();
    });
    cdp.on('Page.javascriptDialogOpening', (e: Protocol.Page.JavascriptDialogOpeningEvent) => {
      this.dialog = {
        t: 'dialog',
        dialogId: nextDialogId++,
        kind: e.type,
        message: e.message.slice(0, 10_000),
        ...(e.type === 'prompt' ? { defaultText: e.defaultPrompt ?? '' } : {}),
      };
      log.info(`tab ${this.id}: page opened a ${e.type} dialog`);
      if (!this.closed) this.onDialog(this, this.dialog);
    });
    cdp.on('Page.javascriptDialogClosed', () => {
      this.dialog = undefined;
    });
    cdp.on('Fetch.requestPaused', (e: Protocol.Fetch.RequestPausedEvent) => {
      if (!this.restoringStorage) void this.onDocumentRequest(e);
    });
    await this.watchDocuments();
    await watchStorage(cdp, (change) => {
      if (!this.closed) this.onStorage(this, change);
    });
    await this.refreshHistory();
    await this.refreshTitle();
  }

  /**
   * Opens `url` in a tab restored from the device: first writes the tab's saved
   * sessionStorage, then loads the page and drops the helper history entries.
   */
  async restore(url: string, session: StorageData | undefined): Promise<void> {
    if (session) {
      this.restoringStorage = true;
      try {
        await writeStorage(this.cdp, 'session', session);
      } finally {
        this.restoringStorage = false;
        await this.watchDocuments();
      }
    }
    try {
      await this.setIdentity(this.wantsIpad(url), url);
      const committed = this.mainFrameNavigated(5000);
      await this.cdp.send('Page.navigate', { url });
      // The helper pages of the storage restore must not stay in the history; the
      // history can only be reset once the new page is the current one.
      if (session) {
        await committed;
        await this.cdp.send('Page.resetNavigationHistory');
      }
    } catch (err) {
      log.warn(`restoring tab ${url} failed`, err);
    }
  }

  /** Pauses every page load briefly, to choose the identity for its site. */
  private async watchDocuments(): Promise<void> {
    await this.cdp.send('Fetch.enable', { patterns: [{ resourceType: 'Document', requestStage: 'Request' }] }).catch((err) => log.warn('document watch failed', err));
  }

  /** The identity a page load of `url` needs, in the main frame. */
  private wantsIpad(url: string): boolean {
    return !!this.ipadUa && needsIpadIdentity(url);
  }

  /** Switches the tab's identity; only call while no page load is paused. */
  private async setIdentity(ipad: boolean, url: string): Promise<void> {
    if (ipad === this.usingIpad || !this.ua) return;
    this.usingIpad = ipad;
    log.info(`tab ${this.id}: ${ipad ? 'iPad' : 'normal'} identity for ${new URL(url).origin}`);
    await this.cdp.send('Emulation.setUserAgentOverride', ipad ? this.ipadUa! : this.ua).catch(() => {});
  }

  /**
   * Every main-frame page load passes here. Loads Surfer starts already have the
   * right identity (navigate, restore). A load the page starts (a link) that
   * needs the other identity is cancelled and started again after the switch:
   * the identity cannot change while a load is paused, and the request and the
   * new page's scripts must both see the new one.
   */
  private async onDocumentRequest(e: Protocol.Fetch.RequestPausedEvent): Promise<void> {
    const proceed = () => this.cdp.send('Fetch.continueRequest', { requestId: e.requestId }).catch(() => {});
    if (e.frameId !== this.mainFrameId || !this.ua) {
      await proceed();
      return;
    }
    const ipad = this.wantsIpad(e.request.url);
    if (ipad === this.usingIpad || e.request.method !== 'GET') {
      await proceed();
      return;
    }
    await this.cdp.send('Fetch.failRequest', { requestId: e.requestId, errorReason: 'Aborted' }).catch(() => {});
    await this.setIdentity(ipad, e.request.url);
    const referrer = e.request.headers['Referer'] ?? e.request.headers['referer'];
    await this.cdp
      .send('Page.navigate', { url: e.request.url, ...(referrer ? { referrer } : {}) })
      .catch((err) => log.warn('reloading with the other identity failed', err));
  }

  /** Resolves when the main frame commits a new page (or after `timeoutMs`). */
  private mainFrameNavigated(timeoutMs: number): Promise<void> {
    return new Promise((resolve) => {
      const done = () => {
        clearTimeout(timer);
        this.cdp.off('Page.frameNavigated', onNav);
        resolve();
      };
      const onNav = (e: Protocol.Page.FrameNavigatedEvent) => {
        if (!e.frame.parentId) done();
      };
      const timer = setTimeout(done, timeoutMs);
      this.cdp.on('Page.frameNavigated', onNav);
    });
  }

  async applyEmulation(
    viewport: Viewport,
    layout: UserSettings['layout'],
    ua: UserAgentOverride,
    ipadUa: UserAgentOverride,
  ): Promise<void> {
    this.ua = ua;
    // The desktop layout works on those sites as it is.
    this.ipadUa = layout === 'desktop' ? undefined : ipadUa;
    if (!this.ipadUa) this.usingIpad = false;
    const mobile = layout !== 'desktop';
    const w = Math.round(viewport.w);
    const h = Math.round(viewport.h);
    await Promise.all([
      this.cdp.send('Emulation.setDeviceMetricsOverride', {
        width: w,
        height: h,
        deviceScaleFactor: viewport.dpr,
        mobile,
        screenWidth: w,
        screenHeight: h,
      }),
      this.cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 }),
      this.cdp.send('Emulation.setUserAgentOverride', this.usingIpad && this.ipadUa ? this.ipadUa : ua),
    ]);
  }

  get pendingDialog(): DialogMessage | undefined {
    return this.dialog;
  }

  /** Answers the page's dialog; returns false if `dialogId` is not this tab's open dialog. */
  answerDialog(dialogId: number, accept: boolean, text?: string): boolean {
    if (!this.dialog || this.dialog.dialogId !== dialogId) return false;
    this.dialog = undefined;
    this.cdp
      .send('Page.handleJavaScriptDialog', { accept, ...(text !== undefined ? { promptText: text } : {}) })
      .catch((err) => log.debug('answering dialog failed', err));
    return true;
  }

  navigate(url: string): void {
    void (async () => {
      await this.setIdentity(this.wantsIpad(url), url);
      await this.cdp.send('Page.navigate', { url });
    })().catch((err) => log.warn(`navigate ${url} failed`, err));
  }

  async back(): Promise<void> {
    await this.goHistory(-1);
  }

  async forward(): Promise<void> {
    await this.goHistory(1);
  }

  reload(): void {
    this.cdp.send('Page.reload').catch(() => {});
  }

  stop(): void {
    this.cdp.send('Page.stopLoading').catch(() => {});
  }

  async close(): Promise<void> {
    if (this.closed) return;
    this.closed = true;
    this.input.dispose();
    await this.page.close({ runBeforeUnload: false }).catch(() => {});
  }

  /** Called when the page closed itself (window.close) or crashed away. */
  markClosed(): void {
    this.closed = true;
    this.input.dispose();
  }

  private async goHistory(delta: number): Promise<void> {
    try {
      const { currentIndex, entries } = await this.cdp.send('Page.getNavigationHistory');
      const entry = entries[currentIndex + delta];
      if (entry) await this.cdp.send('Page.navigateToHistoryEntry', { entryId: entry.id });
    } catch (err) {
      log.debug('history navigation failed', err);
    }
  }

  private async refreshHistory(): Promise<void> {
    try {
      const { currentIndex, entries } = await this.cdp.send('Page.getNavigationHistory');
      this.update({ canBack: currentIndex > 0, canForward: currentIndex < entries.length - 1 });
    } catch {
      // Page is navigating or closed; the next event refreshes again.
    }
  }

  private async refreshTitle(): Promise<void> {
    try {
      const { result } = await this.cdp.send('Runtime.evaluate', { expression: 'document.title', returnByValue: true });
      if (typeof result.value === 'string') this.update({ title: result.value });
    } catch {
      // Execution context changed mid-navigation; a later event refreshes again.
    }
  }

  private update(patch: Partial<TabInfo>): void {
    if (this.closed) return;
    let changed = false;
    for (const [key, value] of Object.entries(patch) as [keyof TabInfo, never][]) {
      if (this.info[key] !== value) {
        this.info[key] = value;
        changed = true;
      }
    }
    if (changed) this.onChange(this);
  }
}
