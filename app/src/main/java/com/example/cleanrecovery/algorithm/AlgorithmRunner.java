package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.RecoveryType;
import com.example.cleanrecovery.ScanDiagnostics;
import com.example.cleanrecovery.ScanProgressTracker;

import java.util.List;

public final class AlgorithmRunner {
    public interface Delegate {
        boolean isCancelled();

        ScanProgressTracker.Phase phaseForAlgorithm(String algorithmId);

        void onAlgorithmPhase(ScanProgressTracker.Phase phase);

        void onCandidate(com.example.cleanrecovery.experiment.RecoveryCandidate candidate, ScanProgressTracker.Phase phase);

        void onProgress(int processed, String currentPath, ScanProgressTracker.Phase phase);

        int getDuplicateCount();
    }

    private final List<RecoveryAlgorithm> algorithms;

    public AlgorithmRunner() {
        this(null);
    }

    public AlgorithmRunner(List<RecoveryAlgorithm> algorithms) {
        this.algorithms = algorithms;
    }

    public void run(ScanMode mode, RecoveryType type, AlgorithmContext context, Delegate delegate) {
        List<RecoveryAlgorithm> toRun = algorithms != null
                ? algorithms
                : AlgorithmRegistry.runnableForMode(mode, type);
        for (RecoveryAlgorithm algorithm : toRun) {
            if (delegate.isCancelled()) {
                break;
            }
            if (!AlgorithmRegistry.supportsType(algorithm, type)) {
                continue;
            }
            AlgorithmAvailability availability = AlgorithmRegistry.resolvedAvailability(algorithm, context);
            if (!availability.isRunnable()) {
                String reason = skipReason(availability);
                ScanDiagnostics.algorithmSkipped(algorithm.id(), reason);
                continue;
            }
            ScanProgressTracker.Phase phase = delegate.phaseForAlgorithm(algorithm.id());
            delegate.onAlgorithmPhase(phase);
            ScanDiagnostics.algorithmStart(algorithm.id(), type);
            long startedAt = System.currentTimeMillis();
            final int[] processed = {0};
            final int[] found = {0};
            final int duplicatesAtStart = delegate.getDuplicateCount();
            AlgorithmCallback callback = new AlgorithmCallback() {
                @Override
                public boolean isCancelled() {
                    return delegate.isCancelled();
                }

                @Override
                public void onCandidate(com.example.cleanrecovery.experiment.RecoveryCandidate candidate) {
                    found[0]++;
                    delegate.onCandidate(candidate, phase);
                }

                @Override
                public void onProgress(int processedCount, String currentPath) {
                    processed[0] = processedCount;
                    delegate.onProgress(processedCount, currentPath, phase);
                }

                @Override
                public void onAlgorithmEvent(AlgorithmEvent event) {
                    // Coordinator logs algorithm lifecycle directly.
                }
            };
            try {
                algorithm.scan(context, callback);
                int duplicatesSkipped = delegate.getDuplicateCount() - duplicatesAtStart;
                ScanDiagnostics.algorithmEnd(
                        algorithm.id(),
                        type,
                        System.currentTimeMillis() - startedAt,
                        processed[0],
                        found[0],
                        duplicatesSkipped
                );
            } catch (Exception exception) {
                ScanDiagnostics.algorithmError(algorithm.id(), exception.getMessage());
                ScanDiagnostics.error("algorithm", algorithm.id(), exception);
            }
        }
    }

    private static String skipReason(AlgorithmAvailability availability) {
        switch (availability.getStatus()) {
            case REQUIRES_API:
                return "requiresApi=" + availability.getMinApi();
            case EVIDENCE_ONLY:
                return "evidenceOnly";
            case DISABLED:
            default:
                return "disabled";
        }
    }
}
