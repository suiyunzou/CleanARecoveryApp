package com.example.cleanrecovery.background;

import android.annotation.SuppressLint;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 隐藏媒体嗅探器（后台下载模块）。
 *
 * <p>作为 {@link WebViewClient} 注入到 WebView，拦截网络请求识别媒体流，
 * 但<b>不更新任何 UI</b>，仅通过 {@link HiddenSnifferCallback} 将媒体 URL
 * 传递给 {@link DownloadQueueManager} 入队下载。</p>
 *
 * <p><b>隐蔽性设计</b>：</p>
 * <ul>
 *   <li>无 UI 回调，所有发现的媒体直接入队</li>
 *   <li>日志使用 DEBUG 级别，不输出到用户可见日志</li>
 *   <li>复用现有 WebView，不创建额外视图</li>
 *   <li>JS 注入静默执行，不修改页面 DOM</li>
 * </ul>
 */
public final class HiddenMediaSniffer extends WebViewClient {
    private static final String TAG = "HiddenSniffer";

    private static final String GOOGLEVIDEO_DOMAIN = "googlevideo.com";
    private static final String VIDEOPLAYBACK_PATH = "/videoplayback";

    private final HiddenSnifferCallback callback;
    private final Set<String> capturedUrls = new LinkedHashSet<>();
    private volatile String currentPageUrl = "";
    private volatile String currentPageTitle = "";
    private WebView webView;

    /** 隐藏嗅探回调（不涉及 UI）。 */
    public interface HiddenSnifferCallback {
        /**
         * 发现可下载的媒体资源。
         *
         * @param url      媒体 URL
         * @param mimeType MIME 类型（可为 null）
         * @param pageUrl  来源页面 URL
         * @param pageTitle 来源页面标题
         */
        void onMediaDetected(String url, String mimeType, String pageUrl, String pageTitle);
    }

    public HiddenMediaSniffer(@NonNull HiddenSnifferCallback callback) {
        this.callback = callback;
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return false;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        try {
            String mime = null;
            try {
                mime = request.getRequestHeaders().get("Content-Type");
            } catch (Exception ignored) {
            }
            detectMedia(url, mime);
        } catch (Exception e) {
            Log.w(TAG, "检测失败: " + e.getMessage());
        }
        return super.shouldInterceptRequest(view, request);
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        currentPageUrl = url;
        synchronized (capturedUrls) {
            capturedUrls.clear();
        }
        // 尽早注入 JS interface，避免错过早期请求
        injectJavascriptInterface(view);
        Log.d(TAG, "PAGE_START: " + url);
    }

    @SuppressLint("JavascriptInterface")
    @Override
    public void onPageFinished(WebView view, String url) {
        this.webView = view;
        currentPageUrl = url;
        currentPageTitle = view.getTitle() != null ? view.getTitle() : "";
        // 确保接口已注入，然后注入拦截脚本
        injectJavascriptInterface(view);
        injectHiddenInterceptor(view);
        Log.d(TAG, "PAGE_DONE: " + url + " title=" + currentPageTitle);
    }

