package com.example.cleanrecovery.util;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.net.HttpURLConnection;
import java.net.URL;

/** Network boundary for fetching quick-link favicons outside Activity code. */
public final class FaviconFetcher {
    private FaviconFetcher() {
    }

    public static Bitmap fetch(String host) {
        if (host == null || host.isEmpty()) return null;
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://www.google.com/s2/favicons?domain="
                    + java.net.URLEncoder.encode(host, "UTF-8") + "&sz=64");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                            + "Chrome/120.0.0.0 Mobile");
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            return BitmapFactory.decodeStream(connection.getInputStream());
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
