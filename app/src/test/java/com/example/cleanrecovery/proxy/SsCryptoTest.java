package com.example.cleanrecovery.proxy;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** SS AEAD 加解密往返单测。 */
public class SsCryptoTest {

    private static void writeAll(java.io.ByteArrayOutputStream out, byte[] b) {
        if (b != null && b.length > 0) out.write(b, 0, b.length);
    }

    @Test
    public void deriveKeyIs32BytesAndDeterministic() {
        byte[] k1 = SsCrypto.deriveKey("hello", 32);
        byte[] k2 = SsCrypto.deriveKey("hello", 32);
        assertEquals(32, k1.length);
        assertArrayEquals(k1, k2);
    }

    @Test
    public void hkdfSha1ProducesKeyLen() {
        byte[] salt = new byte[16];
        byte[] ikm = SsCrypto.deriveKey("pw", 32);
        byte[] sub = SsCrypto.hkdfSha1(salt, ikm, "ss-subkey".getBytes(StandardCharsets.UTF_8), 32);
        assertEquals(32, sub.length);
    }

    @Test
    public void aesGcmRoundTripSingleBlock() {
        byte[] key = SsCrypto.deriveKey("password", 32);
        byte[] pt = "hello-shadowsocks".getBytes(StandardCharsets.UTF_8);
        byte[] ct = SsCrypto.seal(SsCrypto.AES_256_GCM, key, 0, pt, 0, pt.length);
        byte[] dec = SsCrypto.open(SsCrypto.AES_256_GCM, key, 0, ct, 0, ct.length);
        assertArrayEquals(pt, dec);
    }

    @Test
    public void aesGcmNonceCounterIncrements() {
        byte[] n0 = SsCrypto.nonce(SsCrypto.AES_256_GCM, 0);
        byte[] n1 = SsCrypto.nonce(SsCrypto.AES_256_GCM, 1);
        assertEquals(12, n0.length);
        assertEquals(12, n1.length);
        // 计数器写在末 8 字节，n1 应大于 n0
        assertEquals(0, n0[4]);
        assertEquals(1, n1[4]);
    }

    @Test
    public void encryptorDecryptorRoundTrip() {
        byte[] masterKey = SsCrypto.deriveKey("mypw", 32);
        SsEncryptor enc = new SsEncryptor(SsCrypto.AES_256_GCM, masterKey);
        byte[] salt = enc.salt();
        // 模拟明文流：地址帧 + 多段数据
        byte[] addr = SsAddress.buildFrame("example.com", 443);
        byte[] d1 = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] d2 = new byte[20_000]; // 超过单块，触发多块
        Arrays.fill(d2, (byte) 'A');

        java.io.ByteArrayOutputStream cipherOut = new java.io.ByteArrayOutputStream();
        cipherOut.write(salt, 0, salt.length);
        writeAll(cipherOut, enc.update(addr, 0, addr.length));
        writeAll(cipherOut, enc.update(d1, 0, d1.length));
        writeAll(cipherOut, enc.update(d2, 0, d2.length));
        writeAll(cipherOut, enc.flush());

        byte[] cipher = cipherOut.toByteArray();
        SsDecryptor dec = new SsDecryptor(SsCrypto.AES_256_GCM, masterKey);
        byte[] plain = dec.update(cipher, 0, cipher.length);

        // plain 应包含 addr + d1 + d2
        java.io.ByteArrayOutputStream expected = new java.io.ByteArrayOutputStream();
        expected.write(addr, 0, addr.length);
        expected.write(d1, 0, d1.length);
        expected.write(d2, 0, d2.length);
        assertArrayEquals(expected.toByteArray(), plain);
    }

    @Test
    public void chacha20RoundTripIfSupported() {        if (!SsCrypto.isSupported(SsCrypto.CHACHA20_IETF_POLY1305)) {
            System.out.println("chacha20-ietf-poly1305 not supported on this JVM, skip");
            return;
        }
        byte[] key = SsCrypto.deriveKey("pw", 32);
        byte[] pt = "chacha-test".getBytes(StandardCharsets.UTF_8);
        byte[] ct = SsCrypto.seal(SsCrypto.CHACHA20_IETF_POLY1305, key, 0, pt, 0, pt.length);
        byte[] dec = SsCrypto.open(SsCrypto.CHACHA20_IETF_POLY1305, key, 0, ct, 0, ct.length);
        assertArrayEquals(pt, dec);
        assertTrue(true);
    }
}
