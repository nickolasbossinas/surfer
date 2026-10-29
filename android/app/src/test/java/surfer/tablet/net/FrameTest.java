package surfer.tablet.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.Test;

public class FrameTest {
    /** Builds an update the way the server's encodeFrame does. */
    private static byte[] update(int flags, int width, int height, int[][] rects, int[] lengths) {
        int size = Frame.HEADER_SIZE;
        for (int len : lengths) size += Frame.RECT_HEADER_SIZE + len;
        ByteBuffer b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) 1).put(1, (byte) flags).putShort(2, (short) rects.length);
        b.putInt(4, (int) 4000000000L).putInt(8, 7);
        b.putShort(12, (short) width).putShort(14, (short) height);
        b.putFloat(16, 1.5f).putFloat(24, 1234.5f);
        b.putFloat(36, 56f).putFloat(52, 321.25f).putInt(56, 9);
        int o = Frame.HEADER_SIZE;
        for (int i = 0; i < rects.length; i++) {
            b.putShort(o, (short) rects[i][0]).putShort(o + 2, (short) rects[i][1]);
            b.putShort(o + 4, (short) rects[i][2]).putShort(o + 6, (short) rects[i][3]);
            b.put(o + 8, (byte) rects[i][4]).putInt(o + 12, lengths[i]);
            o += Frame.RECT_HEADER_SIZE + lengths[i];
        }
        return b.array();
    }

    @Test
    public void parsesHeaderAndRectangles() {
        byte[] data = update(Frame.FLAG_REFINE | Frame.FLAG_SCROLL_TARGET, 1280, 800,
                new int[][] {{0, 0, 32, 64, Frame.FORMAT_PNG}, {640, 96, 1, 1, Frame.FORMAT_JPEG}}, new int[] {3, 2});
        Frame f = Frame.parse(data);
        assertTrue(f.hasScrollTarget());
        assertFalse(f.isKeyframe());
        assertFalse(f.isFullPicture());
        assertEquals(4000000000L, f.frameId);
        assertEquals(7, f.tabId);
        assertEquals(1280, f.width);
        assertEquals(1234.5f, f.scrollY, 0f);
        assertEquals(321.25f, f.targetScrollTop, 0f);
        assertEquals(9, f.targetId);
        assertEquals(2, f.rects.length);
        assertEquals(640, f.rects[1].x);
        assertEquals(Frame.FORMAT_JPEG, f.rects[1].format);
        assertEquals(Frame.HEADER_SIZE + Frame.RECT_HEADER_SIZE, f.rects[0].offset);
        assertEquals(3, f.rects[0].length);
        assertEquals(Frame.HEADER_SIZE + 2 * Frame.RECT_HEADER_SIZE + 3, f.rects[1].offset);
    }

    @Test
    public void recognisesFullPicture() {
        Frame f = Frame.parse(update(Frame.FLAG_KEYFRAME, 800, 600, new int[][] {{0, 0, 800, 600, Frame.FORMAT_JPEG}}, new int[] {5}));
        assertTrue(f.isKeyframe());
        assertTrue(f.isFullPicture());
    }

    @Test
    public void rejectsMalformedUpdates() {
        assertNull(Frame.parse(new byte[10]));
        assertNull(Frame.parse(new byte[Frame.HEADER_SIZE]));
        byte[] truncated = update(0, 10, 10, new int[][] {{0, 0, 10, 10, 0}}, new int[] {100});
        byte[] cut = new byte[truncated.length - 50];
        System.arraycopy(truncated, 0, cut, 0, cut.length);
        assertNull(Frame.parse(cut));
    }

    @Test
    public void buildsWebSocketUrls() {
        int p = ServerConnection.DEFAULT_PORT;
        assertEquals("wss://192.168.1.20:" + p + "/ws", ServerConnection.urlFor("192.168.1.20"));
        assertEquals("wss://192.168.1.20:9000/ws", ServerConnection.urlFor(" 192.168.1.20:9000 "));
        assertEquals("wss://surfer.lan:" + p + "/ws", ServerConnection.urlFor("wss://surfer.lan/ws"));
        assertEquals("wss://[fe80::1]:" + p + "/ws", ServerConnection.urlFor("[fe80::1]"));
        assertEquals("wss://[fe80::1]:9000/ws", ServerConnection.urlFor("[fe80::1]:9000"));
    }
}
