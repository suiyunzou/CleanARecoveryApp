package com.example.cleanrecovery.experiment.jpeg;

import com.example.cleanrecovery.experiment.RecoveryCandidate;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class JpegStructureParserTest {
    @Test
    public void parsesMinimalJpeg() {
        byte[] data = new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xD9
        };
        JpegStructureParser parser = new JpegStructureParser();
        JpegStructureParser.ParseResult result = parser.parse(data, 0);

        assertTrue(result.valid);
        assertEquals(4, result.endOffset);
    }

    @Test
    public void findsEmbeddedJpegInBlob() {
        byte[] data = new byte[]{
                0x00, 0x01,
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xD9,
                0x02
        };
        JpegStructureParser parser = new JpegStructureParser();

        assertEquals(List.of(2), parser.findSoiOffsets(data));
    }

    @Test
    public void rejectsInvalidSegmentLength() {
        byte[] data = new byte[]{
                (byte) 0xFF, (byte) 0xD8,
                (byte) 0xFF, (byte) 0xE0, 0x00, 0x05
        };
        JpegStructureParser parser = new JpegStructureParser();
        JpegStructureParser.ParseResult result = parser.parse(data, 0);

        assertFalse(result.valid);
    }
}
