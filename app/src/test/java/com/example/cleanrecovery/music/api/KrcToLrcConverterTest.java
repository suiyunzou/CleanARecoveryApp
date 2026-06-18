package com.example.cleanrecovery.music.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link KrcToLrcConverter} 单元测试。
 *
 * <p>验证 KRC 逐字歌词到 LRC 行级歌词的格式转换正确性：</p>
 * <ol>
 *   <li>元数据行保留（[ti:], [ar:], [al:], [by:], [offset:]）</li>
 *   <li>行时间戳 [startMs,durationMs] 转换为 LRC 时间戳 [mm:ss.xx]</li>
 *   <li>逐字时间戳 字(offset,dur) 提取纯歌词文本</li>
 *   <li>毫秒到 LRC 时间戳格式转换正确性</li>
 * </ol>
 */
public class KrcToLrcConverterTest {

    /** 测试完整的 KRC 到 LRC 转换流程 */
    @Test
    public void testFullConversion() {
        String krc = "[id:0]\n"
                + "[ti:测试歌曲]\n"
                + "[ar:测试歌手]\n"
                + "[al:测试专辑]\n"
                + "[by:酷狗歌词]\n"
                + "[offset:0]\n"
                + "[0,3000]测(0,500)试(500,500)歌(1000,500)词(1500,500)\n"
                + "[3000,3000]第(0,500)二(500,500)行(1000,500)\n";

        String lrc = KrcToLrcConverter.convert(krc);

        // 验证元数据保留
        assertTrue("Should preserve [ti:] metadata", lrc.contains("[ti:测试歌曲]"));
        assertTrue("Should preserve [ar:] metadata", lrc.contains("[ar:测试歌手]"));
        assertTrue("Should preserve [al:] metadata", lrc.contains("[al:测试专辑]"));
        assertTrue("Should preserve [by:] metadata", lrc.contains("[by:酷狗歌词]"));
        assertTrue("Should preserve [offset:] metadata", lrc.contains("[offset:0]"));

        // 验证时间戳转换：0ms → [00:00.00]
        assertTrue("Should convert 0ms to [00:00.00]", lrc.contains("[00:00.00]测试歌词"));
        // 验证时间戳转换：3000ms → [00:03.00]
        assertTrue("Should convert 3000ms to [00:03.00]", lrc.contains("[00:03.00]第二行"));

        // [id:0] 不在保留列表中，应被丢弃
        assertTrue("Should drop [id:] metadata", !lrc.contains("[id:"));
    }

    /** 测试毫秒到 LRC 时间戳转换 */
    @Test
    public void testTimestampConversion() {
        // 0ms → 00:00.00
        verifyTimestamp(0, "00:00.00");
        // 1234ms → 00:01.23
        verifyTimestamp(1234, "00:01.23");
        // 65000ms → 01:05.00
        verifyTimestamp(65000, "01:05.00");
        // 3661000ms → 61:01.00
        verifyTimestamp(3661000, "61:01.00");
    }

    /** 测试空输入 */
    @Test
    public void testEmptyInput() {
        assertEquals("", KrcToLrcConverter.convert(null));
        assertEquals("", KrcToLrcConverter.convert(""));
    }

    /** 测试仅含元数据的 KRC */
    @Test
    public void testMetadataOnly() {
        String krc = "[ti:歌曲名]\n[ar:歌手]\n[al:专辑]\n";
        String lrc = KrcToLrcConverter.convert(krc);

        assertTrue(lrc.contains("[ti:歌曲名]"));
        assertTrue(lrc.contains("[ar:歌手]"));
        assertTrue(lrc.contains("[al:专辑]"));
    }

    /** 测试无时间戳的行应被跳过 */
    @Test
    public void testLinesWithoutTimestamp() {
        String krc = "[ti:测试]\n"
                + "这是一行没有时间戳的歌词\n"
                + "[0,1000]有(0,500)时(500,500)间(1000,0)\n";
        String lrc = KrcToLrcConverter.convert(krc);

        // 无时间戳行应被丢弃
        assertTrue("Lines without timestamp should be dropped",
                !lrc.contains("这是一行没有时间戳的歌词"));
        // 有时间戳行应保留
        assertTrue("Lines with timestamp should be kept",
                lrc.contains("[00:00.00]有时间"));
    }

    /** 测试包含空格的歌词文本 */
    @Test
    public void testLyricsWithSpaces() {
        String krc = "[0,2000]Hello(0,500) (500,500)World(1000,1000)\n";
        String lrc = KrcToLrcConverter.convert(krc);

        assertTrue("Should preserve spaces in lyrics", lrc.contains("[00:00.00]Hello World"));
    }

    /** 测试负时间戳处理（边界情况） */
    @Test
    public void testNegativeTimestamp() {
        // KRC 行时间戳正则要求 \d+，负号不匹配，因此负时间戳行会被跳过
        String krc = "[-100,1000]负(0,500)时(500,500)间(1000,0)\n";
        String lrc = KrcToLrcConverter.convert(krc);

        // 负时间戳行无法匹配正则，应被丢弃
        assertTrue("Negative timestamp line should be dropped",
                !lrc.contains("负时间"));
        assertTrue("Output should be empty", lrc.isEmpty());
    }

    /** 测试多行歌词顺序保持 */
    @Test
    public void testMultipleLinesOrder() {
        // 输入顺序：5000ms, 0ms, 10000ms（非时间顺序）
        String krc = "[ti:多行测试]\n"
                + "[5000,2000]五(0,500)秒(500,500)\n"
                + "[0,2000]零(0,500)秒(500,500)\n"
                + "[10000,2000]十(0,500)秒(500,500)\n";

        String lrc = KrcToLrcConverter.convert(krc);

        // 验证所有行都存在
        assertTrue(lrc.contains("[00:00.00]零秒"));
        assertTrue(lrc.contains("[00:05.00]五秒"));
        assertTrue(lrc.contains("[00:10.00]十秒"));

        // 验证顺序保持原始输入顺序（5000 → 0 → 10000）
        int idx5 = lrc.indexOf("[00:05.00]");
        int idx0 = lrc.indexOf("[00:00.00]");
        int idx10 = lrc.indexOf("[00:10.00]");
        assertTrue("Lines should preserve original input order (5000 first)",
                idx5 < idx0);
        assertTrue("Lines should preserve original input order (0 second)",
                idx0 < idx10);
    }

    // ===== 辅助方法 =====

    /** 验证指定毫秒数对应的 LRC 时间戳格式 */
    private void verifyTimestamp(long ms, String expectedTimestamp) {
        String krc = String.format("[%d,1000]测(0,500)试(500,500)\n", ms);
        String lrc = KrcToLrcConverter.convert(krc);
        assertTrue("Timestamp " + ms + "ms should convert to [" + expectedTimestamp + "]",
                lrc.contains("[" + expectedTimestamp + "]测试"));
    }
}