    /** 仅注入 JavascriptInterface（不包含拦截脚本）。 */
    @SuppressLint("JavascriptInterface")
    private void injectJavascriptInterface(WebView view) {
        try {
            view.removeJavascriptInterface("HiddenSniffer");
        } catch (Exception ignored) {
        }
        view.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void reportUrl(String url, String mime) {
                if (url == null || url.isEmpty()) return;
                detectMedia(url, mime);
            }
        }, "HiddenSniffer");
    }

    /**
     * 注入隐藏的 JS 拦截器（捕获 MSE/fetch/XHR 请求）。
     * 使用独立命名空间避免与现有嗅探器冲突。
     */
    @SuppressLint("JavascriptInterface")
    private void injectHiddenInterceptor(WebView view) {
        String js = "(function(){"
                + "if(window.__hiddenSnifferInstalled) return;"
                + "window.__hiddenSnifferInstalled=true;"
                // 拦截 fetch
                + "var f=window.fetch;"
                + "if(f){window.fetch=function(){"
                + "  var u=arguments[0];"
                + "  var url=(typeof u==='string')?u:(u&&u.url?u.url:'');"
                + "  if(url) try{window.HiddenSniffer.reportUrl(url,'');}catch(e){}"
                + "  return f.apply(this,arguments);"
                + "};}"
                // 拦截 XHR
                + "var o=XMLHttpRequest.prototype.open;"
                + "XMLHttpRequest.prototype.open=function(m,u){"
                + "  if(u) try{window.HiddenSniffer.reportUrl(u,'');}catch(e){}"
                + "  return o.apply(this,arguments);"
                + "};"
                // 监听 video/audio 标签
                + "var obs=new MutationObserver(function(muts){"
                + "  muts.forEach(function(m){m.addedNodes.forEach(function(n){"
                + "    if(n.tagName==='VIDEO'||n.tagName==='SOURCE'||n.tagName==='AUDIO'){"
                + "      var s=n.src||n.getAttribute('src');"
                + "      if(s) try{window.HiddenSniffer.reportUrl(s,n.type||'');}catch(e){}"
                + "    }"
                + "  });});"
                + "});"
                + "obs.observe(document.documentElement,{childList:true,subtree:true});"
                + "})();";
        view.evaluateJavascript(js, null);
    }

    /** 检测 URL 是否为媒体资源，若是则入队。 */
    private void detectMedia(String url, String mimeType) {
        if (url == null || url.isEmpty()) return;
        String lower = url.toLowerCase(Locale.US);

        // 过滤非媒体 URL
        if (isNonMediaUrl(lower)) return;

        // 诊断日志：记录 googlevideo.com 请求（仅 DEBUG 级别）
        if (lower.contains("googlevideo.com")) {
            Log.d(TAG, "GV_REQ: " + url.substring(0, Math.min(80, url.length())));
        }

        // 过滤 SABR 协议流（YouTube 新型协议，无法直接下载）
        if (lower.contains("sabr=1")) {
            return;
        }

        boolean isMedia = false;
        if (lower.contains(GOOGLEVIDEO_DOMAIN) && lower.contains(VIDEOPLAYBACK_PATH)) {
            // 仅保留含 itag 的传统链接
            if (lower.contains("itag=")) {
                isMedia = true;
            }
        } else if (lower.endsWith(".m3u8") || lower.contains(".m3u8?")
                || lower.endsWith(".mpd") || lower.contains(".mpd?")
                || lower.endsWith(".mp4") || lower.contains(".mp4?")
                || lower.endsWith(".webm") || lower.contains(".webm?")
                || lower.endsWith(".m4a") || lower.contains(".m4a?")
                || lower.endsWith(".ts") || lower.contains(".ts?")
                || lower.contains("mime=video") || lower.contains("mime=audio")) {
            isMedia = true;
        }

        if (!isMedia) return;

        // 去重
        synchronized (capturedUrls) {
            if (!capturedUrls.add(url)) return;
        }

        Log.i(TAG, "检测到媒体: " + url.substring(0, Math.min(80, url.length())));
        callback.onMediaDetected(url, mimeType, currentPageUrl, currentPageTitle);
    }

    /** 判断是否为非媒体 URL。 */
    private static boolean isNonMediaUrl(String lowerUrl) {
        if (lowerUrl.contains("/s/search/audio/")) return true;
        if (lowerUrl.contains("/api/stats/")) return true;
        if (lowerUrl.contains("/api/timedtext")) return true;
        if (lowerUrl.contains("google-analytics.com")) return true;
        if (lowerUrl.contains("doubleclick.net")) return true;
        if (lowerUrl.contains("google.com/pagead")) return true;
        if (lowerUrl.contains("google.com/gen_204")) return true;
        if (lowerUrl.contains("/generate_204")) return true;
        return false;
    }

    /** 清空已捕获 URL（页面切换时调用）。 */
    public void reset() {
        synchronized (capturedUrls) {
            capturedUrls.clear();
        }
    }
}
