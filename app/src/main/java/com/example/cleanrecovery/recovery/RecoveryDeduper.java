package com.example.cleanrecovery.recovery;

import java.util.Locale;

public final class RecoveryDeduper {
    private final java.util.Set<String> seenContentUris = new java.util.HashSet<>();
    private final java.util.Set<String> seenCanonicalPaths = new java.util.HashSet<>();
    private int duplicateCount;

    public void clear() {
        seenContentUris.clear();
        seenCanonicalPaths.clear();
        duplicateCount = 0;
    }

    public boolean isDuplicate(RecoveryItem item) {
        String path = item.path;
        if (path != null && path.startsWith("content://") && seenContentUris.contains(path)) {
            duplicateCount++;
            return true;
        }
        String normalized = normalizePath(path);
        if (!normalized.isEmpty() && seenCanonicalPaths.contains(normalized)) {
            duplicateCount++;
            return true;
        }
        remember(item);
        return false;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    private void remember(RecoveryItem item) {
        String path = item.path;
        if (path != null && path.startsWith("content://")) {
            seenContentUris.add(path);
        }
        String normalized = normalizePath(path);
        if (!normalized.isEmpty()) {
            seenCanonicalPaths.add(normalized);
        }
    }

    static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.replace('\\', '/').toLowerCase(Locale.US);
    }
}
