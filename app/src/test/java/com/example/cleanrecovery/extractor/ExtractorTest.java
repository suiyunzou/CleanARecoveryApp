package com.example.cleanrecovery.extractor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.cleanrecovery.download.UniversalDownloadManager;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 提取器单元测试。
 *
 * <p>覆盖纯逻辑部分（不依赖网络）：</p>
 * <ul>
 *   <li>URL 匹配（suitable）</li>
 *   <li>格式选择（getBestCombinedFormat / getBestVideoOnlyFormat / getBestAudioOnlyFormat）</li>
 *   <li>Generic 工具方法（MIME 转换、URL 扩展名提取）</li>
 *   <li>ExtractorRegistry 注册与匹配</li>
 *   <li>ExtractorException 错误分类</li>
 * </ul>
 *
 * <p>网络提取（extract 方法）需在 instrumented test 中验证。</p>
 */
public class ExtractorTest {

    // ===== URL 匹配测试 =====

    @Test
    public void testBilibiliSuitable_bvUrl() {
        BilibiliExtractor ex = new BilibiliExtractor();
        assertTrue("BV 链接应匹配", ex.suitable("https://www.bilibili.com/video/BV1xx411c7mC"));
        assertTrue("BV 链接带参数应匹配", ex.suitable("https://www.bilibili.com/video/BV1xx411c7mC?p=2"));
        assertTrue("小写 bv 应匹配", ex.suitable("https://www.bilibili.com/video/bv1xx411c7mC"));
    }

    @Test
    public void testBilibiliSuitable_avUrl() {
        BilibiliExtractor ex = new BilibiliExtractor();
        assertTrue("AV 链接应匹配", ex.suitable("https://www.bilibili.com/video/av170001"));
    }

    @Test
    public void testBilibiliSuitable_b23ShortUrl() {
        BilibiliExtractor ex = new BilibiliExtractor();
        assertTrue("b23.tv 短链应匹配", ex.suitable("https://b23.tv/abc123"));
    }

    @Test
    public void testBilibiliSuitable_nonBilibiliUrl() {
        BilibiliExtractor ex = new BilibiliExtractor();
        assertFalse("非 Bilibili URL 不应匹配", ex.suitable("https://www.youtube.com/watch?v=123"));
        assertFalse("抖音 URL 不应匹配", ex.suitable("https://www.douyin.com/video/123"));
    }

    @Test
    public void testDouyinSuitable() {
        DouyinExtractor ex = new DouyinExtractor();
        assertTrue("douyin.com 视频应匹配", ex.suitable("https://www.douyin.com/video/6961737553342991651"));
        assertTrue("iesdouyin.com 应匹配", ex.suitable("https://www.iesdouyin.com/share/video/6961737553342991651"));
        assertTrue("v.douyin.com 短链应匹配", ex.suitable("https://v.douyin.com/abc123/"));
    }

    @Test
    public void testDouyinSuitable_nonDouyin() {
        DouyinExtractor ex = new DouyinExtractor();
        assertFalse("非抖音 URL 不应匹配", ex.suitable("https://www.bilibili.com/video/BV1xx"));
    }

    @Test
    public void testTikTokSuitable() {
        TikTokExtractor ex = new TikTokExtractor();
        assertTrue("tiktok.com 视频应匹配", ex.suitable("https://www.tiktok.com/@user/video/1234567890"));
        assertTrue("vm.tiktok.com 短链应匹配", ex.suitable("https://vm.tiktok.com/ZMxxxxxxx/"));
        assertTrue("vt.tiktok.com 短链应匹配", ex.suitable("https://vt.tiktok.com/ZSxxxxxxx/"));
    }

    // ===== YouTube URL 匹配测试 =====

    @Test
    public void testYouTubeSuitable_watchUrl() {
        YouTubeExtractor ex = new YouTubeExtractor();
        assertTrue("watch?v= 应匹配", ex.suitable("https://www.youtube.com/watch?v=BaW_jenozKc"));
        assertTrue("watch 带额外参数应匹配", ex.suitable("https://www.youtube.com/watch?v=BaW_jenozKc&t=1s&end=9"));
        assertTrue("watch v 参数在中间应匹配", ex.suitable("https://www.youtube.com/watch?list=PL123&v=BaW_jenozKc&index=1"));
        assertTrue("m.youtube.com 应匹配", ex.suitable("https://m.youtube.com/watch?v=BaW_jenozKc"));
    }

