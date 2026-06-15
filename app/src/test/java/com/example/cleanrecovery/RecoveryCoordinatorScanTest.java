package com.example.cleanrecovery;

import com.example.cleanrecovery.algorithm.AlgorithmRunner;
import com.example.cleanrecovery.algorithm.FakeCoordinatorAlgorithms;
import com.example.cleanrecovery.algorithm.ScanMode;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecoveryCoordinatorScanTest {
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

    @Test
    public void scanSessionTracksCumulativeScannedCount() {
        RecoveryCoordinator.ScanSession session = new RecoveryCoordinator.ScanSession();
        session.completedTypeScannedCount = 800;
        session.scannedCount = 200;
        assertEquals(1_000, session.cumulativeScannedCount());
    }

    @Test
    public void scannableTypesIncludeAllCategories() {
        RecoveryType[] types = RecoveryType.scannableValues();
        assertEquals(4, types.length);
        assertEquals(RecoveryType.IMAGE, types[0]);
        assertEquals(RecoveryType.DOCUMENT, types[3]);
    }

    @Test
    public void algorithmRunnerSkipsFailureAndContinues() {
        FakeCoordinatorAlgorithms.SuccessCounter counter = new FakeCoordinatorAlgorithms.SuccessCounter();
        AlgorithmRunner runner = new AlgorithmRunner(Arrays.asList(
                new FakeCoordinatorAlgorithms.FailingAlgorithm(),
                new FakeCoordinatorAlgorithms.SuccessAlgorithm(counter)
        ));
        runner.run(
                ScanMode.EXPERIMENTAL_ALL,
                RecoveryType.IMAGE,
                new com.example.cleanrecovery.algorithm.AlgorithmContext(null, RecoveryType.IMAGE),
                FakeCoordinatorAlgorithms.noopDelegate()
        );
        assertTrue(counter.wasCalled());
    }
}
