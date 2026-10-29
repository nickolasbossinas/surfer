package surfer.x10.net;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;


/**
 * The WebSocket to the Surfer server. Text messages are delivered on the main
 * thread; frames are delivered on the network thread (the frame view decodes
 * them off the main thread anyway). Also keeps the clock offset to the server.
 */
public final class ServerConnection {
    public interface Listener {
        void onOpen();
        void onMessage(JSONObject msg);
        /** Called on the network thread. */
        void onFrame(Frame frame);
        /** The connection ended. {@code tlsMismatch}: the server's certificate is not the pinned one. */
        void onClosed(int code, String reason, boolean tlsMismatch);
    }

    private static final long PING_INTERVAL_MS = 2000;

    private final PinnedTls tls;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile WebSocket socket;
    /** Increments per connect(); callbacks from older sockets are ignored. */
    private volatile int generation;
    private boolean open;

    // Clock sync: serverTime ≈ wallClock + offset. Written and read on the main thread.
    private long clockOffsetMs;
    private long rttMs = -1;

    private final Runnable pinger = new Runnable() {
        @Override
        public void run() {
            if (!open) return;
            try {
                send(new JSONObject().put("t", "ping").put("t0", System.currentTimeMillis()));
            } catch (JSONException ignored) {
            }
            main.postDelayed(this, PING_INTERVAL_MS);
        }
    };

    public ServerConnection(PinnedTls tls, Listener listener) {
        this.listener = listener;
        this.tls = tls;
    }

    /** The server's port when the address is typed without one (set at build time). */
    public static final int DEFAULT_PORT = surfer.x10.BuildConfig.DEFAULT_PORT;

    /** "192.168.1.20" or "host:port" → wss URL. */
    public static String urlFor(String server) {
        String s = server.trim();
        if (s.startsWith("wss://")) s = s.substring(6);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        boolean hasPort = s.lastIndexOf(':') > s.lastIndexOf(']');
        return "wss://" + s + (hasPort ? "" : ":" + DEFAULT_PORT) + "/ws";
    }

    /** Host and port of a URL made by {@link #urlFor}. */
    static String[] hostAndPort(String url) {
        String s = url.substring("wss://".length());
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int colon = s.lastIndexOf(':');
        String host = s.substring(0, colon);
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        return new String[] {host, s.substring(colon + 1)};
    }

    public void connect(String url) {
        disconnect();
        final int gen = ++generation;
        String[] hp = hostAndPort(url);
        int port;
        try {
            port = Integer.parseInt(hp[1]);
        } catch (NumberFormatException e) {
            closed(gen, 1006, "bad port " + hp[1], false);
            return;
        }
        WebSocket ws = new WebSocket(tls, hp[0], port, "/ws", new WebSocket.Listener() {
            @Override
            public void onOpen() {
                post(gen, new Runnable() {
                    @Override
                    public void run() {
                        open = true;
                        listener.onOpen();
                        main.post(pinger);
                    }
                });
            }

            @Override
            public void onText(String text) {
                final JSONObject msg;
                try {
                    msg = new JSONObject(text);
                } catch (JSONException e) {
                    return;
                }
                post(gen, new Runnable() {
                    @Override
                    public void run() {
                        if ("pong".equals(msg.optString("t"))) onPong(msg);
                        else listener.onMessage(msg);
                    }
                });
            }

            @Override
            public void onBinary(byte[] data) {
                if (gen != generation) return;
                Frame frame = Frame.parse(data);
                if (frame != null) listener.onFrame(frame);
            }

            @Override
            public void onClosed(int code, String reason, Throwable error) {
                closed(gen, code, reason, isTlsMismatch(error));
            }
        });
        socket = ws;
        ws.start();
    }

    public void disconnect() {
        generation++;
        open = false;
        main.removeCallbacks(pinger);
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    public boolean isOpen() {
        return open;
    }

    public void send(JSONObject msg) {
        WebSocket s = socket;
        if (s != null) s.sendText(msg.toString());
    }

    /**
     * Like send(), but first waits while more than 1 MB is queued, so a large
     * restore does not fill the small heap. Never call on the main thread.
     */
    public void sendBlocking(JSONObject msg) {
        WebSocket s = socket;
        while (s != null && s == socket && s.queuedBytes() > 1024 * 1024) {
            SystemClock.sleep(20);
        }
        if (s != null && s == socket) s.sendText(msg.toString());
    }

    /** Sends a message built from key/value pairs: send("t", "back", "tabId", 3). */
    public void send(Object... keyValues) {
        try {
            JSONObject msg = new JSONObject();
            for (int i = 0; i + 1 < keyValues.length; i += 2) msg.put((String) keyValues[i], keyValues[i + 1]);
            send(msg);
        } catch (JSONException ignored) {
        }
    }

    /** Converts an event time (SystemClock.uptimeMillis base) to server-clock milliseconds. */
    public long serverTime(long uptimeMs) {
        long wall = uptimeMs + (System.currentTimeMillis() - SystemClock.uptimeMillis());
        return wall + clockOffsetMs;
    }

    public long rttMs() {
        return rttMs;
    }

    private void onPong(JSONObject msg) {
        long now = System.currentTimeMillis();
        long rtt = now - msg.optLong("t0", now);
        // Keep the offset from the fastest round trip seen recently: least queuing error.
        if (rttMs < 0 || rtt <= rttMs * 1.5) {
            clockOffsetMs = msg.optLong("server", now) + rtt / 2 - now;
        }
        rttMs = rttMs < 0 ? rtt : (rttMs * 7 + rtt) / 8;
    }

    private void closed(final int gen, final int code, final String reason, final boolean tlsMismatch) {
        post(gen, new Runnable() {
            @Override
            public void run() {
                open = false;
                main.removeCallbacks(pinger);
                socket = null;
                generation++;
                listener.onClosed(code, reason, tlsMismatch);
            }
        });
    }

    private void post(final int gen, final Runnable r) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (gen == generation) r.run();
            }
        });
    }

    private static boolean isTlsMismatch(Throwable t) {
        // Android 2.x wraps the trust manager's exception in various ways; the text survives.
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof PinnedTls.MismatchException) return true;
            String m = c.getMessage();
            if (m != null && m.indexOf("not the one this app was built for") >= 0) return true;
        }
        return false;
    }
}
