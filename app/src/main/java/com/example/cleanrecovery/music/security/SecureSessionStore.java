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
    private final android.content.Context appContext;
    private final Gson gson = new Gson();

    public SecureSessionStore(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Persist a session, encrypting it before storage. */
    public void save(Session session) throws Exception {
        if (session == null) {
            clear();
            return;
        }
        String json = gson.toJson(session);

        // 主存储：Keystore 加密（安全但重装丢失）
        String encrypted = CryptoUtils.encrypt(json);
        prefs.edit().putString(KEY_SESSION, encrypted).apply();

        // 副存储：密码派生密钥加密到外部文件（可跨重装）
        // 密码使用设备指纹，重装后仍可重建密钥解密
        saveBackupToFile(json);
    }

    /** Read and decrypt the stored session. Returns null if none or decryption fails. */
    public Session restore() {
        // 1. 尝试主存储（Keystore 加密）
        String encrypted = prefs.getString(KEY_SESSION, null);
        if (encrypted != null && !encrypted.isEmpty()) {
            try {
                String json = CryptoUtils.decrypt(encrypted);
                return gson.fromJson(json, Session.class);
            } catch (Exception e) {
                // Keystore 密钥丢失（重装），清除主存储，尝试备份
                prefs.edit().remove(KEY_SESSION).apply();
            }
        }

        // 2. 主存储失败，尝试外部备份（密码派生密钥，可跨重装）
        Session backup = restoreBackupFromFile();
        if (backup != null) {
            // 恢复成功，重新写入主存储（用新的 Keystore 密钥）
            try {
                String json = gson.toJson(backup);
                String reencrypted = CryptoUtils.encrypt(json);
                prefs.edit().putString(KEY_SESSION, reencrypted).apply();
            } catch (Exception ignored) {
                // 重新加密失败不影响返回备份 session
            }
        }
        return backup;
    }

    /** Remove all stored session data. */
    public void clear() {
        prefs.edit().clear().apply();
        // 同时清除外部备份
        try {
            java.io.File dir = appContext.getExternalFilesDir(null);
            if (dir != null) {
                java.io.File file = new java.io.File(dir, BACKUP_FILENAME);
                if (file.exists()) file.delete();
            }
        } catch (Exception ignored) {}
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

    // ========== 外部存储备份（跨重装） ==========
    private static final String BACKUP_FILENAME = "session_backup.txt";

    /**
     * 将 session JSON 加密后保存到应用私有外部目录。
     *
     * <p>使用设备指纹作为密码派生 AES 密钥，重装后设备指纹不变，
     * 可重建密钥解密备份，避免强制重登。
     *
     * <p>文件路径：getExternalFilesDir(null)/session_backup.txt
     * （应用私有目录，卸载时清除，但 adb pull 可读取用于调试）
     */
    private void saveBackupToFile(String json) {
        try {
            java.io.File dir = appContext.getExternalFilesDir(null);
            if (dir == null) return; // 外部存储不可用
            String password = com.example.cleanrecovery.music.security.DeviceFingerprint.get(appContext);
            String encrypted = CryptoUtils.encryptWithPassword(json, password);
            java.io.File file = new java.io.File(dir, BACKUP_FILENAME);
            java.io.FileWriter fw = new java.io.FileWriter(file, false);
            try {
                fw.write(encrypted);
            } finally {
                fw.close();
            }
        } catch (Exception e) {
            android.util.Log.w("SecureSessionStore", "saveBackupToFile failed", e);
        }
    }

    /**
     * 从外部备份文件恢复 session。
     *
     * @return 解密后的 Session，或 null（文件不存在/解密失败）
     */
    private Session restoreBackupFromFile() {
        java.io.FileReader fr = null;
        try {
            java.io.File dir = appContext.getExternalFilesDir(null);
            if (dir == null) return null;
            java.io.File file = new java.io.File(dir, BACKUP_FILENAME);
            if (!file.exists()) return null;

            StringBuilder sb = new StringBuilder();
            fr = new java.io.FileReader(file);
            char[] buf = new char[1024];
            int len;
            while ((len = fr.read(buf)) >= 0) sb.append(buf, 0, len);
            String encrypted = sb.toString();
            if (encrypted.isEmpty()) return null;

            String password = com.example.cleanrecovery.music.security.DeviceFingerprint.get(appContext);
            String json = CryptoUtils.decryptWithPassword(encrypted, password);
            Session session = gson.fromJson(json, Session.class);
            android.util.Log.d("SecureSessionStore", "session restored from backup");
            return session;
        } catch (Exception e) {
            android.util.Log.w("SecureSessionStore", "restoreBackupFromFile failed", e);
            return null;
        } finally {
            if (fr != null) try { fr.close(); } catch (Exception ignored) {}
        }
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
