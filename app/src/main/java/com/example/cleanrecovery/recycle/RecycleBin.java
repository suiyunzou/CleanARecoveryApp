package com.example.cleanrecovery.recycle;

import com.example.cleanrecovery.util.PathManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件回收站系统。
 *
 * 设计要点：
 *  - 暂存：删除文件时不直接 delete，而是移动到 .trash/<uuid>/ 下，并写 meta.json。
 *  - 恢复：根据 meta.json 记录的原路径，将文件移回原位置（冲突时自动重命名）。
 *  - 彻底删除：从回收站物理删除文件与元数据。
 *  - 定时清理：超过保留期（默认 30 天）的条目在 list/cleanup 时自动彻底删除。
 *  - 容量上限：超过 maxBytes 时按最早进入回收站的顺序淘汰。
 *  - 线程安全：所有公开方法 synchronized 或通过单线程 executor 串行化。
 *
 * 元数据格式（meta.json）：
 * {
 *   "id": "uuid",
 *   "originalPath": "/storage/emulated/0/...",
 *   "originalName": "photo.jpg",
 *   "size": 123456,
 *   "deletedAt": 1718700000000,
 *   "expiresAt": 1721292000000,
 *   "isDirectory": false
 * }
 *
 * 不引入任何云备份相关功能。
 */
public final class RecycleBin {
    /** 默认保留期：30 天。 */
    public static final long DEFAULT_RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000L;
    /** 默认容量上限：500MB。 */
    public static final long DEFAULT_MAX_BYTES = 500L * 1024 * 1024;
    private static final String META_FILE_NAME = "meta.json";
    private static final String DATA_FILE_NAME = "data";

    public interface OpCallback {
        void onComplete(boolean success, String message);
    }

    public interface ListCallback {
        void onResult(List<RecycleEntry> entries);
    }

    private final File trashRoot;
    private final long retentionMillis;
    private final long maxBytes;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public RecycleBin() {
        this(PathManager.trashRoot(), DEFAULT_RETENTION_MILLIS, DEFAULT_MAX_BYTES);
    }

    public RecycleBin(File trashRoot, long retentionMillis, long maxBytes) {
        this.trashRoot = trashRoot;
        this.retentionMillis = retentionMillis;
        this.maxBytes = maxBytes;
        if (trashRoot != null && !trashRoot.exists()) {
            //noinspection ResultOfMethodCallIgnored
            trashRoot.mkdirs();
        }
    }

    // ============ 数据模型 ============

    public static final class RecycleEntry {
        public final String id;
        public final String originalPath;
        public final String originalName;
        public final long size;
        public final long deletedAt;
        public final long expiresAt;
        public final boolean isDirectory;

        public RecycleEntry(String id, String originalPath, String originalName,
                            long size, long deletedAt, long expiresAt, boolean isDirectory) {
            this.id = id;
            this.originalPath = originalPath;
            this.originalName = originalName;
            this.size = size;
            this.deletedAt = deletedAt;
            this.expiresAt = expiresAt;
            this.isDirectory = isDirectory;
        }

        public boolean isExpired(long now) {
            return now >= expiresAt;
        }

        public File dataFile(File trashRoot) {
            return new File(new File(trashRoot, id), DATA_FILE_NAME);
        }

        public File metaFile(File trashRoot) {
            return new File(new File(trashRoot, id), META_FILE_NAME);
        }
    }

    // ============ 公开 API ============

