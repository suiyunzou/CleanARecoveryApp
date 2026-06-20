package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.scan.ScanProgressTracker;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

public final class FakeCoordinatorAlgorithms {
    private FakeCoordinatorAlgorithms() {
    }

    public static AlgorithmRunner.Delegate noopDelegate() {
        return new AlgorithmRunner.Delegate() {
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
            public void onAlgorithmEvent(AlgorithmEvent event) {
            }

            @Override
            public int getDuplicateCount() {
                return 0;
            }
        };
    }

    public static final class SuccessCounter {
        private boolean called;

        public boolean wasCalled() {
            return called;
        }

        public void markCalled() {
            called = true;
        }
    }

    public static final class FailingAlgorithm implements RecoveryAlgorithm {
        @Override
        public String id() {
            return "fake_failing";
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
            return AlgorithmAvailability.runnable();
        }

        @Override
        public void scan(AlgorithmContext context, AlgorithmCallback callback) {
            throw new RuntimeException("boom");
        }
    }

    public static final class SuccessAlgorithm implements RecoveryAlgorithm {
        private final SuccessCounter counter;

        public SuccessAlgorithm(SuccessCounter counter) {
            this.counter = counter;
        }

        @Override
        public String id() {
            return "fake_success";
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
            return AlgorithmAvailability.runnable();
        }

        @Override
        public void scan(AlgorithmContext context, AlgorithmCallback callback) {
            counter.markCalled();
            callback.onCandidate(new RecoveryCandidate.Builder()
                    .sourceKind(CandidateSourceKind.VISIBLE_SHARED_FILE)
                    .sourceUriOrPath("/tmp/found.jpg")
                    .mimeDetected("image/jpeg")
                    .build());
        }
    }
}
