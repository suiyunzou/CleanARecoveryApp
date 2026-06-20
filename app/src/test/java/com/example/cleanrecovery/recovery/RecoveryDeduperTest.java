package com.example.cleanrecovery.recovery;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecoveryDeduperTest {
    @Test
    public void sameCanonicalPathIsDuplicate() {
        RecoveryDeduper deduper = new RecoveryDeduper();
        RecoveryItem first = itemAt("/storage/emulated/0/DCIM/photo.jpg");
        RecoveryItem second = itemAt("/storage/emulated/0\\DCIM\\photo.jpg");

        assertFalse(deduper.isDuplicate(first));
        assertTrue(deduper.isDuplicate(second));
        assertEquals(1, deduper.getDuplicateCount());
    }

    @Test
    public void differentPathsAreNotDuplicates() {
        RecoveryDeduper deduper = new RecoveryDeduper();

        assertFalse(deduper.isDuplicate(itemAt("/storage/a.jpg")));
        assertFalse(deduper.isDuplicate(itemAt("/storage/b.jpg")));
        assertEquals(0, deduper.getDuplicateCount());
    }

    @Test
    public void clearResetsDuplicateMemory() {
        RecoveryDeduper deduper = new RecoveryDeduper();
        deduper.isDuplicate(itemAt("/storage/a.jpg"));
        deduper.clear();

        assertFalse(deduper.isDuplicate(itemAt("/storage/a.jpg")));
        assertEquals(0, deduper.getDuplicateCount());
    }

    private static RecoveryItem itemAt(String path) {
        return new RecoveryItem(
                RecoveryType.IMAGE,
                "photo.jpg",
                path,
                100L,
                0L,
                100,
                100,
                false,
                RecoverySourceKind.VISIBLE_SHARED_FILE
        );
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.Assert.assertEquals(expected, actual);
    }
}
