package com.example.cleanrecovery;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ProgressThrottlerTest {
    @Test
    public void shouldUpdateOnlyAfterIntervalPasses() {
        ProgressThrottler throttler = new ProgressThrottler(300L);

        assertTrue(throttler.shouldUpdate(0L));
        assertFalse(throttler.shouldUpdate(100L));
        assertTrue(throttler.shouldUpdate(300L));
    }

    @Test
    public void resetAllowsImmediateUpdate() {
        ProgressThrottler throttler = new ProgressThrottler(300L);
        throttler.shouldUpdate(0L);

        throttler.reset();

        assertTrue(throttler.shouldUpdate(50L));
    }
}
