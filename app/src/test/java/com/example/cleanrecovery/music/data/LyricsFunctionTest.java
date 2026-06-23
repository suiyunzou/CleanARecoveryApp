package com.example.cleanrecovery.music.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * 歌词功能单元测试。
 *
 * <p>测试覆盖已登录状态下歌词功能的核心逻辑：</p>
 * <ul>
 *   <li><b>加载速度</b>：解析性能基准测试</li>
 *   <li><b>同步准确性</b>：indexOfActive 时间戳定位精度</li>
 *   <li><b>显示格式</b>：LRC 格式解析、多时间戳行、元数据处理</li>
 *   <li><b>离线缓存</b>：raw 字段保留原始文本用于缓存</li>
 * </ul>
 */
public class LyricsFunctionTest {

    // ===== 加载速度测试 =====

    /**
     * 测试歌词解析性能：1000 行 LRC 应在 100ms 内完成解析。
     *
     * <p>对应测试项：歌词加载速度。</p>
     */
    @Test
    public void testLyricsParsingPerformance() {
        // 构造 1000 行 LRC 文本
        StringBuilder lrc = new StringBuilder();
        lrc.append("[ti:性能测试歌曲]\n[ar:测试歌手]\n");
        for (int i = 0; i < 1000; i++) {
            long ms = i * 1000;
            long min = ms / 60000;
            long sec = (ms % 60000) / 1000;
            lrc.append(String.format("[%02d:%02d.00]第%d行歌词内容测试\n", min, sec, i + 1));
        }

        long start = System.currentTimeMillis();
        Lyrics lyrics = Lyrics.parse(lrc.toString());
        long elapsed = System.currentTimeMillis() - start;

        assertNotNull(lyrics);
        assertEquals(1000, lyrics.size());
        // 解析 1000 行应在 100ms 内完成
        assertTrue("Parsing 1000 lines should complete within 100ms, took " + elapsed + "ms",
                elapsed < 100);
    }

    /**
     * 测试空歌词解析速度。
     */
    @Test
    public void testEmptyLyricsParsingSpeed() {
        long start = System.currentTimeMillis();
        Lyrics empty = Lyrics.parse("");
        long elapsed = System.currentTimeMillis() - start;

        assertTrue(empty.isEmpty());
        assertTrue("Empty lyrics parsing should be instant, took " + elapsed + "ms",
                elapsed < 10);
    }

    // ===== 同步准确性测试 =====

    /**
     * 测试歌词同步定位：indexOfActive 应返回正确的行为索引。
     *
     * <p>对应测试项：歌词与音频同步准确性。</p>
     */
    @Test
    public void testSyncAccuracy() {
        Lyrics lyrics = Lyrics.parse(
                "[00:00.00]第一行\n"
                + "[00:05.00]第二行\n"
                + "[00:10.00]第三行\n"
                + "[00:15.00]第四行\n"
                + "[00:20.00]第五行\n");

        // 播放位置 0ms → 第一行（索引 0）
        assertEquals(0, lyrics.indexOfActive(0));

        // 播放位置 3000ms → 仍在第一行
        assertEquals(0, lyrics.indexOfActive(3000));

        // 播放位置 5000ms → 第二行（索引 1）
        assertEquals(1, lyrics.indexOfActive(5000));

        // 播放位置 7500ms → 仍在第二行
        assertEquals(1, lyrics.indexOfActive(7500));

        // 播放位置 10000ms → 第三行（索引 2）
        assertEquals(2, lyrics.indexOfActive(10000));

        // 播放位置 20000ms → 第五行（索引 4）
        assertEquals(4, lyrics.indexOfActive(20000));

        // 播放位置 30000ms → 仍是最后一行
        assertEquals(4, lyrics.indexOfActive(30000));
    }

