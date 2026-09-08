package com.example.cleanrecovery.algorithm.carve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.cleanrecovery.algorithm.AlgorithmCallback;
import com.example.cleanrecovery.algorithm.AlgorithmEvent;
import com.example.cleanrecovery.algorithm.FileSignatureProbe;
import com.example.cleanrecovery.algorithm.RawPartitionCarver;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.recovery.RecoveryType;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Hard acceptance: thoroughly "deleted" payloads of all four RecoveryTypes can be
 * carved back from a synthetic partition image (unallocated-gap layout).
 */
public final class SignatureCarverHardAcceptTest {

    @Test
    public void carvesDeletedJpegImage() throws IOException {
        assertCarved(SignatureFixtures.jpeg(), RecoveryType.IMAGE, "image/jpeg", "jpg");
    }

    @Test
    public void carvesDeletedPngImage() throws IOException {
        assertCarved(SignatureFixtures.png(), RecoveryType.IMAGE, "image/png", "png");
    }

    @Test
    public void carvesDeletedMp4Video() throws IOException {
        assertCarved(SignatureFixtures.mp4(), RecoveryType.VIDEO, "video/mp4", "mp4");
    }

    @Test
    public void carvesDeletedFlacAudio() throws IOException {
        assertCarved(SignatureFixtures.flac(), RecoveryType.AUDIO, "audio/flac", "flac");
    }

    @Test
    public void carvesDeletedPdfDocument() throws IOException {
        assertCarved(SignatureFixtures.pdf(), RecoveryType.DOCUMENT, "application/pdf", "pdf");
    }

    @Test
    public void carvesDeletedDocxDocument() throws IOException {
        assertCarved(SignatureFixtures.docx(), RecoveryType.DOCUMENT, FileSignatureProbe.MIME_DOCX, "docx");
    }

    @Test
    public void typeFilterKeepsOnlyRequestedType() throws IOException {
        byte[] partition = SignatureFixtures.deletedLayout(SignatureFixtures.png(), 200);
        // also plant a pdf after the png
        byte[] pdf = SignatureFixtures.pdf();
        System.arraycopy(pdf, 0, partition, 2000, pdf.length);

        List<RecoveryCandidate> candidates = carve(partition, RecoveryType.IMAGE);
        assertFalse(candidates.isEmpty());
        for (RecoveryCandidate candidate : candidates) {
            assertTrue(candidate.mimeDetected.startsWith("image/"));
        }
    }

    private static void assertCarved(
            byte[] payload,
            RecoveryType type,
            String expectedMime,
            String expectedExt
    ) throws IOException {
        byte[] partition = SignatureFixtures.deletedLayout(payload, 128);
        List<RecoveryCandidate> candidates = carve(partition, type);
        assertFalse("expected carved candidate for " + expectedMime, candidates.isEmpty());

        boolean matched = false;
        for (RecoveryCandidate candidate : candidates) {
            File staged = new File(candidate.sourceUriOrPath);
            assertTrue("staged file missing", staged.isFile());
            assertTrue(staged.getName().endsWith("." + expectedExt)
                    || expectedMime.equals(candidate.mimeDetected));
            if (expectedMime.equals(candidate.mimeDetected)) {
                byte[] recovered = Files.readAllBytes(staged.toPath());
                assertTrue("recovered bytes should contain original payload",
                        indexOf(recovered, payload) >= 0 || java.util.Arrays.equals(recovered, payload));
                matched = true;
            }
        }
        assertTrue("mime " + expectedMime + " not found among " + candidates.size() + " hits", matched);
        assertEquals(CandidateSourceKind.OFFLINE_F2FS_METADATA, candidates.get(0).sourceKind);
    }

    private static List<RecoveryCandidate> carve(byte[] partition, RecoveryType type) throws IOException {
        File stagingDir = Files.createTempDirectory("sig-carve").toFile();
        List<RecoveryCandidate> candidates = new ArrayList<>();
        new RawPartitionCarver(type).carveStream(
                new ByteArrayInputStream(partition),
                stagingDir,
                CandidateSourceKind.OFFLINE_F2FS_METADATA,
                "offline_partition_raw_carve",
                collecting(candidates));
        return candidates;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
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
}
