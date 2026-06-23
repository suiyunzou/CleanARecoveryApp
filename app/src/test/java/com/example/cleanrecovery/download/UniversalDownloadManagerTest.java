package com.example.cleanrecovery.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link UniversalDownloadManager} 单元测试。
 *
 * <p>测试覆盖借鉴 yt-dlp HttpFD 实现的核心下载逻辑：</p>
 * <ul>
 *   <li><b>动态块大小调整</b>：bestBlockSize 根据网速自适应</li>
 *   <li><b>文件名清理</b>：sanitizeFileName 处理非法字符与长度限制</li>
 *   <li><b>暂停/取消状态</b>：原子标志的线程安全操作</li>
 *   <li><b>构造器参数</b>：自定义超时/重试/限速配置</li>
 * </ul>
 *
 * <p>注：网络下载（download 方法）涉及真实 HTTP 连接，需在 instrumented test 中验证。</p>
 */
public class UniversalDownloadManagerTest {

    // ===== 动态块大小测试 =====

    /**
     * 测试快速网络下块大小增大。
     *
     * <p>对应 yt-dlp best_block_size：上次读取完整且快速（<50ms）→ 增大块。</p>
     */
    @Test
    public void testBestBlockSize_growOnFastNetwork() {
        int current = UniversalDownloadManager.DEFAULT_BLOCK_SIZE; // 8KB
        int lastReadSize = current; // 完整读取
        long lastReadMs = 30; // 快速

        int result = UniversalDownloadManager.bestBlockSize(current, lastReadSize, lastReadMs);
        assertEquals("快速网络应增大块大小", current * 2, result);
    }

    /**
     * 测试慢速网络下块大小减小。
     *
     * <p>对应 yt-dlp best_block_size：上次读取慢（>200ms）→ 减小块。</p>
     */
    @Test
    public void testBestBlockSize_shrinkOnSlowNetwork() {
        int current = UniversalDownloadManager.DEFAULT_BLOCK_SIZE; // 8KB
        int lastReadSize = current;
        long lastReadMs = 300; // 慢速

        int result = UniversalDownloadManager.bestBlockSize(current, lastReadSize, lastReadMs);
        assertEquals("慢速网络应减小块大小", current / 2, result);
    }

    /**
     * 测试块大小不超过 MAX_BLOCK_SIZE。
     */
    @Test
    public void testBestBlockSize_cappedAtMax() {
        int current = UniversalDownloadManager.MAX_BLOCK_SIZE; // 64KB
        int lastReadSize = current;
        long lastReadMs = 10; // 极快

        int result = UniversalDownloadManager.bestBlockSize(current, lastReadSize, lastReadMs);
        assertEquals("不应超过 MAX_BLOCK_SIZE", UniversalDownloadManager.MAX_BLOCK_SIZE, result);
    }

    /**
     * 测试块大小不低于 MIN_BLOCK_SIZE。
     */
    @Test
    public void testBestBlockSize_flooredAtMin() {
        int current = UniversalDownloadManager.MIN_BLOCK_SIZE; // 4KB
        int lastReadSize = current;
        long lastReadMs = 500; // 极慢

        int result = UniversalDownloadManager.bestBlockSize(current, lastReadSize, lastReadMs);
        assertEquals("不应低于 MIN_BLOCK_SIZE", UniversalDownloadManager.MIN_BLOCK_SIZE, result);
    }

    /**
     * 测试正常网速下块大小保持不变。
     */
    @Test
    public void testBestBlockSize_stableOnNormalNetwork() {
        int current = UniversalDownloadManager.DEFAULT_BLOCK_SIZE;
        int lastReadSize = current;
        long lastReadMs = 100; // 正常

        int result = UniversalDownloadManager.bestBlockSize(current, lastReadSize, lastReadMs);
        assertEquals("正常网速应保持当前块大小", current, result);
    }

    /**
     * 测试 lastReadSize 为 0 时保持当前值。
     */
    @Test
    public void testBestBlockSize_noChangeOnZeroRead() {
        int current = UniversalDownloadManager.DEFAULT_BLOCK_SIZE;
        int result = UniversalDownloadManager.bestBlockSize(current, 0, 100);
        assertEquals("零读取应保持当前块大小", current, result);
    }

    // ===== 文件名清理测试 =====

    /**
     * 测试清理 Windows/Android 非法字符。
     */
    @Test
    public void testSanitizeFileName_illegalChars() {
        assertEquals("a_b_c", UniversalDownloadManager.sanitizeFileName("a:b:c"));
        assertEquals("a_b", UniversalDownloadManager.sanitizeFileName("a\\b"));
        assertEquals("a_b", UniversalDownloadManager.sanitizeFileName("a/b"));
        assertEquals("a_b", UniversalDownloadManager.sanitizeFileName("a*b"));
        assertEquals("a_b", UniversalDownloadManager.sanitizeFileName("a?b"));
        assertEquals("a_b", UniversalDownloadManager.sanitizeFileName("a\"b"));
        assertEquals("a_b", UniversalDownloadManager.sanitizeFileName("a<b"));
        assertEquals("a_b", UniversalDownloadManager.sanitizeFileName("a>b"));
        assertEquals("a_b", UniversalDownloadManager.sanitizeFileName("a|b"));
    }

