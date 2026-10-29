package surfer.tablet.net;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLPeerUnverifiedException;

import okhttp3.ConnectionSpec;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

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

    private final OkHttpClient client;
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
        this.client = new OkHttpClient.Builder()
                .sslSocketFactory(tls.socketFactory, tls.trustManager)
                .hostnameVerifier(tls.hostnameVerifier)
                .connectionSpecs(Collections.singletonList(ConnectionSpec.MODERN_TLS))
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(15, TimeUnit.SECONDS)
                .build();
    }

    /** The server's port when the address is typed without one (set at build time). */
    public static final int DEFAULT_PORT = surfer.tablet.BuildConfig.DEFAULT_PORT;

    /** "192.168.1.20" or "host:port" → wss URL. */
    public static String urlFor(String server) {
        String s = server.trim();
        if (s.startsWith("wss://")) s = s.substring(6);
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        boolean hasPort = s.lastIndexOf(':') > s.lastIndexOf(']');
        return "wss://" + s + (hasPort ? "" : ":" + DEFAULT_PORT) + "/ws";
    }

    public void connect(String url) {
        disconnect();
        final int gen = ++generation;
        Request request = new Request.Builder().url(url).build();
        socket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
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
            public void onMessage(WebSocket ws, String text) {
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
            public void onMessage(WebSocket ws, ByteString bytes) {
                if (gen != generation) return;
                Frame frame = Frame.parse(bytes.toByteArray());
                if (frame != null) listener.onFrame(frame);
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                ws.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket ws, final int code, final String reason) {
                closed(gen, code, reason, false);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                closed(gen, 1006, String.valueOf(t.getMessage()), isTlsMismatch(t));
            }
        });
    }

    public void disconnect() {
        generation++;
        open = false;
        main.removeCallbacks(pinger);
        if (socket != null) {
            socket.close(1000, null);
            socket = null;
        }
    }

    public boolean isOpen() {
        return open;
    }

    public void send(JSONObject msg) {
        WebSocket s = socket;
        if (s != null) s.send(msg.toString());
    }

    /**
     * Like send(), but first waits while more than 4 MB are queued: OkHttp closes
     * the socket when its send queue exceeds 16 MB. Never call on the main thread.
     */
    public void sendBlocking(JSONObject msg) {
        WebSocket s = socket;
        while (s != null && s == socket && s.queueSize() > 4L * 1024 * 1024) {
            SystemClock.sleep(20);
        }
        if (s != null && s == socket) s.send(msg.toString());
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
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SSLPeerUnverifiedException || c instanceof CertPathValidatorException
                    || c instanceof CertificateException) {
                return true;
            }
        }
        return false;
    }
}
