package com.example.cleanrecovery.scan;

import com.example.cleanrecovery.recovery.RecoveryType;

import android.content.Context;
import android.content.SharedPreferences;

public final class ScanHistoryStore {
    private static final String PREFS = "clean_recovery_scan_history";

    private static final String KEY_ONBOARDING_DONE = "onboarding_done";
    private static final String KEY_LAST_SCAN_TIME_MS = "last_scan_time_ms";
    private static final String KEY_LAST_SCANNED_COUNT = "last_scanned_count";
    private static final String KEY_LAST_FOUND_COUNT = "last_found_count";
    private static final String KEY_LAST_SCAN_TYPE = "last_scan_type";
    private static final String KEY_COUNT_IMAGE = "count_image";
    private static final String KEY_COUNT_VIDEO = "count_video";
    private static final String KEY_COUNT_AUDIO = "count_audio";
    private static final String KEY_COUNT_DOCUMENT = "count_document";

    public static final class Snapshot {
        public final long lastScanTimeMs;
        public final int lastScannedCount;
        public final int lastFoundCount;
        public final String lastScanTypeName;
        public final int imageCount;
        public final int videoCount;
        public final int audioCount;
        public final int documentCount;

        Snapshot(
                long lastScanTimeMs,
                int lastScannedCount,
                int lastFoundCount,
                String lastScanTypeName,
                int imageCount,
                int videoCount,
                int audioCount,
                int documentCount
        ) {
            this.lastScanTimeMs = lastScanTimeMs;
            this.lastScannedCount = lastScannedCount;
            this.lastFoundCount = lastFoundCount;
            this.lastScanTypeName = lastScanTypeName;
            this.imageCount = imageCount;
            this.videoCount = videoCount;
            this.audioCount = audioCount;
            this.documentCount = documentCount;
        }

        public int countForType(RecoveryType type) {
            if (type == RecoveryType.IMAGE) {
                return imageCount;
            }
            if (type == RecoveryType.VIDEO) {
                return videoCount;
            }
            if (type == RecoveryType.AUDIO) {
                return audioCount;
            }
            return documentCount;
        }

        public boolean hasScanHistory() {
            return lastScanTimeMs > 0L;
        }
    }

    private ScanHistoryStore() {
    }

    public static boolean isOnboardingComplete(Context context) {
        return prefs(context).getBoolean(KEY_ONBOARDING_DONE, false);
    }

    public static void setOnboardingComplete(Context context) {
        prefs(context).edit().putBoolean(KEY_ONBOARDING_DONE, true).apply();
    }

    public static Snapshot read(Context context) {
        SharedPreferences preferences = prefs(context);
        return new Snapshot(
                preferences.getLong(KEY_LAST_SCAN_TIME_MS, 0L),
                preferences.getInt(KEY_LAST_SCANNED_COUNT, 0),
                preferences.getInt(KEY_LAST_FOUND_COUNT, 0),
                preferences.getString(KEY_LAST_SCAN_TYPE, ""),
                preferences.getInt(KEY_COUNT_IMAGE, 0),
                preferences.getInt(KEY_COUNT_VIDEO, 0),
                preferences.getInt(KEY_COUNT_AUDIO, 0),
                preferences.getInt(KEY_COUNT_DOCUMENT, 0)
        );
    }

    public static void saveScanResult(
            Context context,
            RecoveryType scanType,
            int scannedCount,
            int foundCount,
            int imageCount,
            int videoCount,
            int audioCount,
            int documentCount
    ) {
        prefs(context).edit()
                .putLong(KEY_LAST_SCAN_TIME_MS, System.currentTimeMillis())
                .putInt(KEY_LAST_SCANNED_COUNT, scannedCount)
                .putInt(KEY_LAST_FOUND_COUNT, foundCount)
                .putString(KEY_LAST_SCAN_TYPE, scanType == null ? "" : scanType.name())
                .putInt(KEY_COUNT_IMAGE, imageCount)
                .putInt(KEY_COUNT_VIDEO, videoCount)
                .putInt(KEY_COUNT_AUDIO, audioCount)
                .putInt(KEY_COUNT_DOCUMENT, documentCount)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
