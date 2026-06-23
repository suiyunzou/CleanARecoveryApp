package com.example.cleanrecovery.recovery;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecoveryResultsSessionTest {
    @Test
    public void saveAndRestoreRoundTrip() {
        RecoveryResultsSession.clear();
        RecoveryState state = new RecoveryState();
        RecoveryItem item = new RecoveryItem(
                RecoveryType.IMAGE,
                "photo.jpg",
                "/storage/emulated/0/DCIM/photo.jpg",
                1024L,
                1L,
                100,
                100,
                false
        );
        item.selected = true;
        state.addAll(Collections.singletonList(item));
        state.setFilter(RecoveryState.FilterMode.EXISTING);

        RecoveryResultsSession.saveFrom(state, RecoveryType.IMAGE, false, true, 42, 1);

        RecoveryState restored = new RecoveryState();
        RecoveryResultsSession.restoreTo(restored);

        assertTrue(RecoveryResultsSession.hasResults());
        assertEquals(1, restored.getAllCount());
        assertEquals(RecoveryState.FilterMode.EXISTING, restored.getFilter());
        assertEquals(RecoveryType.IMAGE, RecoveryResultsSession.getScanType());
        assertTrue(RecoveryResultsSession.isExperimentalMode());
        assertEquals(42, RecoveryResultsSession.getScannedCount());
        assertEquals(1, RecoveryResultsSession.getFoundCount());
        assertTrue(restored.getVisibleItems().get(0).selected);
    }

    @Test
    public void clearRemovesResults() {
        RecoveryResultsSession.clear();
        assertFalse(RecoveryResultsSession.hasResults());
    }
}
