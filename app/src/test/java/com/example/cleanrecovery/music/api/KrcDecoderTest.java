package com.example.cleanrecovery.music.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.DeflaterOutputStream;

import org.junit.Test;

/**
 * {@link KrcDecoder} 单元测试。
 *
 * <p>验证 KRC 歌词解码器的技术原理实现正确性：</p>
 * <ol>
 *   <li>跳过 4 字节文件头</li>
 *   <li>16 字节固定密钥 XOR 异或解密</li>
 *   <li>zlib inflate 解压</li>
 *   <li>UTF-8 编码转换</li>
 * </ol>
 *
 * <p>测试策略：</p>
 * <ul>
 *   <li><b>正向测试</b>：构造已知明文 → zlib 压缩 → XOR 加密 → 添加文件头 → 解码 → 验证还原</li>
 *   <li><b>边界测试</b>：空输入、过短输入、非法 Base64</li>
 *   <li><b>格式识别测试</b>：验证 KRC 文件头魔数检测</li>
 * </ul>
 */
public class KrcDecoderTest {

    /** KRC 文件头魔数 "krc1" */
    private static final byte[] KRC_HEADER = {'k', 'r', 'c', '1'};

    /** KRC XOR 解密密钥（与 KrcDecoder 中一致） */
    private static final byte[] XOR_KEY = {
            64, 71, 97, 119, 94, 50, 116, 71,
            81, 54, 49, 45, (byte) 206, (byte) 210, 110, 105
    };

    /**
     * 测试完整的 KRC 编解码流程：明文 → 压缩 → XOR 加密 → 添加文件头 → 解码 → 还原明文。
     *
     * <p>这是核心测试用例，验证解码器实现的算法与编码流程互逆。</p>
     */
    @Test
    public void testFullDecodePipeline() throws Exception {
        // 1. 构造测试用的 KRC 明文（含元数据与逐字歌词）
        String originalKrc = "[id:0]\n"
                + "[ti:测试歌曲]\n"
                + "[ar:测试歌手]\n"
                + "[al:测试专辑]\n"
                + "[by:酷狗歌词]\n"
                + "[offset:0]\n"
                + "[0,3000]测(0,500)试(500,500)歌(1000,500)词(1500,500)\n"
                + "[3000,3000]第(0,500)二(500,500)行(1000,500)歌(1500,500)词(2000,500)\n";

        // 2. zlib 压缩
        byte[] compressed = zlibCompress(originalKrc.getBytes(StandardCharsets.UTF_8));

        // 3. XOR 加密（与解码流程相反）
        byte[] encrypted = new byte[compressed.length];
        for (int i = 0; i < compressed.length; i++) {
            encrypted[i] = (byte) (compressed[i] ^ XOR_KEY[i % XOR_KEY.length]);
        }

        // 4. 添加 KRC 文件头
        byte[] krcBytes = new byte[KRC_HEADER.length + encrypted.length];
        System.arraycopy(KRC_HEADER, 0, krcBytes, 0, KRC_HEADER.length);
        System.arraycopy(encrypted, 0, krcBytes, KRC_HEADER.length, encrypted.length);

        // 5. 解码
        String decoded = KrcDecoder.decode(krcBytes);

        // 6. 验证还原结果
        assertNotNull("Decoded text should not be null", decoded);
        assertEquals("Decoded text should match original", originalKrc, decoded);
    }

