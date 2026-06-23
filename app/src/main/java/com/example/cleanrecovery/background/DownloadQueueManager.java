package com.example.cleanrecovery.background;

import android.util.Log;

import androidx.annotation.NonNull;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 下载队列管理器（后台下载模块）。
 *
 * <p>管理后台下载任务的优先级队列，支持：</p>
 * <ul>
 *   <li>优先级排序（HLS/DASH > 直接视频 > 音频 > 未知）</li>
 *   <li>失败自动重试（最多 3 次，指数退避：1s, 2s, 4s）</li>
 *   <li>去重（相同 URL 不重复入队）</li>
 *   <li>任务状态跟踪（PENDING/RUNNING/COMPLETED/FAILED/CANCELLED）</li>
 *   <li>顺序执行（单线程，避免并发问题）</li>
 * </ul>
 */
public final class DownloadQueueManager {
    private static final String TAG = "DownloadQueue";
    private static final int MAX_RETRY = 3;
    private static final long[] RETRY_DELAYS_MS = {1000, 2000, 4000};

    private static volatile DownloadQueueManager instance;

    private final PriorityQueue<DownloadTask> queue = new PriorityQueue<>();
    private final Map<String, DownloadTask> taskMap = new HashMap<>();
    private final Object lock = new Object();
    private volatile DownloadTask currentTask;
    private volatile boolean running = false;
    private final AtomicInteger taskIdGenerator = new AtomicInteger(0);
    private TaskExecutor executor;

    /** 下载任务。 */
    public static final class DownloadTask implements Comparable<DownloadTask> {
        public final int id;
        public final String url;
        public final String mimeType;
        public final String pageUrl;
        public final String pageTitle;
        public final int priority;
        public int retryCount = 0;
        public volatile TaskStatus status = TaskStatus.PENDING;
        public volatile String errorMessage;
        public volatile String resultPath;
        public volatile String fileHash;
        public volatile long fileSize;

        public enum TaskStatus {
            PENDING, RUNNING, COMPLETED, FAILED, CANCELLED
        }

        DownloadTask(int id, String url, String mimeType, String pageUrl,
                     String pageTitle, int priority) {
            this.id = id;
            this.url = url;
            this.mimeType = mimeType;
            this.pageUrl = pageUrl;
            this.pageTitle = pageTitle;
            this.priority = priority;
        }

        @Override
        public int compareTo(@NonNull DownloadTask other) {
            // 优先级高的先执行（数值大的优先）
            return Integer.compare(other.priority, this.priority);
        }

        @Override
        public String toString() {
            return "Task#" + id + "[" + status + "] " + url.substring(0, Math.min(50, url.length()));
        }
    }

    /** 任务执行器接口（由 BackgroundDownloadService 实现）。 */
    public interface TaskExecutor {
        /**
         * 执行下载任务。
         *
         * @param task 任务
         * @return 下载的文件，null 表示失败
         */
        File executeTask(DownloadTask task);
    }

    private DownloadQueueManager() {
    }

    /** 获取单例实例。 */
    public static DownloadQueueManager getInstance() {
        if (instance == null) {
            synchronized (DownloadQueueManager.class) {
                if (instance == null) {
                    instance = new DownloadQueueManager();
                }
            }
        }
        return instance;
    }

    /** 设置任务执行器。 */
    public void setExecutor(TaskExecutor executor) {
        this.executor = executor;
    }

    /**
     * 入队下载任务。
     *
     * @param url       媒体 URL
     * @param mimeType  MIME 类型（可为 null）
     * @param pageUrl   来源页面 URL
     * @param pageTitle 来源页面标题
     * @return 任务 ID，-1 表示重复未入队
     */
    public int enqueue(String url, String mimeType, String pageUrl, String pageTitle) {
        if (url == null || url.isEmpty()) return -1;

        synchronized (lock) {
            // 去重
            if (taskMap.containsKey(url)) {
                Log.d(TAG, "URL已在队列中，跳过: " + url.substring(0, Math.min(50, url.length())));
                return -1;
            }

            // 计算优先级
            int priority = calculatePriority(url, mimeType);

            DownloadTask task = new DownloadTask(
                    taskIdGenerator.incrementAndGet(), url, mimeType, pageUrl, pageTitle, priority);
            taskMap.put(url, task);
            queue.add(task);

            Log.i(TAG, "入队任务#" + task.id + " 优先级=" + priority + " URL="
                    + url.substring(0, Math.min(60, url.length())));
        }

        // 触发执行
        startProcessing();
        return taskIdGenerator.get();
    }

