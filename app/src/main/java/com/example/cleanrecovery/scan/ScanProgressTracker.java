package com.example.cleanrecovery.scan;

/**
 * Honest scan progress based on enumerated work units, with ETA from measured throughput.
 */
public final class ScanProgressTracker {
    public enum Phase {
        PREPARING,
        FILE_SCAN,
        MEDIASTORE,
        CACHE,
        TRASH_SCAN,
        WECHAT_SCAN,
        COMPLETE
    }

    private static final int PREPARING_CAP_PERCENT = 5;
    private static final int FILE_SCAN_START_PERCENT = 5;
    private static final int FILE_SCAN_END_PERCENT = 45;
    private static final int MEDIASTORE_START_PERCENT = 45;
    private static final int MEDIASTORE_END_PERCENT = 55;
    private static final int CACHE_START_PERCENT = 55;
    private static final int CACHE_END_PERCENT = 65;
    private static final int TRASH_START_PERCENT = 65;
    private static final int TRASH_END_PERCENT = 75;
    private static final int WECHAT_START_PERCENT = 75;
    private static final int WECHAT_END_PERCENT = 85;
    private static final int SCANNING_CAP_PERCENT = 99;
    private static final long ETA_WARMUP_MS = 2_000L;
    private static final int ETA_MIN_SAMPLES = 40;

    private Phase phase = Phase.PREPARING;
    private int historicalEstimate;
    private int preparedTotal;
    private int preparedCount;
    private int scannedCount;
    private int mediastoreProcessed;
    private int cacheScanned;
    private int cacheTotal;
    private long startedAtMs;
    private long scanningStartedAtMs;
    private float smoothedEntriesPerMs;
    private boolean multiTypeMode;
    private int typeCount = 1;
    private int currentTypeIndex;

    public void reset(int historicalEstimate) {
        resetInternal(historicalEstimate, false, 1);
    }

    public void resetMultiType(int historicalEstimate, int typeCount) {
        resetInternal(historicalEstimate, true, typeCount);
    }

    private void resetInternal(int historicalEstimate, boolean multiType, int typeCount) {
        this.historicalEstimate = Math.max(1, historicalEstimate);
        preparedTotal = 0;
        preparedCount = 0;
        scannedCount = 0;
        mediastoreProcessed = 0;
        cacheScanned = 0;
        cacheTotal = 0;
        smoothedEntriesPerMs = 0f;
        phase = Phase.PREPARING;
        startedAtMs = System.currentTimeMillis();
        scanningStartedAtMs = 0L;
        multiTypeMode = multiType;
        this.typeCount = Math.max(1, typeCount);
        currentTypeIndex = 0;
    }

    public void beginType(int typeIndex) {
        if (!multiTypeMode) {
            return;
        }
        currentTypeIndex = Math.max(0, Math.min(this.typeCount - 1, typeIndex));
        scannedCount = 0;
        mediastoreProcessed = 0;
        cacheScanned = 0;
        cacheTotal = 0;
        smoothedEntriesPerMs = 0f;
        if (preparedTotal > 0) {
            phase = Phase.FILE_SCAN;
            scanningStartedAtMs = System.currentTimeMillis();
        }
    }

    public boolean isMultiTypeMode() {
        return multiTypeMode;
    }

    public int getCurrentTypeIndex() {
        return currentTypeIndex;
    }

    public int getTypeCount() {
        return typeCount;
    }

    public void onPrepareProgress(int countedSoFar) {
        if (phase == Phase.COMPLETE) {
            return;
        }
        phase = Phase.PREPARING;
        preparedCount = Math.max(0, countedSoFar);
    }

    public void onPrepared(int totalEntries) {
        if (phase == Phase.COMPLETE) {
            return;
        }
        preparedTotal = Math.max(0, totalEntries);
        preparedCount = preparedTotal;
        phase = Phase.FILE_SCAN;
        scanningStartedAtMs = System.currentTimeMillis();
        ScanDiagnostics.trackerEvent("onPrepared total=" + totalEntries);
    }

    public void onScanProgress(int scannedSoFar) {
        onFileScanProgress(scannedSoFar);
    }

    public void onFileScanProgress(int scannedSoFar) {
        if (phase == Phase.COMPLETE) {
            return;
        }
        if (phase == Phase.PREPARING) {
            phase = Phase.FILE_SCAN;
            scanningStartedAtMs = System.currentTimeMillis();
        }
        if (phase == Phase.FILE_SCAN) {
            scannedCount = Math.max(0, scannedSoFar);
            updateThroughput();
        }
    }

    public void onMediaStorePhaseStart() {
        if (phase == Phase.COMPLETE) {
            return;
        }
        phase = Phase.MEDIASTORE;
        mediastoreProcessed = 0;
        ScanDiagnostics.trackerEvent("onMediaStorePhaseStart");
    }

    public void onMediaStoreProgress(int processedSoFar) {
        if (phase == Phase.COMPLETE) {
            return;
        }
        phase = Phase.MEDIASTORE;
        mediastoreProcessed = Math.max(0, processedSoFar);
    }

    public void onCachePhaseStart(int estimatedTotal) {
        if (phase == Phase.COMPLETE) {
            return;
        }
        phase = Phase.CACHE;
        cacheTotal = Math.max(0, estimatedTotal);
        cacheScanned = 0;
        ScanDiagnostics.trackerEvent("onCachePhaseStart estimatedTotal=" + estimatedTotal);
    }