    @Test
    public void testYouTubeSuitable_shortUrl() {
        YouTubeExtractor ex = new YouTubeExtractor();
        assertTrue("youtu.be 短链应匹配", ex.suitable("https://youtu.be/BaW_jenozKc"));
        assertTrue("youtu.be 带参数应匹配", ex.suitable("https://youtu.be/BaW_jenozKc?t=10"));
        assertTrue("www.youtu.be 应匹配", ex.suitable("https://www.youtu.be/BaW_jenozKc"));
    }

    @Test
    public void testYouTubeSuitable_embedUrl() {
        YouTubeExtractor ex = new YouTubeExtractor();
        assertTrue("embed 应匹配", ex.suitable("https://www.youtube.com/embed/BaW_jenozKc"));
        assertTrue("embed 带参数应匹配", ex.suitable("https://www.youtube.com/embed/BaW_jenozKc?autoplay=1"));
    }

    @Test
    public void testYouTubeSuitable_shortsUrl() {
        YouTubeExtractor ex = new YouTubeExtractor();
        assertTrue("shorts 应匹配", ex.suitable("https://www.youtube.com/shorts/BaW_jenozKc"));
        assertTrue("m.youtube.com shorts 应匹配", ex.suitable("https://m.youtube.com/shorts/BaW_jenozKc"));
    }

    @Test
    public void testYouTubeSuitable_liveUrl() {
        YouTubeExtractor ex = new YouTubeExtractor();
        assertTrue("live 应匹配", ex.suitable("https://www.youtube.com/live/BaW_jenozKc"));
    }

    @Test
    public void testYouTubeSuitable_vUrl() {
        YouTubeExtractor ex = new YouTubeExtractor();
        assertTrue("v/ 应匹配", ex.suitable("https://www.youtube.com/v/BaW_jenozKc"));
    }

    @Test
    public void testYouTubeSuitable_eUrl() {
        YouTubeExtractor ex = new YouTubeExtractor();
        assertTrue("e/ 应匹配", ex.suitable("https://www.youtube.com/e/BaW_jenozKc"));
    }

    @Test
    public void testYouTubeSuitable_nonYouTube() {
        YouTubeExtractor ex = new YouTubeExtractor();
        assertFalse("非 YouTube URL 不应匹配", ex.suitable("https://www.bilibili.com/video/BV1xx"));
        assertFalse("抖音 URL 不应匹配", ex.suitable("https://www.douyin.com/video/123"));
        assertFalse("null 不应匹配", ex.suitable(null));
        assertFalse("空字符串不应匹配", ex.suitable(""));
        assertFalse("普通网页不应匹配", ex.suitable("https://example.com/video.mp4"));
    }

    @Test
    public void testYouTubeSuitable_invalidId() {
        YouTubeExtractor ex = new YouTubeExtractor();
        // ID 少于 11 位不应匹配
        assertFalse("短 ID 不应匹配", ex.suitable("https://youtu.be/shortid"));
        assertFalse("watch 短 ID 不应匹配", ex.suitable("https://www.youtube.com/watch?v=shortid"));
    }

    @Test
    public void testRegistryMatch_youtube() {
        Extractor ex = ExtractorRegistry.match("https://www.youtube.com/watch?v=BaW_jenozKc");
        assertEquals("youtube", ex.name());
    }

    @Test
    public void testRegistryMatch_youtubeShort() {
        Extractor ex = ExtractorRegistry.match("https://youtu.be/BaW_jenozKc");
        assertEquals("youtube", ex.name());
    }

    @Test
    public void testRegistryMatch_youtubeShorts() {
        Extractor ex = ExtractorRegistry.match("https://www.youtube.com/shorts/BaW_jenozKc");
        assertEquals("youtube", ex.name());
    }

    @Test
    public void testGenericSuitable_alwaysTrue() {
        GenericExtractor ex = new GenericExtractor();
        assertTrue("http URL 应匹配", ex.suitable("http://example.com/video.mp4"));
        assertTrue("https URL 应匹配", ex.suitable("https://example.com/video.mp4"));
        assertFalse("null 不应匹配", ex.suitable(null));
        assertFalse("空字符串不应匹配", ex.suitable(""));
    }

