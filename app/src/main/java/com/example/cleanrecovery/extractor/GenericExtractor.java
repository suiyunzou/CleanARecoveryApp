package com.example.cleanrecovery.extractor;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通用兜底提取器（对应 yt-dlp GenericIE）。
 *
 * <p>yt-dlp 的 Generic 提取器作为最后兜底，直接请求 URL 检测 Content-Type：
 * <ul>
 *   <li>视频/音频流（mp4/webm/mp3/m4a/ogg/flac 等）→ 直接下载</li>
 *   <li>HLS 播放列表（m3u8）→ 由 {@link HlsDownloader} 处理分片下载合并</li>
 *   <li>HTML 网页（静态）→ 尝试从 meta 标签/og:video 提取媒体链接</li>
 *   <li>HTML 网页（JS 渲染）→ 由 {@link JsRendererExtractor} 渲染后提取</li>
 * </ul>
 *
 * <p>本实现聚焦直链场景：用户粘贴一个 .mp4/.mp3 等直链时直接下载，
 * 文件名从 URL 路径提取，扩展名从 Content-Type 或 URL 推断。</p>
 *
 * <p>HLS 与 JS 渲染检测通过 {@link HlsExtractorHook} 与 {@link JsRendererHook}
 * 回调注入，避免本类直接依赖 Android UI 组件（WebView），保持可单元测试性。</p>
 *
 * @see <a href="https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/generic.py">GenericIE</a>
 */
public class GenericExtractor implements Extractor {

    private static final String NAME = "generic";

    /** 媒体 MIME 类型到扩展名映射（对应 yt-dlp mimetype2ext）。 */
    private static final Map<String, String> MIME_TO_EXT = new HashMap<>();
    static {
        MIME_TO_EXT.put("video/mp4", "mp4");
        MIME_TO_EXT.put("video/webm", "webm");
        MIME_TO_EXT.put("video/x-matroska", "mkv");
        MIME_TO_EXT.put("video/quicktime", "mov");
        MIME_TO_EXT.put("video/x-flv", "flv");
        MIME_TO_EXT.put("video/avi", "avi");
        MIME_TO_EXT.put("audio/mpeg", "mp3");
        MIME_TO_EXT.put("audio/mp3", "mp3");
        MIME_TO_EXT.put("audio/mp4", "m4a");
        MIME_TO_EXT.put("audio/m4a", "m4a");
        MIME_TO_EXT.put("audio/aac", "aac");
        MIME_TO_EXT.put("audio/ogg", "ogg");
        MIME_TO_EXT.put("audio/flac", "flac");
        MIME_TO_EXT.put("audio/wav", "wav");
        MIME_TO_EXT.put("audio/x-wav", "wav");
        MIME_TO_EXT.put("audio/webm", "webm");
        // HLS 相关 MIME
        MIME_TO_EXT.put("application/vnd.apple.mpegurl", "ts");
        MIME_TO_EXT.put("application/x-mpegurl", "ts");
    }

    /** URL 路径扩展名正则。 */
    private static final Pattern EXT_PATTERN = Pattern.compile("\\.([a-zA-Z0-9]{2,4})(?:$|[?#])");

    /** og:video 元标签正则。 */
    private static final Pattern OG_VIDEO_PATTERN =
            Pattern.compile("<meta[^>]+property=[\"']og:video(?::url)?[\"'][^>]+content=[\"']([^\"']+)[\"']");

    /** HTML title 正则。 */
    private static final Pattern TITLE_PATTERN =
            Pattern.compile("<title[^>]*>([^<]+)</title>", Pattern.CASE_INSENSITIVE);

    /** SPA 框架特征正则：检测页面是否依赖 JS 渲染。 */
    private static final Pattern SPA_MARKER_PATTERN = Pattern.compile(
            "<div[^>]+id=[\"'](?:app|root|__next|__nuxt)[\"']"
            + "|<script[^>]*src=[\"'][^\"']*(?:react|vue|angular|nuxt|next)[^\"']*[\"']"
            + "|<noscript[^>]*>\\s*(?:enable|please enable)[^<]*javascript",
            Pattern.CASE_INSENSITIVE);

    /** HLS 提取钩子（由 UI 层注入，处理 m3u8 下载）。 */
    private static volatile HlsExtractorHook hlsHook;
    /** JS 渲染提取钩子（由 UI 层注入，处理 SPA 页面）。 */
    private static volatile JsRendererHook jsHook;

    /** HLS 提取钩子接口。 */
    public interface HlsExtractorHook {
        /**
         * 处理 m3u8 链接，返回可直接下载的合并后文件路径标识。
         *
         * @return 处理后的 {@link ExtractorResult}，含合并后的 TS 文件信息
         */
        ExtractorResult handleHls(String m3u8Url, String title) throws ExtractorException, IOException;
    }

