package com.example.cleanrecovery.algorithm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class RawPartitionCarverTest {

    @Test
    public void carvesEmbeddedJpegFromStreamAndStagesIt() throws IOException {
        byte[] partition = new byte[8192];
        // Minimal JPEG at offset 100.
        partition[100] = (byte) 0xFF;
        partition[101] = (byte) 0xD8;
        partition[102] = (byte) 0xFF;
        partition[103] = (byte) 0xD9;

        File stagingDir = createTempDir("raw-carve");
        List<RecoveryCandidate> candidates = new ArrayList<>();

        int emitted = new RawPartitionCarver().carveStream(
                new ByteArrayInputStream(partition),
                stagingDir,
                CandidateSourceKind.OFFLINE_F2FS_METADATA,
                "offline_partition_raw_carve",
                collecting(candidates));

        assertEquals(1, emitted);
        assertEquals(1, candidates.size());
        RecoveryCandidate candidate = candidates.get(0);
        assertEquals(CandidateSourceKind.OFFLINE_F2FS_METADATA, candidate.sourceKind);
        assertTrue("staged file should exist", new File(candidate.sourceUriOrPath).isFile());
    }

    @Test
    public void dedupesIdenticalJpegsAcrossStream() throws IOException {
        byte[] partition = new byte[8192];
        // Two identical minimal JPEGs -> same sha256 -> deduped to one.
        putJpeg(partition, 100);
        putJpeg(partition, 4000);

        File stagingDir = createTempDir("raw-carve-dedupe");
        List<RecoveryCandidate> candidates = new ArrayList<>();
        int emitted = new RawPartitionCarver().carveStream(
                new ByteArrayInputStream(partition),
                stagingDir,
                CandidateSourceKind.OFFLINE_EXT4_JOURNAL,
                "offline_partition_raw_carve",
                collecting(candidates));

        assertEquals(1, emitted);
    }

    private static void putJpeg(byte[] data, int at) {
        data[at] = (byte) 0xFF;
        data[at + 1] = (byte) 0xD8;
        data[at + 2] = (byte) 0xFF;
        data[at + 3] = (byte) 0xD9;
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

    private static File createTempDir(String prefix) throws IOException {
        File dir = File.createTempFile(prefix, "-dir");
        if (!dir.delete() || !dir.mkdir()) {
            throw new IOException("Failed to create temp dir");
        }
        return dir;
    }
}
