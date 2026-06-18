package com.example.cleanrecovery.music.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 下载功能数据层单元测试。
 *
 * <p>测试覆盖已登录状态下下载功能的核心数据逻辑：</p>
 * <ul>
 *   <li><b>下载队列管理</b>：DownloadedSong 记录创建与查询</li>
 *   <li><b>存储空间检查</b>：sizeBytes 统计与格式化</li>
 *   <li><b>已下载歌曲验证</b>：本地播放所需的元数据完整性</li>
 *   <li><b>质量标识</b>：128/320/flac 质量标签转换</li>
 * </ul>
 *
 * <p>注意：DownloadManager 涉及 Android Context 和网络 IO，
 * 其完整集成测试需在 instrumented test 环境中执行。</p>
 */
public class DownloadFunctionTest {

    // ===== 下载记录管理测试 =====

    /**
     * 测试下载记录创建与字段完整性。
     *
     * <p>对应测试项：下载队列管理 - 记录创建。</p>
     */
    @Test
    public void testDownloadedSongCreation() {
        DownloadedSong song = new DownloadedSong();
        song.hash = "ABC123DEF456";
        song.title = "测试歌曲";
        song.artist = "测试歌手";
        song.album = "测试专辑";
        song.duration = 240; // 4分钟
        song.quality = "320";
        song.localPath = "/storage/emulated/0/Music/downloads/test.mp3";
        song.sizeBytes = 8_388_608L; // 8MB
        song.downloadedAt = System.currentTimeMillis();
        song.imgUrl = "http://example.com/cover.jpg";

        assertEquals("ABC123DEF456", song.hash);
        assertEquals("测试歌曲", song.title);
        assertEquals("测试歌手", song.artist);
        assertEquals("测试专辑", song.album);
        assertEquals(240, song.duration);
        assertEquals("320", song.quality);
        assertEquals("/storage/emulated/0/Music/downloads/test.mp3", song.localPath);
        assertEquals(8_388_608L, song.sizeBytes);
        assertTrue(song.downloadedAt > 0);
        assertEquals("http://example.com/cover.jpg", song.imgUrl);
    }

    /**
     * 测试下载记录的相等性判断（基于 hash）。
     */
    @Test
    public void testDownloadedSongEquality() {
        DownloadedSong s1 = new DownloadedSong();
        s1.hash = "HASH001";
        s1.title = "歌曲A";

        DownloadedSong s2 = new DownloadedSong();
        s2.hash = "HASH001";
        s2.title = "歌曲A（不同标题）";

        DownloadedSong s3 = new DownloadedSong();
        s3.hash = "HASH002";
        s3.title = "歌曲A";

        // 相同 hash 视为相等
        assertTrue(s1.equals(s2));
        assertEquals(s1.hashCode(), s2.hashCode());

        // 不同 hash 视为不等
        assertFalse(s1.equals(s3));

        // null hash 处理
        DownloadedSong nullHash = new DownloadedSong();
        assertFalse(s1.equals(nullHash));
        assertFalse(nullHash.equals(s1));
    }

    // ===== 存储空间检查测试 =====

    /**
     * 测试文件大小格式化显示。
     *
     * <p>对应测试项：存储空间不足时的错误提示 - 大小显示格式。</p>
     */
    @Test
    public void testSizeFormatting() {
        DownloadedSong song = new DownloadedSong();

        // 0 字节
        song.sizeBytes = 0;
        assertEquals("0 B", song.sizeFormatted());

        // 小于 1KB
        song.sizeBytes = 512;
        assertEquals("0.5 KB", song.sizeFormatted());

        // 1KB - 1MB
        song.sizeBytes = 1024;
        assertEquals("1.0 KB", song.sizeFormatted());

        song.sizeBytes = 512_000;
        assertEquals("500.0 KB", song.sizeFormatted());

        // 1MB - 1GB
        song.sizeBytes = 1_048_576; // 1MB
        assertEquals("1.0 MB", song.sizeFormatted());

        song.sizeBytes = 8_388_608; // 8MB
        assertEquals("8.0 MB", song.sizeFormatted());

        song.sizeBytes = 52_428_800; // 50MB
        assertEquals("50.0 MB", song.sizeFormatted());

        // 大于 1GB
        song.sizeBytes = 1_073_741_824L; // 1GB
        assertEquals("1.00 GB", song.sizeFormatted());

        song.sizeBytes = 5_368_709_120L; // 5GB
        assertEquals("5.00 GB", song.sizeFormatted());
    }

    /**
     * 测试时长格式化显示。
     */
    @Test
    public void testDurationFormatting() {
        DownloadedSong song = new DownloadedSong();

        song.duration = 0;
        assertEquals("0:00", song.durationFormatted());

        song.duration = 30;
        assertEquals("0:30", song.durationFormatted());

        song.duration = 60;
        assertEquals("1:00", song.durationFormatted());

        song.duration = 90;
        assertEquals("1:30", song.durationFormatted());

        song.duration = 240; // 4分钟
        assertEquals("4:00", song.durationFormatted());

        song.duration = 3661; // 61分1秒
        assertEquals("61:01", song.durationFormatted());
    }

