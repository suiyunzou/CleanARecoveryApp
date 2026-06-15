package com.example.cleanrecovery.experiment.mediastore;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class MediaStoreReadabilityProbe {
    public static final class ProbeResult {
        public final boolean readable;
        public final String decodeStatus;
        public final int width;
        public final int height;
        public final String sha256;
        public final long readBytes;
        public final long elapsedMs;
        public final String errorCode;

        public ProbeResult(
                boolean readable,
                String decodeStatus,
                int width,
                int height,
                String sha256,
                long readBytes,
                long elapsedMs,
                String errorCode
        ) {
            this.readable = readable;
            this.decodeStatus = decodeStatus;
            this.width = width;
            this.height = height;
            this.sha256 = sha256;
            this.readBytes = readBytes;
            this.elapsedMs = elapsedMs;
            this.errorCode = errorCode;
        }
    }

    public ProbeResult probe(Context context, Uri uri, String mimeType) {
        long started = System.currentTimeMillis();
        try (InputStream inputStream = context.getContentResolver().openInputStream(uri)) {
            if (inputStream == null) {
                return unreadable("open_failed", started);
            }
            byte[] signature = readPrefix(inputStream, 16);
            if (signature.length < 2) {
                return unreadable("short_signature", started);
            }
            if (!mimeLooksValid(signature, mimeType)) {
                return unreadable("mime_mismatch", started);
            }
            try (InputStream boundsStream = context.getContentResolver().openInputStream(uri);
                 InputStream hashStream = context.getContentResolver().openInputStream(uri)) {
                if (boundsStream == null || hashStream == null) {
                    return unreadable("reopen_failed", started);
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(boundsStream, null, options);
                byte[] allBytes = readAllBytes(hashStream);
                return new ProbeResult(
                        options.outWidth > 0 && options.outHeight > 0,
                        options.outWidth > 0 ? "BOUNDS_OK" : "BOUNDS_FAILED",
                        options.outWidth,
                        options.outHeight,
                        sha256(allBytes),
                        allBytes.length,
                        System.currentTimeMillis() - started,
                        ""
                );
            }
        } catch (IOException exception) {
            return unreadable("io_error", started);
        }
    }

    private static ProbeResult unreadable(String errorCode, long started) {
        return new ProbeResult(false, "UNREADABLE", 0, 0, "", 0L, System.currentTimeMillis() - started, errorCode);
    }

    private static byte[] readPrefix(InputStream inputStream, int maxBytes) throws IOException {
        byte[] buffer = new byte[maxBytes];
        int read = inputStream.read(buffer);
        if (read <= 0) {
            return new byte[0];
        }
        byte[] result = new byte[read];
        System.arraycopy(buffer, 0, result, 0, read);
        return result;
    }

    private static boolean mimeLooksValid(byte[] signature, String mimeType) {
        if (mimeType == null || !mimeType.startsWith("image/")) {
            return true;
        }
        boolean jpeg = (signature[0] & 0xFF) == 0xFF && (signature[1] & 0xFF) == 0xD8;
        boolean png = signature.length >= 4
                && signature[0] == (byte) 0x89
                && signature[1] == 0x50
                && signature[2] == 0x4E
                && signature[3] == 0x47;
        if (mimeType.contains("jpeg") || mimeType.contains("jpg")) {
            return jpeg;
        }
        if (mimeType.contains("png")) {
            return png;
        }
        return true;
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        while ((read = inputStream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format(Locale.US, "%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }
}
