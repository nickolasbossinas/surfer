// Injected into every frame, in an isolated world (the page's own scripts cannot
// see or call it). Reports focus and contents of text fields to the server and
// offers the editing helpers the server's IME bridge needs. See DESIGN.md §3.4.
(() => {
  if (globalThis.__surferIme) return;

  const emit = (msg) => {
    try {
      __surferEmit(JSON.stringify(msg));
    } catch {
      // Binding not ready (very early in the document); the next event retries.
    }
  };

  const TEXT_INPUTS = new Set(['text', 'search', 'email', 'url', 'tel', 'password', 'number']);
  const BLOCK = /^(P|DIV|LI|H[1-6]|BLOCKQUOTE|PRE|TD|TH|DD|DT|SECTION|ARTICLE|HEADER|FOOTER|FIGCAPTION)$/;

  let field = null;
  let fieldId = 0;
  let reportQueued = false;

  /** The focused element, looking inside shadow roots. */
  function deepActive() {
    let a = document.activeElement;
    while (a && a.shadowRoot && a.shadowRoot.activeElement) a = a.shadowRoot.activeElement;
    return a;
  }

  /** The editable element for `el`: the input/textarea itself or the contenteditable host. */
  function editableOf(el) {
    if (!el) return null;
    if (el.tagName === 'TEXTAREA') return el.readOnly || el.disabled ? null : el;
    if (el.tagName === 'INPUT') return TEXT_INPUTS.has(el.type) && !el.readOnly && !el.disabled ? el : null;
    if (el.isContentEditable) {
      let host = el;
      while (host.parentElement && host.parentElement.isContentEditable) host = host.parentElement;
      return host;
    }
    return null;
  }

  const isInput = (el) => el.tagName === 'INPUT' || el.tagName === 'TEXTAREA';

  /** In a contenteditable, the IME sees the text of the block around the caret. */
  function blockOf(node, host) {
    let n = node && node.nodeType === Node.ELEMENT_NODE ? node : node && node.parentElement;
    while (n && n !== host && !BLOCK.test(n.tagName)) n = n.parentElement;
    return n && host.contains(n) ? n : host;
  }

  function textBefore(block, node, offset) {
    const r = document.createRange();
    r.selectNodeContents(block);
    try {
      r.setEnd(node, offset);
    } catch {
      return 0;
    }
    return r.toString().length;
  }

  function blockText(block) {
    const r = document.createRange();
    r.selectNodeContents(block);
    return r.toString();
  }

  /** {text, selStart, selEnd} of the focused field, offsets in UTF-16 units. */
  function state() {
    if (!field) return null;
    if (isInput(field)) {
      const len = field.value.length;
      let s = len;
      let e = len;
      try {
        // number/email inputs throw on selectionStart.
        if (field.selectionStart !== null) s = field.selectionStart;
        if (field.selectionEnd !== null) e = field.selectionEnd;
      } catch {}
      return { text: field.value, selStart: s, selEnd: e };
    }
    const sel = getSelection();
    if (!sel || !sel.rangeCount || !field.contains(sel.anchorNode)) return { text: '', selStart: 0, selEnd: 0 };
    const block = blockOf(sel.anchorNode, field);
    const text = blockText(block);
    const a = textBefore(block, sel.anchorNode, sel.anchorOffset);
    const f = block.contains(sel.focusNode) ? textBefore(block, sel.focusNode, sel.focusOffset) : a;
    return { text, selStart: Math.min(a, f), selEnd: Math.max(a, f) };
  }

  function inputType(el) {
    if (el.tagName === 'TEXTAREA') return 'textarea';
    if (el.tagName === 'INPUT') return el.type;
    return 'contenteditable';
  }

  function describe() {
    const r = field.getBoundingClientRect();
    return {
      fieldId,
      inputType: inputType(field),
      inputMode: field.inputMode || '',
      multiline: !isInput(field) || field.tagName === 'TEXTAREA',
      enterKeyHint: field.enterKeyHint || '',
      autocapitalize: field.autocapitalize || '',
      autocorrect: field.getAttribute('autocorrect') !== 'off' && field.spellcheck !== false,
      rect: { x: r.x, y: r.y, w: r.width, h: r.height },
      ...state(),
    };
  }

  function queueReport() {
    if (reportQueued || !field) return;
    reportQueued = true;
    setTimeout(() => {
      reportQueued = false;
      if (field) emit({ type: 'edit', fieldId, ...state() });
    }, 0);
  }

  function checkFocus() {
    const f = editableOf(deepActive());
    if (f === field) return;
    field = f;
    if (f) {
      fieldId++;
      emit({ type: 'focus', ...describe() });
    } else {
      emit({ type: 'blur' });
    }
  }

  document.addEventListener('focusin', checkFocus, true);
  document.addEventListener('focusout', () => setTimeout(checkFocus, 0), true);
  document.addEventListener('input', queueReport, true);
  document.addEventListener('selectionchange', queueReport, true);
  // A field can be focused before this script runs (autofocus).
  if (document.readyState !== 'loading') checkFocus();
  else document.addEventListener('DOMContentLoaded', checkFocus);

  // --- Helpers called by the server -----------------------------------------

  /** Places a DOM range at text offsets [start, end) of the caret's block. */
  function rangeInBlock(block, start, end) {
    const walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
    const range = document.createRange();
    range.setStart(block, 0);
    range.setEnd(block, 0);
    let pos = 0;
    let startSet = false;
    let node;
    while ((node = walker.nextNode())) {
      const len = node.data.length;
      if (!startSet && start <= pos + len) {
        range.setStart(node, start - pos);
        startSet = true;
      }
      if (startSet && end <= pos + len) {
        range.setEnd(node, end - pos);
        return range;
      }
      pos += len;
    }
    range.selectNodeContents(block);
    range.collapse(false);
    return range;
  }

  function select(start, end) {
    if (!field) return false;
    if (isInput(field)) {
      try {
        field.setSelectionRange(start, end);
      } catch {
        return false;
      }
      return true;
    }
    const sel = getSelection();
    if (!sel.rangeCount) return false;
    const block = blockOf(sel.anchorNode, field);
    sel.removeAllRanges();
    sel.addRange(rangeInBlock(block, start, end));
    return true;
  }

  globalThis.__surferIme = {
    state: () => (field ? { fieldId, ...state() } : null),
    select,
    /**
     * Selects `before` characters before and `after` after the caret; returns
     * the selected length, or -1 if the field has no selection API (email and
     * number inputs, where the caret is taken to be at the end).
     */
    selectAround(before, after) {
      const s = state();
      if (!s) return 0;
      const start = Math.max(0, s.selStart - before);
      const end = Math.min(s.text.length, s.selEnd + after);
      return select(start, end) ? end - start : -1;
    },
    /** The text in [start, end) of the field (or caret block). */
    textAt(start, end) {
      const s = state();
      return s ? s.text.slice(start, end) : '';
    },
    /**
     * After the keyboard shrank the viewport: if the field (or, in an editor
     * taller than the screen, the caret's line) is not fully visible, scroll it
     * to the middle of the visible area, as Chrome does.
     */
    reveal() {
      if (!field) return;
      const vv = globalThis.visualViewport;
      const top = vv ? vv.offsetTop : 0;
      const height = vv ? vv.height : innerHeight;
      const margin = Math.min(24, height / 10);
      let target = field;
      let r = field.getBoundingClientRect();
      if (r.height > height - 2 * margin && !isInput(field)) {
        const sel = getSelection();
        if (sel && sel.rangeCount && field.contains(sel.focusNode)) {
          const block = blockOf(sel.focusNode, field);
          const br = block.getBoundingClientRect();
          if (block !== field && br.height <= height - 2 * margin) {
            target = block;
            r = br;
          }
        }
      }
      if (r.top >= top + margin && r.bottom <= top + height - margin) return;
      const tall = r.height > height - 2 * margin;
      target.scrollIntoView({ block: tall ? 'start' : 'center', inline: 'nearest' });
    },
  };
})();