    /** JS 渲染提取钩子接口。 */
    public interface JsRendererHook {
        /**
         * 渲染 JS 页面并提取媒体链接。
         *
         * @return 渲染后提取到的媒体 URL 列表（可能为空）
         */
        List<String> renderAndExtract(String pageUrl) throws ExtractorException, IOException;
    }

    /** 注册 HLS 提取钩子。 */
    public static void setHlsHook(HlsExtractorHook hook) {
        hlsHook = hook;
    }

    /** 注册 JS 渲染提取钩子。 */
    public static void setJsHook(JsRendererHook hook) {
        jsHook = hook;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Generic 始终返回 true，作为兜底。 */
    @Override
    public boolean suitable(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    @Override
    public ExtractorResult extract(String url) throws ExtractorException, IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "URL 为空");
        }

        // 1. 用 HEAD 请求获取 Content-Type
        Map<String, String> headers = ExtractorHttp.defaultHeaders();
        String contentType = null;
        try {
            contentType = ExtractorHttp.headContentType(url, headers);
        } catch (IOException ignored) {
            // 某些服务器不支持 HEAD，降级到 GET
        }

        // 2. 判断是否为 HLS 播放列表（m3u8）
        if (HlsDownloader.isHlsUrl(url) || HlsDownloader.isHlsContentType(contentType)) {
            return handleHls(url, contentType);
        }

        // 3. 判断是否为媒体流（mp4/mp3 等）
        if (isMediaContentType(contentType)) {
            return buildDirectResult(url, contentType);
        }

        // 4. 从 URL 扩展名判断
        String urlExt = extractExtFromUrl(url);
        if (isMediaExt(urlExt)) {
            return buildDirectResult(url, extToMime(urlExt));
        }

        // 5. 下载网页，尝试从 og:video 提取
        String webpage = ExtractorHttp.downloadWebpage(url, headers);
        Matcher m = OG_VIDEO_PATTERN.matcher(webpage);
        if (m.find()) {
            String mediaUrl = m.group(1);
            if (mediaUrl.startsWith("http")) {
                String ct = ExtractorHttp.headContentType(mediaUrl, headers);
                if (isMediaContentType(ct)) {
                    return buildResultWithPageTitle(mediaUrl, ct, webpage);
                }
                // 检测嵌入的 m3u8
                if (HlsDownloader.isHlsUrl(mediaUrl)) {
                    return handleHls(mediaUrl, ct);
                }
            }
        }

        // 6. 检测页面是否为 SPA（需 JS 渲染）
        if (isJsRenderedPage(webpage) && jsHook != null) {
            return handleJsRendered(url, webpage);
        }

