package com.example.cleanrecovery.proxy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shadowsocks AEAD 加密（SIP022 现代协议，FlClash/ss-android 同款）。
 *
 * <h3>协议要点</h3>
 * <ul>
 *   <li>主密钥派生：{@code EVP_BytesToKey(password, MD5, key_len)}，链式 MD5 拼到目标长度。</li>
 *   <li>会话子密钥：{@code HKDF-SHA1(salt, master_key, "ss-subkey", key_len)}。</li>
 *   <li>每条 TCP 连接：先发随机 salt（明文），随后是 AEAD 块流。</li>
 *   <li>每块格式：{@code AEAD(len[2]) || AEAD(payload)}，payload ≤ {@value #MAX_PAYLOAD}。
 *       len 块 = 2 + 16 字节，data 块 = n + 16 字节。</li>
 *   <li>nonce：4 字节 0 前缀 + 8 字节小端计数器（每块计数 +1，len 与 data 各占一次）。</li>
 * </ul>
 *
 * <h3>支持算法</h3>
 * <ul>
 *   <li>{@code aes-256-gcm}：JDK/Android 全版本可用（{@code javax.crypto.Cipher} GCM）。</li>
 *   <li>{@code chacha20-ietf-poly1305}：需 JDK 11+ / Android API 28+ 的 {@code ChaCha20-Poly1305}。
 *       若运行环境不支持则工厂返回 null，调用方应回退到 aes-256-gcm（3 次失败规则）。</li>
 * </ul>
 */
public final class SsCrypto {

    /** 单块 payload 上限（SS AEAD 规范 0x3FFF）。 */
    public static final int MAX_PAYLOAD = 0x3FFF;

    /** HKDF info（SS 规范固定值）。 */
    private static final byte[] HKDF_INFO = "ss-subkey".getBytes(StandardCharsets.UTF_8);

    private static final SecureRandom RNG = new SecureRandom();

    private SsCrypto() {
    }

    /** 加密算法描述。 */
    public static final class CipherSpec {
        public final String name;
        public final int keyLen;
        public final int saltLen;
        public final int nonceLen;
        public final int tagLen;

        public CipherSpec(String name, int keyLen, int saltLen, int nonceLen, int tagLen) {
            this.name = name;
            this.keyLen = keyLen;
            this.saltLen = saltLen;
            this.nonceLen = nonceLen;
            this.tagLen = tagLen;
        }
    }

    /** AES-256-GCM 规格。 */
    public static final CipherSpec AES_256_GCM =
            new CipherSpec("aes-256-gcm", 32, 16, 12, 16);

    /** ChaCha20-IETF-Poly1305 规格。 */
    public static final CipherSpec CHACHA20_IETF_POLY1305 =
            new CipherSpec("chacha20-ietf-poly1305", 32, 32, 12, 16);

    /** 按名称取规格，未知返回 null。 */
    public static CipherSpec specByName(String name) {
        if (name == null) return null;
        switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "aes-256-gcm":
                return AES_256_GCM;
            case "chacha20-ietf-poly1305":
            case "chacha20-poly1305":
            case "chacha20-ietf-poly1305-ietf":
                return CHACHA20_IETF_POLY1305;
            default:
                return null;
        }
    }

    /** 探测当前运行环境是否支持某算法（不抛异常）。 */
    public static boolean isSupported(CipherSpec spec) {
        if (spec == null) return false;
        try {
            Cipher c = newCipher(spec);
            return c != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** EVP_BytesToKey（MD5，无 IV），从密码派生主密钥。 */
    public static byte[] deriveKey(String password, int keyLen) {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] out = new byte[0];
            byte[] prev = new byte[0];
            while (out.length < keyLen) {
                md5.reset();
                md5.update(prev);
                md5.update(pw);
                prev = md5.digest();
                out = concat(out, prev);
            }
            return Arrays.copyOf(out, keyLen);
        } catch (Exception e) {
            throw new RuntimeException("MD5 unavailable", e);
        }
    }

    /** HKDF-SHA1：extract + expand，输出 length 字节。 */
    public static byte[] hkdfSha1(byte[] salt, byte[] ikm, byte[] info, int length) {
        try {
            byte[] prk = hmacSha1(salt, ikm);
            byte[] okm = new byte[0];
            byte[] t = new byte[0];
            int block = 1;
            while (okm.length < length) {
                t = hmacSha1(prk, concat(t, info, new byte[]{(byte) block}));
                okm = concat(okm, t);
                block++;
            }
            return Arrays.copyOf(okm, length);
        } catch (Exception e) {
            throw new RuntimeException("HKDF-SHA1 failed", e);
        }
    }

    private static byte[] hmacSha1(byte[] key, byte[] data) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        return mac.doFinal(data);
    }

    /** 从 salt + 主密钥派生会话子密钥。 */
    public static byte[] deriveSubkey(CipherSpec spec, byte[] salt, byte[] masterKey) {
        return hkdfSha1(salt, masterKey, HKDF_INFO, spec.keyLen);
    }

    /** 生成随机 salt。 */
    public static byte[] randomSalt(CipherSpec spec) {
        byte[] salt = new byte[spec.saltLen];
        RNG.nextBytes(salt);
        return salt;
    }

    /** 构造 nonce：4 字节 0 前缀 + 8 字节小端计数器。 */
    public static byte[] nonce(CipherSpec spec, long counter) {
        byte[] nonce = new byte[spec.nonceLen];
        // 末 8 字节写小端计数器，前缀保持 0
        nonce[4] = (byte) (counter);
        nonce[5] = (byte) (counter >>> 8);
        nonce[6] = (byte) (counter >>> 16);
        nonce[7] = (byte) (counter >>> 24);
        nonce[8] = (byte) (counter >>> 32);
        nonce[9] = (byte) (counter >>> 40);
        nonce[10] = (byte) (counter >>> 48);
        nonce[11] = (byte) (counter >>> 56);
        return nonce;
    }

    /** AEAD 加密单块。 */
    public static byte[] seal(CipherSpec spec, byte[] key, long counter, byte[] plaintext, int off, int len) {
        try {
            Cipher c = newCipher(spec);
            byte[] nonce = nonce(spec, counter);
            if (spec == AES_256_GCM) {
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(spec.tagLen * 8, nonce));
            } else {
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                        new IvParameterSpec(nonce));
            }
            return c.doFinal(plaintext, off, len);
        } catch (Exception e) {
            throw new RuntimeException("seal failed", e);
        }
    }

    /** AEAD 解密单块（密文含 tag）。 */
    public static byte[] open(CipherSpec spec, byte[] key, long counter, byte[] ciphertext, int off, int len) {
        try {
            Cipher c = newCipher(spec);
            byte[] nonce = nonce(spec, counter);
            if (spec == AES_256_GCM) {
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                        new GCMParameterSpec(spec.tagLen * 8, nonce));
            } else {
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "ChaCha20"),
                        new IvParameterSpec(nonce));
            }
            return c.doFinal(ciphertext, off, len);
        } catch (Exception e) {
            throw new RuntimeException("open failed", e);
        }
    }

    private static Cipher newCipher(CipherSpec spec) throws Exception {
        if (spec == AES_256_GCM) {
            return Cipher.getInstance("AES/GCM/NoPadding");
        } else {
            // JDK 11+ / Android API 28+；不支持则抛 NoSuchAlgorithmException
            return Cipher.getInstance("ChaCha20-Poly1305");
        }
    }

    /** 字节数组拼接。 */
    public static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] out = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, out, pos, a.length);
            pos += a.length;
        }
        return out;
    }
}
