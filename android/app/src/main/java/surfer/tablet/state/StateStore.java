package surfer.tablet.state;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The device's copy of its browser state (DESIGN.md §4.1): cookies, local and
 * session storage, tabs, settings, and the history of visited pages (which
 * stays on the device; it only feeds URL bar suggestions). The server streams every change; this
 * class applies them on its own thread and writes the changed parts to
 * encrypted files shortly after. On a new session it sends everything back.
 */
public final class StateStore {
    /** Sends protocol messages; may block while the socket's send buffer is full. */
    public interface Sender {
        void sendBlocking(JSONObject msg);
    }

    /** Receives history matches on the main thread. */
    public interface HistoryCallback {
        void onHistory(String query, List<History.Entry> matches);
    }

    private static final String TAG = "SurferState";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final long FLUSH_DELAY_MS = 500;

    /** One tab: its URL and its sessionStorage (storage key → items). */
    private static final class TabState {
        long id;
        String url = "";
        Map<String, Map<String, String>> session = new HashMap<String, Map<String, String>>();
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final File dir;
    private final File localDir;
    private final Handler thread;
    private final Handler main = new Handler(Looper.getMainLooper());
    private StateCrypto crypto;

    // Everything below is only touched on the state thread.
    private final LinkedHashMap<String, JSONObject> cookies = new LinkedHashMap<String, JSONObject>();
    private final Map<String, Map<String, String>> local = new HashMap<String, Map<String, String>>();
    private List<TabState> tabs = new ArrayList<TabState>();
    private int active;
    private JSONObject settings = new JSONObject();
    private final History history = new History();
    /** Tabs as sent in the last restore; the next tab list from the server gets their sessionStorage. */
    private List<TabState> restoredTabs;
    private boolean cookiesDirty;
    private boolean tabsDirty;
    private boolean historyDirty;
    private final Set<String> localDirty = new HashSet<String>();

    private final Runnable flush = new Runnable() {
        @Override
        public void run() {
            flushNow();
        }
    };

    public StateStore(Context context, SharedPreferences prefs) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        dir = new File(context.getFilesDir(), "state");
        localDir = new File(dir, "local");
        HandlerThread ht = new HandlerThread("surfer-state");
        ht.start();
        thread = new Handler(ht.getLooper());
        thread.post(new Runnable() {
            @Override
            public void run() {
                load();
            }
        });
    }

    // -------------------------------------------------------------------------
    // Public API (any thread)

    /** Applies a server message if it changes the saved state (cookies, storage, tabs, settings). */
    public void onMessage(final JSONObject msg) {
        thread.post(new Runnable() {
            @Override
            public void run() {
                try {
                    apply(msg);
                } catch (JSONException e) {
                    Log.w(TAG, "bad sync message", e);
                }
            }
        });
    }

    /** Sends the saved state to start a new server session. */
    public void sendRestore(final Sender sender) {
        thread.post(new Runnable() {
            @Override
            public void run() {
                try {
                    restore(sender);
                } catch (JSONException e) {
                    Log.w(TAG, "building restore failed", e);
                }
            }
        });
    }

