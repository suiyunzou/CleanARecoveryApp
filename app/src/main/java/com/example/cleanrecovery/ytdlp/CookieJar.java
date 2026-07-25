package com.example.cleanrecovery.ytdlp;

import java.util.List;

/**
 * Cookie 容器接口（对应 yt-dlp {@code YoutubeDLCookieJar}）。
 *
 * <p>提供按 URL 域名查询 cookie 的能力，由 {@link PersistentCookieJar} 实现。</p>
 *
 * <p>注入点：</p>
 * <ul>
 *   <li>{@code ExtractorHttp}：Extractor 发起 API 请求前注入对应域名 cookie</li>
 *   <li>{@code UniversalDownloadManager}：下载直链前注入（解决 D1：YouTube googlevideo 需要 WebView cookie）</li>
 *   <li>{@code HlsDownloader}：下载 m3u8 与分片前注入</li>
 * </ul>
 */
public interface CookieJar {

    /** 单条 cookie（对齐 yt-dlp HttpCookie 字段子集）。 */
    final class Cookie {
        public final String name;
        public final String value;
        public final String domain;
        public final String path;
        public final boolean secure;
        public final boolean httpOnly;

        public Cookie(String name, String value, String domain, String path,
                      boolean secure, boolean httpOnly) {
            this.name = name;
            this.value = value;
            this.domain = domain;
            this.path = path;
            this.secure = secure;
            this.httpOnly = httpOnly;
        }

        /** 是否匹配该 URL 域名（粗匹配：domain 等于 host 或 host 以 .domain 结尾）。 */
        public boolean matches(String host) {
            if (host == null || domain == null) return false;
            String h = host.toLowerCase();
            String d = domain.toLowerCase();
            if (d.startsWith(".")) d = d.substring(1);
            return h.equals(d) || h.endsWith("." + d);
        }
    }

    /**
     * 获取匹配 URL 的所有 cookie，拼成 {@code name=value; name2=value2} 形式。
     *
     * @param requestUrl 完整请求 URL（含 scheme + host + path）
     * @return cookie 字符串，无匹配时返回空串
     */
    String cookieHeaderFor(String requestUrl);

    /** 获取匹配 URL 的所有 cookie 列表。 */
    List<Cookie> cookiesFor(String requestUrl);

    /** 注入一条 cookie（覆盖同名同域同路径）。 */
    void putCookie(Cookie cookie);

    /** 批量注入。 */
    void putCookies(List<Cookie> cookies);

    /** 清空所有 cookie。 */
    void clear();

    /** 是否有任何 cookie（用于判断是否已登录）。 */
    boolean isEmpty();
}
