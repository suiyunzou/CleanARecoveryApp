package com.example.cleanrecovery.ytdlp;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 下载归档（对应 yt-dlp {@code download_archive}）。
 *
 * <p>记录已成功下载的视频（extractor_key+id），实现幂等跳过：
 * 用户重复粘贴同一链接时，自动跳过，不再重新解析与下载。</p>
 *
 * <p>持久化到应用私有目录 {@code archive.txt}，每行一条
 * {@code extractor_key id}。线程安全：内部用 {@link CopyOnWriteArraySet}。</p>
 */
public final class DownloadArchive {

    private static final String TAG = "DownloadArchive";
    private static final String ARCHIVE_FILE = "archive.txt";

    private static volatile DownloadArchive instance;

    private final CopyOnWriteArraySet<String> downloaded = new CopyOnWriteArraySet<>();
    private final File storeFile;

    private DownloadArchive(File storeFile) {
        this.storeFile = storeFile;
        loadFromDisk();
    }

    /** 获取单例。 */
    public static DownloadArchive getInstance(Context context) {
        if (instance == null) {
            synchronized (DownloadArchive.class) {
                if (instance == null) {
                    File f = new File(context.getApplicationContext().getFilesDir(), ARCHIVE_FILE);
                    instance = new DownloadArchive(f);
                }
            }
        }
        return instance;
    }

    /** 测试用：直接构造（不持久化）。 */
    public static DownloadArchive forTesting() {
        return new DownloadArchive(null);
    }

    /** 是否已下载（幂等判断）。 */
    public boolean isDownloaded(String extractorKey, String id) {
        return isDownloaded(archiveKey(extractorKey, id));
    }

    /** 是否已下载（直接传归档键）。 */
    public boolean isDownloaded(String archiveKey) {
        return archiveKey != null && downloaded.contains(archiveKey);
    }

    /** 标记已下载。 */
    public void markDownloaded(String extractorKey, String id) {
        markDownloaded(archiveKey(extractorKey, id));
    }

    /** 标记已下载（直接传归档键）。 */
    public void markDownloaded(String archiveKey) {
        if (archiveKey == null || archiveKey.isEmpty()) return;
        if (downloaded.add(archiveKey)) {
            appendToDisk(archiveKey);
        }
    }

    /** 移除已下载标记（允许重新下载）。 */
    public void remove(String archiveKey) {
        if (archiveKey == null) return;
        if (downloaded.remove(archiveKey)) {
            saveToDisk();
        }
    }

    /** 已归档数量。 */
    public int size() { return downloaded.size(); }

    /** 当前快照（不可变）。 */
    public Set<String> snapshot() { return new HashSet<>(downloaded); }

    /** 清空所有归档（谨慎使用）。 */
    public void clear() {
        downloaded.clear();
        saveToDisk();
    }

    /** 拼接归档键：{@code extractor_key id}（对齐 yt-dlp archive 行格式）。 */
    public static String archiveKey(String extractorKey, String id) {
        if (extractorKey == null || id == null) return null;
        return extractorKey + " " + id;
    }

    // ===== 持久化 =====

    private void loadFromDisk() {
        if (storeFile == null || !storeFile.exists()) return;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(storeFile), StandardCharsets.UTF_8))) {
            String line;
            int loaded = 0;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                downloaded.add(line);
                loaded++;
            }
            Log.i(TAG, "loaded " + loaded + " archive entries from " + storeFile.getName());
        } catch (Exception e) {
            Log.w(TAG, "load archive failed: " + e.getMessage());
        }
    }

    private synchronized void appendToDisk(String archiveKey) {
        if (storeFile == null) return;
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(storeFile, true), StandardCharsets.UTF_8)) {
            w.write(archiveKey + "\n");
        } catch (Exception e) {
            Log.w(TAG, "append archive failed: " + e.getMessage());
        }
    }

    private synchronized void saveToDisk() {
        if (storeFile == null) return;
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(storeFile), StandardCharsets.UTF_8)) {
            for (String key : downloaded) {
                w.write(key + "\n");
            }
        } catch (Exception e) {
            Log.w(TAG, "save archive failed: " + e.getMessage());
        }
    }
}