    /** Finds visited pages matching what the user typed in the URL bar. */
    public void matchHistory(final String query, final int limit, final HistoryCallback callback) {
        thread.post(new Runnable() {
            @Override
            public void run() {
                final List<History.Entry> matches = history.match(query, limit, System.currentTimeMillis());
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onHistory(query, matches);
                    }
                });
            }
        });
    }

    /** Deletes everything (the user cleared browsing data). */
    public void clear() {
        thread.post(new Runnable() {
            @Override
            public void run() {
                cookies.clear();
                local.clear();
                tabs = new ArrayList<TabState>();
                active = 0;
                restoredTabs = null;
                history.clear();
                cookiesDirty = tabsDirty = historyDirty = false;
                localDirty.clear();
                thread.removeCallbacks(flush);
                deleteRecursively(dir);
            }
        });
    }

    /** Writes pending changes now (the app is going to the background). */
    public void flushSoon() {
        thread.removeCallbacks(flush);
        thread.post(flush);
    }

    // -------------------------------------------------------------------------
    // Applying changes

    private void apply(JSONObject msg) throws JSONException {
        String type = msg.optString("t");
        if ("cookies".equals(type)) {
            JSONArray set = msg.optJSONArray("set");
            for (int i = 0; set != null && i < set.length(); i++) {
                JSONObject c = set.getJSONObject(i);
                cookies.put(cookieKey(c), c);
            }
            JSONArray removed = msg.optJSONArray("removed");
            for (int i = 0; removed != null && i < removed.length(); i++) cookies.remove(cookieKey(removed.getJSONObject(i)));
            cookiesDirty = true;
        } else if ("storage".equals(type)) {
            applyStorage(msg);
        } else if ("tabs".equals(type)) {
            applyTabs(msg);
        } else if ("tabUpdate".equals(type)) {
            TabState tab = tab(msg.optLong("id"), true);
            String url = msg.optString("url", tab.url);
            // Without a <title>, the server reports the address as the title.
            String title = msg.optString("title");
            if (title.equals(url)) title = "";
            if (!url.equals(tab.url)) {
                tab.url = url;
                tabsDirty = true;
                // The title may still be the previous page's; the page's own arrives in a later update.
                history.visit(url, null, System.currentTimeMillis());
                historyDirty = true;
            } else if (history.setTitle(url, title)) {
                historyDirty = true;
            }
        } else if ("settings".equals(type) || "ready".equals(type)) {
            JSONObject s = "ready".equals(type) ? msg.optJSONObject("settings") : msg;
            if (s != null) {
                settings = new JSONObject()
                        .put("layout", s.optString("layout", settings.optString("layout", "tablet")))
                        .put("searchEngine", s.optString("searchEngine", settings.optString("searchEngine", "")));
                tabsDirty = true;
            }
        } else {
            return;
        }
        thread.removeCallbacks(flush);
        thread.postDelayed(flush, FLUSH_DELAY_MS);
    }

    private void applyStorage(JSONObject msg) {
        String key = msg.optString("key");
        String op = msg.optString("op");
        Map<String, String> items;
        if ("session".equals(msg.optString("area"))) {
            TabState tab = tab(msg.optLong("tabId"), true);
            items = tab.session.get(key);
            if (items == null) tab.session.put(key, items = new HashMap<String, String>());
            tabsDirty = true;
        } else {
            items = local.get(key);
            if (items == null) local.put(key, items = new HashMap<String, String>());
            localDirty.add(key);
        }
        if ("set".equals(op)) items.put(msg.optString("name"), msg.optString("value"));
        else if ("remove".equals(op)) items.remove(msg.optString("name"));
        else if ("clear".equals(op)) items.clear();
    }

    private void applyTabs(JSONObject msg) {
        JSONArray list = msg.optJSONArray("tabs");
        long activeId = msg.optLong("activeId");
        List<TabState> next = new ArrayList<TabState>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject t = list.optJSONObject(i);
            TabState tab = tab(t.optLong("id"), false);
            if (tab == null) {
                tab = new TabState();
                tab.id = t.optLong("id");
            }
            // First list after a restore: the server opened the saved tabs in order.
            if (restoredTabs != null && i < restoredTabs.size()) {
                Map<String, Map<String, String>> merged = new HashMap<String, Map<String, String>>(restoredTabs.get(i).session);
                merged.putAll(tab.session); // anything already reported by the new tab is newer
                tab.session = merged;
            }
            tab.url = t.optString("url", tab.url);
            if (tab.id == activeId) active = i;
            next.add(tab);
        }
        restoredTabs = null;
        tabs = next;
        tabsDirty = true;
    }

    private TabState tab(long id, boolean create) {
        for (TabState t : tabs) if (t.id == id) return t;
        if (!create) return null;
        TabState t = new TabState();
        t.id = id;
        tabs.add(t);
        return t;
    }

    private static String cookieKey(JSONObject c) {
        JSONObject p = c.optJSONObject("partitionKey");
        String partition = p == null ? "" : p.optString("topLevelSite") + "|" + p.optBoolean("hasCrossSiteAncestor");
        return c.optString("name") + "\u0001" + c.optString("domain") + "\u0001" + c.optString("path") + "\u0001" + partition;
    }

    // -------------------------------------------------------------------------
    // Restore

    private void restore(Sender sender) throws JSONException {
        JSONArray cookieList = new JSONArray();
        for (JSONObject c : cookies.values()) cookieList.put(c);
        JSONArray tabList = new JSONArray();
        for (TabState t : tabs) {
            if (t.url.isEmpty()) continue;
            tabList.put(new JSONObject().put("url", t.url).put("session", toJson(t.session)));
        }
        sender.sendBlocking(new JSONObject()
                .put("t", "restoreBegin")
                .put("cookies", cookieList)
                .put("tabs", tabList)
                .put("active", active)
                .put("settings", settings));
        for (Map.Entry<String, Map<String, String>> e : local.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            sender.sendBlocking(new JSONObject()
                    .put("t", "restoreLocal")
                    .put("key", e.getKey())
                    .put("items", new JSONObject(e.getValue())));
        }
        sender.sendBlocking(new JSONObject().put("t", "restoreEnd"));
        restoredTabs = new ArrayList<TabState>();
        for (TabState t : tabs) if (!t.url.isEmpty()) restoredTabs.add(t);
        // The new session numbers its tabs from 1 again. Keep the saved tabs (in case
        // the connection drops before the new list arrives) under ids that cannot clash.
        for (int i = 0; i < tabs.size(); i++) tabs.get(i).id = -1 - i;
    }

    private static JSONObject toJson(Map<String, Map<String, String>> storage) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, Map<String, String>> e : storage.entrySet()) out.put(e.getKey(), new JSONObject(e.getValue()));
        return out;
    }

    private static Map<String, Map<String, String>> fromJson(JSONObject o) {
        Map<String, Map<String, String>> out = new HashMap<String, Map<String, String>>();
        if (o == null) return out;
        for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
            String key = keys.next();
            out.put(key, items(o.optJSONObject(key)));
        }
        return out;
    }

    private static Map<String, String> items(JSONObject o) {
        Map<String, String> out = new HashMap<String, String>();
        if (o == null) return out;
        for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
            String k = keys.next();
            out.put(k, o.optString(k));
        }
        return out;
    }

    // -------------------------------------------------------------------------
    // Files: state/cookies, state/tabs, state/history, state/local/<sha1 of storage key>

    private void load() {
        try {
            crypto = new StateCrypto(context, prefs);
        } catch (StateCrypto.KeyLostException e) {
            Log.w(TAG, "state key lost; starting with empty state", e);
            StateCrypto.reset(prefs);
            deleteRecursively(dir);
            try {
                crypto = new StateCrypto(context, prefs);
            } catch (StateCrypto.KeyLostException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        try {
            JSONObject c = read(new File(dir, "cookies"));
            JSONArray list = c == null ? null : c.optJSONArray("cookies");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject cookie = list.optJSONObject(i);
                if (cookie != null) cookies.put(cookieKey(cookie), cookie);
            }
            JSONObject t = read(new File(dir, "tabs"));
            if (t != null) {
                JSONArray tabList = t.optJSONArray("tabs");
                for (int i = 0; tabList != null && i < tabList.length(); i++) {
                    JSONObject o = tabList.optJSONObject(i);
                    TabState tab = new TabState();
                    tab.id = -1 - i;
                    tab.url = o.optString("url");
                    tab.session = fromJson(o.optJSONObject("session"));
                    tabs.add(tab);
                }
                active = t.optInt("active");
                JSONObject s = t.optJSONObject("settings");
                if (s != null) settings = s;
            }
            history.load(read(new File(dir, "history")));
            File[] files = localDir.listFiles();
            for (int i = 0; files != null && i < files.length; i++) {
                JSONObject o = read(files[i]);
                if (o != null) local.put(o.optString("key"), items(o.optJSONObject("items")));
            }
        } catch (Exception e) {
            Log.w(TAG, "loading saved state failed", e);
        }
    }

    private void flushNow() {
        try {
            if (cookiesDirty) {
                JSONArray list = new JSONArray();
                for (JSONObject c : cookies.values()) list.put(c);
                write(new File(dir, "cookies"), new JSONObject().put("cookies", list));
                cookiesDirty = false;
            }
            if (tabsDirty) {
                JSONArray list = new JSONArray();
                for (TabState t : tabs) list.put(new JSONObject().put("url", t.url).put("session", toJson(t.session)));
                write(new File(dir, "tabs"), new JSONObject().put("tabs", list).put("active", active).put("settings", settings));
                tabsDirty = false;
            }
            if (historyDirty) {
                write(new File(dir, "history"), history.toJson());
                historyDirty = false;
            }
            for (String key : localDirty) {
                File file = new File(localDir, sha1(key));
                Map<String, String> items = local.get(key);
                if (items == null || items.isEmpty()) {
                    file.delete();
                } else {
                    write(file, new JSONObject().put("key", key).put("items", new JSONObject(items)));
                }
            }
            localDirty.clear();
        } catch (Exception e) {
            Log.w(TAG, "saving state failed", e);
        }
    }

    private JSONObject read(File file) throws Exception {
        if (!file.exists()) return null;
        byte[] data = readAll(file);
        return new JSONObject(new String(crypto.decrypt(data), UTF8));
    }

    /** Atomic: temp file + rename, so a crash never leaves a half-written file. */
    private void write(File file, JSONObject value) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("cannot create " + parent);
        File tmp = new File(file.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(crypto.encrypt(value.toString().getBytes(UTF8)));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(file)) throw new IOException("rename failed for " + file);
    }

    private static byte[] readAll(File file) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) throw new IOException("short read");
                off += n;
            }
            return data;
        } finally {
            in.close();
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(UTF8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteRecursively(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File c : children) deleteRecursively(c);
        f.delete();
    }
}
