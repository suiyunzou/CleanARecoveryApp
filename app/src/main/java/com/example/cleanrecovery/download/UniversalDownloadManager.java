package com.example.cleanrecovery.download;

import android.util.Log;

import com.example.cleanrecovery.extractor.HlsDownloader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全网下载核心管理器 —— 借鉴 yt-dlp HttpFD 实现的可恢复下载。
 *
 * <p>基于 yt-dlp 源码分析（yt-dlp-master/yt_dlp/downloader/http.py）集成以下关键机制：</p>
 * <ul>
 *   <li><b>断点续传</b>：使用 {@code .part} 临时文件 + Range/Content-Range 协商
 *       （对应 yt-dlp HttpFD.establish_connection，http.py#L80-L180）</li>
 *   <li><b>重试机制</b>：网络错误（5xx/IO）自动重试，对应 yt-dlp RetryDownload 模式
 *       （http.py#L140-L170）</li>
 *   <li><b>动态块大小</b>：根据上一块下载耗时调整下一块大小，平衡内存与效率
 *       （对应 yt-dlp best_block_size，http.py#L298）</li>
 *   <li><b>限速支持</b>：可选 {@code rateLimitBps} 限制下载速度
 *       （对应 yt-dlp slow_down，http.py#L289）</li>
 *   <li><b>进度钩子</b>：通过 {@link DownloadProgressCallback} 解耦 UI
 *       （对应 yt-dlp _hook_progress，common.py#L488）</li>
 * </ul>
 *
 * <p>本类为纯 Java 逻辑，不依赖 Android UI 组件，便于单元测试。
 * UI 层通过实现 {@link DownloadProgressCallback} 接收进度更新。</p>
 */
public final class UniversalDownloadManager {

    private static final String TAG = "UniversalDLManager";

    /** 连接超时（毫秒）。 */
    public static final int CONNECT_TIMEOUT = 15_000;
    /** 读取超时（毫秒）。 */
    public static final int READ_TIMEOUT = 30_000;
    /** 最小缓冲块大小（字节）。 */
    public static final int MIN_BLOCK_SIZE = 4 * 1024;
    /** 最大缓冲块大小（字节）。 */
    public static final int MAX_BLOCK_SIZE = 64 * 1024;
    /** 默认初始块大小（字节）。 */
    public static final int DEFAULT_BLOCK_SIZE = 8 * 1024;
    /** 最大重试次数。 */
    public static final int MAX_RETRIES = 3;
    /** 重试基础延迟（毫秒），实际延迟 = base * attempt。 */
    public static final long RETRY_BASE_DELAY_MS = 1_000;

    /** 临时文件后缀。 */
    public static final String PART_SUFFIX = ".part";

    private final int connectTimeout;
    private final int readTimeout;
    private final int maxRetries;
    private final long rateLimitBps;

    /** 暂停标志（原子操作，线程安全）。 */
    private final AtomicBoolean paused = new AtomicBoolean(false);
    /** 取消标志。 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** 构造器，使用默认参数。 */
    public UniversalDownloadManager() {
        this(CONNECT_TIMEOUT, READ_TIMEOUT, MAX_RETRIES, 0);
    }

