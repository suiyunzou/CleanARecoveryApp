package com.example.cleanrecovery.music.api;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * 测试新的 Youth 接口签名方式（android lite 签名 + query 参数）。
 * 使用假 token 验证签名是否被服务端接受（错误码应从 20001 变成 token 相关错误）。
 */
public class YouthSignTest {

    private static final String LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    private static final int LITE_APPID = 3116;
    private static final int LITE_CLIENTVER = 11440;
    private static final String USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";

    // 从日志提取的真实参数
    private static final String REAL_MID = "86199675333783855405531721047009750314";
    private static final String REAL_USERID = "2419699404";
    private static final String REAL_DFID = "76n3U7ke8YsOCxpLBw2NLjGs";

    public static void main(String[] args) throws Exception {
        System.out.println("=== 测试1: 假 token + 正确签名（验证签名是否被接受）===");
        testPlayReport("fake_token_12345", REAL_USERID, REAL_MID, REAL_DFID);

        System.out.println("\n=== 测试2: 无 token + 正确签名 ===");
        testPlayReport("", "0", REAL_MID, REAL_DFID);

        System.out.println("\n=== 测试3: 无 token + 无 userid ===");
        testPlayReport("", "", REAL_MID, REAL_DFID);
    }

    static void testPlayReport(String token, String userid, String mid, String dfid) throws Exception {
        long nowMs = System.currentTimeMillis();
        String bodyJson = "{\"ad_id\":12307537187,\"play_end\":" + nowMs + ",\"play_start\":" + (nowMs - 30000) + "}";

        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("dfid", dfid != null ? dfid : "-");
        params.put("mid", mid != null ? mid : "");
        params.put("uuid", "-");
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        if (token != null && !token.isEmpty()) params.put("token", token);
        if (userid != null && !userid.isEmpty()) params.put("userid", userid);

        String signature = signatureAndroidLite(params, bodyJson);
        params.put("signature", signature);

        StringBuilder urlBuilder = new StringBuilder("https://gateway.kugou.com/youth/v1/ad/play_report?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) urlBuilder.append("&");
            urlBuilder.append(e.getKey()).append("=").append(URLEncoder.encode(e.getValue(), "UTF-8"));
            first = false;
        }
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
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("dfid", params.get("dfid"));
        conn.setRequestProperty("clienttime", params.get("clienttime"));
        conn.setRequestProperty("mid", params.get("mid"));
        conn.setRequestProperty("kg-rc", "1");
        conn.setRequestProperty("kg-thash", "5d816a0");
        conn.setRequestProperty("kg-rec", "1");
        conn.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(bytes);
        }
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
            return md5(buildSignString(params, bodyJson));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static String buildSignString(TreeMap<String, String> params, String bodyJson) {
        StringBuilder sb = new StringBuilder();
        sb.append(LITE_SALT);
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append(bodyJson != null ? bodyJson : "");
        sb.append(LITE_SALT);
        return sb.toString();
    }

    static String md5(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
