package com.example.cleanrecovery.music.api;

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
 * 用真实 token 测试 Youth play_report 接口。
 */
public class RealTokenTest {

    private static final String LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    private static final int LITE_APPID = 3116;
    private static final int LITE_CLIENTVER = 11440;
    private static final String USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";

    // 从日志提取的真实参数（最新登录）
    private static final String REAL_TOKEN = "e7eecb8a174dd110f0fa66099a1e1a68a8c0117377724e9769713b1013eedce3";
    private static final String REAL_USERID = "2419699404";
    private static final String REAL_MID = "86199675333783855405531721047009750314";
    private static final String REAL_DFID = "lfnSDRm88bMgdGsMUD1ygyZB";

    public static void main(String[] args) throws Exception {
        System.out.println("=== 测试1: 真实 token + userid + dfid ===");
        testPlayReport(REAL_TOKEN, REAL_USERID, REAL_MID, REAL_DFID);

        System.out.println("\n=== 测试2: 真实 token + userid + dfid=- ===");
        testPlayReport(REAL_TOKEN, REAL_USERID, REAL_MID, "-");

        System.out.println("\n=== 测试3: 真实 token + 无 userid ===");
        testPlayReport(REAL_TOKEN, "", REAL_MID, REAL_DFID);
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
            StringBuilder sb = new StringBuilder();
            sb.append(LITE_SALT);
            for (Map.Entry<String, String> e : params.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue());
            }
            sb.append(bodyJson != null ? bodyJson : "");
            sb.append(LITE_SALT);
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
