package com.example.cleanrecovery.music.api;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * PC 端验证测试：用最新 token 验证 VIP 歌曲播放 URL 是否可用。
 *
 * <p>用法：
 * <ol>
 *   <li>在手机端登录 app（会自动导出 latest_token.txt）</li>
 *   <li>adb pull /sdcard/Android/data/com.example.cleanrecovery.musicapp/files/latest_token.txt ./latest_token.txt</li>
 *   <li>把 latest_token.txt 放到项目根目录或 app/src/test/resources/</li>
 *   <li>运行本测试（main 方法）</li>
 * </ol>
 *
 * <p>输出：SUCCESS 或 FAIL，附带具体原因（HTTP 状态码、err_code、URL 是否可访问）。
 *
 * <p>本测试不依赖 Android 框架，可直接用 java 运行。
 */
public class PlayUrlVerificationTest {

    private static final String LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    private static final String LITE_KEY_SALT = "185672dd44712f60bb1736df5a377e82";
    private static final int LITE_APPID = 3116;
    private static final int LITE_CLIENTVER = 11440;
    private static final String USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";

    // 测试用 VIP 歌曲 hash（从日志提取的播放失败那首）
    private static final String VIP_SONG_HASH = "8CA8C420761A7C36FF16DD42FE5D331D";

    // token 文件搜索路径（按顺序尝试）
    private static final String[] TOKEN_FILE_PATHS = {
            "latest_token.txt",
            "app/src/test/resources/latest_token.txt",
            "app/latest_token.txt",
            System.getProperty("user.home") + "/latest_token.txt"
    };

    public static void main(String[] args) throws Exception {
        System.out.println("=== 酷狗概念版 VIP 播放 URL 验证测试 ===\n");

        // 1. 读取最新 token
        TokenInfo token = loadLatestToken();
        if (token == null) {
            System.out.println("FAIL: 找不到 latest_token.txt");
            System.out.println("请先在手机端登录，然后执行：");
            System.out.println("  adb pull /sdcard/Android/data/com.example.cleanrecovery.musicapp/files/latest_token.txt ./latest_token.txt");
            System.exit(1);
            return;
        }
        System.out.println("Token 来源: " + token.sourcePath);
        System.out.println("Token: " + token.token.substring(0, Math.min(16, token.token.length())) + "...");
        System.out.println("UserID: " + token.userid);
        System.out.println("MID: " + token.mid);
        System.out.println("DFID: " + token.dfid);
        System.out.println("导出时间: " + new java.util.Date(token.exportedAt));
        System.out.println();

        // 2. 调用概念版网关获取播放 URL（/v5/url + 概念版参数 + 签名）
        System.out.println("--- 步骤 1: 调用 gateway.kugou.com/v5/url 获取播放 URL ---");
        PlayUrlResult result = fetchConceptPlayUrl(VIP_SONG_HASH, token);
        System.out.println("HTTP 状态: " + result.httpCode);
        System.out.println("响应体: " + result.response);
        System.out.println();

        if (result.playUrl == null || result.playUrl.isEmpty()) {
            System.out.println("FAIL: 未获取到播放 URL");
            System.out.println("原因: 网关未返回 url 字段，可能是 token 过期或 cookie 未生效");
            System.exit(1);
            return;
        }

        System.out.println("获取到的播放 URL: " + result.playUrl);
        System.out.println();

        // 3. 验证 URL 实际可访问（HEAD 请求）
        System.out.println("--- 步骤 2: 验证播放 URL 可访问性（HEAD 请求）---");
        UrlCheckResult check = checkUrlAccessible(result.playUrl);
        System.out.println("HTTP 状态: " + check.httpCode);
        System.out.println("Content-Type: " + check.contentType);
        System.out.println("Content-Length: " + check.contentLength);
        System.out.println();

        // 4. 输出最终结果
        if (check.httpCode == 200 || check.httpCode == 206) {
            System.out.println("=== SUCCESS ===");
            System.out.println("VIP 歌曲 " + VIP_SONG_HASH + " 播放 URL 可正常访问");
            System.out.println("可以安全安装 app");
            System.exit(0);
        } else {
            System.out.println("=== FAIL ===");
            System.out.println("播放 URL 返回 HTTP " + check.httpCode + "，无法访问");
            System.out.println("不要安装 app，请检查 token 是否过期或网络是否正常");
            System.exit(1);
        }
    }

