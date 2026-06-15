package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.RecoveryItem;
import com.example.cleanrecovery.RecoveryScanner;
import com.example.cleanrecovery.RecoveryType;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

public final class FileTreeVisibleAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "file_tree_visible";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_file_tree_visible;
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
        RecoveryScanner scanner = new RecoveryScanner();
        final int[] processed = {0};
        scanner.scan(context.type, new RecoveryScanner.Callback() {
            @Override
            public boolean isCancelled() {
                return callback.isCancelled();
            }

            @Override
            public void onProgress(int scannedCount, int foundCount, String currentPath) {
                processed[0] = scannedCount;
                callback.onProgress(scannedCount, currentPath);
            }

            @Override
            public void onItemFound(RecoveryItem item) {
                RecoveryCandidate candidate = toCandidate(item);
                if (candidate != null) {
                    callback.onCandidate(candidate);
                }
            }

            @Override
            public void onDone(int scannedCount, int foundCount) {
                processed[0] = scannedCount;
            }

            @Override
            public void onError(java.io.File file, Exception exception) {
                // Errors are logged by RecoveryScanner.
            }
        });
    }

    static RecoveryCandidate toCandidate(RecoveryItem item) {
        if (item == null) {
            return null;
        }
        return new RecoveryCandidate.Builder()
                .candidateId(item.path)
                .sourceKind(CandidateSourceKind.VISIBLE_SHARED_FILE)
                .sourceUriOrPath(item.path)
                .extractionMethod("file_tree_visible")
                .byteLength(item.size)
                .mimeDetected(mimeFor(item.type))
                .width(item.width)
                .height(item.height)
                .build();
    }

    private static String mimeFor(RecoveryType type) {
        switch (type) {
            case IMAGE:
                return "image/jpeg";
            case VIDEO:
                return "video/mp4";
            case AUDIO:
                return "audio/mpeg";
            case DOCUMENT:
                return "application/pdf";
            default:
                return "";
        }
    }
}
