package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.RecoveryType;

/**
 * Registry placeholder: JPEG carving runs inside {@link CacheProfileAlgorithm}.
 */
public final class JpegKnownBlobCarverAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "jpeg_known_blob_carver";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_jpeg_known_blob_carver;
    }

    @Override
    public RecoveryType[] supportedTypes() {
        return new RecoveryType[] {RecoveryType.IMAGE};
    }

    @Override
    public AlgorithmAvailability availability(AlgorithmContext context) {
        return AlgorithmAvailability.runnable();
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        // Executed through CacheProfileAlgorithm / CacheProfileScanner delegation.
    }
}
