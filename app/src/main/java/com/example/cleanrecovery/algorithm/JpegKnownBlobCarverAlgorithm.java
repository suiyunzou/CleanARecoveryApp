package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.experiment.cache.CacheProfileRegistry;
import com.example.cleanrecovery.experiment.jpeg.JpegBlobCarver;
import com.example.cleanrecovery.recovery.RecoveryScanner;
import com.example.cleanrecovery.recovery.RecoveryType;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Carves embedded JPEGs out of opaque container blobs (databases, .dat caches,
 * extension-less files) that the {@link CacheProfileAlgorithm} does NOT already
 * cover. Cache-profile paths and ordinary standalone media files are skipped so
 * this never double-emits what other algorithms find.
 *
 * <p>Reuses the existing structured carver ({@link JpegBlobCarver}); carved
 * candidates are labelled {@code CARVED_FROM_KNOWN_BLOB} and reference the
 * container as {@code <path>#<offset>}, which {@code RecoveryCopier} already
 * knows how to slice out. Expensive, so registered experimental-only.
 */
public final class JpegKnownBlobCarverAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "jpeg_known_blob_carver";

    // Containers smaller than this rarely embed a recoverable JPEG; larger than
    // this is too costly to read fully into memory during a phone-side scan.
    private static final long MIN_CONTAINER_BYTES = 64L * 1024L;
    private static final long MAX_CONTAINER_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_CONTAINERS = 2_000;

    // Standalone media/document files are handled by FileTreeVisible / signature
    // sniffers; carving them again would only duplicate results.
    private static final Set<String> STANDALONE_EXTENSIONS = new HashSet<>();

    static {
        String[] exts = {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
            "mp4", "mov", "avi", "mkv", "3gp", "m4v",
            "mp3", "aac", "wav", "ogg", "flac", "amr", "m4a",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "apk", "zip",
        };
        for (String ext : exts) {
            STANDALONE_EXTENSIONS.add(ext);
        }
    }

    private final JpegBlobCarver carver = new JpegBlobCarver();
    private final Set<String> visitedDirs = new HashSet<>();
    private int containersScanned;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_jpeg_known_blob_carver;
    }

    @Override
    public RecoveryType[] supportedTypes() {
        return new RecoveryType[] {RecoveryType.IMAGE};
    }

    @Override
    public AlgorithmAvailability availability(AlgorithmContext context) {
        return AlgorithmAvailability.runnable();
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        visitedDirs.clear();
        containersScanned = 0;
        for (File root : RecoveryScanner.readableStorageRoots()) {
            if (callback.isCancelled()) {
                return;
            }
            scanRoot(root, context, callback);
        }
    }

    /** Test seam: carve a single directory tree. */
    void scanRoot(File root, AlgorithmContext context, AlgorithmCallback callback) {
        walk(root, context, callback, new int[] {0});
    }

    private void walk(File dir, AlgorithmContext context, AlgorithmCallback callback, int[] processed) {
        if (callback.isCancelled() || dir == null || !dir.exists() || !dir.canRead()) {
            return;
        }
        if (RecoveryScanner.isOutputDirectoryName(dir.getName())) {
            return;
        }
        if (!visitedDirs.add(canonicalPath(dir))) {
            return;
        }
        File[] children = listFiles(dir);
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (callback.isCancelled() || containersScanned >= MAX_CONTAINERS) {
                return;
            }
            if (child.isDirectory()) {
                walk(child, context, callback, processed);
            } else if (isCarveContainer(child)) {
                processed[0]++;
                if (processed[0] % 25 == 0) {
                    callback.onProgress(processed[0], child.getAbsolutePath());
                }
                carveContainer(child, callback);
            }
        }
    }

    private void carveContainer(File file, AlgorithmCallback callback) {
        containersScanned++;
        try {
            List<RecoveryCandidate> carved = carver.carve(file, new JpegBlobCarver.Progress() {
                @Override
                public void onCandidateFound(RecoveryCandidate candidate) {
                    // Forwarded below in bulk; nothing per-candidate here so the
                    // dedup pass in carve() can run first.
                }

                @Override
                public boolean isCancelled() {
                    return callback.isCancelled();
                }
            });
            for (RecoveryCandidate candidate : carved) {
                if (callback.isCancelled()) {
                    return;
                }
                callback.onCandidate(candidate);
            }
        } catch (IOException | OutOfMemoryError ignored) {
            // Oversized or unreadable container — skip and keep scanning.
        }
    }

    static boolean isCarveContainer(File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            return false;
        }
        long length = file.length();
        if (length < MIN_CONTAINER_BYTES || length > MAX_CONTAINER_BYTES) {
            return false;
        }
        String path = file.getAbsolutePath();
        // Already carved/handled by the cache-profile pipeline.
        if (CacheProfileRegistry.matchPath(path) != null) {
            return false;
        }
        // Standalone media/documents are surfaced by other algorithms.
        return !STANDALONE_EXTENSIONS.contains(extensionOf(file.getName()));
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
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
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }
}
