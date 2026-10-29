package surfer.tablet.view;

import android.app.Activity;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.PopupWindow;

/**
 * Reports where the soft keyboard's top edge is on screen.
 *
 * The browser window does not resize for the keyboard (adjustNothing), and for
 * such windows Android reports no keyboard at all, neither in the window size
 * nor in its visible display frame. So an invisible, zero-width popup window
 * that does resize (adjustResize) spans the screen height; its bottom edge is
 * the keyboard's top. This works in full-screen mode too, where adjustResize
 * windows are not resized but their visible frame still is.
 */
public final class KeyboardWatcher implements ViewTreeObserver.OnGlobalLayoutListener {
    public interface Listener {
        /** The keyboard's top edge in screen coordinates moved ({@link Integer#MAX_VALUE} when closed). */
        void onKeyboardTop(int top);
    }

    private final Activity activity;
    private final Listener listener;
    private final View probe;
    private final PopupWindow popup;
    private final Rect visible = new Rect();
    private final int[] location = new int[2];
    private final Point screen = new Point();
    /** A smaller gap at the bottom is a system bar, not a keyboard. */
    private final int minKeyboardPx;
    private int top = Integer.MAX_VALUE;

    public KeyboardWatcher(Activity activity, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        minKeyboardPx = Math.round(100 * activity.getResources().getDisplayMetrics().density);
        probe = new View(activity);
        popup = new PopupWindow(probe, 0, ViewGroup.LayoutParams.MATCH_PARENT);
        popup.setBackgroundDrawable(new ColorDrawable(0));
        popup.setTouchable(false);
        popup.setFocusable(false);
        popup.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        popup.setInputMethodMode(PopupWindow.INPUT_METHOD_NEEDED);
        probe.getViewTreeObserver().addOnGlobalLayoutListener(this);
    }

    /** Shows the probe once the activity window exists. */
    public void start() {
        final View root = activity.getWindow().getDecorView();
        root.post(new Runnable() {
            @Override
            public void run() {
                if (!popup.isShowing() && root.getWindowToken() != null && !activity.isFinishing()) {
                    popup.showAtLocation(root, Gravity.NO_GRAVITY, 0, 0);
                }
            }
        });
    }

    public void stop() {
        popup.dismiss();
    }

    /** The keyboard's top edge on screen, or {@link Integer#MAX_VALUE} when it is closed. */
    public int keyboardTop() {
        return top;
    }

    @Override
    public void onGlobalLayout() {
        probe.getWindowVisibleDisplayFrame(visible);
        probe.getLocationOnScreen(location);
        int bottom = Math.min(visible.bottom, location[1] + probe.getHeight());
        activity.getWindowManager().getDefaultDisplay().getRealSize(screen);
        int next = screen.y - bottom >= minKeyboardPx ? bottom : Integer.MAX_VALUE;
        if (next == top) return;
        top = next;
        listener.onKeyboardTop(top);
    }
}
