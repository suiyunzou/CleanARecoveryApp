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

    @Test
    public void differentSourceTagsStageDifferentFiles() throws IOException {
        // Two whole-file carves (offset 0) with distinct source tags must land on
        // distinct staged paths — identical names made the deduper discard every
        // hit after the first and overwrite staged bytes on device.
        byte[] jpeg = new byte[256];
        putJpeg(jpeg, 0);

        File stagingDir = createTempDir("raw-carve-tags");
        List<RecoveryCandidate> first = new ArrayList<>();
        List<RecoveryCandidate> second = new ArrayList<>();
        new RawPartitionCarver().carveBytes(jpeg, 0L, "aaaa", stagingDir,
                CandidateSourceKind.OFFLINE_F2FS_METADATA, "plaintext", collecting(first));
        new RawPartitionCarver().carveBytes(jpeg, 0L, "bbbb", stagingDir,
                CandidateSourceKind.OFFLINE_F2FS_METADATA, "plaintext", collecting(second));

        assertEquals(1, first.size());
        assertEquals(1, second.size());
        String firstPath = first.get(0).sourceUriOrPath;
        String secondPath = second.get(0).sourceUriOrPath;
        assertTrue("first staged name carries its tag", firstPath.contains("aaaa_"));
        assertTrue("second staged name carries its tag", secondPath.contains("bbbb_"));
        assertTrue("staged names must differ", !firstPath.equals(secondPath));
        assertTrue("both staged files exist", new File(firstPath).isFile() && new File(secondPath).isFile());
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
