package com.example.cleanrecovery.ui.browser;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 广告拦截 WebView 壳（规则匹配委托给共享的 {@link AdFilterEngine}）。
 *
 * <p>职责：判定当前页是否启用拦截（全局开关/按站覆盖/视频站白名单）、
 * 提供拦截响应（空文本 / 1×1 GIF / 主框架 stub 页）、计数与节省流量估算。
 * 规则装载由 {@link AdBlockRuleLoader} 完成，全 App 共享一份索引。</p>
 */
public final class BrowserAdBlocker extends WebViewClient {

    /** 元素隐藏 CSS 的虚拟资源名（页面内以 <link href="…/via_inject_blocker.css"> 引用）。 */
    public static final String VIRTUAL_CSS_NAME = "via_inject_blocker.css";

    public static String cosmeticScript() {
        return "(function(){if(!/^https?:$/.test(location.protocol)||location.hostname==='appassets.androidplatform.net')return;"
                + "function inject(){if(!document.head)return false;if(document.getElementById('via_inject_css_blocker'))return true;"
                + "var l=document.createElement('link');l.id='via_inject_css_blocker';l.rel='stylesheet';l.type='text/css';"
                + "l.href='https://'+location.host+'/" + VIRTUAL_CSS_NAME + "';document.head.appendChild(l);return true;}"
                + "if(!inject()){var observer=new MutationObserver(function(){if(inject())observer.disconnect()});"
                + "observer.observe(document.documentElement||document,{childList:true,subtree:true})}})();";
    }

    /** 媒体保护豁免站根域：跳过媒体类网络规则（易误杀播放器 CDN），其余防护保留。 */
    private static final Set<String> FORCE_OFF_ROOTS = new HashSet<>(java.util.Arrays.asList(
            "youku.com", "iqiyi.com", "mgtv.com", "qq.com"));

    /** 仅跳过引擎元素隐藏注入（网络拦截保留）的站根域或完整 host。 */
    private static final Set<String> COSMETIC_SKIP_ROOTS = new HashSet<>(java.util.Arrays.asList(
            "v.qq.com", "film.qq.com", "bilibili.com", "ximalaya.com"));

    private static final String GIF_1PX_B64 =
            "R0lGODlhAQABAID/AMDAwAAAACH5BAEAAAAALAAAAAABAAEAAAICRAEAOw==";
    private static final byte[] GIF_1PX = android.util.Base64.decode(GIF_1PX_B64, android.util.Base64.DEFAULT);

    private static final AdFilterEngine ENGINE = new AdFilterEngine();

    private final BrowserPrefs prefs;
    /** 当前页 host（UI 线程 onPageStarted 时缓存；shouldInterceptRequest 在 WebView IO 线程执行，
     * 严禁在该回调里调用任何 WebView 方法）。 */
    private volatile String pageHost = "";
    /** 当前页保护策略（随 pageHost 一起刷新；IO 线程只读）。 */
    private volatile SiteProtectionPolicy pagePolicy = SiteProtectionPolicy.FULL;
    /** 最近活跃的拦截器实例（Service Worker 过滤复用当前页策略）。 */
    private static volatile BrowserAdBlocker ACTIVE;
    /** 主框架「继续访问」放行过的 host（会话级；UI 线程写、WebView IO 线程读，必须线程安全）。 */
    private final Set<String> bypassHosts =
            Collections.synchronizedSet(new HashSet<String>());
    private final AtomicInteger sessionBlocked = new AtomicInteger();

    public BrowserAdBlocker(BrowserPrefs prefs) {
        this.prefs = prefs;
    }

    /** 由 UI 线程（onPageStarted）更新当前页 host 与保护策略。 */
    public void setPageUrl(String url) {
        this.pageHost = hostOf(url);
        this.pagePolicy = resolvePolicy(BrowserPrefs.siteKey(url), this.pageHost);
        ACTIVE = this;
    }

    /** 解析当前页四维保护策略（按站覆盖优先于豁免表）。 */
    private SiteProtectionPolicy resolvePolicy(String siteKey, String host) {
        int siteMode = 2;
        if (prefs.siteSettingsEnabled(siteKey)) {
            int mode = prefs.siteAdBlockMode(siteKey);
            if (mode == 0 || mode == 1) siteMode = mode;
        } else if (prefs.siteAdBlockOff(siteKey)) {
            siteMode = 0;
        }
        SiteProtectionPolicy p = SiteProtectionPolicy.resolve(
                prefs.adBlockEnabled(), host, siteMode, FORCE_OFF_ROOTS, COSMETIC_SKIP_ROOTS);
        if (siteMode == 1 && !p.mediaProtection) {
            // 用户对该站显式开启拦截 → 媒体豁免也让位
            p = new SiteProtectionPolicy(p.networkBlocking, p.cosmeticFiltering, p.navigationGuard, true);
        }
        return p;
    }

