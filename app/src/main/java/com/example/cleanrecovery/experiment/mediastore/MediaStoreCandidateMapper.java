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
            MediaStoreReadabilityProbe.ProbeResult probeResult
    ) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
        String displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME));
        String relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH));
        String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE));
        long size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE));
        Uri contentUri = ContentUris.withAppendedId(collectionUri, id);

        CandidateSourceKind sourceKind = sourceKindFor(queryMode);
        CandidateLabel label = labelFor(queryMode, probeResult);

        return new RecoveryCandidate.Builder()
                .candidateId(UUID.randomUUID().toString())
                .sourceKind(sourceKind)
                .sourceUriOrPath(contentUri.toString())
                .extractionMethod("mediastore_query:" + queryMode.name().toLowerCase())
                .originalContainer(volumeName + ":" + relativePath)
                .byteLength(size)
                .mimeDetected(mimeType == null ? "" : mimeType)
                .decodeStatus(probeResult.readable ? probeResult.decodeStatus : "UNREADABLE")
                .width(probeResult.width)
                .height(probeResult.height)
                .sha256(probeResult.sha256)
                .readBytes(probeResult.readBytes)
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
            MediaStoreReadabilityProbe.ProbeResult probeResult
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
}
