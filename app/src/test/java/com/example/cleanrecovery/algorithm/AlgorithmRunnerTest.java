package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.RecoveryType;
import com.example.cleanrecovery.ScanProgressTracker;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertTrue;

public final class AlgorithmRunnerTest {
    @Test
    public void failingAlgorithmDoesNotAbortFollowingAlgorithm() {
        AtomicBoolean successRan = new AtomicBoolean(false);
        RecoveryAlgorithm failing = new FakeAlgorithm("fake_failing", true, false);
        RecoveryAlgorithm success = new FakeAlgorithm("fake_success", false, true) {
            @Override
            public void scan(AlgorithmContext context, AlgorithmCallback callback) {
                successRan.set(true);
                callback.onCandidate(new RecoveryCandidate.Builder()
                        .sourceKind(CandidateSourceKind.VISIBLE_SHARED_FILE)
                        .sourceUriOrPath("/tmp/found.jpg")
                        .mimeDetected("image/jpeg")
                        .build());
            }
        };

        AlgorithmRunner runner = new AlgorithmRunner(Arrays.asList(failing, success));
        AlgorithmContext context = new AlgorithmContext(null, RecoveryType.IMAGE);
        runner.run(ScanMode.EXPERIMENTAL_ALL, RecoveryType.IMAGE, context, new AlgorithmRunner.Delegate() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public ScanProgressTracker.Phase phaseForAlgorithm(String algorithmId) {
                return ScanProgressTracker.Phase.FILE_SCAN;
            }

            @Override
            public void onAlgorithmPhase(ScanProgressTracker.Phase phase) {
            }

            @Override
            public void onCandidate(RecoveryCandidate candidate, ScanProgressTracker.Phase phase) {
            }

            @Override
            public void onProgress(int processed, String currentPath, ScanProgressTracker.Phase phase) {
            }

            @Override
            public int getDuplicateCount() {
                return 0;
            }
        });

        assertTrue(successRan.get());
    }

    private static class FakeAlgorithm implements RecoveryAlgorithm {
        private final String algorithmId;
        private final boolean fail;
        private final boolean runnable;

        FakeAlgorithm(String algorithmId, boolean fail, boolean runnable) {
            this.algorithmId = algorithmId;
            this.fail = fail;
            this.runnable = runnable;
        }

        @Override
        public String id() {
            return algorithmId;
        }

        @Override
        public int displayNameResId() {
            return 0;
        }

        @Override
        public RecoveryType[] supportedTypes() {
            return RecoveryType.scannableValues();
        }

        @Override
        public AlgorithmAvailability availability(AlgorithmContext context) {
            return runnable ? AlgorithmAvailability.runnable() : AlgorithmAvailability.disabled(0);
        }

        @Override
        public void scan(AlgorithmContext context, AlgorithmCallback callback) throws Exception {
            if (fail) {
                throw new RuntimeException("boom");
            }
        }
    }
}
