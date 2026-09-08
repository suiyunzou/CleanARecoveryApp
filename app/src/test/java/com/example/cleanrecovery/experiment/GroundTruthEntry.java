package com.example.cleanrecovery.experiment;

public final class GroundTruthEntry {
    public final String groundTruthId;
    public final String originalPath;
    public final String sha256;
    public final String perceptualHash;
    public final boolean expectsExactBytes;
    public final boolean allowsDerivative;

    public GroundTruthEntry(
            String groundTruthId,
            String originalPath,
            String sha256,
            String perceptualHash,
            boolean expectsExactBytes,
            boolean allowsDerivative
    ) {
        this.groundTruthId = groundTruthId;
        this.originalPath = originalPath;
        this.sha256 = sha256;
        this.perceptualHash = perceptualHash;
        this.expectsExactBytes = expectsExactBytes;
        this.allowsDerivative = allowsDerivative;
    }
}
