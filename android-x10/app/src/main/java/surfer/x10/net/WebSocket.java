package surfer.x10.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A WebSocket client (RFC 6455) over the pinned TLS connection. No WebSocket
 * library supports Android 2.1, and the protocol subset needed here is small:
 * text and binary messages, fragmentation, ping/pong and close. No extensions.
 *
 * One reader thread connects and reads; one writer thread sends whatever is
 * queued, so callers never block on the network. Callbacks run on the reader
 * thread.
 */
public final class WebSocket {
    public interface Listener {
        void onOpen();
        void onText(String text);
        void onBinary(byte[] data);
        /** Called once, after open or after a failed connect. {@code error} is null for a clean close. */
        void onClosed(int code, String reason, Throwable error);
    }

    static final int OP_CONTINUATION = 0;
    static final int OP_TEXT = 1;
    static final int OP_BINARY = 2;
    static final int OP_CLOSE = 8;
    static final int OP_PING = 9;
    static final int OP_PONG = 10;

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    /** The server pings every 15 s and answers the app's pings every 2 s; silence this long means a dead link. */
    private static final int READ_TIMEOUT_MS = 40000;
    private static final int MAX_MESSAGE = 64 * 1024 * 1024;
    private static final byte[] STOP = new byte[0];

    private final PinnedTls tls;
    private final String host;
    private final int port;
    private final String path;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private final LinkedBlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<byte[]>();
    private final Object sizeLock = new Object();
    private long queuedBytes;
    private volatile Socket socket;
    private volatile boolean closing;
    private boolean closedReported;

    public WebSocket(PinnedTls tls, String host, int port, String path, Listener listener) {
        this.tls = tls;
        this.host = host;
        this.port = port;
        this.path = path;
        this.listener = listener;
    }

