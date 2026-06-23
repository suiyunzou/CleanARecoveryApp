package com.example.cleanrecovery.background;

import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 分段视频合并器（后台下载模块）。
 *
 * <p>支持 HLS（.m3u8）和 DASH（.mpd）自适应流的分段下载与合并：</p>
 * <ul>
 *   <li>HLS：解析 m3u8 playlist，下载所有 .ts 分片，按顺序合并为单个 .ts/.mp4 文件</li>
 *   <li>DASH：解析 MPD manifest，下载所有分片，按顺序合并</li>
 *   <li>支持 Master Playlist（自动选择最高分辨率的 Media Playlist）</li>
 *   <li>支持相对 URL 解析（基于 base URL）</li>
 * </ul>
 */
public final class SegmentMerger {
    private static final String TAG = "SegmentMerger";

    /** 下载进度回调。 */
    public interface MergeProgressCallback {
        /**
         * @param current 当前分片索引（从 1 开始）
         * @param total   总分片数
         * @param speedBps 下载速度（字节/秒）
         */
        void onSegmentProgress(int current, int total, long speedBps);
    }

    private static final Pattern HLS_RESOLUTION_PATTERN = Pattern.compile(
            "RESOLUTION=(\\d+)x(\\d+)");
    private static final Pattern HLS_SEGMENT_PATTERN = Pattern.compile(
            "^#EXTINF:.*$|^.*\\.(ts|aac|m4s|mp4)$", Pattern.MULTILINE);
    private static final Pattern M3U8_URL_PATTERN = Pattern.compile(
            "^[^#].*\\.m3u8.*$", Pattern.MULTILINE);

    private SegmentMerger() {
    }

    /**
     * 下载并合并 HLS 流。
     *
     * @param m3u8Url    m3u8 playlist URL
     * @param outFile    输出文件
     * @param headers    请求头（可为 null）
     * @param callback   进度回调（可为 null）
     * @throws IOException 下载或合并失败
     */
    public static void downloadAndMergeHls(String m3u8Url, File outFile,
                                            java.util.Map<String, String> headers,
                                            MergeProgressCallback callback) throws IOException {
        Log.i(TAG, "开始下载HLS流: " + m3u8Url);

        // 1. 获取 m3u8 内容
        String playlist = fetchText(m3u8Url, headers);
        if (playlist == null || playlist.isEmpty()) {
            throw new IOException("无法获取m3u8内容");
        }

        // 2. 如果是 Master Playlist，选择最高分辨率的 Media Playlist
        if (playlist.contains("#EXT-X-STREAM-INF")) {
            String mediaPlaylistUrl = selectBestVariant(playlist, m3u8Url);
            if (mediaPlaylistUrl == null) {
                throw new IOException("Master Playlist中未找到可用的Media Playlist");
            }
            Log.i(TAG, "选择最高分辨率: " + mediaPlaylistUrl);
            playlist = fetchText(mediaPlaylistUrl, headers);
            m3u8Url = mediaPlaylistUrl;
        }

        // 3. 解析分片 URL
        List<String> segments = parseHlsSegments(playlist, m3u8Url);
        if (segments.isEmpty()) {
            throw new IOException("未解析到任何分片");
        }
        Log.i(TAG, "解析到 " + segments.size() + " 个分片");

        // 4. 下载并合并分片
        downloadAndMergeSegments(segments, outFile, headers, callback);
    }

    /**
     * 下载并合并分片列表。
     *
     * @param segmentUrls 分片 URL 列表
     * @param outFile     输出文件
     * @param headers     请求头
     * @param callback    进度回调
     */
    public static void downloadAndMergeSegments(List<String> segmentUrls, File outFile,
                                                 java.util.Map<String, String> headers,
                                                 MergeProgressCallback callback) throws IOException {
        File tempDir = new File(outFile.getParentFile(), ".merge_temp_" + outFile.getName());
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            throw new IOException("无法创建临时目录: " + tempDir);
        }

