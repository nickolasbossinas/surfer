package surfer.x10.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import surfer.x10.input.PageInput;
import surfer.x10.net.Frame;

/**
 * Shows the server's picture and turns touches into protocol touch events.
 *
 * Updates must be applied in order (each paints onto the result of the one
 * before), so every update is decoded on one background thread and applied on
 * the main thread in arrival order, painted onto one picture kept in RGB 565:
 * half the memory of 32-bit colour on a 32 MB heap, and the X10's screen shows
 * 16-bit colour anyway.
 */
public class FrameView extends View {
    public interface Listener {
        /** The page area changed size (CSS px and device pixel ratio). */
        void onViewport(float width, float height, float dpr);
        /** A touch event; {@code eventTime} is in SystemClock.uptimeMillis() base. */
        void onTouch(String phase, long eventTime, JSONArray points);
        /** The update was applied; acknowledge it. */
        void onFrameDone(Frame frame);
    }

    private static final Bitmap.Config PICTURE_CONFIG = Bitmap.Config.RGB_565;
    private final Paint scaledPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float density;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    /** The picture on screen; owned by the main thread. Every update is painted onto it. */
    private Bitmap front;
    private Canvas frontCanvas;
    private Listener listener;
    private PageInput pageInput;
    /** Height covered by the soft keyboard, in px; the page area excludes it. */
    private int bottomInset;
    private volatile boolean released;
    // Decode statistics for the debug log (decoder thread writes, main thread reads).
    private volatile long decodeCount;
    private volatile long decodeMsTotal;
    private volatile long decodeMsMax;
    private volatile long rectCount;

    /** The decoded images of one update, ready to apply on the main thread. */
    private static final class Decoded {
        final Frame frame;
        /** Images, parallel to frame.rects (entries may be null if undecodable); null if out of memory. */
        Bitmap[] parts;

        Decoded(Frame frame) {
            this.frame = frame;
        }
    }

