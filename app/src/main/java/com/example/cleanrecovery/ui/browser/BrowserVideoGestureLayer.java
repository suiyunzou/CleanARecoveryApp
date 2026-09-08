package com.example.cleanrecovery.ui.browser;

import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Full-screen video gestures matching Via: seek, brightness, and media volume. */
public final class BrowserVideoGestureLayer extends FrameLayout {
    public interface Callbacks {
        void seek(int seconds);
        int changeBrightness(float delta);
        int changeVolume(float delta);
    }

    private final boolean enabled;
    private final Callbacks callbacks;
    private final TextView indicator;
    private float downX, downY, lastX, lastY;
    private int mode;

    public BrowserVideoGestureLayer(android.content.Context context, View content,
                                    boolean enabled, Callbacks callbacks) {
        super(context);
        this.enabled = enabled;
        this.callbacks = callbacks;
        addView(content, new LayoutParams(-1, -1));
        indicator = new TextView(context);
        indicator.setTextColor(Color.WHITE);
        indicator.setTextSize(18);
        indicator.setGravity(Gravity.CENTER);
        indicator.setBackgroundColor(0x99000000);
        indicator.setPadding(dp(20), dp(12), dp(20), dp(12));
        indicator.setVisibility(GONE);
        LayoutParams params = new LayoutParams(-2, -2, Gravity.CENTER);
        addView(indicator, params);
    }

    private int dp(float value) { return ViaUi.dp(getContext(), value); }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!enabled) return super.dispatchTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = event.getX(); downY = lastY = event.getY(); mode = 0;
                if (downY < dp(48) || downY > getHeight() - dp(72)
                        || downX < dp(24) || downX > getWidth() - dp(48)) mode = -1;
                break;
            case MotionEvent.ACTION_MOVE:
                if (mode < 0) break;
                float dx = event.getX() - lastX, dy = event.getY() - lastY;
                lastX = event.getX(); lastY = event.getY();
                if (mode == 0 && (Math.abs(dx) > dp(8) || Math.abs(dy) > dp(8))) {
                    if (Math.abs(dy) > Math.abs(dx) * 1.4f && downX < getWidth() / 3f) mode = 1;
                    else if (Math.abs(dy) > Math.abs(dx) * 1.4f && downX > getWidth() * 2f / 3f) mode = 2;
                    else if (Math.abs(dx) > Math.abs(dy) * 3f) mode = 3;
                    else mode = -1;
                    if (mode > 0) {
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel);
                        cancel.recycle();
                    }
                }
                if (mode == 1) show(callbacks.changeBrightness(-dy * 2f / getHeight()) + "%");
                else if (mode == 2) show(callbacks.changeVolume(-dy * 2f / getHeight()) + "%");
                else if (mode == 3) {
                    int seconds = (int) (((event.getX() - downX) / getWidth()) * 300);
                    show(String.format(java.util.Locale.ROOT, "%s%02d:%02d", seconds >= 0 ? "+" : "-",
                            Math.abs(seconds / 60), Math.abs(seconds % 60)));
                }
                if (mode > 0) return true;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (mode == 3) callbacks.seek((int) (((event.getX() - downX) / getWidth()) * 300));
                if (mode > 0) {
                    indicator.setVisibility(GONE);
                    mode = 0;
                    return true;
                }
                mode = 0;
                break;
        }
        return super.dispatchTouchEvent(event);
    }

    private void show(String text) {
        indicator.setText(text);
        indicator.setVisibility(VISIBLE);
        indicator.bringToFront();
    }
}
