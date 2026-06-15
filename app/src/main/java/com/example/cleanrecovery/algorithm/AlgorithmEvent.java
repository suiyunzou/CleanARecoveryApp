package com.example.cleanrecovery.algorithm;

public final class AlgorithmEvent {
    public enum Kind {
        ALGORITHM_START,
        ALGORITHM_END,
        ALGORITHM_SKIPPED,
        ALGORITHM_ERROR
    }

    public final Kind kind;
    public final String algorithmId;
    public final long durationMs;
    public final int processed;
    public final int found;
    public final int duplicatesSkipped;
    public final String reason;

    private AlgorithmEvent(
            Kind kind,
            String algorithmId,
            long durationMs,
            int processed,
            int found,
            int duplicatesSkipped,
            String reason
    ) {
        this.kind = kind;
        this.algorithmId = algorithmId;
        this.durationMs = durationMs;
        this.processed = processed;
        this.found = found;
        this.duplicatesSkipped = duplicatesSkipped;
        this.reason = reason;
    }

    public static AlgorithmEvent algorithmStart(String algorithmId) {
        return new AlgorithmEvent(Kind.ALGORITHM_START, algorithmId, 0L, 0, 0, 0, null);
    }

    public static AlgorithmEvent algorithmEnd(
            String algorithmId,
            long durationMs,
            int processed,
            int found,
            int duplicatesSkipped
    ) {
        return new AlgorithmEvent(
                Kind.ALGORITHM_END,
                algorithmId,
                durationMs,
                processed,
                found,
                duplicatesSkipped,
                null
        );
    }

    public static AlgorithmEvent algorithmSkipped(String algorithmId, String reason) {
        return new AlgorithmEvent(Kind.ALGORITHM_SKIPPED, algorithmId, 0L, 0, 0, 0, reason);
    }

    public static AlgorithmEvent algorithmError(String algorithmId, String reason) {
        return new AlgorithmEvent(Kind.ALGORITHM_ERROR, algorithmId, 0L, 0, 0, 0, reason);
    }
}
