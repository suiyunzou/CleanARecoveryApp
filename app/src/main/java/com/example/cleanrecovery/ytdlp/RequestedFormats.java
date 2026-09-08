package com.example.cleanrecovery.ytdlp;

import com.example.cleanrecovery.extractor.ExtractorResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已选定下载的格式集合（对应 yt-dlp requested_formats）。
 *
 * <p>由 {@code SmartFormatSelector} 根据 D5 策略选定：</p>
 * <ul>
 *   <li>单条 {@link ExtractorResult.Format}（合一轨，无需合并）</li>
 *   <li>两条（最佳 videoOnly + 最佳 audioOnly，{@link #needsMerge}=true，需 ffmpeg 合并）</li>
 * </ul>
 */
public final class RequestedFormats {

    private final List<ExtractorResult.Format> formats;
    /** 是否需要 ffmpeg 合并（双轨场景）。 */
    public final boolean needsMerge;

    public RequestedFormats(List<ExtractorResult.Format> formats, boolean needsMerge) {
        this.formats = formats != null ? new ArrayList<>(formats) : new ArrayList<>();
        this.needsMerge = needsMerge;
    }

    /** 单轨构造（合一轨或仅音频）。 */
    public RequestedFormats(ExtractorResult.Format single) {
        this.formats = single != null
                ? new ArrayList<>(Collections.singletonList(single))
                : new ArrayList<>();
        this.needsMerge = false;
    }

    /** 获取所有选定 format（不可变）。 */
    public List<ExtractorResult.Format> getFormats() {
        return Collections.unmodifiableList(formats);
    }

    /** 选定 format 数量（1 或 2）。 */
    public int size() { return formats.size(); }

    /** 是否为空（无可下载 format）。 */
    public boolean isEmpty() { return formats.isEmpty(); }

    /** 主 format（双轨时为 videoOnly，单轨时为唯一 format）。 */
    public ExtractorResult.Format primary() {
        return formats.isEmpty() ? null : formats.get(0);
    }

    /** 双轨场景的音频 format（无则 null）。 */
    public ExtractorResult.Format audio() {
        return formats.size() >= 2 ? formats.get(1) : null;
    }
}
