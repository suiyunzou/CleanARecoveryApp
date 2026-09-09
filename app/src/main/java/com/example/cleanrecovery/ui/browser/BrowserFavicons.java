package com.example.cleanrecovery.ui.browser;

import android.graphics.Bitmap;
import android.net.Uri;
import android.util.LruCache;

/** Small in-memory cache of icons supplied by visited pages; no extra network requests. */
public final class BrowserFavicons {
    private static final LruCache<String, Bitmap> ICONS = new LruCache<>(128);
    private BrowserFavicons() {}
    public static void put(String url, Bitmap icon) {
        String host = host(url);
        if (host != null && icon != null && !icon.isRecycled()) {
            ICONS.put(host, Bitmap.createScaledBitmap(icon, 48, 48, true));
        }
    }
    public static Bitmap get(String url) {
        String host = host(url);
        return host == null ? null : ICONS.get(host);
    }
    private static String host(String url) {
        if (url == null) return null;
        String host = Uri.parse(url).getHost();
        return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
    }
}
