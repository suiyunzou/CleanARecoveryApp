package com.example.cleanrecovery.ui.browser;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/** Observe horizontal swipes without replacing toolbar clicks or long presses. */
public final class BrowserToolbarGestures {
    private BrowserToolbarGestures() { }

    public static void bind(View root, java.util.function.IntConsumer swipe) {
        float[] down = new float[2];
        View.OnTouchListener listener = (view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                down[0] = event.getRawX(); down[1] = event.getRawY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                float dx = event.getRawX() - down[0], dy = event.getRawY() - down[1];
                if (Math.abs(dx) > ViaUi.dp(view.getContext(), 48) && Math.abs(dx) > Math.abs(dy) * 2) {
                    swipe.accept(dx > 0 ? 1 : -1);
                    return true;
                }
            }
            return false;
        };
        attach(root, listener);
    }

    private static void attach(View view, View.OnTouchListener listener) {
        view.setOnTouchListener(listener);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) attach(group.getChildAt(i), listener);
        }
    }
}