    public void onCacheProgress(int scannedSoFar) {
        if (phase == Phase.COMPLETE) {
            return;
        }
        phase = Phase.CACHE;
        cacheScanned = Math.max(0, scannedSoFar);
        if (cacheTotal <= 0) {
            cacheTotal = cacheScanned;
        } else {
            cacheTotal = Math.max(cacheTotal, scannedSoFar);
        }
    }

    public void complete() {
        phase = Phase.COMPLETE;
        scannedCount = Math.max(scannedCount, preparedTotal);
        ScanDiagnostics.trackerEvent("complete scanned=" + scannedCount);
    }

    public Phase getPhase() {
        return phase;
    }

    public int getPreparedTotal() {
        return preparedTotal;
    }

    public int getPreparedCount() {
        return preparedCount;
    }

    public int getScannedCount() {
        return scannedCount;
    }

    public int getDisplayPercent() {
        if (phase == Phase.COMPLETE) {
            return 100;
        }
        int singleTypePercent = getSingleTypeDisplayPercent();
        if (!multiTypeMode || typeCount <= 1 || phase == Phase.PREPARING) {
            return singleTypePercent;
        }
        int scanRange = SCANNING_CAP_PERCENT - FILE_SCAN_START_PERCENT;
        float typeProgress = Math.max(0f, Math.min(1f,
                (singleTypePercent - FILE_SCAN_START_PERCENT) / (float) scanRange));
        int global = FILE_SCAN_START_PERCENT + Math.round((currentTypeIndex + typeProgress) / typeCount * scanRange);
        return Math.max(FILE_SCAN_START_PERCENT, Math.min(SCANNING_CAP_PERCENT, global));
    }

    private int getSingleTypeDisplayPercent() {
        if (phase == Phase.PREPARING) {
            int estimate = Math.max(historicalEstimate, preparedCount + 1);
            float ratio = Math.min(1f, preparedCount / (float) estimate);
            return Math.max(0, Math.min(PREPARING_CAP_PERCENT, Math.round(ratio * PREPARING_CAP_PERCENT)));
        }
        if (phase == Phase.FILE_SCAN) {
            if (preparedTotal <= 0) {
                return FILE_SCAN_START_PERCENT;
            }
            float ratio = Math.min(1f, scannedCount / (float) preparedTotal);
            int span = FILE_SCAN_END_PERCENT - FILE_SCAN_START_PERCENT;
            return Math.max(FILE_SCAN_START_PERCENT, Math.min(FILE_SCAN_END_PERCENT,
                    FILE_SCAN_START_PERCENT + Math.round(ratio * span)));
        }
        if (phase == Phase.MEDIASTORE) {
            int span = MEDIASTORE_END_PERCENT - MEDIASTORE_START_PERCENT;
            float ratio = Math.min(1f, mediastoreProcessed / 500f);
            return Math.max(MEDIASTORE_START_PERCENT, Math.min(MEDIASTORE_END_PERCENT,
                    MEDIASTORE_START_PERCENT + Math.round(ratio * span)));
        }
        if (phase == Phase.CACHE) {
            int span = CACHE_END_PERCENT - CACHE_START_PERCENT;
            if (cacheTotal <= 0) {
                return CACHE_START_PERCENT;
            }
            float ratio = Math.min(1f, cacheScanned / (float) cacheTotal);
            return Math.max(CACHE_START_PERCENT, Math.min(CACHE_END_PERCENT,
                    CACHE_START_PERCENT + Math.round(ratio * span)));
        }
        if (phase == Phase.TRASH_SCAN) {
            return TRASH_START_PERCENT + (TRASH_END_PERCENT - TRASH_START_PERCENT) / 2;
        }
        if (phase == Phase.WECHAT_SCAN) {
            return WECHAT_START_PERCENT + (WECHAT_END_PERCENT - WECHAT_START_PERCENT) / 2;
        }
        return FILE_SCAN_START_PERCENT;
    }

    public long getEstimatedRemainingMs() {
        if (phase != Phase.FILE_SCAN || preparedTotal <= 0) {
            return -1L;
        }
        long elapsed = System.currentTimeMillis() - scanningStartedAtMs;
        if (elapsed < ETA_WARMUP_MS || scannedCount < ETA_MIN_SAMPLES || smoothedEntriesPerMs <= 0f) {
            return -1L;
        }
        int remainingInType = Math.max(0, preparedTotal - scannedCount);
        int remainingTypes = multiTypeMode ? Math.max(0, typeCount - currentTypeIndex - 1) : 0;
        int remaining = remainingInType + remainingTypes * preparedTotal;
        if (remaining == 0) {
            return 0L;
        }
        return Math.max(1_000L, Math.round(remaining / smoothedEntriesPerMs));
    }

    private void updateThroughput() {
        if (scanningStartedAtMs <= 0L || scannedCount <= 0) {
            return;
        }
        long elapsed = System.currentTimeMillis() - scanningStartedAtMs;
        if (elapsed <= 0L) {
            return;
        }
        float sampleRate = scannedCount / (float) elapsed;
        if (smoothedEntriesPerMs <= 0f) {
            smoothedEntriesPerMs = sampleRate;
        } else {
            smoothedEntriesPerMs = smoothedEntriesPerMs * 0.8f + sampleRate * 0.2f;
        }
    }
}
