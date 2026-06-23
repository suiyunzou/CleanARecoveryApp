package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class AccessibleSignatureSnifferAlgorithmTest {
    @Test
    public void extensionlessJpegEmitsImageCandidate() throws IOException {
        File root = createTempDir("sig-sniff");
        File orphan = new File(root, "orphan");
        writeBytes(orphan, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0);

        List<RecoveryCandidate> candidates = runScan(root, RecoveryType.IMAGE);

        assertEquals(1, candidates.size());
        assertEquals(CandidateSourceKind.ACCESSIBLE_SIGNATURE_MATCH, candidates.get(0).sourceKind);
    }

    @Test
    public void pdfRejectedForImageScanAcceptedForDocumentScan() throws IOException {
        File root = createTempDir("sig-sniff-doc");
        File orphan = new File(root, "note");
        writeBytes(orphan, '%', 'P', 'D', 'F', '-', '1', '.', '4');

        assertTrue(runScan(root, RecoveryType.IMAGE).isEmpty());
        assertEquals(1, runScan(root, RecoveryType.DOCUMENT).size());
    }

    private static List<RecoveryCandidate> runScan(File root, RecoveryType type) {
        AccessibleSignatureSnifferAlgorithm algorithm = new AccessibleSignatureSnifferAlgorithm();
        AlgorithmContext context = new AlgorithmContext(null, type);
        final List<RecoveryCandidate> candidates = new ArrayList<>();
        algorithm.scanRoot(root, context, new AlgorithmCallback() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCandidate(RecoveryCandidate candidate) {
                candidates.add(candidate);
            }

            @Override
            public void onProgress(int processed, String currentPath) {
            }

            @Override
            public void onAlgorithmEvent(AlgorithmEvent event) {
            }
        });
        return candidates;
    }

    private static File createTempDir(String prefix) throws IOException {
        File dir = File.createTempFile(prefix, "-dir");
        if (!dir.delete() || !dir.mkdir()) {
            throw new IOException("Failed to create temp dir");
        }
        return dir;
    }

    private static void writeBytes(File file, int... values) throws IOException {
        byte[] bytes = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
    }
}
