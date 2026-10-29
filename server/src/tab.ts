import type { CDPSession, Page, Protocol } from 'puppeteer-core';
import { Ime } from './ime.js';
import { InputController } from './input.js';
import { logger } from './log.js';
import type { ServerMessage, TabInfo, UserSettings } from './protocol.js';
import { writeStorage, type StorageData } from './restore.js';
import type { Viewport } from './screencast.js';
import { watchStorage, type StorageChange } from './sync.js';
import type { UserAgentOverride } from './useragent.js';

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
    if (session) await writeStorage(this.cdp, 'session', session);
    try {
      await this.cdp.send('Page.navigate', { url });
      if (session) await this.cdp.send('Page.resetNavigationHistory');
    } catch (err) {
      log.warn(`restoring tab ${url} failed`, err);
    }
  }

  async applyEmulation(viewport: Viewport, layout: UserSettings['layout'], ua: UserAgentOverride): Promise<void> {
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
      this.cdp.send('Emulation.setUserAgentOverride', ua),
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
    this.cdp.send('Page.navigate', { url }).catch((err) => log.warn(`navigate ${url} failed`, err));
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
