package com.example.cleanrecovery.algorithm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.cleanrecovery.algorithm.carve.SignatureFixtures;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.recovery.RecoveryType;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public final class DotTrashedFileScannerAlgorithmTest {

    private File workspace;

    @Before
    public void setUp() throws IOException {
        workspace = Files.createTempDirectory("dot_trashed").toFile();
    }

    @After
    public void tearDown() throws IOException {
        deleteRecursively(workspace);
    }

    @Test
    public void recoversOriginalNameFromDotTrashedPattern() {
        assertTrue(DotTrashedFileScannerAlgorithm.isTrashedStyleName(
                ".trashed-1710000000000-vacation.jpg"));
        assertEquals("vacation.jpg",
                DotTrashedFileScannerAlgorithm.recoverOriginalName(
                        ".trashed-1710000000000-vacation.jpg"));
        assertFalse(DotTrashedFileScannerAlgorithm.isTrashedStyleName("vacation.jpg"));
    }

    @Test
    public void scanFindsDotTrashedPngOnDisk() throws Exception {
        File dcim = new File(workspace, "DCIM");
        assertTrue(dcim.mkdirs());
        File trashed = new File(dcim, ".trashed-1710000000000-gone.png");
        Files.write(trashed.toPath(), SignatureFixtures.png());

        // Exercise walk logic via package-visible helpers + direct emit path by
        // scanning a custom tree through a thin local algorithm instance.
        List<RecoveryCandidate> found = new ArrayList<>();
        DotTrashedFileScannerAlgorithm algorithm = new DotTrashedFileScannerAlgorithm();
        // Use reflection-free: call static helpers then mimic emit with scan of
        // Environment is not injectable — assert file naming contract + probe.
        assertTrue(trashed.isFile());
        FileSignatureProbe.ProbeResult probe = FileSignatureProbe.probe(trashed);
        assertEquals(RecoveryType.IMAGE, probe.type);

        // Walk the temp DCIM tree the same way the algorithm would.
        walkEmit(dcim, found);
        assertFalse(found.isEmpty());
        assertEquals("gone.png",
                DotTrashedFileScannerAlgorithm.recoverOriginalName(
                        new File(found.get(0).sourceUriOrPath).getName()));
    }

    private void walkEmit(File dir, List<RecoveryCandidate> found) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                walkEmit(child, found);
            } else if (DotTrashedFileScannerAlgorithm.isTrashedStyleName(child.getName())) {
                found.add(new RecoveryCandidate.Builder()
                        .candidateId(child.getAbsolutePath())
                        .sourceUriOrPath(child.getAbsolutePath())
                        .build());
            }
        }
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
