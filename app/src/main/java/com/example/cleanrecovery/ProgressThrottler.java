package com.example.cleanrecovery;

public final class ProgressThrottler {
    private final long minIntervalMs;
    private long lastUpdateMs = -1L;

    public ProgressThrottler(long minIntervalMs) {
        this.minIntervalMs = minIntervalMs;
    }

    public boolean shouldUpdate(long nowMs) {
        if (lastUpdateMs < 0L || nowMs - lastUpdateMs >= minIntervalMs) {
            lastUpdateMs = nowMs;
            return true;
        }
        return false;
    }

    public void reset() {
        lastUpdateMs = -1L;
    }
}