    // ===== Registry 匹配测试 =====

    @Test
    public void testRegistryMatch_bilibili() {
        Extractor ex = ExtractorRegistry.match("https://www.bilibili.com/video/BV1xx411c7mC");
        assertEquals("bilibili", ex.name());
    }

    @Test
    public void testRegistryMatch_douyin() {
        Extractor ex = ExtractorRegistry.match("https://www.douyin.com/video/6961737553342991651");
        assertEquals("douyin", ex.name());
    }

    @Test
    public void testRegistryMatch_tiktok() {
        Extractor ex = ExtractorRegistry.match("https://www.tiktok.com/@user/video/1234567890");
        assertEquals("tiktok", ex.name());
    }

    @Test
    public void testRegistryMatch_genericFallback() {
        Extractor ex = ExtractorRegistry.match("https://example.com/video.mp4");
        assertEquals("未知 URL 应回退到 generic", "generic", ex.name());
    }

    @Test
    public void testRegistryMatch_nullReturnsGeneric() {
        Extractor ex = ExtractorRegistry.match(null);
        assertEquals("generic", ex.name());
    }

    @Test
    public void testRegistryGetExtractors_containsAll() {
        List<Extractor> extractors = ExtractorRegistry.getExtractors();
        assertTrue("应包含 bilibili", extractors.stream().anyMatch(e -> e.name().equals("bilibili")));
        assertTrue("应包含 douyin", extractors.stream().anyMatch(e -> e.name().equals("douyin")));
        assertTrue("应包含 tiktok", extractors.stream().anyMatch(e -> e.name().equals("tiktok")));
    }

    // ===== 格式选择测试 =====

    @Test
    public void testGetBestCombinedFormat() {
        ExtractorResult.Format combined = new ExtractorResult.Format(
                "url1", "mp4", 80, "h264", "aac", 2000, 1920, 1080, 0, "1080P");
        ExtractorResult.Format videoOnly = new ExtractorResult.Format(
                "url2", "mp4", 120, "h265", "none", 5000, 3840, 2160, 0, "4K");
        ExtractorResult.Format audioOnly = new ExtractorResult.Format(
                "url3", "m4a", 0, "none", "aac", 320, 0, 0, 0, "音频");

        ExtractorResult result = new ExtractorResult("id", "title", null,
                Arrays.asList(combined, videoOnly, audioOnly), "title", "mp4");

        ExtractorResult.Format best = result.getBestCombinedFormat();
        assertNotNull("应找到合并格式", best);
        assertEquals("url1", best.url);
    }

    @Test
    public void testGetBestVideoOnlyFormat() {
        ExtractorResult.Format v1 = new ExtractorResult.Format(
                "url1", "mp4", 80, "h264", "none", 2000, 1920, 1080, 0, "1080P");
        ExtractorResult.Format v2 = new ExtractorResult.Format(
                "url2", "mp4", 120, "h265", "none", 5000, 3840, 2160, 0, "4K");

        ExtractorResult result = new ExtractorResult("id", "title", null,
                Arrays.asList(v1, v2), "title", "mp4");

        ExtractorResult.Format best = result.getBestVideoOnlyFormat();
        assertNotNull(best);
        assertEquals("应选择高度最大的", 2160, best.height);
    }

    @Test
    public void testGetBestAudioOnlyFormat() {
        ExtractorResult.Format a1 = new ExtractorResult.Format(
                "url1", "m4a", 0, "none", "aac", 128, 0, 0, 0, "128kbps");
        ExtractorResult.Format a2 = new ExtractorResult.Format(
                "url2", "m4a", 0, "none", "aac", 320, 0, 0, 0, "320kbps");

        ExtractorResult result = new ExtractorResult("id", "title", null,
                Arrays.asList(a1, a2), "title", "m4a");

        ExtractorResult.Format best = result.getBestAudioOnlyFormat();
        assertNotNull(best);
        assertEquals("应选择码率最高的", 320, best.tbr);
    }

    @Test
    public void testGetBestCombinedFormat_nullWhenOnlySeparate() {
        ExtractorResult.Format videoOnly = new ExtractorResult.Format(
                "url1", "mp4", 80, "h264", "none", 2000, 1920, 1080, 0, "1080P");
        ExtractorResult.Format audioOnly = new ExtractorResult.Format(
                "url2", "m4a", 0, "none", "aac", 320, 0, 0, 0, "音频");

        ExtractorResult result = new ExtractorResult("id", "title", null,
                Arrays.asList(videoOnly, audioOnly), "title", "mp4");

        assertNull("仅有分离格式时应返回 null", result.getBestCombinedFormat());
    }

