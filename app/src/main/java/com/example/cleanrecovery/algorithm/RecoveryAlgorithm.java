package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.RecoveryType;

public interface RecoveryAlgorithm {
    String id();

    int displayNameResId();

    RecoveryType[] supportedTypes();

    AlgorithmAvailability availability(AlgorithmContext context);

    void scan(AlgorithmContext context, AlgorithmCallback callback) throws Exception;
}
