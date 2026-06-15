package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.RecoveryType;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class FileSignatureProbeTest {
    @Test
    public void detectsListedSignatures() {
        assertType(RecoveryType.IMAGE, "image/jpeg", (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00);
        assertType(RecoveryType.IMAGE, "image/png", (byte) 0x89, 0x50, 0x4E, 0x47);
        assertType(RecoveryType.IMAGE, "image/gif", 'G', 'I', 'F', '8', '7', 'a');
        assertType(RecoveryType.IMAGE, "image/webp",
                'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P');
        assertType(RecoveryType.DOCUMENT, "application/pdf", '%', 'P', 'D', 'F', '-');
        assertType(RecoveryType.DOCUMENT, "application/zip", 'P', 'K', 0x03, 0x04);
        assertType(RecoveryType.VIDEO, "video/mp4", 0, 0, 0, 0, 'f', 't', 'y', 'p');
    }

    @Test
    public void unknownBytesReturnNoType() {
        assertNull(FileSignatureProbe.probe(new byte[] {0x00, 0x11, 0x22, 0x33}));
    }

    private static void assertType(RecoveryType expectedType, String expectedMime, int... values) {
        byte[] prefix = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            prefix[i] = (byte) values[i];
        }
        FileSignatureProbe.ProbeResult result = FileSignatureProbe.probe(prefix);
        assertNotNull(result);
        assertEquals(expectedType, result.type);
        assertEquals(expectedMime, result.mimeDetected);
    }
}