    /** 异步将文件移入回收站。原文件会被删除。 */
    public void moveToTrash(File file, OpCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                String msg;
                try {
                    ok = moveToTrashSync(file);
                    msg = ok ? "OK" : "FAIL";
                } catch (Exception e) {
                    ok = false;
                    msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final boolean finalOk = ok;
                final String finalMsg = msg;
                callback.onComplete(finalOk, finalMsg);
            }
        });
    }

    /** 同步版本：将文件移入回收站。成功后原文件已不存在。 */
    public synchronized boolean moveToTrashSync(File file) throws IOException {
        if (file == null || !file.exists()) {
            throw new IOException("Source file does not exist");
        }
        cleanupExpiredSync();

        String id = UUID.randomUUID().toString().replace("-", "");
        File entryDir = new File(trashRoot, id);
        if (!entryDir.exists() && !entryDir.mkdirs()) {
            throw new IOException("Cannot create trash entry dir: " + entryDir.getAbsolutePath());
        }
        File dataFile = new File(entryDir, DATA_FILE_NAME);
        long size = file.isDirectory() ? PathManager.directorySize(file) : file.length();
        long now = System.currentTimeMillis();

        // 移动文件：优先 rename，失败则复制+删除
        boolean moved;
        try {
            moved = file.renameTo(dataFile);
        } catch (SecurityException e) {
            moved = false;
        }
        if (!moved) {
            if (file.isDirectory()) {
                // 目录跨挂载点 rename 失败时，递归复制后删除原目录
                copyDirectory(file, dataFile);
                PathManager.deleteRecursively(file, false);
            } else {
                copyFile(file, dataFile);
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }

        RecycleEntry entry = new RecycleEntry(
                id,
                file.getAbsolutePath(),
                file.getName(),
                size,
                now,
                now + retentionMillis,
                file.isDirectory()
        );
        writeMeta(entry);
        enforceCapacitySync();
        return true;
    }

    /** 异步恢复：将回收站条目移回原路径。冲突时自动重命名。 */
    public void restore(String entryId, OpCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok;
                String msg;
                try {
                    File restored = restoreSync(entryId);
                    ok = true;
                    msg = restored == null ? "NOT_FOUND" : restored.getAbsolutePath();
                } catch (Exception e) {
                    ok = false;
                    msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                callback.onComplete(ok, msg);
            }
        });
    }

    /** 同步恢复：返回恢复后的目标文件，或 null 表示条目不存在。 */
    public synchronized File restoreSync(String entryId) throws IOException {
        RecycleEntry entry = readMeta(entryId);
        if (entry == null) return null;
        File entryDir = new File(trashRoot, entryId);
        File dataFile = new File(entryDir, DATA_FILE_NAME);
        if (!dataFile.exists()) {
            throw new IOException("Trash data file missing: " + dataFile.getAbsolutePath());
        }

        File original = new File(entry.originalPath);
        File target = original;
        // 处理冲突：原路径已存在同名文件时，添加 (1)、(2) 后缀
        if (target.exists()) {
            target = uniqueTarget(original);
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot recreate original parent dir: " + parent.getAbsolutePath());
        }

        boolean moved;
        try {
            moved = dataFile.renameTo(target);
        } catch (SecurityException e) {
            moved = false;
        }
        if (!moved) {
            if (entry.isDirectory) {
                copyDirectory(dataFile, target);
                PathManager.deleteRecursively(dataFile, false);
            } else {
                copyFile(dataFile, target);
                //noinspection ResultOfMethodCallIgnored
                dataFile.delete();
            }
        }
        // 清理条目目录
        PathManager.deleteRecursively(entryDir, false);
        return target;
    }

    /**
     * 文件管理 V2：按原始文件名同步恢复最近一条匹配条目。
     * 用于 Snackbar 撤销场景。返回恢复后的目标文件，或 null 表示未找到。
     */
    public synchronized File restoreByNameSync(String originalName) throws IOException {
        if (originalName == null) return null;
        List<RecycleEntry> entries = listSync();
        for (RecycleEntry entry : entries) {
            if (originalName.equals(entry.originalName)) {
                return restoreSync(entry.id);
            }
        }
        return null;
    }

    /** 异步彻底删除：从回收站物理删除条目。 */
    public void permanentDelete(String entryId, OpCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                File entryDir = new File(trashRoot, entryId);
                boolean ok = PathManager.deleteRecursively(entryDir, false);
                callback.onComplete(ok, ok ? "OK" : "FAIL");
            }
        });
    }

    /** 同步彻底删除。 */
    public synchronized boolean permanentDeleteSync(String entryId) {
        File entryDir = new File(trashRoot, entryId);
        return PathManager.deleteRecursively(entryDir, false);
    }

    /** 异步清空回收站。 */
    public void emptyAll(OpCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                boolean ok = PathManager.deleteRecursively(trashRoot, true);
                if (ok && trashRoot != null) {
                    //noinspection ResultOfMethodCallIgnored
                    trashRoot.mkdirs();
                }
                callback.onComplete(ok, ok ? "OK" : "FAIL");
            }
        });
    }

    /** 异步列出回收站所有条目（自动清理已过期项）。 */
    public void list(ListCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                callback.onResult(listSync());
            }
        });
    }

    /** 同步列出回收站所有条目。 */
    public synchronized List<RecycleEntry> listSync() {
        cleanupExpiredSync();
        if (trashRoot == null || !trashRoot.exists()) {
            return Collections.emptyList();
        }
        File[] children = trashRoot.listFiles();
        if (children == null) return Collections.emptyList();
        List<RecycleEntry> result = new ArrayList<>();
        for (File dir : children) {
            if (!dir.isDirectory()) continue;
            RecycleEntry entry = readMeta(dir.getName());
            if (entry != null) {
                result.add(entry);
            }
        }
        // 按删除时间倒序（最近删除的在前）
        Collections.sort(result, new java.util.Comparator<RecycleEntry>() {
            @Override
            public int compare(RecycleEntry a, RecycleEntry b) {
                return Long.compare(b.deletedAt, a.deletedAt);
            }
        });
        return result;
    }

    /** 同步清理所有已过期条目。返回清理掉的条目数。 */
    public synchronized int cleanupExpiredSync() {
        if (trashRoot == null || !trashRoot.exists()) return 0;
        File[] children = trashRoot.listFiles();
        if (children == null) return 0;
        long now = System.currentTimeMillis();
        int cleaned = 0;
        for (File dir : children) {
            if (!dir.isDirectory()) continue;
            RecycleEntry entry = readMeta(dir.getName());
            if (entry == null || entry.isExpired(now)) {
                if (PathManager.deleteRecursively(dir, false)) cleaned++;
            }
        }
        return cleaned;
    }

    /** 异步清理过期条目。 */
    public void cleanupExpired(OpCallback callback) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                int n = cleanupExpiredSync();
                callback.onComplete(true, String.valueOf(n));
            }
        });
    }

    /** 计算回收站当前占用字节数。 */
    public synchronized long usageBytes() {
        return PathManager.directorySize(trashRoot);
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    // ============ 内部实现 ============

    private void enforceCapacitySync() {
        long usage = usageBytes();
        if (usage <= maxBytes) return;
        // 按删除时间正序（最早进入的先淘汰）
        List<RecycleEntry> all = listSync();
        Collections.sort(all, new java.util.Comparator<RecycleEntry>() {
            @Override
            public int compare(RecycleEntry a, RecycleEntry b) {
                return Long.compare(a.deletedAt, b.deletedAt);
            }
        });
        for (RecycleEntry entry : all) {
            if (usage <= maxBytes) break;
            File entryDir = new File(trashRoot, entry.id);
            long entrySize = PathManager.directorySize(entryDir);
            if (PathManager.deleteRecursively(entryDir, false)) {
                usage -= entrySize;
            }
        }
    }

    private void writeMeta(RecycleEntry entry) throws IOException {
        File entryDir = new File(trashRoot, entry.id);
        File metaFile = new File(entryDir, META_FILE_NAME);
        JSONObject json = new JSONObject();
        try {
            json.put("id", entry.id);
            json.put("originalPath", entry.originalPath);
            json.put("originalName", entry.originalName);
            json.put("size", entry.size);
            json.put("deletedAt", entry.deletedAt);
            json.put("expiresAt", entry.expiresAt);
            json.put("isDirectory", entry.isDirectory);
        } catch (JSONException e) {
            throw new IOException("Cannot serialize meta: " + e.getMessage(), e);
        }
        try (FileOutputStream fos = new FileOutputStream(metaFile)) {
            fos.write(json.toString().getBytes("UTF-8"));
        }
    }

    private RecycleEntry readMeta(String entryId) {
        File entryDir = new File(trashRoot, entryId);
        File metaFile = new File(entryDir, META_FILE_NAME);
        if (!metaFile.exists()) return null;
        try (FileInputStream fis = new FileInputStream(metaFile)) {
            byte[] buf = new byte[(int) metaFile.length()];
            int read = fis.read(buf);
            if (read <= 0) return null;
            JSONObject json = new JSONObject(new String(buf, 0, read, "UTF-8"));
            return new RecycleEntry(
                    json.optString("id", entryId),
                    json.optString("originalPath", ""),
                    json.optString("originalName", ""),
                    json.optLong("size", 0L),
                    json.optLong("deletedAt", 0L),
                    json.optLong("expiresAt", 0L),
                    json.optBoolean("isDirectory", false)
            );
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private static File uniqueTarget(File original) {
        String name = original.getName();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        File parent = original.getParentFile();
        int counter = 1;
        File candidate;
        do {
            candidate = new File(parent, String.format(Locale.US, "%s(%d)%s", base, counter, ext));
            counter++;
        } while (candidate.exists());
        return candidate;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileChannel in = new FileInputStream(src).getChannel();
             FileChannel out = new FileOutputStream(dst).getChannel()) {
            in.transferTo(0L, in.size(), out);
        }
    }

    private static void copyDirectory(File src, File dst) throws IOException {
        if (!dst.exists() && !dst.mkdirs()) {
            throw new IOException("Cannot create target dir: " + dst.getAbsolutePath());
        }
        File[] children = src.listFiles();
        if (children == null) return;
        for (File child : children) {
            File target = new File(dst, child.getName());
            if (child.isDirectory()) {
                copyDirectory(child, target);
            } else {
                copyFile(child, target);
            }
        }
    }
}
