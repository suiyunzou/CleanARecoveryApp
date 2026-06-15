package com.example.cleanrecovery;

import android.os.Environment;

import com.example.cleanrecovery.experiment.cache.CacheProfileRegistry;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
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
        return walk(Environment.getExternalStorageDirectory(), callback);
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
