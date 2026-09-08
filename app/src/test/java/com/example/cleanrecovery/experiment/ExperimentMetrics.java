package com.example.cleanrecovery.experiment;

import java.util.Locale;

public final class ExperimentMetrics {
    public int candidateCount;
    public int exactMatchCount;
    public int derivativeMatchCount;
    public int falsePositiveCount;
    public int duplicateCount;
    public int metadataOnlyCount;
    public double exactRecall;
    public double derivativeRecall;
    public double precision;
    public double falsePositiveRate;
    public double duplicateRate;
    public long runtimeMs;
    public long peakMemoryBytes;
    public long bytesRead;
    public long bytesWritten;

    public String toJson() {
        return String.format(Locale.US, """
                {
                  "candidateCount": %d,
                  "exactMatchCount": %d,
                  "derivativeMatchCount": %d,
                  "falsePositiveCount": %d,
                  "duplicateCount": %d,
                  "metadataOnlyCount": %d,
                  "exactRecall": %.6f,
                  "derivativeRecall": %.6f,
                  "precision": %.6f,
                  "falsePositiveRate": %.6f,
                  "duplicateRate": %.6f,
                  "runtimeMs": %d,
                  "peakMemoryBytes": %d,
                  "bytesRead": %d,
                  "bytesWritten": %d
                }""",
                candidateCount,
                exactMatchCount,
                derivativeMatchCount,
                falsePositiveCount,
                duplicateCount,
                metadataOnlyCount,
                exactRecall,
                derivativeRecall,
                precision,
                falsePositiveRate,
                duplicateRate,
                runtimeMs,
                peakMemoryBytes,
                bytesRead,
                bytesWritten
        );
    }

    public static ExperimentMetrics fromCandidates(
            Iterable<RecoveryCandidate> candidates,
            int groundTruthExactTotal,
            int groundTruthDerivativeTotal,
            long runtimeMs,
            long bytesRead
    ) {
        ExperimentMetrics metrics = new ExperimentMetrics();
        metrics.runtimeMs = runtimeMs;
        metrics.bytesRead = bytesRead;
        int exact = 0;
        int derivative = 0;
        int falsePositive = 0;
        int metadataOnly = 0;
        int total = 0;
        for (RecoveryCandidate candidate : candidates) {
            total++;
            if (candidate.grade == ResultGrade.EXACT_BYTES || candidate.exactGroundTruthMatch) {
                exact++;
            } else if (candidate.grade == ResultGrade.VALID_DERIVATIVE || candidate.derivativeMatch) {
                derivative++;
            } else if (candidate.grade == ResultGrade.FALSE_POSITIVE) {
                falsePositive++;
            } else if (candidate.grade == ResultGrade.METADATA_ONLY) {
                metadataOnly++;
            }
        }
        metrics.candidateCount = total;
        metrics.exactMatchCount = exact;
        metrics.derivativeMatchCount = derivative;
        metrics.falsePositiveCount = falsePositive;
        metrics.metadataOnlyCount = metadataOnly;
        metrics.exactRecall = groundTruthExactTotal == 0 ? 0.0d : exact / (double) groundTruthExactTotal;
        metrics.derivativeRecall = groundTruthDerivativeTotal == 0 ? 0.0d : derivative / (double) groundTruthDerivativeTotal;
        int valid = exact + derivative;
        metrics.precision = total == 0 ? 0.0d : valid / (double) total;
        metrics.falsePositiveRate = total == 0 ? 0.0d : falsePositive / (double) total;
        return metrics;
    }
}
