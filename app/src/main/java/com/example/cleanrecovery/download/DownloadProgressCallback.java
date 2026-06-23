package com.example.cleanrecovery.download;

/**
 * 全网下载进度钩子接口。
 *
 * <p>借鉴 yt-dlp 的 progress_hooks 机制（yt_dlp/downloader/common.py#L488），
 * 将下载逻辑与 UI 更新解耦。下载管理器在关键节点回调此接口，
 * UI 层实现此接口即可接收进度更新，无需侵入下载代码。</p>
 *
 * <p>回调方法均在主线程执行，实现者可直接更新 View。</p>
 */
public interface DownloadProgressCallback {

    /**
     * 下载进度更新。
     *
     * @param downloadedBytes 已下载字节数
     * @param totalBytes      总字节数（未知时为 -1）
     * @param speedBps        当前速度（字节/秒）
     * @param percent         进度百分比（0-100，总大小未知时为 -1）
     */
    void onProgress(long downloadedBytes, long totalBytes, long speedBps, int percent);

    /**
     * 下载状态变更通知。
     *
     * @param status 新状态：resolving / downloading / paused / completed / failed
     * @param message 人类可读的状态描述
     */
    void onStatusChanged(String status, String message);

    /**
     * 下载完成。
     *
     * @param savedPath 文件保存路径
     */
    void onComplete(String savedPath);

    /**
     * 下载失败。
     *
     * @param errorCode 错误码（如 http_404, network_error, parse_failed）
     * @param message   错误描述
     */
    void onError(String errorCode, String message);
}
