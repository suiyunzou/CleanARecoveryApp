package com.example.cleanrecovery.proxy;

import android.util.Log;
import android.webkit.WebView;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebView 代理路由工具（供 BrowserActivity 调用，不修改 BrowserActivity）。
 *
 * <h3>策略</h3>
 * <ol>
 *   <li>优先：{@code androidx.webkit} 的 {@link ProxyController#setProxyOverride}，
 *       把所有流量分流到本地 SOCKS5（{@code socks5://127.0.0.1:port}）。
 *       需要 {@code WebViewFeature.PROXY_OVERRIDE} 支持（webkit 1.10.0 已提供）。</li>
 *   <li>回退：若 ProxyController 连续失败 3 次，改用
 *       {@code System.setProperty("http.proxyHost"/"http.proxyPort"/"https.proxyHost"/"https.proxyPort")}
 *       指向本地 HTTP 代理（{@link ProxyEngine#httpPort()}）。</li>
 * </ol>
 *
 * <h3>集成 API（供协调者后续接入 BrowserActivity 菜单/设置）</h3>
 * <ul>
 *   <li>{@link #applyProxy(WebView)}：在 WebView 创建/设置后调用，按当前引擎状态路由。</li>
 *   <li>{@link #clearProxy()}：代理关闭时调用，清除 WebView 代理覆盖与系统属性。</li>
 *   <li>{@link #isProxyActive()}：查询当前是否有生效的本地代理。</li>
 * </ul>
 */
public final class ProxyRouter {

    private static final String TAG = "ProxyRouter";
    private static final Executor UI_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicInteger proxyControllerFailures = new AtomicInteger(0);
    private static volatile boolean useSystemPropertyFallback = false;

    private ProxyRouter() {
    }

    /** 是否有生效的本地代理。 */
    public static boolean isProxyActive() {
        ProxyEngine eng = ProxyEngine.current();
        return eng != null && eng.isRunning();
    }

    /**
     * 应用代理到 WebView：若引擎运行中则路由到本地代理，否则清除覆盖。
     */
    public static void applyProxy(WebView webView) {
        if (webView == null) return;
        ProxyEngine eng = ProxyEngine.current();
        if (eng == null || !eng.isRunning()) {
            clearProxy();
            return;
        }
        if (useSystemPropertyFallback || !WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            applySystemPropertyFallback(eng);
            return;
        }
        try {
            applyProxyController(eng);
        } catch (Exception e) {
            Log.w(TAG, "ProxyController failed: " + e.getMessage());
            int n = proxyControllerFailures.incrementAndGet();
            if (n >= 3) {
                Log.w(TAG, "ProxyController failed " + n + " times, switching to System.setProperty fallback");
                useSystemPropertyFallback = true;
                applySystemPropertyFallback(eng);
            }
        }
    }

    /** 用 ProxyController 把 WebView 流量分流到本地代理。 */
    @android.annotation.SuppressLint("RequiresFeature")
    private static void applyProxyController(ProxyEngine eng) {
        ProxyController pc = ProxyController.getInstance();
        // WebView 124 对 socks:// 规则存在“回调成功但请求不进入代理”的兼容问题。
        // Mihomo mixed-port 原生支持 HTTP，故优先使用标准 HTTP 代理；旧 SS 引擎保留 SOCKS。
        String proxyUrl = (eng.isMihomo() ? "http://" : "socks://")
                + ProxyEngine.localHost() + ":"
                + (eng.isMihomo() ? eng.httpPort() : eng.socks5Port());
        ProxyConfig config = new ProxyConfig.Builder()
                .addProxyRule(proxyUrl)
                .addBypassRule("127.0.0.1")
                .addBypassRule("localhost")
                .build();
        pc.setProxyOverride(config, UI_EXECUTOR, () ->
                Log.i(TAG, "ProxyController applied: " + proxyUrl));
    }

    /** 回退：用 System.setProperty 指向本地 HTTP 代理。 */
    private static void applySystemPropertyFallback(ProxyEngine eng) {
        int httpPort = eng.httpPort();
        if (httpPort <= 0) {
            Log.w(TAG, "HTTP proxy not enabled, cannot apply system property fallback");
            return;
        }
        System.setProperty("http.proxyHost", ProxyEngine.localHost());
        System.setProperty("http.proxyPort", String.valueOf(httpPort));
        System.setProperty("https.proxyHost", ProxyEngine.localHost());
        System.setProperty("https.proxyPort", String.valueOf(httpPort));
        Log.i(TAG, "System.setProperty fallback applied: " + ProxyEngine.localHost() + ":" + httpPort);
    }

    /** 清除 WebView 代理覆盖与系统属性。 */
    public static void clearProxy() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            try {
                ProxyController.getInstance().clearProxyOverride(UI_EXECUTOR, () ->
                        Log.i(TAG, "ProxyController cleared"));
            } catch (Exception e) {
                Log.w(TAG, "clearProxyOverride failed: " + e.getMessage());
            }
        }
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
    }
}
