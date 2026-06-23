package com.example.cleanrecovery.music.api;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.TreeMap;

/**
 * PC 端测试概念版播放 URL 接口（gateway.kugou.com/v1/url）
 * 验证 VIP 歌曲能否拿到播放 URL，避免在手机端反复测试导致账号风控。
 *
 * 用法：先运行 RealTokenTest 拿到最新 token，再运行本程序。
 */
public class ConceptPlayUrlTest {

    private static final String LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    private static final int LITE_APPID = 3116;
    private static final int LITE_CLIENTVER = 11440;
    private static final String USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";

    // 从最新日志提取的真实参数
    private static final String REAL_TOKEN = "e7eecb8a174dd110f0fa66099a1e1a68c46e3091bd40b98ba0829b60bac529c7";
    private static final String REAL_USERID = "2419699404";
    private static final String REAL_MID = "86199675333783855405531721047009750314";
    private static final String REAL_DFID = "D8Oj9ymF9PKvgzYlvrhXovMl";

    // 从日志提取的 VIP 歌曲 hash（播放失败的那首）
    private static final String VIP_SONG_HASH = "8CA8C420761A7C36FF16DD42FE5D331D";

    public static void main(String[] args) throws Exception {
        // i/v2 接口返回 errcode:20028 (需要 VIP 权限)
        // 测试不同 vipType 和 appid 组合
        String[] vipTypes = {"65530", "65535", "1", "2", "7", "11", "15"};
        int[] appids = {1005, 3116, 1014};
        for (int appid : appids) {
            for (String vipType : vipTypes) {
                System.out.println("=== appid=" + appid + " vipType=" + vipType + " ===");
                testIV2WithVipType(VIP_SONG_HASH, REAL_TOKEN, REAL_USERID, REAL_MID, REAL_DFID, appid, vipType);
            }
        }
    }

    static void testIV2WithVipType(String hash, String token, String userid, String mid, String dfid, int appid, String vipType) throws Exception {
        String key = md5(hash.toLowerCase() + "kgcloudv2").substring(0, 16);

        long clienttime = System.currentTimeMillis() / 1000;
        StringBuilder urlBuilder = new StringBuilder("http://trackercdn.kugou.com/i/v2/?");
        urlBuilder.append("key=").append(key);
        urlBuilder.append("&hash=").append(hash.toLowerCase());
        urlBuilder.append("&appid=").append(appid);
        urlBuilder.append("&behavior=play");
        urlBuilder.append("&cmd=26");
        urlBuilder.append("&filename=").append(hash.toUpperCase());
        urlBuilder.append("&userid=").append(userid);
        urlBuilder.append("&token=").append(token);
        urlBuilder.append("&vipType=").append(vipType);
        urlBuilder.append("&pid=2");
        urlBuilder.append("&version=9108");
        urlBuilder.append("&area_code=1");
        urlBuilder.append("&mid=").append(mid);
        urlBuilder.append("&dfid=").append(dfid);
        urlBuilder.append("&clienttime=").append(clienttime);
        urlBuilder.append("&pidversion=3001");
        doGet(urlBuilder.toString());
    }

    static void testV5Url(String hash, String token, String userid, String mid, String dfid) throws Exception {
        // v5/url 使用固定密钥: key = MD5(hash + "185672dd44712f60bb1736df5a377e82" + appid + MD5(dfid) + userid)
        String appid = "1005";
        String dfidMd5 = md5(dfid);
        String keySource = hash.toLowerCase() + "185672dd44712f60bb1736df5a377e82" + appid + dfidMd5 + userid;
        String key = md5(keySource);
        System.out.println("key source: " + keySource);
        System.out.println("key: " + key);

        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", appid);
        params.put("clientver", "9108");
        params.put("clienttime", String.valueOf(clienttime));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("userid", userid);
        params.put("token", token);
        params.put("uuid", "-");
        params.put("hash", hash.toLowerCase());
        params.put("type", "audio");
        params.put("area_id", "1");
        params.put("p2p", "0");
        params.put("key", key);

        StringBuilder urlBuilder = new StringBuilder("https://trackercdn.kugou.com/v5/url?");
        buildQuery(urlBuilder, params);
        System.out.println("URL: " + urlBuilder.toString());
        doGet(urlBuilder.toString());
    }

