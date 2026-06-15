package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.RecoveryType;

public final class LogEvidenceImportAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "log_evidence_import";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_log_evidence_import;
    }

    @Override
    public RecoveryType[] supportedTypes() {
        return RecoveryType.scannableValues();
    }

    @Override
    public AlgorithmAvailability availability(AlgorithmContext context) {
        return AlgorithmAvailability.evidenceOnly(R.string.alg_reason_log_evidence_only);
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        // Evidence-only card; not executed during normal or experimental scans.
    }
}
