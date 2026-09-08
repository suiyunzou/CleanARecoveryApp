package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.graphics.Color;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Via's reader extraction and removable overlay, scoped to each WebView document. */
public final class BrowserReader {
    private final String script;
    private final String textScript;

    public BrowserReader(Context context) {
        script = load(context, "browser_reader.js");
        textScript = load(context, "browser_text.js");
    }

    private static String load(Context context, String name) {
        try (InputStream input = context.getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Missing bundled reader script", error);
        }
    }

    public void run(WebView web, String action, BrowserPrefs prefs, ValueCallback<String> callback) {
        if ("text".equals(action)) { web.evaluateJavascript(textScript, callback); return; }
        web.evaluateJavascript("(function(action,appearance){" + script + "})("
                + JSONObject.quote(action) + "," + JSONObject.quote(appearance(prefs)) + ");", callback);
    }

    public static String appearance(BrowserPrefs prefs) {
        int[] colors = themeColors();
        int color = colors[Math.max(0, Math.min(colors.length - 1, prefs.readerTheme()))];
        int dark = Color.rgb((int) (Color.red(color) * .3f),
                (int) (Color.green(color) * .3f), (int) (Color.blue(color) * .3f));
        return palette(color) + ".via-reader-body{font-size:"
                + Math.max(8, Math.min(84, prefs.readerFont())) + "px!important;}"
                + "@media(prefers-color-scheme:dark){" + palette(dark) + "}" + prefs.readerCss();
    }

    public static int[] themeColors() {
        return new int[]{Color.WHITE, -462365, -4133433, -11908531, -15592942};
    }

    private static String palette(int color) {
        boolean light = Color.red(color) * .299 + Color.green(color) * .587
                + Color.blue(color) * .114 >= 192;
        return String.format(Locale.ROOT,
                ".via-reader-body,.via-reader-body>div{background-color:#%06x!important;}"
                        + ".via-reader-body{color:%s!important;}", color & 0xffffff, light ? "#000" : "#fff");
    }
}
