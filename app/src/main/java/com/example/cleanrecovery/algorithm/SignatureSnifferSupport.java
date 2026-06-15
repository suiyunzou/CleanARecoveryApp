package com.example.cleanrecovery.algorithm;

import android.os.Environment;

import com.example.cleanrecovery.RecoveryScanner;
import com.example.cleanrecovery.ScanLimits;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Walks accessible shared storage and probes unknown or extensionless files.
 */
final class SignatureSnifferSupport {
    private final Set<String> visitedDirectories = new HashSet<>();

    int walkAndProbe(
            File root,
            AlgorithmContext context,
            AlgorithmCallback callback,
            FileFilter filter
    ) {
        visitedDirectories.clear();
        final int[] processed = {0};
        final int[] probed = {0};
        walkDirectory(root, context, callback, filter, processed, probed);
        return processed[0];
    }

    private void walkDirectory(
            File directory,
            AlgorithmContext context,
            AlgorithmCallback callback,
            FileFilter filter,
            int[] processed,
            int[] probed
    ) {
        if (callback.isCancelled() || directory == null || !directory.exists() || !directory.canRead()) {
            return;
        }
        String canonicalPath = canonicalPathOf(directory);
        if (!visitedDirectories.add(canonicalPath)) {
            return;
        }
        if (RecoveryScanner.isOutputDirectoryName(directory.getName())) {
            return;
        }

        File[] children = listChildren(directory);
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (callback.isCancelled()) {
                return;
            }
            if (child.isDirectory()) {
                walkDirectory(child, context, callback, filter, processed, probed);
            } else {
                processed[0]++;
                if (processed[0] % 25 == 0) {
                    callback.onProgress(processed[0], child.getAbsolutePath());
                }
                if (!filter.shouldProbe(child)) {
                    continue;
                }
                if (probed[0] >= ScanLimits.SIGNATURE_MAX_UNKNOWN_FILES) {
                    return;
                }
                probed[0]++;
                probeAndEmit(child, context, callback);
            }
        }
    }

    private void probeAndEmit(File file, AlgorithmContext context, AlgorithmCallback callback) {
        try {
            FileSignatureProbe.ProbeResult result = FileSignatureProbe.probe(file);
            if (result == null || result.type != context.type) {
                return;
            }
            RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                    .candidateId(file.getAbsolutePath())
                    .sourceKind(CandidateSourceKind.ACCESSIBLE_SIGNATURE_MATCH)
                    .sourceUriOrPath(file.getAbsolutePath())
                    .extractionMethod("accessible_signature_sniffer")
                    .byteLength(file.length())
                    .mimeDetected(result.mimeDetected)
                    .build();
            callback.onCandidate(candidate);
        } catch (IOException ignored) {
            // Skip unreadable files.
        }
    }

    static boolean isExtensionless(File file) {
        String name = file.getName();
        int index = name.lastIndexOf('.');
        return index < 0 || index == name.length() - 1;
    }

    static boolean isLostDirPath(String path) {
        return path.replace('\\', '/').toUpperCase(Locale.US).contains("/LOST.DIR/");
    }

    private static String canonicalPathOf(File directory) {
        try {
            return directory.getCanonicalPath();
        } catch (IOException exception) {
            return directory.getAbsolutePath();
        }
    }

    private static File[] listChildren(File directory) {
        try {
            return directory.listFiles();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    interface FileFilter {
        boolean shouldProbe(File file);
    }
}
