package com.example.cleanrecovery.algorithm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.recovery.RecoveryType;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class JpegKnownBlobCarverAlgorithmTest {

    @Test
    public void carvesEmbeddedJpegFromOpaqueBlob() throws IOException {
        File root = createTempDir("blob-carve");
        // 64KB+ container whose extension is not a standalone media type, with a
        // minimal JPEG (SOI..EOI) embedded near the end.
        writeBlobWithJpeg(new File(root, "cache_store.dat"), 64 * 1024 + 16);

        List<RecoveryCandidate> candidates = runScan(root);
        assertEquals(1, candidates.size());
        assertEquals(CandidateSourceKind.CARVED_FROM_KNOWN_BLOB, candidates.get(0).sourceKind);
    }

    @Test
    public void skipsStandaloneJpegFiles() throws IOException {
        File root = createTempDir("blob-skip-jpg");
        // A normal .jpg is surfaced by other algorithms — must not be re-carved.
        writeBlobWithJpeg(new File(root, "photo.jpg"), 64 * 1024 + 16);
        assertTrue(runScan(root).isEmpty());
    }

    @Test
    public void skipsTooSmallContainers() throws IOException {
        File root = createTempDir("blob-skip-small");
        writeBlobWithJpeg(new File(root, "tiny.dat"), 1024);
        assertTrue(runScan(root).isEmpty());
    }

    @Test
    public void isCarveContainerRejectsKnownExtensions() throws IOException {
        File root = createTempDir("blob-ext");
        File png = new File(root, "x.png");
        writeBlobWithJpeg(png, 64 * 1024 + 16);
        assertFalse(JpegKnownBlobCarverAlgorithm.isCarveContainer(png));
    }

    private static List<RecoveryCandidate> runScan(File root) {
        JpegKnownBlobCarverAlgorithm algorithm = new JpegKnownBlobCarverAlgorithm();
        AlgorithmContext context = new AlgorithmContext(null, RecoveryType.IMAGE);
        final List<RecoveryCandidate> candidates = new ArrayList<>();
        algorithm.scanRoot(root, context, collecting(candidates));
        return candidates;
    }

    private static AlgorithmCallback collecting(final List<RecoveryCandidate> sink) {
        return new AlgorithmCallback() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCandidate(RecoveryCandidate candidate) {
                sink.add(candidate);
            }

            @Override
            public void onProgress(int processed, String currentPath) {
            }

            @Override
            public void onAlgorithmEvent(AlgorithmEvent event) {
            }
        };
    }

    private static void writeBlobWithJpeg(File file, int totalSize) throws IOException {
        byte[] data = new byte[totalSize];
        // ...zeros... FF D8 FF D9 near the end (minimal valid JPEG).
        data[totalSize - 6] = (byte) 0xFF;
        data[totalSize - 5] = (byte) 0xD8;
        data[totalSize - 4] = (byte) 0xFF;
        data[totalSize - 3] = (byte) 0xD9;
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    private static File createTempDir(String prefix) throws IOException {
        File dir = File.createTempFile(prefix, "-dir");
        if (!dir.delete() || !dir.mkdir()) {
            throw new IOException("Failed to create temp dir");
        }
        return dir;
    }
}