    public void start() {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "surfer-ws-read");
        reader.start();
    }

    public void sendText(String text) {
        try {
            enqueue(encodeFrame(OP_TEXT, text.getBytes("UTF-8"), newMask()));
        } catch (UnsupportedEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Bytes queued and not yet written to the socket. */
    public long queuedBytes() {
        synchronized (sizeLock) {
            return queuedBytes;
        }
    }

    /** Starts a clean close; the connection ends shortly after (or at once if it never opened). */
    public void close() {
        if (closing) return;
        closing = true;
        enqueue(encodeFrame(OP_CLOSE, new byte[] {0x03, (byte) 0xe8}, newMask())); // 1000
        outgoing.offer(STOP);
        // Closing an SSL socket can block on Android 2.x while a read is in progress.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
                closeSocket();
            }
        }, "surfer-ws-close").start();
    }

    // -------------------------------------------------------------------------

    private void readLoop() {
        int code = 1006;
        String reason = "";
        Throwable error = null;
        try {
            Socket s = tls.connect(host, port, CONNECT_TIMEOUT_MS);
            socket = s;
            if (closing) throw new IOException("closed");
            InputStream in = new BufferedInputStream(s.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 16384);
            handshake(in, out);
            s.setSoTimeout(READ_TIMEOUT_MS);
            startWriter(out);
            listener.onOpen();

            DataInputStream data = new DataInputStream(in);
            ByteArrayOutputStream message = new ByteArrayOutputStream();
            int messageOp = -1;
            while (true) {
                Frame f = readFrame(data);
                switch (f.opcode) {
                    case OP_PING:
                        enqueue(encodeFrame(OP_PONG, f.payload, newMask()));
                        break;
                    case OP_PONG:
                        break;
                    case OP_CLOSE:
                        code = f.payload.length >= 2 ? ((f.payload[0] & 0xff) << 8) | (f.payload[1] & 0xff) : 1005;
                        reason = f.payload.length > 2 ? new String(f.payload, 2, f.payload.length - 2, "UTF-8") : "";
                        if (!closing) {
                            closing = true;
                            enqueue(encodeFrame(OP_CLOSE, f.payload.length >= 2 ? new byte[] {f.payload[0], f.payload[1]} : new byte[0], newMask()));
                            outgoing.offer(STOP);
                        }
                        return;
                    case OP_TEXT:
                    case OP_BINARY:
                    case OP_CONTINUATION:
                        if (f.opcode != OP_CONTINUATION) {
                            messageOp = f.opcode;
                            message.reset();
                        } else if (messageOp < 0) {
                            throw new IOException("continuation without a message");
                        }
                        if (message.size() + f.payload.length > MAX_MESSAGE) throw new IOException("message too large");
                        if (f.fin && message.size() == 0) {
                            deliver(messageOp, f.payload);
                            messageOp = -1;
                        } else {
                            message.write(f.payload, 0, f.payload.length);
                            if (f.fin) {
                                deliver(messageOp, message.toByteArray());
                                message.reset();
                                messageOp = -1;
                            }
                        }
                        break;
                    default:
                        throw new IOException("unknown opcode " + f.opcode);
                }
            }
        } catch (Throwable t) {
            if (!closing) error = t;
            reason = String.valueOf(t.getMessage());
        } finally {
            outgoing.offer(STOP);
            closeSocket();
            reportClosed(code, reason, error);
        }
    }

    private void deliver(int opcode, byte[] payload) throws UnsupportedEncodingException {
        if (opcode == OP_TEXT) listener.onText(new String(payload, "UTF-8"));
        else listener.onBinary(payload);
    }

    private synchronized void reportClosed(int code, String reason, Throwable error) {
        if (closedReported) return;
        closedReported = true;
        listener.onClosed(code, reason, error);
    }

    private void handshake(InputStream in, OutputStream out) throws IOException {
        byte[] keyBytes = new byte[16];
        random.nextBytes(keyBytes);
        String key = Base64.encode(keyBytes);
        String hostHeader = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + hostHeader + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + key + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "User-Agent: Surfer-X10\r\n\r\n";
        out.write(request.getBytes("ISO-8859-1"));
        out.flush();

        String status = readLine(in);
        if (!status.startsWith("HTTP/1.1 101")) throw new IOException("server refused the WebSocket: " + status);
        String accept = null;
        for (String line = readLine(in); line.length() > 0; line = readLine(in)) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Accept")) {
                accept = line.substring(colon + 1).trim();
            }
        }
        if (!acceptFor(key).equals(accept)) throw new IOException("bad Sec-WebSocket-Accept");
    }

    static String acceptFor(String key) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.encode(sha1.digest((key + GUID).getBytes("ISO-8859-1")));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) throw new EOFException("connection closed during handshake");
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) throw new IOException("handshake line too long");
        }
        return sb.toString();
    }

    private void startWriter(final OutputStream out) {
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        byte[] frame = outgoing.take();
                        if (frame == STOP) break;
                        out.write(frame);
                        synchronized (sizeLock) {
                            queuedBytes -= frame.length;
                        }
                        if (outgoing.isEmpty()) out.flush();
                    }
                    out.flush();
                } catch (InterruptedException ignored) {
                } catch (IOException e) {
                    closeSocket(); // the reader notices and reports
                }
            }
        }, "surfer-ws-write");
        writer.start();
    }

    private void enqueue(byte[] frame) {
        synchronized (sizeLock) {
            queuedBytes += frame.length;
        }
        outgoing.offer(frame);
    }

    private void closeSocket() {
        Socket s = socket;
        if (s == null) return;
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }

    private byte[] newMask() {
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        return mask;
    }

    // -------------------------------------------------------------------------
    // Framing (package-private for tests)

    static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /** A complete, final frame; client frames are always masked. */
    static byte[] encodeFrame(int opcode, byte[] payload, byte[] mask) {
        int len = payload.length;
        int header = 2 + (len < 126 ? 0 : len <= 0xffff ? 2 : 8) + 4;
        byte[] out = new byte[header + len];
        out[0] = (byte) (0x80 | opcode);
        int i;
        if (len < 126) {
            out[1] = (byte) (0x80 | len);
            i = 2;
        } else if (len <= 0xffff) {
            out[1] = (byte) (0x80 | 126);
            out[2] = (byte) (len >> 8);
            out[3] = (byte) len;
            i = 4;
        } else {
            out[1] = (byte) (0x80 | 127);
            long l = len;
            for (int k = 0; k < 8; k++) out[2 + k] = (byte) (l >> (56 - 8 * k));
            i = 10;
        }
        System.arraycopy(mask, 0, out, i, 4);
        i += 4;
        for (int k = 0; k < len; k++) out[i + k] = (byte) (payload[k] ^ mask[k & 3]);
        return out;
    }

    static Frame readFrame(DataInputStream in) throws IOException {
        int b0 = in.readUnsignedByte();
        int b1 = in.readUnsignedByte();
        if ((b0 & 0x70) != 0) throw new IOException("unexpected extension bits");
        long len = b1 & 0x7f;
        if (len == 126) len = in.readUnsignedShort();
        else if (len == 127) len = in.readLong();
        if (len < 0 || len > MAX_MESSAGE) throw new IOException("frame too large");
        byte[] mask = null;
        if ((b1 & 0x80) != 0) {
            mask = new byte[4];
            in.readFully(mask);
        }
        byte[] payload = new byte[(int) len];
        in.readFully(payload);
        if (mask != null) for (int k = 0; k < payload.length; k++) payload[k] ^= mask[k & 3];
        return new Frame((b0 & 0x80) != 0, b0 & 0x0f, payload);
    }
}
