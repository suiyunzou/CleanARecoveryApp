package com.example.cleanrecovery.algorithm;

import android.os.Build;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.RecoveryType;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.experiment.mediastore.MediaStoreExperimentScanner;

public final class MediaStoreIndexTrashAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "mediastore_index_trash";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_mediastore_index_trash;
    }

    @Override
    public RecoveryType[] supportedTypes() {
        return new RecoveryType[] {RecoveryType.IMAGE};
    }

    @Override
    public AlgorithmAvailability availability(AlgorithmContext context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return AlgorithmAvailability.runnable();
        }
        return AlgorithmAvailability.requiresApi(Build.VERSION_CODES.Q);
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        MediaStoreExperimentScanner scanner = new MediaStoreExperimentScanner(context.context);
        scanner.scan(new MediaStoreExperimentScanner.Callback() {
            @Override
            public boolean isCancelled() {
                return callback.isCancelled();
            }

            @Override
            public void onCandidate(RecoveryCandidate candidate) {
                callback.onCandidate(candidate);
            }

            @Override
            public void onProgress(String message) {
                // Cursor progress is surfaced via candidate count.
            }
        });
    }
}