    @Test
    public void testFormatIsAudioOnly() {
        ExtractorResult.Format audio = new ExtractorResult.Format(
                "url", "mp3", 0, "none", "mp3", 320, 0, 0, 0, "音频");
        assertTrue(audio.isAudioOnly());
        assertFalse(audio.isVideoOnly());
    }

    @Test
    public void testFormatIsVideoOnly() {
        ExtractorResult.Format video = new ExtractorResult.Format(
                "url", "mp4", 80, "h264", "none", 2000, 1920, 1080, 0, "1080P");
        assertTrue(video.isVideoOnly());
        assertFalse(video.isAudioOnly());
    }

    @Test
    public void testFormatIsNeither() {
        ExtractorResult.Format combined = new ExtractorResult.Format(
                "url", "mp4", 80, "h264", "aac", 2000, 1920, 1080, 0, "1080P");
        assertFalse(combined.isAudioOnly());
        assertFalse(combined.isVideoOnly());
    }

    // ===== Generic 工具方法测试 =====

    @Test
    public void testIsMediaContentType() {
        assertTrue(GenericExtractor.isMediaContentType("video/mp4"));
        assertTrue(GenericExtractor.isMediaContentType("audio/mpeg"));
        assertTrue(GenericExtractor.isMediaContentType("VIDEO/MP4"));
        assertTrue(GenericExtractor.isMediaContentType("application/octet-stream"));
        assertFalse(GenericExtractor.isMediaContentType("text/html"));
        assertFalse(GenericExtractor.isMediaContentType(null));
    }

    @Test
    public void testMimeToExt() {
        assertEquals("mp4", GenericExtractor.mimeToExt("video/mp4"));
        assertEquals("mp3", GenericExtractor.mimeToExt("audio/mpeg"));
        assertEquals("m4a", GenericExtractor.mimeToExt("audio/mp4"));
        assertEquals("mp4", GenericExtractor.mimeToExt("video/mp4; charset=utf-8"));
        assertNull(GenericExtractor.mimeToExt(null));
        assertNull(GenericExtractor.mimeToExt("text/html"));
    }

    @Test
    public void testExtractExtFromUrl() {
        assertEquals("mp4", GenericExtractor.extractExtFromUrl("https://example.com/video.mp4"));
        assertEquals("mp3", GenericExtractor.extractExtFromUrl("https://example.com/audio.mp3?token=abc"));
        assertEquals("webm", GenericExtractor.extractExtFromUrl("https://example.com/path/to/file.webm#t=5"));
        assertNull(GenericExtractor.extractExtFromUrl("https://example.com/noextension"));
    }

    @Test
    public void testExtractBaseNameFromUrl() {
        assertEquals("video", GenericExtractor.extractBaseNameFromUrl("https://example.com/video.mp4"));
        assertEquals("file", GenericExtractor.extractBaseNameFromUrl("https://example.com/path/to/file.mp3"));
        assertNull(GenericExtractor.extractBaseNameFromUrl("https://example.com/"));
    }

    @Test
    public void testIsMediaExt() {
        assertTrue(GenericExtractor.isMediaExt("mp4"));
        assertTrue(GenericExtractor.isMediaExt("MP4"));
        assertTrue(GenericExtractor.isMediaExt("mp3"));
        assertTrue(GenericExtractor.isMediaExt("flac"));
        assertFalse(GenericExtractor.isMediaExt("html"));
        assertFalse(GenericExtractor.isMediaExt(null));
    }

    @Test
    public void testExtToMime() {
        assertEquals("video/mp4", GenericExtractor.extToMime("mp4"));
        assertEquals("audio/mpeg", GenericExtractor.extToMime("mp3"));
        assertNull(GenericExtractor.extToMime("unknown"));
        assertNull(GenericExtractor.extToMime(null));
    }

    // ===== ExtractorException 测试 =====

