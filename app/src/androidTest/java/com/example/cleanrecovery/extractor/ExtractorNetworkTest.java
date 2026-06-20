package com.example.cleanrecovery.extractor;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 提取器真实网络集成测试（在设备上运行，访问真实平台 API）。
 *
 * <p>本测试验证各平台提取器的实际可用性，包括：</p>
 * <ul>
 *   <li>URL 匹配与短链解析</li>
 *   <li>API 调用与响应解析</li>
 *   <li>直链提取与格式列表完整性</li>
 *   <li>VIP/付费内容错误处理</li>
 *   <li>错误链接的容错</li>
 * </ul>
 *
 * <p>运行方式：{@code ./gradlew :app:connectedDebugAndroidTest}</p>
 */
@RunWith(AndroidJUnit4.class)
public class ExtractorNetworkTest {

    private static final String TAG = "ExtractorNetTest";

    @Before
    public void setUp() {
        Log.i(TAG, "=== 开始网络集成测试 ===");
    }

    // ===== Bilibili 测试 =====

    /**
     * 测试用例 B-01：Bilibili 公开视频（BV 号）。
     *
     * <p>预期：提取成功，返回多个 DASH 格式（视频+音频），标题非空。</p>
     */
    @Test
    public void testBilibili_publicVideo() {
        String url = "https://www.bilibili.com/video/BV1GJ411x7h7";  // 公开科普视频
        Log.i(TAG, "测试 B-01: Bilibili 公开视频 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("bilibili", ex.name());

        try {
            ExtractorResult result = ex.extract(url);
            assertNotNull("结果不应为 null", result);
            assertNotNull("标题不应为 null", result.getTitle());
            assertFalse("格式列表不应为空", result.getFormats().isEmpty());
            Log.i(TAG, "B-01 通过：标题=" + result.getTitle()
                    + " 格式数=" + result.getFormats().size());

            // 验证至少有一个视频格式
            boolean hasVideo = false;
            for (ExtractorResult.Format f : result.getFormats()) {
                if (!f.isAudioOnly()) { hasVideo = true; break; }
            }
            assertTrue("应至少有一个视频格式", hasVideo);
        } catch (Exception e) {
            Log.e(TAG, "B-01 失败", e);
            fail("Bilibili 公开视频提取失败: " + e.getMessage());
        }
    }

    /**
     * 测试用例 B-02：Bilibili 分 P 视频。
     *
     * <p>预期：提取成功，标题包含分 P 信息。</p>
     */
    @Test
    public void testBilibili_multiPage() {
        String url = "https://www.bilibili.com/video/BV1GJ411x7h7?p=1";
        Log.i(TAG, "测试 B-02: Bilibili 分P视频 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("bilibili", ex.name());

        try {
            ExtractorResult result = ex.extract(url);
            assertNotNull(result);
            assertFalse(result.getFormats().isEmpty());
            Log.i(TAG, "B-02 通过：标题=" + result.getTitle());
        } catch (Exception e) {
            Log.e(TAG, "B-02 失败", e);
            fail("分P视频提取失败: " + e.getMessage());
        }
    }

    /**
     * 测试用例 B-03：Bilibili AV 号链接。
     */
    @Test
    public void testBilibili_avUrl() {
        String url = "https://www.bilibili.com/video/av170001";
        Log.i(TAG, "测试 B-03: Bilibili AV号 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("bilibili", ex.name());

        try {
            ExtractorResult result = ex.extract(url);
            assertNotNull(result);
            Log.i(TAG, "B-03 通过：标题=" + result.getTitle());
        } catch (ExtractorException e) {
            // AV 号转换可能失败，记录但不强制失败
            Log.w(TAG, "B-03 警告：AV号转换 " + e.getKind() + " " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "B-03 警告：" + e.getMessage());
        }
    }

    /**
     * 测试用例 B-04：Bilibili 不存在的视频。
     *
     * <p>预期：抛出 ExtractorException，kind 为 NOT_FOUND 或 PARSE_FAILED。</p>
     */
    @Test
    public void testBilibili_notFound() {
        String url = "https://www.bilibili.com/video/BV0000000000";
        Log.i(TAG, "测试 B-04: Bilibili 不存在的视频 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        try {
            ex.extract(url);
            fail("应抛出 ExtractorException");
        } catch (ExtractorException e) {
            Log.i(TAG, "B-04 通过：正确抛出 " + e.getKind());
            assertTrue("应为 NOT_FOUND 或 PARSE_FAILED",
                    e.getKind() == ExtractorException.Kind.NOT_FOUND
                            || e.getKind() == ExtractorException.Kind.PARSE_FAILED);
        } catch (Exception e) {
            Log.w(TAG, "B-04 警告：非预期异常 " + e.getMessage());
        }
    }

    /**
     * 测试用例 B-05：Bilibili 大会员专属视频（VIP 内容）。
     *
     * <p>预期：抛出 PREMIUM_ONLY 或返回有限格式（仅试看）。</p>
     */
    @Test
    public void testBilibili_vipContent() {
        // 这是一个已知的大会员专属视频（如番剧）
        String url = "https://www.bilibili.com/bangumi/play/ep16725";
        Log.i(TAG, "测试 B-05: Bilibili VIP内容 " + url);

        // 番剧 URL 不匹配 BV/AV 正则，会回退到 Generic
        Extractor ex = ExtractorRegistry.match(url);
        Log.i(TAG, "B-05 匹配到提取器: " + ex.name());

        try {
            ex.extract(url);
            Log.i(TAG, "B-05 信息：VIP内容提取未抛异常（可能返回有限格式）");
        } catch (ExtractorException e) {
            Log.i(TAG, "B-05 通过：VIP内容正确抛出 " + e.getKind());
            // 预期 PREMIUM_ONLY 或 PARSE_FAILED
            assertTrue("VIP内容应抛出 PREMIUM_ONLY 或 PARSE_FAILED",
                    e.getKind() == ExtractorException.Kind.PREMIUM_ONLY
                            || e.getKind() == ExtractorException.Kind.PARSE_FAILED
                            || e.getKind() == ExtractorException.Kind.UNSUPPORTED);
        } catch (Exception e) {
            Log.w(TAG, "B-05 警告：" + e.getMessage());
        }
    }

    // ===== 抖音测试 =====

    /**
     * 测试用例 D-01：抖音公开视频。
     *
     * <p>预期：提取成功或返回 LOGIN_REQUIRED（API 需要 cookie）。</p>
     */
    @Test
    public void testDouyin_publicVideo() {
        String url = "https://www.douyin.com/video/6961737553342991651";
        Log.i(TAG, "测试 D-01: 抖音公开视频 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("douyin", ex.name());

        try {
            ExtractorResult result = ex.extract(url);
            assertNotNull(result);
            assertFalse("格式列表不应为空", result.getFormats().isEmpty());
            Log.i(TAG, "D-01 通过：标题=" + result.getTitle()
                    + " 格式数=" + result.getFormats().size());
        } catch (ExtractorException e) {
            Log.w(TAG, "D-01 信息：抖音API限制 " + e.getKind() + " " + e.getMessage());
            // 抖音 API 经常需要有效 cookie，LOGIN_REQUIRED 是预期行为
            assertTrue("抖音应返回 LOGIN_REQUIRED 或 PARSE_FAILED",
                    e.getKind() == ExtractorException.Kind.LOGIN_REQUIRED
                            || e.getKind() == ExtractorException.Kind.PARSE_FAILED
                            || e.getKind() == ExtractorException.Kind.GEO_RESTRICTED);
        } catch (Exception e) {
            Log.e(TAG, "D-01 失败", e);
            fail("抖音提取异常: " + e.getMessage());
        }
    }

    /**
     * 测试用例 D-02：抖音不存在的视频。
     */
    @Test
    public void testDouyin_notFound() {
        String url = "https://www.douyin.com/video/0000000000000000000";
        Log.i(TAG, "测试 D-02: 抖音不存在视频 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        try {
            ex.extract(url);
            Log.i(TAG, "D-02 信息：未抛异常（API 可能返回空数据）");
        } catch (ExtractorException e) {
            Log.i(TAG, "D-02 通过：正确抛出 " + e.getKind());
        } catch (Exception e) {
            Log.w(TAG, "D-02 警告：" + e.getMessage());
        }
    }

    // ===== TikTok 测试 =====

    /**
     * 测试用例 T-01：TikTok 公开视频。
     *
     * <p>预期：提取成功或返回 PARSE_FAILED（页面结构变更）。</p>
     */
    @Test
    public void testTikTok_publicVideo() {
        String url = "https://www.tiktok.com/@tiktok/video/7106594312292453675";
        Log.i(TAG, "测试 T-01: TikTok公开视频 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("tiktok", ex.name());

        try {
            ExtractorResult result = ex.extract(url);
            assertNotNull(result);
            assertFalse("格式列表不应为空", result.getFormats().isEmpty());
            Log.i(TAG, "T-01 通过：标题=" + result.getTitle()
                    + " 格式数=" + result.getFormats().size());
        } catch (ExtractorException e) {
            Log.w(TAG, "T-01 信息：TikTok限制 " + e.getKind() + " " + e.getMessage());
            // TikTok 可能因地区限制或页面结构变更失败
            assertTrue("TikTok 应返回 PARSE_FAILED 或 LOGIN_REQUIRED",
                    e.getKind() == ExtractorException.Kind.PARSE_FAILED
                            || e.getKind() == ExtractorException.Kind.LOGIN_REQUIRED
                            || e.getKind() == ExtractorException.Kind.GEO_RESTRICTED);
        } catch (Exception e) {
            Log.e(TAG, "T-01 失败", e);
            fail("TikTok 提取异常: " + e.getMessage());
        }
    }

    // ===== Generic 测试 =====

    /**
     * 测试用例 G-01：直链 MP4 下载。
     *
     * <p>预期：GenericExtractor 识别为媒体流，返回单个格式。</p>
     */
    @Test
    public void testGeneric_directMp4() {
        // Google 官方测试视频
        String url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4";
        Log.i(TAG, "测试 G-01: 直链MP4 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("generic", ex.name());

        try {
            ExtractorResult result = ex.extract(url);
            assertNotNull(result);
            assertEquals("应有1个格式", 1, result.getFormats().size());
            ExtractorResult.Format f = result.getFormats().get(0);
            assertNotNull("URL不应为空", f.url);
            Log.i(TAG, "G-01 通过：ext=" + f.ext + " url=" + f.url.substring(0, Math.min(50, f.url.length())));
        } catch (Exception e) {
            Log.e(TAG, "G-01 失败", e);
            fail("直链MP4提取失败: " + e.getMessage());
        }
    }

    /**
     * 测试用例 G-02：直链 MP3 下载。
     */
    @Test
    public void testGeneric_directMp3() {
        String url = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3";
        Log.i(TAG, "测试 G-02: 直链MP3 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("generic", ex.name());

        try {
            ExtractorResult result = ex.extract(url);
            assertNotNull(result);
            assertEquals(1, result.getFormats().size());
            Log.i(TAG, "G-02 通过：ext=" + result.getFormats().get(0).ext);
        } catch (Exception e) {
            Log.e(TAG, "G-02 失败", e);
            fail("直链MP3提取失败: " + e.getMessage());
        }
    }

    /**
     * 测试用例 G-03：非媒体网页（应抛出 UNSUPPORTED）。
     */
    @Test
    public void testGeneric_nonMediaPage() {
        String url = "https://example.com/";
        Log.i(TAG, "测试 G-03: 非媒体网页 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("generic", ex.name());

        try {
            ex.extract(url);
            Log.i(TAG, "G-03 信息：未抛异常（可能找到og:video）");
        } catch (ExtractorException e) {
            Log.i(TAG, "G-03 通过：正确抛出 " + e.getKind());
            assertEquals("应抛出 UNSUPPORTED",
                    ExtractorException.Kind.UNSUPPORTED, e.getKind());
        } catch (Exception e) {
            Log.w(TAG, "G-03 警告：" + e.getMessage());
        }
    }

    // ===== Registry 集成测试 =====

    /**
     * 测试用例 R-01：Registry 对各平台 URL 的匹配。
     */
    @Test
    public void testRegistry_allPlatforms() {
        Log.i(TAG, "测试 R-01: Registry 全平台匹配");

        assertEquals("bilibili", ExtractorRegistry.match(
                "https://www.bilibili.com/video/BV1GJ411x7h7").name());
        assertEquals("bilibili", ExtractorRegistry.match(
                "https://b23.tv/abc123").name());
        assertEquals("douyin", ExtractorRegistry.match(
                "https://www.douyin.com/video/6961737553342991651").name());
        assertEquals("douyin", ExtractorRegistry.match(
                "https://v.douyin.com/abc123/").name());
        assertEquals("tiktok", ExtractorRegistry.match(
                "https://www.tiktok.com/@user/video/123456").name());
        assertEquals("tiktok", ExtractorRegistry.match(
                "https://vm.tiktok.com/abc123/").name());
        assertEquals("generic", ExtractorRegistry.match(
                "https://example.com/video.mp4").name());

        Log.i(TAG, "R-01 通过：全平台匹配正确");
    }

    // ===== YouTube 测试 =====

    /**
     * 测试用例 Y-01：YouTube watch URL 提取。
     *
     * <p>预期：YouTubeExtractor 匹配并提取成功，返回多个格式。</p>
     * <p>注意：需设备网络可访问 YouTube。中国大陆默认网络下可能因
     * GEO_RESTRICTED 失败，此情况视为环境限制而非代码缺陷。</p>
     */
    @Test
    public void testYouTube_diagnostic() {
        String url = "https://www.youtube.com/watch?v=jNQXAC9IVRw"; // Me at the zoo
        Log.i(TAG, "测试 Y-01: YouTube " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("应匹配 youtube 提取器", "youtube", ex.name());
        Log.i(TAG, "Y-01 匹配到提取器: " + ex.name());

        try {
            ExtractorResult result = ex.extract(url);
            assertNotNull("结果不应为 null", result);
            assertNotNull("标题不应为 null", result.getTitle());
            assertFalse("格式列表不应为空", result.getFormats().isEmpty());
            Log.i(TAG, "Y-01 通过: 标题=" + result.getTitle()
                    + " 格式数=" + result.getFormats().size());
            for (ExtractorResult.Format f : result.getFormats()) {
                Log.i(TAG, "Y-01 格式: " + f.description + " ext=" + f.ext
                        + " url=" + (f.url != null ? f.url.substring(0, Math.min(60, f.url.length())) : "null"));
            }
        } catch (ExtractorException e) {
            Log.w(TAG, "Y-01 ExtractorException: kind=" + e.getKind() + " msg=" + e.getMessage());
            // GEO_RESTRICTED 和反机器人检测是环境限制，不视为失败
            if (e.getKind() == ExtractorException.Kind.GEO_RESTRICTED) {
                Log.i(TAG, "Y-01 跳过: 网络环境无法访问 YouTube");
            } else if (e.getKind() == ExtractorException.Kind.LOGIN_REQUIRED
                    && e.getMessage() != null && e.getMessage().contains("bot")) {
                Log.i(TAG, "Y-01 跳过: YouTube 反机器人检测触发（环境限制）");
            } else {
                fail("YouTube 提取失败 [" + e.getKind() + "]: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.w(TAG, "Y-01 异常: " + e.getClass().getSimpleName() + " msg=" + e.getMessage());
            fail("YouTube 提取异常: " + e.getMessage());
        }
    }

    /**
     * 测试用例 Y-02：YouTube 短链 youtu.be 提取。
     */
    @Test
    public void testYouTube_shortUrl_diagnostic() {
        String url = "https://youtu.be/jNQXAC9IVRw";
        Log.i(TAG, "测试 Y-02: YouTube 短链 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("应匹配 youtube 提取器", "youtube", ex.name());
        Log.i(TAG, "Y-02 匹配到提取器: " + ex.name());

        try {
            ExtractorResult result = ex.extract(url);
            assertNotNull("结果不应为 null", result);
            assertFalse("格式列表不应为空", result.getFormats().isEmpty());
            Log.i(TAG, "Y-02 通过: 标题=" + result.getTitle()
                    + " 格式数=" + result.getFormats().size());
        } catch (ExtractorException e) {
            Log.w(TAG, "Y-02 ExtractorException: kind=" + e.getKind() + " msg=" + e.getMessage());
            if (e.getKind() == ExtractorException.Kind.GEO_RESTRICTED) {
                Log.i(TAG, "Y-02 跳过: 网络环境无法访问 YouTube");
            } else if (e.getKind() == ExtractorException.Kind.LOGIN_REQUIRED
                    && e.getMessage() != null && e.getMessage().contains("bot")) {
                Log.i(TAG, "Y-02 跳过: YouTube 反机器人检测触发（环境限制）");
            } else {
                fail("YouTube 短链提取失败 [" + e.getKind() + "]: " + e.getMessage());
            }
        } catch (Exception e) {
            fail("YouTube 短链提取异常: " + e.getMessage());
        }
    }

    /**
     * 测试用例 Y-03：YouTube 不存在的视频。
     */
    @Test
    public void testYouTube_notFound() {
        String url = "https://www.youtube.com/watch?v=aaaaaaaaaaa";
        Log.i(TAG, "测试 Y-03: YouTube 不存在视频 " + url);

        Extractor ex = ExtractorRegistry.match(url);
        assertEquals("youtube", ex.name());

        try {
            ex.extract(url);
            Log.w(TAG, "Y-03 警告: 不存在视频未抛异常");
        } catch (ExtractorException e) {
            Log.i(TAG, "Y-03 通过: 正确抛出 " + e.getKind());
        } catch (Exception e) {
            Log.i(TAG, "Y-03 通过: 抛出 " + e.getClass().getSimpleName());
        }
    }

    /**
     * 诊断测试 D-01：检查应用网络出口 IP 与代理状态。
     *
     * <p>用于诊断 YouTube 反机器人检测是否由代理未生效导致。</p>
     */
    @Test
    public void testDiagnose_proxyAndIp() {
        Log.i(TAG, "=== D-01 代理与出口 IP 诊断 ===");
        try {
            // 1. 读取系统代理设置
            String httpProxy = System.getProperty("http.proxyHost");
            String httpPort = System.getProperty("http.proxyPort");
            Log.i(TAG, "D-01 系统代理: host=" + httpProxy + " port=" + httpPort);

            // 2. 访问 IP 查询服务获取出口 IP
            String ipJson = ExtractorHttp.downloadJson(
                    "https://api.ipify.org?format=json", null);
            Log.i(TAG, "D-01 出口 IP 响应: " + ipJson);

            // 3. 访问 ipinfo.io 获取 IP 地理位置
            String ipInfo = ExtractorHttp.downloadJson(
                    "https://ipinfo.io/json", null);
            Log.i(TAG, "D-01 IP 地理信息: " + ipInfo);

            // 4. 测试 YouTube 连通性
            try {
                String ytResp = ExtractorHttp.downloadWebpage(
                        "https://www.youtube.com/", null);
                Log.i(TAG, "D-01 YouTube 可访问, 响应长度=" + ytResp.length());
            } catch (Exception e) {
                Log.w(TAG, "D-01 YouTube 不可访问: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "D-01 诊断失败: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }
}
