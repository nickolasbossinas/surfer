package surfer.x10.state;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pages visited, for URL bar suggestions. Kept with the rest of the device's
 * state (encrypted) and never sent to the server. Not thread-safe: StateStore
 * uses it on its own thread.
 */
public final class History {
    static final int MAX_ENTRIES = 2000;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;

    public static final class Entry {
        public final String url;
        public String title = "";
        int visits;
        long lastVisit;

        Entry(String url) {
            this.url = url;
        }
    }

    private final Map<String, Entry> entries = new HashMap<String, Entry>();

    /** A page was opened. */
    public void visit(String url, String title, long now) {
        if (!isRecordable(url)) return;
        Entry e = entries.get(url);
        if (e == null) {
            if (entries.size() >= MAX_ENTRIES) dropOldest();
            entries.put(url, e = new Entry(url));
        }
        e.visits++;
        e.lastVisit = now;
        if (title != null && title.length() > 0) e.title = title;
    }

    /** The page's title became known. Returns true if something changed. */
    public boolean setTitle(String url, String title) {
        Entry e = entries.get(url);
        if (e == null || title == null || title.length() == 0 || title.equals(e.title)) return false;
        e.title = title;
        return true;
    }

    public void clear() {
        entries.clear();
    }

    boolean contains(String url) {
        return entries.containsKey(url);
    }

    public int size() {
        return entries.size();
    }

    static boolean isRecordable(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://")) && url.length() <= 2048;
    }

    private void dropOldest() {
        Entry oldest = null;
        for (Entry e : entries.values()) if (oldest == null || e.lastVisit < oldest.lastVisit) oldest = e;
        if (oldest != null) entries.remove(oldest.url);
    }

    // -------------------------------------------------------------------------
    // Matching

    /**
     * The best entries for what the user typed, like Chrome's history
     * suggestions: the typed text must start the address (ignoring the scheme
     * and "www.") or every typed word must start a word of the title or
     * address. Frequently and recently visited pages rank higher.
     */
    public List<Entry> match(String query, int limit, long now) {
        String q = query.trim().toLowerCase(Locale.US);
        List<Entry> out = new ArrayList<Entry>();
        if (q.length() == 0 || limit <= 0) return out;
        String[] terms = q.split("\\s+");
        final Map<Entry, Double> scores = new HashMap<Entry, Double>();
        for (Entry e : entries.values()) {
            String address = displayAddress(e.url).toLowerCase(Locale.US);
            double base;
            if (address.startsWith(q) || e.url.toLowerCase(Locale.US).startsWith(q)) {
                base = 100;
            } else if (allTermsMatch(terms, address, e.title.toLowerCase(Locale.US))) {
                base = 40;
            } else {
                continue;
            }
            double ageDays = Math.max(0, (now - e.lastVisit) / (double) DAY_MS);
            double score = base * (1 + Math.log(1 + e.visits)) / (1 + ageDays / 30);
            // Among equals, the shorter address (a site's home page) first.
            score -= address.length() * 0.001;
            scores.put(e, score);
            out.add(e);
        }
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Double.compare(scores.get(b), scores.get(a));
            }
        });
        return out.size() > limit ? new ArrayList<Entry>(out.subList(0, limit)) : out;
    }

    /** The address without "http(s)://" and "www.", as the URL bar suggestions show it. */
    public static String displayAddress(String url) {
        String s = url;
        if (s.startsWith("https://")) s = s.substring(8);
        else if (s.startsWith("http://")) s = s.substring(7);
        if (s.startsWith("www.")) s = s.substring(4);
        if (s.endsWith("/") && s.indexOf('/') == s.length() - 1) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static boolean allTermsMatch(String[] terms, String address, String title) {
        for (String t : terms) {
            if (!startsWord(address, t) && !startsWord(title, t)) return false;
        }
        return true;
    }

    /** {@code term} occurs in {@code text} at the start of a word. */
    static boolean startsWord(String text, String term) {
        for (int i = text.indexOf(term); i >= 0; i = text.indexOf(term, i + 1)) {
            if (i == 0 || !Character.isLetterOrDigit(text.charAt(i - 1))) return true;
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Saved form

    JSONObject toJson() throws JSONException {
        JSONArray list = new JSONArray();
        for (Entry e : entries.values()) {
            list.put(new JSONObject().put("u", e.url).put("t", e.title).put("v", e.visits).put("l", e.lastVisit));
        }
        return new JSONObject().put("entries", list);
    }

    void load(JSONObject o) {
        JSONArray list = o == null ? null : o.optJSONArray("entries");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject j = list.optJSONObject(i);
            if (j == null || !isRecordable(j.optString("u"))) continue;
            Entry e = new Entry(j.optString("u"));
            e.title = j.optString("t");
            e.visits = Math.max(1, j.optInt("v", 1));
            e.lastVisit = j.optLong("l");
            entries.put(e.url, e);
        }
    }
}
