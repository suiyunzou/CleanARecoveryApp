package com.example.cleanrecovery.algorithm;

import android.os.Environment;

import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.util.RootShell;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Shared driver for the two offline (root) partition-carving algorithms.
 * Prefers plaintext-readable media residuals (lower FBE risk), then streams the
 * userdata block device through {@link RawPartitionCarver}. All access is
 * read-only; carved bytes land in the app-private cache.
 */
final class OfflinePartitionSupport {
    private static final int HEADER_BYTES = 0x1000; // enough for ext4/F2FS superblock magic
    private static final String STAGING_DIR_NAME = "offline_carve";
    private static final long PLAINTEXT_MAX_FILE_BYTES = 64L * 1024L * 1024L;
    private static final int PLAINTEXT_MAX_FILES = 300;
    private static final int PLAINTEXT_MAX_DEPTH = 4;

    private OfflinePartitionSupport() {
    }

    static boolean rootRunnable() {
        return RootShell.isRootAvailable();
    }

    /** True when userdata uses file-based encryption (raw stream = ciphertext). */
    private static boolean isFileBasedEncryption() {
        String type = RootShell.getSystemProperty("ro.crypto.type");
        return "file".equalsIgnoreCase(type);
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

        File stagingDir = new File(context.context.getCacheDir(), STAGING_DIR_NAME);
        RecoveryType typeFilter = context.type;
        RawPartitionCarver carver = new RawPartitionCarver(typeFilter);

        // 1) Plaintext-first: carve large residual files under mounted media roots.
        //    On FBE devices this may still see decrypted content via the VFS.
        carvePlaintextResiduals(carver, stagingDir, sourceKind,
                extractionMethod + "_plaintext", callback);

        if (callback.isCancelled()) {
            return;
        }

        // 2) Raw userdata stream (may be ciphertext under FBE — still attempted).
        //    On FBE devices (every modern Android) file contents are encrypted at
        //    the filesystem layer, so the raw stream holds no carvable plaintext:
        //    skipping it saves minutes of pointless reading and keeps the result
        //    honest. Plaintext residual carving above stays the productive path.
        if (isFileBasedEncryption()) {
            callback.onProgress(0, "offline:fbe_encrypted_raw_stream_skipped");
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

        try (RootShell.PartitionStream stream = RootShell.openPartitionStream(device)) {
            carver.carveStream(stream.input, stagingDir, sourceKind, extractionMethod, callback);
        } catch (Exception ignored) {
            // Streaming/IO failure — leave whatever was already emitted.
        }
    }

    private static void carvePlaintextResiduals(
            RawPartitionCarver carver,
            File stagingDir,
            CandidateSourceKind sourceKind,
            String extractionMethod,
            AlgorithmCallback callback
    ) {
        List<File> roots = plaintextRoots();
        ArrayList<File> files = new ArrayList<>();
        for (File root : roots) {
            collectLargeFiles(root, 0, files, callback);
            if (files.size() >= PLAINTEXT_MAX_FILES || callback.isCancelled()) {
                break;
            }
        }
        int index = 0;
        for (File file : files) {
            if (callback.isCancelled()) {
                return;
            }
            callback.onProgress(index++, "plaintext:" + file.getAbsolutePath());
            try {
                carveFile(carver, file, stagingDir, sourceKind, extractionMethod, callback);
            } catch (Exception ignored) {
                // Skip unreadable residual; continue others.
            }
        }
    }

    private static void carveFile(
            RawPartitionCarver carver,
            File file,
            File stagingDir,
            CandidateSourceKind sourceKind,
            String extractionMethod,
            AlgorithmCallback callback
    ) throws IOException {
        long length = file.length();
        if (length <= 0L || length > PLAINTEXT_MAX_FILE_BYTES) {
            return;
        }
        byte[] data = new byte[(int) length];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = input.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset < data.length) {
                byte[] trimmed = new byte[offset];
                System.arraycopy(data, 0, trimmed, 0, offset);
                data = trimmed;
            }
        }
        carver.carveBytes(data, 0L, stagingTagFor(file), stagingDir, sourceKind, extractionMethod, callback);
    }

    /** Stable per-source tag so staged carve files never collide by name. */
    private static String stagingTagFor(File file) {
        return Integer.toHexString(file.getAbsolutePath().hashCode());
    }

    private static List<File> plaintextRoots() {
        ArrayList<File> roots = new ArrayList<>();
        // Narrow, high-signal directories first so the file cap isn't eaten by
        // the broad /sdcard walk before hidden subdirectories are reached.
        File external = Environment.getExternalStorageDirectory();
        if (external != null) {
            addIfDir(roots, new File(external, "DCIM"));
            addIfDir(roots, new File(external, "Pictures"));
            addIfDir(roots, new File(external, "Download"));
            addIfDir(roots, new File(external, "Documents"));
            addIfDir(roots, new File(external, "LOST.DIR"));
            addIfDir(roots, new File(external, ".trash"));
            // Common gallery / system trash locations (files may still exist after "delete").
            addIfDir(roots, new File(external, "DCIM/.trash"));
            addIfDir(roots, new File(external, "Pictures/.trash"));
            addIfDir(roots, new File(external, ".Trash"));
            addIfDir(roots, new File(external, "Android/data/com.google.android.apps.photos/files/trash"));
            addIfDir(roots, new File(external, "Android/media/com.google.android.apps.photos/trash"));
            // Catch-all last.
            addIfDir(roots, external);
        }
        addIfDir(roots, new File("/data/media/0"));
        addIfDir(roots, new File("/data/media/0/DCIM"));
        addIfDir(roots, new File("/data/media/0/Pictures"));
        addIfDir(roots, new File("/data/media/0/Download"));
        addIfDir(roots, new File("/data/media/0/Documents"));
        addIfDir(roots, new File("/sdcard"));
        return roots;
    }

    private static void addIfDir(List<File> roots, File dir) {
        if (dir != null && dir.isDirectory()) {
            for (File existing : roots) {
                if (existing.getAbsolutePath().equals(dir.getAbsolutePath())) {
                    return;
                }
            }
            roots.add(dir);
        }
    }

    private static void collectLargeFiles(
            File dir,
            int depth,
            List<File> out,
            AlgorithmCallback callback
    ) {
        if (dir == null || depth > PLAINTEXT_MAX_DEPTH || out.size() >= PLAINTEXT_MAX_FILES) {
            return;
        }
        if (callback.isCancelled()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (out.size() >= PLAINTEXT_MAX_FILES || callback.isCancelled()) {
                return;
            }
            if (child.isDirectory()) {
                String name = child.getName();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                // The Android subtree (other apps' data) is scanned through its
                // dedicated trash/oracle paths; walking it here would eat the
                // whole file budget before hidden media directories are reached.
                if ("Android".equals(name) && (depth == 0 || depth == 1)) {
                    continue;
                }
                collectLargeFiles(child, depth + 1, out, callback);
            } else if (child.isFile() && child.canRead()
                    && child.length() > 32L
                    && child.length() <= PLAINTEXT_MAX_FILE_BYTES) {
                // Include small recycle "data" payloads (name has no extension).
                out.add(child);
            }
        }
    }
}
