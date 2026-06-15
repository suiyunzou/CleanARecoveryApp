package com.example.cleanrecovery.experiment.cache;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

final class CacheProfileFileProbe {
    static final long MAX_PROBE_BYTES = 10L * 1024L * 1024L;
    /** Smallest valid JPEG SOI … EOI marker pair in test fixtures. */
    static final long MIN_PROBE_BYTES = 4L;

    static final class Result {
        final String sha256;
        final String mimeDetected;
        final String decodeStatus;

        Result(String sha256, String mimeDetected, String decodeStatus) {
            this.sha256 = sha256;
            this.mimeDetected = mimeDetected;
            this.decodeStatus = decodeStatus;
        }

        static Result empty() {
            return new Result("", "", "EMPTY");
        }

        boolean hasImageSignature() {
            return !mimeDetected.isEmpty();
        }
    }

    private CacheProfileFileProbe() {
    }

    static Result probe(File file) {
        if (file == null || !file.isFile() || !file.canRead()) {
            return Result.empty();
        }
        long length = file.length();
        if (length < MIN_PROBE_BYTES || length > MAX_PROBE_BYTES) {
            return Result.empty();
        }
        byte[] header = new byte[16];
        int headerRead;
        try (FileInputStream inputStream = new FileInputStream(file)) {
            headerRead = inputStream.read(header);
            if (headerRead < 2) {
                return Result.empty();
            }
            String mime = sniffMime(header, headerRead);
            if (mime.isEmpty()) {
                return Result.empty();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(header, 0, headerRead);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return new Result(toHex(digest.digest()), mime, "SIGNATURE_OK");
        } catch (IOException | NoSuchAlgorithmException exception) {
            return Result.empty();
        }
    }

    private static String sniffMime(byte[] header, int length) {
        if (length >= 3
                && (header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (length >= 8
                && header[0] == (byte) 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47) {
            return "image/png";
        }
        if (length >= 12
                && header[0] == 0x52
                && header[1] == 0x49
                && header[2] == 0x46
                && header[3] == 0x46
                && header[8] == 0x57
                && header[9] == 0x45
                && header[10] == 0x42
                && header[11] == 0x50) {
            return "image/webp";
        }
        if (length >= 6
                && header[0] == 0x47
                && header[1] == 0x49
                && header[2] == 0x46
                && header[3] == 0x38) {
            return "image/gif";
        }
        return "";
    }

    private static String toHex(byte[] hash) {
        StringBuilder builder = new StringBuilder(hash.length * 2);
        for (byte value : hash) {
            builder.append(String.format(Locale.US, "%02x", value));
        }
        return builder.toString();
    }
}
