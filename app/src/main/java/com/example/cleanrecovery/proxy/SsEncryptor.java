package com.example.cleanrecovery.proxy;

import java.io.ByteArrayOutputStream;

/**
 * SS AEAD 流式加密器：把明文流切成 ≤ {@link SsCrypto#MAX_PAYLOAD} 的块，
 * 每块前缀加密的 2 字节长度，输出 {@code salt || [len块][data块]...}。
 *
 * <p>地址帧（ATYP+ADDR+PORT）作为明文流的前缀一并送入，无需特殊处理。</p>
 */
public final class SsEncryptor {

    private final SsCrypto.CipherSpec spec;
    private final byte[] subkey;
    private final byte[] salt;
    private long counter = 0;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private boolean saltEmitted = false;

    public SsEncryptor(SsCrypto.CipherSpec spec, byte[] masterKey) {
        this.spec = spec;
        this.salt = SsCrypto.randomSalt(spec);
        this.subkey = SsCrypto.deriveSubkey(spec, salt, masterKey);
    }

    /** 返回当前会话 salt（首次输出块前需写出）。 */
    public byte[] salt() {
        return salt;
    }

    /** 喂入明文，返回已生成的密文（可能为空）。 */
    public byte[] update(byte[] in, int off, int len) {
        if (!saltEmitted) {
            // 第一块前先输出 salt
            saltEmitted = true;
        }
        buffer.write(in, off, len);
        return flushFullChunks(false);
    }

    /** 强制把缓冲区剩余全部输出（即使不足一块）。 */
    public byte[] flush() {
        return flushFullChunks(true);
    }

    private byte[] flushFullChunks(boolean finalFlush) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = buffer.toByteArray();
        int pos = 0;
        while (pos < data.length) {
            int chunkLen = Math.min(data.length - pos, SsCrypto.MAX_PAYLOAD);
            if (!finalFlush && chunkLen < SsCrypto.MAX_PAYLOAD && pos + chunkLen == data.length) {
                // 保留不足一块的尾部在缓冲区，等下次或 flush
                break;
            }
            byte[] lenPlain = new byte[]{
                    (byte) (chunkLen >>> 8),
                    (byte) (chunkLen)};
            byte[] sealedLen = SsCrypto.seal(spec, subkey, counter, lenPlain, 0, 2);
            counter++;
            byte[] sealedData = SsCrypto.seal(spec, subkey, counter, data, pos, chunkLen);
            counter++;
            try {
                out.write(sealedLen);
                out.write(sealedData);
            } catch (java.io.IOException ignored) {
            }
            pos += chunkLen;
        }
        // 保留未处理部分
        buffer.reset();
        if (pos < data.length) {
            buffer.write(data, pos, data.length - pos);
        }
        return out.toByteArray();
    }
}
