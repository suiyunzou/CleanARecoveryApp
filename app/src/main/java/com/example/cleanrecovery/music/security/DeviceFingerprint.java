package com.example.cleanrecovery.music.security;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * Generates a stable, privacy-preserving device fingerprint for device binding.
 *
 * <p>Combines {@link android.provider.Settings.Secure#ANDROID_ID} with hardware
 * identifiers (manufacturer, model, build fingerprint) and hashes them with
 * SHA-256. The raw identifiers are never transmitted — only the hash.</p>
 *
 * <p>Also provides Kugou-compatible device identifiers:
 * <ul>
 *   <li>{@link #getGuid(Context)} — persistent UUID v4</li>
 *   <li>{@link #getMid(Context)} — Kugou MID (MD5 of GUID converted to decimal)</li>
 * </ul></p>
 */
public final class DeviceFingerprint {

    private static final String PREFS = "music_device";
    private static final String KEY_FINGERPRINT = "device_fp";
    private static final String KEY_GUID = "device_guid";
    /** 公共持久目录镜像：卸载不清除，重装后回种指纹/会话密钥，避免被迫二次登录。 */
    private static final String MIRROR_DIR = "DataRecovery";
    private static final String MIRROR_FILE = ".music_device_id";
    private static volatile String cached;
    private static volatile String cachedGuid;
    private static volatile String cachedMid;

    private DeviceFingerprint() {}

    /** Returns a 64-character hex SHA-256 fingerprint, stable across app restarts. */
    public static String get(Context context) {
        if (cached != null) return cached;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_FINGERPRINT, null);
        if (stored != null && stored.length() == 64) {
            cached = stored;
            mirror(context);
            return cached;
        }
        // 重装场景：prefs 已清，先看公共目录镜像（指纹是会话备份的解密密钥来源）
        String mirrored = readMirror(context).fp;
        if (mirrored != null && mirrored.length() == 64) {
            cached = mirrored;
        } else {
            cached = generate(context);
        }
        prefs.edit().putString(KEY_FINGERPRINT, cached).apply();
        mirror(context);
        return cached;
    }

    /**
     * Returns a persistent GUID (UUID v4 format), generated once and stored.
     * This is used as input for Kugou's MID calculation.
     */
    public static String getGuid(Context context) {
        if (cachedGuid != null) return cachedGuid;
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String stored = prefs.getString(KEY_GUID, null);
        if (stored != null && !stored.isEmpty()) {
            cachedGuid = stored;
            mirror(context);
            return cachedGuid;
        }
        String mirrored = readMirror(context).guid;
        if (mirrored != null && !mirrored.isEmpty()) {
            cachedGuid = mirrored;
        } else {
            cachedGuid = UUID.randomUUID().toString();
        }
        prefs.edit().putString(KEY_GUID, cachedGuid).apply();
        mirror(context);
        return cachedGuid;
    }

    // ---- 公共目录镜像（跨重装） --------------------------------------------

    private static final class Mirror {
        String fp;
        String guid;
    }

    private static java.io.File mirrorFile() {
        java.io.File root = android.os.Environment.getExternalStorageDirectory();
        if (root == null || !"mounted".equals(android.os.Environment.getExternalStorageState())) {
            return null;
        }
        java.io.File dir = new java.io.File(root, MIRROR_DIR);
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new java.io.File(dir, MIRROR_FILE);
    }

    private static Mirror readMirror(Context context) {
        Mirror m = new Mirror();
        try {
            java.io.File file = mirrorFile();
            if (file == null || !file.exists()) return m;
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(file))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(sb.toString()).getAsJsonObject();
            m.fp = o.has("fp") && !o.get("fp").isJsonNull() ? o.get("fp").getAsString() : null;
            m.guid = o.has("guid") && !o.get("guid").isJsonNull() ? o.get("guid").getAsString() : null;
        } catch (Exception ignored) {
        }
        return m;
    }

    private static void mirror(Context context) {
        try {
            java.io.File file = mirrorFile();
            if (file == null) return;
            String fp = prefsGet(context, KEY_FINGERPRINT);
            String guid = prefsGet(context, KEY_GUID);
            if (fp == null || guid == null) return;
            com.google.gson.JsonObject o = new com.google.gson.JsonObject();
            o.addProperty("fp", fp);
            o.addProperty("guid", guid);
            try (java.io.FileWriter fw = new java.io.FileWriter(file, false)) {
                fw.write(o.toString());
            }
        } catch (Exception ignored) {
            // 公共存储不可用时退化为原行为（仅 prefs）
        }
    }

    private static String prefsGet(Context context, String key) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(key, null);
    }

    /**
     * Calculates the Kugou MID from the device GUID.
     *
     * <p>Algorithm (matching KuGouMusicApi's calculateMid):
     * <ol>
     *   <li>Compute MD5 hash of the GUID → 32-char hex string</li>
     *   <li>Treat the hex string as a base-16 big integer</li>
     *   <li>Convert to base-10 (decimal) string</li>
     * </ol></p>
     *
     * @return decimal string representation of MD5(GUID) as a big integer
     */
    public static String getMid(Context context) {
        if (cachedMid != null) return cachedMid;
        String guid = getGuid(context);
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(guid.getBytes(StandardCharsets.UTF_8));
            // Convert MD5 bytes to hex string
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            // Convert hex to decimal big integer
            BigInteger bigInt = new BigInteger(hex.toString(), 16);
            cachedMid = bigInt.toString();
            return cachedMid;
        } catch (Exception e) {
            // Fallback: use GUID hash code
            return String.valueOf(guid.hashCode());
        }
    }

    private static String generate(Context context) {
        String androidId = android.provider.Settings.Secure.getString(
                context.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = "unknown";

        String material = androidId
                + "|" + Build.MANUFACTURER
                + "|" + Build.MODEL
                + "|" + Build.BRAND
                + "|" + Build.VERSION.SDK_INT;

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            // Fallback: use the raw material hash code (less ideal but functional)
            return Integer.toHexString(material.hashCode());
        }
    }
}
