// Text input: connects the device's keyboard (Android InputConnection) to the
// focused field of a page. See DESIGN.md §3.5.

import { readFileSync } from 'node:fs';
import type { CDPSession, Protocol } from 'puppeteer-core';
import type { InputController } from './input.js';
import { logger } from './log.js';
import type { ClientMessage, ServerMessage } from './protocol.js';

const log = logger('ime');

/** Isolated world of the injected script: invisible to the page's own scripts. */
const WORLD = '__surfer';
const BINDING = '__surferEmit';
const PAGE_SCRIPT = readFileSync(new URL('./inject/page.js', import.meta.url), 'utf8');
/** Coalesces state reports after a burst of edits. */
const STATE_DELAY_MS = 15;
const MAX_KEY_DELETES = 200;

export type ImeMessage = Extract<
  ClientMessage,
  { t: 'commit' | 'compose' | 'finishCompose' | 'deleteSurrounding' | 'setSelection' | 'setComposingRegion' | 'key' | 'paste' }
>;

interface PageState {
  fieldId: number;
  text: string;
  selStart: number;
  selEnd: number;
}

/**
 * Per tab. Edit operations from the device carry a sequence number; every state
 * sent back carries the number of the last operation applied, so the device can
 * ignore states that do not yet include its latest edits.
 */
export class Ime {
  private readonly contexts = new Set<number>();
  /** Execution context (of our world) of the frame whose field has focus. */
  private focusContext: number | undefined;
  private fieldId = 0;
  /** Text of the active composition, '' if none. */
  private composing = '';
  private appliedSeq = 0;
  private chain: Promise<void> = Promise.resolve();
  private stateTimer: NodeJS.Timeout | undefined;

  constructor(
    private readonly cdp: CDPSession,
    private readonly input: InputController,
    private readonly emit: (msg: ServerMessage) => void,
  ) {}

  /** Needs Runtime and Page enabled on the session. */
  async init(): Promise<void> {
    const { cdp } = this;
    cdp.on('Runtime.executionContextCreated', (e: Protocol.Runtime.ExecutionContextCreatedEvent) => {
      if (e.context.name === WORLD) this.contexts.add(e.context.id);
    });
    cdp.on('Runtime.executionContextDestroyed', (e) => {
      this.contexts.delete(e.executionContextId);
      if (e.executionContextId === this.focusContext) this.lostFocus();
    });
    cdp.on('Runtime.executionContextsCleared', () => {
      this.contexts.clear();
      if (this.focusContext !== undefined) this.lostFocus();
    });
    cdp.on('Runtime.bindingCalled', (e: Protocol.Runtime.BindingCalledEvent) => {
      if (e.name === BINDING) this.onPage(e.executionContextId, e.payload);
    });
    await cdp.send('Runtime.addBinding', { name: BINDING, executionContextName: WORLD });
    await cdp.send('Page.addScriptToEvaluateOnNewDocument', { source: PAGE_SCRIPT, worldName: WORLD, runImmediately: true });
  }

  /** Whether a text field has focus. */
  get active(): boolean {
    return this.focusContext !== undefined;
  }

  /** Applies one edit operation; operations run strictly in order. */
  handle(msg: ImeMessage): Promise<void> {
    this.chain = this.chain
      .then(() => this.apply(msg))
      .catch((err) => log.debug(`${msg.t} failed`, err))
      .then(() => {
        if (msg.seq !== undefined && msg.seq > this.appliedSeq) this.appliedSeq = msg.seq;
        this.scheduleState();
      });
    return this.chain;
  }

  /** Scrolls the focused field into view (after the keyboard resized the viewport). */
  async reveal(): Promise<void> {
    await this.evaluate('__surferIme.reveal()');
  }

  // ---------------------------------------------------------------------------

