package com.example.cleanrecovery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class ScanLimitsTest {
    @Test
    public void batchAndProgressConstantsArePositive() {
        assertTrue(ScanLimits.ITEM_BATCH_SIZE > 0);
        assertTrue(ScanLimits.PROGRESS_MIN_INTERVAL_MS > 0L);
        assertEquals(25, ScanLimits.ITEM_BATCH_SIZE);
        assertEquals(300L, ScanLimits.PROGRESS_MIN_INTERVAL_MS);
    }
}
