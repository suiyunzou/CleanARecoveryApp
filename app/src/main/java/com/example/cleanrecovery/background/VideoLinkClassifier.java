package com.example.cleanrecovery.background;

import android.util.Log;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 视频链接格式识别分类器（后台下载模块）。
 *
 * <p>根据 URL 特征、MIME 类型、协议标识等，将视频链接分为不同类别，
 * 以便 {@link BackgroundDownloadService} 选择最优下载策略。</p>
 *
 * <h3>支持的分类</h3>
 * <ul>
 *   <li>{@link LinkType#DIRECT_MP4}：直接 MP4/WebM URL，可断点续传</li>
 *   <li>{@link LinkType#DIRECT_AUDIO}：直接音频 URL（m4a/mp3）</li>
 *   <li>{@link LinkType#HLS}：HLS 自适应流（.m3u8），需分段下载+合并</li>
 *   <li>{@link LinkType#DASH}：DASH 自适应流（.mpd），需分段下载+合并</li>
 *   <li>{@link LinkType#GOOGLEVIDEO}：YouTube googlevideo 链接，需特殊请求头</li>
 *   <li>{@link LinkType#ENCRYPTED}：加密流（含加密参数），暂不支持</li>
 *   <li>{@link LinkType#UNKNOWN}：未知类型，尝试直接下载</li>
 * </ul>
 */
public final class VideoLinkClassifier {
    private static final String TAG = "LinkClassifier";

    /** 链接类型枚举。 */
    public enum LinkType {
        /** 直接 MP4/WebM 视频文件。 */
        DIRECT_MP4,
        /** 直接音频文件（m4a/mp3/aac）。 */
        DIRECT_AUDIO,
        /** HLS 自适应流（.m3u8）。 */
        HLS,
        /** DASH 自适应流（.mpd）。 */
        DASH,
        /** YouTube googlevideo.com 视频流。 */
        GOOGLEVIDEO,
        /** 加密流（含加密参数，暂不支持）。 */
        ENCRYPTED,
        /** 未知类型。 */
        UNKNOWN
    }

    /** 分类结果。 */
    public static final class ClassifyResult {
        public final LinkType type;
        /** 推荐的文件扩展名（不含点）。 */
        public final String ext;
        /** 是否需要分段处理。 */
        public final boolean segmented;
        /** 是否需要特殊请求头。 */
        public final boolean needsSpecialHeaders;
        /** 预估分片数量（0 表示未知或非分段）。 */
        public final int estimatedSegments;
        /** 备注（如格式详情）。 */
        public final String note;

        ClassifyResult(LinkType type, String ext, boolean segmented,
                       boolean needsSpecialHeaders, int estimatedSegments, String note) {
            this.type = type;
            this.ext = ext;
            this.segmented = segmented;
            this.needsSpecialHeaders = needsSpecialHeaders;
            this.estimatedSegments = estimatedSegments;
            this.note = note;
        }
    }

    private static final Pattern HLS_PATTERN = Pattern.compile(
            "\\.m3u8(\\?|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DASH_PATTERN = Pattern.compile(
            "\\.mpd(\\?|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MP4_PATTERN = Pattern.compile(
            "\\.mp4(\\?|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEBM_PATTERN = Pattern.compile(
            "\\.webm(\\?|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern M4A_PATTERN = Pattern.compile(
            "\\.(m4a|aac)(\\?|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MP3_PATTERN = Pattern.compile(
            "\\.mp3(\\?|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TS_PATTERN = Pattern.compile(
            "\\.ts(\\?|$)", Pattern.CASE_INSENSITIVE);

    private VideoLinkClassifier() {
    }

    /**
     * 分类视频链接。
     *
     * @param url       视频 URL
     * @param mimeType  MIME 类型（可为 null）
     * @param headers   请求头（可为 null）
     * @return 分类结果
     */
    public static ClassifyResult classify(String url, String mimeType, Map<String, String> headers) {
        if (url == null || url.isEmpty()) {
            return new ClassifyResult(LinkType.UNKNOWN, "bin", false, false, 0, "空URL");
        }
        String lower = url.toLowerCase(Locale.US);

        // 1. YouTube googlevideo.com
        if (lower.contains("googlevideo.com") && lower.contains("/videoplayback")) {
            // 过滤 SABR 协议流
            if (lower.contains("sabr=1")) {
                return new ClassifyResult(LinkType.ENCRYPTED, "mp4", false, true,
                        0, "SABR协议流，暂不支持");
            }
            String itag = extractParam(url, "itag");
            String mime = extractParam(url, "mime");
            String ext = guessExtFromMime(mime, itag);
            boolean isAudio = mime != null && mime.contains("audio");
            LinkType type = isAudio ? LinkType.DIRECT_AUDIO : LinkType.GOOGLEVIDEO;
            return new ClassifyResult(type, ext, false, true, 0,
                    "itag=" + itag + ", mime=" + mime);
        }

        // 2. 加密流（含加密参数）
        if (lower.contains("encryption") || lower.contains("encrypted")
                || (lower.contains("key=") && lower.contains("iv="))) {
            return new ClassifyResult(LinkType.ENCRYPTED, "enc", false, false,
                    0, "加密流，暂不支持");
        }

        // 3. HLS 自适应流
        if (HLS_PATTERN.matcher(lower).find() || "application/vnd.apple.mpegurl".equals(mimeType)
                || "application/x-mpegurl".equals(mimeType)) {
            // Master playlist 还是 Media playlist 需要解析后才能确定分片数
            return new ClassifyResult(LinkType.HLS, "ts", true, false,
                    0, "HLS自适应流，需解析m3u8");
        }

        // 4. DASH 自适应流
        if (DASH_PATTERN.matcher(lower).find() || "application/dash+xml".equals(mimeType)) {
            return new ClassifyResult(LinkType.DASH, "mp4", true, false,
                    0, "DASH自适应流，需解析mpd");
        }

        // 5. 直接视频文件
        if (MP4_PATTERN.matcher(lower).find() || "video/mp4".equals(mimeType)) {
            return new ClassifyResult(LinkType.DIRECT_MP4, "mp4", false, false,
                    0, "直接MP4文件");
        }
        if (WEBM_PATTERN.matcher(lower).find() || "video/webm".equals(mimeType)) {
            return new ClassifyResult(LinkType.DIRECT_MP4, "webm", false, false,
                    0, "直接WebM文件");
        }

        // 6. 直接音频文件
        if (M4A_PATTERN.matcher(lower).find() || "audio/mp4".equals(mimeType)
                || "audio/aac".equals(mimeType)) {
            return new ClassifyResult(LinkType.DIRECT_AUDIO, "m4a", false, false,
                    0, "直接AAC/M4A音频");
        }
        if (MP3_PATTERN.matcher(lower).find() || "audio/mpeg".equals(mimeType)) {
            return new ClassifyResult(LinkType.DIRECT_AUDIO, "mp3", false, false,
                    0, "直接MP3音频");
        }

        // 7. TS 分片（HLS 的单个分片）
        if (TS_PATTERN.matcher(lower).find() || "video/mp2t".equals(mimeType)) {
            return new ClassifyResult(LinkType.HLS, "ts", true, false,
                    1, "HLS分片文件");
        }

        // 8. 通过 MIME 判断
        if (mimeType != null) {
            if (mimeType.startsWith("video/")) {
                return new ClassifyResult(LinkType.DIRECT_MP4, "mp4", false, false,
                        0, "视频MIME: " + mimeType);
            }
            if (mimeType.startsWith("audio/")) {
                return new ClassifyResult(LinkType.DIRECT_AUDIO, "m4a", false, false,
                        0, "音频MIME: " + mimeType);
            }
        }

        // 9. URL 含 mime 参数
        if (lower.contains("mime=video")) {
            return new ClassifyResult(LinkType.DIRECT_MP4, "mp4", false, false,
                    0, "URL含video mime参数");
        }
        if (lower.contains("mime=audio")) {
            return new ClassifyResult(LinkType.DIRECT_AUDIO, "m4a", false, false,
                    0, "URL含audio mime参数");
        }

        Log.d(TAG, "未知链接类型: " + url.substring(0, Math.min(80, url.length())));
        return new ClassifyResult(LinkType.UNKNOWN, "bin", false, false,
                0, "未知类型，尝试直接下载");
    }

    /** 从 URL 提取查询参数值。 */
    private static String extractParam(String url, String key) {
        int q = url.indexOf('?');
        if (q < 0) return null;
        String query = url.substring(q + 1);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(key)) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    /** 根据 MIME 类型或 itag 猜测扩展名。 */
    private static String guessExtFromMime(String mime, String itag) {
        if (mime != null) {
            if (mime.contains("mp4")) return "mp4";
            if (mime.contains("webm")) return "webm";
            if (mime.contains("audio")) return "m4a";
        }
        // 根据 itag 推断（参考 yt-dlp _ITAG_TABLE）
        if (itag != null) {
            switch (itag) {
                case "18": case "22": case "37": case "38":
                case "82": case "83": case "84": case "85":
                case "133": case "134": case "135": case "136":
                case "137": case "160": case "264": case "298": case "299":
                    return "mp4";
                case "242": case "243": case "244": case "245":
                case "246": case "247": case "248": case "271": case "278":
                case "302": case "303": case "308": case "313": case "315":
                    return "webm";
                case "140": case "141": case "171": case "249":
                case "250": case "251":
                    return "m4a";
                default:
                    break;
            }
        }
        return "mp4";
    }
}