    /** 计算任务优先级（数值越大优先级越高）。 */
    private int calculatePriority(String url, String mimeType) {
        String lower = url.toLowerCase();
        // HLS/DASH 分段流优先级最高
        if (lower.contains(".m3u8") || lower.contains(".mpd")) return 100;
        // YouTube googlevideo 视频流
        if (lower.contains("googlevideo.com") && lower.contains("itag=")) {
            // 含 itag 的视频流优先级高
            if (lower.contains("mime=video")) return 90;
            if (lower.contains("mime=audio")) return 70;
            return 80;
        }
        // 直接视频文件
        if (lower.endsWith(".mp4") || lower.endsWith(".webm")) return 60;
        // 直接音频文件
        if (lower.endsWith(".m4a") || lower.endsWith(".mp3") || lower.endsWith(".aac")) return 50;
        // 未知类型
        return 30;
    }

    /** 启动队列处理。 */
    private void startProcessing() {
        if (running) return;
        running = true;
        Thread thread = new Thread(this::processQueue, "BgDownloadQueue");
        thread.setDaemon(true);
        thread.start();
    }

    /** 处理队列。 */
    private void processQueue() {
        Log.i(TAG, "队列处理线程启动");
        while (running) {
            DownloadTask task;
            synchronized (lock) {
                task = queue.poll();
            }
            if (task == null) {
                break;
            }
            currentTask = task;
            processTask(task);
            currentTask = null;
        }
        running = false;
        Log.i(TAG, "队列处理线程结束");
    }

    /** 处理单个任务（含重试逻辑）。 */
    private void processTask(DownloadTask task) {
        Log.i(TAG, "开始处理 " + task);

        while (task.retryCount <= MAX_RETRY) {
            task.status = DownloadTask.TaskStatus.RUNNING;
            try {
                if (executor == null) {
                    task.errorMessage = "未设置执行器";
                    task.status = DownloadTask.TaskStatus.FAILED;
                    break;
                }

                File result = executor.executeTask(task);
                if (result != null && result.exists() && result.length() > 0) {
                    task.resultPath = result.getAbsolutePath();
                    task.fileSize = result.length();
                    task.status = DownloadTask.TaskStatus.COMPLETED;
                    Log.i(TAG, "任务完成 " + task + " -> " + result.getName()
                            + " (" + result.length() + " bytes)");
                    return;
                }

                task.errorMessage = "下载结果为空";
            } catch (Exception e) {
                task.errorMessage = e.getMessage();
                Log.w(TAG, "任务异常 " + task + ": " + e.getMessage());
            }

            // 重试
            task.retryCount++;
            if (task.retryCount <= MAX_RETRY) {
                long delay = RETRY_DELAYS_MS[Math.min(task.retryCount - 1, RETRY_DELAYS_MS.length - 1)];
                Log.i(TAG, "任务重试 " + task.retryCount + "/" + MAX_RETRY
                        + " 延迟 " + delay + "ms");
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    task.status = DownloadTask.TaskStatus.FAILED;
                    break;
                }
            }
        }

        if (task.status != DownloadTask.TaskStatus.COMPLETED) {
            task.status = DownloadTask.TaskStatus.FAILED;
            Log.w(TAG, "任务最终失败 " + task + ": " + task.errorMessage);
        }

        // 失败任务从 map 中移除（允许重新入队）
        synchronized (lock) {
            if (task.status == DownloadTask.TaskStatus.FAILED) {
                taskMap.remove(task.url);
            }
        }
    }

    /** 取消所有任务。 */
    public void cancelAll() {
        synchronized (lock) {
            for (DownloadTask task : queue) {
                task.status = DownloadTask.TaskStatus.CANCELLED;
            }
            queue.clear();
            taskMap.clear();
        }
        running = false;
        Log.i(TAG, "已取消所有任务");
    }

    /** 获取队列状态。 */
    public String getStatus() {
        synchronized (lock) {
            int pending = 0, completed = 0, failed = 0;
            for (DownloadTask task : taskMap.values()) {
                switch (task.status) {
                    case PENDING: case RUNNING: pending++; break;
                    case COMPLETED: completed++; break;
                    case FAILED: case CANCELLED: failed++; break;
                }
            }
            return String.format("队列: 待处理=%d 已完成=%d 失败=%d 当前=%s",
                    pending, completed, failed,
                    currentTask != null ? "#" + currentTask.id : "无");
        }
    }
}
