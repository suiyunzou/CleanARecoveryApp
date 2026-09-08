package com.example.cleanrecovery.ui.browser;

/** Internal home markers are UI routes, never WebView network destinations. */
public final class BrowserInternalUrls {
    private BrowserInternalUrls() {}
    public static boolean isHome(String value) {
        if (value == null || value.trim().isEmpty()) return true;
        String url = value.trim().toLowerCase(java.util.Locale.ROOT);
        return url.equals("chrome://home") || url.equals("chrome://home/")
                || url.equals("about:home");
    }
}