        // 7. 无法识别
        throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED,
                "无法识别的链接：不是直链媒体，也未找到嵌入式视频");
    }

    /**
     * 处理 HLS 播放列表。
     *
     * <p>若已注册 {@link HlsExtractorHook}，委托给钩子处理；
     * 否则抛出 UNSUPPORTED 异常提示用户。</p>
     */
    private ExtractorResult handleHls(String m3u8Url, String contentType) throws ExtractorException, IOException {
        if (hlsHook == null) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED,
                    "检测到 HLS 播放列表（m3u8），但未注册 HLS 处理器");
        }
        String title = extractBaseNameFromUrl(m3u8Url);
        if (title == null) title = "hls_stream_" + System.currentTimeMillis();
        return hlsHook.handleHls(m3u8Url, title);
    }

    /**
     * 处理 JS 渲染页面。
     *
     * <p>委托给 {@link JsRendererHook} 渲染页面并提取媒体链接。
     * 若渲染后提取到 m3u8，进一步路由到 HLS 处理。</p>
     */
    private ExtractorResult handleJsRendered(String pageUrl, String webpage) throws ExtractorException, IOException {
        List<String> mediaUrls = jsHook.renderAndExtract(pageUrl);
        if (mediaUrls == null || mediaUrls.isEmpty()) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED,
                    "JS 渲染后未找到媒体资源");
        }

        // 优先选择 HLS（通常质量更高）
        for (String u : mediaUrls) {
            if (HlsDownloader.isHlsUrl(u)) {
                return handleHls(u, null);
            }
        }

        // 否则选择第一个媒体 URL 作为直链
        String mediaUrl = mediaUrls.get(0);
        String ct = null;
        try {
            ct = ExtractorHttp.headContentType(mediaUrl, ExtractorHttp.defaultHeaders());
        } catch (IOException ignored) {}
        if (isMediaContentType(ct)) {
            return buildResultWithPageTitle(mediaUrl, ct, webpage);
        }
        String ext = extractExtFromUrl(mediaUrl);
        if (isMediaExt(ext)) {
            return buildResultWithPageTitle(mediaUrl, extToMime(ext), webpage);
        }

        throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED,
                "JS 渲染提取到的 URL 不是有效媒体: " + mediaUrl);
    }

    /** 判断 HTML 是否为依赖 JS 渲染的 SPA 页面。 */
    static boolean isJsRenderedPage(String html) {
        if (html == null || html.isEmpty()) return false;
        // 1. 检测 SPA 框架特征
        if (SPA_MARKER_PATTERN.matcher(html).find()) return true;
        // 2. 检测页面 body 内容极少（典型 SPA 占位）
        int bodyStart = html.toLowerCase().indexOf("<body");
        if (bodyStart > 0) {
            int bodyEnd = html.toLowerCase().indexOf("</body>");
            if (bodyEnd > bodyStart) {
                String body = html.substring(bodyStart, bodyEnd);
                // 去除 script 标签后的可见文本
                String visible = body.replaceAll("(?is)<script[^>]*>.*?</script>", "")
                        .replaceAll("(?s)<[^>]+>", "").trim();
                if (visible.length() < 200 && html.toLowerCase().contains("<script")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 构建直链下载结果。 */
    private ExtractorResult buildDirectResult(String url, String contentType) {
        String ext = mimeToExt(contentType);
        if (ext == null) ext = extractExtFromUrl(url);
        if (ext == null) ext = "mp4";

        String baseName = extractBaseNameFromUrl(url);
        String title = baseName != null ? baseName : "media_" + System.currentTimeMillis();

        ExtractorResult.Format fmt = new ExtractorResult.Format(
                url, ext, 0, "unknown", "unknown",
                0, 0, 0, 0, null);

        return new ExtractorResult(title, title, null,
                Collections.singletonList(fmt), title, ext);
    }

    /** 构建带网页标题的结果。 */
    private ExtractorResult buildResultWithPageTitle(String mediaUrl, String contentType, String webpage) {
        String ext = mimeToExt(contentType);
        if (ext == null) ext = extractExtFromUrl(mediaUrl);
        if (ext == null) ext = "mp4";

        String title = "media_" + System.currentTimeMillis();
        Matcher tm = TITLE_PATTERN.matcher(webpage);
        if (tm.find()) {
            title = tm.group(1).trim();
        }

        ExtractorResult.Format fmt = new ExtractorResult.Format(
                mediaUrl, ext, 0, "unknown", "unknown",
                0, 0, 0, 0, null);

        return new ExtractorResult(title, title, null,
                Collections.singletonList(fmt), title, ext);
    }

    /** 判断 Content-Type 是否为媒体类型。 */
    static boolean isMediaContentType(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.startsWith("video/") || lower.startsWith("audio/")
                || lower.contains("octet-stream");
    }

    /** MIME 类型转扩展名。 */
    static String mimeToExt(String mime) {
        if (mime == null) return null;
        String lower = mime.toLowerCase();
        int semi = lower.indexOf(';');
        if (semi > 0) lower = lower.substring(0, semi).trim();
        return MIME_TO_EXT.get(lower);
    }

    /** 从 URL 路径提取扩展名（不含点）。 */
    static String extractExtFromUrl(String url) {
        if (url == null) return null;
        try {
            String path = new URL(url).getPath();
            Matcher m = EXT_PATTERN.matcher(path);
            if (m.find()) return m.group(1).toLowerCase();
        } catch (Exception ignored) {}
        return null;
    }

    /** 从 URL 路径提取基础文件名（不含扩展名）。 */
    static String extractBaseNameFromUrl(String url) {
        if (url == null) return null;
        try {
            String path = new URL(url).getPath();
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = name.lastIndexOf('.');
            return dot > 0 ? name.substring(0, dot) : (name.isEmpty() ? null : name);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 判断扩展名是否为媒体类型。 */
    static boolean isMediaExt(String ext) {
        if (ext == null) return false;
        return MIME_TO_EXT.containsValue(ext.toLowerCase())
                || ext.matches("(?i)mp4|webm|mkv|mov|flv|avi|mp3|m4a|aac|ogg|flac|wav|opus");
    }

    /** 扩展名转 MIME 类型。 */
    static String extToMime(String ext) {
        if (ext == null) return null;
        for (Map.Entry<String, String> e : MIME_TO_EXT.entrySet()) {
            if (e.getValue().equalsIgnoreCase(ext)) return e.getKey();
        }
        return null;
    }
}
