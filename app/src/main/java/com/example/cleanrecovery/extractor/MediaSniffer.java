package com.example.cleanrecovery.extractor;

import android.annotation.SuppressLint;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 媒体资源嗅探器（借鉴 Via 浏览器的 resource_sniffer 设计）。
 *
 * <p>作为 {@link WebViewClient} 注入到 WebView，拦截所有网络请求，
 * 识别视频/音频/HLS 流媒体 URL，用于内置浏览器下载功能。</p>
 *
 * <p><b>核心机制</b>：</p>
 * <ol>
 *   <li>重写 {@link #shouldInterceptRequest} 捕获每个网络请求</li>
 *   <li>通过 URL 特征（域名、扩展名、MIME）判断是否为媒体流</li>
 *   <li>去重后通知回调，UI 层展示嗅探结果</li>
 * </ol>
 *
 * <p><b>YouTube 适配</b>：YouTube 视频流通过 {@code *.googlevideo.com/videoplayback}
 * 域名分发，URL 含 {@code itag}（格式标识）、{@code mime}（MIME 类型）等参数。
 * 本嗅探器会解析这些参数，提取分辨率、编码等信息。</p>
 */
public class MediaSniffer extends WebViewClient {

    private static final String TAG = "MediaSniffer";

    /** YouTube 视频分发域名。 */
    private static final String GOOGLEVIDEO_DOMAIN = "googlevideo.com";
    /** YouTube 视频流路径。 */
    private static final String VIDEOPLAYBACK_PATH = "/videoplayback";

    /** 媒体 URL 正则：匹配常见视频/音频/HLS 扩展名。 */
    private static final Set<String> MEDIA_EXTENSIONS;
    static {
        Set<String> s = new LinkedHashSet<>();
        Collections.addAll(s,
                ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".flv", ".avi",
                ".mp3", ".m4a", ".aac", ".flac", ".ogg", ".wav",
                ".m3u8", ".m3u", ".ts");
        MEDIA_EXTENSIONS = Collections.unmodifiableSet(s);
    }

    /** 媒体 MIME 前缀。 */
    private static final String[] MEDIA_MIME_PREFIXES = {
            "video/", "audio/",
            "application/vnd.apple.mpegurl",
            "application/x-mpegurl",
            "application/octet-stream"
    };

    /** 嗅探回调（主线程调用）。 */
    public interface SnifferCallback {
        /**
         * 发现新的媒体资源。
         *
         * @param resource 媒体资源信息
         */
        void onMediaFound(@NonNull MediaResource resource);

        /** 页面开始加载。 */
        void onPageStarted(String url);

        /** 页面加载完成。 */
        void onPageFinished(String url);
    }

    /** 媒体资源信息。 */
    public static final class MediaResource {
        /** 媒体 URL */
        public final String url;
        /** 推测的扩展名（mp4/m4a/m3u8 等） */
        public final String ext;
        /** 媒体类型：video / audio / hls */
        public final String kind;
        /** 分辨率描述（如 "1080p"），未知为 "" */
        public final String resolution;
        /** 视频编码（如 avc1），未知为 "" */
        public final String vcodec;
        /** 音频编码（如 mp4a），未知为 "" */
        public final String acodec;
        /** 来源页面 URL */
        public final String pageUrl;
        /** 发现时间戳 */
        public final long foundAtMs;

        MediaResource(String url, String ext, String kind, String resolution,
                      String vcodec, String acodec, String pageUrl) {
            this.url = url;
            this.ext = ext;
            this.kind = kind;
            this.resolution = resolution != null ? resolution : "";
            this.vcodec = vcodec != null ? vcodec : "";
            this.acodec = acodec != null ? acodec : "";
            this.pageUrl = pageUrl != null ? pageUrl : "";
            this.foundAtMs = System.currentTimeMillis();
        }

        /** 获取显示标题。 */
        public String getDisplayTitle() {
            StringBuilder sb = new StringBuilder();
            if ("hls".equals(kind)) {
                sb.append("HLS 流");
            } else if ("audio".equals(kind)) {
                sb.append("音频");
            } else {
                sb.append("视频");
            }
            if (!resolution.isEmpty()) {
                sb.append(" · ").append(resolution);
            }
            if (!vcodec.isEmpty() && !"none".equals(vcodec)) {
                sb.append(" · ").append(vcodec);
            }
            return sb.toString();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MediaResource)) return false;
            return url.equals(((MediaResource) o).url);
        }

        @Override
        public int hashCode() {
            return url.hashCode();
        }
    }

    @NonNull
    private final SnifferCallback callback;
    /** 已捕获的媒体 URL（去重）。 */
    @NonNull
    private final Set<String> capturedUrls = new LinkedHashSet<>();
    /** 当前页面 URL。 */
    private volatile String currentPageUrl = "";

    public MediaSniffer(@NonNull SnifferCallback callback) {
        this.callback = callback;
    }

    /** 清空已捕获的媒体列表（切换页面时调用）。 */
    public void clear() {
        synchronized (capturedUrls) {
            capturedUrls.clear();
        }
    }

    /** 获取已捕获的所有媒体 URL（不可变副本）。 */
    public List<String> getCapturedUrls() {
        synchronized (capturedUrls) {
            return Collections.unmodifiableList(new java.util.ArrayList<>(capturedUrls));
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        return false;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        try {
            MediaResource resource = analyzeRequest(url, request);
            if (resource != null) {
                synchronized (capturedUrls) {
                    if (capturedUrls.add(url)) {
                        Log.i(TAG, "嗅探到媒体: " + resource.getDisplayTitle()
                                + " url=" + truncate(url, 120));
                        callback.onMediaFound(resource);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "分析请求失败: " + e.getMessage());
        }
        return super.shouldInterceptRequest(view, request);
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        currentPageUrl = url;
        callback.onPageStarted(url);
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        currentPageUrl = url;
        // 注入 JavaScript 拦截 fetch/XHR 请求（捕获 MSE 分块请求）
        injectNetworkInterceptor(view);
        callback.onPageFinished(url);
    }

    /**
     * 注入网络拦截脚本，捕获 fetch/XHR 发起的媒体请求。
     *
     * <p>YouTube 使用 MSE（Media Source Extensions）播放视频，视频流通过
     * fetch/XHR 分块加载，{@link #shouldInterceptRequest} 可能无法捕获。
     * 本方法注入 JS 钩子，拦截这些请求并通过 {@code JsInterface} 回传。</p>
     */
    @SuppressLint("JavascriptInterface")
    private void injectNetworkInterceptor(WebView view) {
        // 添加 JavascriptInterface（注意：仅用于内部，不暴露给外部页面）
        view.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void reportMediaUrl(String url) {
                if (url == null || url.isEmpty()) return;
                String lower = url.toLowerCase(Locale.US);
                if (isNonMediaUrl(lower)) return;
                if (lower.contains(GOOGLEVIDEO_DOMAIN) && lower.contains(VIDEOPLAYBACK_PATH)) {
                    MediaResource resource = parseGooglevideoUrl(url);
                    synchronized (capturedUrls) {
                        if (capturedUrls.add(url)) {
                            Log.i(TAG, "JS拦截到媒体: " + resource.getDisplayTitle());
                            callback.onMediaFound(resource);
                        }
                    }
                } else if (lower.endsWith(".m3u8") || lower.contains(".m3u8?")
                        || lower.contains("mime=video") || lower.contains("mime=audio")) {
                    synchronized (capturedUrls) {
                        if (capturedUrls.add(url)) {
                            Log.i(TAG, "JS拦截到媒体: " + url);
                            callback.onMediaFound(new MediaResource(
                                    url, "m3u8", "hls", "", "", "", currentPageUrl));
                        }
                    }
                }
            }
        }, "AndroidSniffer");

        String js = "(function(){"
                + "if(window.__snifferInstalled) return;"
                + "window.__snifferInstalled=true;"
                // 拦截 fetch
                + "var origFetch=window.fetch;"
                + "if(origFetch){"
                + "  window.fetch=function(){"
                + "    var url=arguments[0];"
                + "    if(typeof url==='string'){"
                + "      try{window.AndroidSniffer.reportMediaUrl(url);}catch(e){}"
                + "    } else if(url&&url.url){"
                + "      try{window.AndroidSniffer.reportMediaUrl(url.url);}catch(e){}"
                + "    }"
                + "    return origFetch.apply(this,arguments);"
                + "  };"
                + "}"
                // 拦截 XMLHttpRequest
                + "var origOpen=XMLHttpRequest.prototype.open;"
                + "XMLHttpRequest.prototype.open=function(method,url){"
                + "  try{window.AndroidSniffer.reportMediaUrl(url);}catch(e){}"
                + "  return origOpen.apply(this,arguments);"
                + "};"
                // 监听 video/audio 标签的 src 属性变化
                + "var observer=new MutationObserver(function(mutations){"
                + "  mutations.forEach(function(m){"
                + "    m.addedNodes.forEach(function(node){"
                + "      if(node.tagName==='VIDEO'||node.tagName==='SOURCE'||node.tagName==='AUDIO'){"
                + "        var src=node.src||node.getAttribute('src');"
                + "        if(src) try{window.AndroidSniffer.reportMediaUrl(src);}catch(e){}"
                + "      }"
                + "    });"
                + "  });"
                + "});"
                + "observer.observe(document.documentElement,{childList:true,subtree:true});"
                + "})();";

        view.evaluateJavascript(js, null);
        Log.d(TAG, "网络拦截器已注入");
    }

    /**
     * 分析请求 URL，判断是否为媒体资源。
     *
     * @param url     请求 URL
     * @param request WebResourceRequest（含请求头）
     * @return MediaResource 或 null（非媒体）
     */
    private MediaResource analyzeRequest(String url, WebResourceRequest request) {
        if (url == null || url.isEmpty()) return null;
        String lower = url.toLowerCase(Locale.US);

        // 0. 过滤掉非媒体资源（YouTube 系统提示音、分析请求等）
        if (isNonMediaUrl(lower)) return null;

        // 1. YouTube googlevideo.com 视频流
        if (lower.contains(GOOGLEVIDEO_DOMAIN) && lower.contains(VIDEOPLAYBACK_PATH)) {
            return parseGooglevideoUrl(url);
        }

        // 2. HLS manifest（.m3u8 / .m3u）
        if (lower.endsWith(".m3u8") || lower.endsWith(".m3u")
                || lower.contains(".m3u8?") || lower.contains(".m3u?")) {
            return new MediaResource(url, "m3u8", "hls", "", "", "", currentPageUrl);
        }

        // 3. 通过 MIME 类型判断
        String mime = getMimeType(request);
        if (mime != null && isMediaMime(mime)) {
            String ext = guessExtFromMime(mime);
            String kind = mime.startsWith("video/") ? "video"
                    : mime.startsWith("audio/") ? "audio" : "hls";
            return new MediaResource(url, ext, kind, "", "", "", currentPageUrl);
        }

        // 4. 通过 URL 扩展名判断（排除小文件和系统音频）
        for (String ext : MEDIA_EXTENSIONS) {
            if (lower.endsWith(ext) || lower.contains(ext + "?")) {
                // 过滤 YouTube 搜索页面的系统提示音
                if (isSystemAudio(lower)) return null;
                String kind = guessKindFromExt(ext);
                return new MediaResource(url, ext.substring(1), kind, "", "", "", currentPageUrl);
            }
        }

        // 5. URL 含 mime 参数（部分 CDN 用查询参数标识类型）
        if (lower.contains("mime=video") || lower.contains("type=video")) {
            return new MediaResource(url, "mp4", "video", "", "", "", currentPageUrl);
        }
        if (lower.contains("mime=audio") || lower.contains("type=audio")) {
            return new MediaResource(url, "m4a", "audio", "", "", "", currentPageUrl);
        }

        return null;
    }

    /** 判断是否为非媒体 URL（YouTube 系统提示音、分析、统计等）。 */
    private static boolean isNonMediaUrl(String lowerUrl) {
        // YouTube 搜索页面的系统提示音
        if (lowerUrl.contains("/s/search/audio/")) return true;
        // YouTube 统计与分析请求
        if (lowerUrl.contains("/api/stats/")) return true;
        if (lowerUrl.contains("/api/timedtext")) return true;  // 字幕
        // 通用分析脚本
        if (lowerUrl.contains("google-analytics.com")) return true;
        if (lowerUrl.contains("doubleclick.net")) return true;
        return false;
    }

    /** 判断是否为系统音频（非用户内容）。 */
    private static boolean isSystemAudio(String lowerUrl) {
        if (lowerUrl.contains("/s/search/audio/")) return true;
        // 常见系统提示音文件名
        String[] systemAudio = {"failure.mp3", "success.mp3", "open.mp3",
                "no_input.mp3", "click.mp3", "beep.mp3", "notification.mp3"};
        for (String s : systemAudio) {
            if (lowerUrl.endsWith(s) || lowerUrl.contains(s + "?")) return true;
        }
        return false;
    }

    /**
     * 解析 YouTube googlevideo.com URL，提取 itag 对应的格式信息。
     *
     * <p>YouTube videoplayback URL 示例：
     * {@code https://rr1---sn-xxx.googlevideo.com/videoplayback?expire=...&itag=18&mime=video/mp4&...}</p>
     *
     * <p>itag 是 YouTube 的格式标识符，对应固定的分辨率/编码组合，
     * 参见 yt-dlp 的 itag 表（extractor/youtube/_video.py）。</p>
     */
    private MediaResource parseGooglevideoUrl(String url) {
        Map<String, String> params = parseQueryParams(url);
        String itag = params.get("itag");
        String mime = params.get("mime");
        String cpn = params.get("cpn");

        // 过滤 SABR 协议流（YouTube 新型自适应协议，无法直接下载）
        if ("1".equals(params.get("sabr")) || url.contains("sabr=1")
                || url.contains("/api/timedtext")) {
            return null;
        }
        // 仅保留含 itag 的传统 videoplayback 链接
        if (itag == null || itag.isEmpty()) {
            return null;
        }

        // 根据 itag 查询格式信息（对应 yt-dlp 的 _ITAG_TABLE）
        ItagInfo info = lookupItag(itag);

        String ext;
        String kind;
        String resolution = "";
        String vcodec = "";
        String acodec = "";

        if (info != null) {
            ext = info.ext;
            kind = info.kind;
            resolution = info.resolution;
            vcodec = info.vcodec;
            acodec = info.acodec;
        } else if (mime != null) {
            if (mime.contains("audio")) {
                ext = "m4a";
                kind = "audio";
                acodec = "mp4a";
            } else if (mime.contains("mp4")) {
                ext = "mp4";
                kind = "video";
                vcodec = "avc1";
            } else if (mime.contains("webm")) {
                ext = "webm";
                kind = "video";
                vcodec = "vp9";
            } else {
                ext = "mp4";
                kind = "video";
            }
        } else {
            ext = "mp4";
            kind = "video";
        }

        return new MediaResource(url, ext, kind, resolution, vcodec, acodec, currentPageUrl);
    }

    /** YouTube itag 格式表（节选自 yt-dlp extractor/youtube/_video.py）。 */
    private static final class ItagInfo {
        final String ext, kind, resolution, vcodec, acodec;
        ItagInfo(String ext, String kind, String resolution, String vcodec, String acodec) {
            this.ext = ext; this.kind = kind; this.resolution = resolution;
            this.vcodec = vcodec; this.acodec = acodec;
        }
    }

    /** 常见 itag 查询表（对应 yt-dlp _ITAG_TABLE）。 */
    private static final Map<String, ItagInfo> ITAG_TABLE;
    static {
        Map<String, ItagInfo> m = new LinkedHashMap<>();
        // 合并格式（video+audio）
        m.put("18",  new ItagInfo("mp4",  "video", "360p",  "avc1",   "mp4a"));
        m.put("22",  new ItagInfo("mp4",  "video", "720p",  "avc1",   "mp4a"));
        m.put("5",   new ItagInfo("flv",  "video", "240p",  "flv1",   "mp3"));
        m.put("36",  new ItagInfo("3gp",  "video", "240p",  "mp4v",   "aac"));
        m.put("17",  new ItagInfo("3gp",  "video", "144p",  "mp4v",   "aac"));
        // DASH 视频
        m.put("137", new ItagInfo("mp4",  "video", "1080p", "avc1",   "none"));
        m.put("136", new ItagInfo("mp4",  "video", "720p",  "avc1",   "none"));
        m.put("135", new ItagInfo("mp4",  "video", "480p",  "avc1",   "none"));
        m.put("134", new ItagInfo("mp4",  "video", "360p",  "avc1",   "none"));
        m.put("133", new ItagInfo("mp4",  "video", "240p",  "avc1",   "none"));
        m.put("160", new ItagInfo("mp4",  "video", "144p",  "avc1",   "none"));
        m.put("248", new ItagInfo("webm", "video", "1080p", "vp9",    "none"));
        m.put("247", new ItagInfo("webm", "video", "720p",  "vp9",    "none"));
        m.put("244", new ItagInfo("webm", "video", "480p",  "vp9",    "none"));
        m.put("243", new ItagInfo("webm", "video", "360p",  "vp9",    "none"));
        m.put("271", new ItagInfo("webm", "video", "1440p", "vp9",    "none"));
        m.put("313", new ItagInfo("webm", "video", "2160p", "vp9",    "none"));
        m.put("401", new ItagInfo("mp4",  "video", "2160p", "av01",   "none"));
        m.put("400", new ItagInfo("mp4",  "video", "1440p", "av01",   "none"));
        // DASH 音频
        m.put("140", new ItagInfo("m4a",  "audio", "",       "none",  "mp4a"));
        m.put("139", new ItagInfo("m4a",  "audio", "",       "none",  "mp4a"));
        m.put("251", new ItagInfo("webm", "audio", "",       "none",  "opus"));
        m.put("250", new ItagInfo("webm", "audio", "",       "none",  "opus"));
        m.put("249", new ItagInfo("webm", "audio", "",       "none",  "opus"));
        ITAG_TABLE = Collections.unmodifiableMap(m);
    }

    private static ItagInfo lookupItag(String itag) {
        if (itag == null) return null;
        return ITAG_TABLE.get(itag);
    }

    /** 解析 URL 查询参数。 */
    private static Map<String, String> parseQueryParams(String url) {
        Map<String, String> params = new LinkedHashMap<>();
        int q = url.indexOf('?');
        if (q < 0) return params;
        String query = url.substring(q + 1);
        int h = query.indexOf('#');
        if (h >= 0) query = query.substring(0, h);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                String val = pair.substring(eq + 1);
                params.put(key, val);
            }
        }
        return params;
    }

    /** 从 WebResourceRequest 提取 MIME 类型。 */
    private static String getMimeType(WebResourceRequest request) {
        try {
            Map<String, String> headers = request.getRequestHeaders();
            if (headers != null) {
                String ct = headers.get("Content-Type");
                if (ct == null) ct = headers.get("content-type");
                if (ct != null) {
                    int semi = ct.indexOf(';');
                    return semi > 0 ? ct.substring(0, semi).trim() : ct.trim();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isMediaMime(String mime) {
        if (mime == null) return false;
        String lower = mime.toLowerCase(Locale.US);
        for (String prefix : MEDIA_MIME_PREFIXES) {
            if (lower.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String guessExtFromMime(String mime) {
        if (mime == null) return "mp4";
        String lower = mime.toLowerCase(Locale.US);
        if (lower.contains("mp4")) return "mp4";
        if (lower.contains("webm")) return "webm";
        if (lower.contains("mpegurl")) return "m3u8";
        if (lower.contains("mp3")) return "mp3";
        if (lower.contains("aac")) return "aac";
        if (lower.contains("flac")) return "flac";
        if (lower.contains("ogg")) return "ogg";
        if (lower.contains("wav")) return "wav";
        return "mp4";
    }

    private static String guessKindFromExt(String ext) {
        if (ext.equals(".m3u8") || ext.equals(".m3u") || ext.equals(".ts")) return "hls";
        if (ext.equals(".mp3") || ext.equals(".m4a") || ext.equals(".aac")
                || ext.equals(".flac") || ext.equals(".ogg") || ext.equals(".wav")) return "audio";
        return "video";
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
