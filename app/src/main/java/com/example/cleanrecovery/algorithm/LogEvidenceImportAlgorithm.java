package com.example.cleanrecovery.algorithm;

import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.experiment.CandidateLabel;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.experiment.ResultGrade;
import com.example.cleanrecovery.recovery.RecoveryType;

import java.io.File;

/**
 * Surfaces "evidence" that a file once existed: MediaStore still has an index
 * row whose {@code _data} path no longer resolves on disk (a stale record).
 * These are path/URI clues only — no bytes are recovered — so candidates are
 * labelled {@link CandidateSourceKind#LOG_EVIDENCE_ONLY} with a
 * {@code METADATA_ONLY} grade. Registered experimental-only to keep the default
 * result set limited to recoverable items.
 */
public final class LogEvidenceImportAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "log_evidence_import";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_log_evidence_import;
    }

    @Override
    public RecoveryType[] supportedTypes() {
        return RecoveryType.scannableValues();
    }

    @Override
    public AlgorithmAvailability availability(AlgorithmContext context) {
        return AlgorithmAvailability.runnable();
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        if (context == null || context.context == null) {
            return;
        }
        Uri collection = collectionFor(context.type);
        if (collection == null) {
            return;
        }
        String[] projection = {
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DISPLAY_NAME,
        };
        int processed = 0;
        try (Cursor cursor = context.context.getContentResolver()
                .query(collection, projection, null, null, null)) {
            if (cursor == null) {
                return;
            }
            int idCol = cursor.getColumnIndex(MediaStore.MediaColumns._ID);
            int dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            int sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            while (cursor.moveToNext()) {
                if (callback.isCancelled()) {
                    return;
                }
                String data = dataCol >= 0 ? cursor.getString(dataCol) : null;
                if (!isStaleRecord(data)) {
                    continue;
                }
                processed++;
                if (processed % 25 == 0) {
                    callback.onProgress(processed, data);
                }
                long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : 0L;
                String mime = mimeCol >= 0 ? cursor.getString(mimeCol) : "";
                Uri rowUri = idCol >= 0
                        ? ContentUris.withAppendedId(collection, cursor.getLong(idCol))
                        : collection;
                callback.onCandidate(buildEvidence(data, rowUri.toString(), size, mime));
            }
        } catch (RuntimeException ignored) {
            // MediaStore query may throw on some OEMs / scoped-storage states;
            // evidence import is best-effort.
        }
    }

    /** Test seam: a record is stale when it names a path that no longer exists. */
    static boolean isStaleRecord(String dataPath) {
        if (dataPath == null || dataPath.isEmpty()) {
            return false;
        }
        return !new File(dataPath).exists();
    }

    static RecoveryCandidate buildEvidence(String dataPath, String rowUri, long size, String mime) {
        return new RecoveryCandidate.Builder()
                .candidateId(rowUri)
                .sourceKind(CandidateSourceKind.LOG_EVIDENCE_ONLY)
                .sourceUriOrPath(dataPath != null ? dataPath : rowUri)
                .extractionMethod("log_evidence_import")
                .byteLength(size)
                .mimeDetected(mime != null ? mime : "")
                .decodeStatus("EVIDENCE_ONLY")
                .errorCode("FILE_MISSING")
                .label(CandidateLabel.METADATA_ONLY)
                .grade(ResultGrade.METADATA_ONLY)
                .build();
    }

    private static Uri collectionFor(RecoveryType type) {
        if (type == null) {
            return null;
        }
        switch (type) {
            case IMAGE:
                return MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            case VIDEO:
                return MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            case AUDIO:
                return MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            case DOCUMENT:
                return MediaStore.Files.getContentUri("external");
            default:
                return null;
        }
    }
}
