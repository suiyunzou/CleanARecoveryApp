package com.example.cleanrecovery.experiment;

import java.util.Locale;

public final class GroundTruthMatcher {
    private GroundTruthMatcher() {
    }

    public static RecoveryCandidate gradeAgainstGroundTruth(
            RecoveryCandidate candidate,
            Iterable<GroundTruthEntry> groundTruth
    ) {
        ResultGrade grade = ResultGrade.FALSE_POSITIVE;
        boolean exact = false;
        boolean derivative = false;
        for (GroundTruthEntry entry : groundTruth) {
            if (!candidate.sha256.isEmpty() && candidate.sha256.equalsIgnoreCase(entry.sha256)) {
                exact = true;
                grade = ResultGrade.EXACT_BYTES;
                break;
            }
            if (!candidate.perceptualHash.isEmpty()
                    && candidate.perceptualHash.equalsIgnoreCase(entry.perceptualHash)
                    && entry.allowsDerivative) {
                derivative = true;
                grade = ResultGrade.VALID_DERIVATIVE;
            }
        }
        if ("METADATA_ONLY".equalsIgnoreCase(candidate.decodeStatus)) {
            grade = ResultGrade.METADATA_ONLY;
        }
        return new RecoveryCandidate.Builder()
                .candidateId(candidate.candidateId)
                .sourceKind(candidate.sourceKind)
                .sourceUriOrPath(candidate.sourceUriOrPath)
                .extractionMethod(candidate.extractionMethod)
                .originalContainer(candidate.originalContainer)
                .byteLength(candidate.byteLength)
                .sha256(candidate.sha256)
                .perceptualHash(candidate.perceptualHash)
                .mimeDetected(candidate.mimeDetected)
                .decodeStatus(candidate.decodeStatus)
                .width(candidate.width)
                .height(candidate.height)
                .exactGroundTruthMatch(exact)
                .derivativeMatch(derivative)
                .extractionOffsetStart(candidate.extractionOffsetStart)
                .extractionOffsetEnd(candidate.extractionOffsetEnd)
                .readBytes(candidate.readBytes)
                .elapsedMs(candidate.elapsedMs)
                .errorCode(candidate.errorCode)
                .label(candidate.label)
                .grade(grade)
                .build();
    }

    public static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.replace('\\', '/').toLowerCase(Locale.US);
    }
}
