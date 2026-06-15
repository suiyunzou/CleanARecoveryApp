package com.example.cleanrecovery;

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
        assertTrue(percent >= 27 && percent <= 35);
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
        assertTrue(percent >= 58 && percent <= 63);
    }

    @Test
    public void cachePhaseUsesDedicatedSlice() {
        ScanProgressTracker tracker = new ScanProgressTracker();
        tracker.reset(1_000);
        tracker.onCachePhaseStart(100);
        tracker.onCacheProgress(50);
        int percent = tracker.getDisplayPercent();
        assertTrue(percent >= 80 && percent <= 86);
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
        assertTrue(firstTypeMid >= 10 && firstTypeMid <= 15);

        tracker.beginType(2);
        tracker.onFileScanProgress(500);
        int thirdTypeMid = tracker.getDisplayPercent();
        assertTrue(thirdTypeMid >= 55 && thirdTypeMid <= 60);
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
