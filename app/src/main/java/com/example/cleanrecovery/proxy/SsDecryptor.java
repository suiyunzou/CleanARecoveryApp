package com.example.cleanrecovery.proxy;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

/**
 * SS AEAD 流式解密器：读取 {@code salt || [len块][data块]...}，
 * 解密并输出明文流。本类面向「客户端侧本地代理」：服务端返回的流不含地址帧，
 * 解密结果即为目标数据。
 *
 * <p>实现为状态机，可分批喂入网络字节，自动跨包拼接。</p>
 */
public final class SsDecryptor {

    private enum State { SALT, LEN, DATA }

    private final SsCrypto.CipherSpec spec;
    private final byte[] masterKey;
    private byte[] subkey;
    private long counter = 0;
    private State state = State.SALT;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private int expectedDataLen = 0;

    public SsDecryptor(SsCrypto.CipherSpec spec, byte[] masterKey) {
        this.spec = spec;
        this.masterKey = masterKey;
    }

    /** 喂入密文字节，返回解密后的明文（可能为空）。 */
    public byte[] update(byte[] in, int off, int len) {
        pending.write(in, off, len);
        return pump();
    }

    private byte[] pump() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] data = pending.toByteArray();
        int pos = 0;
        boolean progress = true;
        while (progress) {
            progress = false;
            if (state == State.SALT) {
                if (data.length - pos < spec.saltLen) break;
                byte[] salt = Arrays.copyOfRange(data, pos, pos + spec.saltLen);
                pos += spec.saltLen;
                subkey = SsCrypto.deriveSubkey(spec, salt, masterKey);
                state = State.LEN;
                progress = true;
            } else if (state == State.LEN) {
                int lenBlock = 2 + spec.tagLen;
                if (data.length - pos < lenBlock) break;
                byte[] sealedLen = Arrays.copyOfRange(data, pos, pos + lenBlock);
                pos += lenBlock;
                byte[] lenPlain = SsCrypto.open(spec, subkey, counter, sealedLen, 0, lenBlock);
                counter++;
                expectedDataLen = ((lenPlain[0] & 0xFF) << 8) | (lenPlain[1] & 0xFF);
                state = State.DATA;
                progress = true;
            } else if (state == State.DATA) {
                int dataBlock = expectedDataLen + spec.tagLen;
                if (data.length - pos < dataBlock) break;
                byte[] sealedData = Arrays.copyOfRange(data, pos, pos + dataBlock);
                pos += dataBlock;
                byte[] plain = SsCrypto.open(spec, subkey, counter, sealedData, 0, dataBlock);
                counter++;
                try {
                    out.write(plain);
                } catch (java.io.IOException ignored) {
                }
                state = State.LEN;
                progress = true;
            }
        }
        // 保留未消费字节
        pending.reset();
        if (pos < data.length) {
            pending.write(data, pos, data.length - pos);
        }
        return out.toByteArray();
    }
}
