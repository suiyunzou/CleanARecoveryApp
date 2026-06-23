package com.example.cleanrecovery.music.api;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.InflaterInputStream;

/**
 * 酷狗 KRC 逐字歌词解码器。
 *
 * <p>技术原理（参考开源项目 KuGouMusicApi/util/util.js 的 decodeLyrics 实现）：</p>
 * <ol>
 *   <li>KRC 文件以 4 字节文件头标识 "krc1" / "krc2" 开头，需跳过</li>
 *   <li>剩余字节使用固定 16 字节密钥进行 XOR 异或解密（密钥循环使用）</li>
 *   <li>解密后的字节流为 zlib 压缩数据，使用 zlib inflate 解压得到明文</li>
 *   <li>明文为 UTF-8 编码的逐字歌词文本（含 [ti:...]、[ar:...] 等元数据行）</li>
 * </ol>
 *
 * <p>密钥为硬编码固定值，无动态密钥协商，属于"混淆"级别保护而非真正的加密。
 * 任何获取到 KRC 文件的第三方均可离线解密。</p>
 *
 * <p>典型 KRC 明文格式示例：</p>
 * <pre>
 * [id:0]
 * [ti:歌曲名]
 * [ar:歌手]
 * [al:专辑]
 * [by:酷狗歌词]
 * [offset:0]
 * [0,1000]歌(0,500)词(500,500)
 * [1000,2000]下(0,500)一(500,500)行(1000,500)歌(1500,500)词(2000,0)
 * </pre>
 *
 * <p>其中 [startMs,durationMs] 为行时间戳，括号内 (offsetMs,durationMs) 为逐字时间戳。</p>
 */
public final class KrcDecoder {

    /** KRC 文件头长度（字节），需跳过。 */
    private static final int KRC_HEADER_LENGTH = 4;

    /**
     * KRC XOR 解密密钥（16 字节，循环使用）。
     * 与开源项目 KuGouMusicApi/util/util.js 中的 enKey 完全一致。
     */
    private static final byte[] XOR_KEY = {
            64, 71, 97, 119, 94, 50, 116, 71,
            81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105
    };

    private KrcDecoder() {
        // 工具类，禁止实例化
    }

    /**
     * 解码 Base64 编码的 KRC 歌词数据。
     *
     * <p>对应酷狗歌词下载接口 {@code lyrics.kugou.com/download?fmt=krc}
     * 返回的 {@code content} 字段（Base64 编码的二进制 KRC 数据）。</p>
     *
     * @param base64Content Base64 编码的 KRC 二进制数据
     * @return UTF-8 解码后的明文歌词，解码失败返回空字符串
     */
    public static String decodeFromBase64(String base64Content) {
        if (base64Content == null || base64Content.isEmpty()) {
            return "";
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Content);
            return decode(bytes);
        } catch (IllegalArgumentException e) {
            // Base64 解码失败
            return "";
        }
    }

    /**
     * 解码原始 KRC 二进制数据。
     *
     * <p>解码流程：</p>
     * <ol>
     *   <li>校验数据长度大于文件头长度</li>
     *   <li>跳过前 4 字节文件头</li>
     *   <li>对剩余字节进行 XOR 异或解密</li>
     *   <li>使用 zlib inflate 解压得到 UTF-8 明文</li>
     * </ol>
     *
     * @param krcBytes 原始 KRC 二进制数据（含文件头）
     * @return UTF-8 解码后的明文歌词，解码失败返回空字符串
     */
    public static String decode(byte[] krcBytes) {
        if (krcBytes == null || krcBytes.length <= KRC_HEADER_LENGTH) {
            return "";
        }

        try {
            // 1. 跳过前 4 字节文件头
            byte[] payload = new byte[krcBytes.length - KRC_HEADER_LENGTH];
            System.arraycopy(krcBytes, KRC_HEADER_LENGTH, payload, 0, payload.length);

            // 2. XOR 异或解密（16 字节密钥循环使用）
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ XOR_KEY[i % XOR_KEY.length]);
            }

            // 3. zlib inflate 解压
            byte[] inflated = inflate(payload);
            if (inflated == null || inflated.length == 0) {
                return "";
            }

            // 4. 转换为 UTF-8 字符串
            return new String(inflated, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 使用 zlib inflate 解压数据。
     *
     * <p>使用 JDK 内置的 {@link InflaterInputStream}，无需第三方依赖（如 pako）。
     * KRC 的 zlib 数据使用默认的 zlib 头（78 9C），对应 InflaterInputStream 的默认模式。</p>
     *
     * @param compressed 已 XOR 解密的压缩数据
     * @return 解压后的字节数组，解压失败返回 null
     */
    private static byte[] inflate(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            return null;
        }

        ByteArrayInputStream bais = new ByteArrayInputStream(compressed);
        // 使用 nowrap=false 以解析 zlib 头（KRC 使用标准 zlib 格式）
        try (InflaterInputStream iis = new InflaterInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = iis.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            return baos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 检测字节数组是否为有效的 KRC 格式。
     *
     * <p>通过检查文件头魔数判断：KRC 文件以 "krc1" 或 "krc2" 开头。</p>
     *
     * @param data 待检测的字节数组
     * @return true 表示数据为 KRC 格式
     */
    public static boolean isKrcFormat(byte[] data) {
        if (data == null || data.length < KRC_HEADER_LENGTH) {
            return false;
        }
        // 检查 "krc1" 或 "krc2" 文件头
        return (data[0] == 'k' && data[1] == 'r' && data[2] == 'c'
                && (data[3] == '1' || data[3] == '2'));
    }
}
