package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.CacheProfilePathWalker;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.RecoveryType;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.experiment.cache.CacheProfileScanner;

import java.io.File;

public final class CacheProfileAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "oem_cache_profiles";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_oem_cache_profiles;
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
        final CacheProfileScanner cacheScanner = new CacheProfileScanner();
        final CacheProfilePathWalker walker = new CacheProfilePathWalker();
        walker.walk(new CacheProfilePathWalker.Callback() {
            @Override
            public boolean isCancelled() {
                return callback.isCancelled();
            }

            @Override
            public void onProfileFile(File file, int scannedSoFar) {
                cacheScanner.scanFile(file, new CacheProfileScanner.Callback() {
                    @Override
                    public boolean isCancelled() {
                        return callback.isCancelled();
                    }

                    @Override
                    public void onCandidate(RecoveryCandidate candidate) {
                        callback.onCandidate(candidate);
                    }
                });
                callback.onProgress(scannedSoFar, file.getAbsolutePath());
            }
        });
    }
}
