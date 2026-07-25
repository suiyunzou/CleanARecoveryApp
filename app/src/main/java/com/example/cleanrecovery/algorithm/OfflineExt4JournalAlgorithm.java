package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
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
        if (OfflinePartitionSupport.rootRunnable()) {
            return AlgorithmAvailability.runnable();
        }
        return AlgorithmAvailability.disabled(R.string.alg_reason_offline_requires_root);
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        // Root-only: read-only raw carve of the userdata partition when it is ext4.
        OfflinePartitionSupport.scan(
                context,
                callback,
                "EXT4",
                CandidateSourceKind.OFFLINE_EXT4_JOURNAL,
                "offline_partition_raw_carve");
    }
}