    static void testIV2(String hash, String token, String userid, String mid, String dfid) throws Exception {
        // i/v2 使用 key = MD5(hash + "kgcloudv2") 前16位
        String key = md5(hash.toLowerCase() + "kgcloudv2").substring(0, 16);
        System.out.println("key: " + key);

        long clienttime = System.currentTimeMillis() / 1000;
        StringBuilder urlBuilder = new StringBuilder("http://trackercdn.kugou.com/i/v2/?");
        urlBuilder.append("key=").append(key);
        urlBuilder.append("&hash=").append(hash.toLowerCase());
        urlBuilder.append("&appid=1005");
        urlBuilder.append("&behavior=play");
        urlBuilder.append("&cmd=25");
        urlBuilder.append("&filename=").append(hash.toUpperCase());
        urlBuilder.append("&userid=0");
        urlBuilder.append("&pid=2");
        urlBuilder.append("&version=9108");
        urlBuilder.append("&area_code=1");
        urlBuilder.append("&mid=").append(mid);
        urlBuilder.append("&dfid=").append(dfid);
        urlBuilder.append("&clienttime=").append(clienttime);
        System.out.println("URL: " + urlBuilder.toString());
        doGet(urlBuilder.toString());
    }

    static void testIV2WithAuth(String hash, String token, String userid, String mid, String dfid) throws Exception {
        // i/v2 带 token + userid + vipType
        String key = md5(hash.toLowerCase() + "kgcloudv2").substring(0, 16);
        System.out.println("key: " + key);

        long clienttime = System.currentTimeMillis() / 1000;
        StringBuilder urlBuilder = new StringBuilder("http://trackercdn.kugou.com/i/v2/?");
        urlBuilder.append("key=").append(key);
        urlBuilder.append("&hash=").append(hash.toLowerCase());
        urlBuilder.append("&album_audio_id=");
        urlBuilder.append("&album_id=");
        urlBuilder.append("&appid=1005");
        urlBuilder.append("&behavior=play");
        urlBuilder.append("&cmd=26");
        urlBuilder.append("&filename=").append(hash.toUpperCase());
        urlBuilder.append("&userid=").append(userid);
        urlBuilder.append("&token=").append(token);
        urlBuilder.append("&vipType=65530");
        urlBuilder.append("&pid=2");
        urlBuilder.append("&version=9108");
        urlBuilder.append("&area_code=1");
        urlBuilder.append("&mid=").append(mid);
        urlBuilder.append("&dfid=").append(dfid);
        urlBuilder.append("&clienttime=").append(clienttime);
        urlBuilder.append("&pidversion=3001");
        urlBuilder.append("&with_res_tag=1");
        System.out.println("URL: " + urlBuilder.toString());
        doGet(urlBuilder.toString());
    }

    static void testPrivUrlWithAppid(String hash, String token, String userid, String mid, String dfid, int appid, String salt) throws Exception {
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", String.valueOf(appid));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("userid", userid);
        params.put("token", token);
        params.put("uuid", "-");
        params.put("hash", hash.toLowerCase());
        params.put("type", "audio");
        params.put("area_id", "1");
        params.put("p2p", "0");
        params.put("is_buy", "1");
        params.put("vip_token", token);
        params.put("behavior", "play");

        String bodyJson = "{}";
        String signature = signatureWithSalt(params, bodyJson, salt);
        params.put("signature", signature);

        StringBuilder urlBuilder = new StringBuilder("https://trackercdn.kugou.com/v6/priv_url?");
        buildQuery(urlBuilder, params);
        doGet(urlBuilder.toString());
    }

