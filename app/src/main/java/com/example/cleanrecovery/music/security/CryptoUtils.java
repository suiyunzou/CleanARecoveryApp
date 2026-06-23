package com.example.cleanrecovery.music.security;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
/**
 * AES-256-GCM encryption backed by the Android Keystore.
 *
 * <p>The key is generated inside the hardware-backed Keystore and never leaves it.
 * Ciphertext format: base64(iv || ciphertext || tag). The IV is 12 bytes, the GCM
 * auth tag is 16 bytes (appended automatically by the JCE provider).</p>
 */
public final class CryptoUtils {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "music_auth_session_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private CryptoUtils() {}

    /** Encrypt UTF-8 plaintext, returning base64(iv || ciphertext || tag). */
    public static String encrypt(String plaintext) throws Exception {
        if (plaintext == null) return null;
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(plaintext.getBytes("UTF-8"));
        byte[] combined = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(ct, 0, combined, iv.length, ct.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    /** Decrypt a value produced by {@link #encrypt(String)}. */
    public static String decrypt(String encoded) throws Exception {
        if (encoded == null || encoded.isEmpty()) return null;
        byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
        if (combined.length < IV_BYTES + 1) throw new IllegalArgumentException("Ciphertext too short");

        byte[] iv = new byte[IV_BYTES];
        byte[] ct = new byte[combined.length - IV_BYTES];
        System.arraycopy(combined, 0, iv, 0, IV_BYTES);
        System.arraycopy(combined, IV_BYTES, ct, 0, ct.length);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), "UTF-8");
    }

    /** Generate a cryptographically random alphanumeric token. */
    public static String randomToken(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(rng.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        SecretKey existing = (SecretKey) ks.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;

        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256);

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(spec.build());
        return kg.generateKey();
    }

    // ========== 密码派生密钥加密（跨重装备份用） ==========
    // 与 Keystore 加密不同，密钥由密码派生，可在重装后重建。
    // 用于 session 备份到外部存储，避免重装后强制重登。

    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int PBKDF2_ITERATIONS = 10000;
    private static final int PBKDF2_KEY_BITS = 256;
    private static final int PBKDF2_SALT_BYTES = 16;

    /**
     * 使用密码派生密钥加密 UTF-8 明文。
     *
     * <p>格式：base64(salt || iv || ciphertext || tag)
     * <p>盐值随机生成并存储在密文前部，IV 由 Cipher 自动生成。
     * <p>密钥通过 PBKDF2 从密码派生，迭代 10000 次，确保即使密码较弱也难以暴力破解。
     *
     * @param plaintext 待加密的明文
     * @param password  派生密钥的密码（如设备指纹）
     * @return base64 编码的密文
     */
    public static String encryptWithPassword(String plaintext, String password) throws Exception {
        if (plaintext == null) return null;
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("password must not be empty");
        }

        byte[] salt = new byte[PBKDF2_SALT_BYTES];
        new SecureRandom().nextBytes(salt);

        SecretKey key = deriveKey(password, salt);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] iv = cipher.getIV();
        byte[] ct = cipher.doFinal(plaintext.getBytes("UTF-8"));

        // 格式：salt(16) || iv(12) || ciphertext || tag(16)
        byte[] combined = new byte[salt.length + iv.length + ct.length];
        System.arraycopy(salt, 0, combined, 0, salt.length);
        System.arraycopy(iv, 0, combined, salt.length, iv.length);
        System.arraycopy(ct, 0, combined, salt.length + iv.length, ct.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    /**
     * 使用密码派生密钥解密密文。
     *
     * @param encoded encryptWithPassword 产生的 base64 密文
     * @param password 派生密钥的密码（必须与加密时相同）
     * @return UTF-8 明文
     */
    public static String decryptWithPassword(String encoded, String password) throws Exception {
        if (encoded == null || encoded.isEmpty()) return null;
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("password must not be empty");
        }

        byte[] combined = Base64.decode(encoded, Base64.NO_WRAP);
        if (combined.length < PBKDF2_SALT_BYTES + IV_BYTES + 1) {
            throw new IllegalArgumentException("Ciphertext too short");
        }

        byte[] salt = new byte[PBKDF2_SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        byte[] ct = new byte[combined.length - PBKDF2_SALT_BYTES - IV_BYTES];
        System.arraycopy(combined, 0, salt, 0, PBKDF2_SALT_BYTES);
        System.arraycopy(combined, PBKDF2_SALT_BYTES, iv, 0, IV_BYTES);
        System.arraycopy(combined, PBKDF2_SALT_BYTES + IV_BYTES, ct, 0, ct.length);

        SecretKey key = deriveKey(password, salt);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(ct), "UTF-8");
    }

    /**
     * 通过 PBKDF2 从密码派生 AES-256 密钥。
     */
    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        javax.crypto.SecretKeyFactory factory = javax.crypto.SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
        javax.crypto.spec.PBEKeySpec spec = new javax.crypto.spec.PBEKeySpec(
                password.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_BITS);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new javax.crypto.spec.SecretKeySpec(keyBytes, "AES");
    }
}
