package surfer.x10;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import surfer.x10.state.History;
import surfer.x10.state.StateStore;

/**
 * The list under the URL bar while typing, like Chrome's: what was typed
 * (go to it or search it), matching pages from the device's history, and the
 * search engine's suggestions (fetched by the server, DESIGN.md §3.6). The
 * arrow on a row puts its text into the URL bar to keep editing.
 */
final class UrlSuggestions {
    interface Host {
        void requestSuggestions(int id, String query);
        /** Go to a URL or search for the text (as if typed and submitted). */
        void open(String input);
        /** Put the text into the URL bar for further editing. */
        void fill(String text);
    }

    private static final int MAX_HISTORY = 3;
    private static final int MAX_SEARCH = 5;
    private static final long REQUEST_DELAY_MS = 80;

    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*:.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern HOST = Pattern.compile(
            "^(localhost|(\\d{1,3}\\.){3}\\d{1,3}|([a-z0-9-]+\\.)+[a-z][a-z0-9-]{1,62}\\.?)(:\\d+)?([/?#].*)?$",
            Pattern.CASE_INSENSITIVE);

    private enum Kind { TYPED_URL, TYPED_SEARCH, HISTORY, SEARCH, SITE }

    private static final class Item {
        final Kind kind;
        final CharSequence text;
        final String detail;
        /** Sent as the navigate input when the row is tapped. */
        final String input;
        /** Put into the URL bar by the arrow. */
        final String fill;

        Item(Kind kind, CharSequence text, String detail, String input, String fill) {
            this.kind = kind;
            this.text = text;
            this.detail = detail;
            this.input = input;
            this.fill = fill;
        }
    }

    private final Context context;
    private final ListView list;
    private final StateStore state;
    private final Host host;
    private final Handler handler = new Handler();
    private final Adapter adapter = new Adapter();

    private String query = "";
    private int requestId;
    private List<History.Entry> historyMatches = new ArrayList<History.Entry>();
    /** The latest search suggestions; kept while newer ones load, if they still fit the text. */
    private final List<JSONObject> searchMatches = new ArrayList<JSONObject>();
    /** The text {@link #searchMatches} are for. */
    private String searchQuery = "";
    private final List<Item> items = new ArrayList<Item>();

    private final Runnable request = new Runnable() {
        @Override
        public void run() {
            host.requestSuggestions(++requestId, query);
        }
    };

    UrlSuggestions(Context context, ListView list, StateStore state, Host host) {
        this.context = context;
        this.list = list;
        this.state = state;
        this.host = host;
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                UrlSuggestions.this.host.open(items.get(position).input);
            }
        });
    }

    /** The user changed the URL bar text. */
    void onQuery(String text) {
        String q = text.trim();
        if (q.length() == 0) {
            hide();
            return;
        }
        query = q;
        state.matchHistory(q, MAX_HISTORY + 1, new StateStore.HistoryCallback() {
            @Override
            public void onHistory(String forQuery, List<History.Entry> matches) {
                if (!forQuery.equals(query)) return;
                historyMatches = matches;
                render();
            }
        });
        handler.removeCallbacks(request);
        handler.postDelayed(request, REQUEST_DELAY_MS);
        render();
    }

    /** The server's "suggestions" message. */
    void onSuggestions(JSONObject msg) {
        if (msg.optInt("id") != requestId || query.length() == 0) return;
        searchMatches.clear();
        searchQuery = msg.optString("q");
        JSONArray list = msg.optJSONArray("items");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null) searchMatches.add(o);
        }
        render();
    }

    void hide() {
        handler.removeCallbacks(request);
        requestId++; // answers still on the way are dropped
        query = "";
        historyMatches = new ArrayList<History.Entry>();
        searchMatches.clear();
        searchQuery = "";
        items.clear();
        adapter.notifyDataSetChanged();
        list.setVisibility(View.GONE);
    }

    /** Keeps the list above the keyboard. */
    void setBottomMargin(int px) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) list.getLayoutParams();
        if (lp.bottomMargin == px) return;
        lp.bottomMargin = px;
        list.setLayoutParams(lp);
    }

    private void render() {
        items.clear();
        if (query.length() == 0) return;
        String lower = query.toLowerCase(Locale.US);
        boolean typedUrl = looksLikeUrl(query);
        items.add(typedUrl
                ? new Item(Kind.TYPED_URL, query, "", query, query)
                : new Item(Kind.TYPED_SEARCH, query, "", query, query));

        int shown = 0;
        for (History.Entry e : historyMatches) {
            if (shown == MAX_HISTORY) break;
            String address = History.displayAddress(e.url);
            if (typedUrl && address.equalsIgnoreCase(History.displayAddress(query))) continue;
            String title = e.title.length() == 0 ? address : e.title;
            items.add(new Item(Kind.HISTORY, title, address, e.url, e.url));
            shown++;
        }

        shown = 0;
        boolean current = searchQuery.equalsIgnoreCase(query);
        for (JSONObject o : searchMatches) {
            if (shown == MAX_SEARCH) break;
            String text = o.optString("text");
            if (text.length() == 0) continue;
            // Results for an older text stay until the new ones arrive, if they still fit.
            boolean url = "url".equals(o.optString("kind"));
            if (!current && !(url ? History.displayAddress(text) : text).toLowerCase(Locale.US).startsWith(lower)) continue;
            if (url) {
                items.add(new Item(Kind.SITE, History.displayAddress(text), "", text, text));
            } else {
                items.add(new Item(Kind.SEARCH, boldAfter(text, query), "", text, text));
            }
            shown++;
        }
        adapter.notifyDataSetChanged();
        list.setVisibility(View.VISIBLE);
    }

    /** Like Chrome: what the user typed stays normal, the suggested rest is bold. */
    private static CharSequence boldAfter(String text, String typed) {
        if (!text.toLowerCase(Locale.US).startsWith(typed.toLowerCase(Locale.US)) || text.length() == typed.length()) return text;
        SpannableString s = new SpannableString(text);
        s.setSpan(new StyleSpan(Typeface.BOLD), typed.length(), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    /** Whether the server will open the text as an address rather than search for it (see url.ts). */
    static boolean looksLikeUrl(String text) {
        if (text.length() == 0 || text.matches(".*\\s.*")) return false;
        return HOST.matcher(text).matches() || SCHEME.matcher(text).matches();
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Item getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(context).inflate(R.layout.suggestion_row, parent, false);
            final Item item = getItem(position);
            ImageView icon = (ImageView) row.findViewById(R.id.icon);
            switch (item.kind) {
                case HISTORY: icon.setImageResource(R.drawable.ic_history); break;
                case TYPED_URL:
                case SITE: icon.setImageResource(R.drawable.ic_globe); break;
                default: icon.setImageResource(R.drawable.ic_search); break;
            }
            ((TextView) row.findViewById(R.id.text)).setText(item.text);
            TextView detail = (TextView) row.findViewById(R.id.detail);
            detail.setText(item.detail);
            detail.setVisibility(item.detail.length() == 0 ? View.GONE : View.VISIBLE);
            // ImageButton makes itself focusable in its constructor (ignoring the XML),
            // and a focusable child stops ListView item clicks; so the row handles its own tap.
            View fill = row.findViewById(R.id.fill);
            fill.setFocusable(false);
            fill.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.fill(item.fill);
                }
            });
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.open(item.input);
                }
            });
            return row;
        }
    }
}
