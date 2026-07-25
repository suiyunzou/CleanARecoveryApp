package com.example.cleanrecovery.download;

import com.example.cleanrecovery.extractor.ExtractorResult;
import com.example.cleanrecovery.extractor.HlsDownloader;

import java.util.HashMap;
import java.util.Map;

/**
 * 下载器注册表（对应 yt-dlp {@code PROTOCOL_MAP}）。
 *
 * <p>由 {@code SmartProtocolRouter} 调用，根据 {@link ExtractorResult.Format#protocol}
 * 路由到具体 {@link FileDownloader}。</p>
 *
 * <p>注册映射（M1）：</p>
 * <ul>
 *   <li>{@code http} / {@code https} → {@link HttpFD}</li>
 *   <li>{@code m3u8} / {@code m3u8_native} → {@link HlsFD}</li>
 *   <li>{@code dash} → {@link DashFD}（M1 占位，M5 实现）</li>
 *   <li>{@code unknown} → 按 URL 后缀兜底（.m3u8→HlsFD，否则 HttpFD）</li>
 * </ul>
 *
 * <p>消除旧 {@code UniversalDownloadManager#isHlsUrl} 事后猜测，
 * 由 Extractor 在解析时显式填 {@code protocol}。</p>
 */
public final class DownloaderRegistry {

    private static final Map<String, FileDownloader> PROTOCOL_MAP = new HashMap<>();

    static {
        HttpFD httpFD = new HttpFD();
        register("http", httpFD);
        register("https", httpFD);

        HlsFD hlsFD = new HlsFD();
        register("m3u8", hlsFD);
        register("m3u8_native", hlsFD);

        register("dash", new DashFD());
    }

    private DownloaderRegistry() {}

    /** 注册下载器到指定协议。 */
    public static synchronized void register(String protocol, FileDownloader downloader) {
        if (protocol == null || protocol.isEmpty()) return;
        PROTOCOL_MAP.put(protocol, downloader);
    }

    /**
     * 路由下载器。
     *
     * @param format 含 protocol 字段的 format
     * @return 匹配的下载器；protocol=unknown 时按 URL 后缀兜底
     */
    public static FileDownloader route(ExtractorResult.Format format) {
        if (format == null) throw new IllegalArgumentException("format is null");
        String protocol = format.protocol;
        FileDownloader downloader = PROTOCOL_MAP.get(protocol);
        if (downloader != null) return downloader;

        // protocol=unknown 时按 URL 后缀兜底（兼容旧 Extractor 未填 protocol 的情况）
        if (ExtractorResult.PROTOCOL_UNKNOWN.equals(protocol) && format.url != null) {
            if (HlsDownloader.isHlsUrl(format.url)) {
                return PROTOCOL_MAP.get("m3u8");
            }
            return PROTOCOL_MAP.get("http");
        }
        throw new IllegalArgumentException("unsupported protocol: " + protocol);
    }

    /** 是否已注册该协议的下载器。 */
    public static boolean isSupported(String protocol) {
        return PROTOCOL_MAP.containsKey(protocol);
    }
}