    public FrameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = context.getResources().getDisplayMetrics().density;
        setBackgroundColor(Color.WHITE);
        // Takes keyboard focus for the page's text fields.
        setFocusable(true);
        setFocusableInTouchMode(true);
    }

    public void setPageInput(PageInput pageInput) {
        this.pageInput = pageInput;
    }

    @Override
    public boolean onCheckIsTextEditor() {
        return pageInput != null && pageInput.isActive();
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        return pageInput == null ? null : pageInput.createConnection(outAttrs);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK && pageInput != null && pageInput.onKey(event)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode != KeyEvent.KEYCODE_BACK && pageInput != null && pageInput.onKey(event)) return true;
        return super.onKeyUp(keyCode, event);
    }

    /** The soft keyboard covers the bottom {@code px} of this view (0 when closed). */
    public void setBottomInset(int px) {
        if (px == bottomInset) return;
        bottomInset = px;
        reportViewport();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public float density() {
        return density;
    }

    /** Called from the network thread, in arrival order. */
    public void submit(final Frame frame) {
        if (released) return;
        decoder.execute(new Runnable() {
            @Override
            public void run() {
                // Whatever happens, the update is applied (or skipped) and acknowledged:
                // a missing acknowledgement would stop the picture.
                Decoded decoded;
                try {
                    decoded = decode(frame);
                } catch (Throwable t) {
                    decoded = new Decoded(frame);
                }
                final Decoded result = decoded;
                post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            apply(result);
                        } catch (RuntimeException e) {
                            if (listener != null) listener.onFrameDone(frame);
                        }
                    }
                });
            }
        });
    }

    /** Forgets the current picture (e.g. after clearing browsing data). */
    public void clear() {
        if (front != null) front.recycle();
        front = null;
        frontCanvas = null;
        invalidate();
    }

    public void release() {
        released = true;
        decoder.shutdownNow();
    }

    /** "n updates, avg/max decode ms" since the last call. */
    public String takeStats() {
        long n = decodeCount, total = decodeMsTotal, max = decodeMsMax, rects = rectCount;
        decodeCount = 0;
        decodeMsTotal = 0;
        decodeMsMax = 0;
        rectCount = 0;
        return n + " updates (" + rects + " rects) decoded, avg " + (n > 0 ? total / n : 0) + " ms, max " + max + " ms";
    }

    // Runs on the decoder thread.
    private Decoded decode(Frame frame) {
        long started = SystemClock.uptimeMillis();
        Decoded d = new Decoded(frame);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = PICTURE_CONFIG;
        options.inDither = false;
        try {
            d.parts = new Bitmap[frame.rects.length];
            for (int i = 0; i < frame.rects.length; i++) {
                Frame.Rect r = frame.rects[i];
                d.parts[i] = BitmapFactory.decodeByteArray(frame.data, r.offset, r.length, options);
            }
        } catch (OutOfMemoryError e) {
            recycleAll(d.parts);
            d.parts = null;
        }
        long ms = SystemClock.uptimeMillis() - started;
        decodeCount++;
        rectCount += frame.rects.length;
        decodeMsTotal += ms;
        if (ms > decodeMsMax) decodeMsMax = ms;
        return d;
    }

    private static void recycleAll(Bitmap[] bitmaps) {
        if (bitmaps == null) return;
        for (Bitmap b : bitmaps) if (b != null) b.recycle();
    }

    // Runs on the main thread, in update order.
    private void apply(Decoded d) {
        if (released) {
            recycleAll(d.parts);
            return;
        }
        Frame frame = d.frame;
        if (d.parts != null) {
            if (front == null || front.getWidth() != frame.width || front.getHeight() != frame.height) {
                // New size (rotation, keyboard): a new picture; it starts white until painted.
                if (front != null) front.recycle();
                front = null;
                frontCanvas = null;
                try {
                    front = Bitmap.createBitmap(frame.width, frame.height, PICTURE_CONFIG);
                    front.eraseColor(Color.WHITE);
                    frontCanvas = new Canvas(front);
                } catch (OutOfMemoryError e) {
                    recycleAll(d.parts);
                    if (listener != null) listener.onFrameDone(frame);
                    return;
                }
            }
            for (int i = 0; i < d.parts.length; i++) {
                Bitmap part = d.parts[i];
                if (part == null) continue;
                frontCanvas.drawBitmap(part, frame.rects[i].x, frame.rects[i].y, null);
                part.recycle();
            }
            invalidate();
        }
        if (listener != null) listener.onFrameDone(frame);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Bitmap b = front;
        if (b == null || b.isRecycled()) return;
        float scale = getWidth() / (float) b.getWidth();
        if (Math.abs(scale - 1f) < 0.001f) {
            canvas.drawBitmap(b, 0, 0, null);
        } else {
            // Only while a rotation or resize is in progress.
            canvas.save();
            canvas.scale(scale, scale);
            canvas.drawBitmap(b, 0, 0, scaledPaint);
            canvas.restore();
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        reportViewport();
    }

    /** Reports the page area: the view minus what the keyboard covers. */
    public void reportViewport() {
        int h = getHeight() - bottomInset;
        if (listener != null && getWidth() > 0 && h > 0) listener.onViewport(getWidth() / density, h / density, density);
    }

    /** The page area height in px (view height minus keyboard). */
    public int pageHeight() {
        return getHeight() - bottomInset;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null) return false;
        // getActionMasked()/getActionIndex() are Android 2.2; these masks are 2.0.
        // Android 1.6 has a single pointer and no masks.
        int action = MULTITOUCH ? e.getAction() & MotionEvent.ACTION_MASK : e.getAction();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                listener.onTouch("start", e.getEventTime(), points(e, -1, -1));
                break;
            case MotionEvent.ACTION_MOVE:
                // Historical samples give Chromium the real finger path and speed (fling).
                for (int h = 0; h < e.getHistorySize(); h++) {
                    listener.onTouch("move", e.getHistoricalEventTime(h), points(e, h, -1));
                }
                listener.onTouch("move", e.getEventTime(), points(e, -1, -1));
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                listener.onTouch("move", e.getEventTime(), points(e, -1, -1));
                break;
            case MotionEvent.ACTION_POINTER_UP:
                listener.onTouch("move", e.getEventTime(), points(e, -1, pointerIndex(e)));
                break;
            case MotionEvent.ACTION_UP:
                listener.onTouch("end", e.getEventTime(), new JSONArray());
                break;
            case MotionEvent.ACTION_CANCEL:
                listener.onTouch("cancel", e.getEventTime(), new JSONArray());
                break;
            default:
                return false;
        }
        return true;
    }

    /** Multi-pointer MotionEvents are Android 2.0 (API 5). */
    private static final boolean MULTITOUCH = Build.VERSION.SDK_INT >= 5;

    @SuppressWarnings("deprecation")
    private static int pointerIndex(MotionEvent e) {
        if (Build.VERSION.SDK_INT < 5) return 0;
        return (e.getAction() & MotionEvent.ACTION_POINTER_ID_MASK) >> MotionEvent.ACTION_POINTER_ID_SHIFT;
    }

    /** All pointers of the event (or of one historical sample), minus {@code skipIndex}. */
    private JSONArray points(MotionEvent e, int history, int skipIndex) {
        JSONArray out = new JSONArray();
        try {
            if (Build.VERSION.SDK_INT < 5) {
                float x = history < 0 ? e.getX() : e.getHistoricalX(history);
                float y = history < 0 ? e.getY() : e.getHistoricalY(history);
                out.put(new JSONObject().put("id", 0).put("x", round(x / density)).put("y", round(y / density)));
                return out;
            }
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (i == skipIndex) continue;
                float x = history < 0 ? e.getX(i) : e.getHistoricalX(i, history);
                float y = history < 0 ? e.getY(i) : e.getHistoricalY(i, history);
                out.put(new JSONObject()
                        .put("id", e.getPointerId(i))
                        .put("x", round(x / density))
                        .put("y", round(y / density)));
            }
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static double round(float v) {
        return Math.round(v * 100) / 100.0;
    }
}
