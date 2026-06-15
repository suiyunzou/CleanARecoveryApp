package com.example.cleanrecovery;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecoveryScannerTest {
    @Test
    public void normalVisiblePathIsNotSuspectedDeleted() {
        assertFalse(RecoveryScanner.isSuspectedDeletedPath(
                "/storage/emulated/0/DCIM/Camera/photo.jpg",
                "photo.jpg",
                "jpg"
        ));
    }

    @Test
    public void thumbnailsExtensionIsSuspectedDeleted() {
        assertTrue(RecoveryScanner.isSuspectedDeletedPath(
                "/storage/emulated/0/.thumbnails/123",
                "123",
                "thumbnails"
        ));
    }

    @Test
    public void cachePathIsSuspectedDeleted() {
        assertTrue(RecoveryScanner.isSuspectedDeletedPath(
                "/storage/emulated/0/Android/data/com.app/cache/image.jpg",
                "image.jpg",
                "jpg"
        ));
    }

    @Test
    public void trashPathIsSuspectedDeleted() {
        assertTrue(RecoveryScanner.isSuspectedDeletedPath(
                "/storage/emulated/0/trash/photo.png",
                "photo.png",
                "png"
        ));
    }

    @Test
    public void lostDirPathIsSuspectedDeleted() {
        assertTrue(RecoveryScanner.isSuspectedDeletedPath(
                "/storage/emulated/0/LOST.DIR/fragment",
                "fragment",
                ""
        ));
    }

    @Test
    public void hiddenFilenameIsSuspectedDeleted() {
        assertTrue(RecoveryScanner.isSuspectedDeletedPath(
                "/storage/emulated/0/Download/.hidden.jpg",
                ".hidden.jpg",
                "jpg"
        ));
    }

    @Test
    public void dataRecoveryDirectoryIsProtected() {
        assertTrue(RecoveryScanner.isOutputDirectoryName("DataRecovery"));
        assertTrue(RecoveryScanner.isOutputDirectoryName("datarecovery"));
        assertFalse(RecoveryScanner.isOutputDirectoryName("Pictures"));
    }
}
