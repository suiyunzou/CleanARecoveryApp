package com.example.cleanrecovery.extractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 提取结果（对应 yt-dlp 的 info_dict）。
 *
 * <p>包含一个或多个 {@link Format}（直链），以及标题、扩展名等元数据。
 * 调用方可按 {@link Format#quality} 降序选择最佳画质。</p>
 *
 * <p><b>M1 扩展</b>（对应 yt-dlp 移植计划 §2）：</p>
 * <ul>
 *   <li>{@link Format} 新增 {@code protocol} / {@code formatId} / {@code fps} / {@code language} /
 *       {@code httpHeaders}，驱动 {@code SmartProtocolRouter} 与每 format 专属请求头</li>
 *   <li>顶层新增 {@code duration} / {@code thumbnailUrl} / {@code description} /
 *       {@code uploaderId} / {@code webpageUrl} / {@code extractorKey}，供归档与后处理使用</li>
 *   <li>旧 10 参 {@code Format} 构造器与新 6 参顶层构造器保留，确保不破坏现有 6 个提取器调用点</li>
 * </ul>
 */
public class ExtractorResult {

    /** 默认 protocol 值，表示 Extractor 未显式声明（由 {@code SmartProtocolRouter} 兜底猜测）。 */
    public static final String PROTOCOL_UNKNOWN = "unknown";

    /** 视频/音频格式（对应 yt-dlp formats 列表）。 */
    public static class Format {
        /** 直链 URL */
        public final String url;
        /** 扩展名（mp4/m4a/mp3 等） */
        public final String ext;
        /** 画质标识（如 1080p/720p，数值越大越高） */
        public final int quality;
        /** 视频编码（如 avc1/hevc），纯音频为 "none" */
        public final String vcodec;
        /** 音频编码（如 mp4a/aac），纯视频为 "none" */
        public final String acodec;
        /** 码率（kbps），0 表示未知 */
        public final int tbr;
        /** 宽度（像素），纯音频为 0 */
        public final int width;
        /** 高度（像素），纯音频为 0 */
        public final int height;
        /** 文件大小（字节），0 表示未知 */
        public final long filesize;
        /** 格式描述（如 "1080P 高清"） */
        public final String description;

        // ===== M1 新增字段（驱动 protocol 路由 + 每 format 专属请求头）=====

        /**
         * 下载协议（http/https/m3u8/m3u8_native/dash）。
         * <p>由 Extractor 在解析时填入，驱动 {@code SmartProtocolRouter} 选 {@code FileDownloader}。
         * 未填时为 {@link #PROTOCOL_UNKNOWN}，由 Router 兜底（按 URL 后缀猜测）。</p>
         */
        public final String protocol;

        /** 格式唯一标识（对应 yt-dlp format_id），用于归档与日志 */
        public final String formatId;

        /** 帧率（fps），0 表示未知 */
        public final int fps;

        /** 语言标签（如 "zh-CN" / "en"），多音轨选择用 */
        public final String language;

        /** 每 format 专属请求头（替代 googlevideo 硬编码 Referer/Origin） */
        public final Map<String, String> httpHeaders;

        /**
         * @deprecated 使用 {@link Builder} 或全参构造器。本构造器仅保留兼容现有提取器调用点。
         */
        @Deprecated
        public Format(String url, String ext, int quality, String vcodec, String acodec,
                      int tbr, int width, int height, long filesize, String description) {
            this(url, ext, quality, vcodec, acodec, tbr, width, height, filesize, description,
                    PROTOCOL_UNKNOWN, null, 0, null, null);
        }

        /** 全参构造器（M1 新增字段）。新代码应优先使用 {@link Builder}。 */
        public Format(String url, String ext, int quality, String vcodec, String acodec,
                      int tbr, int width, int height, long filesize, String description,
                      String protocol, String formatId, int fps, String language,
                      Map<String, String> httpHeaders) {
            this.url = url;
            this.ext = ext;
            this.quality = quality;
            this.vcodec = vcodec;
            this.acodec = acodec;
            this.tbr = tbr;
            this.width = width;
            this.height = height;
            this.filesize = filesize;
            this.description = description;
            this.protocol = protocol != null ? protocol : PROTOCOL_UNKNOWN;
            this.formatId = formatId;
            this.fps = fps;
            this.language = language;
            this.httpHeaders = httpHeaders != null
                    ? Collections.unmodifiableMap(httpHeaders) : null;
        }

        /** 是否为纯音频格式。 */
        public boolean isAudioOnly() {
            return "none".equals(vcodec) && !"none".equals(acodec);
        }

        /** 是否为纯视频格式（无音轨）。 */
        public boolean isVideoOnly() {
            return "none".equals(acodec) && !"none".equals(vcodec);
        }

        /** 是否为 HLS 协议（m3u8 / m3u8_native）。 */
        public boolean isHls() {
            return "m3u8".equals(protocol) || "m3u8_native".equals(protocol);
        }

        /** 是否为 DASH 协议。 */
        public boolean isDash() {
            return "dash".equals(protocol);
        }

        /**
         * Builder（推荐用法）。
         *
         * <pre>{@code
         * Format f = new Format.Builder()
         *     .url(u).ext("mp4").quality(1080)
         *     .vcodec("avc1").acodec("mp4a")
         *     .height(1080).width(1920)
         *     .protocol("http").formatId("137+140")
         *     .httpHeader("Referer", "https://www.youtube.com/")
         *     .build();
         * }</pre>
         */
        public static class Builder {
            private String url;
            private String ext;
            private int quality;
            private String vcodec = "none";
            private String acodec = "none";
            private int tbr;
            private int width;
            private int height;
            private long filesize;
            private String description;
            private String protocol = PROTOCOL_UNKNOWN;
            private String formatId;
            private int fps;
            private String language;
            private final java.util.HashMap<String, String> httpHeaders = new java.util.HashMap<>();

            public Builder url(String url) { this.url = url; return this; }
            public Builder ext(String ext) { this.ext = ext; return this; }
            public Builder quality(int quality) { this.quality = quality; return this; }
            public Builder vcodec(String v) { this.vcodec = v; return this; }
            public Builder acodec(String a) { this.acodec = a; return this; }
            public Builder tbr(int tbr) { this.tbr = tbr; return this; }
            public Builder width(int w) { this.width = w; return this; }
            public Builder height(int h) { this.height = h; return this; }
            public Builder filesize(long s) { this.filesize = s; return this; }
            public Builder description(String d) { this.description = d; return this; }
            public Builder protocol(String p) { this.protocol = p; return this; }
            public Builder formatId(String id) { this.formatId = id; return this; }
            public Builder fps(int fps) { this.fps = fps; return this; }
            public Builder language(String lang) { this.language = lang; return this; }
            public Builder httpHeader(String k, String v) { this.httpHeaders.put(k, v); return this; }
            public Builder httpHeaders(Map<String, String> h) {
                this.httpHeaders.clear();
                if (h != null) this.httpHeaders.putAll(h);
                return this;
            }

            public Format build() {
                return new Format(url, ext, quality, vcodec, acodec, tbr, width, height,
                        filesize, description, protocol, formatId, fps, language,
                        httpHeaders.isEmpty() ? null : httpHeaders);
            }
        }
    }

    private final String id;
    private final String title;
    private final String uploader;
    private final List<Format> formats;
    /** 推荐文件名（不含扩展名） */
    private final String baseFilename;
    /** 推荐扩展名（mp4/mp3 等） */
    private final String ext;

    // ===== M1 新增顶层字段（归档键 + 后处理元数据）=====

    /** 时长（秒），0 表示未知 */
    private final long duration;
    /** 缩略图 URL，供 EmbedThumbnailPP 使用 */
    private final String thumbnailUrl;
    /** 描述，供 FFmpegMetadataPP 使用 */
    private final String description;
    /** 上传者 ID，归档键组成 */
    private final String uploaderId;
    /** 来源网页 URL，归档键组成 */
    private final String webpageUrl;
    /** 提取器 key（如 "Bilibili" / "YouTube"），归档键 extractor_key+id */
    private final String extractorKey;

    /** @deprecated 使用带归档字段的构造器。 */
    @Deprecated
    public ExtractorResult(String id, String title, String uploader,
                           List<Format> formats, String baseFilename, String ext) {
        this(id, title, uploader, formats, baseFilename, ext,
                0L, null, null, null, null, null);
    }

    public ExtractorResult(String id, String title, String uploader,
                           List<Format> formats, String baseFilename, String ext,
                           long duration, String thumbnailUrl, String description,
                           String uploaderId, String webpageUrl, String extractorKey) {
        this.id = id;
        this.title = title;
        this.uploader = uploader;
        this.formats = formats != null ? new ArrayList<>(formats) : new ArrayList<>();
        this.baseFilename = baseFilename;
        this.ext = ext;
        this.duration = duration;
        this.thumbnailUrl = thumbnailUrl;
        this.description = description;
        this.uploaderId = uploaderId;
        this.webpageUrl = webpageUrl;
        this.extractorKey = extractorKey;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getUploader() { return uploader; }
    public String getBaseFilename() { return baseFilename; }
    public String getExt() { return ext; }

    /** 时长（秒），0 表示未知。M1 新增。 */
    public long getDuration() { return duration; }
    /** 缩略图 URL。M1 新增。 */
    public String getThumbnailUrl() { return thumbnailUrl; }
    /** 描述。M1 新增。 */
    public String getDescription() { return description; }
    /** 上传者 ID。M1 新增。 */
    public String getUploaderId() { return uploaderId; }
    /** 来源网页 URL。M1 新增。 */
    public String getWebpageUrl() { return webpageUrl; }
    /** 提取器 key（归档用）。M1 新增。 */
    public String getExtractorKey() { return extractorKey; }

    /**
     * 归档键（extractor_key + id），对齐 yt-dlp archive 行。
     * @return 归档键，extractorKey 或 id 为 null 时返回 null
     */
    public String getArchiveKey() {
        if (extractorKey == null || id == null) return null;
        return extractorKey + " " + id;
    }

    /** 获取所有格式（不可变）。 */
    public List<Format> getFormats() {
        return Collections.unmodifiableList(formats);
    }

    /**
     * 获取最佳视频+音频合并格式（对应 yt-dlp 的 best 格式选择）。
     *
     * <p>优先选择同时包含音视频的格式；若仅有分离的 DASH 流，
     * 则分别返回最佳视频和最佳音频，调用方需自行合并（或仅下载视频流）。</p>
     */
    public Format getBestCombinedFormat() {
        Format best = null;
        for (Format f : formats) {
            if (!f.isAudioOnly() && !f.isVideoOnly()) {
                if (best == null || f.quality > best.quality) best = f;
            }
        }
        return best;
    }

    /** 获取最佳纯视频格式（DASH 场景）。 */
    public Format getBestVideoOnlyFormat() {
        Format best = null;
        for (Format f : formats) {
            if (f.isVideoOnly() && (best == null || f.height > best.height)) best = f;
        }
        return best;
    }

    /** 获取最佳纯音频格式（DASH 场景）。 */
    public Format getBestAudioOnlyFormat() {
        Format best = null;
        for (Format f : formats) {
            if (f.isAudioOnly() && (best == null || f.tbr > best.tbr)) best = f;
        }
        return best;
    }
}
