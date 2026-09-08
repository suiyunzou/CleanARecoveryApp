package com.example.cleanrecovery.experiment.mediastore;

import android.content.ContentResolver;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;

/**
 * Runs MediaStore queries with the correct API-30+ trash matching semantics.
 * Without {@link MediaStore#QUERY_ARG_MATCH_TRASHED}, trashed rows are filtered
 * out even when {@code IS_TRASHED=1} appears in the SQL selection.
 */
public final class MediaStoreQueryExecutor {
    private MediaStoreQueryExecutor() {
    }

    public static Cursor query(ContentResolver resolver, MediaStoreQuerySpec spec) {
        if (resolver == null || spec == null || spec.collectionUri == null) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && spec.queryMode == MediaStoreQuerySpec.QueryMode.TRASHED) {
            Bundle extras = new Bundle();
            extras.putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY);
            if (spec.selection != null) {
                extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, spec.selection);
                extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, spec.selectionArgs);
            }
            if (spec.sortOrder != null) {
                extras.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, spec.sortOrder);
            }
            return resolver.query(spec.collectionUri, spec.projection, extras, null);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && spec.queryMode == MediaStoreQuerySpec.QueryMode.PENDING) {
            // Default matching only exposes the caller's own pending rows; MATCH_ONLY
            // is what actually lists pending items from other apps.
            Bundle extras = new Bundle();
            extras.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY);
            if (spec.selection != null) {
                extras.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, spec.selection);
                extras.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, spec.selectionArgs);
            }
            if (spec.sortOrder != null) {
                extras.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, spec.sortOrder);
            }
            return resolver.query(spec.collectionUri, spec.projection, extras, null);
        }
        return resolver.query(
                spec.collectionUri,
                spec.projection,
                spec.selection,
                spec.selectionArgs,
                spec.sortOrder);
    }
}
