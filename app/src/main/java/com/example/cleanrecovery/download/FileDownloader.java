package com.example.cleanrecovery.download;

import com.example.cleanrecovery.extractor.ExtractorResult;

import java.io.File;

/**
 * 下载器接口（对应 yt-dlp {@code FileDownloader}）。
 *
 * <p>由 {@code DownloaderRegistry} 注册，{@code SmartProtocolRouter} 按
 * {@link ExtractorResult.Format#protocol} 路由到具体实现。</p>
 *
 * <p>实现类需线程安全（可能被多任务并发调用），且支持取消/暂停（通过
 * {@link DownloadProgressCallback} 的状态回调间接表达）。</p>
 */
public interface FileDownloader {

    /**
     * 下载指定 format 到目标文件。
     *
     * @param format   含直链 + protocol + 每 format 专属 httpHeaders
     * @param outFile  最终输出文件路径（不含 .part 后缀）
     * @param callback 进度回调（可为 null）
     * @throws Exception 下载失败（IO / 协议 / 取消等）
     */
    void download(ExtractorResult.Format format, File outFile, DownloadProgressCallback callback)
            throws Exception;

    /** 该下载器支持的协议标识（用于 {@code DownloaderRegistry} 注册）。 */
    String supportedProtocol();
}
