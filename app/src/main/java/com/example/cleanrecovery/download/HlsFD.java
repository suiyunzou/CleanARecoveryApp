package com.example.cleanrecovery.download;

import com.example.cleanrecovery.extractor.ExtractorResult;
import com.example.cleanrecovery.extractor.HlsDownloader;
import com.example.cleanrecovery.ytdlp.CookieJar;

import java.io.File;
import java.util.Map;

/**
 * HLS 下载器（对应 yt-dlp {@code HlsFD}）。
 *
 * <p>薄封装 {@link HlsDownloader}，从 {@link ExtractorResult.Format} 提取 URL +
 * per-format httpHeaders + 全局 cookiejar 注入。M1 暂不实现 AES-128 解密与
 * #EXT-X-MAP（M4 处理）。</p>
 */
public final class HlsFD implements FileDownloader {

    public static final String PROTOCOL_M3U8 = "m3u8";
    public static final String PROTOCOL_M3U8_NATIVE = "m3u8_native";

    private final HlsDownloader downloader;

    public HlsFD() {
        this(new HlsDownloader());
    }

    public HlsFD(HlsDownloader downloader) {
        this.downloader = downloader != null ? downloader : new HlsDownloader();
    }

    @Override
    public void download(ExtractorResult.Format format, File outFile, DownloadProgressCallback callback)
            throws Exception {
        if (format == null || format.url == null) {
            throw new IllegalArgumentException("format or url is null");
        }
        // D1：注入全局 cookiejar（解决 HLS 清流因缺少会话 Cookie 返回 403）
        CookieJar jar = UniversalDownloadManager.getGlobalCookieJar();
        if (jar != null) {
            downloader.setCookieJar(jar);
        }
        // 传递 per-format headers（Referer/Origin/User-Agent 等）
        Map<String, String> headers = format.httpHeaders;
        downloader.download(format.url, outFile, headers, callback);
    }

    @Override
    public String supportedProtocol() {
        return PROTOCOL_M3U8;
    }

    public HlsDownloader downloader() {
        return downloader;
    }
}
