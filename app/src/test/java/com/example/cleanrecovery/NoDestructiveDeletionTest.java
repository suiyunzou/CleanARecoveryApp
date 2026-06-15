package com.example.cleanrecovery;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class NoDestructiveDeletionTest {
    @Test
    public void junkCleanerClassIsAbsent() {
        try {
            Class.forName("com.example.cleanrecovery.JunkCleaner");
            throw new AssertionError("JunkCleaner should not exist");
        } catch (ClassNotFoundException expected) {
            // expected
        }
    }
}
