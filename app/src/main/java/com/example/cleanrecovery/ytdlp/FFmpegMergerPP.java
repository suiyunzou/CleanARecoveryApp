package com.example.cleanrecovery.ytdlp;

import android.content.Context;
import android.util.Log;

import java.io.File;

/** 双轨合并后处理（videoOnly + audioOnly → mp4）。 */
public final class FFmpegMergerPP implements PostProcessor {

    private static final String TAG = "FFmpegMergerPP";

    @SuppressWarnings("unused")
    private final Context context;

    public FFmpegMergerPP(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    @Override
    public DownloadResult process(DownloadResult result) throws PostProcessorException {
        if (result == null || !result.needsMerge) {
            return result;
        }
        File video = result.primaryFile();
        File audio = result.audioFile();
        File output = result.outputFile;
        if (video == null || audio == null || output == null) {
            throw fail(result, "missing merge input");
        }
        try {
            if (FFmpegNative.isBridgeAvailable() && FFmpegNative.merge(video, audio, output)) {
                Log.i(TAG, "merged by libav JNI: " + output.getName());
            } else {
                MediaMuxerUtil.merge(video, audio, output);
                Log.i(TAG, "merged by MediaMuxer fallback: " + output.getName());
            }
            result.outputFile = output;
            return result;
        } catch (Exception e) {
            throw fail(result, "merge failed: " + e.getMessage());
        }
    }

    @Override
    public PostProcessWhen when() {
        return PostProcessWhen.POST_PROCESS;
    }

    @Override
    public String name() {
        return "FFmpegMergerPP";
    }

    private static PostProcessorException fail(DownloadResult result, String message) {
        String detail = message + " (native=" + FFmpegNative.unavailableReason() + ")";
        result.error = detail;
        return new PostProcessorException(detail);
    }
}
