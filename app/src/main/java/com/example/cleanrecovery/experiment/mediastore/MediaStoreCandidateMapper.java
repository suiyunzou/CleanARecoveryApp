package com.example.cleanrecovery.experiment.mediastore;

import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;

import com.example.cleanrecovery.experiment.CandidateLabel;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import java.util.UUID;

public final class MediaStoreCandidateMapper {
    private MediaStoreCandidateMapper() {
    }

    public static RecoveryCandidate fromCursor(
            Cursor cursor,
            Uri collectionUri,
            MediaStoreQuerySpec.QueryMode queryMode,
            String volumeName,
            boolean filesCollection,
            String dataPath,
            MediaStoreExistenceProbe.ProbeResult probeResult
    ) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
        String displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME));
        String relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH));
        String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE));
        long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE));
        long modifiedAtMs = secondsToMillis(cursor, MediaStore.MediaColumns.DATE_MODIFIED);
        long expiresAtMs = secondsToMillis(cursor, MediaStore.MediaColumns.DATE_EXPIRES);
        Uri contentUri = ContentUris.withAppendedId(collectionUri, id);

        CandidateSourceKind sourceKind = sourceKindFor(queryMode);
        CandidateLabel label = labelFor(queryMode, probeResult);

        return new RecoveryCandidate.Builder()
                .candidateId(UUID.randomUUID().toString())
                .sourceKind(sourceKind)
                .sourceUriOrPath(contentUri.toString())
                .dataPath(dataPath == null ? "" : dataPath)
                // The _files suffix marks rows from the MediaStore.Files collection:
                // unknown-mime rows must only surface under DOCUMENT scans.
                .extractionMethod("mediastore_query:" + queryMode.name().toLowerCase()
                        + (filesCollection ? "_files" : ""))
                // relativePath alone has no file name for trashed rows (their
                // display name carries the .trashed-<ts>- prefix) — append it so
                // downstream naming can recover the original file name.
                .originalContainer(volumeName + ":" + (relativePath == null ? "" : relativePath)
                        + (displayName == null ? "" : displayName))
                .byteLength(size)
                .mimeDetected(mimeType == null ? "" : mimeType)
                .decodeStatus(probeResult.readable ? probeResult.status : "UNREADABLE")
                .modifiedAt(modifiedAtMs)
                .expiresAt(expiresAtMs)
                .readBytes(probeResult.readable && probeResult.status != null
                        && probeResult.status.equals(MediaStoreExistenceProbe.STATUS_STREAM_OK) ? 16L : 0L)
                .elapsedMs(probeResult.elapsedMs)
                .errorCode(probeResult.errorCode)
                .label(label)
                .build();
    }

    private static CandidateSourceKind sourceKindFor(MediaStoreQuerySpec.QueryMode queryMode) {
        if (queryMode == MediaStoreQuerySpec.QueryMode.TRASHED) {
            return CandidateSourceKind.MEDIASTORE_TRASH;
        }
        if (queryMode == MediaStoreQuerySpec.QueryMode.PENDING) {
            return CandidateSourceKind.MEDIASTORE_PENDING;
        }
        if (queryMode == MediaStoreQuerySpec.QueryMode.STALE) {
            return CandidateSourceKind.MEDIASTORE_STALE_RECORD;
        }
        return CandidateSourceKind.VISIBLE_SHARED_FILE;
    }

    private static CandidateLabel labelFor(
            MediaStoreQuerySpec.QueryMode queryMode,
            MediaStoreExistenceProbe.ProbeResult probeResult
    ) {
        if (!probeResult.readable) {
            return CandidateLabel.METADATA_ONLY;
        }
        if (queryMode == MediaStoreQuerySpec.QueryMode.TRASHED) {
            return CandidateLabel.TRASH_OBJECT;
        }
        if (queryMode == MediaStoreQuerySpec.QueryMode.VISIBLE) {
            return CandidateLabel.ORIGINAL_VISIBLE_FILE;
        }
        return CandidateLabel.UNVERIFIED;
    }

    private static long secondsToMillis(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0) {
            return 0L;
        }
        long seconds = cursor.getLong(index);
        return seconds > 0L ? seconds * 1000L : 0L;
    }
}
