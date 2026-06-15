package com.example.cleanrecovery.experiment.cache;

import com.example.cleanrecovery.experiment.CandidateLabel;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.experiment.FakeCorpus;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CacheProfileScannerTest {
    @Test
    public void thumbnailJpegGetsDigestAndMime() throws Exception {
        File root = createTempDir("cache-scanner-thumb");
        File thumb = new File(root, "DCIM/.thumbnails/123");
        thumb.getParentFile().mkdirs();
        try (FileOutputStream output = new FileOutputStream(thumb)) {
            output.write(FakeCorpus.minimalJpegBytes());
        }

        CacheProfileScanner scanner = new CacheProfileScanner();
        List<RecoveryCandidate> results = scanner.scanFile(thumb, null);

        assertEquals(1, results.size());
        RecoveryCandidate candidate = results.get(0);
        assertEquals(CandidateLabel.THUMBNAIL, candidate.label);
        assertEquals("image/jpeg", candidate.mimeDetected);
        assertFalse(candidate.sha256.isEmpty());
        assertEquals("SIGNATURE_OK", candidate.decodeStatus);
    }

    @Test
    public void skipsNonImageUnderThumbnailPath() throws Exception {
        File root = createTempDir("cache-scanner-pdf");
        File pdf = new File(root, "DCIM/.thumbnails/report");
        pdf.getParentFile().mkdirs();
        try (FileOutputStream output = new FileOutputStream(pdf)) {
            output.write("%PDF-1.4 fake".getBytes());
        }

        CacheProfileScanner scanner = new CacheProfileScanner();
        List<RecoveryCandidate> results = scanner.scanFile(pdf, null);

        assertTrue(results.isEmpty());
    }

    private static File createTempDir(String prefix) {
        File dir = new File(System.getProperty("java.io.tmpdir"), prefix + "-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        dir.deleteOnExit();
        return dir;
    }
}
