package com.example.cleanrecovery;

public final class ScanLimits {
    public static final int MAX_RESULTS = 5000;
    public static final int ITEM_BATCH_SIZE = 25;
    public static final long PROGRESS_MIN_INTERVAL_MS = 300L;

    private ScanLimits() {
    }

    public static boolean isAtCap(int currentCount) {
        return currentCount >= MAX_RESULTS;
    }

    public static boolean canAcceptMore(int currentCount, int incomingCount) {
        return currentCount + incomingCount <= MAX_RESULTS;
    }

    public static int remainingCapacity(int currentCount) {
        return Math.max(0, MAX_RESULTS - currentCount);
    }
}
