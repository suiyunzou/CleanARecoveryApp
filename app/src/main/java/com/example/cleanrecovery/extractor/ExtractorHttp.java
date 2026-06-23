package com.example.cleanrecovery.extractor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 提取器共享 HTTP 工具（对应 yt-dlp InfoExtractor 的 _download_webpage/_download_json）。
 *
 * <p>提供网页下载、JSON 下载、流式下载等基础方法，统一处理 User-Agent、
 * Referer、Cookie 等请求头，以及重定向、超时、错误码。</p>
 *
 * <p>所有提取器应通过本类发起网络请求，避免重复代码。</p>
 */
public final class ExtractorHttp {

    /** 默认连接超时（ms）。 */
    public static final int CONNECT_TIMEOUT = 15_000;
    /** 默认读取超时（ms）。 */
    public static final int READ_TIMEOUT = 30_000;

    /** 通用浏览器 UA（对应 yt-dlp std_headers）。 */
    public static final String DEFAULT_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private ExtractorHttp() {}

    /** 构建默认请求头。 */
    public static Map<String, String> defaultHeaders() {
        Map<String, String> h = new HashMap<>();
        h.put("User-Agent", DEFAULT_UA);
        h.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        return h;
    }

    /**
     * 下载网页文本（对应 yt-dlp _download_webpage）。
     *
     * @param url 目标 URL
     * @param headers 额外请求头（可含 Referer/Cookie）
     * @return 网页 HTML 文本
     */
    public static String downloadWebpage(String url, Map<String, String> headers) throws IOException {
        return downloadWebpage(url, headers, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    public static String downloadWebpage(String url, Map<String, String> headers,
                                          int connectTimeout, int readTimeout) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, headers, "GET", connectTimeout, readTimeout);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            in.close();
            return buf.toString(StandardCharsets.UTF_8.name());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 下载 JSON 文本（对应 yt-dlp _download_json）。
     *
     * <p>本方法仅返回原始 JSON 字符串，解析由调用方用 org.json 完成。</p>
     */
    public static String downloadJson(String url, Map<String, String> headers) throws IOException {
        Map<String, String> h = new HashMap<>(headers != null ? headers : defaultHeaders());
        h.put("Accept", "application/json, text/plain, */*");
        return downloadWebpage(url, h);
    }

    /**
     * 发送 POST 请求并返回响应文本。
     *
     * @param url 目标 URL
     * @param body 请求体（UTF-8）
     * @param contentType Content-Type 头
     * @param headers 额外请求头
     */
    public static String postString(String url, String body, String contentType,
                                     Map<String, String> headers) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, headers, "POST", CONNECT_TIMEOUT, READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType);
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) throw new IOException("HTTP " + code + " for " + url);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            in.close();
            return buf.toString(StandardCharsets.UTF_8.name());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 获取响应的 Content-Type（用于 GenericExtractor 判断是否为媒体流）。
     */
    public static String headContentType(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, headers, "HEAD", CONNECT_TIMEOUT, READ_TIMEOUT);
            return conn.getContentType();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 下载网页并收集响应中的 Set-Cookie（对应 yt-dlp _get_cookies）。
     *
     * <p>用于在调用 InnerTube API 前获取 YouTube 的会话 cookies
     * （PREF、VISITOR_INFO1_LIVE 等），以绕过反机器人检测。</p>
     *
     * @param url 目标 URL
     * @param headers 额外请求头
     * @return 包含网页文本和合并后 cookie 字符串的结果
     */
    public static CookieResponse downloadWebpageWithCookies(String url, Map<String, String> headers)
            throws IOException {
        return downloadWebpageWithCookies(url, headers, CONNECT_TIMEOUT, READ_TIMEOUT);
    }

    public static CookieResponse downloadWebpageWithCookies(String url, Map<String, String> headers,
                                                             int connectTimeout, int readTimeout)
            throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = openConnection(url, headers, "GET", connectTimeout, readTimeout);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
            in.close();
            String body = buf.toString(StandardCharsets.UTF_8.name());

            // 收集 Set-Cookie 响应头（对应 yt-dlp cookiejar 处理）
            StringBuilder cookieBuilder = new StringBuilder();
            Map<String, List<String>> headerFields = conn.getHeaderFields();
            List<String> setCookies = headerFields.get("Set-Cookie");
            if (setCookies == null) setCookies = headerFields.get("set-cookie");
            if (setCookies != null) {
                for (String sc : setCookies) {
                    // Set-Cookie 格式: name=value; Path=/; Domain=.youtube.com; ...
                    int semi = sc.indexOf(';');
                    String pair = (semi > 0) ? sc.substring(0, semi) : sc;
                    if (cookieBuilder.length() > 0) cookieBuilder.append("; ");
                    cookieBuilder.append(pair.trim());
                }
            }
            return new CookieResponse(body, cookieBuilder.toString());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** downloadWebpageWithCookies 的返回结果。 */
    public static final class CookieResponse {
        public final String body;
        public final String cookies;

        public CookieResponse(String body, String cookies) {
            this.body = body;
            this.cookies = cookies;
        }
    }

    /** 打开并配置连接，处理重定向。 */
    private static HttpURLConnection openConnection(String url, Map<String, String> headers,
                                                     String method, int connectTimeout, int readTimeout)
            throws IOException {
        Map<String, String> h = new HashMap<>(defaultHeaders());
        if (headers != null) h.putAll(headers);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(connectTimeout);
        conn.setReadTimeout(readTimeout);
        conn.setInstanceFollowRedirects(true);
        for (Map.Entry<String, String> e : h.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        return conn;
    }
}
