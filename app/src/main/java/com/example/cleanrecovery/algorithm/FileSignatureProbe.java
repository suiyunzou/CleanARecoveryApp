package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.RecoveryType;
import com.example.cleanrecovery.ScanLimits;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Bounded prefix signature probe for accessible shared-storage files.
 */
public final class FileSignatureProbe {
    public static final class ProbeResult {
        public final RecoveryType type;
        public final String mimeDetected;

        ProbeResult(RecoveryType type, String mimeDetected) {
            this.type = type;
            this.mimeDetected = mimeDetected;
        }
    }

    private FileSignatureProbe() {
    }

    public static ProbeResult probe(File file) throws IOException {
        if (file == null || !file.isFile() || !file.canRead()) {
            return null;
        }
        if (file.length() <= 0L || file.length() > ScanLimits.SIGNATURE_MAX_FILE_BYTES) {
            return null;
        }
        byte[] prefix = readPrefix(file, ScanLimits.SIGNATURE_PREFIX_BYTES);
        return probe(prefix);
    }

    public static ProbeResult probe(byte[] prefix) {
        if (prefix == null || prefix.length < 4) {
            return null;
        }
        if (matchesJpeg(prefix)) {
            return new ProbeResult(RecoveryType.IMAGE, "image/jpeg");
        }
        if (matchesPng(prefix)) {
            return new ProbeResult(RecoveryType.IMAGE, "image/png");
        }
        if (matchesGif(prefix)) {
            return new ProbeResult(RecoveryType.IMAGE, "image/gif");
        }
        if (matchesWebp(prefix)) {
            return new ProbeResult(RecoveryType.IMAGE, "image/webp");
        }
        if (matchesPdf(prefix)) {
            return new ProbeResult(RecoveryType.DOCUMENT, "application/pdf");
        }
        if (matchesZip(prefix)) {
            return new ProbeResult(RecoveryType.DOCUMENT, "application/zip");
        }
        if (matchesFtyp(prefix)) {
            return new ProbeResult(RecoveryType.VIDEO, "video/mp4");
        }
        return null;
    }

    static byte[] readPrefix(File file, int maxBytes) throws IOException {
        int toRead = (int) Math.min(file.length(), maxBytes);
        byte[] buffer = new byte[toRead];
        try (FileInputStream input = new FileInputStream(file)) {
            int read = input.read(buffer);
            if (read <= 0) {
                return new byte[0];
            }
            if (read < buffer.length) {
                return Arrays.copyOf(buffer, read);
            }
            return buffer;
        }
    }

    static boolean matchesJpeg(byte[] bytes) {
        return bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF;
    }

    static boolean matchesPng(byte[] bytes) {
        return bytes.length >= 4
                && (bytes[0] & 0xFF) == 0x89
                && bytes[1] == 0x50
                && bytes[2] == 0x4E
                && bytes[3] == 0x47;
    }

    static boolean matchesGif(byte[] bytes) {
        if (bytes.length < 6) {
            return false;
        }
        String header = new String(bytes, 0, 6, java.nio.charset.StandardCharsets.US_ASCII);
        return "GIF87a".equals(header) || "GIF89a".equals(header);
    }

    static boolean matchesWebp(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P';
    }

    static boolean matchesPdf(byte[] bytes) {
        return bytes.length >= 5
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F'
                && bytes[4] == '-';
    }

    static boolean matchesZip(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 'P' && bytes[1] == 'K'
                && bytes[2] == 0x03 && bytes[3] == 0x04;
    }

    static boolean matchesFtyp(byte[] bytes) {
        return bytes.length >= 8
                && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p';
    }
}
