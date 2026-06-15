package com.example.cleanrecovery;

/**
 * Honest scan progress based on enumerated work units, with ETA from measured throughput.
 */
public final class ScanProgressTracker {
    public enum Phase {
        PREPARING,
        FILE_SCAN,
        MEDIASTORE,
        CACHE,
        COMPLETE
    }

    private static final int PREPARING_CAP_PERCENT = 5;
    private static final int FILE_SCAN_START_PERCENT = 5;
    private static final int FILE_SCAN_END_PERCENT = 65;
    private static final int MEDIASTORE_START_PERCENT = 65;
    private static final int MEDIASTORE_END_PERCENT = 80;
    private static final int CACHE_START_PERCENT = 80;
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

    public void reset(int historicalEstimate) {
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
            int span = SCANNING_CAP_PERCENT - CACHE_START_PERCENT;
            if (cacheTotal <= 0) {
                return CACHE_START_PERCENT;
            }
            float ratio = Math.min(1f, cacheScanned / (float) cacheTotal);
            return Math.max(CACHE_START_PERCENT, Math.min(SCANNING_CAP_PERCENT,
                    CACHE_START_PERCENT + Math.round(ratio * span)));
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
        int remaining = Math.max(0, preparedTotal - scannedCount);
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