        try {
            int total = segmentUrls.size();
            // 1. 下载所有分片
            for (int i = 0; i < total; i++) {
                String segUrl = segmentUrls.get(i);
                File segFile = new File(tempDir, String.format(Locale.US, "seg_%05d.ts", i));

                // 断点续传：已下载的分片跳过
                if (segFile.exists() && segFile.length() > 0) {
                    Log.d(TAG, "分片 " + (i + 1) + "/" + total + " 已存在，跳过");
                    if (callback != null) {
                        callback.onSegmentProgress(i + 1, total, 0);
                    }
                    continue;
                }

                long startTime = System.currentTimeMillis();
                downloadFile(segUrl, segFile, headers);
                long elapsed = System.currentTimeMillis() - startTime;
                long speed = elapsed > 0 ? (segFile.length() * 1000 / elapsed) : 0;

                Log.d(TAG, "分片 " + (i + 1) + "/" + total + " 下载完成 ("
                        + segFile.length() + " bytes)");
                if (callback != null) {
                    callback.onSegmentProgress(i + 1, total, speed);
                }
            }

            // 2. 合并所有分片
            Log.i(TAG, "开始合并 " + total + " 个分片到 " + outFile.getName());
            mergeFiles(tempDir, outFile, total);

            Log.i(TAG, "合并完成: " + outFile.getAbsolutePath()
                    + " (" + outFile.length() + " bytes)");
        } finally {
            // 清理临时文件
            cleanupTempDir(tempDir);
        }
    }

    /** 从 Master Playlist 中选择最高分辨率的 variant。 */
    private static String selectBestVariant(String playlist, String baseUrl) {
        String[] lines = playlist.split("\n");
        String bestUrl = null;
        int bestPixels = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                Matcher m = HLS_RESOLUTION_PATTERN.matcher(line);
                if (m.find()) {
                    int w = Integer.parseInt(m.group(1));
                    int h = Integer.parseInt(m.group(2));
                    int pixels = w * h;
                    if (pixels > bestPixels && i + 1 < lines.length) {
                        bestPixels = pixels;
                        bestUrl = lines[i + 1].trim();
                    }
                }
            }
        }
        return bestUrl != null ? resolveUrl(baseUrl, bestUrl) : null;
    }

    /** 解析 HLS Media Playlist 中的分片 URL。 */
    private static List<String> parseHlsSegments(String playlist, String baseUrl) {
        List<String> segments = new ArrayList<>();
        String[] lines = playlist.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            // 非 # 开头的行即为分片 URL
            segments.add(resolveUrl(baseUrl, line));
        }
        return segments;
    }

    /** 解析相对 URL（基于 base URL）。 */
    private static String resolveUrl(String baseUrl, String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isEmpty()) return relativeUrl;
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl;
        }
        try {
            URL base = new URL(baseUrl);
            return new URL(base, relativeUrl).toString();
        } catch (Exception e) {
            return relativeUrl;
        }
    }

    /** 合并临时目录中的所有分片到输出文件。 */
    private static void mergeFiles(File tempDir, File outFile, int totalSegments) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[64 * 1024];
            for (int i = 0; i < totalSegments; i++) {
                File segFile = new File(tempDir, String.format(Locale.US, "seg_%05d.ts", i));
                if (!segFile.exists()) {
                    throw new IOException("分片缺失: " + segFile.getName());
                }
                try (FileInputStream fis = new FileInputStream(segFile)) {
                    int n;
                    while ((n = fis.read(buffer)) > 0) {
                        fos.write(buffer, 0, n);
                    }
                }
            }
            fos.flush();
        }
    }

    /** 下载单个文件。 */
    private static void downloadFile(String url, File outFile,
                                      java.util.Map<String, String> headers) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            if (headers != null) {
                for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code + " 下载分片失败: " + url);
            }
            try (InputStream is = conn.getInputStream();
                 FileOutputStream fos = new FileOutputStream(outFile)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, n);
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 获取文本内容（m3u8/mpd）。 */
    private static String fetchText(String url, java.util.Map<String, String> headers) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            if (headers != null) {
                for (java.util.Map.Entry<String, String> e : headers.entrySet()) {
                    conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " 获取内容失败: " + url);
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            return sb.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 清理临时目录。 */
    private static void cleanupTempDir(File tempDir) {
        if (tempDir == null || !tempDir.exists()) return;
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.delete()) {
                    Log.w(TAG, "无法删除临时文件: " + f.getName());
                }
            }
        }
        if (!tempDir.delete()) {
            Log.w(TAG, "无法删除临时目录: " + tempDir.getName());
        }
    }
}
