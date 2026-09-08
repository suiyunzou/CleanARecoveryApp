package com.example.cleanrecovery.recycle;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.cleanrecovery.algorithm.AlgorithmCallback;
import com.example.cleanrecovery.algorithm.AlgorithmEvent;
import com.example.cleanrecovery.algorithm.RawPartitionCarver;
import com.example.cleanrecovery.algorithm.carve.SignatureFixtures;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.recovery.RecoveryType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceptance script for recycle delete/restore plus deep carve after permanent delete.
 * Recycle stays isolated from RecoveryCopier; deep recovery only sees residual bytes.
 */
public final class RecycleBinDeepRecoveryAcceptanceTest {

    private File workspace;
    private File trashRoot;
    private RecycleBin bin;

    @Before
    public void setUp() throws IOException {
        workspace = Files.createTempDirectory("rb_deep").toFile();
        trashRoot = new File(workspace, "trash");
        bin = new RecycleBin(trashRoot,
                RecycleBin.DEFAULT_RETENTION_MILLIS,
                RecycleBin.DEFAULT_MAX_BYTES);
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursively(workspace);
    }

    @Test
    public void trashThenRestore_roundTripsFourTypes() throws IOException {
        assertTrashRestore("sample.jpg", SignatureFixtures.jpeg());
        assertTrashRestore("sample.png", SignatureFixtures.png());
        assertTrashRestore("sample.mp4", SignatureFixtures.mp4());
        assertTrashRestore("sample.flac", SignatureFixtures.flac());
        assertTrashRestore("sample.pdf", SignatureFixtures.pdf());
        assertTrashRestore("sample.docx", SignatureFixtures.docx());
    }

    @Test
    public void permanentDelete_thenSignatureCarveRecoversPayload() throws IOException {
        byte[] png = SignatureFixtures.png();
        File original = new File(workspace, "gone.png");
        Files.write(original.toPath(), png);
        assertTrue(bin.moveToTrashSync(original));
        assertFalse(original.exists());

        List<RecycleBin.RecycleEntry> entries = bin.listSync();
        assertEquals(1, entries.size());
        String entryId = entries.get(0).id;
        assertTrue(bin.permanentDeleteSync(entryId));
        assertTrue(bin.listSync().isEmpty());
        assertFalse(original.exists());

        // Simulate unallocated residual still holding the payload (post-unlink).
        byte[] partition = SignatureFixtures.deletedLayout(png, 256);
        File staging = Files.createTempDirectory(workspace.toPath(), "stage").toFile();
        List<RecoveryCandidate> carved = new ArrayList<>();
        int emitted = new RawPartitionCarver(RecoveryType.IMAGE).carveStream(
                new ByteArrayInputStream(partition),
                staging,
                CandidateSourceKind.OFFLINE_F2FS_METADATA,
                "offline_partition_raw_carve",
                collecting(carved));

        assertTrue(emitted >= 1);
        boolean recovered = false;
        for (RecoveryCandidate candidate : carved) {
            if ("image/png".equals(candidate.mimeDetected)) {
                byte[] bytes = Files.readAllBytes(new File(candidate.sourceUriOrPath).toPath());
                assertTrue(java.util.Arrays.equals(bytes, png));
                recovered = true;
            }
        }
        assertTrue(recovered);
    }

    private void assertTrashRestore(String name, byte[] payload) throws IOException {
        File original = new File(workspace, name);
        Files.write(original.toPath(), payload);
        assertTrue(bin.moveToTrashSync(original));
        assertFalse(original.exists());

        List<RecycleBin.RecycleEntry> entries = bin.listSync();
        assertFalse(entries.isEmpty());
        RecycleBin.RecycleEntry match = null;
        for (RecycleBin.RecycleEntry entry : entries) {
            if (name.equals(entry.originalName)) {
                match = entry;
                break;
            }
        }
        assertNotNull(match);
        File restored = bin.restoreSync(match.id);
        assertNotNull(restored);
        assertTrue(restored.exists());
        assertEquals(payload.length, restored.length());
        assertTrue(java.util.Arrays.equals(payload, Files.readAllBytes(restored.toPath())));
        // Clean for next sample.
        //noinspection ResultOfMethodCallIgnored
        restored.delete();
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

    private static void deleteRecursively(File file) throws IOException {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        Files.deleteIfExists(file.toPath());
    }
}
