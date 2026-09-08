package com.example.cleanrecovery.experiment.mediastore;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Cheap, honest data check for MediaStore rows: does real backing data exist and
 * can this app read it? A row that fails this probe is a dead index (metadata
 * without bytes) and must never be offered as recoverable.
 *
 * <p>Fast path checks the {@code _data} file on disk; when that is missing or
 * unreadable it falls back to opening a bounded stream through the resolver.
 */
public final class MediaStoreExistenceProbe {

    public static final class ProbeResult {
        public final boolean readable;
        public final String status;
        public final String errorCode;
        public final long elapsedMs;

        public ProbeResult(boolean readable, String status, String errorCode, long elapsedMs) {
            this.readable = readable;
            this.status = status;
            this.errorCode = errorCode;
            this.elapsedMs = elapsedMs;
        }
    }

    public static final String STATUS_PATH_OK = "PATH_OK";
    public static final String STATUS_STREAM_OK = "STREAM_OK";
    public static final String STATUS_NO_DATA = "NO_DATA";

    public ProbeResult probe(Context context, Uri uri, String dataPath) {
        long started = System.currentTimeMillis();
        if (dataPath != null && !dataPath.isEmpty()) {
            File file = new File(dataPath);
            if (file.exists() && file.canRead() && file.length() > 1L) {
                return done(true, STATUS_PATH_OK, "", started);
            }
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return done(false, STATUS_NO_DATA, "open_null", started);
            }
            byte[] prefix = new byte[16];
            int read = input.read(prefix);
            if (read <= 2) {
                return done(false, STATUS_NO_DATA, "empty_stream", started);
            }
            return done(true, STATUS_STREAM_OK, "", started);
        } catch (IOException | SecurityException | IllegalStateException exception) {
            return done(false, STATUS_NO_DATA, exception.getClass().getSimpleName(), started);
        }
    }

    private static ProbeResult done(boolean readable, String status, String errorCode, long started) {
        return new ProbeResult(readable, status, errorCode, System.currentTimeMillis() - started);
    }
}