    static void testPrivUrlNoSign(String hash, String token, String userid, String mid, String dfid) throws Exception {
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("userid", userid);
        params.put("token", token);
        params.put("uuid", "-");
        params.put("hash", hash.toLowerCase());
        params.put("type", "audio");
        params.put("area_id", "1");
        params.put("p2p", "0");
        params.put("is_buy", "1");
        params.put("vip_token", token);
        params.put("behavior", "play");

        StringBuilder urlBuilder = new StringBuilder("https://trackercdn.kugou.com/v6/priv_url?");
        buildQuery(urlBuilder, params);
        System.out.println("URL: " + urlBuilder.toString());
        doGet(urlBuilder.toString());
    }

    static void testPrivUrlWithSalt(String hash, String token, String userid, String mid, String dfid, String salt) throws Exception {
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("userid", userid);
        params.put("token", token);
        params.put("uuid", "-");
        params.put("hash", hash.toLowerCase());
        params.put("type", "audio");
        params.put("area_id", "1");
        params.put("p2p", "0");
        params.put("is_buy", "1");
        params.put("vip_token", token);
        params.put("behavior", "play");

        String bodyJson = "{}";
        String signature = signatureWithSalt(params, bodyJson, salt);
        params.put("signature", signature);

        StringBuilder urlBuilder = new StringBuilder("https://trackercdn.kugou.com/v6/priv_url?");
        buildQuery(urlBuilder, params);
        System.out.println("URL: " + urlBuilder.toString());
        doGet(urlBuilder.toString());
    }

    static void testPrivUrlMinimal(String hash, String token, String userid, String mid, String dfid) throws Exception {
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("userid", userid);
        params.put("token", token);
        params.put("uuid", "-");
        params.put("hash", hash.toLowerCase());
        params.put("type", "audio");
        params.put("area_id", "1");

        String bodyJson = "{}";
        String signature = signatureAndroidLite(params, bodyJson);
        params.put("signature", signature);

        StringBuilder urlBuilder = new StringBuilder("https://trackercdn.kugou.com/v6/priv_url?");
        buildQuery(urlBuilder, params);
        System.out.println("URL: " + urlBuilder.toString());
        doGet(urlBuilder.toString());
    }

