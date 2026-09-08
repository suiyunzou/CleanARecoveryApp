package com.example.cleanrecovery.extractor;

import android.util.Log;

import com.example.cleanrecovery.download.DownloadProgressCallback;
import com.example.cleanrecovery.ytdlp.CookieJar;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HLS（HTTP Live Streaming）下载器。
 *
 * <p>对应 yt-dlp {@code downloader/hls.py} 的 {@code HlsFD}，实现 m3u8 播放列表
 * 解析与 TS 分片下载合并。</p>
 *
 * <p>核心流程（对应 yt-dlp HlsFD.real_download）：</p>
 * <ol>
 *   <li>下载 m3u8 主播放列表</li>
 *   <li>若是 Master Playlist（含多码率），选择最高码率的子播放列表</li>
 *   <li>解析 Media Playlist，提取所有 TS 分片 URL</li>
 *   <li>依次下载每个 TS 分片，追加到输出文件</li>
 *   <li>支持断点续传：记录已下载分片数到 .ytdl JSON 文件（C1）</li>
 * </ol>
 *
 * <h3>P0 C1 改动：JSON .ytdl 续传元数据</h3>
 * <p>原 {@code .meta} 文件仅存储一个整数（已下载分片数），无法检测播放列表变更
 * （如直播流刷新、VOD 被替换），导致续传时可能追加到错误的分片序列。</p>
 * <p>M1 改用 {@code .ytdl} JSON 文件，存储：</p>
 * <ul>
 *   <li>{@code fragment_index}：下一个待下载分片索引</li>
 *   <li>{@code total_fragments}：分片总数</li>
 *   <li>{@code urls_sha256}：所有分片 URL 的 SHA-256，用于检测播放列表变更</li>
 * </ul>
 * <p>续传时若 {@code urls_sha256} 不匹配，从头下载（保守策略，避免分片错位）。</p>
 *
 * <h3>P0 D1 改动：Cookie 注入</h3>
 * <p>新增 {@link #setCookieJar(CookieJar)}，{@code downloadSegment}/{@code downloadText}
 * 在请求前自动注入对应域名 Cookie，解决 HLS 清流因缺少会话 Cookie 返回 403 的问题。</p>
 *
 * @see <a href="https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/downloader/hls.py">HlsFD</a>
 */
public final class HlsDownloader {

    private static final String TAG = "HlsDownloader";

    /** 连接超时（毫秒）。 */
    private static final int CONNECT_TIMEOUT = 15_000;
    /** 读取超时（毫秒）。 */
    private static final int READ_TIMEOUT = 30_000;
    /** 单分片下载缓冲区。 */
    private static final int BUFFER_SIZE = 8 * 1024;
    /** 续传元数据后缀（C1：原 .meta → 现 .ytdl JSON）。 */
    private static final String META_SUFFIX = ".ytdl";
    /** 临时文件后缀。 */
    private static final String PART_SUFFIX = ".part";

    /** D1：cookiejar 引用（可为 null）。 */
    private CookieJar cookieJar;
    /** per-format 请求头（Referer/Origin/User-Agent 等，可为 null）。 */
    private Map<String, String> formatHeaders;

    private final AtomicBoolean paused = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /** HLS 下载结果。 */
    public static final class HlsResult {
        public final String outputPath;
        public final int totalSegments;
        public final int downloadedSegments;

        HlsResult(String path, int total, int done) {
            this.outputPath = path;
            this.totalSegments = total;
            this.downloadedSegments = done;
        }
    }

    /** D1：设置 cookiejar，后续分片/播放列表请求自动注入对应域名 Cookie。 */
    public void setCookieJar(CookieJar jar) {
        this.cookieJar = jar;
    }

    /**
     * 设置 per-format 请求头（Referer/Origin/User-Agent 等）。
     * 在 {@link #download(String, File, Map, DownloadProgressCallback)} 中自动调用。
     */
    public void setHeaders(Map<String, String> headers) {
        this.formatHeaders = headers;
    }

    /**
     * 下载 HLS 流（含 per-format headers + cookie，由 HlsFD 调用）。
     *
     * <p>设置 headers 后委托给 {@link #download(String, File, DownloadProgressCallback)}。</p>
     */
    public HlsResult download(String m3u8Url, File outFile, Map<String, String> headers,
                                DownloadProgressCallback callback) throws IOException {
        this.formatHeaders = headers;
        return download(m3u8Url, outFile, callback);
    }

    /**
     * 下载 HLS 流到本地文件。
     *
     * @param m3u8Url  m3u8 播放列表 URL
     * @param outFile  输出文件（最终路径，不含 .part 后缀）
     * @param callback 进度回调（可为 null）
     * @return 下载结果
     * @throws IOException 下载失败
     */
    public HlsResult download(String m3u8Url, File outFile, DownloadProgressCallback callback)
            throws IOException {
        if (m3u8Url == null || m3u8Url.isEmpty()) {
            throw new IOException("m3u8 URL is empty");
        }
        if (outFile == null) {
            throw new IOException("output file is null");
        }

        Log.i(TAG, "开始 HLS 下载: " + m3u8Url);

        // 1. 下载并解析 m3u8
        String playlistContent = downloadText(m3u8Url);
        List<String> segmentUrls = resolvePlaylist(m3u8Url, playlistContent);

        if (segmentUrls.isEmpty()) {
            throw new IOException("m3u8 播放列表为空或无法解析: " + m3u8Url);
        }

        Log.i(TAG, "解析到 " + segmentUrls.size() + " 个分片");

        // 2. 准备临时文件与续传元数据（C1：.ytdl JSON）
        File partFile = new File(outFile.getAbsolutePath() + PART_SUFFIX);
        File metaFile = new File(outFile.getAbsolutePath() + META_SUFFIX);
        String urlsHash = computeUrlsSha256(segmentUrls);
        YtdlMeta meta = readYtdlMeta(metaFile);

        // C1：检测播放列表变更 —— urls_sha256 不匹配则从头下载（保守策略，避免分片错位）
        int startSegment = 0;
        if (meta != null && urlsHash.equals(meta.urlsSha256)
                && meta.totalFragments == segmentUrls.size() && partFile.exists()) {
            startSegment = meta.fragmentIndex;
            if (startSegment > segmentUrls.size()) startSegment = 0;
            Log.i(TAG, "续传：从分片 " + startSegment + "/" + segmentUrls.size() + " 继续");
        } else {
            if (meta != null) {
                Log.w(TAG, "播放列表变更或 .part 缺失，从头下载: hashMatch="
                        + (meta != null && urlsHash.equals(meta.urlsSha256)));
            }
            // 清理可能残留的旧 .part 文件（避免追加到错误数据）
            if (partFile.exists()) partFile.delete();
        }

        // 3. 依次下载分片并追加
        FileOutputStream fos = null;
        try {
            // 追加模式：仅当续传有效（startSegment > 0）时
            boolean append = startSegment > 0;
            fos = new FileOutputStream(partFile, append);

            for (int i = startSegment; i < segmentUrls.size(); i++) {
                if (cancelled.get()) {
                    throw new IOException("download cancelled");
                }
                while (paused.get()) {
                    try { Thread.sleep(500); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                    if (cancelled.get()) throw new IOException("download cancelled");
                }

                String segUrl = segmentUrls.get(i);
                int percent = (int) ((i * 100L) / segmentUrls.size());
                if (callback != null) {
                    callback.onProgress(0, 0, 0, percent);
                    callback.onStatusChanged("downloading",
                            "下载分片 " + (i + 1) + "/" + segmentUrls.size());
                }

                downloadSegment(segUrl, fos);
                // C1：每下载一个分片后更新 .ytdl 元数据
                writeYtdlMeta(metaFile, i + 1, segmentUrls.size(), urlsHash);
            }

            // 4. 下载完成：重命名 .part → 最终文件
            fos.flush();
            fos.close();
            fos = null;

            if (outFile.exists()) outFile.delete();
            if (!partFile.renameTo(outFile)) {
                throw new IOException("重命名临时文件失败: " + partFile + " → " + outFile);
            }
            metaFile.delete();

            Log.i(TAG, "HLS 下载完成: " + outFile.getAbsolutePath());
            if (callback != null) {
                callback.onComplete(outFile.getAbsolutePath());
            }
            return new HlsResult(outFile.getAbsolutePath(), segmentUrls.size(), segmentUrls.size());

        } finally {
            if (fos != null) {
                try { fos.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 解析 m3u8 播放列表，返回所有分片 URL。
     *
     * <p>处理 Master Playlist（选择最高码率子列表）与 Media Playlist。</p>
     */
    private List<String> resolvePlaylist(String baseUrl, String content) throws IOException {
        List<String> segments = new ArrayList<>();
        List<String> lines = parseLines(content);

        // 检测是否为 Master Playlist
        String masterStreamUrl = null;
        long bestBandwidth = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                // 解析 BANDWIDTH 属性
                long bw = parseAttributeLong(line, "BANDWIDTH");
                // 下一行是子播放列表 URL
                if (i + 1 < lines.size()) {
                    String subUrl = lines.get(i + 1).trim();
                    if (!subUrl.isEmpty() && !subUrl.startsWith("#")) {
                        if (bw > bestBandwidth) {
                            bestBandwidth = bw;
                            masterStreamUrl = resolveUrl(baseUrl, subUrl);
                        }
                    }
                }
            }
        }

        if (masterStreamUrl != null) {
            // 递归解析子播放列表
            Log.i(TAG, "选择最高码率子列表: BANDWIDTH=" + bestBandwidth + " url=" + masterStreamUrl);
            String subContent = downloadText(masterStreamUrl);
            return resolvePlaylist(masterStreamUrl, subContent);
        }

        // Media Playlist：提取 #EXTINF 后的分片 URL
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("#EXTINF")) {
                // 下一行非注释行为分片 URL
                if (i + 1 < lines.size()) {
                    String segLine = lines.get(i + 1).trim();
                    if (!segLine.isEmpty() && !segLine.startsWith("#")) {
                        segments.add(resolveUrl(baseUrl, segLine));
                    }
                }
            }
        }

        return segments;
    }

    /** 下载单个 TS 分片并追加到输出流。 */
    private void downloadSegment(String segUrl, OutputStream out) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(segUrl).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", ExtractorHttp.DEFAULT_UA);
            conn.setInstanceFollowRedirects(true);
            // 应用 per-format headers（Referer/Origin/User-Agent 覆盖默认 UA）
            applyFormatHeaders(conn);
            // D1：注入对应域名 Cookie（解决 HLS 清流因缺少会话 Cookie 返回 403）
            applyCookie(conn, segUrl);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new IOException("分片下载失败 HTTP " + code + ": " + segUrl);
            }

            InputStream in = conn.getInputStream();
            byte[] buf = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled.get()) throw new IOException("download cancelled");
                out.write(buf, 0, n);
            }
            in.close();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 下载文本内容（m3u8 播放列表）。 */
    private String downloadText(String url) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", ExtractorHttp.DEFAULT_UA);
            conn.setInstanceFollowRedirects(true);
            // 应用 per-format headers
            applyFormatHeaders(conn);
            // D1：注入对应域名 Cookie
            applyCookie(conn, url);

            int code = conn.getResponseCode();
            if (code < 200 || code >= 400) {
                throw new IOException("HTTP " + code + " for " + url);
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            reader.close();
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 应用 per-format 请求头（User-Agent/Referer/Origin 等，覆盖默认 UA）。 */
    private void applyFormatHeaders(HttpURLConnection conn) {
        if (formatHeaders == null || formatHeaders.isEmpty()) return;
        for (Map.Entry<String, String> e : formatHeaders.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (k == null || v == null) continue;
            conn.setRequestProperty(k, v);
        }
    }

    /** D1：从 cookiejar 查询并注入 Cookie 头（若 cookiejar 为 null 则跳过）。 */
    private void applyCookie(HttpURLConnection conn, String url) {
        if (cookieJar == null) return;
        String cookieHeader = cookieJar.cookieHeaderFor(url);
        if (cookieHeader != null && !cookieHeader.isEmpty()) {
            conn.setRequestProperty("Cookie", cookieHeader);
        }
    }

    /** 解析 m3u8 文本为行列表（去除空行）。 */
    private List<String> parseLines(String content) {
        List<String> lines = new ArrayList<>();
        for (String line : content.split("\n")) {
            line = line.trim();
            if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    /** 解析 #EXT-X-STREAM-INF 行中的属性值。 */
    private long parseAttributeLong(String line, String key) {
        // 格式：#EXT-X-STREAM-INF:BANDWIDTH=1280000,CODECS="..."
        int idx = line.indexOf(key + "=");
        if (idx < 0) return -1;
        int start = idx + key.length() + 1;
        int end = start;
        while (end < line.length() && Character.isDigit(line.charAt(end))) end++;
        try {
            return Long.parseLong(line.substring(start, end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 将可能相对的 URL 解析为绝对 URL。 */
    private String resolveUrl(String baseUrl, String maybeRelative) {
        if (maybeRelative.startsWith("http://") || maybeRelative.startsWith("https://")) {
            return maybeRelative;
        }
        try {
            URL base = new URL(baseUrl);
            return new URL(base, maybeRelative).toString();
        } catch (Exception e) {
            return maybeRelative;
        }
    }

    /**
     * C1：读取 .ytdl JSON 续传元数据。
     *
     * <p>格式：{@code {"fragment_index":N,"total_fragments":M,"urls_sha256":"hex"}}</p>
     *
     * @return 元数据对象，文件不存在或解析失败返回 null
     */
    private YtdlMeta readYtdlMeta(File metaFile) {
        if (!metaFile.exists()) return null;
        try (FileInputStream fis = new FileInputStream(metaFile)) {
            byte[] data = new byte[(int) Math.min(metaFile.length(), 8192)];
            int n = fis.read(data);
            if (n <= 0) return null;
            String json = new String(data, 0, n, StandardCharsets.UTF_8).trim();
            JSONObject obj = new JSONObject(json);
            return new YtdlMeta(
                    obj.optInt("fragment_index", 0),
                    obj.optInt("total_fragments", 0),
                    obj.optString("urls_sha256", ""));
        } catch (IOException | JSONException e) {
            Log.w(TAG, "读取 .ytdl 元数据失败，将从头下载: " + e.getMessage());
            return null;
        }
    }

    /**
     * C1：写入 .ytdl JSON 续传元数据。
     *
     * <p>每下载一个分片后调用，记录下一个待下载分片索引 + 播放列表指纹，
     * 用于断点续传时检测播放列表是否变更。</p>
     */
    private void writeYtdlMeta(File metaFile, int fragmentIndex, int totalFragments,
                                 String urlsSha256) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("fragment_index", fragmentIndex);
            obj.put("total_fragments", totalFragments);
            obj.put("urls_sha256", urlsSha256);
            try (FileOutputStream fos = new FileOutputStream(metaFile)) {
                fos.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException | JSONException e) {
            Log.w(TAG, "写入 .ytdl 元数据失败: " + e.getMessage());
        }
    }

    /**
     * C1：计算分片 URL 列表的 SHA-256 指纹。
     *
     * <p>用于检测播放列表变更（如直播流刷新、VOD 被替换）。
     * 续传时若指纹不匹配，从头下载。</p>
     */
    private static String computeUrlsSha256(List<String> urls) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (String u : urls) {
                sb.append(u).append('\n');
            }
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JRE 必备算法，理论上不会抛此异常
            return "sha256-unavailable";
        }
    }

    /** C1：.ytdl JSON 续传元数据。 */
    private static final class YtdlMeta {
        final int fragmentIndex;
        final int totalFragments;
        final String urlsSha256;

        YtdlMeta(int fragmentIndex, int totalFragments, String urlsSha256) {
            this.fragmentIndex = fragmentIndex;
            this.totalFragments = totalFragments;
            this.urlsSha256 = urlsSha256 != null ? urlsSha256 : "";
        }
    }

    /** 暂停下载。 */
    public void pause() { paused.set(true); }

    /** 继续下载。 */
    public void resume() { paused.set(false); }

    /** 取消下载。 */
    public void cancel() { cancelled.set(true); paused.set(false); }

    /** 是否已暂停。 */
    public boolean isPaused() { return paused.get(); }

    /** 是否已取消。 */
    public boolean isCancelled() { return cancelled.get(); }

    /**
     * 判断 URL 是否为 HLS 播放列表。
     *
     * <p>检测依据：URL 路径以 .m3u8 或 .m3u 结尾，或 Content-Type 为
     * application/vnd.apple.mpegurl / application/x-mpegURL。</p>
     */
    public static boolean isHlsUrl(String url) {
        if (url == null) return false;
        // 去除查询参数与片段后再判断扩展名
        int q = url.indexOf('?');
        if (q > 0) url = url.substring(0, q);
        int h = url.indexOf('#');
        if (h > 0) url = url.substring(0, h);
        String lower = url.toLowerCase();
        return lower.endsWith(".m3u8") || lower.endsWith(".m3u");
    }

    /**
     * 判断 Content-Type 是否为 HLS。
     */
    public static boolean isHlsContentType(String contentType) {
        if (contentType == null) return false;
        String lower = contentType.toLowerCase();
        return lower.contains("mpegurl") || lower.contains("m3u8");
    }
}
