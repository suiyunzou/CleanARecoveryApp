package com.example.cleanrecovery.music.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 酷狗概念版（Youth）权益网关客户端。
 *
 * <p>实现与 KuGouMusicApi 的 youth_vip.js 一致的调用方式：
 * <ul>
 *   <li>URL query 参数：dfid, mid, uuid, appid, clientver, clienttime, token, userid, signature</li>
 *   <li>android 签名（概念版盐值 LnT6xpN3khm36zse0QzvmgTZ3waWdRSA）</li>
 *   <li>请求头：dfid, clienttime, mid, kg-rc, kg-thash, kg-rec, kg-rf</li>
 * </ul>
 *
 * <p>核心接口：POST /youth/v1/ad/play_report
 * body: { ad_id: 12307537187, play_end, play_start }
 */
public final class KugouYouthEntitlementClient {

    private static final String TAG = "KugouAuthClient";

    private static final String PREFS = "music_youth_entitlement";
    private static final String KEY_DEVICE_ID = "device_id";

    /** 概念版领取 VIP 的广告位 ID（来自 KuGouMusicApi youth_vip.js） */
    private static final long AD_ID_DAILY_VIP = 12307537187L;

    private static final String BASE_URL = "https://gateway.kugou.com";
    private static final String PLAY_REPORT_URL = BASE_URL + "/youth/v1/ad/play_report";
    private static final String PLAY_STATUS_URL = BASE_URL + "/youth/v1/ad/play_status";

    /** 概念版配置（来自 KuGouMusicApi util/config.json） */
    private static final int LITE_APPID = 3116;
    private static final int LITE_CLIENTVER = 11440;
    /** 概念版 android 签名盐值 */
    private static final String LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";

    private static final String CLIENT_PACKAGE = "com.kugou.android.lite";
    private static final String USER_AGENT = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";

    private final SharedPreferences prefs;

    public KugouYouthEntitlementClient(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 领取酷狗概念版 VIP 权益。
     *
     * @param token  登录后的 access token
     * @param userid 用户 ID
     * @param mid    设备 MID
     * @param dfid   设备指纹 ID
     * @return 权益快照
     */
    public Entitlement claimDailyVip(String token, String userid, String mid, String dfid) throws Exception {
        Entitlement result = new Entitlement();

        // 调用 play_report 领取当日 VIP（核心接口，对应 youth_vip.js）
        try {
            JsonObject reportBody = playReportBody();
            Log.d(TAG, "Youth play_report body: " + reportBody);
            JsonObject reportResp = postSigned(PLAY_REPORT_URL, reportBody, token, userid, mid, dfid);
            Log.d(TAG, "Youth play_report response: " + reportResp);
            Entitlement reportEnt = Entitlement.from(reportResp);
            reportEnt.claimed = reportEnt.claimed || isSuccess(reportResp);
            result.merge(reportEnt);
        } catch (Exception e) {
            Log.w(TAG, "Youth play_report failed", e);
        }

        Log.d(TAG, "Youth entitlement final: hasVip=" + result.hasVip
                + " claimed=" + result.claimed
                + " userId=" + result.userId);
        return result;
    }

    /** 构造 play_report 请求体（对应 youth_vip.js） */
    private JsonObject playReportBody() {
        long now = System.currentTimeMillis();
        JsonObject body = new JsonObject();
        body.addProperty("ad_id", AD_ID_DAILY_VIP);
        body.addProperty("play_end", now);
        body.addProperty("play_start", now - 30_000L);
        return body;
    }

    /**
     * 发送带 android 签名的 POST 请求（与 KuGouMusicApi util/request.js 一致）。
     */
    private JsonObject postSigned(String urlStr, JsonObject body, String token, String userid,
                                   String mid, String dfid) throws Exception {
        String bodyJson = body.toString();
        byte[] bytes = bodyJson.getBytes(StandardCharsets.UTF_8);

        // 构造 query 参数（与 request.js defaultParams 一致）
        long clienttime = System.currentTimeMillis() / 1000;
        TreeMap<String, String> params = new TreeMap<>();
        params.put("dfid", dfid != null ? dfid : "-");
        params.put("mid", mid != null ? mid : "");
        params.put("uuid", "-");
        params.put("appid", String.valueOf(LITE_APPID));
        params.put("clientver", String.valueOf(LITE_CLIENTVER));
        params.put("clienttime", String.valueOf(clienttime));
        if (token != null && !token.isEmpty()) {
            params.put("token", token);
        }
        if (userid != null && !userid.isEmpty()) {
            params.put("userid", userid);
        }
        Log.d(TAG, "Youth postSigned: token=" + (token != null ? token.substring(0, Math.min(16, token.length())) + "..." : "null")
                + " userid=" + userid + " mid=" + mid + " dfid=" + dfid + " paramsKeys=" + params.keySet());

        // 计算 android 签名（概念版盐值）
        String signature = signatureAndroidLite(params, bodyJson);
        params.put("signature", signature);

        // 构造 URL
        StringBuilder urlBuilder = new StringBuilder(urlStr);
        urlBuilder.append("?");
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) urlBuilder.append("&");
            urlBuilder.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        String fullUrl = urlBuilder.toString();
        Log.d(TAG, "Youth POST " + fullUrl + " body=" + bodyJson);

        HttpURLConnection conn = (HttpURLConnection) new URL(fullUrl).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            // KuGouMusicApi request.js 中的请求头
            conn.setRequestProperty("dfid", params.get("dfid"));
            conn.setRequestProperty("clienttime", params.get("clienttime"));
            conn.setRequestProperty("mid", params.get("mid"));
            conn.setRequestProperty("kg-rc", "1");
            conn.setRequestProperty("kg-thash", "5d816a0");
            conn.setRequestProperty("kg-rec", "1");
            conn.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }

