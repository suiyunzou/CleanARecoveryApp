package com.example.cleanrecovery.scan;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ScanProgressTrackerTest {
    @Test
    public void preparingPhaseStaysWithinCap() {
        ScanProgressTracker tracker = new ScanProgressTracker();
        tracker.reset(10_000);
        tracker.onPrepareProgress(2_000);
        assertTrue(tracker.getDisplayPercent() <= 5);
    }

    @Test
    public void scanningPhaseTracksRealRatio() {
        ScanProgressTracker tracker = new ScanProgressTracker();
        tracker.reset(10_000);
        tracker.onPrepared(1_000);
        tracker.onFileScanProgress(500);
        int percent = tracker.getDisplayPercent();
        assertTrue(percent >= 22 && percent <= 28);
    }

    @Test
    public void mediastorePhaseUsesDedicatedSlice() {
        ScanProgressTracker tracker = new ScanProgressTracker();
        tracker.reset(1_000);
        tracker.onPrepared(1_000);
        tracker.onFileScanProgress(1_000);
        tracker.onMediaStorePhaseStart();
        tracker.onMediaStoreProgress(250);
        int percent = tracker.getDisplayPercent();
        assertTrue(percent >= 48 && percent <= 53);
    }

    @Test
    public void cachePhaseUsesDedicatedSlice() {
        ScanProgressTracker tracker = new ScanProgressTracker();
        tracker.reset(1_000);
        tracker.onCachePhaseStart(100);
        tracker.onCacheProgress(50);
        int percent = tracker.getDisplayPercent();
        assertTrue(percent >= 58 && percent <= 64);
    }

    @Test
    public void completeOnlyShowsOneHundredAtEnd() {
        ScanProgressTracker tracker = new ScanProgressTracker();
        tracker.reset(1_000);
        tracker.onPrepared(1_000);
        tracker.onScanProgress(1_000);
        assertTrue(tracker.getDisplayPercent() <= 99);
        tracker.complete();
        assertEquals(100, tracker.getDisplayPercent());
    }

    @Test
    public void multiTypeProgressSpansAllTypes() {
        ScanProgressTracker tracker = new ScanProgressTracker();
        tracker.resetMultiType(1_000, 4);
        tracker.onPrepared(1_000);
        tracker.beginType(0);
        tracker.onFileScanProgress(500);
        int firstTypeMid = tracker.getDisplayPercent();
        assertTrue(firstTypeMid >= 8 && firstTypeMid <= 14);

        tracker.beginType(2);
        tracker.onFileScanProgress(500);
        int thirdTypeMid = tracker.getDisplayPercent();
        assertTrue(thirdTypeMid >= 52 && thirdTypeMid <= 62);
    }

    @Test
    public void multiTypeCompleteShowsOneHundred() {
        ScanProgressTracker tracker = new ScanProgressTracker();
        tracker.resetMultiType(1_000, 4);
        tracker.onPrepared(1_000);
        tracker.beginType(3);
        tracker.onFileScanProgress(1_000);
        assertTrue(tracker.getDisplayPercent() <= 99);
        tracker.complete();
        assertEquals(100, tracker.getDisplayPercent());
    }
}
