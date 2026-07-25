package com.example.cleanrecovery.ytdlp;

/**
 * 用户画质偏好（对应 yt-dlp format selection 的"档位"概念）。
 *
 * <p>智能零配置下默认 {@link #AUTO}，由 {@code SmartFormatSelector} 自行决定
 * （D5 策略：合一轨优先，否则 bv+ba 合并）。用户可在 UI 高级面板选择具体档位。</p>
 */
public enum QualityHint {
    /** 智能选择（默认）。 */
    AUTO,
    /** 4K（2160p）。 */
    P2160,
    /** 1080p。 */
    P1080,
    /** 720p。 */
    P720,
    /** 仅音频。 */
    AUDIO_ONLY;

    /** 转换为目标高度（像素），AUDIO_ONLY 返回 0。 */
    public int targetHeight() {
        switch (this) {
            case P2160: return 2160;
            case P1080: return 1080;
            case P720:  return 720;
            default:    return 0;
        }
    }
}
