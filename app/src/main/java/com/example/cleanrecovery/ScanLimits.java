package com.example.cleanrecovery;

public final class ScanLimits {
    public static final int ITEM_BATCH_SIZE = 25;
    public static final long PROGRESS_MIN_INTERVAL_MS = 300L;
    public static final int SIGNATURE_PREFIX_BYTES = 32;
    public static final long SIGNATURE_MAX_FILE_BYTES = 50L * 1024L * 1024L;
    public static final int SIGNATURE_MAX_UNKNOWN_FILES = 5_000;

    private ScanLimits() {
    }
}
