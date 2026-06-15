package com.example.cleanrecovery;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecoveryCoordinatorScanTest {
    @Test
    public void scanSessionRespectsResultCap() {
        RecoveryCoordinator.ScanSession session = new RecoveryCoordinator.ScanSession();
        session.foundCount = ScanLimits.MAX_RESULTS - 1;
        assertFalse(session.atCap());

        session.foundCount = ScanLimits.MAX_RESULTS;
        assertTrue(session.atCap());
    }

    @Test
    public void deduperPreventsDuplicateBatchItems() {
        RecoveryDeduper deduper = new RecoveryDeduper();
        RecoveryItem fileItem = new RecoveryItem(
                RecoveryType.IMAGE,
                "photo.jpg",
                "/storage/emulated/0/DCIM/photo.jpg",
                100L,
                0L,
                100,
                100,
                false,
                RecoverySourceKind.VISIBLE_SHARED_FILE
        );
        RecoveryItem mediaStoreItem = new RecoveryItem(
                RecoveryType.IMAGE,
                "photo.jpg",
                "content://media/external/images/media/1",
                100L,
                0L,
                100,
                100,
                false,
                RecoverySourceKind.VISIBLE_SHARED_FILE
        );

        assertFalse(deduper.isDuplicate(fileItem));
        assertFalse(deduper.isDuplicate(mediaStoreItem));

        RecoveryItem duplicatePath = new RecoveryItem(
                RecoveryType.IMAGE,
                "photo.jpg",
                "/storage/emulated/0/DCIM/photo.jpg",
                100L,
                0L,
                100,
                100,
                false,
                RecoverySourceKind.MEDIASTORE_TRASH
        );
        assertTrue(deduper.isDuplicate(duplicatePath));
        assertEquals(1, deduper.getDuplicateCount());
    }
}