    /**
     * 测试 null 和空字符串输入。
     */
    @Test
    public void testSanitizeFileName_nullAndEmpty() {
        assertNull(UniversalDownloadManager.sanitizeFileName(null));
        assertNull(UniversalDownloadManager.sanitizeFileName(""));
    }

    /**
     * 测试正常文件名保持不变。
     */
    @Test
    public void testSanitizeFileName_normalName() {
        assertEquals("video_1080p.mp4", UniversalDownloadManager.sanitizeFileName("video_1080p.mp4"));
        assertEquals("youtube_my_video.mp3", UniversalDownloadManager.sanitizeFileName("youtube_my_video.mp3"));
    }

    /**
     * 测试超长文件名截断（保留扩展名）。
     */
    @Test
    public void testSanitizeFileName_truncateLongName() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('a');
        sb.append(".mp4");
        String longName = sb.toString();

        String result = UniversalDownloadManager.sanitizeFileName(longName);
        assertNotNull(result);
        assertTrue("应截断到 200 字符以内", result.length() <= 200);
        assertTrue("应保留扩展名", result.endsWith(".mp4"));
    }

    /**
     * 测试超长无扩展名文件名截断。
     */
    @Test
    public void testSanitizeFileName_truncateNoExtension() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) sb.append('x');

        String result = UniversalDownloadManager.sanitizeFileName(sb.toString());
        assertNotNull(result);
        assertEquals("应截断到 200 字符", 200, result.length());
    }

    /**
     * 测试前后空格 trim。
     */
    @Test
    public void testSanitizeFileName_trimSpaces() {
        assertEquals("file.mp4", UniversalDownloadManager.sanitizeFileName("  file.mp4  "));
    }

    // ===== 暂停/取消状态测试 =====

    /**
     * 测试初始状态非暂停非取消。
     */
    @Test
    public void testInitialState() {
        UniversalDownloadManager mgr = new UniversalDownloadManager();
        assertFalse("初始不应暂停", mgr.isPaused());
        assertFalse("初始不应取消", mgr.isCancelled());
    }

    /**
     * 测试暂停与恢复。
     */
    @Test
    public void testPauseAndResume() {
        UniversalDownloadManager mgr = new UniversalDownloadManager();
        mgr.pause();
        assertTrue("暂停后应处于暂停状态", mgr.isPaused());
        mgr.resume();
        assertFalse("恢复后不应处于暂停状态", mgr.isPaused());
    }

    /**
     * 测试取消。
     */
    @Test
    public void testCancel() {
        UniversalDownloadManager mgr = new UniversalDownloadManager();
        mgr.cancel();
        assertTrue("取消后应处于取消状态", mgr.isCancelled());
    }

    /**
     * 测试取消同时清除暂停。
     */
    @Test
    public void testCancelClearsPause() {
        UniversalDownloadManager mgr = new UniversalDownloadManager();
        mgr.pause();
        assertTrue(mgr.isPaused());
        mgr.cancel();
        assertTrue("取消后应处于取消状态", mgr.isCancelled());
        assertFalse("取消应清除暂停标志", mgr.isPaused());
    }

    // ===== 构造器参数测试 =====

    /**
     * 测试默认构造器使用默认参数。
     */
    @Test
    public void testDefaultConstructor() {
        UniversalDownloadManager mgr = new UniversalDownloadManager();
        assertNotNull(mgr);
        assertFalse(mgr.isPaused());
        assertFalse(mgr.isCancelled());
    }

    /**
     * 测试自定义参数构造器。
     */
    @Test
    public void testCustomConstructor() {
        UniversalDownloadManager mgr = new UniversalDownloadManager(
                5_000, 10_000, 5, 1024 * 1024);
        assertNotNull(mgr);
    }

    // ===== 常量验证测试 =====

    /**
     * 验证核心常量符合预期。
     */
    @Test
    public void testConstants() {
        assertEquals("连接超时默认 15s", 15_000, UniversalDownloadManager.CONNECT_TIMEOUT);
        assertEquals("读取超时默认 30s", 30_000, UniversalDownloadManager.READ_TIMEOUT);
        assertEquals("最小块 4KB", 4 * 1024, UniversalDownloadManager.MIN_BLOCK_SIZE);
        assertEquals("最大块 64KB", 64 * 1024, UniversalDownloadManager.MAX_BLOCK_SIZE);
        assertEquals("默认块 8KB", 8 * 1024, UniversalDownloadManager.DEFAULT_BLOCK_SIZE);
        assertEquals("最大重试 3 次", 3, UniversalDownloadManager.MAX_RETRIES);
        assertEquals("重试基础延迟 1s", 1_000, UniversalDownloadManager.RETRY_BASE_DELAY_MS);
        assertEquals(".part 后缀", ".part", UniversalDownloadManager.PART_SUFFIX);
    }

    /**
     * 验证 MIN < DEFAULT < MAX 的块大小关系。
     */
    @Test
    public void testBlockSizeOrdering() {
        assertTrue("MIN < DEFAULT",
                UniversalDownloadManager.MIN_BLOCK_SIZE < UniversalDownloadManager.DEFAULT_BLOCK_SIZE);
        assertTrue("DEFAULT < MAX",
                UniversalDownloadManager.DEFAULT_BLOCK_SIZE < UniversalDownloadManager.MAX_BLOCK_SIZE);
    }
}
