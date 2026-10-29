package surfer.x10.state;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class HistoryTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private static String urls(List<History.Entry> list) {
        StringBuilder sb = new StringBuilder();
        for (History.Entry e : list) sb.append(sb.length() == 0 ? "" : " ").append(e.url);
        return sb.toString();
    }

    @Test
    public void matchesAddressPrefixIgnoringSchemeAndWww() {
        History h = new History();
        h.visit("https://www.wikipedia.org/", "Wikipedia", NOW);
        h.visit("https://example.com/wiki", "Example", NOW);
        // The address prefix ranks above a word inside the path.
        assertEquals("https://www.wikipedia.org/ https://example.com/wiki", urls(h.match("wiki", 5, NOW)));
        assertEquals("https://www.wikipedia.org/", urls(h.match("https://www.wiki", 5, NOW)));
    }

    @Test
    public void matchesWordStartsInTitle() {
        History h = new History();
        h.visit("https://mail.google.com/mail/u/0/", "Inbox (3) - someone@gmail.com - Gmail", NOW);
        assertEquals(1, h.match("gmail inbox", 5, NOW).size());
        assertEquals(0, h.match("box", 5, NOW).size()); // not at a word start
    }

    @Test
    public void ranksPrefixThenFrequentThenRecent() {
        History h = new History();
        h.visit("https://news.example.com/a", "News", NOW - 10 * DAY);
        for (int i = 0; i < 5; i++) h.visit("https://news.ycombinator.com/", "Hacker News", NOW);
        h.visit("https://example.org/", "Daily news", NOW);
        List<History.Entry> m = h.match("news", 5, NOW);
        assertEquals("https://news.ycombinator.com/ https://news.example.com/a https://example.org/", urls(m));
    }

    @Test
    public void limitsAndIgnoresNonWebPages() {
        History h = new History();
        h.visit("about:blank", "", NOW);
        h.visit("chrome://version", "", NOW);
        assertEquals(0, h.size());
        for (int i = 0; i < 10; i++) h.visit("https://site" + i + ".com/", "", NOW + i);
        assertEquals(3, h.match("site", 3, NOW).size());
        assertEquals(0, h.match("  ", 3, NOW).size());
    }

    @Test
    public void dropsOldestWhenFull() {
        History h = new History();
        for (int i = 0; i < History.MAX_ENTRIES + 1; i++) h.visit("https://s.com/" + i, "", NOW + i);
        assertEquals(History.MAX_ENTRIES, h.size());
        assertFalse(h.contains("https://s.com/0"));
        assertTrue(h.contains("https://s.com/1"));
    }

    @Test
    public void displayAddress() {
        assertEquals("example.com", History.displayAddress("https://www.example.com/"));
        assertEquals("example.com/a/", History.displayAddress("http://example.com/a/"));
        assertTrue(History.startsWord("hello-world", "world"));
        assertFalse(History.startsWord("helloworld", "world"));
    }
}
