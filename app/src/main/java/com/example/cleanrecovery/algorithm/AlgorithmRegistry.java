package com.example.cleanrecovery.algorithm;

import android.os.Build;

import com.example.cleanrecovery.recovery.RecoveryType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class AlgorithmRegistry {
    private static final List<RecoveryAlgorithm> CATALOG = Collections.unmodifiableList(Arrays.asList(
            new FileTreeVisibleAlgorithm(),
            new MediaStoreIndexTrashAlgorithm(),
            new CacheProfileAlgorithm(),
            new JpegKnownBlobCarverAlgorithm(),
            new AccessibleSignatureSnifferAlgorithm(),
            new LostDirOrphanSnifferAlgorithm(),
            new SystemTrashScannerAlgorithm(),
            new WechatDirectoryScannerAlgorithm(),
            new DeepValidationAlgorithm(),
            new LogEvidenceImportAlgorithm(),
            new OfflineF2fsImageAlgorithm(),
            new OfflineExt4JournalAlgorithm()
    ));

    private AlgorithmRegistry() {
    }

    public static List<RecoveryAlgorithm> catalog() {
        return CATALOG;
    }

    public static List<RecoveryAlgorithm> runnableForMode(ScanMode mode, RecoveryType type) {
        List<RecoveryAlgorithm> selected = new ArrayList<>();
        for (RecoveryAlgorithm algorithm : CATALOG) {
            if (!supportsType(algorithm, type)) {
                continue;
            }
            if (!shouldRunInMode(algorithm, mode)) {
                continue;
            }
            selected.add(algorithm);
        }
        return selected;
    }

    public static RecoveryAlgorithm byId(String id) {
        for (RecoveryAlgorithm algorithm : CATALOG) {
            if (algorithm.id().equals(id)) {
                return algorithm;
            }
        }
        return null;
    }

    static boolean shouldRunInMode(RecoveryAlgorithm algorithm, ScanMode mode) {
        String id = algorithm.id();
        // Expensive or specialised algorithms run only in the experimental sweep:
        //  - jpeg_known_blob_carver: full-storage embedded-JPEG carving
        //  - log_evidence_import: stale-record evidence (no byte recovery)
        //  - offline_f2fs_image / offline_ext4_journal: root-only raw partition carve
        //  - ffmpeg_deep_validation: native decode validation
        if (JpegKnownBlobCarverAlgorithm.ID.equals(id)
                || LogEvidenceImportAlgorithm.ID.equals(id)
                || OfflineF2fsImageAlgorithm.ID.equals(id)
                || OfflineExt4JournalAlgorithm.ID.equals(id)
                || DeepValidationAlgorithm.ID.equals(id)) {
            return mode == ScanMode.EXPERIMENTAL_ALL;
        }
        return true;
    }

    static boolean supportsType(RecoveryAlgorithm algorithm, RecoveryType type) {
        RecoveryType[] supported = algorithm.supportedTypes();
        for (RecoveryType candidate : supported) {
            if (candidate == type) {
                return true;
            }
        }
        return false;
    }

    public static AlgorithmAvailability resolvedAvailability(RecoveryAlgorithm algorithm, AlgorithmContext context) {
        AlgorithmAvailability availability = algorithm.availability(context);
        if (!availability.isRunnable()) {
            if (availability.getStatus() == AlgorithmAvailability.Status.REQUIRES_API
                    && Build.VERSION.SDK_INT >= availability.getMinApi()) {
                return AlgorithmAvailability.runnable();
            }
            return availability;
        }
        if (MediaStoreIndexTrashAlgorithm.ID.equals(algorithm.id())
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return AlgorithmAvailability.requiresApi(Build.VERSION_CODES.Q);
        }
        return availability;
    }
}
