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
}
