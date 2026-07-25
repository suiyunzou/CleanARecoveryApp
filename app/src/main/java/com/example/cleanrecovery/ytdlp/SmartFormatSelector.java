package com.example.cleanrecovery.ytdlp;

import com.example.cleanrecovery.extractor.ExtractorResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 智能格式选择器（替代 yt-dlp 的 -f）。
 *
 * <p>D5 策略：优先选择足够清晰的音视频合一轨；否则自动选择最佳纯视频 +
 * 最佳纯音频，交给后处理合并。用户只暴露清晰度档位，不暴露 format 表达式。</p>
 */
public final class SmartFormatSelector {

    private static final int AUTO_COMBINED_MIN_HEIGHT = 1080;

    public RequestedFormats select(ExtractorResult result, DownloadOptions opts) {
        if (opts == null) {
            opts = DownloadOptions.defaults();
        }
        QualityHint hint = opts.audioOnly ? QualityHint.AUDIO_ONLY : opts.quality;
        return select(result, hint);
    }

    public RequestedFormats select(ExtractorResult result, QualityHint hint) {
        if (result == null || result.getFormats().isEmpty()) {
            return null;
        }
        if (hint == null) {
            hint = QualityHint.AUTO;
        }
        if (hint == QualityHint.AUDIO_ONLY) {
            ExtractorResult.Format audio = bestAudio(result.getFormats());
            if (audio == null) {
                audio = bestCombinedAtOrBelow(result.getFormats(), 0);
            }
            return audio != null ? new RequestedFormats(audio) : null;
        }

        int targetHeight = hint.targetHeight();
        int combinedMinHeight = targetHeight > 0 ? targetHeight : AUTO_COMBINED_MIN_HEIGHT;

        ExtractorResult.Format combined = bestCombinedAtLeast(result.getFormats(), combinedMinHeight,
                targetHeight);
        if (combined != null) {
            return new RequestedFormats(combined);
        }

        ExtractorResult.Format video = bestVideo(result.getFormats(), targetHeight);
        ExtractorResult.Format audio = bestAudio(result.getFormats());
        if (video != null && audio != null) {
            List<ExtractorResult.Format> formats = new ArrayList<>(2);
            formats.add(video);
            formats.add(audio);
            return new RequestedFormats(formats, true);
        }

        ExtractorResult.Format fallbackCombined = bestCombinedAtOrBelow(result.getFormats(),
                targetHeight);
        if (fallbackCombined != null) {
            return new RequestedFormats(fallbackCombined);
        }
        if (video != null) {
            return new RequestedFormats(video);
        }
        return audio != null ? new RequestedFormats(audio) : null;
    }

    private static ExtractorResult.Format bestCombinedAtLeast(List<ExtractorResult.Format> formats,
                                                              int minHeight,
                                                              int targetHeight) {
        List<ExtractorResult.Format> candidates = new ArrayList<>();
        for (ExtractorResult.Format f : formats) {
            if (f == null || f.isAudioOnly() || f.isVideoOnly()) {
                continue;
            }
            int height = effectiveHeight(f);
            if (height < minHeight) {
                continue;
            }
            if (targetHeight > 0 && height > targetHeight) {
                continue;
            }
            candidates.add(f);
        }
        return max(candidates, VIDEO_LIKE_COMPARATOR);
    }

    private static ExtractorResult.Format bestCombinedAtOrBelow(List<ExtractorResult.Format> formats,
                                                                int targetHeight) {
        List<ExtractorResult.Format> combined = new ArrayList<>();
        for (ExtractorResult.Format f : formats) {
            if (f == null || f.isAudioOnly() || f.isVideoOnly()) {
                continue;
            }
            if (targetHeight > 0 && effectiveHeight(f) > targetHeight) {
                continue;
            }
            combined.add(f);
        }
        if (!combined.isEmpty()) {
            return max(combined, VIDEO_LIKE_COMPARATOR);
        }
        if (targetHeight <= 0) {
            return null;
        }
        for (ExtractorResult.Format f : formats) {
            if (f != null && !f.isAudioOnly() && !f.isVideoOnly()) {
                combined.add(f);
            }
        }
        return max(combined, VIDEO_LIKE_COMPARATOR);
    }

    private static ExtractorResult.Format bestVideo(List<ExtractorResult.Format> formats,
                                                    int targetHeight) {
        List<ExtractorResult.Format> videos = new ArrayList<>();
        for (ExtractorResult.Format f : formats) {
            if (f != null && f.isVideoOnly()) {
                videos.add(f);
            }
        }
        return bestVideoLike(videos, targetHeight, targetHeight > 0);
    }

    private static ExtractorResult.Format bestVideoLike(List<ExtractorResult.Format> formats,
                                                        int targetHeight,
                                                        boolean preferAtOrBelowTarget) {
        if (formats.isEmpty()) {
            return null;
        }
        if (preferAtOrBelowTarget && targetHeight > 0) {
            ExtractorResult.Format bestUnderTarget = null;
            for (ExtractorResult.Format f : formats) {
                if (effectiveHeight(f) <= targetHeight
                        && (bestUnderTarget == null
                        || VIDEO_LIKE_COMPARATOR.compare(f, bestUnderTarget) > 0)) {
                    bestUnderTarget = f;
                }
            }
            if (bestUnderTarget != null) {
                return bestUnderTarget;
            }
        }
        return max(formats, VIDEO_LIKE_COMPARATOR);
    }

    private static ExtractorResult.Format bestAudio(List<ExtractorResult.Format> formats) {
        ExtractorResult.Format best = null;
        for (ExtractorResult.Format format : formats) {
            if (format != null && format.isAudioOnly()
                    && (best == null || AUDIO_COMPARATOR.compare(format, best) > 0)) {
                best = format;
            }
        }
        return best;
    }

    private static final Comparator<ExtractorResult.Format> VIDEO_LIKE_COMPARATOR =
            new Comparator<ExtractorResult.Format>() {
                @Override
                public int compare(ExtractorResult.Format left, ExtractorResult.Format right) {
                    int result = Integer.compare(effectiveHeight(left), effectiveHeight(right));
                    if (result == 0) result = Integer.compare(left.quality, right.quality);
                    if (result == 0) result = Integer.compare(left.fps, right.fps);
                    if (result == 0) result = Integer.compare(left.tbr, right.tbr);
                    if (result == 0) result = Long.compare(left.filesize, right.filesize);
                    return result;
                }
            };

    private static final Comparator<ExtractorResult.Format> AUDIO_COMPARATOR =
            new Comparator<ExtractorResult.Format>() {
                @Override
                public int compare(ExtractorResult.Format left, ExtractorResult.Format right) {
                    int result = Integer.compare(left.tbr, right.tbr);
                    if (result == 0) result = Long.compare(left.filesize, right.filesize);
                    if (result == 0) result = safe(left.ext).compareTo(safe(right.ext));
                    return result;
                }
            };

    private static ExtractorResult.Format max(List<ExtractorResult.Format> formats,
                                              Comparator<ExtractorResult.Format> comparator) {
        ExtractorResult.Format best = null;
        for (ExtractorResult.Format format : formats) {
            if (format != null && (best == null || comparator.compare(format, best) > 0)) {
                best = format;
            }
        }
        return best;
    }

    private static int effectiveHeight(ExtractorResult.Format f) {
        if (f == null) {
            return 0;
        }
        if (f.height > 0) {
            return f.height;
        }
        return Math.max(f.quality, 0);
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
