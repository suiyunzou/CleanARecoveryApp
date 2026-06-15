package com.example.cleanrecovery;

import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import java.io.File;
import java.util.Locale;

/**
 * Maps experiment {@link RecoveryCandidate} records into production {@link RecoveryItem}s.
 */
public final class RecoveryCandidateMapper {
    private RecoveryCandidateMapper() {
    }

    public static RecoveryItem toRecoveryItem(RecoveryCandidate candidate, RecoveryType requestedType) {
        if (candidate == null || requestedType == null) {
            return null;
        }
        RecoveryType type = recoveryTypeFor(candidate, requestedType);
        if (type == null) {
            return null;
        }
        String path = candidate.sourceUriOrPath == null ? "" : candidate.sourceUriOrPath;
        String name = displayNameFor(candidate, path);
        RecoverySourceKind sourceKind = mapSourceKind(candidate.sourceKind);
        boolean suspectedDeleted = candidate.sourceKind == CandidateSourceKind.MEDIASTORE_TRASH;
        long modifiedAt = 0L;
        return new RecoveryItem(
                type,
                name,
                path,
                candidate.byteLength,
                modifiedAt,
                candidate.width,
                candidate.height,
                suspectedDeleted,
                sourceKind
        );
    }

    static RecoveryType recoveryTypeFor(RecoveryCandidate candidate, RecoveryType requestedType) {
        RecoveryType detected = typeFromMime(candidate.mimeDetected);
        if (detected != null) {
            return detected == requestedType ? detected : null;
        }
        if (requestedType == RecoveryType.IMAGE) {
            return RecoveryType.IMAGE;
        }
        return null;
    }

    static RecoverySourceKind mapSourceKind(CandidateSourceKind candidateKind) {
        if (candidateKind == null) {
            return RecoverySourceKind.VISIBLE_SHARED_FILE;
        }
        switch (candidateKind) {
            case MEDIASTORE_TRASH:
                return RecoverySourceKind.MEDIASTORE_TRASH;
            case MEDIASTORE_PENDING:
                return RecoverySourceKind.MEDIASTORE_PENDING;
            case GENERIC_THUMBNAIL:
                return RecoverySourceKind.GENERIC_THUMBNAIL;
            case OEM_GALLERY_CACHE:
                return RecoverySourceKind.OEM_GALLERY_CACHE;
            case KNOWN_CACHE_BLOB:
                return RecoverySourceKind.KNOWN_CACHE_BLOB;
            case CARVED_FROM_KNOWN_BLOB:
                return RecoverySourceKind.CARVED_FROM_KNOWN_BLOB;
            case ACCESSIBLE_SIGNATURE_MATCH:
                return RecoverySourceKind.ACCESSIBLE_SIGNATURE_MATCH;
            case VISIBLE_SHARED_FILE:
            case MEDIASTORE_STALE_RECORD:
            default:
                return RecoverySourceKind.VISIBLE_SHARED_FILE;
        }
    }

    private static RecoveryType typeFromMime(String mimeDetected) {
        if (mimeDetected == null || mimeDetected.isEmpty()) {
            return null;
        }
        String mime = mimeDetected.toLowerCase(Locale.US);
        if (mime.startsWith("image/")) {
            return RecoveryType.IMAGE;
        }
        if (mime.startsWith("video/")) {
            return RecoveryType.VIDEO;
        }
        if (mime.startsWith("audio/")) {
            return RecoveryType.AUDIO;
        }
        if (mime.equals("application/pdf") || mime.equals("application/zip")) {
            return RecoveryType.DOCUMENT;
        }
        return null;
    }

    static String displayNameFor(RecoveryCandidate candidate, String path) {
        if (path != null && path.startsWith("content://")) {
            String fromContainer = nameFromOriginalContainer(candidate.originalContainer);
            if (fromContainer != null) {
                return fromContainer;
            }
            return "media_item.jpg";
        }
        String filePath = path == null ? "" : path;
        int hashIndex = filePath.indexOf('#');
        if (hashIndex > 0) {
            filePath = filePath.substring(0, hashIndex);
        }
        if (filePath.isEmpty()) {
            return "recovered_file";
        }
        String name = new File(filePath).getName();
        if (path != null && path.contains("#") && (name.isEmpty() || !name.contains("."))) {
            return "carved_" + path.substring(path.indexOf('#') + 1) + ".jpg";
        }
        return name.isEmpty() ? "recovered_file" : name;
    }

    private static String nameFromOriginalContainer(String originalContainer) {
        if (originalContainer == null || originalContainer.isEmpty()) {
            return null;
        }
        int colon = originalContainer.indexOf(':');
        String relative = colon >= 0 ? originalContainer.substring(colon + 1) : originalContainer;
        int slash = relative.lastIndexOf('/');
        if (slash >= 0 && slash < relative.length() - 1) {
            return relative.substring(slash + 1);
        }
        return relative.isEmpty() ? null : relative;
    }
}