    static TokenInfo loadLatestToken() throws Exception {
        for (String path : TOKEN_FILE_PATHS) {
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                String json = readFile(f);
                TokenInfo info = parseTokenJson(json);
                if (info != null) {
                    info.sourcePath = f.getAbsolutePath();
                    return info;
                }
            }
        }
        return null;
    }

    static PlayUrlResult fetchConceptPlayUrl(String hash, TokenInfo token) throws Exception {
        PlayUrlResult result = new PlayUrlResult();
        String lowerHash = hash.toLowerCase();
        String dfid = (token.dfid != null && !token.dfid.isEmpty()) ? token.dfid : "-";
        String mid = (token.mid != null && !token.mid.isEmpty()) ? token.mid : "";
        long clienttime = System.currentTimeMillis() / 1000;

        // 构造参数（TreeMap 自动按 key 排序，用于签名）
        // 参考 KuGouMusicApi module/song_url.js 和 util/request.js
        TreeMap<String, String> params = new TreeMap<>();
        params.put("album_audio_id", "0");
        params.put("album_id", "0");
        params.put("area_code", "1");
        params.put("behavior", "play");
        params.put("cdnBackup", "1");
        params.put("clienttime", String.valueOf(clienttime));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("cmd", "26");
        params.put("dfid", dfid);
        params.put("hash", lowerHash);
        params.put("IsFreePart", "0");
        params.put("mid", mid);
        params.put("module", "");
        params.put("page_id", "967177915");  // 概念版特有
        params.put("pid", "411");             // 概念版特有（标准版为 2）
        params.put("pidversion", "3001");
        params.put("ppage_id", "356753938,823673182,967485191");  // 概念版特有
        params.put("quality", "128");
        params.put("ssa_flag", "is_fromtrack");
        params.put("uuid", "-");
        params.put("version", "11430");

        // 概念版标识参数
        params.put("appid", String.valueOf(LITE_APPID));

        // 登录态参数（VIP 歌曲必需）
        if (token.token != null && !token.token.isEmpty()) params.put("token", token.token);
        if (token.userid != null && !token.userid.isEmpty()) params.put("userid", token.userid);

        // 生成 key 参数：MD5(hash + keySalt + appid + mid + userid)
        String keyUserId = (token.userid == null || token.userid.isEmpty()) ? "0" : token.userid;
        String key = md5(lowerHash + LITE_KEY_SALT + LITE_APPID + mid + keyUserId);
        params.put("key", key);

        // 生成 signature：MD5(salt + sorted("k=v").join("") + bodyJson + salt)
        // bodyJson 为空（GET 请求无 body）
        StringBuilder sigBuilder = new StringBuilder(LITE_SALT);
        for (Map.Entry<String, String> e : params.entrySet()) {
            sigBuilder.append(e.getKey()).append("=").append(e.getValue());
        }
        sigBuilder.append(LITE_SALT);
        String signature = md5(sigBuilder.toString());
        params.put("signature", signature);

        // 构建最终 URL
        StringBuilder urlBuilder = new StringBuilder("https://gateway.kugou.com/v5/url?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) urlBuilder.append("&");
            urlBuilder.append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
            first = false;
        }
        String fullUrl = urlBuilder.toString();
        System.out.println("请求 URL: " + fullUrl);

        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        // 请求头参考 KuGouMusicApi util/request.js
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("x-router", "trackercdn.kugou.com");
        conn.setRequestProperty("dfid", dfid);
        conn.setRequestProperty("mid", mid);
        conn.setRequestProperty("clienttime", String.valueOf(clienttime));
        conn.setRequestProperty("kg-rc", "1");
        conn.setRequestProperty("kg-thash", "5d816a0");
        conn.setRequestProperty("kg-rec", "1");
        conn.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
        conn.setRequestProperty("Accept", "application/json");

        result.httpCode = conn.getResponseCode();
        InputStream is = (result.httpCode >= 200 && result.httpCode < 300)
                ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        result.response = sb.toString();

        // 提取 url 字段
        result.playUrl = extractUrlField(result.response);
        return result;
    }

    static UrlCheckResult checkUrlAccessible(String playUrl) throws Exception {
        UrlCheckResult r = new UrlCheckResult();
        HttpURLConnection conn = (HttpURLConnection) new URL(playUrl).openConnection();
        conn.setRequestMethod("HEAD");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        try {
            r.httpCode = conn.getResponseCode();
            r.contentType = conn.getContentType();
            r.contentLength = conn.getContentLength();
        } finally {
            conn.disconnect();
        }
        return r;
    }

    static String extractUrlField(String json) {
        if (json == null) return null;

        // 概念版 /v5/url 实际响应格式：{"status":1,"url":["http://...","http://..."],"backupUrl":["..."]}
        // url 字段是数组，需要提取第一个元素。
        // 先尝试数组格式："url":["...","..."]
        String[] urlKeys = {"url", "play_url", "playUrl"};
        for (String key : urlKeys) {
            String arrayPattern = "\"" + key + "\":[";
            int arrIdx = json.indexOf(arrayPattern);
            if (arrIdx >= 0) {
                int startQuote = json.indexOf("\"", arrIdx + arrayPattern.length());
                if (startQuote >= 0) {
                    int endQuote = json.indexOf("\"", startQuote + 1);
                    if (endQuote > startQuote) {
                        String url = json.substring(startQuote + 1, endQuote);
                        return url.replace("\\/", "/");
                    }
                }
            }
            // 再尝试字符串格式："key":"..."
            String strPattern = "\"" + key + "\":\"";
            int strIdx = json.indexOf(strPattern);
            if (strIdx >= 0) {
                int start = strIdx + strPattern.length();
                int end = json.indexOf("\"", start);
                if (end > start) {
                    return json.substring(start, end).replace("\\/", "/");
                }
            }
        }
        return null;
    }

    static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    static String readFile(File f) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    static TokenInfo parseTokenJson(String json) {
        if (json == null || json.isEmpty()) return null;
        TokenInfo info = new TokenInfo();
        info.token = extractJsonField(json, "token");
        info.userid = extractJsonField(json, "userid");
        info.mid = extractJsonField(json, "mid");
        info.dfid = extractJsonField(json, "dfid");
        String at = extractJsonField(json, "exportedAt");
        if (at != null && !at.isEmpty()) {
            try { info.exportedAt = Long.parseLong(at); } catch (Exception ignored) {}
        }
        if (info.token == null || info.token.isEmpty()) return null;
        if (info.userid == null || info.userid.isEmpty()) return null;
        return info;
    }

    static String extractJsonField(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) {
            // 尝试数字字段
            pattern = "\"" + key + "\":";
            idx = json.indexOf(pattern);
            if (idx < 0) return null;
            int start = idx + pattern.length();
            int end = json.indexOf(",", start);
            if (end < 0) end = json.indexOf("}", start);
            if (end < 0) return null;
            return json.substring(start, end).trim();
        }
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    static class TokenInfo {
        String token;
        String userid;
        String mid;
        String dfid;
        long exportedAt;
        String sourcePath;
    }

    static class PlayUrlResult {
        int httpCode;
        String response;
        String playUrl;
    }

    static class UrlCheckResult {
        int httpCode;
        String contentType;
        int contentLength;
    }
}
