package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.scan.ScanLimits;

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
        ProbeResult result = probe(prefix);
        if (result != null && MIME_ZIP.equals(result.mimeDetected)) {
            // OOXML part names (word/, xl/, ppt/) sit past the 32-byte prefix,
            // so re-read a small window to tell docx/xlsx/pptx from a plain zip.
            byte[] window = readPrefix(file, ZIP_REFINE_BYTES);
            return new ProbeResult(RecoveryType.DOCUMENT, zipMime(window));
        }
        return result;
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
        if (matchesBmp(prefix)) {
            return new ProbeResult(RecoveryType.IMAGE, "image/bmp");
        }
        if (matchesHeif(prefix)) {
            return new ProbeResult(RecoveryType.IMAGE, "image/heif");
        }
        if (matchesPdf(prefix)) {
            return new ProbeResult(RecoveryType.DOCUMENT, "application/pdf");
        }
        if (matchesZip(prefix)) {
            // zipMime inspects whatever bytes we were handed; with only the
            // 32-byte prefix it stays generic, but a larger buffer (carving,
            // probe(File) refinement) can resolve docx/xlsx/pptx.
            return new ProbeResult(RecoveryType.DOCUMENT, zipMime(prefix));
        }
        if (matchesFtyp(prefix)) {
            return new ProbeResult(RecoveryType.VIDEO, "video/mp4");
        }
        if (matchesRiff(prefix)) {
            return new ProbeResult(RecoveryType.VIDEO, "video/avi");
        }
        if (matchesMkv(prefix)) {
            return new ProbeResult(RecoveryType.VIDEO, "video/x-matroska");
        }
        if (matchesOgg(prefix)) {
            return new ProbeResult(RecoveryType.AUDIO, "audio/ogg");
        }
        if (matchesFlac(prefix)) {
            return new ProbeResult(RecoveryType.AUDIO, "audio/flac");
        }
        if (matchesAmr(prefix)) {
            return new ProbeResult(RecoveryType.AUDIO, "audio/amr");
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

    static boolean matchesBmp(byte[] bytes) {
        return bytes.length >= 2
                && bytes[0] == 'B' && bytes[1] == 'M';
    }

    static boolean matchesHeif(byte[] bytes) {
        return bytes.length >= 12
                && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p'
                && bytes[8] == 'h' && bytes[9] == 'e' && bytes[10] == 'i' && bytes[11] == 'c';
    }

    static boolean matchesRiff(byte[] bytes) {
        return bytes.length >= 12
                && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'A' && bytes[9] == 'V' && bytes[10] == 'I' && bytes[11] == ' ';
    }

    static boolean matchesMkv(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 0x1A && bytes[1] == 0x45 && bytes[2] == (byte) 0xDF && bytes[3] == (byte) 0xA3;
    }

    static boolean matchesOgg(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S';
    }

    static boolean matchesFlac(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == 'f' && bytes[1] == 'L' && bytes[2] == 'a' && bytes[3] == 'C';
    }

    static boolean matchesAmr(byte[] bytes) {
        return bytes.length >= 6
                && bytes[0] == '#' && bytes[1] == '!' && bytes[2] == 'A' && bytes[3] == 'M'
                && bytes[4] == 'R' && bytes[5] == '\n';
    }

    static final String MIME_ZIP = "application/zip";
    static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    private static final int ZIP_REFINE_BYTES = 512;

    /**
     * Resolve a ZIP-signatured buffer to a specific OOXML mime when the
     * discriminating part name is visible, otherwise generic application/zip.
     */
    static String zipMime(byte[] bytes) {
        if (containsAscii(bytes, "word/")) {
            return MIME_DOCX;
        }
        if (containsAscii(bytes, "xl/")) {
            return MIME_XLSX;
        }
        if (containsAscii(bytes, "ppt/")) {
            return MIME_PPTX;
        }
        return MIME_ZIP;
    }

    static boolean containsAscii(byte[] haystack, String needle) {
        if (haystack == null || needle == null) {
            return false;
        }
        byte[] target = needle.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (target.length == 0 || haystack.length < target.length) {
            return false;
        }
        outer:
        for (int i = 0; i + target.length <= haystack.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (haystack[i + j] != target[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
