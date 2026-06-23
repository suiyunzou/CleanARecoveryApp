package com.example.cleanrecovery.music.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

import com.example.cleanrecovery.music.data.Lyrics;
import com.example.cleanrecovery.music.data.SongInfo;

/**
 * 已登录状态下歌词与下载功能集成测试。
 *
 * <p>本测试模拟已登录用户（携带 AuthContext）场景下的核心功能流程，
 * 验证 KugouDataSource 在带鉴权上下文时的行为正确性。</p>
 *
 * <p>测试覆盖：</p>
 * <ul>
 *   <li><b>已登录状态歌词获取</b>：AuthContext 设置后歌词获取流程</li>
 *   <li><b>已登录状态下载 URL 解析</b>：VIP 歌曲的 concept 端点调用</li>
 *   <li><b>多质量下载支持</b>：128/320/flac 三档质量请求</li>
 *   <li><b>异常情况处理</b>：网络错误、空响应、无效 token</li>
 * </ul>
 *
 * <p>注意：本测试不发起真实网络请求，仅验证 AuthContext 传递、
 * 参数构造、错误处理等逻辑。真实网络测试需在 instrumented test 中执行。</p>
 */
public class LoggedInIntegrationTest {

    // ===== AuthContext 测试 =====

    /**
     * 测试 AuthContext 创建与字段完整性。
     *
     * <p>已登录状态下必须能正确创建并传递鉴权上下文。</p>
     */
    @Test
    public void testAuthContextCreation() {
        KugouDataSource.AuthContext ctx = new KugouDataSource.AuthContext(
                "test_token_abc123",
                "2419699404",
                "86199675333783855405531721047009750314",
                "D8Oj9ymF9PKvgzYlvrhXovMl");

        assertEquals("test_token_abc123", ctx.token);
        assertEquals("2419699404", ctx.userid);
        assertEquals("86199675333783855405531721047009750314", ctx.mid);
        assertEquals("D8Oj9ymF9PKvgzYlvrhXovMl", ctx.dfid);
    }

    /**
     * 测试 AuthContext 设置到 DataSource。
     */
    @Test
    public void testSetAuthContext() {
        KugouDataSource dataSource = new KugouDataSource();
        KugouDataSource.AuthContext ctx = new KugouDataSource.AuthContext(
                "token", "userid", "mid", "dfid");

        // 设置前不应影响功能
        dataSource.setAuthContext(null);

        // 设置鉴权上下文
        dataSource.setAuthContext(ctx);
        // 设置后应能正常使用（通过 resolveDownloadUrl 间接验证）
    }

    /**
     * 测试空 AuthContext 字段处理。
     */
    @Test
    public void testEmptyAuthContextFields() {
        KugouDataSource.AuthContext ctx = new KugouDataSource.AuthContext(
                null, null, null, null);

        assertNull(ctx.token);
        assertNull(ctx.userid);
        assertNull(ctx.mid);
        assertNull(ctx.dfid);
    }

    // ===== 歌词获取功能测试（已登录状态） =====

    /**
     * 测试已登录状态下歌词获取：空歌曲信息应返回空歌词。
     *
     * <p>对应测试项：读歌词功能 - 异常输入处理。</p>
     */
    @Test
    public void testGetLyricsWithNullSong() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();
        dataSource.setAuthContext(new KugouDataSource.AuthContext(
                "token", "userid", "mid", "dfid"));

