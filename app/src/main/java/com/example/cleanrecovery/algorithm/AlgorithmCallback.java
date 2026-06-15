package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.experiment.RecoveryCandidate;

public interface AlgorithmCallback {
    boolean isCancelled();

    void onCandidate(RecoveryCandidate candidate);

    void onProgress(int processed, String currentPath);

    void onAlgorithmEvent(AlgorithmEvent event);
}
