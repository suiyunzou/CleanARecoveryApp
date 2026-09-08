package com.example.cleanrecovery.background;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import com.example.cleanrecovery.ui.browser.BrowserPrefs;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.cleanrecovery.download.UniversalDownloadManager;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 后台下载服务（隐藏模块）。
 *
 * <p>作为<b>前台服务</b>运行（{@code foregroundServiceType=dataSync}），从
 * {@link DownloadQueueManager} 获取任务并执行下载。完全无 UI，所有操作静默执行。</p>
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
 *
 * <h3>P0 A1 改动：前台服务化</h3>
 * <p>Android 14+（API 34）对后台下载限制严格，普通后台服务在息屏/省电模式下
 * 极易被系统杀死导致下载中断。本服务转为 {@code dataSync} 类型前台服务，通过
 * 常驻通知保证进程存活，下载过程不被系统回收。</p>
 * <ul>
 *   <li>{@code onCreate} 创建通知渠道</li>
 *   <li>{@code onStartCommand} 调用 {@link #startForeground} 提升为前台服务</li>
 *   <li>{@link #stopForegroundSelf} 在队列空闲时降级回普通服务（可选）</li>
 * </ul>
 */
public final class BackgroundDownloadService extends Service
        implements DownloadQueueManager.TaskExecutor {
    private static final String TAG = "BgDownloadSvc";

    /** 最大下载速度（字节/秒），0 表示不限速。 */
    private static final long MAX_SPEED_BPS = 2 * 1024 * 1024; // 2MB/s
    /** 缓冲区大小（64KB，平衡内存和效率）。 */
    private static final int BUFFER_SIZE = 64 * 1024;

    /** 通知渠道 ID。 */
    private static final String CHANNEL_ID = "bg_download_channel";
    /** 前台服务通知 ID（固定值，避免与其它通知冲突）。 */
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;

    private DownloadQueueManager queueManager;
    private final AtomicLong totalDownloadedBytes = new AtomicLong(0);
    private final AtomicLong totalTasksCompleted = new AtomicLong(0);
    private NotificationManager notificationManager;
    private volatile boolean foregroundStarted = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "后台下载服务启动");
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
        queueManager = DownloadQueueManager.getInstance();
        queueManager.setExecutor(this);
        // P0 A2：从 SQLite 恢复未完成任务（应用被杀重启后能续跑）
        queueManager.init(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // P0 A1：立即提升为前台服务，避免下载中被系统杀死
        startForegroundIfNeeded();
        return START_STICKY; // 服务被杀后自动重启
    }

    /** 创建通知渠道（Android 8.0+ 必需）。 */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notificationManager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "后台下载服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("下载服务运行状态通知，下载期间会常驻");
            channel.setShowBadge(false);
            channel.enableVibration(false);
            channel.enableLights(false);
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /** 提升为前台服务（如尚未提升）。 */
    private void startForegroundIfNeeded() {
        if (foregroundStarted) return;
        try {
            Notification notification = buildForegroundNotification("下载服务运行中", null);
            // Android 14+（API 34）要求指定 foregroundServiceType
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(FOREGROUND_NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
            Log.i(TAG, "已提升为前台服务 (dataSync)");
        } catch (SecurityException | IllegalStateException e) {
            // 缺少权限或后台限制时降级为后台服务，不让浏览器入口崩溃
            Log.w(TAG, "前台服务启动失败，降级为后台服务: " + e.getMessage());
        }
    }

    /**
     * 构建前台服务通知。
     *
     * @param contentText 通知正文（如"下载服务运行中"或"正在下载: xxx.mp4"）
     * @param mainIntent  点击通知跳转的 Intent（可为 null）
     */
    private Notification buildForegroundNotification(String contentText, Intent mainIntent) {
        PendingIntent pi = null;
        if (mainIntent != null) {
            int flag = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flag |= PendingIntent.FLAG_IMMUTABLE;
            }
            pi = PendingIntent.getActivity(this, 0, mainIntent, flag);
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("数据恢复下载")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS);
        if (pi != null) {
            b.setContentIntent(pi);
        }
        return b.build();
    }

    /**
     * 更新前台服务通知文本（用于反映当前下载进度/状态）。
     * M1 简化：仅在任务切换时更新，不在每帧进度时更新（避免通知频繁刷新）。
     */
    public void updateNotification(String contentText) {
        if (!foregroundStarted || notificationManager == null) return;
        try {
            Notification notification = buildForegroundNotification(contentText, null);
            notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification);
        } catch (Exception e) {
            Log.w(TAG, "更新通知失败: " + e.getMessage());
        }
    }

    /**
     * 队列空闲时可选降级为普通后台服务（释放通知栏位）。
     * <p>调用 {@link #stopForeground} 移除通知，但服务进程仍在。</p>
     */
    public void stopForegroundSelf() {
        if (!foregroundStarted) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        foregroundStarted = false;
        Log.i(TAG, "已从前台降级");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "后台下载服务停止");
        if (queueManager != null) queueManager.setExecutor(null);
        super.onDestroy();
    }

    /**
     * 执行下载任务（由 DownloadQueueManager 调用）。
     */
    @Override
    public File executeTask(DownloadQueueManager.DownloadTask task) {
        Log.i(TAG, "执行任务#" + task.id + " URL=" + task.url.substring(0, Math.min(60, task.url.length())));
        // 更新前台通知，反映当前正在下载的任务（P0 A1）
        String displayName = (task.pageTitle != null && !task.pageTitle.isEmpty())
                ? task.pageTitle : ("任务#" + task.id);
        updateNotification("正在下载: " + displayName);

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
        Map<String, String> headers = task.rawResource ? task.requestHeaders : buildHeaders(classifyResult);

        try {
            File result;
            // 4. 根据类型选择下载策略
            if (task.rawResource) {
                result = downloadDirect(task.id, task.url, outFile, headers, true);
            } else switch (classifyResult.type) {
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
                    result = downloadDirect(task.id, task.url, outFile, headers, false);
                    break;
            }

            if (result == null || !result.exists() || result.length() == 0) {
                Log.w(TAG, "下载失败: 文件为空");
                return null;
            }

            // 浏览器下载保存原始文件，清单、图片、压缩包等无需通过媒体解码器。
            if (task.rawResource) {
                task.fileHash = IntegrityVerifier.computeSha256(result);
                task.fileSize = result.length();
                if (task.fileHash == null) throw new java.io.IOException("Cannot hash downloaded file");
            } else {
                IntegrityVerifier.VerifyResult verifyResult = IntegrityVerifier.verify(result);
                if (!verifyResult.valid) {
                    Log.w(TAG, "完整性校验失败: " + verifyResult.errorMessage);
                    // 删除无效文件
                    if (!result.delete()) {
                        Log.w(TAG, "无法删除无效文件: " + result.getName());
                    }
                    return null;
                }
                task.fileHash = verifyResult.hash;
                task.fileSize = verifyResult.fileSize;
            }

            // 6. 记录哈希和大小
            totalDownloadedBytes.addAndGet(task.fileSize);
            totalTasksCompleted.incrementAndGet();

            Log.i(TAG, "下载+校验完成: " + result.getName()
                    + " " + task.fileSize + "字节 hash="
                    + (task.fileHash != null ? task.fileHash.substring(0, 12) : "null"));

            return result;
        } catch (Exception e) {
            Log.e(TAG, "下载异常: " + e.getMessage(), e);
            // 清理临时文件
            cleanupFile(outFile);
            return null;
        }
    }

    /** 下载直接视频/音频文件（复用 UniversalDownloadManager）。 */
    private File downloadDirect(int taskId, String url, File outFile, Map<String, String> headers, boolean rawResource) {
        try {
            UniversalDownloadManager manager = rawResource ? new BrowserFileDownloader() : new UniversalDownloadManager();
            DownloadTaskDbHelper taskDb = DownloadTaskDbHelper.getInstance(this);
            final boolean[] success = {false};
            final String[] error = {null};

            manager.download(url, outFile, headers, new com.example.cleanrecovery.download.DownloadProgressCallback() {
                private long lastProgressAt = -1;
                private long lastDownloaded;
                private long lastTotal;

                @Override
                public void onProgress(long downloadedBytes, long totalBytes, long speedBps, int percent) {
                    lastDownloaded = downloadedBytes;
                    lastTotal = totalBytes;
                    long now = android.os.SystemClock.elapsedRealtime();
                    // 下载页按秒轮询；最多每半秒写一次，终态另行写入精确字节数。
                    if (lastProgressAt < 0 || now - lastProgressAt >= 500) {
                        taskDb.updateProgress(taskId, downloadedBytes, totalBytes);
                        lastProgressAt = now;
                    }
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
                    long size = new File(path).length();
                    taskDb.updateProgress(taskId, size, size);
                    success[0] = true;
                }

                @Override
                public void onError(String errorCode, String message) {
                    taskDb.updateProgress(taskId, lastDownloaded, lastTotal);
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
        String extSuffix = "." + ext;
        if (task.rawResource) {
            String requested = sanitizeFileName(task.fileName == null || task.fileName.isEmpty()
                    ? android.webkit.URLUtil.guessFileName(task.url, null, task.mimeType) : task.fileName);
            int dot = requested.lastIndexOf('.');
            baseName = dot > 0 ? requested.substring(0, dot) : requested;
            extSuffix = dot > 0 ? requested.substring(dot) : "";
        } else if (task.fileName != null && !task.fileName.isEmpty()) {
            // 「新建下载」用户指定的文件名：自带扩展名则原样用，否则追加探测到的扩展名
            String requested = sanitizeFileName(task.fileName);
            int dot = requested.lastIndexOf('.');
            if (dot > 0) {
                baseName = requested.substring(0, dot);
                extSuffix = requested.substring(dot);
            } else {
                baseName = requested;
            }
        } else if (task.pageTitle != null && !task.pageTitle.isEmpty()) {
            baseName = sanitizeFileName(task.pageTitle);
        }
        // 截断过长的文件名
        if (baseName.length() > 80) {
            baseName = baseName.substring(0, 80);
        }

        String fileName = baseName + extSuffix;
        File outFile = new File(dir, fileName);

        // 避免覆盖已存在文件
        int counter = 1;
        while (outFile.exists()) {
            outFile = new File(dir, baseName + "_" + counter + extSuffix);
            counter++;
        }

        return outFile;
    }

    /** 获取下载目录（P0③：设置→下载目录 优先，回退应用私有目录）。 */
    private File getDownloadDir() {
        try {
            File configured = new BrowserPrefs(this).downloadDirFile();
            if (configured != null) return configured;
        } catch (Exception ignored) {
        }
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