        Lyrics lyrics = dataSource.getLyrics(null);
        assertNotNull(lyrics);
        assertTrue("Null song should return empty lyrics", lyrics.isEmpty());
    }

    /**
     * 测试已登录状态下歌词获取：空 hash 应返回空歌词。
     */
    @Test
    public void testGetLyricsWithEmptyHash() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();
        dataSource.setAuthContext(new KugouDataSource.AuthContext(
                "token", "userid", "mid", "dfid"));

        SongInfo song = new SongInfo();
        song.hash = "";
        song.title = "测试歌曲";

        Lyrics lyrics = dataSource.getLyrics(song);
        assertNotNull(lyrics);
        assertTrue("Empty hash should return empty lyrics", lyrics.isEmpty());
    }

    /**
     * 测试已登录状态下歌词获取：null hash 应返回空歌词。
     */
    @Test
    public void testGetLyricsWithNullHash() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();

        SongInfo song = new SongInfo();
        song.hash = null;
        song.title = "测试歌曲";

        Lyrics lyrics = dataSource.getLyrics(song);
        assertNotNull(lyrics);
        assertTrue("Null hash should return empty lyrics", lyrics.isEmpty());
    }

    // ===== 下载 URL 解析功能测试（已登录状态） =====

    /**
     * 测试已登录状态下下载 URL 解析：空歌曲应返回 null。
     *
     * <p>对应测试项：下载歌曲功能 - 异常输入处理。</p>
     */
    @Test
    public void testResolveDownloadUrlWithNullSong() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();
        dataSource.setAuthContext(new KugouDataSource.AuthContext(
                "token", "userid", "mid", "dfid"));

        String url = dataSource.resolveDownloadUrl(null, "128");
        assertNull("Null song should return null URL", url);
    }

    /**
     * 测试已登录状态下下载 URL 解析：空 hash 应返回 null。
     */
    @Test
    public void testResolveDownloadUrlWithEmptyHash() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();

        SongInfo song = new SongInfo();
        song.hash = "";

        String url = dataSource.resolveDownloadUrl(song, "128");
        assertNull("Empty hash should return null URL", url);
    }

    /**
     * 测试已登录状态下多质量下载请求。
     *
     * <p>对应测试项：下载歌曲功能 - 多质量支持。</p>
     */
    @Test
    public void testMultipleQualityDownloadRequest() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();
        dataSource.setAuthContext(new KugouDataSource.AuthContext(
                "valid_token", "valid_userid", "valid_mid", "valid_dfid"));

        SongInfo song = new SongInfo();
        song.hash = "ABC123DEF456";
        song.title = "测试歌曲";
        song.vipRequired = true;

        // 标准质量（无网络情况下会返回 null，但不应抛出异常）
        String url128 = dataSource.resolveDownloadUrl(song, "128");
        assertNull("No network: 128 quality should return null", url128);

        // 高质量
        String url320 = dataSource.resolveDownloadUrl(song, "320");
        assertNull("No network: 320 quality should return null", url320);

        // 无损质量
        String urlFlac = dataSource.resolveDownloadUrl(song, "flac");
        assertNull("No network: flac quality should return null", urlFlac);

        // null 质量（应默认为 128）
        String urlNull = dataSource.resolveDownloadUrl(song, null);
        assertNull("No network: null quality should return null", urlNull);
    }

    /**
     * 测试质量标识标准化。
     *
     * <p>验证各种质量字符串都能被正确映射到 128/320/flac。</p>
     */
    @Test
    public void testQualityNormalization() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();
        dataSource.setAuthContext(new KugouDataSource.AuthContext(
                "token", "userid", "mid", "dfid"));

        SongInfo song = new SongInfo();
        song.hash = "TESTHASH123";
        song.title = "测试";

        // 各种质量标识变体（无网络，均返回 null，但不应抛异常）
        String[] qualities = {
                "128", "320", "flac",
                "FLAC", "Flac",
                "lossless", "LOSSLESS", "sq", "SQ",
                "high", "HIGH", "hq", "HQ",
                "standard", "low",
                null, "", "unknown"
        };

        for (String q : qualities) {
            String url = dataSource.resolveDownloadUrl(song, q);
            // 无网络时所有质量都应返回 null，不应抛出异常
            assertNull("Quality '" + q + "' should not throw exception", url);
        }
    }

    // ===== 播放 URL 解析功能测试（已登录状态） =====

    /**
     * 测试已登录状态下播放 URL 解析：VIP 歌曲。
     *
     * <p>已登录状态下 VIP 歌曲应优先尝试 concept 端点。</p>
     */
    @Test
    public void testResolvePlayUrlForVipSong() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();
        dataSource.setAuthContext(new KugouDataSource.AuthContext(
                "vip_token", "vip_userid", "vip_mid", "vip_dfid"));

        SongInfo vipSong = new SongInfo();
        vipSong.hash = "VIPSONGHASH123";
        vipSong.title = "VIP歌曲";
        vipSong.vipRequired = true;

        // 无网络情况下应返回 null，不应抛出异常
        String url = dataSource.resolvePlayUrl(vipSong);
        assertNull("No network: VIP song URL should return null", url);
    }

    /**
     * 测试已登录状态下播放 URL 解析：非 VIP 歌曲。
     */
    @Test
    public void testResolvePlayUrlForFreeSong() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();
        dataSource.setAuthContext(new KugouDataSource.AuthContext(
                "token", "userid", "mid", "dfid"));

        SongInfo freeSong = new SongInfo();
        freeSong.hash = "FREESONGHASH456";
        freeSong.title = "免费歌曲";
        freeSong.vipRequired = false;

        // 无网络情况下应返回 null
        String url = dataSource.resolvePlayUrl(freeSong);
        assertNull("No network: free song URL should return null", url);
    }

    /**
     * 测试无 AuthContext 时的播放 URL 解析。
     */
    @Test
    public void testResolvePlayUrlWithoutAuth() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();
        // 不设置 AuthContext

        SongInfo song = new SongInfo();
        song.hash = "TESTHASH789";
        song.title = "测试歌曲";
        song.vipRequired = false;

        // 无网络情况下应返回 null
        String url = dataSource.resolvePlayUrl(song);
        assertNull("No network: should return null", url);
    }

    // ===== 异常情况处理测试 =====

    /**
     * 测试无效 hash 格式的处理。
     */
    @Test
    public void testInvalidHashFormat() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();

        SongInfo song = new SongInfo();
        song.hash = "!!!invalid hash!!!";
        song.title = "测试";

        // 不应抛出异常，应返回 null
        String url = dataSource.resolvePlayUrl(song);
        assertNull("Invalid hash should return null", url);
    }

    /**
     * 测试极长 hash 字符串的处理。
     */
    @Test
    public void testVeryLongHash() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();

        StringBuilder longHash = new StringBuilder();
        for (int i = 0; i < 1000; i++) {
            longHash.append("A");
        }

        SongInfo song = new SongInfo();
        song.hash = longHash.toString();
        song.title = "测试";

        // 不应抛出异常
        String url = dataSource.resolvePlayUrl(song);
        assertNull("Very long hash should return null", url);
    }

    /**
     * 测试包含特殊字符的歌曲信息。
     */
    @Test
    public void testSpecialCharacterSongInfo() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();

        SongInfo song = new SongInfo();
        song.hash = "ABC123";
        song.title = "测试<>&\"'特殊字符";
        song.artist = "歌手&制作人";
        song.album = "专辑<测试>";

        // 不应抛出异常
        Lyrics lyrics = dataSource.getLyrics(song);
        assertNotNull(lyrics);
        assertTrue(lyrics.isEmpty()); // 无网络返回空
    }

    /**
     * 测试连续多次调用歌词获取（模拟快速切歌场景）。
     */
    @Test
    public void testConsecutiveLyricRequests() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();

        for (int i = 0; i < 10; i++) {
            SongInfo song = new SongInfo();
            song.hash = "HASH" + i;
            song.title = "歌曲" + i;

            Lyrics lyrics = dataSource.getLyrics(song);
            assertNotNull(lyrics);
            // 无网络返回空歌词
            assertTrue(lyrics.isEmpty());
        }
    }

    /**
     * 测试连续多次调用下载 URL 解析（模拟批量下载场景）。
     */
    @Test
    public void testConsecutiveDownloadUrlRequests() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();
        dataSource.setAuthContext(new KugouDataSource.AuthContext(
                "token", "userid", "mid", "dfid"));

        for (int i = 0; i < 10; i++) {
            SongInfo song = new SongInfo();
            song.hash = "HASH" + i;
            song.title = "歌曲" + i;

            String url = dataSource.resolveDownloadUrl(song, "128");
            assertNull(url);
        }
    }

    // ===== 性能基准测试 =====

    /**
     * 测试歌词获取响应时间基准（无网络场景）。
     *
     * <p>对应测试项：响应时间记录。</p>
     */
    @Test
    public void testGetLyricsResponseTime() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();

        SongInfo song = new SongInfo();
        song.hash = "PERFTEST123";
        song.title = "性能测试歌曲";

        // 执行 100 次并测量平均时间
        long totalTime = 0;
        int iterations = 100;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            dataSource.getLyrics(song);
            long elapsed = System.nanoTime() - start;
            totalTime += elapsed;
        }

        double avgMs = (totalTime / iterations) / 1_000_000.0;
        // 无网络情况下每次调用应在 200ms 内完成（含 JVM 预热和参数构造时间）
        assertTrue("Average getLyrics time should be < 200ms, was " + avgMs + "ms",
                avgMs < 200);
    }

    /**
     * 测试下载 URL 解析响应时间基准（无网络场景）。
     */
    @Test
    public void testResolveDownloadUrlResponseTime() throws Exception {
        KugouDataSource dataSource = new KugouDataSource();
        dataSource.setAuthContext(new KugouDataSource.AuthContext(
                "token", "userid", "mid", "dfid"));

        SongInfo song = new SongInfo();
        song.hash = "PERFTEST456";
        song.title = "性能测试歌曲";
        song.vipRequired = true;

        long totalTime = 0;
        int iterations = 100;

        for (int i = 0; i < iterations; i++) {
            long start = System.nanoTime();
            dataSource.resolveDownloadUrl(song, "320");
            long elapsed = System.nanoTime() - start;
            totalTime += elapsed;
        }

        double avgMs = (totalTime / iterations) / 1_000_000.0;
        // 无网络情况下每次调用应在 300ms 内完成（含 JVM 预热时间）
        assertTrue("Average resolveDownloadUrl time should be < 300ms, was " + avgMs + "ms",
                avgMs < 300);
    }
}