    public static AdFilterEngine engine() {
        return ENGINE;
    }

    /** 当前标签页本次会话已拦截数（页级徽标用）。 */
    public int sessionBlockedCount() {
        return sessionBlocked.get();
    }

    /** 主框架「继续访问」放行（stub 页链接回调）。 */
    public void allowHostOnce(String host) {
        if (host != null && !host.isEmpty()) bypassHosts.add(host);
    }

    /** host 是否在会话放行名单（NavigationGuard 消费）。 */
    public boolean isBypassListed(String host) {
        return host != null && bypassHosts.contains(host);
    }

    /** 当前页导航守卫开关（四维策略的 navigationGuard 维度）。 */
    public boolean navigationGuardActive() {
        return pagePolicy.navigationGuard;
    }

    /** 全量重建共享规则索引（调用方需在后台线程执行）。 */
    public static void installRules(AdFilterEngine.Builder builder) {
        ENGINE.swap(builder);
    }

    public static boolean rulesLoaded() {
        return ENGINE.current().hasNetworkRules();
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (request == null) return null;
        Uri uri = request.getUrl();
        if (uri == null) return null;
        String url = uri.toString();
        // 元素隐藏 CSS 走虚拟资源：页面 head 解析到 <link> 时立即生效，无闪烁
        if (url.endsWith(VIRTUAL_CSS_NAME)) {
            return textResponse("text/css", cssForPage(url));
        }
        if (request.isForMainFrame()) {
            return interceptMainFrame(url, uri);
        }
        return interceptSubframe(url, uri, request.getRequestHeaders());
    }

    /** 主框架拦截：命中即回本地提示页（提供「继续访问」）。 */
    private WebResourceResponse interceptMainFrame(String url, Uri uri) {
        if (!pagePolicy.networkBlocking) return null;
        String urlHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (urlHost.isEmpty()) return null;
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        if (bypassHosts.contains(urlHost)) {
            Log.i("ViaGuard", "mainframe bypass: " + urlHost);
            return null;
        }
        AdFilterEngine.Filter hit = ENGINE.current().matchNetwork(
                lowerUrl, urlHost, pageHost, AdFilterEngine.T_DOCUMENT,
                AdFilterEngine.isThirdParty(urlHost, pageHost));
        if (hit != null) {
            Log.i("ViaGuard", "mainframe block: " + urlHost + " rule=" + hit.raw);
            recordBlock(lowerUrl);
            return stubPage(url, hit);
        }
        return null;
    }

    /**
     * 子框架网络过滤；Service Worker 过滤复用同一逻辑（SW 请求没有主框架概念，
     * pageHost 取最近活跃标签页）。
     */
    WebResourceResponse interceptSubframe(String url, Uri uri, Map<String, String> requestHeaders) {
        SiteProtectionPolicy policy = pagePolicy;
        if (!policy.networkBlocking) return null;
        String urlHost = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (urlHost.isEmpty()) return null;
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        int type = AdFilterEngine.resourceType(false, requestHeaders, url);
        if (!policy.shouldFilterType(type)) return null;
        boolean thirdParty = AdFilterEngine.isThirdParty(urlHost, pageHost);
        AdFilterEngine.Filter hit = ENGINE.current().matchNetwork(
                lowerUrl, urlHost, pageHost, type, thirdParty);
        if (hit == null) return null;
        recordBlock(lowerUrl);
        // 对齐 Via：html/htm/css/js 返回空文本避免破坏渲染，其余回 1×1 GIF
        String ext = extensionOf(lowerUrl);
        if (ext.equals("html") || ext.equals("htm") || ext.equals("css") || ext.equals("js")) {
            return textResponse("text/plain", "");
        }
        return new WebResourceResponse("image/gif", null,
                new ByteArrayInputStream(GIF_1PX));
    }

