package surfer.tablet;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import surfer.tablet.input.PageInput;
import surfer.tablet.net.Frame;
import surfer.tablet.net.PinnedTls;
import surfer.tablet.net.ServerConnection;
import surfer.tablet.state.StateStore;
import surfer.tablet.view.FrameView;
import surfer.tablet.view.KeyboardWatcher;

public class BrowserActivity extends Activity implements ServerConnection.Listener, FrameView.Listener {
    private static final int PROTOCOL_VERSION = 1;
    private static final int CLOSE_TAKEN_OVER = 4001;
    private static final int CLOSE_FORGOTTEN = 4002;
    private static final long STATS_INTERVAL_MS = 10000;

    /** One tab as the server describes it (TabInfo). */
    static final class Tab {
        long id;
        String title = "";
        String url = "";
        boolean loading;
        double progress;
        boolean canBack;
        boolean canForward;

        void update(JSONObject o) {
            id = o.optLong("id", id);
            title = o.optString("title", title);
            url = o.optString("url", url);
            loading = o.optBoolean("loading", loading);
            progress = o.optDouble("progress", progress);
            canBack = o.optBoolean("canBack", canBack);
            canForward = o.optBoolean("canForward", canForward);
        }
    }

    private final Handler handler = new Handler();
    private Prefs prefs;
    private ServerConnection conn;
    private StateStore state;
    private PageInput pageInput;
    private InputMethodManager imm;
    private KeyboardWatcher keyboard;
    private UrlSuggestions suggestions;
    private FrameView frameView;
    private EditText urlField;
    private View urlClear;
    private ImageButton backButton;
    private ImageButton forwardButton;
    private ImageButton reloadButton;
    private Button tabsButton;
    private ProgressBar progress;
    private TextView status;
    private View setupPanel;
    private EditText serverField;
    private TextView setupError;

    private final List<Tab> tabs = new ArrayList<Tab>();
    private long activeId;
    private String layout = "tablet";
    private TabsAdapter tabsAdapter;

    /** Connected and receiving the session. */
    private boolean ready;
    /** Between onStart and onStop: the app wants a connection. */
    private boolean visible;
    /** The user pressed Connect on the setup screen and is waiting for the result. */
    private boolean connectingFromSetup;
    private long reconnectDelayMs = 1000;
    private AlertDialog takenOverDialog;
    private AlertDialog pageDialog;

    private final Runnable reconnect = new Runnable() {
        @Override
        public void run() {
            connect();
        }
    };

