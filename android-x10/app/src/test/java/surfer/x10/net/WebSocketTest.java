package surfer.x10.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

public class WebSocketTest {
    private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};

    private static WebSocket.Frame roundTrip(int opcode, byte[] payload) throws IOException {
        byte[] encoded = WebSocket.encodeFrame(opcode, payload, MASK);
        return WebSocket.readFrame(new DataInputStream(new ByteArrayInputStream(encoded)));
    }

    @Test
    public void acceptKeyFromRfc6455() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocket.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    public void framesOfEveryLengthClass() throws IOException {
        for (int len : new int[] {0, 1, 125, 126, 65535, 65536, 300000}) {
            byte[] payload = new byte[len];
            for (int i = 0; i < len; i++) payload[i] = (byte) (i * 7);
            WebSocket.Frame f = roundTrip(WebSocket.OP_BINARY, payload);
            assertTrue(f.fin);
            assertEquals(WebSocket.OP_BINARY, f.opcode);
            assertArrayEquals("length " + len, payload, f.payload);
        }
    }

    @Test
    public void clientFramesAreMasked() {
        byte[] encoded = WebSocket.encodeFrame(WebSocket.OP_TEXT, "Hi".getBytes(), MASK);
        assertEquals((byte) 0x81, encoded[0]);
        assertEquals((byte) (0x80 | 2), encoded[1]);
        assertEquals('H' ^ 0x11, encoded[6]);
        assertEquals('i' ^ 0x22, encoded[7]);
    }

    @Test
    public void readsUnmaskedServerFrames() throws IOException {
        byte[] server = {(byte) 0x82, 3, 1, 2, 3};
        WebSocket.Frame f = WebSocket.readFrame(new DataInputStream(new ByteArrayInputStream(server)));
        assertArrayEquals(new byte[] {1, 2, 3}, f.payload);
    }

    @Test(expected = IOException.class)
    public void rejectsExtensionBits() throws IOException {
        WebSocket.readFrame(new DataInputStream(new ByteArrayInputStream(new byte[] {(byte) 0xc1, 0})));
    }

    @Test
    public void base64() {
        assertEquals("", Base64.encode(new byte[0]));
        assertEquals("Zg==", Base64.encode("f".getBytes()));
        assertEquals("Zm8=", Base64.encode("fo".getBytes()));
        assertEquals("Zm9v", Base64.encode("foo".getBytes()));
        assertEquals("-_8", Base64.encodeUrlSafe(new byte[] {(byte) 0xfb, (byte) 0xff}));
    }

    @Test
    public void hostAndPort() {
        assertArrayEquals(new String[] {"192.168.1.20", "8688"}, ServerConnection.hostAndPort("wss://192.168.1.20:8688/ws"));
        assertArrayEquals(new String[] {"fe80::1", "9000"}, ServerConnection.hostAndPort("wss://[fe80::1]:9000/ws"));
    }
}
