package com.example.cleanrecovery.algorithm;

import android.os.Build;
import android.os.Environment;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import java.io.File;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Scans Android system trash and OEM gallery recycle directories.
 * Derived from reference APK reverse-engineering: scanSystemTrashForAndroidQPlus,
 * scanDeletedPhotosInAndroidData, scanAggressivelyForDeletedFiles.
 */
public final class SystemTrashScannerAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "system_trash_scanner";

    private static final String[] TRASH_PATTERNS = {
        "/.Trash/",
        "/.trash/",
        "/.recycle/",
        "/.recycler/",
        "/DCIM/.trash/",
        "/Pictures/.trash/",
        "/Android/data/com.android.gallery3d/files/trash/",
        "/Android/data/com.google.android.apps.photos/files/trash/",
        "/Android/media/com.google.android.apps.photos/trash/",
        // OEM gallery trash paths (observed in reference APK)
        "/MIUI/Gallery/cloud/.trash/",
        "/Huawei/MagazineUnlock/.trash/",
        "/ColorOS/Gallery/.recycle/",
    };

    private final Set<String> visitedDirs = new HashSet<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_system_trash_scanner;
    }

    @Override
    public RecoveryType[] supportedTypes() {
        return RecoveryType.scannableValues();
    }

    @Override
    public AlgorithmAvailability availability(AlgorithmContext context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return AlgorithmAvailability.runnable();
        }
        return AlgorithmAvailability.requiresApi(Build.VERSION_CODES.Q);
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        visitedDirs.clear();
        File root = Environment.getExternalStorageDirectory();
        final int[] processed = {0};
        scanDirectory(root, context, callback, processed);
    }

    private void scanDirectory(File dir, AlgorithmContext context, AlgorithmCallback callback, int[] processed) {
        if (callback.isCancelled() || dir == null || !dir.exists() || !dir.canRead()) {
            return;
        }
        String canon = canonicalPath(dir);
        if (!visitedDirs.add(canon)) {
            return;
        }
        File[] children = listFiles(dir);
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (callback.isCancelled()) {
                return;
            }
            String childPath = canonicalPath(child);
            if (child.isDirectory()) {
                if (isTrashDirectory(childPath)) {
                    walkTrashRecursively(child, context, callback, processed);
                } else {
                    scanDirectory(child, context, callback, processed);
                }
            } else if (isTrashDirectory(canon)) {
                processed[0]++;
                if (processed[0] % 25 == 0) {
                    callback.onProgress(processed[0], childPath);
                }
                emitTrashCandidate(child, childPath, context, callback);
            }
        }
    }

    private void walkTrashRecursively(File dir, AlgorithmContext context, AlgorithmCallback callback, int[] processed) {
        if (callback.isCancelled()) {
            return;
        }
        File[] children = listFiles(dir);
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (callback.isCancelled()) {
                return;
            }
            if (child.isDirectory()) {
                walkTrashRecursively(child, context, callback, processed);
            } else {
                processed[0]++;
                if (processed[0] % 25 == 0) {
                    callback.onProgress(processed[0], child.getAbsolutePath());
                }
                emitTrashCandidate(child, child.getAbsolutePath(), context, callback);
            }
        }
    }

    private void emitTrashCandidate(File file, String path, AlgorithmContext context, AlgorithmCallback callback) {
        if (file.length() <= 1L) {
            return;
        }
        // Verify the file type matches scan type using signature probe
        FileSignatureProbe.ProbeResult probe = null;
        try {
            probe = FileSignatureProbe.probe(file);
        } catch (Exception ignored) {
        }
        RecoveryType fileType = probe != null ? probe.type : null;
        if (fileType != null && fileType != context.type) {
            return;
        }
        String mime = probe != null ? probe.mimeDetected : "";
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .candidateId(path)
                .sourceKind(CandidateSourceKind.MEDIASTORE_TRASH)
                .sourceUriOrPath(path)
                .extractionMethod("system_trash_scanner")
                .byteLength(file.length())
                .mimeDetected(mime)
                .build();
        callback.onCandidate(candidate);
    }

    static boolean isTrashDirectory(String normalizedPath) {
        String p = normalizedPath.replace('\\', '/').toLowerCase(Locale.US);
        for (String pattern : TRASH_PATTERNS) {
            if (p.contains(pattern.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private static File[] listFiles(File dir) {
        try {
            return dir.listFiles();
        } catch (SecurityException ignored) {
            return null;
        }
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (Exception ignored) {
            return file.getAbsolutePath();
        }
    }
}
