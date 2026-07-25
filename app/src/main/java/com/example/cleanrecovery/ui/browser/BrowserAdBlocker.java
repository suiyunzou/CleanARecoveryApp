package com.example.cleanrecovery.ui.browser;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Lightweight request blocker used by the VIA-style browser. */
public final class BrowserAdBlocker extends WebViewClient {
    private static final List<String> KNOWN_AD_HOSTS = Arrays.asList(
            "doubleclick.net",
            "googlesyndication.com",
            "googlesyndication-cn.com",
            "googleadservices.com",
            "adservice.google.com",
            "pagead2.googlesyndication.com",
            "googleads.g.doubleclick.net",
            "amazon-adsystem.com",
            "scorecardresearch.com",
            "taboola.com",
            "outbrain.com",
            "adsystem.com",
            "adnxs.com",
            "adform.net",
            "criteo.com",
            "mediavine.com");

    private static final List<String> KNOWN_AD_URL_PARTS = Arrays.asList(
            "/pagead/", "/ads?", "/ads/", "/adserver/", "/advert/",
            "adsbygoogle", "doubleclick", "googleadservices", "googlesyndication",
            "banner_ad", "interstitial", "adunit", "adslot", "sponsor");

    private final BrowserPrefs prefs;
    private final AtomicInteger blockedCount = new AtomicInteger();

    public BrowserAdBlocker(BrowserPrefs prefs) {
        this.prefs = prefs;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(
            WebView view, WebResourceRequest request) {
        if (!prefs.adBlockEnabled() || request == null || request.isForMainFrame()) {
            return null;
        }
        String pageHost = null;
        try {
            String pageUrl = view == null ? null : view.getUrl();
            pageHost = pageUrl == null ? null : Uri.parse(pageUrl).getHost();
        } catch (Exception ignored) {
        }
        if (prefs.siteAdBlockOff(pageHost)) return null;
        Uri uri = request.getUrl();
        String url = uri == null ? null : uri.toString();
        String host = uri == null ? null : uri.getHost();
        if (url != null && url.endsWith("via_inject_blocker.css")) {
            String css = prefs.cosmeticCssForHost(pageHost);
            if (!css.isEmpty()) {
                return textResponse("text/css", css);
            }
        }
        if (!isBlocked(host, url, prefs.blockedHosts(), prefs.blockedUrlRules())) return null;
        blockedCount.incrementAndGet();
        return textResponse("text/plain", "");
    }

    public int blockedCount() {
        return blockedCount.get();
    }

    static boolean isBlockedHost(String host, Set<String> customHosts) {
        if (host == null || host.isEmpty()) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        for (String known : KNOWN_AD_HOSTS) {
            if (matchesHost(normalized, known)) return true;
        }
        for (String custom : customHosts) {
            if (custom != null && matchesHost(normalized, custom.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isBlocked(String host, String url, Set<String> customHosts, Set<String> customRules) {
        if (isBlockedHost(host, customHosts)) return true;
        String lowerUrl = url == null ? "" : url.toLowerCase(Locale.ROOT);
        for (String part : KNOWN_AD_URL_PARTS) {
            if (!part.isEmpty() && lowerUrl.contains(part)) return true;
        }
        for (String rule : customRules) {
            if (matchesRule(host, lowerUrl, rule)) return true;
        }
        return false;
    }

    private static boolean matchesHost(String host, String rule) {
        if (host == null || rule == null || rule.isEmpty()) return false;
        String r = rule.startsWith("||") ? rule.substring(2) : rule;
        if (r.endsWith("^")) r = r.substring(0, r.length() - 1);
        while (r.startsWith(".")) r = r.substring(1);
        return host.equals(r) || host.endsWith("." + r);
    }

    private static boolean matchesRule(String host, String lowerUrl, String rule) {
        if (rule == null || rule.isEmpty()) return false;
        String r = rule.toLowerCase(Locale.ROOT).trim();
        if (r.startsWith("||")) return matchesHost(host, r);
        if (r.startsWith("*") && r.endsWith("*") && r.length() > 2) {
            return lowerUrl.contains(r.substring(1, r.length() - 1));
        }
        if (r.contains("://") || r.startsWith("/")) return lowerUrl.contains(r.replace("*", ""));
        return matchesHost(host, r) || lowerUrl.contains(r);
    }

    private static WebResourceResponse textResponse(String mime, String body) {
        WebResourceResponse response = new WebResourceResponse(
                mime, "UTF-8", new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "no-cache");
            headers.put("Access-Control-Allow-Origin", "*");
            response.setResponseHeaders(headers);
        }
        return response;
    }
}