    static void doGet(String fullUrl) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("x-router", "trackergateway.trackerv2.kugou.com");
        printResponse(conn, "  ");
    }

    static String signatureWithSalt(TreeMap<String, String> params, String bodyJson, String salt) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(salt);
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append(bodyJson != null ? bodyJson : "");
        sb.append(salt);
        return md5(sb.toString());
    }

    static void testPrivUrlOnHost(String host, String hash, String token, String userid, String mid, String dfid) throws Exception {
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("userid", userid);
        params.put("token", token);
        params.put("uuid", "-");
        params.put("hash", hash.toLowerCase());
        params.put("type", "audio");
        params.put("area_id", "1");
        params.put("p2p", "0");
        params.put("is_buy", "1");
        params.put("vip_token", token);
        params.put("behavior", "play");
        params.put("album_id", "");

        String bodyJson = "{}";
        String signature = signatureAndroidLite(params, bodyJson);
        params.put("signature", signature);

        // 尝试多个路径
        String[] paths = {"/v6/priv_url", "/v2/url", "/v1/url"};
        for (String path : paths) {
            StringBuilder urlBuilder = new StringBuilder(host).append(path).append("?");
            boolean first = true;
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!first) urlBuilder.append("&");
                urlBuilder.append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
                first = false;
            }
            String fullUrl = urlBuilder.toString();
            System.out.println("  " + path + ": ");
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("x-router", "trackergateway.trackerv2.kugou.com");
                printResponse(conn, "    ");
            } catch (Exception e) {
                System.out.println("    Error: " + e.getMessage());
            }
        }
    }

    static void testPrivUrl(String hash, String token, String userid, String mid, String dfid) throws Exception {
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("userid", userid);
        params.put("token", token);
        params.put("uuid", "-");
        params.put("hash", hash.toLowerCase());
        params.put("type", "audio");
        params.put("area_id", "1");
        params.put("p2p", "0");
        params.put("is_buy", "1");
        params.put("vip_token", token);
        params.put("behavior", "play");
        params.put("album_id", "");

        String bodyJson = "{}";
        String signature = signatureAndroidLite(params, bodyJson);
        params.put("signature", signature);

        StringBuilder urlBuilder = new StringBuilder("https://gateway.kugou.com/v6/priv_url?");
        buildQuery(urlBuilder, params);
        String fullUrl = urlBuilder.toString();
        System.out.println("URL: " + fullUrl);
        System.out.println("Body: " + bodyJson);

        byte[] bytes = bodyJson.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("x-router", "trackergateway.trackerv2.kugou.com");
        try (java.io.OutputStream os = conn.getOutputStream()) { os.write(bytes); }
        printResponse(conn);
    }

    static void testV2Url(String hash, String token, String userid, String mid, String dfid) throws Exception {
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("userid", userid);
        params.put("token", token);
        params.put("uuid", "-");
        params.put("hash", hash.toLowerCase());
        params.put("type", "audio");
        params.put("area_id", "1");
        params.put("p2p", "0");
        params.put("is_buy", "1");
        params.put("behavior", "play");

        String bodyJson = "{}";
        String signature = signatureAndroidLite(params, bodyJson);
        params.put("signature", signature);

        StringBuilder urlBuilder = new StringBuilder("https://gateway.kugou.com/v2/url?");
        buildQuery(urlBuilder, params);
        System.out.println("URL: " + urlBuilder.toString());
        byte[] bytes = bodyJson.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlBuilder.toString()).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("x-router", "trackergateway.trackerv2.kugou.com");
        try (java.io.OutputStream os = conn.getOutputStream()) { os.write(bytes); }
        printResponse(conn);
    }

    static void testV3Url(String hash, String token, String userid, String mid, String dfid) throws Exception {
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("userid", userid);
        params.put("token", token);
        params.put("uuid", "-");
        params.put("hash", hash.toLowerCase());
        params.put("type", "audio");
        params.put("area_id", "1");
        params.put("p2p", "0");
        params.put("is_buy", "1");
        params.put("behavior", "play");

        String bodyJson = "{}";
        String signature = signatureAndroidLite(params, bodyJson);
        params.put("signature", signature);

        StringBuilder urlBuilder = new StringBuilder("https://gateway.kugou.com/v3/url?");
        buildQuery(urlBuilder, params);
        System.out.println("URL: " + urlBuilder.toString());
        byte[] bytes = bodyJson.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlBuilder.toString()).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("x-router", "trackergateway.trackerv2.kugou.com");
        try (java.io.OutputStream os = conn.getOutputStream()) { os.write(bytes); }
        printResponse(conn);
    }

    static void buildQuery(StringBuilder urlBuilder, TreeMap<String, String> params) throws Exception {
        urlBuilder.append("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) urlBuilder.append("&");
            urlBuilder.append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
            first = false;
        }
    }

    static void printResponse(HttpURLConnection conn, String indent) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        String resp = sb.toString();
        System.out.println(indent + "HTTP " + code + " | length: " + resp.length());
        System.out.println(indent + "Response: " + resp.substring(0, Math.min(800, resp.length())));
    }

    static void printResponse(HttpURLConnection conn) throws Exception {
        printResponse(conn, "");
    }

    static void testGetSongInfoWithToken(String hash, String token, String userid, String mid, String dfid) throws Exception {
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("cmd", "playInfo");
        params.put("hash", hash.toLowerCase());
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("userid", userid);
        params.put("token", token);
        params.put("uuid", "-");

        StringBuilder urlBuilder = new StringBuilder("http://m.kugou.com/app/i/getSongInfo.php?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) urlBuilder.append("&");
            urlBuilder.append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
            first = false;
        }
        String fullUrl = urlBuilder.toString();
        System.out.println("URL: " + fullUrl);

        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        String resp = sb.toString();
        System.out.println("HTTP " + code + " | Response length: " + resp.length());
        // 提取 url 字段
        int urlIdx = resp.indexOf("\"url\":\"");
        if (urlIdx >= 0) {
            int start = urlIdx + 7;
            int end = resp.indexOf("\"", start);
            String playUrl = resp.substring(start, end);
            System.out.println("play url: " + playUrl);
        } else {
            System.out.println("play url: (empty)");
        }
        // 提取 backup_url
        int backupIdx = resp.indexOf("\"backup_url\":");
        if (backupIdx >= 0) {
            System.out.println("backup_url section: " + resp.substring(backupIdx, Math.min(backupIdx + 200, resp.length())));
        }
        System.out.println("Full response (first 500): " + resp.substring(0, Math.min(500, resp.length())));
    }

    static void testWebPlayUrl(String hash) throws Exception {
        String mid = md5(hash + System.currentTimeMillis());
        String url = "https://wwwapi.kugou.com/yy/index.php?r=play/getdata&hash=" + hash.toLowerCase()
                + "&mid=" + mid + "&platid=4";
        System.out.println("URL: " + url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36");
        conn.setRequestProperty("Referer", "https://www.kugou.com/song/");
        conn.setRequestProperty("Cookie", "kg_mid=" + mid);
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        String resp = sb.toString();
        System.out.println("HTTP " + code + " | Response (first 800): " + resp.substring(0, Math.min(800, resp.length())));
    }

    static void testConceptPlayUrl(String hash, String token, String userid, String mid, String dfid) throws Exception {
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("dfid", dfid != null ? dfid : "-");
        params.put("mid", mid != null ? mid : "");
        params.put("uuid", "-");
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        params.put("hash", hash.toLowerCase());
        params.put("areaid", "23");
        params.put("p2p", "0");
        if (token != null && !token.isEmpty()) params.put("token", token);
        if (userid != null && !userid.isEmpty()) params.put("userid", userid);

        String bodyJson = "";
        String signature = signatureAndroidLite(params, bodyJson);
        params.put("signature", signature);

        StringBuilder urlBuilder = new StringBuilder("https://gateway.kugou.com/v1/url?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) urlBuilder.append("&");
            urlBuilder.append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
            first = false;
        }
        String fullUrl = urlBuilder.toString();
        System.out.println("URL: " + fullUrl);

        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("x-router", "trackergateway.trackerv2.kugou.com");
        conn.setRequestProperty("Accept", "application/json");

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        System.out.println("HTTP " + code + " | Response: " + sb.toString());
    }

    static void testLegacyPlayInfo(String hash) throws Exception {
        String url = "http://m.kugou.com/app/i/getSongInfo.php?cmd=playInfo&hash=" + hash.toLowerCase();
        System.out.println("URL: " + url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13)");
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        System.out.println("HTTP " + code + " | Response: " + sb.toString());
    }

    static void testTrackerCdn(String hash) throws Exception {
        String key = md5(hash.toLowerCase() + "kgcloudv2").substring(0, 16);
        String url = "http://trackercdn.kugou.com/i/v2/?key=" + key + "&hash=" + hash.toLowerCase()
                + "&appid=3116&behavior=play&cmd=25&filename=" + hash.toUpperCase();
        System.out.println("URL: " + url);
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        StringBuilder sb = new StringBuilder();
        if (is != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        System.out.println("HTTP " + code + " | Response: " + sb.toString());
    }

    static String signatureAndroidLite(TreeMap<String, String> params, String bodyJson) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(LITE_SALT);
            for (Map.Entry<String, String> e : params.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            sb.append(bodyJson != null ? bodyJson : "");
            sb.append(LITE_SALT);
            return md5(sb.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String md5(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