    // ===== 质量标识测试 =====

    /**
     * 测试音质标签转换。
     *
     * <p>对应测试项：已下载歌曲的本地播放验证 - 质量标识显示。</p>
     */
    @Test
    public void testQualityLabel() {
        DownloadedSong song = new DownloadedSong();

        // 标准质量 128 kbps
        song.quality = "128";
        assertEquals("128 kbps", song.qualityLabel());

        // 高质量 320 kbps
        song.quality = "320";
        assertEquals("320 kbps", song.qualityLabel());

        // 无损 FLAC
        song.quality = "flac";
        assertEquals("FLAC", song.qualityLabel());

        song.quality = "FLAC";
        assertEquals("FLAC", song.qualityLabel());

        song.quality = "Flac";
        assertEquals("FLAC", song.qualityLabel());

        // 未知质量默认为 128 kbps
        song.quality = "unknown";
        assertEquals("128 kbps", song.qualityLabel());

        song.quality = null;
        assertEquals("128 kbps", song.qualityLabel());
    }

    // ===== 本地播放验证测试 =====

    /**
     * 测试本地播放所需的元数据完整性。
     *
     * <p>对应测试项：已下载歌曲的本地播放验证。</p>
     */
    @Test
    public void testLocalPlaybackMetadataIntegrity() {
        DownloadedSong song = new DownloadedSong();
        song.hash = "ABC123";
        song.title = "本地播放测试";
        song.artist = "测试歌手";
        song.duration = 180;
        song.quality = "320";
        song.localPath = "/storage/emulated/0/Music/downloads/test.mp3";
        song.sizeBytes = 7_340_032L; // ~7MB

        // 验证播放所需的关键字段
        assertNotNull("Hash must not be null for playback", song.hash);
        assertFalse("Hash must not be empty for playback", song.hash.isEmpty());
        assertNotNull("Local path must not be null for playback", song.localPath);
        assertFalse("Local path must not be empty for playback", song.localPath.isEmpty());
        assertTrue("Duration must be positive for playback", song.duration > 0);
        assertTrue("Size must be positive for playback", song.sizeBytes > 0);

        // 验证显示信息
        assertTrue("Title should be available for display", song.title != null && !song.title.isEmpty());
        assertTrue("Artist should be available for display", song.artist != null);
        assertTrue("Quality label should be valid",
                song.qualityLabel().equals("320 kbps"));
        assertTrue("Duration should be formatted correctly",
                song.durationFormatted().equals("3:00"));
        assertTrue("Size should be formatted correctly",
                song.sizeFormatted().contains("MB"));
    }

    /**
     * 测试下载完成时间戳记录。
     */
    @Test
    public void testDownloadTimestamp() {
        DownloadedSong song = new DownloadedSong();
        long beforeTime = System.currentTimeMillis();
        song.downloadedAt = System.currentTimeMillis();
        long afterTime = System.currentTimeMillis();

        // 时间戳应在合理范围内
        assertTrue("Download timestamp should be >= beforeTime",
                song.downloadedAt >= beforeTime);
        assertTrue("Download timestamp should be <= afterTime",
                song.downloadedAt <= afterTime);
    }

    // ===== 边界情况测试 =====

    /**
     * 测试空下载记录。
     */
    @Test
    public void testEmptyDownloadedSong() {
        DownloadedSong song = new DownloadedSong();

        // 所有字段应为默认值
        assertEquals(null, song.hash);
        assertEquals(null, song.title);
        assertEquals(0, song.duration);
        assertEquals(0L, song.sizeBytes);
        assertEquals(0L, song.downloadedAt);

        // 格式化方法应能处理空值
        assertEquals("0 B", song.sizeFormatted());
        assertEquals("0:00", song.durationFormatted());
        assertEquals("128 kbps", song.qualityLabel());
    }

    /**
     * 测试超大文件大小（模拟 FLAC 无损格式）。
     */
    @Test
    public void testLargeFileSize() {
        DownloadedSong song = new DownloadedSong();
        song.sizeBytes = 50L * 1024 * 1024; // 50MB FLAC
        song.quality = "flac";

        assertEquals("FLAC", song.qualityLabel());
        assertEquals("50.0 MB", song.sizeFormatted());
    }

    /**
     * 测试极短歌曲（30秒试听片段）。
     */
    @Test
    public void testShortTrialClip() {
        DownloadedSong song = new DownloadedSong();
        song.duration = 30;
        song.quality = "128";
        song.sizeBytes = 480_000L; // ~470KB

        assertEquals("0:30", song.durationFormatted());
        assertEquals("128 kbps", song.qualityLabel());
        assertTrue(song.sizeFormatted().contains("KB"));
    }
}