    /**
     * 测试第一行之前的播放位置：应返回 -1。
     */
    @Test
    public void testSyncBeforeFirstLine() {
        Lyrics lyrics = Lyrics.parse("[00:05.00]第一行\n[00:10.00]第二行\n");

        // 在第一行之前
        assertEquals(-1, lyrics.indexOfActive(0));
        assertEquals(-1, lyrics.indexOfActive(4999));
        // 到达第一行
        assertEquals(0, lyrics.indexOfActive(5000));
    }

    /**
     * 测试毫秒级精度同步。
     */
    @Test
    public void testMillisecondPrecisionSync() {
        Lyrics lyrics = Lyrics.parse(
                "[00:00.500]第一行\n"   // 500ms
                + "[00:01.250]第二行\n" // 1250ms
                + "[00:02.750]第三行\n"); // 2750ms

        assertEquals(-1, lyrics.indexOfActive(0));
        assertEquals(-1, lyrics.indexOfActive(499));
        assertEquals(0, lyrics.indexOfActive(500));
        assertEquals(0, lyrics.indexOfActive(1249));
        assertEquals(1, lyrics.indexOfActive(1250));
        assertEquals(1, lyrics.indexOfActive(2749));
        assertEquals(2, lyrics.indexOfActive(2750));
    }

    // ===== 显示格式测试 =====

    /**
     * 测试 LRC 格式解析：标准时间戳 [mm:ss.xx]。
     *
     * <p>对应测试项：歌词显示格式。</p>
     */
    @Test
    public void testStandardLrcFormat() {
        Lyrics lyrics = Lyrics.parse(
                "[ti:歌曲名]\n"
                + "[ar:歌手名]\n"
                + "[al:专辑名]\n"
                + "[00:00.00]第一行歌词\n"
                + "[00:03.50]第二行歌词\n");

        // 元数据行应被丢弃（无时间戳）
        assertEquals(2, lyrics.size());

        List<Lyrics.Line> lines = lyrics.lines();
        assertEquals(0, lines.get(0).timeMs);
        assertEquals("第一行歌词", lines.get(0).text);
        assertEquals(3500, lines.get(1).timeMs);
        assertEquals("第二行歌词", lines.get(1).text);
    }

    /**
     * 测试多时间戳行：同一行文本对应多个时间戳。
     */
    @Test
    public void testMultipleTimestampsPerLine() {
        Lyrics lyrics = Lyrics.parse("[00:01.00][00:05.00][00:09.00]重复歌词\n");

        assertEquals(3, lyrics.size());
        assertEquals(1000, lyrics.lines().get(0).timeMs);
        assertEquals(5000, lyrics.lines().get(1).timeMs);
        assertEquals(9000, lyrics.lines().get(2).timeMs);
        // 所有行文本相同
        for (Lyrics.Line line : lyrics.lines()) {
            assertEquals("重复歌词", line.text);
        }
    }

    /**
     * 测试不同时间戳分隔符（冒号和点号）。
     */
    @Test
    public void testTimestampSeparators() {
        // 标准格式 [mm:ss.xx]
        Lyrics l1 = Lyrics.parse("[01:30.50]测试\n");
        assertEquals(90500, l1.lines().get(0).timeMs);

        // 无小数 [mm:ss]
        Lyrics l2 = Lyrics.parse("[01:30]测试\n");
        assertEquals(90000, l2.lines().get(0).timeMs);

        // 一位小数 [mm:ss.x] → 百毫秒
        Lyrics l3 = Lyrics.parse("[01:30.5]测试\n");
        assertEquals(90500, l3.lines().get(0).timeMs);

        // 三位小数 [mm:ss.xxx] → 毫秒
        Lyrics l4 = Lyrics.parse("[01:30.500]测试\n");
        assertEquals(90500, l4.lines().get(0).timeMs);
    }

