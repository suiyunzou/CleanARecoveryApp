package com.example.cleanrecovery.ytdlp;

import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

/**
 * WebView Cookie 同步工具（解决计划 D1：WebView Cookie 不共享）。
 *
 * <p><b>核心问题</b>：{@link BrowserActivity} 的 WebView cookie 不传给下载器，
 * 导致 googlevideo 直链 403。本类从 WebView 的 {@link CookieManager} 与
 * {@code document.cookie} 提取所有 cookie，写入 {@link PersistentCookieJar}，
 * 下载器请求前自动注入。</p>
 *
 * <h3>同步策略</h3>
 * <ol>
 *   <li>{@link CookieManager#getCookie(String)} 获取所有可见 cookie（含部分 HttpOnly，
 *       Android 7+ 行为）</li>
 *   <li>对 HttpOnly cookie（如登录态 cookie），通过
 *       {@link WebView#evaluateJavascript(String, ValueCallback)} 执行
 *       {@code document.cookie} 提取（注意：JS 视图下 HttpOnly 不可见，
 *       此路径仅作为补充；关键 HttpOnly cookie 需通过拦截 Set-Cookie 响应头获取）</li>
 *   <li>解析 {@code name=value; name2=value2} 字符串为 Cookie 列表，domain 取自 URL host</li>
 * </ol>
 *
 * <p><b>HttpOnly 限制</b>：纯 {@code document.cookie} 无法获取 HttpOnly cookie（D2 问题）。
 * 完整方案需拦截 WebView 网络响应的 Set-Cookie 头（M4 优化），M1 先用
 * {@link CookieManager#getCookie(String)} 兜底（Android 7+ 在某些实现下可返回 HttpOnly）。</p>
 */
public final class WebViewCookieSync {

    private static final String TAG = "WebViewCookieSync";

    private WebViewCookieSync() {}

    /**
     * 从 WebView 同步指定 URL 的 cookie 到 cookiejar。
     *
     * @param webView  当前 WebView 实例
     * @param url      目标 URL（host 决定 cookie domain）
     * @param jar      目标 cookiejar（通常为 {@link PersistentCookieJar#getInstance(Context)}）
     */
    public static void syncFromWebView(WebView webView, String url, CookieJar jar) {
        if (webView == null || url == null || jar == null) return;
        CookieManager cm = CookieManager.getInstance();
        // minSdk 23+：CookieManager 自动持久化，无需 sync()

        String cookieHeader = cm.getCookie(url);
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            Log.d(TAG, "no cookie from WebView for " + url);
            return;
        }

        List<CookieJar.Cookie> parsed = parseCookieHeader(cookieHeader, url);
        if (parsed.isEmpty()) {
            Log.d(TAG, "parsed 0 cookies from header for " + url);
            return;
        }
        jar.putCookies(parsed);
        Log.i(TAG, "synced " + parsed.size() + " cookies from WebView for " + url);
    }

    /**
     * 从 {@code CookieManager.getCookie()} 返回的字符串解析为 Cookie 列表。
     *
     * <p>格式：{@code name=value; name2=value2; ...}（无 domain/path/secure，
     * 由 URL host 推断 domain，path 设为 "/"，secure 由 URL scheme 判断）。</p>
     */
    static List<CookieJar.Cookie> parseCookieHeader(String cookieHeader, String url) {
        List<CookieJar.Cookie> result = new ArrayList<>();
        if (cookieHeader == null) return result;
        String host = PersistentCookieJar.extractHost(url);
        boolean secure = url != null && url.startsWith("https://");
        for (String pair : cookieHeader.split(";")) {
            pair = pair.trim();
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            // CookieManager 通常按 host 派发，domain 直接用 host
            result.add(new CookieJar.Cookie(name, value, host, "/", secure, false));
        }
        return result;
    }
}
