package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.RecoveryType;

public final class OfflineF2fsImageAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "offline_f2fs_image";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_offline_f2fs_image;
    }

    @Override
    public RecoveryType[] supportedTypes() {
        return RecoveryType.scannableValues();
    }

    @Override
    public AlgorithmAvailability availability(AlgorithmContext context) {
        return AlgorithmAvailability.disabled(R.string.alg_reason_offline_requires_image);
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        // Disabled offline card; not runnable against normal phone storage.
    }
}