    /**
     * 测试歌词行排序：按时间戳升序排列。
     */
    @Test
    public void testLineSorting() {
        // 输入乱序
        Lyrics lyrics = Lyrics.parse(
                "[00:10.00]第三行\n"
                + "[00:00.00]第一行\n"
                + "[00:05.00]第二行\n");

        List<Lyrics.Line> lines = lyrics.lines();
        assertEquals(0, lines.get(0).timeMs);
        assertEquals("第一行", lines.get(0).text);
        assertEquals(5000, lines.get(1).timeMs);
        assertEquals("第二行", lines.get(1).text);
        assertEquals(10000, lines.get(2).timeMs);
        assertEquals("第三行", lines.get(2).text);
    }

    /**
     * 测试空行和纯元数据行处理。
     */
    @Test
    public void testEmptyAndMetadataLines() {
        Lyrics lyrics = Lyrics.parse(
                "[ti:标题]\n"
                + "\n"
                + "   \n"
                + "[00:01.00]实际歌词\n"
                + "[unknown:未知元数据]\n");

        // 只有带时间戳的行被保留
        assertEquals(1, lyrics.size());
        assertEquals("实际歌词", lyrics.lines().get(0).text);
    }

    // ===== 离线缓存测试 =====

    /**
     * 测试离线缓存能力：raw 字段保留原始 LRC 文本。
     *
     * <p>对应测试项：离线状态下已缓存歌词的读取能力。</p>
     */
    @Test
    public void testOfflineCacheRawText() {
        String originalLrc = "[ti:缓存测试]\n[00:00.00]第一行\n[00:03.00]第二行\n";
        Lyrics lyrics = Lyrics.parse(originalLrc);

        // raw 字段应保留原始文本，可用于离线缓存
        assertNotNull(lyrics.raw);
        assertEquals(originalLrc, lyrics.raw);

        // 从缓存重建 Lyrics 对象
        Lyrics restored = Lyrics.parse(lyrics.raw);
        assertEquals(lyrics.size(), restored.size());
        assertEquals(lyrics.lines().get(0).timeMs, restored.lines().get(0).timeMs);
        assertEquals(lyrics.lines().get(0).text, restored.lines().get(0).text);
    }

    /**
     * 测试空歌词对象的缓存能力。
     */
    @Test
    public void testEmptyLyricsCache() {
        Lyrics empty = Lyrics.empty();

        assertNotNull(empty.raw);
        assertTrue(empty.raw.isEmpty());
        assertTrue(empty.isEmpty());
    }

    /**
     * 测试包含特殊字符的歌词缓存。
     */
    @Test
    public void testSpecialCharacterCache() {
        String lrc = "[00:00.00]中文测试 English Test 123 !@#$%\n";
        Lyrics lyrics = Lyrics.parse(lrc);

        assertEquals(lrc, lyrics.raw);
        assertEquals("中文测试 English Test 123 !@#$%", lyrics.lines().get(0).text);
    }

    // ===== 边界情况测试 =====

    /**
     * 测试超长歌词行。
     */
    @Test
    public void testVeryLongLine() {
        StringBuilder longText = new StringBuilder("超长歌词");
        for (int i = 0; i < 100; i++) {
            longText.append("继续").append(i);
        }
        String lrc = "[00:00.00]" + longText.toString() + "\n";
        Lyrics lyrics = Lyrics.parse(lrc);

        assertEquals(1, lyrics.size());
        assertEquals(longText.toString(), lyrics.lines().get(0).text);
    }

    /**
     * 测试无时间戳的纯文本歌词。
     */
    @Test
    public void testPlainTextWithoutTimestamp() {
        Lyrics lyrics = Lyrics.parse("这是一行没有时间戳的歌词\n另一行\n");

        assertTrue(lyrics.isEmpty());
    }

    /**
     * 测试 null 输入处理。
     */
    @Test
    public void testNullInput() {
        Lyrics lyrics = Lyrics.parse(null);

        assertNotNull(lyrics);
        assertTrue(lyrics.isEmpty());
        assertEquals("", lyrics.raw);
    }
}
