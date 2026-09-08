package com.example.cleanrecovery.extractor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 实链 JVM 测试（用例与 2026-08-31 实机链路探测同源的真实链接）。
 *
 * <p>目标站点网络不可达时用 {@code assumeTrue} 跳过（不计失败）。
 * 抖音用例按设计需要 WebView 预热的会话 Cookie，JVM 上无 Cookie，
 * 因此验证的是「无 Cookie 时优雅失败并给出正确错误类别」。</p>
 */
public class LiveExtractorJvmTest {

    private static void assumeReachable(String url) {
        try {
            // 注意：必须用 GET —— B站 API 对 HEAD 返回 405、抖音根页 HEAD 404
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", ExtractorHttp.DEFAULT_UA);
            int code = c.getResponseCode();
            c.disconnect();
            assumeTrue(url + " 不可达（HTTP " + code + "），跳过实链用例", code < 400);
        } catch (IOException e) {
            assumeTrue(url + " 不可达（" + e.getMessage() + "），跳过实链用例", false);
        }
    }

    /** B站全链路：view API 拿 cid → WBI 签名 playurl → DASH 格式 → 直链 Range 分段可用。 */
    @Test
    public void bilibiliLive_extractDashFormats() throws Exception {
        assumeReachable("https://api.bilibili.com/x/web-interface/nav");
        BilibiliExtractor ex = new BilibiliExtractor();
        ExtractorResult r = ex.extract("https://www.bilibili.com/video/BV1GJ411x7h7");
        assertNotNull(r);
        assertTrue("标题不应为空", r.getTitle() != null && !r.getTitle().isEmpty());

        List<ExtractorResult.Format> formats = r.getFormats();
        assertFalse("应解析出格式", formats.isEmpty());
        boolean hasVideo = false;
        boolean hasAudio = false;
        for (ExtractorResult.Format f : formats) {
            if (f.isAudioOnly()) hasAudio = true;
            else hasVideo = true;
        }
        assertTrue("应有视频流（未登录 try_look 至少 360/480P）", hasVideo);
        assertTrue("应有音频流", hasAudio);

        // 直链支持 Range 206 —— IDM 式分段并发下载的前提
        ExtractorResult.Format v = r.getBestVideoOnlyFormat();
        assertNotNull(v);
        HttpURLConnection conn = (HttpURLConnection) new URL(v.url).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("Range", "bytes=0-1024");
        conn.setRequestProperty("Referer", "https://www.bilibili.com/");
        conn.setRequestProperty("User-Agent", ExtractorHttp.DEFAULT_UA);
        assertEquals("B站直链应支持 Range 分段下载", 206, conn.getResponseCode());
        conn.disconnect();
    }

    /** WBI 签名结构：带 wts、w_rid 为 32 位十六进制、同一秒内确定可复现。 */
    @Test
    public void wbiSigner_signsDeterministically() throws Exception {
        assumeReachable("https://api.bilibili.com/x/web-interface/nav");
        Map<String, String> params = new HashMap<>();
        params.put("bvid", "BV1GJ411x7h7");
        params.put("cid", "137649199");
        params.put("fnval", "4048");
        String u1;
        String u2;
        try {
            u1 = WbiSigner.signUrl("https://api.bilibili.com/x/player/wbi/playurl", params);
            u2 = WbiSigner.signUrl("https://api.bilibili.com/x/player/wbi/playurl", params);
        } catch (IOException e) {
            assumeTrue("WBI key 获取不可达，跳过实链: " + e.getMessage(), false);
            return;
        }
        assertTrue("签名 URL 应包含 wts", u1.contains("wts="));
        assertTrue("w_rid 应为 32 位十六进制", u1.matches(".*w_rid=[0-9a-f]{32}.*"));
        assertTrue("同一秒内两次签名应完全一致", u1.equals(u2));
    }

    /** 抖音：无 Cookie（无 WebView 预热）时应抛 LOGIN_REQUIRED 类错误而非崩溃/空数据。 */
    @Test
    public void douyinLive_withoutCookie_failsGracefully() throws Exception {
        assumeReachable("https://www.douyin.com/");
        DouyinExtractor ex = new DouyinExtractor();
        try {
            ex.extract("https://www.douyin.com/video/7680102262987294242");
            // 极小概率命中有效全局 Cookie 而成功，不判失败
        } catch (ExtractorException e) {
            assertTrue("无 Cookie 应归类为登录态/网络限制类错误，实际: " + e.getKind(),
                    e.getKind() == ExtractorException.Kind.LOGIN_REQUIRED
                            || e.getKind() == ExtractorException.Kind.GEO_RESTRICTED);
        } catch (IOException e) {
            assumeTrue("抖音网络请求失败，跳过: " + e.getMessage(), false);
        }
    }

    /** YouTube：InnerTube android_vr 链路（网络不可达环境自动跳过）。 */
    @Test
    public void youtubeLive_innertubeExtract() throws Exception {
        assumeReachable("https://www.youtube.com/");
        YouTubeExtractor ex = new YouTubeExtractor();
        ExtractorResult r = ex.extract("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        assertNotNull(r);
        assertFalse("应解析出格式", r.getFormats().isEmpty());
    }
}
