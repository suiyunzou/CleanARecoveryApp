package com.example.cleanrecovery;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ThumbnailLoader {
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int TARGET_PX = 256;

    /** 进程级单例缓存，避免每次 Activity 重建都重建缓存。 */
    private static volatile RecoveryCache sharedCache;

    private ThumbnailLoader() {
    }

    private static RecoveryCache cache() {
        RecoveryCache local = sharedCache;
        if (local == null) {
            synchronized (ThumbnailLoader.class) {
                local = sharedCache;
                if (local == null) {
                    local = new RecoveryCache(PathManager.thumbnailCacheDir());
                    local.trim();
                    sharedCache = local;
                }
            }
        }
        return local;
    }

    public static void loadInto(ImageView target, RecoveryItem item, int placeholderResId) {
        target.setTag(item.path);
        target.setImageResource(placeholderResId);
        target.setScaleType(ImageView.ScaleType.CENTER_CROP);

        // 1. 先尝试同步命中缓存，避免线程切换开销
        String cacheKey = RecoveryCache.buildKey(item.path, item.size, item.modifiedAt);
        Bitmap cached = cache().get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            Object tag = target.getTag();
            if (tag != null && item.path.equals(tag)) {
                target.setImageBitmap(cached);
            }
            return;
        }

        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bitmap = decodeThumbnail(item);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        Object tag = target.getTag();
                        if (tag == null || !item.path.equals(tag)) {
                            return;
                        }
                        if (bitmap != null) {
                            target.setImageBitmap(bitmap);
                            // 写入缓存供下次命中
                            cache().put(cacheKey, bitmap);
                        } else {
                            target.setImageResource(placeholderResId);
                        }
                    }
                });
            }
        });
    }

    /** 主动清除指定项的缩略图缓存。 */
    public static void invalidate(String path, long size, long modifiedAt) {
        String key = RecoveryCache.buildKey(path, size, modifiedAt);
        cache().remove(key);
    }

    /** 清空全部缩略图缓存。 */
    public static void clearCache() {
        cache().clear();
    }

    private static Bitmap decodeThumbnail(RecoveryItem item) {
        File file = item.asFile();
        if (!file.exists() || !file.canRead()) {
            return null;
        }
        if (item.type == RecoveryType.IMAGE) {
            return decodeImageSample(file);
        }
        if (item.type == RecoveryType.VIDEO) {
            return decodeVideoFrame(file);
        }
        return null;
    }

    private static Bitmap decodeImageSample(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        BitmapFactory.Options sample = new BitmapFactory.Options();
        sample.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, TARGET_PX);
        sample.inPreferredConfig = Bitmap.Config.RGB_565;
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath(), sample);
        } catch (OutOfMemoryError error) {
            return null;
        }
    }

    private static Bitmap decodeVideoFrame(File file) {
        try {
            Bitmap thumbnail = ThumbnailUtils.createVideoThumbnail(
                    file.getAbsolutePath(),
                    MediaStore.Video.Thumbnails.MINI_KIND
            );
            if (thumbnail != null) {
                return thumbnail;
            }
        } catch (Exception ignored) {
            // Fall through to retriever.
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (RuntimeException exception) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Ignore cleanup failures.
            }
        }
    }

    private static int computeSampleSize(int width, int height, int target) {
        int max = Math.max(width, height);
        int sample = 1;
        while (max / sample > target * 2) {
            sample *= 2;
        }
        return sample;
    }
}
