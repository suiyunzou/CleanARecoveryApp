package com.example.cleanrecovery.scan;

import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.util.PathManager;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 扫描诊断日志：同时输出到 logcat 与本地文件（PathManager.logsDir()）。
 *
 * 文件日志设计：
 *  - 按天滚动：scan_YYYYMMDD.log
 *  - 追加写入：多次扫描累计在同一文件
 *  - 线程安全：ReentrantLock 保护并发写入
 *  - 失败降级：文件写入失败不影响 logcat 输出与主流程
 */
public final class ScanDiagnostics {
    public static final String TAG = "CleanRecovery";

    private static final ReentrantLock FILE_LOCK = new ReentrantLock();
    private static volatile boolean fileLoggingEnabled = true;

    private ScanDiagnostics() {
    }

    /** 临时关闭文件日志（例如在单元测试中）。 */
    public static void setFileLoggingEnabled(boolean enabled) {
        fileLoggingEnabled = enabled;
    }

    public static void scanStart(boolean scanAll, int typeCount) {
        info(scanAll ? "scanStart mode=scanAll types=" + typeCount : "scanStart mode=single");
    }

    public static void prepareComplete(int totalEntries) {
        info("prepareComplete totalEntries=" + totalEntries);
    }

    public static void typeStart(RecoveryType type, int typeIndex, int typeCount) {
        info(String.format(Locale.US, "typeStart type=%s index=%d/%d", type, typeIndex + 1, typeCount));
    }

    public static void phaseStart(ScanProgressTracker.Phase phase, RecoveryType type) {
        info(String.format(Locale.US, "phaseStart phase=%s type=%s", phase, type));
    }

    public static void phaseEnd(
            ScanProgressTracker.Phase phase,
            RecoveryType type,
            long durationMs,
            int scanned,
            int found
    ) {
        info(String.format(Locale.US,
                "phaseEnd phase=%s type=%s durationMs=%d scanned=%d found=%d",
                phase, type, durationMs, scanned, found));
    }

    public static void phaseProgress(ScanProgressTracker.Phase phase, int scanned, int found) {
        debug(String.format(Locale.US, "phaseProgress phase=%s scanned=%d found=%d", phase, scanned, found));
    }

    public static void scanComplete(
            int scanned,
            int found,
            int duplicatesSkipped,
            int sourceVisible,
            int sourceMediastore,
            int sourceCache,
            int suspectedDeleted,
            long totalDurationMs
    ) {
        info(String.format(Locale.US,
                "scanComplete scanned=%d found=%d duplicatesSkipped=%d "
                        + "sourceVisible=%d sourceMediastore=%d sourceCache=%d "
                        + "suspectedDeleted=%d totalDurationMs=%d",
                scanned, found, duplicatesSkipped,
                sourceVisible, sourceMediastore, sourceCache,
                suspectedDeleted, totalDurationMs));
    }

    public static void algorithmStart(String algorithmId, RecoveryType type) {
        info(String.format(Locale.US, "algorithmStart id=%s type=%s", algorithmId, type));
    }

    public static void algorithmEnd(
            String algorithmId,
            RecoveryType type,
            long durationMs,
            int processed,
            int found,
            int duplicatesSkipped
    ) {
        info(String.format(Locale.US,
                "algorithmEnd id=%s type=%s durationMs=%d processed=%d found=%d duplicatesSkipped=%d",
                algorithmId, type, durationMs, processed, found, duplicatesSkipped));
    }

    public static void algorithmSkipped(String algorithmId, String reason) {
        info(String.format(Locale.US, "algorithmSkipped id=%s reason=%s", algorithmId, reason));
    }

    public static void algorithmError(String algorithmId, String detail) {
        warn(String.format(Locale.US, "algorithmError id=%s detail=%s", algorithmId, detail));
    }

    public static void error(String context, String detail, Throwable throwable) {
        if (throwable != null) {
            warn(context + ": " + detail, throwable);
        } else {
            warn(context + ": " + detail);
        }
    }

    public static void permissionDenied(String reason) {
        error("permissionDenied", reason, null);
    }

    public static void selfTestTriggered() {
        info("selfTestTriggered action=SELF_TEST_SCAN");
    }

    public static void trackerEvent(String message) {
        debug("tracker " + message);
    }

    private static void info(String message) {
        safeLog(Log.INFO, message, null);
    }

    public static void debug(String message) {
        safeLog(Log.DEBUG, message, null);
    }

    private static void warn(String message) {
        safeLog(Log.WARN, message, null);
    }

    private static void warn(String message, Throwable throwable) {
        safeLog(Log.WARN, message, throwable);
    }

    private static void safeLog(int priority, String message, Throwable throwable) {
        String fullMessage;
        if (throwable != null) {
            fullMessage = message + '\n' + Log.getStackTraceString(throwable);
        } else {
            fullMessage = message;
        }
        try {
            Log.println(priority, TAG, fullMessage);
        } catch (RuntimeException ignored) {
            // JVM unit tests run without a connected Android Log runtime.
        }
        // 同步写入本地文件日志
        if (fileLoggingEnabled) {
            appendToFile(priority, fullMessage);
        }
    }

    /** 将日志追加写入 PathManager.logsDir() 下的当日文件。失败静默降级。 */
    private static void appendToFile(int priority, String message) {
        File logsDir;
        try {
            logsDir = PathManager.logsDir();
        } catch (Exception ignored) {
            return;
        }
        if (logsDir == null) return;
        String fileName = PathManager.scanLogFileName(System.currentTimeMillis());
        File logFile = new File(logsDir, fileName);
        String levelTag = levelTag(priority);
        String timestamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = timestamp + " " + levelTag + " " + message + "\n";
        FILE_LOCK.lock();
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
            w.write(line);
        } catch (IOException ignored) {
            // 文件写入失败不影响主流程
        } finally {
            FILE_LOCK.unlock();
        }
    }

    private static String levelTag(int priority) {
        switch (priority) {
            case Log.VERBOSE: return "V";
            case Log.DEBUG:   return "D";
            case Log.INFO:    return "I";
            case Log.WARN:    return "W";
            case Log.ERROR:   return "E";
            case Log.ASSERT:  return "A";
            default:          return String.valueOf(priority);
        }
    }
}
