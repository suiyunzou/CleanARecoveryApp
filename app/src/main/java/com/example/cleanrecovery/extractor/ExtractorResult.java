package com.example.cleanrecovery.extractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 提取结果（对应 yt-dlp 的 info_dict）。
 *
 * <p>包含一个或多个 {@link Format}（直链），以及标题、扩展名等元数据。
 * 调用方可按 {@link Format#quality} 降序选择最佳画质。</p>
 */
public class ExtractorResult {

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

        public Format(String url, String ext, int quality, String vcodec, String acodec,
                      int tbr, int width, int height, long filesize, String description) {
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
        }

        /** 是否为纯音频格式。 */
        public boolean isAudioOnly() {
            return "none".equals(vcodec) && !"none".equals(acodec);
        }

        /** 是否为纯视频格式（无音轨）。 */
        public boolean isVideoOnly() {
            return "none".equals(acodec) && !"none".equals(vcodec);
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

    public ExtractorResult(String id, String title, String uploader,
                           List<Format> formats, String baseFilename, String ext) {
        this.id = id;
        this.title = title;
        this.uploader = uploader;
        this.formats = formats != null ? new ArrayList<>(formats) : new ArrayList<>();
        this.baseFilename = baseFilename;
        this.ext = ext;
    }

    public String getId() { return id; }
    public String getTitle() { return title; }
    public String getUploader() { return uploader; }
    public String getBaseFilename() { return baseFilename; }
    public String getExt() { return ext; }

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