    /**
     * 安装 Service Worker 请求过滤（App 级一次即可；API<24 或内核过旧时静默跳过）。
     * 注意 SW 无法感知发起页面，第三方判定复用最近活跃标签页的 pageHost。
     */
    public static void installServiceWorkerFilter() {
        try {
            if (android.os.Build.VERSION.SDK_INT < 24) return;
            androidx.webkit.ServiceWorkerControllerCompat.getInstance()
                    .setServiceWorkerClient(new androidx.webkit.ServiceWorkerClientCompat() {
                        @Override
                        public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                            BrowserAdBlocker b = ACTIVE;
                            if (b == null || request == null || request.getUrl() == null) return null;
                            return b.interceptSubframe(
                                    request.getUrl().toString(), request.getUrl(),
                                    request.getRequestHeaders());
                        }
                    });
        } catch (Throwable ignored) {
            // WebView 内核不支持 Service Worker 过滤：保持默认直连
        }
    }

    // ===== 判定 =====

    private String cssForPage(String pageUrl) {
        String pageHost = hostOf(pageUrl);
        if (!prefs.adBlockEnabled()) return "";
        StringBuilder css = new StringBuilder();
        if (resolvePolicy(BrowserPrefs.siteKey(pageUrl), pageHost).cosmeticFiltering) {
            String engineCss = ENGINE.current().cosmeticCss(pageHost);
            if (!engineCss.isEmpty()) css.append(engineCss);
        }
        css.append(prefs.cosmeticCssForHost(pageHost)); // 手动标记广告的按站规则始终生效
        return css.toString();
    }

    /** 元素隐藏 CSS（引擎规则 + 手动标记规则），供页面注入回退使用。 */
    public String cosmeticCssFor(String pageUrl) {
        return cssForPage(pageUrl);
    }

    // ===== 统计 =====

    private void recordBlock(String url) {
        sessionBlocked.incrementAndGet();
        prefs.addAdBlockBlocked(1);
        prefs.addAdBlockBytes(estimateBytes(url));
    }

    /** 确定性单请求节省字节估算（0-80KB，对齐 Via 的假估策略）。 */
    static long estimateBytes(String url) {
        int h = url == null ? 0 : url.hashCode();
        return (Math.abs(h) % 80 + 1) * 1024L;
    }

    // ===== 响应体 =====

    private static WebResourceResponse textResponse(String mime, String body) {
        WebResourceResponse response = new WebResourceResponse(
                mime, "UTF-8", new ByteArrayInputStream(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Map<String, String> headers = new HashMap<>();
        headers.put("Cache-Control", "no-cache");
        headers.put("Access-Control-Allow-Origin", "*");
        response.setResponseHeaders(headers);
        return response;
    }

    /** 主框架拦截 stub 页：展示命中规则并提供「继续访问」。 */
    private static WebResourceResponse stubPage(String url, AdFilterEngine.Filter hit) {
        String target = Uri.encode(url);
        String html = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>已拦截</title></head>"
                + "<body style=\"margin:0;font-family:sans-serif;background:#f5f6f8;color:#333;"
                + "display:flex;align-items:center;justify-content:center;height:100vh\">"
                + "<div style=\"text-align:center;padding:24px;max-width:92vw\">"
                + "<div style=\"font-size:44px\">&#128737;</div>"
                + "<h3 style=\"margin:12px 0 6px\">已拦截本次跳转</h3>"
                + "<p style=\"margin:4px 0;opacity:.75\">命中规则：<code>" + escapeHtml(hit.serialize()) + "</code></p>"
                + "<p style=\"word-break:break-all;opacity:.6;font-size:13px\">" + escapeHtml(url) + "</p>"
                + "<a href=\"viacontinue://" + target + "\" style=\"display:inline-block;margin-top:16px;"
                + "padding:10px 22px;border-radius:20px;background:#2b7cff;color:#fff;text-decoration:none\">继续访问</a>"
                + "</div></body></html>";
        return textResponse("text/html", html);
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String extensionOf(String lowerUrl) {
        int q = lowerUrl.indexOf('?');
        if (q < 0) q = lowerUrl.indexOf('#');
        String path = q >= 0 ? lowerUrl.substring(0, q) : lowerUrl;
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        return dot > slash && dot >= 0 ? path.substring(dot + 1) : "";
    }

    private static String hostOf(String url) {
        if (url == null || url.isEmpty()) return "";
        try {
            return BrowserPrefs.normalizeHost(Uri.parse(url).getHost());
        } catch (Exception e) {
            return "";
        }
    }
}
