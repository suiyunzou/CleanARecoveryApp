package com.example.cleanrecovery.extractor;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Bilibili WBI 签名（移植自 yt-dlp {@code bilibili.py} 的 getMixinKey/_sign_wbi，
 * 与 BBDown 同一算法）。
 *
 * <p>playurl 等接口要求携带 {@code wts}（秒级时间戳）与 {@code w_rid}
 * （排序后查询串拼接 mixin key 的 MD5）两个参数；mixin key 由 nav API
 * 返回的 img/sub key 经固定混淆表重排得到。密钥会周期性轮换，
 * 这里按 30 分钟 TTL 缓存。</p>
 */
public final class WbiSigner {

    /** 混淆表（yt-dlp bilibili.py: mixin_key_enc_tab，2025-08 校准）。 */
    private static final int[] MIXIN_KEY_ENC_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52
    };

    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    private static volatile String cachedMixinKey;
    private static volatile long cachedAtMillis;

    private WbiSigner() {}

    /**
     * 生成带 WBI 签名的完整请求 URL（对应 yt-dlp _sign_wbi）。
     *
     * @param baseUrl 接口地址（无查询串）
     * @param params 业务参数；wts/w_rid 由本方法追加
     */
    public static String signUrl(String baseUrl, Map<String, String> params) throws IOException {
        String mixinKey = mixinKey();
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.put("wts", String.valueOf(System.currentTimeMillis() / 1000L));

        StringBuilder query = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (query.length() > 0) query.append('&');
            query.append(URLEncoder.encode(e.getKey(), "UTF-8"))
                    .append('=')
                    .append(URLEncoder.encode(filterChars(e.getValue()), "UTF-8"));
        }
        String wRid = md5Hex(query + mixinKey);
        return baseUrl + "?" + query + "&w_rid=" + wRid;
    }

    /** 过滤签名不兼容字符（yt-dlp：values 过滤 !'()* 后再参与 MD5）。 */
    private static String filterChars(String value) {
        if (value == null) return "";
        StringBuilder b = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '!' && c != '\'' && c != '(' && c != ')' && c != '*') b.append(c);
        }
        return b.toString();
    }

    /** 获取（并缓存）mixin key：nav API 取 img/sub key，按混淆表重排取前 32 位。 */
    private static String mixinKey() throws IOException {
        String key = cachedMixinKey;
        if (key != null && System.currentTimeMillis() - cachedAtMillis < CACHE_TTL_MS) return key;
        synchronized (WbiSigner.class) {
            if (cachedMixinKey != null
                    && System.currentTimeMillis() - cachedAtMillis < CACHE_TTL_MS) {
                return cachedMixinKey;
            }
            Map<String, String> headers = ExtractorHttp.defaultHeaders();
            headers.put("Referer", "https://www.bilibili.com/");
            String json = ExtractorHttp.downloadJson(
                    "https://api.bilibili.com/x/web-interface/nav", headers);
            JSONObject data;
            try {
                data = new JSONObject(json).optJSONObject("data");
            } catch (org.json.JSONException e) {
                throw new IOException("nav API 响应解析失败: " + e.getMessage());
            }
            JSONObject wbi = data == null ? null : data.optJSONObject("wbi_img");
            if (wbi == null) throw new IOException("nav API 未返回 wbi_img");
            String raw = tailFilenameKey(wbi.optString("img_url"))
                    + tailFilenameKey(wbi.optString("sub_url"));
            if (raw.length() < 32) throw new IOException("wbi key 长度异常");
            StringBuilder b = new StringBuilder(32);
            for (int i = 0; i < 32; i++) b.append(raw.charAt(MIXIN_KEY_ENC_TAB[i]));
            cachedMixinKey = b.toString();
            cachedAtMillis = System.currentTimeMillis();
            return cachedMixinKey;
        }
    }

    /** 从 "https://.../<name>.png" 提取文件名（去扩展名）作为 key。 */
    private static String tailFilenameKey(String url) {
        if (url == null) return "";
        int slash = url.lastIndexOf('/');
        String name = slash >= 0 ? url.substring(slash + 1) : url;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String md5Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(digest.length * 2);
            for (byte d : digest) {
                b.append(Character.forDigit((d >> 4) & 0xF, 16));
                b.append(Character.forDigit(d & 0xF, 16));
            }
            return b.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 unavailable", e);
        }
    }
}
