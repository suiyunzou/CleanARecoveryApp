package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.recovery.RecoveryType;

public final class OfflineExt4JournalAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "offline_ext4_journal";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_offline_ext4_journal;
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
