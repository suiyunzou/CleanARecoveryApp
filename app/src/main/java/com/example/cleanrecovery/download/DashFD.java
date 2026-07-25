package com.example.cleanrecovery.download;

import com.example.cleanrecovery.extractor.ExtractorResult;

import java.io.File;

/**
 * DASH 下载器（对应 yt-dlp {@code DashFD}）。
 *
 * <p><b>M1 占位</b>：仅声明接口，实际 MPD 解析在 M5 实现（直译
 * {@code 参考/yt-dlp/yt_dlp/downloader/dash.py}）。M1 期间若 Extractor
 * 返回 {@code protocol="dash"} 的 format，路由器将抛出明确异常，
 * 由调用方降级到 HLS 兜底或提示用户。</p>
 */
public final class DashFD implements FileDownloader {

    public static final String PROTOCOL_DASH = "dash";

    @Override
    public void download(ExtractorResult.Format format, File outFile, DownloadProgressCallback callback)
            throws Exception {
        throw new UnsupportedOperationException(
                "DASH (MPD) 下载在 M5 实现。当前 format=" + format.formatId + " url=" + format.url);
    }

    @Override
    public String supportedProtocol() {
        return PROTOCOL_DASH;
    }
}
