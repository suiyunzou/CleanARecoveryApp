package com.example.cleanrecovery.experiment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CandidateDeduper {
    private final Set<String> seenContentUris = new HashSet<>();
    private final Set<String> seenCanonicalPaths = new HashSet<>();
    private final Set<String> seenSha256 = new HashSet<>();
    private final Set<String> seenPerceptualHashes = new HashSet<>();
    private int duplicateCount;

    public List<RecoveryCandidate> dedupe(List<RecoveryCandidate> candidates) {
        duplicateCount = 0;
        ArrayList<RecoveryCandidate> unique = new ArrayList<>();
        for (RecoveryCandidate candidate : candidates) {
            if (isDuplicate(candidate)) {
                duplicateCount++;
                continue;
            }
            remember(candidate);
            unique.add(candidate);
        }
        return unique;
    }

    public int getDuplicateCount() {
        return duplicateCount;
    }

    private boolean isDuplicate(RecoveryCandidate candidate) {
        String uri = candidate.sourceUriOrPath;
        if (uri != null && uri.startsWith("content://") && seenContentUris.contains(uri)) {
            return true;
        }
        String path = GroundTruthMatcher.normalizePath(candidate.sourceUriOrPath);
        if (!path.isEmpty() && seenCanonicalPaths.contains(path)) {
            return true;
        }
        if (!candidate.sha256.isEmpty() && seenSha256.contains(candidate.sha256.toLowerCase(Locale.US))) {
            return true;
        }
        if (!candidate.perceptualHash.isEmpty()
                && seenPerceptualHashes.contains(candidate.perceptualHash.toLowerCase(Locale.US))) {
            return true;
        }
        return false;
    }

    private void remember(RecoveryCandidate candidate) {
        String uri = candidate.sourceUriOrPath;
        if (uri != null && uri.startsWith("content://")) {
            seenContentUris.add(uri);
        }
        String path = GroundTruthMatcher.normalizePath(candidate.sourceUriOrPath);
        if (!path.isEmpty()) {
            seenCanonicalPaths.add(path);
        }
        if (!candidate.sha256.isEmpty()) {
            seenSha256.add(candidate.sha256.toLowerCase(Locale.US));
        }
        if (!candidate.perceptualHash.isEmpty()) {
            seenPerceptualHashes.add(candidate.perceptualHash.toLowerCase(Locale.US));
        }
    }
}
