package com.example.cleanrecovery.download;

import com.example.cleanrecovery.extractor.ExtractorResult;

import java.io.File;
import java.util.Map;

/**
 * HTTP/HTTPS 下载器（对应 yt-dlp {@code HttpFD}）。
 *
 * <p>薄封装 {@link UniversalDownloadManager}，从 {@link ExtractorResult.Format}
 * 提取 URL + per-format httpHeaders，注入到下载请求。</p>
 *
 * <p>注入链：format.httpHeaders → UniversalDownloadManager → HttpURLConnection
 * （由 {@code PersistentCookieJar} 在请求前自动追加 Cookie 头）。</p>
 */
public final class HttpFD implements FileDownloader {

    private static final String PROTOCOL_HTTP = "http";
    private static final String PROTOCOL_HTTPS = "https";

    private final UniversalDownloadManager manager;

    public HttpFD() {
        this(new UniversalDownloadManager());
    }

    public HttpFD(UniversalDownloadManager manager) {
        this.manager = manager != null ? manager : new UniversalDownloadManager();
    }

    @Override
    public void download(ExtractorResult.Format format, File outFile, DownloadProgressCallback callback)
            throws Exception {
        if (format == null || format.url == null) {
            throw new IllegalArgumentException("format or url is null");
        }
        Map<String, String> headers = format.httpHeaders;
        manager.download(format.url, outFile, headers, callback);
    }

    @Override
    public String supportedProtocol() {
        return PROTOCOL_HTTP; // 注册时同时映射 https
    }

    /** UniversalDownloadManager 实例（供外部 pause/resume/cancel 控制）。 */
    public UniversalDownloadManager manager() {
        return manager;
    }
}
