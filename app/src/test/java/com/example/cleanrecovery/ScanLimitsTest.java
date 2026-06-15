package com.example.cleanrecovery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ScanLimitsTest {
    @Test
    public void isAtCapWhenCountReachesMax() {
        assertTrue(ScanLimits.isAtCap(ScanLimits.MAX_RESULTS));
        assertFalse(ScanLimits.isAtCap(ScanLimits.MAX_RESULTS - 1));
    }

    @Test
    public void remainingCapacityNeverGoesNegative() {
        assertEquals(5, ScanLimits.remainingCapacity(ScanLimits.MAX_RESULTS - 5));
        assertEquals(0, ScanLimits.remainingCapacity(ScanLimits.MAX_RESULTS));
        assertEquals(0, ScanLimits.remainingCapacity(ScanLimits.MAX_RESULTS + 10));
    }

    @Test
    public void canAcceptMoreRespectsCap() {
        assertTrue(ScanLimits.canAcceptMore(ScanLimits.MAX_RESULTS - 1, 1));
        assertFalse(ScanLimits.canAcceptMore(ScanLimits.MAX_RESULTS, 1));
    }
}