    /**
     * 测试从 Base64 编码的 KRC 数据解码。
     *
     * <p>模拟实际 API 调用场景：{@code lyrics.kugou.com/download?fmt=krc}
     * 返回的 {@code content} 字段为 Base64 编码的 KRC 二进制数据。</p>
     */
    @Test
    public void testDecodeFromBase64() throws Exception {
        String originalKrc = "[ti:Base64测试]\n[0,1000]歌(0,500)词(500,500)\n";

        byte[] compressed = zlibCompress(originalKrc.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = xorEncrypt(compressed);
        byte[] krcBytes = new byte[KRC_HEADER.length + encrypted.length];
        System.arraycopy(KRC_HEADER, 0, krcBytes, 0, KRC_HEADER.length);
        System.arraycopy(encrypted, 0, krcBytes, KRC_HEADER.length, encrypted.length);

        String base64Content = Base64.getEncoder().encodeToString(krcBytes);

        String decoded = KrcDecoder.decodeFromBase64(base64Content);
        assertEquals(originalKrc, decoded);
    }

    /**
     * 测试空输入处理。
     */
    @Test
    public void testEmptyInput() {
        assertEquals("", KrcDecoder.decode(null));
        assertEquals("", KrcDecoder.decode(new byte[0]));
        assertEquals("", KrcDecoder.decode(new byte[3])); // 不足文件头长度
        assertEquals("", KrcDecoder.decodeFromBase64(null));
        assertEquals("", KrcDecoder.decodeFromBase64(""));
    }

    /**
     * 测试非法 Base64 输入。
     */
    @Test
    public void testInvalidBase64() {
        assertEquals("", KrcDecoder.decodeFromBase64("!!!invalid base64!!!"));
    }

    /**
     * 测试无效的 zlib 数据（XOR 解密后非合法压缩流）。
     */
    @Test
    public void testInvalidZlibData() {
        // 构造合法文件头 + 非 zlib 数据
        byte[] fakePayload = "hello world not zlib".getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = xorEncrypt(fakePayload);
        byte[] krcBytes = new byte[KRC_HEADER.length + encrypted.length];
        System.arraycopy(KRC_HEADER, 0, krcBytes, 0, KRC_HEADER.length);
        System.arraycopy(encrypted, 0, krcBytes, KRC_HEADER.length, encrypted.length);

        // 解码应返回空字符串而非抛出异常
        String result = KrcDecoder.decode(krcBytes);
        assertEquals("Invalid zlib data should return empty string", "", result);
    }

    /**
     * 测试 KRC 格式识别。
     */
    @Test
    public void testIsKrcFormat() {
        // 合法 KRC 文件头 "krc1"
        byte[] krc1 = {'k', 'r', 'c', '1', 0, 0, 0, 0};
        assertTrue(KrcDecoder.isKrcFormat(krc1));

        // 合法 KRC 文件头 "krc2"
        byte[] krc2 = {'k', 'r', 'c', '2', 0, 0, 0, 0};
        assertTrue(KrcDecoder.isKrcFormat(krc2));

        // 非 KRC 格式
        byte[] notKrc = {'I', 'D', '3', 0x03, 0, 0, 0, 0}; // MP3 ID3v2
        assertFalse(KrcDecoder.isKrcFormat(notKrc));

        // 过短数据
        assertFalse(KrcDecoder.isKrcFormat(new byte[3]));
        assertFalse(KrcDecoder.isKrcFormat(null));
    }

    /**
     * 测试包含中文 UTF-8 字符的 KRC 解码。
     *
     * <p>验证解码器正确处理多字节 UTF-8 字符，避免乱码。</p>
     */
    @Test
    public void testChineseUtf8Content() throws Exception {
        String originalKrc = "[ti:晴天]\n"
                + "[ar:周杰伦]\n"
                + "[0,5000]故事的小黄花(0,500)从出生那年就飘着(500,4500)\n";

        byte[] compressed = zlibCompress(originalKrc.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted = xorEncrypt(compressed);
        byte[] krcBytes = new byte[KRC_HEADER.length + encrypted.length];
        System.arraycopy(KRC_HEADER, 0, krcBytes, 0, KRC_HEADER.length);
        System.arraycopy(encrypted, 0, krcBytes, KRC_HEADER.length, encrypted.length);

        String decoded = KrcDecoder.decode(krcBytes);
        assertEquals("Chinese UTF-8 content should be preserved", originalKrc, decoded);
    }

    // ===== 辅助方法 =====

    /** 使用 zlib 压缩数据（对应解码时的 inflate）。 */
    private static byte[] zlibCompress(byte[] data) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos)) {
            dos.write(data);
        }
        return baos.toByteArray();
    }

    /** 使用 KRC 密钥进行 XOR 加密（与解码时的解密互逆）。 */
    private static byte[] xorEncrypt(byte[] data) {
        byte[] result = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            result[i] = (byte) (data[i] ^ XOR_KEY[i % XOR_KEY.length]);
        }
        return result;
    }
}
