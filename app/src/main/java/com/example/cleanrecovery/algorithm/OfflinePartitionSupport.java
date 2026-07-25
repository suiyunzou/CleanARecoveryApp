package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.util.RootShell;

import java.io.File;

/**
 * Shared driver for the two offline (root) partition-carving algorithms.
 * Detects the userdata block device, confirms it is the filesystem the calling
 * algorithm is meant to handle, then streams it through {@link RawPartitionCarver}.
 * All access is read-only; carved bytes land in the app-private cache.
 */
final class OfflinePartitionSupport {
    private static final int HEADER_BYTES = 0x1000; // enough for ext4/F2FS superblock magic
    private static final String STAGING_DIR_NAME = "offline_carve";

    private OfflinePartitionSupport() {
    }

    static boolean rootRunnable() {
        return RootShell.isRootAvailable();
    }

    static void scan(
            AlgorithmContext context,
            AlgorithmCallback callback,
            String expectedFs,
            CandidateSourceKind sourceKind,
            String extractionMethod
    ) {
        if (context == null || context.context == null || callback.isCancelled()) {
            return;
        }
        if (!RootShell.isRootAvailable()) {
            return;
        }
        String device = RootShell.detectUserdataDevice();
        if (device == null) {
            return;
        }
        // Only run the algorithm whose filesystem matches the live partition, so
        // the OFFLINE_F2FS / OFFLINE_EXT4 source kinds stay truthful.
        String fsType = RootShell.detectFsType(RootShell.readHeader(device, HEADER_BYTES));
        if (!expectedFs.equals(fsType)) {
            return;
        }

        File stagingDir = new File(context.context.getCacheDir(), STAGING_DIR_NAME);
        RawPartitionCarver carver = new RawPartitionCarver();
        try (RootShell.PartitionStream stream = RootShell.openPartitionStream(device)) {
            carver.carveStream(stream.input, stagingDir, sourceKind, extractionMethod, callback);
        } catch (Exception ignored) {
            // Streaming/IO failure — leave whatever was already emitted.
        }
    }
}
