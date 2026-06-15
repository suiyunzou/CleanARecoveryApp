package com.example.cleanrecovery;

import com.example.cleanrecovery.experiment.cache.CacheProfileRegistry;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Walks shared storage and visits files that match known cache profile paths.
 */
public final class CacheProfilePathWalker {
    public interface Callback {
        boolean isCancelled();

        void onProfileFile(File file, int scannedSoFar);
    }

    private final Set<String> visitedDirectories = new HashSet<>();

    public int walk(Callback callback) {
        visitedDirectories.clear();
        int total = 0;
        List<File> roots = RecoveryScanner.readableStorageRoots();
        for (File root : roots) {
            if (callback.isCancelled()) break;
            total += walkDirectory(root, callback, total);
        }
        return total;
    }

    public int walk(File root, Callback callback) {
        visitedDirectories.clear();
        return walkDirectory(root, callback, 0);
    }

    private int walkDirectory(File directory, Callback callback, int scannedSoFar) {
        if (callback.isCancelled() || directory == null || !directory.exists() || !directory.canRead()) {
            return scannedSoFar;
        }
        String canonicalPath = canonicalPathOf(directory);
        if (!visitedDirectories.add(canonicalPath)) {
            return scannedSoFar;
        }
        if (RecoveryScanner.isOutputDirectoryName(directory.getName())) {
            return scannedSoFar;
        }

        File[] children = listChildren(directory);
        if (children == null) {
            return scannedSoFar;
        }
        for (File child : children) {
            if (callback.isCancelled()) {
                return scannedSoFar;
            }
            if (child.isDirectory()) {
                scannedSoFar = walkDirectory(child, callback, scannedSoFar);
            } else if (CacheProfileRegistry.matchPath(child.getAbsolutePath()) != null) {
                scannedSoFar++;
                callback.onProfileFile(child, scannedSoFar);
            }
        }
        return scannedSoFar;
    }

    private static File[] listChildren(File directory) {
        try {
            return directory.listFiles();
        } catch (SecurityException exception) {
            return null;
        }
    }

    private static String canonicalPathOf(File directory) {
        try {
            return directory.getCanonicalPath();
        } catch (IOException exception) {
            return directory.getAbsolutePath();
        }
    }
}
