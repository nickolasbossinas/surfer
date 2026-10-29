package surfer.x10.net;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A screen update from the server (DESIGN.md §5.4): a 60-byte header and a list
 * of rectangles, each an encoded image to paint at (x, y) onto the picture.
 * A keyframe replaces the whole picture.
 */
public final class Frame {
    public static final int HEADER_SIZE = 60;
    public static final int RECT_HEADER_SIZE = 16;
    public static final int MSG_FRAME = 1;
    public static final int FORMAT_JPEG = 0;
    public static final int FORMAT_PNG = 1;
    public static final int FORMAT_WEBP = 2;
    public static final int FLAG_KEYFRAME = 1;
    public static final int FLAG_SCROLL_TARGET = 2;
    public static final int FLAG_REFINE = 4;

    /** One rectangle; its image is {@code data[offset .. offset+length)}. */
    public static final class Rect {
        public final int x, y, w, h, format, offset, length;

        Rect(int x, int y, int w, int h, int format, int offset, int length) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.format = format;
            this.offset = offset;
            this.length = length;
        }
    }

    public final int flags;
    public final long frameId;
    public final long tabId;
    /** Size of the whole picture, physical pixels. */
    public final int width;
    public final int height;
    public final float pageScale;
    public final float scrollX;
    public final float scrollY;
    public final float offsetTop;
    /** Scroll target (valid when FLAG_SCROLL_TARGET is set), CSS px. */
    public final float targetX, targetY, targetW, targetH, targetScrollLeft, targetScrollTop;
    public final long targetId;
    public final Rect[] rects;
    public final byte[] data;

    private Frame(ByteBuffer b, byte[] data, Rect[] rects) {
        flags = b.get(1) & 0xff;
        frameId = b.getInt(4) & 0xffffffffL;
        tabId = b.getInt(8) & 0xffffffffL;
        width = b.getShort(12) & 0xffff;
        height = b.getShort(14) & 0xffff;
        pageScale = b.getFloat(16);
        scrollX = b.getFloat(20);
        scrollY = b.getFloat(24);
        offsetTop = b.getFloat(28);
        targetX = b.getFloat(32);
        targetY = b.getFloat(36);
        targetW = b.getFloat(40);
        targetH = b.getFloat(44);
        targetScrollLeft = b.getFloat(48);
        targetScrollTop = b.getFloat(52);
        targetId = b.getInt(56) & 0xffffffffL;
        this.rects = rects;
        this.data = data;
    }

    /** Returns null if the message is not a well-formed update. */
    public static Frame parse(byte[] data) {
        if (data.length < HEADER_SIZE || data[0] != MSG_FRAME) return null;
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int count = b.getShort(2) & 0xffff;
        Rect[] rects = new Rect[count];
        int o = HEADER_SIZE;
        for (int i = 0; i < count; i++) {
            if (o + RECT_HEADER_SIZE > data.length) return null;
            int length = b.getInt(o + 12);
            if (length < 0 || o + RECT_HEADER_SIZE + length > data.length) return null;
            rects[i] = new Rect(b.getShort(o) & 0xffff, b.getShort(o + 2) & 0xffff, b.getShort(o + 4) & 0xffff,
                    b.getShort(o + 6) & 0xffff, b.get(o + 8) & 0xff, o + RECT_HEADER_SIZE, length);
            o += RECT_HEADER_SIZE + length;
        }
        return new Frame(b, data, rects);
    }

    public boolean isKeyframe() {
        return (flags & FLAG_KEYFRAME) != 0;
    }

    public boolean hasScrollTarget() {
        return (flags & FLAG_SCROLL_TARGET) != 0;
    }

    /** True if the update is one image covering the whole picture. */
    public boolean isFullPicture() {
        return rects.length == 1 && rects[0].x == 0 && rects[0].y == 0 && rects[0].w == width && rects[0].h == height;
    }
}
