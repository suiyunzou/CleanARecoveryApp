package com.example.cleanrecovery.experiment.jpeg;

import com.example.cleanrecovery.experiment.FakeCorpus;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class JpegBlobCarverTest {
    @Test
    public void structuredCarverFindsEmbeddedJpeg() {
        byte[] blob = new byte[]{
                0x10, 0x20,
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xD9
        };
        JpegBlobCarver carver = new JpegBlobCarver();
        List<RecoveryCandidate> structured = carver.carveBytes("blob.bin", blob, null);

        assertEquals(1, structured.size());
        assertEquals(2L, structured.get(0).extractionOffsetStart);
        assertEquals("structured_jpeg_parser", structured.get(0).extractionMethod);
    }

    @Test
    public void headerFooterBaselineCanOverSegment() {
        byte[] blob = new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                0x00, 0x00,
                (byte) 0xFF, (byte) 0xD9
        };
        JpegBlobCarver carver = new JpegBlobCarver();
        List<RecoveryCandidate> baseline = carver.carveHeaderFooterBaseline("blob.bin", blob);

        assertTrue(baseline.size() >= 1);
        assertEquals("header_footer_baseline", baseline.get(0).extractionMethod);
    }

    @Test
    public void skipsInvalidHeaderNoiseWithoutPartialFlag() {
        byte[] blob = FakeCorpus.corruptedJpegBytes();
        JpegBlobCarver carver = new JpegBlobCarver();
        List<RecoveryCandidate> structured = carver.carveBytes("corrupt.bin", blob, null);

        assertTrue(structured.isEmpty());
    }
}
