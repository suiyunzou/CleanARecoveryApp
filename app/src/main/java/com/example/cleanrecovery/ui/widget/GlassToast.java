package com.example.cleanrecovery.ui.widget;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;
import android.widget.TextView;
import com.example.cleanrecovery.R;
import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/** Foreground, text-only transient feedback. Does not take focus or consume touches. */
public final class GlassToast {
    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> foreground = new WeakReference<>(null);
    private static GlassToast active;
    private static GlassToast pending;
    private static boolean initialized;
    private final CharSequence message;
    private final int duration;
    private long requestedAt;
    private Dialog dialog;
    private final Runnable hide = this::dismiss;

    private GlassToast(CharSequence message, int duration) {
        this.message = message == null ? "" : message.toString();
        this.duration = duration;
    }
    public static GlassToast makeText(Context context, int message, int duration) {
        return makeText(context, context.getText(message), duration);
    }
    public static GlassToast makeText(Context context, CharSequence message, int duration) {
        return new GlassToast(message, duration);
    }
    public static void initialize(Application application) {
        if (initialized) return;
        initialized = true;
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                foreground = new WeakReference<>(activity);
                GlassToast next = pending; pending = null;
                if (next != null && SystemClock.uptimeMillis() - next.requestedAt < 3500) next.present(activity);
            }
            @Override public void onActivityPaused(Activity activity) {
                if (foreground.get() == activity) {
                    foreground.clear();
                    if (active != null) active.dismiss();
                }
            }
            @Override public void onActivityDestroyed(Activity activity) {
                if (active != null && active.dialog != null && active.dialog.getOwnerActivity() == activity) active.dismiss();
            }
            @Override public void onActivityCreated(Activity activity, Bundle state) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        });
    }
    public void show() {
        MAIN.post(() -> {
            if (message.length() == 0) return;
            requestedAt = SystemClock.uptimeMillis();
            Activity activity = foreground.get();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                pending = this;
                MAIN.postDelayed(() -> { if (pending == this) pending = null; }, 3500);
                return;
            }
            present(activity);
        });
    }
    public void cancel() { MAIN.post(this::dismiss); }

    private void present(Activity activity) {
        if (active != null) active.dismiss();
        active = this;
        boolean dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
        TextView label = new TextView(activity);
        label.setText(message);
        label.setTextSize(14);
        label.setTextColor(dark ? 0xfff5f5f7 : 0xff26262b);
        label.setGravity(Gravity.CENTER);
        label.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        int availableWidth = activity.getWindow().getDecorView().getWidth();
        if (availableWidth <= 0) availableWidth = activity.getResources().getDisplayMetrics().widthPixels;
        label.setMaxWidth(Math.min(dp(activity, 360), Math.max(dp(activity, 80), availableWidth - dp(activity, 48))));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        label.setTag("glass-toast-message");
        dialog = new Dialog(activity, R.style.GlassToastTheme);
        dialog.setOwnerActivity(activity);
        dialog.setContentView(label);
        dialog.setCancelable(false);
        Window window = dialog.getWindow();
        if (window == null) { dismiss(); return; }
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(activity, 18));
        background.setStroke(dp(activity, 1), dark ? 0x30ffffff : 0xb3ffffff);
        window.setBackgroundDrawable(background);
        background.setColor(dark ? 0xe6292930 : 0xe6fafafe);
        window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = WindowManager.LayoutParams.WRAP_CONTENT;
        params.y = dp(activity, 72);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
            if (insets != null && insets.isVisible(WindowInsets.Type.ime())) {
                params.y = Math.max(params.y, insets.getInsets(WindowInsets.Type.ime()).bottom
                    - insets.getInsets(WindowInsets.Type.systemBars()).bottom + dp(activity, 16));
            }
        }
        window.setAttributes(params);
        try { dialog.show(); }
        catch (WindowManager.BadTokenException | IllegalStateException error) { dismiss(); return; }
        if (Build.VERSION.SDK_INT >= 31) Api31.attachBlur(activity, dialog, background, dark);
        AccessibilityManager accessibility = activity.getSystemService(AccessibilityManager.class);
        int timeout = duration == LENGTH_LONG ? 3500 : 2000;
        if (Build.VERSION.SDK_INT >= 29 && accessibility != null)
            timeout = accessibility.getRecommendedTimeoutMillis(timeout, AccessibilityManager.FLAG_CONTENT_TEXT);
        MAIN.postDelayed(hide, timeout);
    }
    private void dismiss() {
        MAIN.removeCallbacks(hide);
        if (pending == this) pending = null;
        if (dialog != null) { dialog.dismiss(); dialog = null; }
        if (active == this) active = null;
    }
    /** Keep newer platform types out of the API 23 fallback path. */
    @android.annotation.TargetApi(31)
    private static final class Api31 {
        static void attachBlur(Activity activity, Dialog dialog, GradientDrawable background, boolean dark) {
            Window window = dialog.getWindow();
            WindowManager manager = activity.getSystemService(WindowManager.class);
            Consumer<Boolean> changed = enabled -> {
                background.setColor(dark ? (enabled ? 0xb3292930 : 0xe6292930) : (enabled ? 0xb8ffffff : 0xe6fafafe));
                window.setBackgroundBlurRadius(enabled ? dp(activity, 18) : 0);
            };
            changed.accept(manager.isCrossWindowBlurEnabled());
            manager.addCrossWindowBlurEnabledListener(command -> MAIN.post(command), changed);
            dialog.setOnDismissListener(ignored -> manager.removeCrossWindowBlurEnabledListener(changed));
        }
    }
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
