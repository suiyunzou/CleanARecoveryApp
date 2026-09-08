package com.example.cleanrecovery.algorithm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.recovery.RecoveryType;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class WechatDirectoryScannerAlgorithmTest {

    @Test
    public void discoversMediaNestedUnderAndroidDataMicroMsg() throws IOException {
        File root = createTempDir("wechat");
        // Reproduces the real-world depth that the previous recursion could not
        // reach: <root>/Android/data/com.tencent.mm/MicroMsg/image2/<file>.
        File image2 = new File(root, "Android/data/com.tencent.mm/MicroMsg/image2/sub");
        assertTrue(image2.mkdirs());
        writeBytes(new File(image2, "img_0001"), (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0);

        List<RecoveryCandidate> candidates = runScan(root, RecoveryType.IMAGE);
        assertEquals(1, candidates.size());
    }

    private static List<RecoveryCandidate> runScan(File root, RecoveryType type) {
        WechatDirectoryScannerAlgorithm algorithm = new WechatDirectoryScannerAlgorithm();
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
