package com.example.cleanrecovery.music.api;

import android.content.Context;
import android.util.Log;

import com.example.cleanrecovery.music.security.DeviceFingerprint;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * HTTP client for Kugou's remote authentication API.
 *
 * <p>Uses the real Kugou login endpoints reverse-engineered from the Kugou Lite APK
 * and cross-referenced with the KuGouMusicApi open-source project:
 * <ul>
 *   <li>Send SMS: {@code http://login.user.kugou.com/v7/send_mobile_code} (POST)</li>
 *   <li>Login by code: {@code http://login.user.kugou.com/v7/login_by_verifycode} (POST)</li>
 *   <li>Token refresh: {@code http://login.user.kugou.com/v4/login_by_token} (GET)</li>
 * </ul></p>
 *
 * <p>Requests use Kugou's Android parameter format:
 * <ul>
 *   <li>Query params: appid, clientver, clienttime, mid, dfid, uuid, signature</li>
 *   <li>POST body: JSON object with endpoint-specific fields</li>
 *   <li>Signature: MD5(secret + sorted(query_params as key=value) + JSON.stringify(body) + secret)</li>
 * </ul></p>
 *
 * <p>Responses follow Kugou's JSON envelope:
 * {@code {"status": 1, "data": {...}}} for success,
 * {@code {"status": 0, "error_code": N}} for failure.</p>
 */
public final class KugouAuthClient {

    private static final String TAG = "KugouAuthClient";

    // ---- Endpoint configuration (real Kugou APIs) -------------------------

    // Lite (concept edition) uses v7 for both send_mobile_code and login_by_verifycode.
    // The login endpoint requires AES-encrypted {mobile, code} as "params",
    // RSA-encrypted {clienttime_ms, key} as "pk", plus t1/t2 device tokens.
    private static final String URL_SEND_CODE   = "http://login.user.kugou.com/v7/send_mobile_code";
    private static final String URL_LOGIN       = "http://login.user.kugou.com/v7/login_by_verifycode";
    private static final String URL_REFRESH     = "http://login.user.kugou.com/v4/login_by_token";

    // Kugou Lite (concept edition) configuration.
    // appid=3116 and clientver=11440 are extracted from the real Kugou Lite APK
    // configuration (listen.usersdkparam.appid / appkey) and confirmed by the
    // KuGouMusicApi open-source project (util/config.json).
    private static final String APP_ID          = "3116";
    private static final String CLIENT_VERSION  = "11440";
    private static final String SIGN_SECRET     = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";

    // RSA public key for Kugou Lite (concept edition), extracted from
    // KuGouMusicApi util/crypto.js (publicLiteRasKey).
    // Used to encrypt {clienttime_ms, key} as the "pk" parameter in login.
    private static final String RSA_LITE_PUBLIC_KEY_B64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYKJesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB";

    // Lite AES keys for t1/t2 device tokens (from login_cellphone.js).
    private static final String LITE_T1_KEY = "5e4ef500e9597fe004bd09a46d8add98";
    private static final String LITE_T1_IV  = "04bd09a46d8add98";
    private static final String LITE_T2_KEY = "fd14b35e3f81af3817a20ae7adae7020";
    private static final String LITE_T2_IV  = "17a20ae7adae7020";
    private static final String LITE_GITVERSION = "5f0b7c4";

    // Internal Kugou headers (extracted from KuGouMusicApi request.js).
    // These identify the request as coming from a genuine Kugou Android client.
    private static final String HEADER_KG_RC    = "1";
    private static final String HEADER_KG_THASH = "5d816a0";
    private static final String HEADER_KG_RF    = "B9EDA08A64250DEFFBCADDEE00F8F25F";
    private static final String USER_AGENT      = "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS    = 15_000;

    // ---- Fields ------------------------------------------------------------

    private final Context context;  // Needed for DeviceFingerprint.getGuid() in login
    private final String mid;   // Kugou MID (MD5 of GUID → decimal big integer)
    private final String dfid;  // device fingerprint id ('-' when unregistered)
    private final String uuid;  // device UUID ('-' matching KuGouMusicApi behavior)
    private final Gson gson = new Gson();

    /** 最近一次 HTTP 响应的 Set-Cookie 合并字符串（如 "k=v; k2=v2"） */
    private String lastResponseCookies = "";

    public KugouAuthClient(Context context) {
        this.context = context.getApplicationContext();
        // Kugou MID: MD5(GUID) converted to decimal big integer.
        // This matches KuGouMusicApi's calculateMid() algorithm.
        this.mid = DeviceFingerprint.getMid(context);
        // dfid is obtained from the register_dev endpoint, which uses complex
        // AES+RSA encryption. When unregistered, KuGouMusicApi uses '-'.
        this.dfid = "-";
        // uuid is also '-' in KuGouMusicApi's default request parameters.
        this.uuid = "-";
    }

    // ---- Public API --------------------------------------------------------

    /**
     * Request the server to send an SMS verification code to the given phone.
     * The code is generated and stored server-side — never on the device.
     *
     * <p>POST body: {@code {"businessid": 5, "mobile": phone, "plat": 3}}</p>
     *
     * @param phone 11-digit mainland China mobile number
     * @throws AuthException on validation, network, or server errors
     */
    public void sendSmsCode(String phone) throws AuthException {
        // Build POST body (JSON)
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("businessid", 5);   // 5 = login business
        body.put("mobile", phone);
        body.put("plat", 3);         // 3 = mobile platform

        // Build query params (default Android params)
        TreeMap<String, String> params = buildDefaultParams();

        JsonObject resp = post(URL_SEND_CODE, params, body);
        ensureSuccess(resp, "Failed to send verification code");
    }

    /**
     * Verify the SMS code with the server and obtain access/refresh tokens.
     *
     * <p>Implements Kugou Lite's login flow (from KuGouMusicApi login_cellphone.js):
     * <ol>
     *   <li>AES-encrypt {@code {mobile, code}} with a random key → {@code params}</li>
     *   <li>RSA-encrypt {@code {clienttime_ms, key}} → {@code pk}</li>
     *   <li>Compute {@code key = MD5(appid + secret + clientver + clienttime_ms)}</li>
     *   <li>Compute {@code t1 = AES("|" + clienttime_ms)} with fixed Lite key</li>
     *   <li>Compute {@code t2 = AES(guid + "|...|" + clienttime_ms)} with fixed Lite key</li>
     *   <li>Mask mobile as {@code "XX*****X"}</li>
     * </ol></p>
     *
     * @param phone 11-digit phone number
     * @param code  6-digit verification code entered by the user
     * @return AuthTokens containing access token, refresh token, and expiry
     * @throws AuthException on mismatch, expiry, or server errors
     */
    public AuthTokens loginWithCode(String phone, String code) throws AuthException {
        long clienttimeMs = System.currentTimeMillis();

        // 1. AES-encrypt {mobile, code} with a random 16-char key
        AesResult encrypt = cryptoAesEncryptWithRandomKey(
                "{\"mobile\":\"" + phone + "\",\"code\":\"" + code + "\"}");

        // 2. RSA-encrypt {clienttime_ms, key} → pk (uppercase hex)
        String pkJson = "{\"clienttime_ms\":" + clienttimeMs
                + ",\"key\":\"" + encrypt.key + "\"}";
        String pk = rsaLiteEncrypt(pkJson);

        // 3. signParamsKey = MD5(appid + secret + clientver + clienttime_ms)
        String signKey = md5(APP_ID + SIGN_SECRET + CLIENT_VERSION + clienttimeMs);

        // 4. t1 = AES-CBC("|" + clienttime_ms, liteT1Key, liteT1Iv)
        String t1 = cryptoAesEncryptFixed("|" + clienttimeMs, LITE_T1_KEY, LITE_T1_IV);

        // 5. t2 = AES-CBC(guid + "|0f607264fc6318a92b9e13c65db7cd3c|||" + clienttimeMs, liteT2Key, liteT2Iv)
        //    GUID from DeviceFingerprint; MAC/DEV left empty (matching KuGouMusicApi defaults)
        String guid = DeviceFingerprint.getGuid(context);
        String t2Input = guid + "|0f607264fc6318a92b9e13c65db7cd3c|||" + clienttimeMs;
        String t2 = cryptoAesEncryptFixed(t2Input, LITE_T2_KEY, LITE_T2_IV);

        // 6. Mask mobile: "XX*****X" (first 2 + ***** + char at index 10)
        String maskedMobile = phone.substring(0, 2) + "*****" + phone.substring(10, 11);

        // 7. dfid = random 24-char string
        String dfidLocal = randomString(24);

        // 8. Build POST body
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("plat", 1);
        body.put("support_multi", 1);
        body.put("t1", t1);
        body.put("t2", t2);
        body.put("clienttime_ms", clienttimeMs);
        body.put("mobile", maskedMobile);
        body.put("key", signKey);
        body.put("pk", pk);
        body.put("params", encrypt.str);
        body.put("dfid", dfidLocal);
        body.put("dev", "");
        body.put("gitversion", LITE_GITVERSION);

        // 9. Build query params and send
        TreeMap<String, String> params = buildDefaultParams();
        JsonObject resp = post(URL_LOGIN, params, body);
        ensureSuccess(resp, "Login failed");

        JsonObject data = resp.getAsJsonObject("data");

        // Token may be encrypted in secu_params — decrypt with the AES key
        String accessToken;
        String refreshToken = "";
        String userId = "";
        String nickname = "";
        String avatarUrl = "";
        boolean isVip = false;

        if (data.has("secu_params")) {
            String secuParams = optString(data, "secu_params");
            String decrypted = cryptoAesDecrypt(secuParams, encrypt.key);
            Log.d(TAG, "Decrypted secu_params: " + decrypted);
            try {
                JsonObject tokenData = JsonParser.parseString(decrypted).getAsJsonObject();
                accessToken = optString(tokenData, "token");
                refreshToken = optString(tokenData, "refreshtoken");
                // secu_params 中通常只有 token，userid/nickname 等从 data 补充
                userId = optString(tokenData, "userid");
                nickname = optString(tokenData, "nickname");
                avatarUrl = optString(tokenData, "imgurl");
                isVip = optInt(tokenData, "vip_type", 0) > 0;
            } catch (Exception e) {
                // If decryption yields a plain token string
                accessToken = decrypted;
            }
            // 从 data 补充 secu_params 中缺失的字段
            if (userId == null || userId.isEmpty()) userId = optString(data, "userid");
            if (nickname == null || nickname.isEmpty()) nickname = optString(data, "nickname");
            if (avatarUrl == null || avatarUrl.isEmpty()) avatarUrl = optString(data, "pic");
            if (!isVip) isVip = optInt(data, "vip_type", 0) > 0 || optInt(data, "is_vip", 0) > 0;
        } else {
            accessToken = optString(data, "token");
            refreshToken = optString(data, "refreshtoken");
            userId = optString(data, "userid");
            nickname = optString(data, "nickname");
            avatarUrl = optString(data, "imgurl");
            isVip = optInt(data, "vip_type", 0) > 0;
        }

        AuthTokens tokens = new AuthTokens();
        tokens.accessToken = accessToken;
        tokens.refreshToken = refreshToken;
        tokens.tokenType = "Bearer";
        tokens.expiresIn = 3600;
        tokens.refreshExpiresIn = 2592000; // 30 days
        tokens.userId = userId;
        tokens.nickname = nickname;
        tokens.avatarUrl = avatarUrl;
        tokens.isVip = isVip;
        tokens.cookies = lastResponseCookies;
        tokens.dfid = dfidLocal;

        return tokens;
    }

    /**
     * Exchange a refresh token for a new access token.
     *
     * @param refreshToken the refresh token from a previous login
     * @return AuthTokens with refreshed access token
     * @throws AuthException if the refresh token is invalid or expired
     */
    public AuthTokens refreshToken(String refreshToken) throws AuthException {
        TreeMap<String, String> params = buildDefaultParams();
        params.put("token", refreshToken);

        JsonObject resp = get(URL_REFRESH, params);
        ensureSuccess(resp, "Token refresh failed");

        JsonObject data = resp.getAsJsonObject("data");
        AuthTokens tokens = new AuthTokens();
        tokens.accessToken = optString(data, "token");
        tokens.refreshToken = optString(data, "refreshtoken", refreshToken);
        tokens.tokenType = "Bearer";
        tokens.expiresIn = optLong(data, "expire_time", 3600) - System.currentTimeMillis() / 1000;
        if (tokens.expiresIn < 0) tokens.expiresIn = 3600;
        tokens.refreshExpiresIn = 2592000;
        return tokens;
    }

    /**
     * Fetch the current user's profile from the server.
     *
     * @param accessToken a valid (non-expired) access token
     * @return UserProfile with the latest user data
     * @throws AuthException on auth or network errors
     */
    public UserProfile getUserProfile(String accessToken) throws AuthException {
        // Kugou's login response already embeds user info; we don't have a
        // dedicated profile endpoint that works with the Lite token. Return
        // an empty profile — the caller (RemoteAuthService) has already
        // populated session fields from the login response, and the Youth
        // entitlement client will fill in VIP status separately.
        UserProfile profile = new UserProfile();
        return profile;
    }

    /**
     * Invalidate the current session on the server.
     *
     * @param accessToken the token to invalidate
     */
    public void logout(String accessToken) throws AuthException {
        // Best-effort: clear locally; no dedicated logout endpoint needed
    }

    // ---- HTTP plumbing: POST ----------------------------------------------

    /**
     * Builds a signed POST request to a Kugou endpoint.
     *
     * <p>Query params carry the default Android parameters plus the signature.
     * The POST body is sent as JSON ({@code application/json}).</p>
     *
     * <p>Signature algorithm (Android):
     * {@code MD5(secret + sorted("key=value" pairs from query params) + JSON.stringify(body) + secret)}</p>
     *
     * @param baseUrl the endpoint URL
     * @param params  query parameters (WITHOUT signature)
     * @param body    POST body as a map (will be JSON-serialized)
     * @return parsed JSON response
     * @throws AuthException on network or parse errors
     */
    private JsonObject post(String baseUrl, TreeMap<String, String> params, TreeMap<String, Object> body) throws AuthException {
        // Serialize body to JSON (sorted keys for deterministic signature)
        String bodyJson = gson.toJson(body);

        // Compute signature: MD5(secret + sorted(query_params as key=value) + bodyJson + secret)
        String sign = computeAndroidSign(params, bodyJson, SIGN_SECRET);
        params.put("signature", sign);

        HttpURLConnection conn = null;
        try {
            String query = buildQuery(params);
            URL url = new URL(baseUrl + "?" + query);
            Log.d(TAG, "POST " + baseUrl + " body=" + bodyJson + " mid=" + mid);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            // Kugou internal headers (required for device verification)
            applyKugouHeaders(conn, params);

            // Write JSON body
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            // 捕获 Set-Cookie 头（酷狗登录态 cookie，用于 Youth 接口）
            lastResponseCookies = extractCookies(conn);
            String responseBody = readStream(conn, code);
            Log.d(TAG, "Response HTTP " + code + ": " + responseBody);
            return parseResponse(responseBody, code);
        } catch (java.net.SocketTimeoutException e) {
            throw new AuthException(AuthException.Code.NETWORK_ERROR, "Request timed out", e);
        } catch (IOException e) {
            throw new AuthException(AuthException.Code.NETWORK_ERROR, "Network error: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // ---- HTTP plumbing: GET -----------------------------------------------

    /**
     * Builds a signed GET request to a Kugou endpoint.
     * Adds the signature parameter computed as
     * MD5(secret + sorted "key=value" pairs + secret).
     */
    private JsonObject get(String baseUrl, TreeMap<String, String> params) throws AuthException {
        // For GET requests, there is no body, so data="" in the signature
        String sign = computeAndroidSign(params, "", SIGN_SECRET);
        params.put("signature", sign);

        HttpURLConnection conn = null;
        try {
            String query = buildQuery(params);
            URL url = new URL(baseUrl + "?" + query);
            Log.d(TAG, "GET " + baseUrl + " mid=" + mid);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            // Kugou internal headers (required for device verification)
            applyKugouHeaders(conn, params);

            int code = conn.getResponseCode();
            String responseBody = readStream(conn, code);
            Log.d(TAG, "Response HTTP " + code + ": " + responseBody);
            return parseResponse(responseBody, code);
        } catch (java.net.SocketTimeoutException e) {
            throw new AuthException(AuthException.Code.NETWORK_ERROR, "Request timed out", e);
        } catch (IOException e) {
            throw new AuthException(AuthException.Code.NETWORK_ERROR, "Network error: " + e.getMessage(), e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * Applies Kugou internal headers to an HTTP connection.
     *
     * <p>These headers (extracted from KuGouMusicApi's request.js) are required
     * for the server to accept the request as coming from a genuine Kugou
     * Android client. Without them, the server may return error 20028
     * ("device verification required").</p>
     */
    private void applyKugouHeaders(HttpURLConnection conn, TreeMap<String, String> params) {
        String clienttime = params.get("clienttime");
        if (clienttime == null) clienttime = String.valueOf(System.currentTimeMillis() / 1000);
        conn.setRequestProperty("dfid", dfid);
        conn.setRequestProperty("mid", mid);
        conn.setRequestProperty("clienttime", clienttime);
        conn.setRequestProperty("kg-rc", HEADER_KG_RC);
        conn.setRequestProperty("kg-thash", HEADER_KG_THASH);
        conn.setRequestProperty("kg-rec", "1");
        conn.setRequestProperty("kg-rf", HEADER_KG_RF);
    }

    // ---- AES encryption (Kugou Lite login) --------------------------------

    /** Result of AES encryption with a random key: ciphertext hex + the key. */
    private static class AesResult {
        final String str;  // hex-encoded ciphertext
        final String key;  // random 16-char key used for encryption
        AesResult(String str, String key) { this.str = str; this.key = key; }
    }

    /**
     * AES-CBC-PKCS7 encrypt with a random 16-char key (matching cryptoAesEncrypt
     * without opt). Key derivation: key=MD5(tempKey)[0:32], iv=MD5(tempKey)[16:32].
     *
     * @param plaintext UTF-8 string to encrypt
     * @return AesResult with hex ciphertext and the random key
     */
    private static AesResult cryptoAesEncryptWithRandomKey(String plaintext) throws AuthException {
        String tempKey = randomString(16).toLowerCase();
        String fullMd5 = md5(tempKey);              // 32-char hex
        String key = fullMd5.substring(0, 32);      // full MD5 (32 chars)
        String iv = fullMd5.substring(16);          // last 16 chars
        String hex = aesCbcEncrypt(plaintext, key, iv);
        return new AesResult(hex, tempKey);
    }

    /**
     * AES-CBC-PKCS7 encrypt with a fixed key and IV (matching cryptoAesEncrypt
     * with opt.key and opt.iv). Used for t1/t2 tokens.
     *
     * @param plaintext UTF-8 string to encrypt
     * @param key       32-char hex key
     * @param iv        16-char hex IV
     * @return hex-encoded ciphertext
     */
    private static String cryptoAesEncryptFixed(String plaintext, String key, String iv) throws AuthException {
        return aesCbcEncrypt(plaintext, key, iv);
    }

    /**
     * AES-CBC-PKCS7 decrypt (matching cryptoAesDecrypt).
     * Key derivation when no IV: key=MD5(tempKey)[0:32], iv=MD5(tempKey)[16:32].
     *
     * @param hexCipher hex-encoded ciphertext
     * @param tempKey   the random key used during encryption
     * @return decrypted UTF-8 string
     */
    private static String cryptoAesDecrypt(String hexCipher, String tempKey) throws AuthException {
        String fullMd5 = md5(tempKey);
        String key = fullMd5.substring(0, 32);
        String iv = fullMd5.substring(16);
        return aesCbcDecrypt(hexCipher, key, iv);
    }

    /**
     * Raw AES-CBC-PKCS7 encrypt. Key and IV are ASCII strings (used as UTF-8 bytes).
     *
     * @param plaintext UTF-8 string
     * @param key       32-char ASCII string (used as 32-byte key)
     * @param iv        16-char ASCII string (used as 16-byte IV)
     * @return hex-encoded ciphertext
     */
    private static String aesCbcEncrypt(String plaintext, String key, String iv) throws AuthException {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            javax.crypto.spec.SecretKeySpec keySpec =
                    new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            javax.crypto.spec.IvParameterSpec ivSpec =
                    new javax.crypto.spec.IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keySpec, ivSpec);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(encrypted);
        } catch (Exception e) {
            throw new AuthException(AuthException.Code.SERVER_ERROR, "AES encryption failed", e);
        }
    }

    /**
     * Raw AES-CBC-PKCS7 decrypt.
     *
     * @param hexCipher hex-encoded ciphertext
     * @param key       32-char ASCII string
     * @param iv        16-char ASCII string
     * @return decrypted UTF-8 string
     */
    private static String aesCbcDecrypt(String hexCipher, String key, String iv) throws AuthException {
        try {
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            javax.crypto.spec.SecretKeySpec keySpec =
                    new javax.crypto.spec.SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
            javax.crypto.spec.IvParameterSpec ivSpec =
                    new javax.crypto.spec.IvParameterSpec(iv.getBytes(StandardCharsets.UTF_8));
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, ivSpec);
            byte[] decrypted = cipher.doFinal(hexToBytes(hexCipher));
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AuthException(AuthException.Code.SERVER_ERROR, "AES decryption failed", e);
        }
    }

    /** Convert bytes to lowercase hex string. */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** Convert hex string to bytes. */
    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    /** Generate a random alphanumeric string of the given length. */
    private static String randomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }

    // ---- RSA encryption (Kugou Lite login) --------------------------------

    /**
     * Encrypts data using Kugou Lite's RSA public key with raw RSA (no padding).
     *
     * <p>This matches KuGouMusicApi's cryptoRSAEncrypt() implementation:
     * <ol>
     *   <li>Convert input string to UTF-8 bytes</li>
     *   <li>Left-pad with zeros to the RSA key length (128 bytes for 1024-bit key)</li>
     *   <li>Interpret as a big integer and compute m^e mod n</li>
     *   <li>Output as uppercase hex string, left-padded to 256 chars (128 bytes * 2)</li>
     * </ol></p>
     *
     * <p>Note: This is raw RSA without any padding scheme (PKCS1/OAEP).
     * While insecure for general use, it matches Kugou's server expectation.</p>
     *
     * @param data JSON string to encrypt
     * @return uppercase hex-encoded RSA ciphertext
     * @throws AuthException if encryption fails
     */
    private static String rsaLiteEncrypt(String data) throws AuthException {
        try {
            // Parse the Lite RSA public key (X.509 SubjectPublicKeyInfo format)
            byte[] keyBytes = java.util.Base64.getDecoder().decode(RSA_LITE_PUBLIC_KEY_B64);
            java.security.spec.X509EncodedKeySpec keySpec = new java.security.spec.X509EncodedKeySpec(keyBytes);
            java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
            java.security.interfaces.RSAPublicKey pubKey =
                    (java.security.interfaces.RSAPublicKey) kf.generatePublic(keySpec);

            java.math.BigInteger modulus = pubKey.getModulus();
            java.math.BigInteger exponent = pubKey.getPublicExponent();
            int keyLength = (modulus.bitLength() + 7) / 8; // bytes

            // Convert data to UTF-8 bytes
            byte[] dataBytes = data.getBytes(StandardCharsets.UTF_8);

            // Right-pad with zeros to key length (matching JS:
            //   padded = new Uint8Array(keyLength); padded.set(buffer);
            // data goes at the START (high-order bytes), zeros at the END.
            byte[] padded = new byte[keyLength];
            System.arraycopy(dataBytes, 0, padded, 0, dataBytes.length);

            // Convert to BigInteger (positive) and compute m^e mod n
            java.math.BigInteger message = new java.math.BigInteger(1, padded);
            java.math.BigInteger encrypted = message.modPow(exponent, modulus);

            // Convert to hex string, left-padded to keyLength*2 chars
            String hex = encrypted.toString(16);
            while (hex.length() < keyLength * 2) {
                hex = "0" + hex;
            }
            return hex.toUpperCase();
        } catch (Exception e) {
            Log.e(TAG, "RSA encryption failed", e);
            throw new AuthException(AuthException.Code.SERVER_ERROR, "Encryption failed", e);
        }
    }

    // ---- Signature computation --------------------------------------------

    /**
     * Computes the Kugou Android API signature.
     *
     * <p>Algorithm:
     * {@code MD5(secret + sorted("key=value" pairs from params) + bodyData + secret)}</p>
     *
     * <p>The secret is both prepended and appended. Parameters are sorted by
     * key (TreeMap natural ordering) and formatted as {@code key=value} with
     * no separator between pairs. The POST body (JSON string) is appended
     * after the params and before the trailing secret.</p>
     *
     * @param params   query parameters (excluding signature itself)
     * @param bodyData JSON-serialized POST body, or empty string for GET
     * @param secret   the signing secret
     * @return hex-encoded MD5 signature (32 chars, lowercase)
     */
    private static String computeAndroidSign(TreeMap<String, String> params, String bodyData, String secret) {
        StringBuilder sb = new StringBuilder();
        sb.append(secret);
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append(bodyData);
        sb.append(secret);
        return md5(sb.toString());
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // ---- Helpers ----------------------------------------------------------

    /**
     * Builds the default Android query parameters for Kugou API requests.
     * Includes appid, clientver, clienttime, mid, dfid, uuid.
     *
     * <p>Matches KuGouMusicApi's defaultParams in request.js:
     * dfid='-', mid=calculateMid(guid), uuid='-', appid, clientver, clienttime.</p>
     */
    private TreeMap<String, String> buildDefaultParams() {
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appid", APP_ID);
        params.put("clientver", CLIENT_VERSION);
        params.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000));
        params.put("mid", mid);
        params.put("dfid", dfid);
        params.put("uuid", uuid);
        return params;
    }

    private static String buildQuery(TreeMap<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append("&");
            first = false;
            sb.append(e.getKey()).append("=").append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private String readStream(HttpURLConnection conn, int responseCode) throws IOException {
        BufferedReader reader;
        if (responseCode >= 200 && responseCode < 300) {
            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        } else {
            java.io.InputStream es = conn.getErrorStream();
            reader = new BufferedReader(new InputStreamReader(
                    es != null ? es : conn.getInputStream(), StandardCharsets.UTF_8));
        }
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        return sb.toString();
    }

    /**
     * 从 HTTP 响应中提取 Set-Cookie 头，合并为 "k=v; k2=v2" 格式。
     * 酷狗登录态 cookie（如 kg_u_list, musicwenji, kg_mid）用于 Youth 接口鉴权。
     */
    private String extractCookies(HttpURLConnection conn) {
        Map<String, java.util.List<String>> headers = conn.getHeaderFields();
        java.util.List<String> setCookies = headers.get("Set-Cookie");
        if (setCookies == null || setCookies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String sc : setCookies) {
            if (sc == null) continue;
            int semi = sc.indexOf(';');
            String kv = (semi > 0) ? sc.substring(0, semi) : sc;
            if (sb.length() > 0) sb.append("; ");
            sb.append(kv);
        }
        String result = sb.toString();
        if (!result.isEmpty()) {
            Log.d(TAG, "Response Set-Cookie: " + result);
        }
        return result;
    }

    private JsonObject parseResponse(String body, int httpCode) throws AuthException {
        if (body == null || body.isEmpty()) {
            throw new AuthException(AuthException.Code.SERVER_ERROR,
                    "Empty response (HTTP " + httpCode + ")");
        }
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (httpCode == 401) {
                throw new AuthException(AuthException.Code.TOKEN_EXPIRED, "Unauthorized");
            }
            if (httpCode == 429) {
                throw new AuthException(AuthException.Code.RATE_LIMITED, "Too many requests");
            }
            if (httpCode >= 500) {
                throw new AuthException(AuthException.Code.SERVER_ERROR,
                        "Server error (HTTP " + httpCode + ")");
            }
            return json;
        } catch (com.google.gson.JsonParseException e) {
            throw new AuthException(AuthException.Code.SERVER_ERROR,
                    "Malformed server response", e);
        }
    }

    /**
     * Checks Kugou's response envelope for errors.
     * Success: {"status": 1, "data": {...}}
     * Failure: {"status": 0, "error_code": N}
     */
    private void ensureSuccess(JsonObject resp, String defaultMessage) throws AuthException {
        int status = optInt(resp, "status", -1);
        if (status == 1) return; // Success

        int errCode = optInt(resp, "error_code", -1);
        String errMsg = optString(resp, "data");

        // Map Kugou error codes to AuthException codes
        switch (errCode) {
            case 20008: // mobile number not allowed / blocked
                throw new AuthException(AuthException.Code.PHONE_NOT_ALLOWED,
                        "该手机号暂不支持，请更换手机号");
            case 20010: // verify code wrong or phone not registered
                throw new AuthException(AuthException.Code.CODE_MISMATCH,
                        "验证码不正确或手机号未注册");
            case 20007: // code expired
                throw new AuthException(AuthException.Code.CODE_EXPIRED,
                        "验证码已过期");
            case 20003: // sms send failed
                throw new AuthException(AuthException.Code.SMS_SEND_FAILED,
                        "短信发送失败");
            case 20004: // phone not allowed
                throw new AuthException(AuthException.Code.PHONE_NOT_ALLOWED,
                        "该手机号不允许登录");
            case 20028: // device verification required (请先通过验证)
                throw new AuthException(AuthException.Code.DEVICE_VERIFICATION_REQUIRED,
                        "设备验证失败，请稍后重试或更换网络环境");
            case 34182: // account does not exist (酷狗概念版不支持手机号自动注册)
                throw new AuthException(AuthException.Code.PHONE_NOT_ALLOWED,
                        "该账号不存在。酷狗仅支持已注册账号手机号登录，新用户请用QQ/微信注册后再绑定手机号");
            case 20001: // token expired
                throw new AuthException(AuthException.Code.TOKEN_EXPIRED,
                        "登录已过期，请重新登录");
            case 20002: // session invalid
                throw new AuthException(AuthException.Code.SESSION_INVALID,
                        "会话无效，请重新登录");
            case 20006: // interface verification failed (signature error)
                throw new AuthException(AuthException.Code.SERVER_ERROR,
                        "接口验证失败，请检查网络或更新应用");
            default:
                String detail = errMsg != null ? " (" + errMsg + ")" : "";
                throw new AuthException(AuthException.Code.UNKNOWN,
                        "登录失败 (错误码: " + errCode + detail + ")");
        }
    }

    // ---- Gson helpers ------------------------------------------------------

    private static String optString(JsonObject obj, String key) {
        return optString(obj, key, null);
    }

    private static String optString(JsonObject obj, String key, String def) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsString();
            } catch (Exception e) {
                return def;
            }
        }
        return def;
    }

    private static long optLong(JsonObject obj, String key, long def) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsLong();
            } catch (Exception e) {
                return def;
            }
        }
        return def;
    }

    private static int optInt(JsonObject obj, String key, int def) {
        if (obj != null && obj.has(key) && !obj.get(key).isJsonNull()) {
            try {
                return obj.get(key).getAsInt();
            } catch (Exception e) {
                return def;
            }
        }
        return def;
    }

    // ---- Response data classes ---------------------------------------------

    public static class AuthTokens {
        public String accessToken;
        public String refreshToken;
        public String tokenType;
        public long expiresIn;          // seconds
        public long refreshExpiresIn;   // seconds

        // Optional user info embedded in login response
        public String userId;
        public String nickname;
        public String avatarUrl;
        public boolean isVip;

        /** 登录响应中 Set-Cookie 头的合并字符串（如 "k=v; k2=v2"），用于后续 Youth 接口 */
        public String cookies;

        /** 登录时使用的设备指纹 ID（dfid），用于后续 Youth 接口鉴权 */
        public String dfid;
    }

    public static class UserProfile {
        public String userId;
        public String nickname;
        public String avatarUrl;
        public boolean isVip;
    }
}
