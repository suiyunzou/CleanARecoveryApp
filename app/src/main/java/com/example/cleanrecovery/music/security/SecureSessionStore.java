package com.example.cleanrecovery.music.security;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

/**
 * Encrypted session persistence using Android Keystore-backed AES-256-GCM.
 *
 * <p>Stores the full authentication session (access token, refresh token, user
 * profile, expiry timestamps) as an encrypted JSON blob in SharedPreferences.
 * The encryption key is hardware-backed and never leaves the Keystore, so even
 * a rooted device cannot trivially extract the session.</p>
 *
 * <p>Token lifecycle:
 * <ul>
 *   <li>Access token: short-lived (1 hour default)</li>
 *   <li>Refresh token: long-lived (30 days default)</li>
 *   <li>If the refresh token expires, the user must re-authenticate</li>
 * </ul></p>
 */
public final class SecureSessionStore {

    private static final String PREFS = "music_session_secure";
    private static final String KEY_SESSION = "session_blob";

    // Default token lifetimes (milliseconds)
    public static final long DEFAULT_ACCESS_TTL_MS = 3_600_000L;       // 1 hour
    public static final long DEFAULT_REFRESH_TTL_MS = 2_592_000_000L;  // 30 days

    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public SecureSessionStore(Context context) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Persist a session, encrypting it before storage. */
    public void save(Session session) throws Exception {
        if (session == null) {
            clear();
            return;
        }
        String json = gson.toJson(session);
        String encrypted = CryptoUtils.encrypt(json);
        prefs.edit().putString(KEY_SESSION, encrypted).apply();
    }

    /** Read and decrypt the stored session. Returns null if none or decryption fails. */
    public Session restore() {
        String encrypted = prefs.getString(KEY_SESSION, null);
        if (encrypted == null || encrypted.isEmpty()) return null;
        try {
            String json = CryptoUtils.decrypt(encrypted);
            return gson.fromJson(json, Session.class);
        } catch (Exception e) {
            // Decryption failure likely means the key was invalidated (e.g., app reinstalled
            // on a device without keystore migration). Clear the corrupt blob.
            clear();
            return null;
        }
    }

    /** Remove all stored session data. */
    public void clear() {
        prefs.edit().clear().apply();
    }

    /** Check whether a valid (non-expired refresh token) session exists. */
    public boolean hasValidSession() {
        Session s = restore();
        return s != null && s.refreshExpiresAt > System.currentTimeMillis();
    }

    /** Check whether the access token is still valid (not expired). */
    public boolean isAccessTokenValid() {
        Session s = restore();
        return s != null
                && s.accessToken != null
                && !s.accessToken.isEmpty()
                && s.accessExpiresAt > System.currentTimeMillis();
    }

    /**
     * Serializable session data. All fields are transient to the caller;
     * only the encrypted blob is persisted.
     */
    public static class Session {
        @SerializedName("access_token")
        public String accessToken;

        @SerializedName("refresh_token")
        public String refreshToken;

        @SerializedName("token_type")
        public String tokenType;

        @SerializedName("user_id")
        public String userId;

        @SerializedName("nickname")
        public String nickname;

        @SerializedName("avatar_url")
        public String avatarUrl;

        @SerializedName("phone")
        public String phone;

        @SerializedName("is_vip")
        public boolean isVip;

        /** 登录响应中的 Set-Cookie 合并字符串，用于 Youth 接口鉴权 */
        @SerializedName("cookies")
        public String cookies;

        /** 登录时使用的设备指纹 ID，用于 Youth 接口鉴权 */
        @SerializedName("dfid")
        public String dfid;

        @SerializedName("access_expires_at")
        public long accessExpiresAt;

        @SerializedName("refresh_expires_at")
        public long refreshExpiresAt;

        public Session() {}

        public boolean isRefreshable() {
            return refreshToken != null
                    && !refreshToken.isEmpty()
                    && refreshExpiresAt > System.currentTimeMillis();
        }

        public boolean isAccessExpired() {
            return accessExpiresAt <= System.currentTimeMillis();
        }
    }
}
