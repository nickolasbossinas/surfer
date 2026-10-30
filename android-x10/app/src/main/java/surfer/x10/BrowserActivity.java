package surfer.x10;

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
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
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

import surfer.x10.input.PageInput;
import surfer.x10.net.Frame;
import surfer.x10.net.PinnedTls;
import surfer.x10.net.ServerConnection;
import surfer.x10.state.StateStore;
import surfer.x10.view.FrameView;

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
    private UrlSuggestions suggestions;
    private FrameView frameView;
    private EditText urlField;
    private View urlClear;
    private ImageButton backButton;
    private ImageButton reloadButton;
    private Button tabsButton;
    private ProgressBar progress;
    private TextView status;
    private View setupPanel;
    private EditText serverField;
    private TextView setupError;

    private final List<Tab> tabs = new ArrayList<Tab>();
    private long activeId;
    private String layout = "phone";
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
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                updateKeyboardInsets();
            }
        });
        applySystemBars();
        wireBar();
        wireSetup();
    }

    @Override
    protected void onStart() {
        super.onStart();
        visible = true;
        handler.postDelayed(statsReporter, STATS_INTERVAL_MS);
        if (prefs.server().length() == 0) showSetup(null);
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
        frameView.release();
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

    /** The status bar: shown normally, hidden in the "Full screen" option. */
    private void applySystemBars() {
        if (prefs.fullScreen()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
    }

    // -------------------------------------------------------------------------
    // Connection

    private void connect() {
        handler.removeCallbacks(reconnect);
        if (!visible || prefs.server().length() == 0) return;
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
                    .put("formats", new JSONArray().put("jpeg").put("png")); // no WebP before Android 4.0
            JSONObject hello = new JSONObject()
                    .put("t", "hello")
                    .put("proto", PROTOCOL_VERSION)
                    .put("app", "x10")
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
            if (text.length() > 0) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
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
            if (host != null && host.length() > 0) b.setTitle(getString(R.string.page_says, host));
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
        urlField.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN;
                if (actionId != EditorInfo.IME_ACTION_GO && !enter) return false;
                String input = urlField.getText().toString().trim();
                if (input.length() > 0) conn.send("t", "navigate", "tabId", activeId, "input", input);
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

    private final Rect visibleFrame = new Rect();
    private final int[] frameLocation = new int[2];

    /**
     * Where the keyboard covers the page area. The window resizes for the
     * keyboard (adjustResize), so normally nothing is covered: the frame view
     * itself got shorter. In full screen Android does not resize the window, but
     * its visible frame still ends at the keyboard; then the page's viewport
     * shrinks to the part above it (and the server scrolls the field into view).
     */
    private void updateKeyboardInsets() {
        getWindow().getDecorView().getWindowVisibleDisplayFrame(visibleFrame);
        frameView.getLocationOnScreen(frameLocation);
        int frameBottom = frameLocation[1] + frameView.getHeight();
        int covered = Math.max(0, frameBottom - visibleFrame.bottom);
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
        reloadButton.setImageResource(tab.loading ? R.drawable.ic_stop : R.drawable.ic_reload);
        reloadButton.setContentDescription(getString(tab.loading ? R.string.stop : R.string.reload));
        if (tab.loading) {
            progress.setVisibility(View.VISIBLE);
            progress.setProgress((int) Math.round(tab.progress * 100));
        } else {
            progress.setVisibility(View.GONE);
        }
    }

    @SuppressWarnings("deprecation")
    private static void setEnabled(ImageButton v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 255 : 90); // ImageView.setAlpha(int): View.setAlpha is Android 3.0
    }

    // The options menu (the phone's Menu key). Android 2.x shows no check marks in
    // it, so each toggle is titled with what it will do.

    private static final int MENU_FORWARD = 1, MENU_NEW_TAB = 2, MENU_TABS = 3, MENU_LAYOUT = 4,
            MENU_FULL_SCREEN = 5, MENU_CLEAR = 6, MENU_SERVER = 7, MENU_DEBUG = 8;

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_FORWARD, 0, R.string.forward).setIcon(R.drawable.ic_forward);
        menu.add(0, MENU_NEW_TAB, 1, R.string.new_tab);
        menu.add(0, MENU_TABS, 2, R.string.tabs);
        menu.add(0, MENU_LAYOUT, 3, R.string.layout);
        menu.add(0, MENU_FULL_SCREEN, 4, R.string.full_screen);
        menu.add(0, MENU_CLEAR, 5, R.string.clear_data);
        menu.add(0, MENU_SERVER, 6, R.string.change_server);
        menu.add(0, MENU_DEBUG, 7, R.string.debug_log_on);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (setupPanel.getVisibility() == View.VISIBLE) return false;
        Tab tab = activeTab();
        menu.findItem(MENU_FORWARD).setEnabled(ready && tab != null && tab.canForward);
        menu.findItem(MENU_FULL_SCREEN).setTitle(prefs.fullScreen() ? R.string.exit_full_screen : R.string.full_screen);
        menu.findItem(MENU_DEBUG).setTitle(prefs.debug() ? R.string.debug_log_off : R.string.debug_log_on);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_FORWARD: conn.send("t", "forward", "tabId", activeId); return true;
            case MENU_NEW_TAB: conn.send("t", "tabNew"); return true;
            case MENU_TABS: showTabs(); return true;
            case MENU_LAYOUT: chooseLayout(); return true;
            case MENU_FULL_SCREEN:
                prefs.fullScreen(!prefs.fullScreen());
                applySystemBars();
                return true;
            case MENU_CLEAR: confirmClearData(); return true;
            case MENU_SERVER: changeServer(); return true;
            case MENU_DEBUG: prefs.debug(!prefs.debug()); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    private void chooseLayout() {
        final String[] values = {"phone", "tablet", "desktop"};
        String[] labels = {getString(R.string.layout_phone), getString(R.string.layout_tablet), getString(R.string.layout_desktop)};
        int checked = 0;
        for (int i = 0; i < values.length; i++) if (values[i].equals(layout)) checked = i;
        new AlertDialog.Builder(this)
                .setTitle(R.string.layout)
                .setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        setLayout(values[which]);
                        d.dismiss();
                    }
                })
                .show();
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
            title.setText(tab.title.length() == 0 ? getString(R.string.new_tab) : tab.title);
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
        if (server.length() == 0) {
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
        // Android 1.6 has neither multitouch nor a way to ask.
        if (Build.VERSION.SDK_INT < 5) return 1;
        PackageManager pm = getPackageManager();
        // Literal names: the constants are newer than Android 2.1 (the X10 itself is single-touch).
        if (pm.hasSystemFeature("android.hardware.touchscreen.multitouch.jazzhand")) return 5;
        if (pm.hasSystemFeature("android.hardware.touchscreen.multitouch.distinct")) return 2;
        if (pm.hasSystemFeature("android.hardware.touchscreen.multitouch")) return 2;
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
