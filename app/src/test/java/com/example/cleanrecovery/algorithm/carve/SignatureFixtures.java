package com.example.cleanrecovery.algorithm.carve;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Synthetic file payloads for signature-carve hard-acceptance tests. */
public final class SignatureFixtures {
    private SignatureFixtures() {
    }

    /** Minimal JPEG: SOI + EOI. */
    public static byte[] jpeg() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
    }

    /** Minimal valid PNG (1x1 IHDR + IEND). */
    public static byte[] png() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
        }, 0, 8);
        byte[] ihdrData = new byte[] {
                0, 0, 0, 1, // width
                0, 0, 0, 1, // height
                8, // bit depth
                2, // color type RGB
                0, 0, 0 // compression, filter, interlace
        };
        writePngChunk(out, "IHDR", ihdrData);
        writePngChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** Minimal MP4: ftyp + free boxes. */
    public static byte[] mp4() {
        byte[] data = new byte[32];
        // ftyp size 24
        data[0] = 0;
        data[1] = 0;
        data[2] = 0;
        data[3] = 24;
        data[4] = 'f';
        data[5] = 't';
        data[6] = 'y';
        data[7] = 'p';
        data[8] = 'i';
        data[9] = 's';
        data[10] = 'o';
        data[11] = 'm';
        data[16] = 'i';
        data[17] = 's';
        data[18] = 'o';
        data[19] = 'm';
        // free size 8
        data[24] = 0;
        data[25] = 0;
        data[26] = 0;
        data[27] = 8;
        data[28] = 'f';
        data[29] = 'r';
        data[30] = 'e';
        data[31] = 'e';
        return data;
    }

    /** Minimal FLAC: fLaC + last STREAMINFO metadata block (zeroed). */
    public static byte[] flac() {
        byte[] data = new byte[4 + 4 + 34];
        data[0] = 'f';
        data[1] = 'L';
        data[2] = 'a';
        data[3] = 'C';
        data[4] = (byte) 0x80; // last block, type 0
        data[5] = 0;
        data[6] = 0;
        data[7] = 34; // length
        return data;
    }

    /** Minimal PDF with %%EOF. */
    public static byte[] pdf() {
        return ("%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\nstartxref\n0\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** Minimal DOCX-like ZIP containing word/document.xml + EOCD. */
    public static byte[] docx() throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.US_ASCII));
            zip.closeEntry();
        }
        return raw.toByteArray();
    }

    /**
     * Build a synthetic "deleted" partition image: filler, then payload in a gap
     * (simulating unallocated sectors after unlink).
     */
    public static byte[] deletedLayout(byte[] payload, int gapOffset) {
        int size = Math.max(gapOffset + payload.length + 64, 4096);
        byte[] partition = new byte[size];
        for (int i = 0; i < partition.length; i++) {
            partition[i] = (byte) 0xA5;
        }
        System.arraycopy(payload, 0, partition, gapOffset, payload.length);
        return partition;
    }

    private static void writePngChunk(ByteArrayOutputStream out, String type, byte[] data) {
        try {
            ByteBuffer len = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(data.length);
            out.write(len.array());
            byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
            out.write(typeBytes);
            out.write(data);
            CRC32 crc = new CRC32();
            crc.update(typeBytes);
            crc.update(data);
            ByteBuffer crcBuf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt((int) crc.getValue());
            out.write(crcBuf.array());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
