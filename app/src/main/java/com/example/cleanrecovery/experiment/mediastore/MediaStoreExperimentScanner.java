package com.example.cleanrecovery.experiment.mediastore;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.provider.MediaStore;

import com.example.cleanrecovery.experiment.RecoveryCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * Queries MediaStore for rows that represent <em>deleted or in-flight</em> data:
 * trashed items (still on disk until the trash is emptied) and pending items.
 *
 * <p>Deliberately does NOT query plain VISIBLE rows: every such row is either an
 * existing file already covered by the file-tree walk, or a dead index whose
 * bytes are gone — listing those as "recoverable" is exactly the misleading
 * behaviour this scanner was reworked to remove. Every row that is emitted is
 * probe-checked for real backing data; unreadable rows are labelled
 * {@code METADATA_ONLY} so downstream code can mark them as non-recoverable.
 */
public final class MediaStoreExperimentScanner {
    public interface Callback {
        boolean isCancelled();
        void onCandidate(RecoveryCandidate candidate);
        void onProgress(String message);
    }

    private final Context context;
    private final MediaStoreExistenceProbe probe;

    public MediaStoreExperimentScanner(Context context) {
        this.context = context.getApplicationContext();
        this.probe = new MediaStoreExistenceProbe();
    }

    public List<RecoveryCandidate> scan(Callback callback) {
        ArrayList<RecoveryCandidate> results = new ArrayList<>();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return results;
        }
        for (String volume : MediaStore.getExternalVolumeNames(context)) {
            if (callback != null && callback.isCancelled()) break;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                results.addAll(scanVolume(volume, MediaStoreQuerySpec.trashedImages(volume), false, callback));
                results.addAll(scanVolume(volume, MediaStoreQuerySpec.trashedVideos(volume), false, callback));
                results.addAll(scanVolume(volume, MediaStoreQuerySpec.trashedAudio(volume), false, callback));
                results.addAll(scanVolume(volume, MediaStoreQuerySpec.trashedFiles(volume), true, callback));
            }
            results.addAll(scanVolume(volume, MediaStoreQuerySpec.pendingImages(volume), false, callback));
            results.addAll(scanVolume(volume, MediaStoreQuerySpec.pendingVideos(volume), false, callback));
            results.addAll(scanVolume(volume, MediaStoreQuerySpec.pendingAudio(volume), false, callback));
            results.addAll(scanVolume(volume, MediaStoreQuerySpec.pendingFiles(volume), true, callback));
        }
        return results;
    }

    private static final int PROGRESS_INTERVAL = 25;

    private List<RecoveryCandidate> scanVolume(
            String volumeName,
            MediaStoreQuerySpec spec,
            boolean filesCollection,
            Callback callback
    ) {
        ArrayList<RecoveryCandidate> results = new ArrayList<>();
        try (Cursor cursor = MediaStoreQueryExecutor.query(context.getContentResolver(), spec)) {
            if (cursor == null) return results;
            int dataColumn = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            int row = 0;
            while (cursor.moveToNext()) {
                if (callback != null && callback.isCancelled()) break;
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                android.net.Uri contentUri = android.content.ContentUris.withAppendedId(spec.collectionUri, id);
                String dataPath = dataColumn >= 0 ? cursor.getString(dataColumn) : null;
                MediaStoreExistenceProbe.ProbeResult probeResult = probe.probe(context, contentUri, dataPath);
                RecoveryCandidate candidate = MediaStoreCandidateMapper.fromCursor(
                        cursor, spec.collectionUri, spec.queryMode, volumeName, filesCollection,
                        dataPath, probeResult);
                results.add(candidate);
                if (callback != null) {
                    callback.onCandidate(candidate);
                    row++;
                    if (row % PROGRESS_INTERVAL == 0) {
                        callback.onProgress(spec.queryMode + " " + row);
                    }
                }
            }
        }
        return results;
    }
}
