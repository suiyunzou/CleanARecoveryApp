package com.example.cleanrecovery.algorithm;

import android.os.Environment;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.RecoveryType;

import java.io.File;

public final class AccessibleSignatureSnifferAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "accessible_signature_sniffer";

    private final SignatureSnifferSupport support = new SignatureSnifferSupport();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_accessible_signature_sniffer;
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
        scanRoot(Environment.getExternalStorageDirectory(), context, callback);
    }

    void scanRoot(java.io.File root, AlgorithmContext context, AlgorithmCallback callback) {
        support.walkAndProbe(
                root,
                context,
                callback,
                new SignatureSnifferSupport.FileFilter() {
                    @Override
                    public boolean shouldProbe(File file) {
                        return SignatureSnifferSupport.isExtensionless(file);
                    }
                }
        );
    }
}
