package com.example.cleanrecovery.recovery;

import android.graphics.Bitmap;
import android.util.LruCache;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 双层缓存：内存 LruCache + 磁盘 LRU 缓存，带过期策略。
 *
 * 设计要点：
 *  - 内存层：基于 android.util.LruCache，按 bitmap 字节数限制（默认 16MB）。
 *  - 磁盘层：基于 LinkedHashMap 实现 LRU 顺序，按总字节数限制（默认 64MB）。
 *  - 过期策略：每个磁盘条目记录写入时间戳，超过 TTL 自动淘汰（默认 7 天）。
 *  - 缓存键：使用 SHA-1(path + size + modifiedAt) 避免非法字符与冲突。
 *  - 线程安全：内存层 LruCache 自带同步；磁盘层用 synchronized 保护。
 *  - 缓存更新：put 覆盖旧值；get 命中时若已过期则视为未命中并清理。
 *
 * 适用场景：缩略图、扫描结果快照等可重建数据。不缓存用户原始文件。
 */
public final class RecoveryCache {
    private static final String TAG = "RecoveryCache";

    /** 默认内存缓存上限：16MB。 */
    private static final int DEFAULT_MEMORY_BYTES = 16 * 1024 * 1024;
    /** 默认磁盘缓存上限：64MB。 */
    private static final long DEFAULT_DISK_BYTES = 64L * 1024 * 1024;
    /** 默认 TTL：7 天。 */
    private static final long DEFAULT_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000L;

    private final LruCache<String, Bitmap> memoryCache;
    private final File diskDir;
    private final long diskMaxBytes;
    private final long ttlMillis;
    private final Object diskLock = new Object();
    // 磁盘 LRU 顺序表：key -> DiskEntry（按访问顺序）
    private final LinkedHashMap<String, DiskEntry> diskIndex = new LinkedHashMap<>(16, 0.75f, true);
    private long diskTotalBytes = 0L;

    private static final class DiskEntry {
        final String key;
        final File file;
        final long size;
        long writtenAt;
        long lastAccessAt;

        DiskEntry(String key, File file, long size, long writtenAt) {
            this.key = key;
            this.file = file;
            this.size = size;
            this.writtenAt = writtenAt;
            this.lastAccessAt = writtenAt;
        }
    }

    public RecoveryCache(File diskDir) {
        this(diskDir, DEFAULT_MEMORY_BYTES, DEFAULT_DISK_BYTES, DEFAULT_TTL_MILLIS);
    }

    public RecoveryCache(File diskDir, int memoryBytes, long diskBytes, long ttlMillis) {
        this.diskDir = diskDir;
        this.diskMaxBytes = diskBytes;
        this.ttlMillis = ttlMillis;
        if (diskDir != null && !diskDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            diskDir.mkdirs();
        }
        this.memoryCache = new LruCache<String, Bitmap>(memoryBytes) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value == null ? 0 : value.getByteCount();
            }