    @Test
    public void testExtractorExceptionKind() {
        ExtractorException e1 = new ExtractorException(ExtractorException.Kind.NOT_FOUND, "视频不存在");
        assertEquals(ExtractorException.Kind.NOT_FOUND, e1.getKind());
        assertEquals("视频不存在", e1.getMessage());

        ExtractorException e2 = new ExtractorException(ExtractorException.Kind.LOGIN_REQUIRED,
                "需登录", new RuntimeException("cause"));
        assertEquals(ExtractorException.Kind.LOGIN_REQUIRED, e2.getKind());
        assertNotNull(e2.getCause());
    }

    @Test
    public void testAllExceptionKinds() {
        for (ExtractorException.Kind kind : ExtractorException.Kind.values()) {
            ExtractorException e = new ExtractorException(kind, "test");
            assertEquals(kind, e.getKind());
        }
    }

    // ===== ExtractorResult 不可变性测试 =====

    @Test
    public void testExtractorResultImmutableFormats() {
        ExtractorResult.Format f1 = new ExtractorResult.Format(
                "url1", "mp4", 80, "h264", "aac", 2000, 1920, 1080, 0, "1080P");
        ExtractorResult result = new ExtractorResult("id", "title", null,
                Collections.singletonList(f1), "title", "mp4");

        List<ExtractorResult.Format> formats = result.getFormats();
        assertEquals(1, formats.size());

        try {
            formats.add(new ExtractorResult.Format("url2", "mp4", 0, "none", "none", 0, 0, 0, 0, ""));
            org.junit.Assert.fail("应抛出 UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // 预期：返回不可变列表
        }
    }

    @Test
    public void testExtractorResultNullFormats() {
        ExtractorResult result = new ExtractorResult("id", "title", null, null, "title", "mp4");
        assertNotNull("null formats 应转为空列表", result.getFormats());
        assertEquals(0, result.getFormats().size());
    }

    @Test
    public void testExtractorResultGetters() {
        ExtractorResult result = new ExtractorResult("vid123", "测试标题", "上传者",
                Collections.emptyList(), "测试标题", "mp4");
        assertEquals("vid123", result.getId());
        assertEquals("测试标题", result.getTitle());
        assertEquals("上传者", result.getUploader());
        assertEquals("测试标题", result.getBaseFilename());
        assertEquals("mp4", result.getExt());
    }

    // ============== HLS 检测测试 ==============

    @Test
    public void testIsHlsUrl_m3u8() {
        assertTrue("m3u8 后缀应识别为 HLS",
                HlsDownloader.isHlsUrl("https://example.com/stream.m3u8"));
        assertTrue("m3u 后缀应识别为 HLS",
                HlsDownloader.isHlsUrl("https://example.com/stream.m3u"));
        assertTrue("带查询参数的 m3u8 应识别为 HLS",
                HlsDownloader.isHlsUrl("https://example.com/stream.m3u8?token=abc"));
    }

    @Test
    public void testIsHlsUrl_nonHls() {
        assertFalse("mp4 不应识别为 HLS",
                HlsDownloader.isHlsUrl("https://example.com/video.mp4"));
        assertFalse("mp3 不应识别为 HLS",
                HlsDownloader.isHlsUrl("https://example.com/audio.mp3"));
        assertFalse("HTML 页面不应识别为 HLS",
                HlsDownloader.isHlsUrl("https://example.com/page.html"));
        assertFalse("null 不应识别为 HLS", HlsDownloader.isHlsUrl(null));
    }

    @Test
    public void testIsHlsContentType() {
        assertTrue("vnd.apple.mpegurl 应识别为 HLS",
                HlsDownloader.isHlsContentType("application/vnd.apple.mpegurl"));
        assertTrue("x-mpegurl 应识别为 HLS",
                HlsDownloader.isHlsContentType("application/x-mpegURL"));
        assertTrue("带 charset 的应识别为 HLS",
                HlsDownloader.isHlsContentType("application/vnd.apple.mpegurl; charset=utf-8"));
        assertFalse("video/mp4 不应识别为 HLS",
                HlsDownloader.isHlsContentType("video/mp4"));
        assertFalse("null 不应识别为 HLS", HlsDownloader.isHlsContentType(null));
    }

    // ============== JS 渲染页面检测测试 ==============

    @Test
    public void testIsJsRenderedPage_reactApp() {
        String html = "<!DOCTYPE html><html><head>"
                + "<script src=\"https://cdn.example.com/react.production.min.js\"></script>"
                + "</head><body><div id=\"root\"></div></body></html>";
        assertTrue("React SPA 应识别为 JS 渲染页面",
                GenericExtractor.isJsRenderedPage(html));
    }

    @Test
    public void testIsJsRenderedPage_vueApp() {
        String html = "<!DOCTYPE html><html><head>"
                + "</head><body><div id=\"app\"></div>"
                + "<script src=\"/js/app.js\"></script></body></html>";
        assertTrue("Vue SPA 应识别为 JS 渲染页面",
                GenericExtractor.isJsRenderedPage(html));
    }

    @Test
    public void testIsJsRenderedPage_nextApp() {
        String html = "<!DOCTYPE html><html><head>"
                + "</head><body><div id=\"__next\"></div></body></html>";
        assertTrue("Next.js SPA 应识别为 JS 渲染页面",
                GenericExtractor.isJsRenderedPage(html));
    }

    @Test
    public void testIsJsRenderedPage_noscriptWarning() {
        String html = "<!DOCTYPE html><html><head>"
                + "<noscript>Please enable JavaScript to run this app.</noscript>"
                + "</head><body><div>placeholder</div></body></html>";
        assertTrue("含 noscript 警告的页面应识别为 JS 渲染",
                GenericExtractor.isJsRenderedPage(html));
    }

    @Test
    public void testIsJsRenderedPage_staticPage() {
        String html = "<!DOCTYPE html><html><head><title>普通页面</title></head>"
                + "<body><h1>欢迎</h1><p>这是一段足够长的正文内容，"
                + "用于确保页面不被误判为 SPA。"
                + "这里包含大量可见文本，远超 200 字符阈值，"
                + "因此不会被识别为 JS 渲染页面。</p>"
                + "<video src=\"https://example.com/video.mp4\"></video>"
                + "</body></html>";
        assertFalse("静态内容页面不应识别为 JS 渲染",
                GenericExtractor.isJsRenderedPage(html));
    }

    @Test
    public void testIsJsRenderedPage_emptyOrNull() {
        assertFalse("空字符串不应识别为 JS 渲染",
                GenericExtractor.isJsRenderedPage(""));
        assertFalse("null 不应识别为 JS 渲染",
                GenericExtractor.isJsRenderedPage(null));
    }

    // ============== GenericExtractor 钩子路由测试 ==============

    @Test
    public void testGenericHlsHook_invoked() throws Exception {
        final boolean[] invoked = {false};
        GenericExtractor.setHlsHook((m3u8Url, title) -> {
            invoked[0] = true;
            ExtractorResult.Format fmt = new ExtractorResult.Format(
                    m3u8Url, "ts", 0, "hls", "aac", 0, 0, 0, 0, "HLS");
            return new ExtractorResult(title, title, null,
                    Collections.singletonList(fmt), title, "ts");
        });
        try {
            GenericExtractor ex = new GenericExtractor();
            // 模拟 m3u8 URL（isHlsUrl 仅看扩展名，不实际请求网络）
            // 注意：extract 会尝试 HEAD 请求，这里仅验证钩子注册机制可用
            assertTrue("Generic 应匹配 m3u8 URL",
                    ex.suitable("https://example.com/stream.m3u8"));
        } finally {
            GenericExtractor.setHlsHook(null);
        }
    }

    @Test
    public void testGenericHlsHook_canBeCleared() {
        GenericExtractor.setHlsHook((url, title) -> null);
        GenericExtractor.setHlsHook(null);
        // 注册后清除，不应残留
    }

    @Test
    public void testGenericJsHook_canBeRegistered() {
        GenericExtractor.setJsHook(url -> Collections.emptyList());
        GenericExtractor.setJsHook(null);
        // 注册后清除，不应残留
    }

    // ============== UniversalDownloadManager HLS 入口测试 ==============

    @Test
    public void testDownloadManagerIsHlsUrl() {
        UniversalDownloadManager mgr = new UniversalDownloadManager();
        assertTrue("downloadManager.isHlsUrl 应识别 m3u8",
                mgr.isHlsUrl("https://example.com/stream.m3u8"));
        assertFalse("downloadManager.isHlsUrl 不应识别 mp4",
                mgr.isHlsUrl("https://example.com/video.mp4"));
    }
}

