package com.example.cleanrecovery;

import java.io.File;
import java.util.Locale;

public final class JunkCleaner {
    private JunkCleaner() {
    }

    public static boolean delete(JunkItem item) {
        File file = item.asFile();
        if (!file.exists()) {
            return true;
        }
        if (isProtectedOutput(file)) {
            return false;
        }
        if (file.isDirectory()) {
            return deleteDirectory(file);
        }
        return file.delete();
    }

    private static boolean deleteDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                boolean deleted = child.isDirectory() ? deleteDirectory(child) : child.delete();
                if (!deleted && child.exists()) {
                    return false;
                }
            }
        }
        return directory.delete();
    }

    private static boolean isProtectedOutput(File file) {
        String path = file.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.US);
        return path.contains("/datarecovery/");
    }
}