            @Override
            protected void entryRemoved(boolean evicted, String key, Bitmap oldValue, Bitmap newValue) {
                // 内存层被驱逐时不删除磁盘层；磁盘层有自己的 LRU。
            }
        };
        loadDiskIndex();
    }

    // ============ 公共 API ============

    /** 生成缓存键：sha1(path|size|modifiedAt)。 */
    public static String buildKey(String path, long size, long modifiedAt) {
        String raw = path + "|" + size + "|" + modifiedAt;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-1 与 UTF-8 在所有 Android 上均可用，理论上不会到这里
            return Integer.toHexString(raw.hashCode());
        }
    }

    /** 优先查内存，未命中查磁盘；磁盘命中则回填内存。返回 null 表示未命中。 */
    public Bitmap get(String key) {
        if (key == null) return null;
        Bitmap mem = memoryCache.get(key);
        if (mem != null && !mem.isRecycled()) {
            return mem;
        }
        Bitmap disk = readDisk(key);
        if (disk != null) {
            memoryCache.put(key, disk);
        }
        return disk;
    }

    /** 同时写入内存与磁盘。 */
    public void put(String key, Bitmap bitmap) {
        if (key == null || bitmap == null || bitmap.isRecycled()) return;
        memoryCache.put(key, bitmap);
        writeDisk(key, bitmap);
    }

    /** 主动清除指定键的内存与磁盘缓存。 */
    public void remove(String key) {
        if (key == null) return;
        memoryCache.remove(key);
        removeDisk(key);
    }

    /** 清空全部缓存（内存 + 磁盘）。 */
    public void clear() {
        // 内存层
        memoryCache.evictAll();
        // 磁盘层
        synchronized (diskLock) {
            for (DiskEntry entry : diskIndex.values()) {
                //noinspection ResultOfMethodCallIgnored
                entry.file.delete();
            }
            diskIndex.clear();
            diskTotalBytes = 0L;
        }
    }

    /** 触发过期清理与容量裁剪。建议在应用启动或低内存时调用。 */
    public void trim() {
        synchronized (diskLock) {
            // 1. 清理过期条目
            long now = System.currentTimeMillis();
            Iterator<Map.Entry<String, DiskEntry>> it = diskIndex.entrySet().iterator();
            while (it.hasNext()) {
                DiskEntry entry = it.next().getValue();
                if (now - entry.writtenAt > ttlMillis) {
                    //noinspection ResultOfMethodCallIgnored
                    entry.file.delete();
                    diskTotalBytes -= entry.size;
                    it.remove();
                }
            }
            // 2. 容量裁剪（LRU：从最旧开始删）
            while (diskTotalBytes > diskMaxBytes && !diskIndex.isEmpty()) {
                Iterator<Map.Entry<String, DiskEntry>> it2 = diskIndex.entrySet().iterator();
                if (!it2.hasNext()) break;
                DiskEntry entry = it2.next().getValue();
                //noinspection ResultOfMethodCallIgnored
                entry.file.delete();
                diskTotalBytes -= entry.size;
                it2.remove();
            }
        }
    }

    public long diskUsageBytes() {
        synchronized (diskLock) {
            return diskTotalBytes;
        }
    }

    public int memoryEntryCount() {
        return memoryCache.size();
    }

    // ============ 磁盘层实现 ============

    private void loadDiskIndex() {
        if (diskDir == null || !diskDir.exists()) return;
        synchronized (diskLock) {
            File[] files = diskDir.listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis();
            for (File f : files) {
                if (!f.isFile()) continue;
                long size = f.length();
                long written = f.lastModified();
                if (now - written > ttlMillis) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    continue;
                }
                String key = f.getName();
                DiskEntry entry = new DiskEntry(key, f, size, written);
                diskIndex.put(key, entry);
                diskTotalBytes += size;
            }
        }
    }

    private Bitmap readDisk(String key) {
        if (diskDir == null) return null;
        synchronized (diskLock) {
            DiskEntry entry = diskIndex.get(key);
            if (entry == null) return null;
            long now = System.currentTimeMillis();
            if (now - entry.writtenAt > ttlMillis) {
                //noinspection ResultOfMethodCallIgnored
                entry.file.delete();
                diskIndex.remove(key);
                diskTotalBytes -= entry.size;
                return null;
            }
            entry.lastAccessAt = now;
            // LinkedHashMap accessOrder=true，get 已自动调整顺序
            Bitmap bmp = decodeBitmapFile(entry.file);
            if (bmp == null) {
                // 损坏的缓存条目，清理
                //noinspection ResultOfMethodCallIgnored
                entry.file.delete();
                diskIndex.remove(key);
                diskTotalBytes -= entry.size;
            }
            return bmp;
        }
    }

    private void writeDisk(String key, Bitmap bitmap) {
        if (diskDir == null) return;
        File outFile = new File(diskDir, key);
        synchronized (diskLock) {
            // 若已存在，先减去旧大小
            DiskEntry old = diskIndex.get(key);
            if (old != null) {
                diskTotalBytes -= old.size;
            }
            try (OutputStream os = new FileOutputStream(outFile)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, os);
            } catch (IOException e) {
                android.util.Log.w(TAG, "writeDisk failed: " + e.getMessage());
                return;
            }
            long size = outFile.length();
            long writtenAt = outFile.lastModified();
            DiskEntry entry = new DiskEntry(key, outFile, size, writtenAt);
            diskIndex.put(key, entry);
            diskTotalBytes += size;
            // 容量裁剪
            while (diskTotalBytes > diskMaxBytes && diskIndex.size() > 1) {
                Iterator<Map.Entry<String, DiskEntry>> it = diskIndex.entrySet().iterator();
                if (!it.hasNext()) break;
                DiskEntry evict = it.next().getValue();
                if (evict.key.equals(key)) {
                    // 不删除刚写入的条目
                    continue;
                }
                //noinspection ResultOfMethodCallIgnored
                evict.file.delete();
                diskTotalBytes -= evict.size;
                it.remove();
            }
        }
    }

    private void removeDisk(String key) {
        synchronized (diskLock) {
            DiskEntry entry = diskIndex.remove(key);
            if (entry != null) {
                //noinspection ResultOfMethodCallIgnored
                entry.file.delete();
                diskTotalBytes -= entry.size;
            }
        }
    }

    private static Bitmap decodeBitmapFile(File file) {
        try (FileInputStream fis = new FileInputStream(file)) {
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return android.graphics.BitmapFactory.decodeStream(fis, null, opts);
        } catch (IOException e) {
            return null;
        }
    }
}
