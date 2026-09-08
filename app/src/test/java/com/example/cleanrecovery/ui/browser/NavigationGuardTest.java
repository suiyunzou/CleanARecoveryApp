package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * NavigationGuard 决策语料（Phase 2）：播放按钮劫持是高价值场景，
 * 语料锁定「透明覆盖层/播放器区域点击 → 拦截」与「正常点击 → 不误杀」的边界。
 */
public class NavigationGuardTest {

    /** 信号时间戳用真实时钟（decide 内部以 System.currentTimeMillis 判新鲜度）。 */
    private static NavigationGuard.ClickSignal signal(float opacity, float area,
                                                      boolean overPlayer, boolean onAnchor) {
        return new NavigationGuard.ClickSignal(
                System.currentTimeMillis(), opacity, area, overPlayer, onAnchor);
    }

    private static NavigationGuard.Context ctx(String pageHost, String targetHost,
                                               boolean hasGesture, boolean isRedirect,
                                               NavigationGuard.ClickSignal click,
                                               boolean bypass, boolean askEnabled) {
        return new NavigationGuard.Context(pageHost, "https://" + targetHost + "/x",
                targetHost, hasGesture, isRedirect, click, bypass, askEnabled, 2000);
    }

    // ===== 基本放行 =====

    @Test
    public void sameSiteNavigationAlwaysAllowed() {
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("www.example.com", "cdn.example.com", false, false, null, false, true)));
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("example.co.uk", "shop.example.co.uk", false, false, null, false, true)));
    }

    @Test
    public void bypassListedHostAllowedEvenWithHijackSignal() {
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("www.example.com", "ads.junk.net", true, false,
                        signal(0.02f, 0.9f, true, false), true, true)));
    }

    @Test
    public void ordinaryGestureCrossSiteClickAllowed() {
        // 用户正常点击站外链接（直点 a[href]）→ 不拦
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("www.example.com", "www.partner.net", true, false,
                        signal(1.0f, 0.01f, false, true), false, true)));
        // 无任何信号的普通跨站点击
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("www.example.com", "www.partner.net", true, false, null, false, true)));
    }

    // ===== 劫持信号 =====

    @Test
    public void transparentFullscreenOverlayClickBlocked() {
        // 播放按钮上的透明全屏层：链透明度 0.02、覆盖 90% 视口
        assertEquals(NavigationGuard.BLOCK_HIJACK, NavigationGuard.decide(
                ctx("www.example.com", "ads.junk.net", true, false,
                        signal(0.02f, 0.9f, false, false), false, true)));
    }

    @Test
    public void visibleClickOverPlayerAreaAllowed() {
        // 真机反馈收窄：搜索页/视频页里可见元素点击后跨站跳转是正常行为，
        // overPlayer 单独不再构成劫持特征（只有透明+全屏才拦）
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("www.example.com", "track.spam.cn", true, false,
                        signal(1.0f, 0.10f, true, false), false, true)));
    }

    @Test
    public void searchRedirectChainWithFreshClickPassesSilently() {
        // 用户点词条 → /link 中转 → 302 跳目标站：无手势重定向但有点击在先 → 静默放行
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("m.baidu.com", "www.target-news.com", false, true,
                        signal(1.0f, 0.02f, false, false), false, true)));
    }

    @Test
    public void driveByRedirectWithoutAnyClickStillAsks() {
        // 落地页自动跳转（近期无任何用户点击）→ 询问
        NavigationGuard.ClickSignal stale = new NavigationGuard.ClickSignal(
                System.currentTimeMillis() - 5000, 1.0f, 0.01f, false, false);
        assertEquals(NavigationGuard.ASK_REDIRECT, NavigationGuard.decide(
                ctx("m.baidu.com", "www.target-news.com", false, true,
                        stale, false, true)));
    }

    @Test
    public void directAnchorClickOverPlayerNotBlocked() {
        // 播放器区域内的正常站外链接直点 → 用户明确意图，放行
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("www.example.com", "www.partner.net", true, false,
                        signal(1.0f, 0.10f, true, true), false, true)));
    }

    @Test
    public void transparentButSmallElementNotBlocked() {
        // 近透明但面积小（如隐藏计数器）→ 不满足覆盖层特征
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("www.example.com", "ads.junk.net", true, false,
                        signal(0.02f, 0.05f, false, false), false, true)));
    }

    @Test
    public void staleSignalIgnored() {
        // 信号 3 秒前产生（超 2 秒时窗）→ 视为与本次导航无关
        NavigationGuard.ClickSignal stale = new NavigationGuard.ClickSignal(
                System.currentTimeMillis() - 3000, 0.02f, 0.9f, false, false);
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("www.example.com", "ads.junk.net", true, false, stale, false, true)));
        // 未来时间戳（时钟偏移）同样丢弃
        NavigationGuard.ClickSignal future = new NavigationGuard.ClickSignal(
                System.currentTimeMillis() + 5000, 0.02f, 0.9f, false, false);
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("www.example.com", "ads.junk.net", true, false, future, false, true)));
    }

    // ===== 无手势重定向询问 =====

    @Test
    public void nonGestureCrossSiteRedirectAsksWhenEnabled() {
        assertEquals(NavigationGuard.ASK_REDIRECT, NavigationGuard.decide(
                ctx("www.example.com", "login.provider.org", false, true, null, false, true)));
    }

    @Test
    public void nonGestureCrossSiteRedirectSilentWhenAskDisabled() {
        // Via 默认：询问关闭 → 放行（不扩大打击面）
        assertEquals(NavigationGuard.ALLOW, NavigationGuard.decide(
                ctx("www.example.com", "login.provider.org", false, true, null, false, false)));
    }

    // ===== 采集证据 JSON 解析 =====

    @Test
    public void clickSignalJsonParsing() {
        NavigationGuard.ClickSignal s = PageClickCollector.parse(
                "{\"t\":\"click\",\"o\":0.02,\"a\":0.9,\"v\":true,\"h\":false}");
        assertTrue(s.minOpacity < 0.1f);
        assertTrue(s.areaRatio > 0.5f);
        assertTrue(s.overPlayer);
        assertFalse(s.onAnchor);

        // 数值夹紧：超界值不产生越界特征
        NavigationGuard.ClickSignal clamped = PageClickCollector.parse(
                "{\"t\":\"click\",\"o\":5,\"a\":-1,\"v\":false,\"h\":false}");
        assertEquals(1.0f, clamped.minOpacity, 0.0001f);
        assertEquals(0.0f, clamped.areaRatio, 0.0001f);

        assertNull("非 click 类型丢弃", PageClickCollector.parse("{\"t\":\"other\"}"));
        assertNull("脏输入丢弃", PageClickCollector.parse("not json at all"));
        assertNull("空消息丢弃", PageClickCollector.parse(null));
    }

    // ===== 特征边界 =====

    @Test
    public void hijackLayerFeatureBoundaries() {
        assertTrue(NavigationGuard.looksLikeHijackLayer(signal(0.05f, 0.6f, false, false)));
        // overPlayer 不再独立触发（真机反馈：搜索页视频卡片误杀）
        assertFalse(NavigationGuard.looksLikeHijackLayer(signal(0.5f, 0.2f, true, false)));
        assertFalse(NavigationGuard.looksLikeHijackLayer(signal(0.05f, 0.3f, false, false)));
        assertFalse(NavigationGuard.looksLikeHijackLayer(signal(0.3f, 0.8f, false, false)));
        assertFalse("直点链接永远不算劫持层",
                NavigationGuard.looksLikeHijackLayer(signal(0.01f, 0.95f, true, true)));
        assertFalse(NavigationGuard.looksLikeHijackLayer(null));
    }
}