  private async apply(msg: ImeMessage): Promise<void> {
    const { cdp } = this;
    switch (msg.t) {
      case 'commit':
      case 'paste':
        await cdp.send('Input.insertText', { text: msg.text });
        this.composing = '';
        return;
      case 'compose':
        await cdp.send('Input.imeSetComposition', { text: msg.text, selectionStart: msg.selStart, selectionEnd: msg.selEnd });
        this.composing = msg.text;
        return;
      case 'finishCompose':
        await this.finishComposition();
        return;
      case 'deleteSurrounding': {
        await this.finishComposition();
        const selected = (await this.evaluate(`__surferIme.selectAround(${int(msg.before)}, ${int(msg.after)})`)) as number;
        // A real Backspace, so the page gets its beforeinput/input events.
        if (selected > 0) {
          this.press('Backspace', 8);
        } else if (selected === -1) {
          // No selection API in this field: delete one character at a time.
          for (let i = 0; i < Math.min(int(msg.before), MAX_KEY_DELETES); i++) this.press('Backspace', 8);
          for (let i = 0; i < Math.min(int(msg.after), MAX_KEY_DELETES); i++) this.press('Delete', 46);
        }
        return;
      }
      case 'setSelection':
        await this.finishComposition();
        await this.evaluate(`__surferIme.select(${int(msg.start)}, ${int(msg.end)})`);
        return;
      case 'setComposingRegion': {
        await this.finishComposition();
        const start = int(Math.min(msg.start, msg.end));
        const end = int(Math.max(msg.start, msg.end));
        const text = (await this.evaluate(`__surferIme.textAt(${start}, ${end})`)) as string | undefined;
        // Without a selection the composition would insert the text a second time.
        if (!text || (await this.evaluate(`__surferIme.select(${start}, ${end})`)) !== true) return;
        // The composition replaces the selection with the same text, now marked as composing.
        await cdp.send('Input.imeSetComposition', { text, selectionStart: text.length, selectionEnd: text.length });
        this.composing = text;
        return;
      }
      case 'key':
        if (msg.phase === 'down' && this.composing) await this.finishComposition();
        this.input.key(msg);
        return;
    }
  }

  private async finishComposition(): Promise<void> {
    if (!this.composing) return;
    const text = this.composing;
    this.composing = '';
    // CDP has no "finish composing"; inserting the composed text commits it.
    await this.cdp.send('Input.insertText', { text });
  }

  private press(name: string, keyCode: number): void {
    const key = { key: name, code: name, keyCode, mods: 0 };
    this.input.key({ t: 'key', phase: 'down', ...key });
    this.input.key({ t: 'key', phase: 'up', ...key });
  }

  private onPage(contextId: number, payload: string): void {
    let msg: Record<string, unknown>;
    try {
      msg = JSON.parse(payload) as Record<string, unknown>;
    } catch {
      return;
    }
    if (!this.contexts.has(contextId)) return;
    switch (msg.type) {
      case 'focus':
        this.focusContext = contextId;
        this.fieldId++;
        this.composing = '';
        this.emit({
          t: 'focus',
          fieldId: this.fieldId,
          inputType: str(msg.inputType),
          inputMode: str(msg.inputMode),
          multiline: msg.multiline === true,
          enterKeyHint: str(msg.enterKeyHint),
          autocapitalize: str(msg.autocapitalize),
          autocorrect: msg.autocorrect !== false,
          rect: msg.rect as { x: number; y: number; w: number; h: number },
          text: str(msg.text),
          selStart: num(msg.selStart),
          selEnd: num(msg.selEnd),
          compStart: -1,
          compEnd: -1,
          seq: this.appliedSeq,
        });
        return;
      case 'edit':
        if (contextId === this.focusContext) this.sendState({ fieldId: this.fieldId, text: str(msg.text), selStart: num(msg.selStart), selEnd: num(msg.selEnd) });
        return;
      case 'blur':
        if (contextId === this.focusContext) this.lostFocus();
        return;
    }
  }

  private lostFocus(): void {
    this.focusContext = undefined;
    this.composing = '';
    this.emit({ t: 'blur' });
  }

  private scheduleState(): void {
    if (this.focusContext === undefined) return;
    clearTimeout(this.stateTimer);
    this.stateTimer = setTimeout(() => {
      void this.evaluate('__surferIme.state()').then((s) => {
        if (s) this.sendState({ ...(s as PageState), fieldId: this.fieldId });
      });
    }, STATE_DELAY_MS);
  }

  private sendState(s: PageState): void {
    // While composing, the composition ends at the caret (Android's setComposingText puts it there).
    const compEnd = this.composing ? s.selEnd : -1;
    const compStart = this.composing ? Math.max(0, compEnd - this.composing.length) : -1;
    this.emit({ t: 'editState', fieldId: s.fieldId, text: s.text, selStart: s.selStart, selEnd: s.selEnd, compStart, compEnd, seq: this.appliedSeq });
  }

  private async evaluate(expression: string): Promise<unknown> {
    const contextId = this.focusContext;
    if (contextId === undefined) return undefined;
    try {
      const { result, exceptionDetails } = await this.cdp.send('Runtime.evaluate', { expression, contextId, returnByValue: true });
      return exceptionDetails ? undefined : result.value;
    } catch {
      return undefined;
    }
  }
}

function int(n: number): number {
  return Math.max(0, Math.min(1e7, Math.trunc(n)));
}

function str(v: unknown): string {
  return typeof v === 'string' ? v : '';
}

function num(v: unknown): number {
  return typeof v === 'number' && Number.isFinite(v) ? v : 0;
}
