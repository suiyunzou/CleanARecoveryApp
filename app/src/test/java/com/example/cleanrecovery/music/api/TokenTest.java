package com.example.cleanrecovery.music.api;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * 独立测试程序：解密 secu_params 拿到 token，然后测试 Youth 接口和 VIP 歌曲 URL。
 * 不需要登录，使用日志中已有的 secu_params 和 AES key。
 */
public class TokenTest {

    // 从日志中提取的登录请求 AES key
    private static final String AES_KEY = "ff1897226dd7b3c7a8de9de19f95c996";
    // 从日志中提取的 secu_params（加密的 token 数据）
    private static final String SECU_PARAMS = "9fd87718c091c2f068db9b90fb5b003d7ea25f163d86e7e054b5141e1829a92a3f9150fe883a9aecd01d66d849a1d002e4b5eb4f45bd27f08de3e42ec49953be59975c86bd4aec579324db965d3c602e";
    // 从日志中提取的 userid
    private static final String USER_ID = "2419699404";
    // 从日志中提取的 mid
    private static final String MID = "86199675333783855405531721047009750314";

    public static void main(String[] args) throws Exception {
        System.out.println("=== 1. 解密 secu_params ===");
        String decrypted = aesDecrypt(SECU_PARAMS, AES_KEY);
        System.out.println("Decrypted: " + decrypted);

        // 提取 token
        String token = extractJsonField(decrypted, "token");
        String refreshToken = extractJsonField(decrypted, "refreshtoken");
        System.out.println("token: " + token);
        System.out.println("refreshToken: " + refreshToken);

        if (token == null || token.isEmpty()) {
            System.out.println("ERROR: token 为空，无法继续测试");
            return;
        }

        System.out.println("\n=== 2. 测试 Youth play_report（无 cookie）===");
        testYouthPlayReport(token, "");

        System.out.println("\n=== 3. 测试 Youth play_report（带 cookie: musicwenji=token）===");
        testYouthPlayReport(token, "musicwenji=" + token);

        System.out.println("\n=== 4. 测试 Youth play_report（带 cookie: kg_u_list + musicwenji + kg_mid）===");
        testYouthPlayReport(token, "kg_u_list=" + USER_ID + "; musicwenji=" + token + "; kg_mid=" + MID);

        System.out.println("\n=== 5. 测试 VIP 歌曲 URL（带 token）===");
        testVipSongUrl(token);

        System.out.println("\n=== 6. 测试 VIP 歌曲 URL（带 token + userid）===");
        testVipSongUrlWithUserid(token, USER_ID);
    }

    static void testYouthPlayReport(String token, String cookie) throws Exception {
        long now = System.currentTimeMillis();
        String body = "{\"ad_id\":12307537187,\"play_end\":" + now + ",\"play_start\":" + (now - 30000) + "}";
        String url = "https://gateway.kugou.com/youth/v1/ad/play_report";
        String resp = httpPost(url, body, cookie, token);
        System.out.println("Response: " + resp);
    }

    static void testVipSongUrl(String token) throws Exception {
        // 周杰伦 - 晴天 (VIP 歌曲)
        String url = "http://www.kugou.com/yy/index.php?r=play/getdata"
                + "&hash=1B8C2A0E5C5F0E3F2A1B0C4D5E6F7A8B"
                + "&album_id=0"
                + "&_=" + System.currentTimeMillis();
        String resp = httpGet(url, token, "");
        System.out.println("Response: " + resp.substring(0, Math.min(resp.length(), 500)));
    }

    static void testVipSongUrlWithUserid(String token, String userid) throws Exception {
        // 使用新版 song_url 接口
        String url = "https://wwwapi.kugou.com/yy/index.php?r=play/getdata"
                + "&hash=1B8C2A0E5C5F0E3F2A1B0C4D5E6F7A8B"
                + "&album_id=0"
                + "&userid=" + userid
                + "&token=" + token
                + "&_=" + System.currentTimeMillis();
        String resp = httpGet(url, token, "");
        System.out.println("Response: " + resp.substring(0, Math.min(resp.length(), 500)));
    }

    static String httpPost(String urlStr, String body, String cookie, String token) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "KugouConcept/1.0 Android");
        conn.setRequestProperty("X-Client-Package", "com.kugou.android.lite");
        conn.setRequestProperty("X-Kugou-Platform", "lite");
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("clienttoken", token);
        }
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
        return "HTTP " + code + " | " + sb.toString();
    }

    static String httpGet(String urlStr, String token, String cookie) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("User-Agent", "KugouConcept/1.0 Android");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("clienttoken", token);
        }
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
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
        return "HTTP " + code + " | " + sb.toString();
    }

    static String aesDecrypt(String hexCipher, String tempKey) throws Exception {
        String fullMd5 = md5(tempKey);
        String key = fullMd5.substring(0, 32);
        String iv = fullMd5.substring(16);
        byte[] cipherBytes = hexToBytes(hexCipher);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"),
                new IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8)));
        byte[] result = cipher.doFinal(cipherBytes);
        return new String(result, StandardCharsets.UTF_8);
    }

    static String md5(String input) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) return "";
        start += key.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return "";
        return json.substring(start, end);
    }
}
