package com.example.cleanrecovery.experiment.mediastore;

import android.net.Uri;
import android.provider.MediaStore;

public final class MediaStoreQuerySpec {
    public final Uri collectionUri;
    public final String[] projection;
    public final String selection;
    public final String[] selectionArgs;
    public final String sortOrder;
    public final String volumeName;
    public final QueryMode queryMode;

    public enum QueryMode {
        VISIBLE,
        TRASHED,
        PENDING,
        STALE
    }

    private MediaStoreQuerySpec(Builder builder) {
        collectionUri = builder.collectionUri;
        projection = builder.projection;
        selection = builder.selection;
        selectionArgs = builder.selectionArgs;
        sortOrder = builder.sortOrder;
        volumeName = builder.volumeName;
        queryMode = builder.queryMode;
    }

    public static MediaStoreQuerySpec visibleImages(String volumeName) {
        return baseBuilder(volumeName, QueryMode.VISIBLE)
                .collectionUri(MediaStore.Images.Media.getContentUri(volumeName))
                .selection(null)
                .build();
    }

    public static MediaStoreQuerySpec trashedImages(String volumeName) {
        return baseBuilder(volumeName, QueryMode.TRASHED)
                .collectionUri(MediaStore.Images.Media.getContentUri(volumeName))
                .selection(MediaStore.MediaColumns.IS_TRASHED + "=?")
                .selectionArgs(new String[]{"1"})
                .build();
    }

    public static MediaStoreQuerySpec pendingImages(String volumeName) {
        return baseBuilder(volumeName, QueryMode.PENDING)
                .collectionUri(MediaStore.Images.Media.getContentUri(volumeName))
                .selection(MediaStore.MediaColumns.IS_PENDING + "=?")
                .selectionArgs(new String[]{"1"})
                .build();
    }

    private static Builder baseBuilder(String volumeName, QueryMode mode) {
        return new Builder()
                .volumeName(volumeName)
                .queryMode(mode)
                .projection(new String[]{
                        MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.MIME_TYPE,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.Images.Media.DATE_TAKEN,
                        MediaStore.MediaColumns.IS_TRASHED,
                        MediaStore.MediaColumns.DATE_EXPIRES,
                        MediaStore.MediaColumns.IS_PENDING
                })
                .sortOrder(MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
    }

    public static final class Builder {
        private Uri collectionUri;
        private String[] projection;
        private String selection;
        private String[] selectionArgs;
        private String sortOrder;
        private String volumeName = MediaStore.VOLUME_EXTERNAL;
        private QueryMode queryMode = QueryMode.VISIBLE;

        public Builder collectionUri(Uri value) {
            collectionUri = value;
            return this;
        }

        public Builder projection(String[] value) {
            projection = value;
            return this;
        }

        public Builder selection(String value) {
            selection = value;
            return this;
        }

        public Builder selectionArgs(String[] value) {
            selectionArgs = value;
            return this;
        }

        public Builder sortOrder(String value) {
            sortOrder = value;
            return this;
        }

        public Builder volumeName(String value) {
            volumeName = value;
            return this;
        }

        public Builder queryMode(QueryMode value) {
            queryMode = value;
            return this;
        }

        public MediaStoreQuerySpec build() {
            return new MediaStoreQuerySpec(this);
        }
    }
}
