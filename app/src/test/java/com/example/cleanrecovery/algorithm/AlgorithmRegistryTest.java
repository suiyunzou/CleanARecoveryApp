package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.RecoveryType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class AlgorithmRegistryTest {
    @Test
    public void catalogContainsStableAlgorithmIds() {
        assertNotNull(AlgorithmRegistry.byId(FileTreeVisibleAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(MediaStoreIndexTrashAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(CacheProfileAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(JpegKnownBlobCarverAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(AccessibleSignatureSnifferAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(LostDirOrphanSnifferAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(SystemTrashScannerAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(WechatDirectoryScannerAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(DeepValidationAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(LogEvidenceImportAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(OfflineF2fsImageAlgorithm.ID));
        assertNotNull(AlgorithmRegistry.byId(OfflineExt4JournalAlgorithm.ID));
        assertEquals(12, AlgorithmRegistry.catalog().size());
    }

    @Test
    public void offlineAndEvidenceAlgorithmsAreNotRunnable() {
        AlgorithmContext context = new AlgorithmContext(null, RecoveryType.IMAGE);
        assertFalse(AlgorithmRegistry.resolvedAvailability(
                AlgorithmRegistry.byId(LogEvidenceImportAlgorithm.ID), context).isRunnable());
        assertFalse(AlgorithmRegistry.resolvedAvailability(
                AlgorithmRegistry.byId(OfflineF2fsImageAlgorithm.ID), context).isRunnable());
        assertFalse(AlgorithmRegistry.resolvedAvailability(
                AlgorithmRegistry.byId(OfflineExt4JournalAlgorithm.ID), context).isRunnable());
    }

    @Test
    public void defaultModeIncludesConservativeAlgorithmsOnly() {
        assertEquals(7, AlgorithmRegistry.runnableForMode(ScanMode.DEFAULT, RecoveryType.IMAGE).size());
        assertEquals(6, AlgorithmRegistry.runnableForMode(ScanMode.DEFAULT, RecoveryType.VIDEO).size());
    }

    @Test
    public void experimentalModeAddsSignatureAlgorithms() {
        assertTrue(AlgorithmRegistry.runnableForMode(ScanMode.EXPERIMENTAL_ALL, RecoveryType.VIDEO).size() >= 3);
    }
}
