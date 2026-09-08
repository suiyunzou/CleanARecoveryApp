package com.example.cleanrecovery.algorithm;

import android.os.Environment;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.recovery.RecoveryType;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Finds Android "soft-trashed" files that still exist on disk as
 * {@code .trashed-<timestamp>-originalName} (MediaStore trash naming), and
 * common gallery trash directories — without requiring our app's recycle bin.
 */
public final class DotTrashedFileScannerAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "dot_trashed_file_scanner";

    private static final String[] SEED_DIRS = {
            "DCIM",
            "Pictures",
            "Download",
            "Movies",
            "Music",
            "Documents",
    };

    private final Set<String> visited = new HashSet<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_dot_trashed_file_scanner;
    }

    @Override
    public RecoveryType[] supportedTypes() {
        return RecoveryType.scannableValues();
    }

    @Override
    public AlgorithmAvailability availability(AlgorithmContext context) {
        return AlgorithmAvailability.runnable();
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        visited.clear();
        File external = Environment.getExternalStorageDirectory();
        if (external == null) {
            return;
        }
        final int[] processed = {0};
        for (String seed : SEED_DIRS) {
            if (callback.isCancelled()) {
                return;
            }
            walk(new File(external, seed), 0, context, callback, processed);
        }
        // Also walk Android/data & Android/media gallery trash folders.
        walk(new File(external, "Android/data/com.google.android.apps.photos/files/trash"),
                0, context, callback, processed);
        walk(new File(external, "Android/media/com.google.android.apps.photos/trash"),
                0, context, callback, processed);
    }

    private void walk(
            File dir,
            int depth,
            AlgorithmContext context,
            AlgorithmCallback callback,
            int[] processed
    ) {
        if (callback.isCancelled() || dir == null || depth > 6 || !dir.isDirectory()) {
            return;
        }
        String key = dir.getAbsolutePath();
        if (!visited.add(key)) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (callback.isCancelled()) {
                return;
            }
            if (child.isDirectory()) {
                String name = child.getName();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                walk(child, depth + 1, context, callback, processed);
            } else if (child.isFile() && isTrashedStyleName(child.getName())) {
                emit(child, context, callback, processed);
            }
        }
    }

    private void emit(
            File file,
            AlgorithmContext context,
            AlgorithmCallback callback,
            int[] processed
    ) {
        if (file.length() <= 1L) {
            return;
        }
        FileSignatureProbe.ProbeResult probe = null;
        try {
            probe = FileSignatureProbe.probe(file);
        } catch (Exception ignored) {
        }
        RecoveryType detected = probe != null ? probe.type : null;
        if (detected != null && context != null && context.type != null && detected != context.type) {
            return;
        }
        processed[0]++;
        if (processed[0] % 20 == 0) {
            callback.onProgress(processed[0], file.getAbsolutePath());
        }
        String mime = probe != null ? probe.mimeDetected : "";
        // Prefer original display name after ".trashed-<ts>-"
        String display = recoverOriginalName(file.getName());
        callback.onCandidate(new RecoveryCandidate.Builder()
                .candidateId(file.getAbsolutePath())
                .sourceKind(CandidateSourceKind.MEDIASTORE_TRASH)
                .sourceUriOrPath(file.getAbsolutePath())
                .extractionMethod("dot_trashed_file_scanner")
                .originalContainer("dot_trashed:" + display)
                .byteLength(file.length())
                .mimeDetected(mime)
                .build());
    }

    static boolean isTrashedStyleName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.startsWith(".trashed-") || lower.contains("/.trashed-");
    }

    static String recoverOriginalName(String name) {
        if (name == null) {
            return "trashed_file";
        }
        // .trashed-<epochMillis>-originalName
        if (name.startsWith(".trashed-") || name.startsWith(".TRASHED-")) {
            String rest = name.substring(".trashed-".length());
            int dash = rest.indexOf('-');
            if (dash >= 0 && dash + 1 < rest.length()) {
                return rest.substring(dash + 1);
            }
        }
        return name;
    }
}
