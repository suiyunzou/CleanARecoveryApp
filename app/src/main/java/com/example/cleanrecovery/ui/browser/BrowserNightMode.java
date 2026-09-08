package com.example.cleanrecovery.ui.browser;

import android.webkit.WebView;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;

/** Via page dark CSS plus the supported WebView darkening API. */
public final class BrowserNightMode {
    private BrowserNightMode() { }
    private static String css;
    public static void apply(WebView web, boolean enabled) {
        web.getContext().getTheme().applyStyle(enabled ? com.example.cleanrecovery.R.style.ViaWebDark
                : com.example.cleanrecovery.R.style.ViaWebLight, true);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.getSettings(), enabled);
            web.evaluateJavascript("document.querySelectorAll('style[data-via-night]').forEach(function(s){s.remove()})", null);
            return;
        }
        else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK))
            WebSettingsCompat.setForceDark(web.getSettings(), enabled ? WebSettingsCompat.FORCE_DARK_ON : WebSettingsCompat.FORCE_DARK_OFF);
        if (css == null) try (java.io.InputStream input = web.getContext().getAssets().open("browser_night.css")) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            css = output.toString("UTF-8");
        } catch (java.io.IOException error) { throw new IllegalStateException("Missing night stylesheet", error); }
        web.evaluateJavascript("(function(){document.querySelectorAll('style[data-via-night]').forEach(function(s){s.remove()});"
                + (enabled ? "var s=document.createElement('style');s.setAttribute('data-via-night','1');s.textContent="
                + JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s);" : "") + "})()", null);
    }
}
