package com.example.cleanrecovery.background;

import android.webkit.CookieManager;
import com.example.cleanrecovery.download.DownloadProgressCallback;
import com.example.cleanrecovery.download.UniversalDownloadManager;
import com.example.cleanrecovery.ui.browser.BrowserScriptHttp;
import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.List;
import java.util.Map;

/** Downloads the selected browser resource as bytes, using its browser session and current proxy. */
public final class BrowserFileDownloader extends UniversalDownloadManager {
    private static final int MAX_REDIRECTS = 10;
    private final Proxy proxyOverride;
    private Proxy downloadProxy;

    public BrowserFileDownloader() { this(null); }

    /** A fixed proxy lets local transport tests leave the user's proxy untouched. */
    BrowserFileDownloader(Proxy proxy) { proxyOverride = proxy; }

    @Override public void download(String fileUrl, File outFile, Map<String, String> headers,
                                   DownloadProgressCallback callback) throws IOException {
        downloadProxy = proxyOverride == null ? BrowserScriptHttp.currentProxy() : proxyOverride;
        try {
            super.download(fileUrl, outFile, headers, callback);
        } finally {
            downloadProxy = null;
        }
    }

    @Override protected HttpURLConnection openConnection(String fileUrl, Map<String, String> headers, long resumeLen)
            throws IOException {
        URL current = new URL(fileUrl);
        boolean forwardAuthorization = true;
        CookieManager cookies = CookieManager.getInstance();
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            if (isCancelled()) throw new IOException("download cancelled");
            if (!"http".equals(current.getProtocol()) && !"https".equals(current.getProtocol()))
                throw new IOException("Only HTTP(S) browser downloads are supported");
            // Retain this download's route if its proxy stops between redirects or retries.
            HttpURLConnection connection = (HttpURLConnection) current.openConnection(downloadProxy);
            boolean keep = false;
            try {
                connection.setConnectTimeout(connectTimeout);
                connection.setReadTimeout(readTimeout);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                if (headers != null) for (Map.Entry<String, String> header : headers.entrySet()) {
                    String name = header.getKey(), value = header.getValue();
                    if (name == null || value == null || "Cookie".equalsIgnoreCase(name)
                            || "Cookie2".equalsIgnoreCase(name) || "Host".equalsIgnoreCase(name)
                            || "Range".equalsIgnoreCase(name) || "Content-Length".equalsIgnoreCase(name)
                            || (!forwardAuthorization && "Authorization".equalsIgnoreCase(name))) continue;
                    connection.setRequestProperty(name, value);
                }
                connection.setRequestProperty("Accept-Encoding", "identity");
                if (resumeLen > 0) connection.setRequestProperty("Range", "bytes=" + resumeLen + "-");
                String cookie = cookies.getCookie(current.toString());
                if (cookie != null && !cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
                int status = connection.getResponseCode();
                boolean changedCookies = false;
                for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                    if (!"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
                    for (String value : header.getValue()) cookies.setCookie(current.toString(), value);
                    changedCookies = true;
                }
                if (changedCookies) cookies.flush();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new IOException("Redirect has no Location");
                    if (redirects == MAX_REDIRECTS) throw new IOException("Too many redirects");
                    URL next = new URL(current, location);
                    if (!sameOrigin(current, next)) forwardAuthorization = false;
                    current = next;
                } else {
                    keep = true;
                    return connection;
                }
            } finally {
                if (!keep) connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private boolean sameOrigin(URL first, URL second) {
        int firstPort = first.getPort() < 0 ? first.getDefaultPort() : first.getPort();
        int secondPort = second.getPort() < 0 ? second.getDefaultPort() : second.getPort();
        return first.getProtocol().equalsIgnoreCase(second.getProtocol())
                && first.getHost().equalsIgnoreCase(second.getHost()) && firstPort == secondPort;
    }
}
