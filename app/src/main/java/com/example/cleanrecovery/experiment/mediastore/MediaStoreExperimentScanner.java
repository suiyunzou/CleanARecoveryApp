package com.example.cleanrecovery.experiment.mediastore;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import com.example.cleanrecovery.experiment.RecoveryCandidate;

import java.util.ArrayList;
import java.util.List;

public final class MediaStoreExperimentScanner {
    public interface Callback {
        boolean isCancelled();
        void onCandidate(RecoveryCandidate candidate);
        void onProgress(String message);
    }

    private final Context context;
    private final MediaStoreReadabilityProbe probe = new MediaStoreReadabilityProbe();

    public MediaStoreExperimentScanner(Context context) {
        this.context = context.getApplicationContext();
    }

    public List<RecoveryCandidate> scan(Callback callback) {
        ArrayList<RecoveryCandidate> results = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return results;
        }
        for (String volume : MediaStore.getExternalVolumeNames(context)) {
            if (callback != null && callback.isCancelled()) {
                break;
            }
            results.addAll(scanVolume(volume, MediaStoreQuerySpec.visibleImages(volume), callback));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                results.addAll(scanVolume(volume, MediaStoreQuerySpec.trashedImages(volume), callback));
            }
            results.addAll(scanVolume(volume, MediaStoreQuerySpec.pendingImages(volume), callback));
        }
        return results;
    }

    private List<RecoveryCandidate> scanVolume(
            String volumeName,
            MediaStoreQuerySpec spec,
            Callback callback
    ) {
        ArrayList<RecoveryCandidate> results = new ArrayList<>();
        try (Cursor cursor = context.getContentResolver().query(
                spec.collectionUri,
                spec.projection,
                spec.selection,
                spec.selectionArgs,
                spec.sortOrder
        )) {
            if (cursor == null) {
                return results;
            }
            while (cursor.moveToNext()) {
                if (callback != null && callback.isCancelled()) {
                    break;
                }
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                String mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE));
                android.net.Uri contentUri = android.content.ContentUris.withAppendedId(spec.collectionUri, id);
                MediaStoreReadabilityProbe.ProbeResult probeResult = probe.probe(context, contentUri, mimeType);
                RecoveryCandidate candidate = MediaStoreCandidateMapper.fromCursor(
                        cursor,
                        spec.collectionUri,
                        spec.queryMode,
                        volumeName,
                        probeResult
                );
                results.add(candidate);
                if (callback != null) {
                    callback.onCandidate(candidate);
                }
            }
        }
        return results;
    }
}
