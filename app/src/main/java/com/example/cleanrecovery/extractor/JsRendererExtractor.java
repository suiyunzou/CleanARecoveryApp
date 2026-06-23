package com.example.cleanrecovery.extractor;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript 渲染提取器。
 *
 * <p>对应 yt-dlp 中针对 SPA（单页应用）的处理思路：当目标页面需要 JavaScript 执行
 * 才能生成媒体链接时，使用 Android 系统 WebView 渲染页面，从渲染后的 DOM 与
 * 网络请求中提取媒体 URL。</p>
 *
 * <p>核心机制：</p>
 * <ol>
 *   <li>启用 WebView 的 JavaScript 执行与 DOM Storage</li>
 *   <li>拦截所有网络请求，捕获 video/audio/m3u8/mp4 等媒体资源</li>
 *   <li>等待页面加载完成后，注入 JS 脚本扫描 DOM 中的 &lt;video&gt; &lt;source&gt; &lt;a&gt;</li>
 *   <li>合并拦截到的 URL 与 DOM 扫描结果</li>
 * </ol>
 *
 * <p>必须在主线程创建 WebView，因此本类通过 {@link Handler} 与 {@link CountDownLatch}
 * 实现线程同步，允许在工作线程中调用。</p>
 */
public final class JsRendererExtractor {

    private static final String TAG = "JsRenderer";

    /** 默认页面加载等待时间（毫秒）。 */
    private static final long DEFAULT_LOAD_TIMEOUT_MS = 20_000;
    /** 渲染后额外等待 SPA 异步请求的时间（毫秒）。 */
    private static final long EXTRA_SETTLE_MS = 3_000;

    /** 媒体 URL 正则：匹配常见视频/音频/HLS 扩展名。 */
    private static final Pattern MEDIA_URL_PATTERN = Pattern.compile(
            "https?://[^\\s\"'<>]+\\.(?:mp4|m4v|webm|mkv|mov|flv|avi|mp3|m4a|aac|flac|ogg|wav|m3u8|m3u)(?:\\?[^\\s\"'<>]*)?",
            Pattern.CASE_INSENSITIVE);

    /** 媒体 MIME 类型前缀。 */
    private static final Set<String> MEDIA_MIME_PREFIXES;
    static {
        Set<String> s = new LinkedHashSet<>();
        Collections.addAll(s, "video/", "audio/", "application/vnd.apple.mpegurl",
                "application/x-mpegurl", "application/octet-stream");
        MEDIA_MIME_PREFIXES = Collections.unmodifiableSet(s);
    }

    private final Context context;
    private final long loadTimeoutMs;

    public JsRendererExtractor(Context context) {
        this(context, DEFAULT_LOAD_TIMEOUT_MS);
    }

    public JsRendererExtractor(Context context, long loadTimeoutMs) {
        this.context = context.getApplicationContext();
        this.loadTimeoutMs = loadTimeoutMs;
    }

    /** 渲染结果。 */
    public static final class RenderResult {
        public final String html;
        public final List<String> mediaUrls;
        public final String title;

        RenderResult(String html, List<String> urls, String title) {
            this.html = html;
            this.mediaUrls = Collections.unmodifiableList(new ArrayList<>(urls));
            this.title = title;
        }
    }

    /**
     * 渲染指定 URL 并提取媒体链接。
     *
     * <p>本方法可在任意线程调用，内部会切换到主线程操作 WebView。</p>
     *
     * @param url 目标页面 URL
     * @return 渲染结果（含 HTML 与媒体 URL 列表）
     * @throws ExtractorException 渲染失败或超时
     */
    public RenderResult renderAndExtract(final String url) throws ExtractorException {
        if (url == null || url.isEmpty()) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "URL 为空");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<RenderResult> resultRef = new AtomicReference<>();
        final AtomicReference<Exception> errorRef = new AtomicReference<>();
        final Set<String> capturedUrls = new LinkedHashSet<>();

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.post(() -> doRenderOnMainThread(url, capturedUrls, latch, resultRef, errorRef));