            int code = conn.getResponseCode();
            String respBody;
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder sb = new StringBuilder();
            if (is != null) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(is, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                }
            }
            respBody = sb.toString();
            Log.d(TAG, "Youth HTTP " + code + " resp=" + respBody);
            if (code < 200 || code >= 300) {
                throw new RuntimeException("HTTP " + code + ": " + respBody);
            }
            return JsonParser.parseString(respBody).getAsJsonObject();
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 概念版 android 签名（对应 helper.js signatureAndroidParams，platform=lite）。
     * 算法：MD5(salt + sorted("k=v" pairs) + bodyJson + salt)
     */
    private static String signatureAndroidLite(TreeMap<String, String> params, String bodyJson) {
        StringBuilder sb = new StringBuilder();
        sb.append(LITE_SALT);
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append(bodyJson != null ? bodyJson : "");
        sb.append(LITE_SALT);
        return md5(sb.toString());
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean isSuccess(JsonObject resp) {
        if (resp == null) return false;
        try {
            int status = resp.has("status") ? resp.get("status").getAsInt() : -1;
            int errCode = resp.has("error_code") ? resp.get("error_code").getAsInt() : -1;
            return status == 1 || errCode == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    @SuppressWarnings("unused")
    private String deviceId() {
        String value = prefs.getString(KEY_DEVICE_ID, null);
        if (value == null || value.isEmpty()) {
            value = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(KEY_DEVICE_ID, value).apply();
        }
        return value;
    }

    public static final class Entitlement {
        public boolean hasVip;
        public boolean claimEligible;
        public boolean claimed;
        public String nickname;
        public String userId;
        public String token;

        void merge(Entitlement other) {
            if (other == null) return;
            hasVip = hasVip || other.hasVip;
            claimEligible = claimEligible || other.claimEligible;
            claimed = claimed || other.claimed;
            if ((nickname == null || nickname.isEmpty()) && other.nickname != null) {
                nickname = other.nickname;
            }
            if ((userId == null || userId.isEmpty()) && other.userId != null) {
                userId = other.userId;
            }
            if ((token == null || token.isEmpty()) && other.token != null) {
                token = other.token;
            }
        }

        static Entitlement from(JsonObject resp) {
            Entitlement e = new Entitlement();
            if (resp == null) return e;
            JsonObject data = object(resp, "data");
            JsonObject root = data != null ? data : resp;
            e.hasVip = boolDeep(root,
                    "is_vip", "vip", "has_vip", "vip_status", "isVip",
                    "hasVip", "privilege", "has_privilege", "is_privilege");
            e.claimEligible = boolDeep(root,
                    "can_receive", "can_claim", "receive_eligible",
                    "claim_eligible", "has_qualification", "eligible");
            e.claimed = boolDeep(root,
                    "received", "claimed", "receive_success", "claim_success");
            e.nickname = strDeep(root, "nickname", "nick_name", "username", "user_name");
            e.userId = strDeep(root, "userid", "user_id", "uid");
            e.token = strDeep(root, "token", "clienttoken", "client_token");
            return e;
        }

        public boolean effectiveVip() {
            return hasVip || claimed || claimEligible;
        }
    }

    private static JsonObject object(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String str(JsonObject o, String... keys) {
        if (o == null) return "";
        for (String key : keys) {
            if (!o.has(key)) continue;
            try {
                JsonElement e = o.get(key);
                if (e != null && !e.isJsonNull()) return e.getAsString();
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static String strDeep(JsonObject o, String... keys) {
        String value = str(o, keys);
        if (!value.isEmpty()) return value;
        if (o == null) return "";
        for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
            JsonElement child = entry.getValue();
            if (child != null && child.isJsonObject()) {
                value = strDeep(child.getAsJsonObject(), keys);
                if (!value.isEmpty()) return value;
            }
        }
        return "";
    }

    private static boolean boolDeep(JsonObject o, String... keys) {
        if (bool(o, keys)) return true;
        if (o == null) return false;
        for (Map.Entry<String, JsonElement> entry : o.entrySet()) {
            JsonElement child = entry.getValue();
            if (child != null && child.isJsonObject() && boolDeep(child.getAsJsonObject(), keys)) {
                return true;
            }
        }
        return false;
    }

    private static boolean bool(JsonObject o, String... keys) {
        if (o == null) return false;
        for (String key : keys) {
            if (!o.has(key)) continue;
            try {
                JsonElement e = o.get(key);
                if (e == null || e.isJsonNull()) continue;
                if (e.getAsJsonPrimitive().isBoolean()) return e.getAsBoolean();
                if (e.getAsJsonPrimitive().isNumber()) return e.getAsInt() > 0;
                String value = e.getAsString();
                return "1".equals(value) || "true".equalsIgnoreCase(value)
                        || "yes".equalsIgnoreCase(value) || "vip".equalsIgnoreCase(value);
            } catch (Exception ignored) {
            }
        }
        return false;
    }
}
