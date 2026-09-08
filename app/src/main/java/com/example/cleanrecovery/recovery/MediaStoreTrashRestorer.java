package com.example.cleanrecovery.recovery;

import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.util.ArrayList;
import java.util.List;

/**
 * Restores system-trash items to their original location instead of copying
 * them out. MediaStore rows are restored through the official
 * {@link MediaStore#createTrashRequest} flow (a system confirmation dialog;
 * the provider un-trashes the row, which also restores its original name and
 * path). This is a real "undelete" for photos/videos still inside the system
 * gallery trash — the data never left the device.
 */
public final class MediaStoreTrashRestorer {
    private MediaStoreTrashRestorer() {
    }

    /** MediaStore trash rows this app can offer true restore for. */
    public static boolean isMediaStoreRestorable(RecoveryItem item) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && item.recoverable
                && item.sourceKind == RecoverySourceKind.MEDIASTORE_TRASH
                && item.path != null
                && item.path.startsWith("content://");
    }

    public static boolean anyMediaStoreRestorable(List<RecoveryItem> items) {
        if (items == null) {
            return false;
        }
        for (RecoveryItem item : items) {
            if (isMediaStoreRestorable(item)) {
                return true;
            }
        }
        return false;
    }

    public static List<Uri> restorableUris(List<RecoveryItem> items) {
        ArrayList<Uri> uris = new ArrayList<>();
        if (items != null) {
            for (RecoveryItem item : items) {
                if (isMediaStoreRestorable(item) && item.path != null) {
                    uris.add(Uri.parse(item.path));
                }
            }
        }
        return uris;
    }

    /**
     * Build the system "restore from trash" request. Returns null when the
     * platform call is unavailable (below R) or the provider rejects the
     * request; callers should then fall back to copying.
     */
    public static PendingIntent createRestoreRequest(Context context, List<RecoveryItem> items) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null;
        }
        List<Uri> uris = restorableUris(items);
        if (uris.isEmpty()) {
            return null;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            return MediaStore.createTrashRequest(
                    resolver,
                    new ArrayList<>(uris),
                    false);
        } catch (Exception exception) {
            android.util.Log.w("TrashRestorer", "createTrashRequest failed: " + exception.getMessage());
            return null;
        }
    }
}