    /**
     * 自定义参数构造器。
     *
     * @param connectTimeout 连接超时（毫秒）
     * @param readTimeout    读取超时（毫秒）
     * @param maxRetries     最大重试次数
     * @param rateLimitBps   限速（字节/秒），0 表示不限速
     */
    public UniversalDownloadManager(int connectTimeout, int readTimeout, int maxRetries, long rateLimitBps) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.maxRetries = maxRetries;
        this.rateLimitBps = rateLimitBps;
    }

    /**
     * 下载文件到指定路径（支持断点续传）。
     *
     * <p>流程对应 yt-dlp HttpFD.real_download：</p>
     * <ol>
     *   <li>检测 {@code .part} 文件大小作为 resume_len</li>
     *   <li>发送 Range 请求</li>
     *   <li>校验 Content-Range 响应头</li>
     *   <li>分块下载，动态调整 block_size</li>
     *   <li>完成后重命名 {@code .part} → 最终文件名</li>
     * </ol>
     *
     * @param fileUrl  直链 URL
     * @param outFile  目标文件（最终路径，不含 .part 后缀）
     * @param callback 进度回调（可为 null）
     * @throws IOException 下载失败
     */
    public void download(String fileUrl, File outFile, DownloadProgressCallback callback)
            throws IOException {
        if (fileUrl == null || fileUrl.isEmpty()) {
            throw new IOException("URL is empty");
        }
        if (outFile == null) {
            throw new IOException("output file is null");
        }

        // 最终文件已存在且非续传场景 → 直接完成
        if (outFile.exists() && outFile.length() > 0 && !hasPartFile(outFile)) {
            if (callback != null) {
                callback.onComplete(outFile.getAbsolutePath());
            }
            return;
        }

        File partFile = new File(outFile.getAbsolutePath() + PART_SUFFIX);
        // 检测 .part 文件以确定续传起点（对应 yt-dlp http.py#L82）
        long resumeLen = partFile.exists() ? partFile.length() : 0;

        int attempt = 0;
        IOException lastError = null;

        // 重试循环（对应 yt-dlp RetryDownload 机制）
        while (attempt < maxRetries) {
            attempt++;
            try {
                if (cancelled.get()) {
                    throw new IOException("download cancelled");
                }
                notifyStatus(callback, "downloading", attempt > 1
                        ? "重试中 (" + attempt + "/" + maxRetries + ")" : "下载中");

                doDownloadWithResume(fileUrl, partFile, outFile, resumeLen, callback);
                // 下载成功
                return;

            } catch (RetryableDownloadException e) {
                // 可重试错误：记录后继续循环
                lastError = e;
                Log.w(TAG, "attempt " + attempt + " failed: " + e.getMessage());
                // 更新 resumeLen 为当前 .part 文件大小（可能已部分下载）
                resumeLen = partFile.exists() ? partFile.length() : 0;
                if (attempt < maxRetries) {
                    sleepBackoff(attempt);
                }
            } catch (IOException e) {
                // 不可重试错误：直接抛出
                throw e;
            }
        }

        // 重试耗尽
        throw lastError != null ? lastError : new IOException("download failed after " + maxRetries + " retries");
    }

    /**
     * 下载 HLS 流（m3u8 播放列表）。
     *
     * <p>对应 yt-dlp 的 HlsFD，委托给 {@link HlsDownloader} 解析播放列表、
     * 下载所有 TS 分片并合并为单个文件。</p>
     *
     * <p>与 {@link #download} 不同，HLS 下载不支持 HTTP Range 续传（分片本身
     * 即最小重试单元），但通过 {@code .meta} 文件记录已下载分片数实现断点续传。</p>
     *
     * @param m3u8Url  m3u8 播放列表 URL
     * @param outFile  输出文件（最终路径，不含 .part 后缀）
     * @param callback 进度回调（可为 null）
     * @throws IOException 下载失败
     */
    public void downloadHls(String m3u8Url, File outFile, DownloadProgressCallback callback)
            throws IOException {
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            throw new IOException("m3u8 URL is empty");
        }
        if (outFile == null) {
            throw new IOException("output file is null");
        }

        HlsDownloader hls = new HlsDownloader();
        try {
            hls.download(m3u8Url, outFile, callback);
        } catch (IOException e) {
            Log.e(TAG, "HLS 下载失败: " + e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 判断 URL 是否为 HLS 流。
     *
     * @return true 表示应使用 {@link #downloadHls} 而非 {@link #download}
     */
    public boolean isHlsUrl(String url) {
        return HlsDownloader.isHlsUrl(url);
    }

    /**
     * 智能下载：根据 URL 类型自动选择 HLS 或普通下载。
     *
     * @param url      媒体 URL（可能是直链或 m3u8）
     * @param outFile  输出文件
     * @param callback 进度回调（可为 null）
     * @throws IOException 下载失败
     */
    public void downloadSmart(String url, File outFile, DownloadProgressCallback callback)
            throws IOException {
        if (isHlsUrl(url)) {
            downloadHls(url, outFile, callback);
        } else {
            download(url, outFile, callback);
        }
    }

    /**
     * 执行单次带续传的下载。
     *
     * <p>对应 yt-dlp HttpFD 的 establish_connection + download 内部函数。</p>
     */
    private void doDownloadWithResume(String fileUrl, File partFile, File outFile,
                                       long resumeLen, DownloadProgressCallback callback) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(fileUrl).openConnection();
            conn.setConnectTimeout(connectTimeout);
            conn.setReadTimeout(readTimeout);
            conn.setRequestMethod("GET");
            // 禁用自动压缩（对应 yt-dlp http.py#L44）
            conn.setRequestProperty("Accept-Encoding", "identity");

            // YouTube googlevideo.com 链接需要特定请求头避免 403/409
            String lowerUrl = fileUrl.toLowerCase(Locale.ROOT);
            if (lowerUrl.contains("googlevideo.com")) {
                conn.setRequestProperty("User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                conn.setRequestProperty("Referer", "https://www.youtube.com/");
                conn.setRequestProperty("Origin", "https://www.youtube.com");
                conn.setRequestProperty("Accept", "*/*");
            }

            // 发送 Range 请求（对应 yt-dlp http.py#L112）
            boolean hasRange = resumeLen > 0;
            if (hasRange) {
                conn.setRequestProperty("Range", "bytes=" + resumeLen + "-");
            }

            conn.connect();
            int code = conn.getResponseCode();

            // HTTP 416：Range 不可满足（对应 yt-dlp http.py#L140）
            if (code == 416) {
                if (resumeLen > 0) {
                    // 可能文件已完整下载，重新无 Range 请求验证
                    conn.disconnect();
                    conn = (HttpURLConnection) new URL(fileUrl).openConnection();
                    conn.setConnectTimeout(connectTimeout);
                    conn.setReadTimeout(readTimeout);
                    conn.setRequestProperty("Accept-Encoding", "identity");
                    conn.connect();
                    code = conn.getResponseCode();
                    long contentLen = parseContentLength(conn);
                    if (contentLen > 0 && Math.abs(contentLen - resumeLen) < 100) {
                        // 文件已完整下载（对应 yt-dlp http.py#L155 的 ±100 字节容差）
                        partFile.renameTo(outFile);
                        notifyComplete(callback, outFile.getAbsolutePath());
                        return;
                    }
                }
                // 真正的 416 错误：重置从头下载
                resumeLen = 0;
                hasRange = false;
            }

            // 5xx 错误：可重试
            if (code >= 500 && code < 600) {
                throw new RetryableDownloadException("HTTP " + code);
            }

            // 其他非 2xx 错误：不可重试
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code);
            }

            // 校验 Content-Range（对应 yt-dlp http.py#L120-L135）
            long contentLen = parseContentLength(conn);
            long totalLen;
            boolean resumeValid = false;

            if (hasRange) {
                String contentRange = conn.getHeaderField("Content-Range");
                if (contentRange != null && contentRange.startsWith("bytes " + resumeLen + "-")) {
                    // Content-Range 起始字节匹配，续传有效
                    resumeValid = true;
                    // 从 Content-Range 提取总大小：bytes start-end/total
                    int slashIdx = contentRange.indexOf('/');
                    if (slashIdx >= 0) {
                        try {
                            totalLen = Long.parseLong(contentRange.substring(slashIdx + 1).trim());
                        } catch (NumberFormatException e) {
                            totalLen = resumeLen + contentLen;
                        }
                    } else {
                        totalLen = resumeLen + contentLen;
                    }
                } else {
                    // 服务器不支持续传，从头下载
                    resumeLen = 0;
                    totalLen = contentLen;
                }
            } else {
                totalLen = contentLen;
            }

            // 打开输出流：续传追加，否则覆盖（对应 yt-dlp http.py#L83 ctx.open_mode）
            String openMode = resumeValid ? "rw" : "rw";
            long fileOffset = resumeValid ? resumeLen : 0;
            if (!resumeValid && partFile.exists()) {
                partFile.delete();
            }

            RandomAccessFileCompat raf = new RandomAccessFileCompat(partFile, openMode);
            raf.seek(fileOffset);

            InputStream in = conn.getInputStream();
            try {
                streamDownload(in, raf, resumeLen, totalLen, callback);
                raf.close();
            } finally {
                try { in.close(); } catch (IOException ignored) {}
            }

            // 下载完成：重命名 .part → 最终文件名（对应 yt-dlp try_rename）
            if (!partFile.renameTo(outFile)) {
                // 某些设备 rename 跨挂载点失败，回退到复制
                copyFile(partFile, outFile);
                partFile.delete();
            }

            notifyComplete(callback, outFile.getAbsolutePath());

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 流式下载核心循环（对应 yt-dlp HttpFD.download 内部函数）。
     *
     * <p>包含：动态块大小调整、限速、暂停检测、进度回调。</p>
     */
    private void streamDownload(InputStream in, RandomAccessFileCompat out,
                                 long resumeLen, long totalLen,
                                 DownloadProgressCallback callback) throws IOException {
        int blockSize = DEFAULT_BLOCK_SIZE;
        long downloaded = resumeLen;
        long startTime = System.currentTimeMillis();
        long lastProgressTime = startTime;
        long lastProgressBytes = downloaded;

        byte[] buf = new byte[MAX_BLOCK_SIZE];

        while (true) {
            // 检测取消
            if (cancelled.get()) {
                throw new IOException("download cancelled");
            }
            // 检测暂停（对应 yt-dlp 无此机制，为本项目增强）
            while (paused.get() && !cancelled.get()) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("download interrupted");
                }
            }
            if (cancelled.get()) {
                throw new IOException("download cancelled");
            }

            // 限制单次读取量到当前 blockSize（动态调整）
            int readLen = Math.min(blockSize, buf.length);
            int n;
            try {
                n = in.read(buf, 0, readLen);
            } catch (IOException e) {
                // 网络读取错误：可重试
                throw new RetryableDownloadException("read failed: " + e.getMessage(), e);
            }

            if (n <= 0) {
                break; // EOF
            }

            out.write(buf, 0, n);
            downloaded += n;

            // 限速（对应 yt-dlp slow_down，http.py#L289）
            if (rateLimitBps > 0) {
                long elapsed = System.currentTimeMillis() - lastProgressTime;
                long expected = (downloaded - lastProgressBytes) * 1000 / rateLimitBps;
                if (expected > elapsed) {
                    try {
                        Thread.sleep(expected - elapsed);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            // 进度回调（对应 yt-dlp _hook_progress）
            long now = System.currentTimeMillis();
            if (callback != null && now - lastProgressTime > 200) {
                long elapsed = now - startTime;
                long speed = elapsed > 0
                        ? (downloaded - resumeLen) * 1000 / elapsed
                        : 0;
                int percent = totalLen > 0 ? (int) (downloaded * 100 / totalLen) : -1;
                callback.onProgress(downloaded, totalLen, speed, percent);
                lastProgressTime = now;
                lastProgressBytes = downloaded;
            }

            // 动态块大小（对应 yt-dlp best_block_size，http.py#L298）
            blockSize = bestBlockSize(blockSize, n, now - lastProgressTime);
        }

        // 最终进度回调
        if (callback != null) {
            long elapsed = System.currentTimeMillis() - startTime;
            long speed = elapsed > 0 ? (downloaded - resumeLen) * 1000 / elapsed : 0;
            int percent = totalLen > 0 ? 100 : -1;
            callback.onProgress(downloaded, totalLen, speed, percent);
        }
    }

    /**
     * 动态计算下一块大小（对应 yt-dlp best_block_size）。
     *
     * <p>策略：如果上一块下载顺利且快速，增大块大小；否则减小。
     * 范围限制在 [{@link #MIN_BLOCK_SIZE}, {@link #MAX_BLOCK_SIZE}]。</p>
     *
     * @param currentBlock 当前块大小
     * @param lastReadSize 上次实际读取字节数
     * @param lastReadMs   上次读取耗时（毫秒）
     * @return 调整后的块大小
     */
    static int bestBlockSize(int currentBlock, int lastReadSize, long lastReadMs) {
        if (lastReadSize <= 0) return currentBlock;
        // 上次读取完整且快速 → 增大
        if (lastReadSize >= currentBlock * 0.9 && lastReadMs < 50) {
            return Math.min(currentBlock * 2, MAX_BLOCK_SIZE);
        }
        // 上次读取慢或不足 → 减小
        if (lastReadMs > 200 || lastReadSize < currentBlock * 0.5) {
            return Math.max(currentBlock / 2, MIN_BLOCK_SIZE);
        }
        return currentBlock;
    }

    /**
     * 清理文件名中的非法字符（对应 cobalt filename 字段处理）。
     *
     * @param name 原始文件名
     * @return 清理后的安全文件名
     */
    public static String sanitizeFileName(String name) {
        if (name == null || name.isEmpty()) return null;
        // 替换 Windows/Android 文件名非法字符
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        // 限制长度（Android 文件系统通常 255 字节限制）
        if (cleaned.length() > 200) {
            int dotIdx = cleaned.lastIndexOf('.');
            if (dotIdx > 0) {
                String ext = cleaned.substring(dotIdx);
                cleaned = cleaned.substring(0, 200 - ext.length()) + ext;
            } else {
                cleaned = cleaned.substring(0, 200);
            }
        }
        return cleaned;
    }

    /** 暂停下载。 */
    public void pause() {
        paused.set(true);
    }

    /** 继续下载。 */
    public void resume() {
        paused.set(false);
    }

    /** 取消下载。 */
    public void cancel() {
        cancelled.set(true);
        paused.set(false);
    }

    /** 是否已暂停。 */
    public boolean isPaused() {
        return paused.get();
    }

    /** 是否已取消。 */
    public boolean isCancelled() {
        return cancelled.get();
    }

    // ===== 内部工具方法 =====

    private boolean hasPartFile(File outFile) {
        return new File(outFile.getAbsolutePath() + PART_SUFFIX).exists();
    }

    private long parseContentLength(HttpURLConnection conn) {
        String len = conn.getHeaderField("Content-Length");
        if (len == null || len.isEmpty()) return -1;
        try {
            return Long.parseLong(len.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void notifyStatus(DownloadProgressCallback cb, String status, String message) {
        if (cb != null) cb.onStatusChanged(status, message);
    }

    private void notifyComplete(DownloadProgressCallback cb, String path) {
        if (cb != null) cb.onComplete(path);
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_DELAY_MS * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void copyFile(File src, File dst) throws IOException {
        try (FileInputStream fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {
            byte[] buf = new byte[DEFAULT_BLOCK_SIZE];
            int n;
            while ((n = fis.read(buf)) > 0) {
                fos.write(buf, 0, n);
            }
        }
    }

    /**
     * 可重试下载异常（对应 yt-dlp RetryDownload）。
     *
     * <p>仅用于标记可重试的网络错误，不应直接暴露给用户。</p>
     */
    static final class RetryableDownloadException extends IOException {
        RetryableDownloadException(String message) {
            super(message);
        }
        RetryableDownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * RandomAccessFile 简化封装（避免直接依赖 java.io.RandomAccessFile 的 API 差异）。
     *
     * <p>支持 seek 定位 + 追加写入，用于断点续传场景。</p>
     */
    private static final class RandomAccessFileCompat {
        private final java.io.RandomAccessFile raf;

        RandomAccessFileCompat(File file, String mode) throws IOException {
            this.raf = new java.io.RandomAccessFile(file, mode);
        }

        void seek(long pos) throws IOException {
            raf.seek(pos);
        }

        void write(byte[] buf, int offset, int len) throws IOException {
            raf.write(buf, offset, len);
        }

        void close() throws IOException {
            raf.close();
        }
    }
}
