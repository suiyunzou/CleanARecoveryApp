package com.example.cleanrecovery.ytdlp;

import android.util.Log;

import java.io.File;

/**
 * FFmpeg/libav 后处理桥。
 *
 * <p>项目当前只打包了 libavcodec/libavformat/libavutil，没有 JNI 封装库和头文件。
 * 因此本类只负责检测共享库，真正合并先由 Android MediaMuxer 后备实现承担。</p>
 */
public final class FFmpegNative {

    private static final String TAG = "FFmpegNative";

    private static volatile boolean librariesLoaded;
    private static volatile String loadError;

    private FFmpegNative() {}

    public static synchronized boolean ensureLibrariesLoaded() {
        if (librariesLoaded) {
            return true;
        }
        if (loadError != null) {
            return false;
        }
        try {
            System.loadLibrary("avutil");
            System.loadLibrary("avcodec");
            System.loadLibrary("avformat");
            librariesLoaded = true;
            return true;
        } catch (UnsatisfiedLinkError e) {
            loadError = e.getMessage();
            Log.w(TAG, "libav load failed: " + loadError);
            return false;
        }
    }

    public static boolean isBridgeAvailable() {
        return false;
    }

    public static String unavailableReason() {
        if (!ensureLibrariesLoaded()) {
            return loadError != null ? loadError : "libav libraries unavailable";
        }
        return "libav JNI bridge not implemented";
    }

    public static boolean merge(File video, File audio, File output) {
        return false;
    }

    public static boolean remux(File input, File output) {
        return false;
    }
}