        try {
            if (!latch.await(loadTimeoutMs + EXTRA_SETTLE_MS, TimeUnit.MILLISECONDS)) {
                throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                        "JS 渲染超时: " + url);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "JS 渲染被中断: " + e.getMessage());
        }

        Exception err = errorRef.get();
        if (err != null) {
            if (err instanceof ExtractorException) throw (ExtractorException) err;
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "JS 渲染失败: " + err.getMessage());
        }

        RenderResult result = resultRef.get();
        if (result == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "JS 渲染返回空结果");
        }
        return result;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void doRenderOnMainThread(final String url,
                                      final Set<String> capturedUrls,
                                      final CountDownLatch latch,
                                      final AtomicReference<RenderResult> resultRef,
                                      final AtomicReference<Exception> errorRef) {
        WebView webView = null;
        try {
            webView = new WebView(context);
            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setDomStorageEnabled(true);
            webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
            webView.getSettings().setUserAgentString(ExtractorHttp.DEFAULT_UA);
            webView.getSettings().setBlockNetworkImage(true);

            final WebView finalWebView = webView;
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return false;
                }

                @Override
                public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                   WebResourceRequest request) {
                    String reqUrl = request.getUrl().toString();
                    // WebResourceRequest 无 getMimeType()，通过 URL 扩展名判断
                    if (isMediaUrl(reqUrl)) {
                        Log.d(TAG, "拦截到媒体请求: " + reqUrl);
                        capturedUrls.add(reqUrl);
                    }
                    return super.shouldInterceptRequest(view, request);
                }

                @Override
                public void onPageFinished(WebView view, String url) {
                    // 等待 SPA 异步请求完成
                    view.postDelayed(() -> extractFromDom(finalWebView, url, capturedUrls,
                            latch, resultRef, errorRef), EXTRA_SETTLE_MS);
                }

                @Override
                public void onReceivedError(WebView view, WebResourceRequest request,
                                            WebResourceError error) {
                    if (request.isForMainFrame()) {
                        errorRef.set(new IOException("WebView 加载失败: "
                                + error.getDescription()));
                        latch.countDown();
                    }
                }
            });

            Log.i(TAG, "开始渲染: " + url);
            webView.loadUrl(url);

        } catch (Exception e) {
            errorRef.set(e);
            latch.countDown();
        }
    }

    /** 从渲染后的 DOM 提取媒体链接。 */
    private void extractFromDom(final WebView webView, final String url,
                                final Set<String> capturedUrls,
                                final CountDownLatch latch,
                                final AtomicReference<RenderResult> resultRef,
                                final AtomicReference<Exception> errorRef) {
        try {
            // 注入 JS：扫描 <video> <source> <audio> <a> 与 og:video 元标签
            String js = "(function(){"
                    + "var urls=[];"
                    + "document.querySelectorAll('video[src],video source[src],audio[src],"
                    + "source[src],a[href]').forEach(function(el){"
                    + "  var s=el.src||el.href; if(s) urls.push(s);"
                    + "});"
                    + "document.querySelectorAll('meta[property=\"og:video\"],"
                    + "meta[property=\"og:video:url\"],meta[property=\"og:audio\"]').forEach("
                    + "function(m){var c=m.content; if(c) urls.push(c);});"
                    + "var title=document.title||'';"
                    + "return JSON.stringify({urls:urls,title:title,html:document.documentElement.outerHTML});"
                    + "})()";

            webView.evaluateJavascript(js, value -> {
                try {
                    RenderResult result = parseJsResult(value, capturedUrls, url);
                    resultRef.set(result);
                    Log.i(TAG, "DOM 提取完成: 媒体数=" + result.mediaUrls.size()
                            + " 标题=" + result.title);
                } catch (Exception e) {
                    errorRef.set(e);
                    Log.e(TAG, "解析 JS 结果失败", e);
                } finally {
                    latch.countDown();
                }
            });
        } catch (Exception e) {
            errorRef.set(e);
            latch.countDown();
        }
    }

    /** 解析 evaluateJavascript 回调返回的 JSON 字符串。 */
    private RenderResult parseJsResult(String jsValue, Set<String> capturedUrls, String pageUrl) {
        Set<String> allUrls = new LinkedHashSet<>(capturedUrls);

        if (jsValue != null && !jsValue.equals("null")) {
            // evaluateJavascript 返回的是 JSON 字符串字面量（带引号）
            String json = jsValue;
            if (json.startsWith("\"") && json.endsWith("\"")) {
                json = unescapeJsonString(json.substring(1, json.length() - 1));
            }
            try {
                JSONObject obj = new JSONObject(json);
                org.json.JSONArray arr = obj.optJSONArray("urls");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String u = arr.optString(i);
                        if (u != null && !u.isEmpty()) allUrls.add(u);
                    }
                }
                String title = obj.optString("title", "");
                String html = obj.optString("html", "");

                // 从 HTML 中正则补充提取媒体 URL
                Matcher m = MEDIA_URL_PATTERN.matcher(html);
                while (m.find()) allUrls.add(m.group());

                // 仅保留真正的媒体 URL
                List<String> mediaUrls = new ArrayList<>();
                for (String u : allUrls) {
                    if (isMediaUrl(u)) mediaUrls.add(u);
                }
                return new RenderResult(html, mediaUrls, title);
            } catch (Exception e) {
                Log.w(TAG, "JSON 解析失败，使用拦截 URL: " + e.getMessage());
            }
        }

        List<String> mediaUrls = new ArrayList<>();
        for (String u : allUrls) {
            if (isMediaUrl(u)) mediaUrls.add(u);
        }
        return new RenderResult("", mediaUrls, "");
    }

    /** 反转义 JSON 字符串字面量。 */
    private String unescapeJsonString(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'u':
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('\\').append('u');
                            }
                        } else {
                            sb.append('\\').append('u');
                        }
                        break;
                    default: sb.append('\\').append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 判断 URL 是否为媒体资源。 */
    private static boolean isMediaUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        String lower = url.toLowerCase();
        if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")) return true;
        if (lower.endsWith(".mp4") || lower.endsWith(".m4v") || lower.endsWith(".webm")) return true;
        if (lower.endsWith(".mkv") || lower.endsWith(".mov") || lower.endsWith(".flv")) return true;
        if (lower.endsWith(".mp3") || lower.endsWith(".m4a") || lower.endsWith(".aac")) return true;
        if (lower.endsWith(".flac") || lower.endsWith(".ogg") || lower.endsWith(".wav")) return true;
        // 含 mime 参数的 URL
        if (lower.contains("mime=video") || lower.contains("mime=audio")) return true;
        if (lower.contains("type=video") || lower.contains("type=audio")) return true;
        return false;
    }

    /** 判断 MIME 类型是否为媒体。 */
    private static boolean isMediaMime(String mime) {
        if (mime == null) return false;
        String lower = mime.toLowerCase();
        for (String prefix : MEDIA_MIME_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }
}