    private final Runnable statsReporter = new Runnable() {
        @Override
        public void run() {
            if (ready && prefs.debug()) {
                log("debug", frameView.takeStats() + ", rtt " + conn.rttMs() + " ms");
            }
            handler.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    // -------------------------------------------------------------------------
    // Lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        installCrashHandler();
        state = new StateStore(this, prefs.raw());
        setContentView(R.layout.activity_browser);

        frameView = (FrameView) findViewById(R.id.frame);
        urlField = (EditText) findViewById(R.id.url);
        urlClear = findViewById(R.id.urlClear);
        backButton = (ImageButton) findViewById(R.id.back);
        forwardButton = (ImageButton) findViewById(R.id.forward);
        reloadButton = (ImageButton) findViewById(R.id.reload);
        tabsButton = (Button) findViewById(R.id.tabs);
        progress = (ProgressBar) findViewById(R.id.progress);
        status = (TextView) findViewById(R.id.status);
        setupPanel = findViewById(R.id.setup);
        serverField = (EditText) findViewById(R.id.server);
        setupError = (TextView) findViewById(R.id.setupError);

        try {
            conn = new ServerConnection(PinnedTls.fromCertificate(getResources().openRawResource(R.raw.server)), this);
        } catch (Exception e) {
            throw new IllegalStateException("bundled server certificate is unusable", e);
        }
        frameView.setListener(this);
        imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        pageInput = new PageInput(frameView, imm, new PageInput.Sender() {
            @Override
            public void send(JSONObject msg) {
                conn.send(msg);
            }
        });
        frameView.setPageInput(pageInput);
        suggestions = new UrlSuggestions(this, (ListView) findViewById(R.id.suggestions), state, new UrlSuggestions.Host() {
            @Override
            public void requestSuggestions(int id, String query) {
                if (ready) conn.send("t", "suggest", "id", id, "q", query);
            }

            @Override
            public void open(String input) {
                if (ready) conn.send("t", "navigate", "tabId", activeId, "input", input);
                leaveUrlField();
            }

            @Override
            public void fill(String text) {
                urlField.setText(text);
                urlField.setSelection(urlField.length());
            }
        });
        keyboard = new KeyboardWatcher(this, new KeyboardWatcher.Listener() {
            @Override
            public void onKeyboardTop(int top) {
                updateKeyboardInsets();
            }
        });
        keyboard.start();
        frameView.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                updateKeyboardInsets();
            }
        });
        wireBar();
        wireSetup();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        handler.postDelayed(statsReporter, STATS_INTERVAL_MS);
        if (prefs.server().isEmpty()) showSetup(null);
        else connect();
    }

    @Override
    protected void onStop() {
        super.onStop();
        visible = false;
        ready = false;
        handler.removeCallbacks(reconnect);
        handler.removeCallbacks(statsReporter);
        conn.disconnect();
        state.flushSoon();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        keyboard.stop();
        frameView.release();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applySystemBars();
    }

    @Override
    public void onBackPressed() {
        if (urlField.hasFocus()) {
            leaveUrlField();
            return;
        }
        Tab tab = activeTab();
        if (ready && tab != null && tab.canBack) {
            conn.send("t", "back", "tabId", activeId);
            return;
        }
        super.onBackPressed();
    }

    /** Status and navigation bars: shown normally, hidden in the "Full screen" option. */
    private void applySystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(!prefs.fullScreen() ? 0
                : View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    // -------------------------------------------------------------------------
    // Connection

    private void connect() {
        handler.removeCallbacks(reconnect);
        if (!visible || prefs.server().isEmpty()) return;
        if (setupPanel.getVisibility() != View.VISIBLE) showStatus(getString(R.string.connecting));
        conn.connect(ServerConnection.urlFor(prefs.server()));
    }

    @Override
    public void onOpen() {
        reconnectDelayMs = 1000;
        try {
            JSONObject device = new JSONObject()
                    .put("model", Build.MANUFACTURER + " " + Build.MODEL)
                    .put("sdk", Build.VERSION.SDK_INT);
            JSONObject caps = new JSONObject()
                    .put("maxTouchPoints", maxTouchPoints())
                    .put("formats", new JSONArray().put("jpeg").put("png").put("webp"));
            JSONObject hello = new JSONObject()
                    .put("t", "hello")
                    .put("proto", PROTOCOL_VERSION)
                    .put("app", "tablet")
                    .put("appVersion", appVersion())
                    .put("deviceId", prefs.deviceId())
                    .put("device", device)
                    .put("caps", caps);
            // The page area, so a restored session lays its pages out for this screen from the start.
            if (frameView.getWidth() > 0) {
                float d = frameView.density();
                hello.put("viewport", new JSONObject()
                        .put("w", Math.round(frameView.getWidth() / d))
                        .put("h", Math.round(frameView.pageHeight() / d))
                        .put("dpr", (double) d));
            }
            conn.send(hello);
        } catch (JSONException ignored) {
        }
    }

    @Override
    public void onMessage(JSONObject msg) {
        String type = msg.optString("t");
        // Cookies, storage, tabs and settings also go to the device's saved state.
        state.onMessage(msg);
        if ("needRestore".equals(type)) {
            state.sendRestore(new StateStore.Sender() {
                @Override
                public void sendBlocking(JSONObject m) {
                    conn.sendBlocking(m);
                }
            });
        } else if ("ready".equals(type)) {
            onReady(msg);
        } else if ("focus".equals(type)) {
            pageInput.onFocus(msg);
            updateKeyboardInsets();
        } else if ("editState".equals(type)) {
            pageInput.onEditState(msg);
        } else if ("blur".equals(type)) {
            pageInput.onBlur();
            updateKeyboardInsets();
        } else if ("suggestions".equals(type)) {
            suggestions.onSuggestions(msg);
        } else if ("forgotten".equals(type)) {
            state.clear();
            tabs.clear();
            renderTabs();
            frameView.clear();
        } else if ("tabs".equals(type)) {
            tabs.clear();
            JSONArray list = msg.optJSONArray("tabs");
            for (int i = 0; list != null && i < list.length(); i++) {
                Tab tab = new Tab();
                tab.update(list.optJSONObject(i));
                tabs.add(tab);
            }
            activeId = msg.optLong("activeId", activeId);
            renderTabs();
        } else if ("tabUpdate".equals(type)) {
            Tab tab = findTab(msg.optLong("id"));
            if (tab == null) {
                tab = new Tab();
                tabs.add(tab);
            }
            tab.update(msg);
            renderTabs();
        } else if ("settings".equals(type)) {
            layout = msg.optString("layout", layout);
        } else if ("notice".equals(type) || "error".equals(type)) {
            String text = msg.optString("notice".equals(type) ? "text" : "message");
            if (!text.isEmpty()) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
        } else if ("dialog".equals(type)) {
            showPageDialog(msg);
        } else if ("sessionTakenOver".equals(type)) {
            showTakenOver();
        }
    }

    private void onReady(JSONObject msg) {
        JSONObject settings = msg.optJSONObject("settings");
        if (settings != null) layout = settings.optString("layout", layout);
        connectingFromSetup = false;
        ready = true;
        setupPanel.setVisibility(View.GONE);
        hideStatus();
        frameView.reportViewport();
        String crash = prefs.takeCrash();
        if (crash != null) log("crash", crash);
    }

    @Override
    public void onFrame(Frame frame) {
        frameView.submit(frame);
    }

    @Override
    public void onClosed(int code, String reason, boolean tlsMismatch) {
        ready = false;
        pageInput.onBlur();
        updateKeyboardInsets();
        if (tlsMismatch) {
            showSetup(getString(R.string.tls_error));
            return;
        }
        if (code == CLOSE_TAKEN_OVER || !visible) return;
        if (code == CLOSE_FORGOTTEN) {
            // Browsing data was cleared: start a fresh session.
            connect();
            return;
        }
        if (setupPanel.getVisibility() == View.VISIBLE) {
            // The user is trying to connect: report instead of retrying forever.
            if (connectingFromSetup) setupError.setText(getString(R.string.server_unreachable, reason));
            connectingFromSetup = false;
            return;
        }
        showStatus(getString(R.string.reconnecting));
        handler.postDelayed(reconnect, reconnectDelayMs);
        reconnectDelayMs = Math.min(reconnectDelayMs * 2, 15000);
    }

    /** The page's alert/confirm/prompt/"leave page?" (the page is frozen until answered). */
    private void showPageDialog(JSONObject msg) {
        if (pageDialog != null && pageDialog.isShowing()) pageDialog.dismiss();
        final long dialogId = msg.optLong("dialogId");
        final String kind = msg.optString("kind");
        final EditText input = "prompt".equals(kind) ? new EditText(this) : null;
        final boolean[] answered = new boolean[1];
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        if ("beforeunload".equals(kind)) {
            b.setTitle(R.string.leave_page_title).setMessage(R.string.leave_page_message);
        } else {
            Tab tab = activeTab();
            String host = tab == null ? "" : android.net.Uri.parse(tab.url).getHost();
            if (host != null && !host.isEmpty()) b.setTitle(getString(R.string.page_says, host));
            b.setMessage(msg.optString("message"));
        }
        if (input != null) {
            input.setText(msg.optString("defaultText"));
            input.setSingleLine(true);
            input.selectAll();
            b.setView(input);
        }
        DialogInterface.OnClickListener reply = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int which) {
                answered[0] = true;
                boolean accept = which == DialogInterface.BUTTON_POSITIVE;
                if (input != null && accept) {
                    conn.send("t", "dialogReply", "dialogId", dialogId, "accept", true, "text", input.getText().toString());
                } else {
                    conn.send("t", "dialogReply", "dialogId", dialogId, "accept", accept);
                }
            }
        };
        b.setPositiveButton("beforeunload".equals(kind) ? R.string.leave : android.R.string.ok, reply);
        if (!"alert".equals(kind)) b.setNegativeButton("beforeunload".equals(kind) ? R.string.stay : android.R.string.cancel, reply);
        pageDialog = b.create();
        pageDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                // Back or a tap outside: an alert is just closed, anything else is cancelled.
                if (!answered[0] && ready) conn.send("t", "dialogReply", "dialogId", dialogId, "accept", "alert".equals(kind));
                applySystemBars();
            }
        });
        pageDialog.show();
    }

    private void showTakenOver() {
        if (takenOverDialog != null && takenOverDialog.isShowing()) return;
        takenOverDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.taken_over_title)
                .setMessage(R.string.taken_over_message)
                .setCancelable(false)
                .setPositiveButton(R.string.use_here, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        connect();
                    }
                })
                .show();
    }

    // -------------------------------------------------------------------------
    // Frame view

    @Override
    public void onViewport(float width, float height, float dpr) {
        if (!ready) return;
        conn.send("t", "viewport", "w", Math.round(width), "h", Math.round(height), "dpr", (double) dpr);
    }

    @Override
    public void onTouch(String phase, long eventTime, JSONArray points) {
        if (!ready) return;
        if ("start".equals(phase) && urlField.hasFocus()) leaveUrlField();
        conn.send("t", "touch", "phase", phase, "time", conn.serverTime(eventTime), "points", points);
        if ("end".equals(phase) && pageInput.isActive()) {
            // Tapping the focused field again reopens a keyboard the user closed. A tap
            // elsewhere blurs the field first, and then nothing is shown.
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    pageInput.showKeyboardAgain();
                }
            }, 200);
        }
    }

    @Override
    public void onFrameDone(Frame frame) {
        conn.send("t", "frameAck", "frameId", frame.frameId);
    }

    // -------------------------------------------------------------------------
    // Top bar

    private void wireBar() {
        backButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                conn.send("t", "back", "tabId", activeId);
            }
        });
        forwardButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                conn.send("t", "forward", "tabId", activeId);
            }
        });
        reloadButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Tab tab = activeTab();
                conn.send("t", tab != null && tab.loading ? "stop" : "reload", "tabId", activeId);
            }
        });
        tabsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showTabs();
            }
        });
        findViewById(R.id.menu).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu(v);
            }
        });
        urlField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId != EditorInfo.IME_ACTION_GO && !enter) return false;
                String input = urlField.getText().toString().trim();
                if (!input.isEmpty()) conn.send("t", "navigate", "tabId", activeId, "input", input);
                leaveUrlField();
                return true;
            }
        });
        urlField.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    suggestions.hide();
                    renderBar();
                }
                updateUrlClear();
            }
        });
        urlField.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                updateUrlClear();
                // Only the user's typing; the bar showing the page's address is not a query.
                if (urlField.hasFocus()) suggestions.onQuery(s.toString());
            }
        });
        urlClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                urlField.setText("");
                urlField.requestFocus();
                imm.showSoftInput(urlField, 0);
            }
        });
    }

    /** The clear (x) button shows while the address is being edited and not empty, like Chrome. */
    private void updateUrlClear() {
        urlClear.setVisibility(urlField.hasFocus() && urlField.length() > 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * Where the keyboard covers the page area: while typing into the page, the
     * page's viewport shrinks to the part above the keyboard (the server then
     * scrolls the field into view, DESIGN.md §4); the URL bar suggestions always
     * end above it. The URL bar's keyboard does not resize the page.
     */
    private void updateKeyboardInsets() {
        int[] location = new int[2];
        frameView.getLocationOnScreen(location);
        int frameBottom = location[1] + frameView.getHeight();
        int covered = Math.max(0, frameBottom - Math.max(location[1], keyboard.keyboardTop()));
        frameView.setBottomInset(pageInput.isActive() ? covered : 0);
        suggestions.setBottomMargin(covered);
    }

    private void leaveUrlField() {
        hideKeyboard(urlField);
        urlField.clearFocus();
        frameView.requestFocus();
        renderBar();
        applySystemBars();
    }

    private void renderTabs() {
        tabsButton.setText(String.valueOf(tabs.size()));
        if (tabsAdapter != null) tabsAdapter.notifyDataSetChanged();
        renderBar();
    }

    private void renderBar() {
        Tab tab = activeTab();
        if (tab == null) return;
        if (!urlField.hasFocus()) urlField.setText("about:blank".equals(tab.url) ? "" : tab.url);
        setEnabled(backButton, tab.canBack);
        setEnabled(forwardButton, tab.canForward);
        reloadButton.setImageResource(tab.loading ? R.drawable.ic_stop : R.drawable.ic_reload);
        reloadButton.setContentDescription(getString(tab.loading ? R.string.stop : R.string.reload));
        if (tab.loading) {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress((int) Math.round(tab.progress * 100));
        } else {
            progress.setVisibility(View.GONE);
        }
    }

    private static void setEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.35f);
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.new_tab);
        menu.getMenu().add(1, 2, 1, R.string.layout_tablet).setCheckable(true).setChecked("tablet".equals(layout));
        menu.getMenu().add(1, 3, 2, R.string.layout_phone).setCheckable(true).setChecked("phone".equals(layout));
        menu.getMenu().add(1, 4, 3, R.string.layout_desktop).setCheckable(true).setChecked("desktop".equals(layout));
        menu.getMenu().setGroupCheckable(1, true, true);
        menu.getMenu().add(2, 8, 4, R.string.full_screen).setCheckable(true).setChecked(prefs.fullScreen());
        menu.getMenu().add(2, 5, 4, R.string.debug_log).setCheckable(true).setChecked(prefs.debug());
        menu.getMenu().add(3, 6, 5, R.string.change_server);
        menu.getMenu().add(3, 7, 6, R.string.clear_data);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case 1: conn.send("t", "tabNew"); return true;
                    case 2: setLayout("tablet"); return true;
                    case 3: setLayout("phone"); return true;
                    case 4: setLayout("desktop"); return true;
                    case 5: prefs.debug(!prefs.debug()); return true;
                    case 6: changeServer(); return true;
                    case 7: confirmClearData(); return true;
                    case 8:
                        prefs.fullScreen(!prefs.fullScreen());
                        applySystemBars();
                        return true;
                    default: return false;
                }
            }
        });
        menu.show();
    }

    private void setLayout(String value) {
        layout = value;
        conn.send("t", "setting", "key", "layout", "value", value);
    }

    private void confirmClearData() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.clear_data)
                .setMessage(R.string.clear_data_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (ready) {
                            // The server wipes its copy, answers "forgotten", and we wipe ours.
                            conn.send("t", "forget");
                        } else {
                            state.clear();
                            tabs.clear();
                            renderTabs();
                            frameView.clear();
                        }
                    }
                })
                .show();
    }

    private void changeServer() {
        conn.disconnect();
        ready = false;
        frameView.clear();
        showSetup(null);
    }

    // -------------------------------------------------------------------------
    // Tabs dialog

    private void showTabs() {
        tabsAdapter = new TabsAdapter();
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.tabs)
                .setAdapter(tabsAdapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        Tab tab = tabsAdapter.getItem(which);
                        conn.send("t", "tabActivate", "tabId", tab.id);
                    }
                })
                .setPositiveButton(R.string.new_tab, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        conn.send("t", "tabNew");
                    }
                })
                .create();
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                tabsAdapter = null;
                applySystemBars();
            }
        });
        dialog.show();
    }

    private final class TabsAdapter extends BaseAdapter {
        @Override public int getCount() { return tabs.size(); }
        @Override public Tab getItem(int position) { return tabs.get(position); }
        @Override public long getItemId(int position) { return tabs.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(BrowserActivity.this).inflate(R.layout.tab_row, parent, false);
            final Tab tab = getItem(position);
            TextView title = (TextView) row.findViewById(R.id.title);
            title.setText(tab.title.isEmpty() ? getString(R.string.new_tab) : tab.title);
            title.setTypeface(null, tab.id == activeId ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            ((TextView) row.findViewById(R.id.address)).setText(tab.url);
            row.findViewById(R.id.close).setFocusable(false);
            row.findViewById(R.id.close).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    conn.send("t", "tabClose", "tabId", tab.id);
                }
            });
            return row;
        }
    }

    // -------------------------------------------------------------------------
    // Setup screen (server address)

    private void wireSetup() {
        findViewById(R.id.connect).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitSetup();
            }
        });
        serverField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                submitSetup();
                return true;
            }
        });
    }

    private void showSetup(String error) {
        hideStatus();
        setupPanel.setVisibility(View.VISIBLE);
        if (serverField.getText().length() == 0) serverField.setText(prefs.server());
        setupError.setText(error == null ? "" : error);
    }

    private void submitSetup() {
        String server = serverField.getText().toString().trim();
        if (server.isEmpty()) {
            setupError.setText(R.string.server_hint);
            return;
        }
        prefs.server(server);
        connectingFromSetup = true;
        setupError.setText(R.string.connecting);
        hideKeyboard(serverField);
        connect();
    }

    // -------------------------------------------------------------------------
    // Helpers

    private Tab activeTab() {
        return findTab(activeId);
    }

    private Tab findTab(long id) {
        for (Tab tab : tabs) if (tab.id == id) return tab;
        return null;
    }

    private void showStatus(String text) {
        status.setText(text);
        status.setVisibility(View.VISIBLE);
    }

    private void hideStatus() {
        status.setVisibility(View.GONE);
    }

    private void hideKeyboard(View v) {
        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    private void log(String level, String text) {
        if (conn.isOpen()) conn.send("t", "log", "level", level, "msg", text);
    }

    private int maxTouchPoints() {
        PackageManager pm = getPackageManager();
        if (pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND)) return 5;
        if (pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_DISTINCT)) return 2;
        if (pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH)) return 2;
        return 1;
    }

    private String appVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    /** Keeps the stack trace of a crash so it can be sent to the server log next time. */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                StringWriter trace = new StringWriter();
                e.printStackTrace(new PrintWriter(trace));
                prefs.crash(Build.MODEL + " SDK " + Build.VERSION.SDK_INT + " " + appVersion() + "\n" + trace);
                if (previous != null) previous.uncaughtException(thread, e);
            }
        });
    }
}
