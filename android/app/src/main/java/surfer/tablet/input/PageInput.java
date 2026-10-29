package surfer.tablet.input;

import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.SpannableStringBuilder;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Connects the Android keyboard to the text field focused in the remote page
 * (DESIGN.md §4, Keyboard). A local Editable mirrors the field so the keyboard
 * can read the text around the cursor (autocorrect, swipe typing); every edit
 * the keyboard makes is applied to the mirror and sent to the server with a
 * sequence number. The server's field states replace the mirror only once they
 * include all edits sent, so fast typing is never undone by a stale state.
 */
public final class PageInput {
    /** Sends a protocol message. */
    public interface Sender {
        void send(JSONObject msg);
    }

    private final View view;
    private final InputMethodManager imm;
    private final Sender sender;
    private final Editable mirror = new SpannableStringBuilder();

    private boolean active;
    private long fieldId;
    private int seq;
    private JSONObject field = new JSONObject();

    public PageInput(View view, InputMethodManager imm, Sender sender) {
        this.view = view;
        this.imm = imm;
        this.sender = sender;
    }

    /** A text field has focus in the page. */
    public boolean isActive() {
        return active;
    }

    // -------------------------------------------------------------------------
    // From the server

    public void onFocus(JSONObject msg) {
        active = true;
        field = msg;
        fieldId = msg.optLong("fieldId");
        setMirror(msg.optString("text"), msg.optInt("selStart"), msg.optInt("selEnd"));
        view.requestFocus();
        imm.restartInput(view);
        imm.showSoftInput(view, 0);
    }

    public void onEditState(JSONObject msg) {
        if (!active || msg.optLong("fieldId") != fieldId) return;
        // Ignore states that do not yet contain every edit we sent.
        if (msg.optInt("seq") < seq) return;
        String text = msg.optString("text");
        int selStart = msg.optInt("selStart");
        int selEnd = msg.optInt("selEnd");
        boolean textChanged = !text.equals(mirror.toString());
        if (!textChanged && selStart == Selection.getSelectionStart(mirror) && selEnd == Selection.getSelectionEnd(mirror)) return;
        setMirror(text, selStart, selEnd);
        if (textChanged) {
            // The page changed the text itself (autocomplete, formatting): the keyboard must re-read it.
            imm.restartInput(view);
        } else {
            imm.updateSelection(view, selStart, selEnd, -1, -1);
        }
    }

    public void onBlur() {
        if (!active) return;
        active = false;
        mirror.clear();
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
        imm.restartInput(view);
    }

    /** The user tapped the page while a field is focused: bring the keyboard back if it was closed. */
    public void showKeyboardAgain() {
        if (active) imm.showSoftInput(view, 0);
    }

    // -------------------------------------------------------------------------
    // For the view

    public InputConnection createConnection(EditorInfo out) {
        if (!active) return null;
        out.inputType = inputType();
        out.imeOptions = imeOptions();
        out.initialSelStart = Selection.getSelectionStart(mirror);
        out.initialSelEnd = Selection.getSelectionEnd(mirror);
        return new Connection();
    }

    /** Hardware keys (and key events some keyboards send) while a field is focused. */
    public boolean onKey(KeyEvent event) {
        if (!active) return false;
        return sendKey(event);
    }

    private int inputType() {
        String type = field.optString("inputType");
        String mode = field.optString("inputMode");
        int t;
        if ("password".equals(type)) {
            t = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
        } else if ("email".equals(type) || "email".equals(mode)) {
            t = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
        } else if ("url".equals(type) || "url".equals(mode)) {
            t = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;
        } else if ("tel".equals(type) || "tel".equals(mode)) {
            t = InputType.TYPE_CLASS_PHONE;
        } else if ("number".equals(type) || "numeric".equals(mode) || "decimal".equals(mode)) {
            t = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
        } else {
            t = InputType.TYPE_CLASS_TEXT;
            if (field.optBoolean("multiline")) t |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
            if (field.optBoolean("autocorrect", true)) t |= InputType.TYPE_TEXT_FLAG_AUTO_CORRECT;
            String caps = field.optString("autocapitalize");
            if ("characters".equals(caps)) t |= InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS;
            else if ("words".equals(caps)) t |= InputType.TYPE_TEXT_FLAG_CAP_WORDS;
            else if ("sentences".equals(caps) || (caps.isEmpty() && field.optBoolean("multiline"))) t |= InputType.TYPE_TEXT_FLAG_CAP_SENTENCES;
        }
        return t;
    }

    private int imeOptions() {
        int flags = EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN;
        String hint = field.optString("enterKeyHint");
        if ("search".equals(hint) || ("".equals(hint) && "search".equals(field.optString("inputType")))) return flags | EditorInfo.IME_ACTION_SEARCH;
        if ("send".equals(hint)) return flags | EditorInfo.IME_ACTION_SEND;
        if ("next".equals(hint)) return flags | EditorInfo.IME_ACTION_NEXT;
        if ("previous".equals(hint)) return flags | EditorInfo.IME_ACTION_PREVIOUS;
        if ("done".equals(hint)) return flags | EditorInfo.IME_ACTION_DONE;
        if ("go".equals(hint)) return flags | EditorInfo.IME_ACTION_GO;
        // Multi-line fields need a real Enter key; single-line fields submit.
        if (field.optBoolean("multiline") || "enter".equals(hint)) return flags | EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        return flags | EditorInfo.IME_ACTION_GO;
    }

