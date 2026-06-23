package com.example.cleanrecovery.music.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link AudioFormatDetector} 单元测试。
 *
 * <p>验证音频文件格式检测器的技术原理实现正确性：</p>
 * <ol>
 *   <li>通过文件头魔数识别标准未加密格式（MP3/FLAC/OGG/M4A）</li>
 *   <li>识别酷狗加密格式（KGM/KGMA/KGG/VPR）</li>
 *   <li>正确区分加密与未加密格式</li>
 *   <li>处理边界情况（空数据、过短数据、未知格式）</li>
 * </ol>
 *
 * <p>测试用例覆盖调研报告中提到的所有格式及其文件头特征。</p>
 */
public class AudioFormatDetectorTest {

    // ===== 标准未加密格式测试 =====

    /** 测试 MP3 (ID3v2) 格式识别 - 文件头 "ID3" */
    @Test
    public void testMp3Id3v2() {
        byte[] header = {'I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.MP3_ID3, result.format);
        assertFalse("MP3 ID3v2 should not be encrypted", result.encrypted);
        assertTrue("MP3 ID3v2 should be playable", result.playable);
    }

    /** 测试 MP3 (帧头) 格式识别 - 文件头 0xFF 0xFB */
    @Test
    public void testMp3Frame() {
        byte[] header = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, (byte) 0x64, 0, 0, 0, 0};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.MP3_FRAME, result.format);
        assertFalse(result.encrypted);
        assertTrue(result.playable);
    }

    /** 测试 MP3 帧头变体 0xFF 0xF3 (MPEG 2.5 Layer III) */
    @Test
    public void testMp3FrameVariant() {
        byte[] header = {(byte) 0xFF, (byte) 0xF3, 0x40, 0x00};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.MP3_FRAME, result.format);
        assertTrue(result.playable);
    }

    /** 测试 FLAC 无损格式识别 - 文件头 "fLaC" */
    @Test
    public void testFlac() {
        byte[] header = {'f', 'L', 'a', 'C', 0x00, 0x00, 0x00, 0x22};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.FLAC, result.format);
        assertFalse(result.encrypted);
        assertTrue(result.playable);
    }

    /** 测试 Ogg/Vorbis 格式识别 - 文件头 "OggS" */
    @Test
    public void testOgg() {
        byte[] header = {'O', 'g', 'g', 'S', 0x00, 0x02, 0x00, 0x00};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.OGG, result.format);
        assertFalse(result.encrypted);
        assertTrue(result.playable);
    }

    /** 测试 M4A/AAC 格式识别 - "ftyp" at offset 4 */
    @Test
    public void testM4a() {
        byte[] header = {0x00, 0x00, 0x00, 0x20, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.M4A, result.format);
        assertFalse(result.encrypted);
        assertTrue(result.playable);
    }

    // ===== 酷狗加密格式测试 =====

    /** 测试 KGM V1 格式识别 - 文件头 "KGM" (3 bytes) */
    @Test
    public void testKgmV1() {
        byte[] header = {'K', 'G', 'M', 0x00, 0x01, 0x02, 0x03, 0x04};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.KGM_V1, result.format);
        assertTrue("KGM V1 should be encrypted", result.encrypted);
        assertFalse("KGM V1 should not be playable", result.playable);
    }

    /** 测试 KGM V2 格式识别 - 文件头 "KGM!" (4 bytes) */
    @Test
    public void testKgmV2() {
        byte[] header = {'K', 'G', 'M', '!', 0x01, 0x02, 0x03, 0x04};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.KGM_V2, result.format);
        assertTrue(result.encrypted);
        assertFalse(result.playable);
    }

    /** 测试 KGMA 格式识别 - 文件头 "KGMA" (4 bytes) */
    @Test
    public void testKgma() {
        byte[] header = {'K', 'G', 'M', 'A', 0x01, 0x02, 0x03, 0x04};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.KGMA, result.format);
        assertTrue(result.encrypted);
        assertFalse(result.playable);
    }

    /** 测试 KGG DRM 格式识别 - 文件头 "KGG" (3 bytes) */
    @Test
    public void testKgg() {
        byte[] header = {'K', 'G', 'G', 0x01, 0x02, 0x03, 0x04};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.KGG, result.format);
        assertTrue("KGG should be encrypted (DRM)", result.encrypted);
        assertFalse(result.playable);
    }

    /** 测试 VPR 格式识别 - 文件头 "VPR" (3 bytes) */
    @Test
    public void testVpr() {
        byte[] header = {'V', 'P', 'R', 0x01, 0x02, 0x03, 0x04};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);

        assertEquals(AudioFormatDetector.Format.VPR, result.format);
        assertTrue(result.encrypted);
        assertFalse(result.playable);
    }

    // ===== 边界情况测试 =====

    /** 测试空数据 */
    @Test
    public void testEmptyData() {
        AudioFormatDetector.Result result = AudioFormatDetector.detect(null, 0);
        assertEquals(AudioFormatDetector.Format.UNKNOWN, result.format);
        assertFalse(result.encrypted);
        assertFalse(result.playable);
    }

    /** 测试过短数据（少于 4 字节） */
    @Test
    public void testTooShortData() {
        byte[] header = {'I', 'D', 0x03};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);
        assertEquals(AudioFormatDetector.Format.UNKNOWN, result.format);
    }

    /** 测试未知格式 */
    @Test
    public void testUnknownFormat() {
        byte[] header = {0x12, 0x34, 0x56, 0x78, (byte) 0xAB, (byte) 0xCD};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, header.length);
        assertEquals(AudioFormatDetector.Format.UNKNOWN, result.format);
        assertFalse(result.encrypted);
        assertFalse(result.playable);
    }

    /** 测试长度参数小于数组实际长度 */
    @Test
    public void testLengthLessThanArray() {
        byte[] header = {'I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00};
        // 只传入 3 字节有效长度，不足以识别
        AudioFormatDetector.Result result = AudioFormatDetector.detect(header, 3);
        assertEquals(AudioFormatDetector.Format.UNKNOWN, result.format);
    }

    // ===== 便捷方法测试 =====

    /** 验证 isEncrypted 便捷方法 */
    @Test
    public void testIsEncryptedFlag() {
        // 加密格式
        byte[] kgm = {'K', 'G', 'M', 0x00};
        assertTrue(AudioFormatDetector.detect(kgm, kgm.length).encrypted);

        byte[] kgg = {'K', 'G', 'G', 0x00};
        assertTrue(AudioFormatDetector.detect(kgg, kgg.length).encrypted);

        // 未加密格式
        byte[] mp3 = {'I', 'D', '3', 0x03};
        assertFalse(AudioFormatDetector.detect(mp3, mp3.length).encrypted);

        byte[] flac = {'f', 'L', 'a', 'C'};
        assertFalse(AudioFormatDetector.detect(flac, flac.length).encrypted);
    }

    /** 验证 isPlayable 便捷方法 */
    @Test
    public void testPlayableFlag() {
        // 可播放格式
        byte[] mp3 = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, (byte) 0x64};
        assertTrue(AudioFormatDetector.detect(mp3, mp3.length).playable);

        // 不可播放格式（加密）
        byte[] kgma = {'K', 'G', 'M', 'A'};
        assertFalse(AudioFormatDetector.detect(kgma, kgma.length).playable);

        // 未知格式
        byte[] unknown = {0x12, 0x34, 0x56, 0x78};
        assertFalse(AudioFormatDetector.detect(unknown, unknown.length).playable);
    }

    /** 验证 toString 输出格式 */
    @Test
    public void testToString() {
        byte[] mp3 = {'I', 'D', '3', 0x03};
        AudioFormatDetector.Result result = AudioFormatDetector.detect(mp3, mp3.length);
        String str = result.toString();

        assertTrue("toString should contain format name", str.contains("MP3"));
        assertTrue("toString should contain encrypted flag", str.contains("encrypted=false"));
        assertTrue("toString should contain playable flag", str.contains("playable=true"));
    }

    /** 测试 KGM 与 KGM!/KGMA 的区分（关键边界） */
    @Test
    public void testKgmVariantDistinction() {
        // KGM V1: "KGM" + 非 '!'/'A' 字节
        byte[] kgmV1 = {'K', 'G', 'M', 0x00};
        assertEquals(AudioFormatDetector.Format.KGM_V1,
                AudioFormatDetector.detect(kgmV1, kgmV1.length).format);

        // KGM V2: "KGM!"
        byte[] kgmV2 = {'K', 'G', 'M', '!'};
        assertEquals(AudioFormatDetector.Format.KGM_V2,
                AudioFormatDetector.detect(kgmV2, kgmV2.length).format);

        // KGMA: "KGMA"
        byte[] kgma = {'K', 'G', 'M', 'A'};
        assertEquals(AudioFormatDetector.Format.KGMA,
                AudioFormatDetector.detect(kgma, kgma.length).format);
    }
}
