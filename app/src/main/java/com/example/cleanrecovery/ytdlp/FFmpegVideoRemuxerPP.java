package com.example.cleanrecovery.ytdlp;

import android.content.Context;
import android.util.Log;

import java.io.File;

/** 单轨转封装后处理（主要用于 ts → mp4）。 */
public final class FFmpegVideoRemuxerPP implements PostProcessor {

    private static final String TAG = "FFmpegVideoRemuxerPP";

    @SuppressWarnings("unused")
    private final Context context;

    public FFmpegVideoRemuxerPP(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    @Override
    public DownloadResult process(DownloadResult result) throws PostProcessorException {
        if (result == null || result.needsMerge || result.outputFile == null) {
            return result;
        }
        File input = result.outputFile;
        if (!needsRemux(input)) {
            return result;
        }
        File output = availableMp4Sibling(input);
        try {
            if (FFmpegNative.isBridgeAvailable() && FFmpegNative.remux(input, output)) {
                Log.i(TAG, "remuxed by libav JNI: " + output.getName());
            } else {
                MediaMuxerUtil.remux(input, output);
                Log.i(TAG, "remuxed by MediaMuxer fallback: " + output.getName());
            }
            result.outputFile = output;
            return result;
        } catch (Exception e) {
            String detail = "remux failed: " + e.getMessage()
                    + " (native=" + FFmpegNative.unavailableReason() + ")";
            result.error = detail;
            throw new PostProcessorException(detail);
        }
    }

    @Override
    public PostProcessWhen when() {
        return PostProcessWhen.POST_PROCESS;
    }

    @Override
    public String name() {
        return "FFmpegVideoRemuxerPP";
    }

    private static boolean needsRemux(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.US);
        return name.endsWith(".ts") || name.endsWith(".m2ts");
    }

    private static File availableMp4Sibling(File input) {
        String name = input.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        File parent = input.getParentFile();
        File candidate = new File(parent, base + ".mp4");
        if (!candidate.exists()) {
            return candidate;
        }
        for (int i = 1; i <= 9999; i++) {
            candidate = new File(parent, base + "_" + i + ".mp4");
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(parent, base + "_" + System.currentTimeMillis() + ".mp4");
    }
}