    // -------------------------------------------------------------------------

    private void setMirror(String text, int selStart, int selEnd) {
        BaseInputConnection.removeComposingSpans(mirror);
        mirror.replace(0, mirror.length(), text);
        int len = mirror.length();
        Selection.setSelection(mirror, clamp(selStart, len), clamp(selEnd, len));
    }

    private static int clamp(int v, int len) {
        return Math.max(0, Math.min(len, v));
    }

    private void send(Object... keyValues) {
        try {
            JSONObject msg = new JSONObject();
            for (int i = 0; i + 1 < keyValues.length; i += 2) msg.put((String) keyValues[i], keyValues[i + 1]);
            msg.put("seq", ++seq);
            sender.send(msg);
        } catch (JSONException ignored) {
        }
    }

    /** Tells the keyboard where the cursor and composition are after a local edit. */
    private void reportSelection() {
        imm.updateSelection(view, Selection.getSelectionStart(mirror), Selection.getSelectionEnd(mirror),
                BaseInputConnection.getComposingSpanStart(mirror), BaseInputConnection.getComposingSpanEnd(mirror));
    }

    private boolean sendKey(KeyEvent event) {
        String key;
        int code;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DEL: key = "Backspace"; code = 8; break;
            case KeyEvent.KEYCODE_FORWARD_DEL: key = "Delete"; code = 46; break;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER: key = "Enter"; code = 13; break;
            case KeyEvent.KEYCODE_TAB: key = "Tab"; code = 9; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: key = "ArrowLeft"; code = 37; break;
            case KeyEvent.KEYCODE_DPAD_UP: key = "ArrowUp"; code = 38; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: key = "ArrowRight"; code = 39; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: key = "ArrowDown"; code = 40; break;
            case KeyEvent.KEYCODE_MOVE_HOME: key = "Home"; code = 36; break;
            case KeyEvent.KEYCODE_MOVE_END: key = "End"; code = 35; break;
            case KeyEvent.KEYCODE_ESCAPE: key = "Escape"; code = 27; break;
            default:
                // A printable key (hardware keyboard, or keyboards that send key events): type it.
                int ch = event.getUnicodeChar();
                if (event.getAction() == KeyEvent.ACTION_DOWN && ch != 0 && !Character.isISOControl(ch)) {
                    String text = new String(Character.toChars(ch));
                    commitLocally(text);
                    send("t", "commit", "text", text, "cursor", 1);
                    return true;
                }
                return false;
        }
        int mods = (event.isAltPressed() ? 1 : 0) | (event.isCtrlPressed() ? 2 : 0) | (event.isMetaPressed() ? 4 : 0)
                | (event.isShiftPressed() ? 8 : 0);
        String phase = event.getAction() == KeyEvent.ACTION_UP ? "up" : "down";
        send("t", "key", "phase", phase, "key", key, "code", key, "keyCode", code, "mods", mods);
        return true;
    }

    private void commitLocally(String text) {
        int start = Selection.getSelectionStart(mirror);
        int end = Selection.getSelectionEnd(mirror);
        if (start < 0) start = end = mirror.length();
        mirror.replace(Math.min(start, end), Math.max(start, end), text);
    }

    /** The keyboard's view of the field; every change goes to the server too. */
    private final class Connection extends BaseInputConnection {
        Connection() {
            super(view, true);
        }

        @Override
        public Editable getEditable() {
            return mirror;
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            String s = text.toString();
            if ("\n".equals(s)) {
                // Some keyboards type Enter as a newline character; the page needs the key.
                pressEnter();
                return true;
            }
            super.commitText(text, newCursorPosition);
            send("t", "commit", "text", s, "cursor", newCursorPosition);
            reportSelection();
            return true;
        }

        @Override
        public boolean setComposingText(CharSequence text, int newCursorPosition) {
            super.setComposingText(text, newCursorPosition);
            int len = text.length();
            send("t", "compose", "text", text.toString(), "selStart", len, "selEnd", len);
            reportSelection();
            return true;
        }

        @Override
        public boolean finishComposingText() {
            super.finishComposingText();
            send("t", "finishCompose");
            reportSelection();
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            super.deleteSurroundingText(beforeLength, afterLength);
            send("t", "deleteSurrounding", "before", beforeLength, "after", afterLength);
            reportSelection();
            return true;
        }

        @Override
        public boolean setSelection(int start, int end) {
            super.setSelection(start, end);
            send("t", "setSelection", "start", start, "end", end);
            reportSelection();
            return true;
        }

        @Override
        public boolean setComposingRegion(int start, int end) {
            super.setComposingRegion(start, end);
            send("t", "setComposingRegion", "start", start, "end", end);
            reportSelection();
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            return sendKey(event);
        }

        @Override
        public boolean performEditorAction(int actionCode) {
            pressEnter();
            return true;
        }

        private void pressEnter() {
            send("t", "key", "phase", "down", "key", "Enter", "code", "Enter", "keyCode", 13, "mods", 0);
            send("t", "key", "phase", "up", "key", "Enter", "code", "Enter", "keyCode", 13, "mods", 0);
        }
    }
}
