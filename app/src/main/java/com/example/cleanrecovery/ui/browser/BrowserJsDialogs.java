package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.net.Uri;
import android.os.SystemClock;
import android.webkit.JsResult;
import android.webkit.WebView;
import com.example.cleanrecovery.R;

/** Process-wide host policy, matching Via's g8.b0 message callbacks. */
public final class BrowserJsDialogs {
    private static final BrowserJsDialogPolicy POLICY = new BrowserJsDialogPolicy();

    public static boolean show(Activity activity, WebView view, String url, String message,
                               boolean confirm, JsResult result) {
        if (!view.isShown()) { result.cancel(); return true; }
        String host = Uri.parse(url == null ? "" : url).getHost();
        String key = host == null || host.isEmpty() ? "default" : host;
        int mode = POLICY.request(key, confirm, SystemClock.elapsedRealtime());
        if (mode == 2) { result.cancel(); return true; }
        String title = "default".equals(key) ? activity.getString(R.string.via_js_message_title)
                : activity.getString(R.string.via_js_message_from, host);
        android.app.Dialog dialog = ViaUi.jsMessageDialog(activity, title, message, confirm, mode == 1, (accepted, suppress) -> {
            if (accepted) result.confirm(); else result.cancel();
            if (suppress != null) POLICY.answer(key, confirm, mode == 1 && suppress, SystemClock.elapsedRealtime());
        });
        cancelOnDetach(view, dialog);
        return true;
    }

    public static boolean prompt(Activity activity, WebView view, String url, String message,
                                 String initial, android.webkit.JsPromptResult result) {
        if (!view.isShown()) { result.cancel(); return true; }
        if (message != null && message.startsWith("BdboxApp:{\"obj\":\"")) {
            result.confirm("");
            return true;
        }
        String host = Uri.parse(url == null ? "" : url).getHost();
        String title = host == null || host.isEmpty() ? activity.getString(R.string.via_js_message_title)
                : activity.getString(R.string.via_js_message_from, host);
        android.app.Dialog dialog = ViaUi.jsPromptDialog(activity, title, message, initial, value -> {
            if (value == null) result.cancel(); else result.confirm(value);
        });
        cancelOnDetach(view, dialog);
        return true;
    }

    public static boolean beforeUnload(Activity activity, WebView view, String message, JsResult result) {
        if (!view.isShown()) { result.confirm(); return true; }
        android.app.Dialog dialog = ViaUi.jsMessageDialog(activity,
                activity.getString(R.string.via_js_leave_title), message, true, false,
                activity.getString(R.string.via_js_stay), activity.getString(R.string.via_js_leave),
                (accepted, ignored) -> { if (accepted) result.confirm(); else result.cancel(); });
        cancelOnDetach(view, dialog);
        return true;
    }

    private static void cancelOnDetach(WebView view, android.app.Dialog dialog) {
        android.view.View.OnAttachStateChangeListener attachment = new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View v) { }
            @Override public void onViewDetachedFromWindow(android.view.View v) { dialog.cancel(); }
        };
        view.addOnAttachStateChangeListener(attachment);
        dialog.setOnDismissListener(d -> view.removeOnAttachStateChangeListener(attachment));
    }
}
