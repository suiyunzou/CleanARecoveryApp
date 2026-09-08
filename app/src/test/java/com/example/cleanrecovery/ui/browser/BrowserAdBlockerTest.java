package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * ABP/Via 规则兼容语料基线（Phase 0，Phase 0 完成标准：语料全过才算引擎可用）。
 *
 * <p>锁定 Via simple.txt 引擎语义：{@code ||host^} host 集合、{@code @@} 例外
 * 无条件优先、{@code $script/$image/$media} 类型门控、{@code $third-party}、
 * {@code $domain=}（按页面域）、{@code ## / #@# / ~域} 排除、hosts 格式、
 * 大小写、根域判断。引擎按小写匹配（生产端 BrowserAdBlocker 已小写化 URL）。</p>
 */
public class BrowserAdBlockerTest {

    private static AdFilterEngine.Index build(String... lines) {
        AdFilterEngine.Builder b = new AdFilterEngine.Builder();
        for (String l : lines) b.addLine(l);
        return b.build();
    }

    private static boolean blocked(AdFilterEngine.Index idx, String url, String urlHost,
                                   String pageHost, int type, boolean thirdParty) {
        return idx.matchNetwork(url.toLowerCase(Locale.ROOT), urlHost, pageHost, type, thirdParty) != null;
    }

    /** 便捷重载：type 用引擎推断，thirdParty 用 isThirdParty 推导（贴近生产路径）。 */
    private static boolean blocked(AdFilterEngine.Index idx, String url, String pageHost) {
        String h = hostOf(url);
        int type = AdFilterEngine.resourceType(false, null, url.toLowerCase(Locale.ROOT));
        return blocked(idx, url, h, pageHost, type, AdFilterEngine.isThirdParty(h, pageHost));
    }

    private static String hostOf(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        int s = u.indexOf("://");
        u = s >= 0 ? u.substring(s + 3) : u;
        int e = u.indexOf('/');
        if (e >= 0) u = u.substring(0, e);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        return u;
    }

    // ===== 1. ||host^：进 O(1) host 集合，精确 host 与子域命中，边界不误杀 =====

    @Test
    public void hostRuleBlocksExactAndSubdomainsOnly() {
        AdFilterEngine.Index idx = build("||googlesyndication.com^");
        // Via 语义：||host^ 必须落在 host 精确集合，而非 n-gram 正则路径
        assertTrue("||host^ should enter the O(1) host set",
                idx.blockHosts.contains("googlesyndication.com"));
        assertTrue(blocked(idx, "https://pagead2.googlesyndication.com/a.js", "example.com"));
        assertTrue(blocked(idx, "https://googlesyndication.com/x", "example.com"));
        assertFalse("host 前缀相同的无关域名不得误杀",
                blocked(idx, "https://notgooglesyndication.com/a.js", "example.com"));
        assertFalse("路径中出现同名字符串不是主机名边界",
                blocked(idx, "https://example.com/googlesyndication.com.js", "example.com"));
    }

    @Test
    public void blocksCustomHostCaseInsensitively() {
        AdFilterEngine.Index idx = build("||Ads.Example.com^");
        assertTrue(blocked(idx, "https://cdn.ads.example.com/x.js", "example.com"));
        assertFalse(blocked(idx, "https://example.com/x.js", "example.com"));
    }

    @Test
    public void mainFrameHostHitIsBlockedForStubPage() {
        AdFilterEngine.Index idx = build("||ads.example.net^");
        assertTrue(blocked(idx, "https://ads.example.net/", "ads.example.net",
                "ads.example.net", AdFilterEngine.T_DOCUMENT, false));
    }

    // ===== 2. 自定义 URL 通配规则 =====

    @Test
    public void blocksCustomUrlWildcardRules() {
        AdFilterEngine.Index idx = build("*promo-slot*");
        assertTrue(blocked(idx, "https://www.example.com/a/promo-slot/index.js", "www.example.com"));
        assertFalse(blocked(idx, "https://www.example.com/content/index.js", "www.example.com"));
    }

    // ===== 3. @@ 例外无条件优先（含 host 集合路径）=====

    @Test
    public void exceptionRuleOverridesHostSetBlock() {
        AdFilterEngine.Index idx = build("||ads.example.net^", "@@||img.ads.example.net^");
        assertTrue("父域命中无例外时仍拦截",
                blocked(idx, "https://ads.example.net/a.js", "ads.example.net",
                        "example.com", AdFilterEngine.T_SCRIPT, true));
        assertFalse("host 集合拦截之前必须先查例外（@@ 救 ||host^ 拦的请求）",
                blocked(idx, "https://img.ads.example.net/a.js", "example.com"));
    }

    @Test
    public void exceptionRuleOverridesUrlPatternBlock() {
        AdFilterEngine.Index idx = build("||b.com^", "@@/banner.js");
        assertTrue(blocked(idx, "https://x.b.com/ads/a.js", "example.com"));
        assertFalse(blocked(idx, "https://x.b.com/banner.js", "example.com"));
    }

    @Test
    public void typedExceptionUnblocksOnlyItsType() {
        AdFilterEngine.Index idx = build("||cdn.example.net^", "@@||cdn.example.net$image");
        assertFalse("图片类型应被 $image 例外放行",
                blocked(idx, "https://cdn.example.net/img.png", "cdn.example.net", "page.com",
                        AdFilterEngine.T_IMAGE, true));
        assertTrue("脚本类型不受 $image 例外保护，仍拦截",
                blocked(idx, "https://cdn.example.net/app.js", "cdn.example.net", "page.com",
                        AdFilterEngine.T_SCRIPT, true));
    }

    // ===== 4. $script / $image / $media 类型门控 =====

    @Test
    public void typeOptionGatesBlocking() {
        AdFilterEngine.Index idx = build("/ads/$script");
        assertTrue(blocked(idx, "https://www.example.com/ads/app.js", "www.example.com"));
        assertFalse("$script 规则不得拦截非脚本资源",
                blocked(idx, "https://www.example.com/ads/pic.png", "www.example.com"));
    }

    @Test
    public void viaKeepsSupportedPartOfMixedLegacyTypes() {
        AdFilterEngine.Index idx = build("||d1.sina.com.cn^$object,script");
        assertTrue("Via keeps the script part when object cannot be represented by WebView",
                blocked(idx, "https://d1.sina.com.cn/ad.js", "news.example.com"));
        assertFalse("The same mixed rule must still remain script-only",
                blocked(idx, "https://d1.sina.com.cn/ad.png", "d1.sina.com.cn",
                        "news.example.com", AdFilterEngine.T_IMAGE, true));
    }

    @Test
    public void negatedResourceTypeMatchesViaMaskSemantics() {
        AdFilterEngine.Index idx = build("/ads/$~image");
        assertTrue(blocked(idx, "https://cdn.example.net/ads/code.js", "page.example.com"));
        assertFalse(blocked(idx, "https://cdn.example.net/ads/banner.png", "cdn.example.net",
                "page.example.com", AdFilterEngine.T_IMAGE, true));
    }

    @Test
    public void mediaOptionBlocksVideoSegments() {
        AdFilterEngine.Index idx = build("/adseg/$media");
        assertTrue(blocked(idx, "https://cdn.example.com/adseg/0.m4s", "cdn.example.com",
                "www.example.com", AdFilterEngine.T_MEDIA, false));
        assertFalse(blocked(idx, "https://cdn.example.com/adseg/0.m4s", "cdn.example.com",
                "www.example.com", AdFilterEngine.T_SCRIPT, false));
    }

    @Test
    public void resourceTypeInference() {
        assertEquals(AdFilterEngine.T_SCRIPT,
                AdFilterEngine.resourceType(false, null, "https://a.com/x.js"));
        assertEquals(AdFilterEngine.T_IMAGE,
                AdFilterEngine.resourceType(false, null, "https://a.com/x.png?w=2"));
        assertEquals(AdFilterEngine.T_MEDIA,
                AdFilterEngine.resourceType(false, null, "https://a.com/v.m3u8"));
        assertEquals(AdFilterEngine.T_MEDIA,
                AdFilterEngine.resourceType(false, null, "https://a.com/v.m4s"));
        assertEquals(AdFilterEngine.T_MEDIA,
                AdFilterEngine.resourceType(false, null, "https://a.com/manifest.mpd"));
        assertEquals(AdFilterEngine.T_STYLESHEET,
                AdFilterEngine.resourceType(false, null, "https://a.com/x.css"));
        assertEquals(AdFilterEngine.T_FONT,
                AdFilterEngine.resourceType(false, null, "https://a.com/f.woff2"));
    }

    @Test
    public void resourceTypeHeaderLookupIsCaseInsensitive() {
        Map<String, String> headers = new HashMap<>();
        headers.put("ACCEPT", "text/css,*/*;q=0.1");
        assertEquals(AdFilterEngine.T_STYLESHEET,
                AdFilterEngine.resourceType(false, headers, "https://a.com/blob"));

        Map<String, String> xhr = new HashMap<>();
        xhr.put("X-Requested-With", "XMLHttpRequest");
        assertEquals(AdFilterEngine.T_XHR,
                AdFilterEngine.resourceType(false, xhr, "https://a.com/api"));

        Map<String, String> iframe = new HashMap<>();
        iframe.put("Accept", "text/html,application/xhtml+xml");
        assertEquals(AdFilterEngine.T_SUBDOCUMENT,
                AdFilterEngine.resourceType(false, iframe, "https://a.com/frame"));
    }

    // ===== 5. $third-party / $~third-party =====

    @Test
    public void thirdPartyOptionMatchesPartyRelation() {
        AdFilterEngine.Index idx = build("/ads/$third-party");
        assertTrue(blocked(idx, "https://cdn.tracker.net/ads/x.js", "www.example.com"));
        assertFalse("第一方请求不得被 $third-party 拦截",
                blocked(idx, "https://cdn.example.com/ads/x.js", "www.example.com"));
        assertTrue(AdFilterEngine.isThirdParty("cdn.tracker.net", "www.example.com"));
        assertFalse(AdFilterEngine.isThirdParty("cdn.example.com", "www.example.com"));
        // 页面 host 未知 → 按第一方处理（避免误杀）
        assertFalse(AdFilterEngine.isThirdParty("cdn.tracker.net", ""));
    }

    @Test
    public void firstPartyOptionBlocksOnlyFirstParty() {
        AdFilterEngine.Index idx = build("/ads/$~third-party");
        assertFalse(blocked(idx, "https://cdn.tracker.net/ads/x.js", "www.example.com"));
        assertTrue(blocked(idx, "https://www.example.com/ads/x.js", "www.example.com"));
    }

    // ===== 6. $domain=：按页面域判定（非请求 host）=====

    @Test
    public void domainOptionUsesPageDomainNotRequestHost() {
        AdFilterEngine.Index idx = build("/ads/$domain=example.com|foo.org");
        assertTrue(blocked(idx, "https://cdn.cnads.net/ads/x.js", "www.example.com"));
        assertTrue(blocked(idx, "https://cdn.cnads.net/ads/x.js", "foo.org"));
        assertFalse(blocked(idx, "https://cdn.cnads.net/ads/x.js", "www.other.net"));
    }

    @Test
    public void domainOptionSupportsSubdomainsAndExclusions() {
        AdFilterEngine.Index idx = build("/ads/$domain=example.com|~sub.example.com");
        assertTrue(blocked(idx, "https://cdn.cnads.net/ads/x.js", "www.example.com"));
        assertFalse("~domain 排除的子页不得拦截",
                blocked(idx, "https://cdn.cnads.net/ads/x.js", "sub.example.com"));
    }

    // ===== 7. 元素隐藏：## / #@# / ~域 排除 =====

    @Test
    public void cosmeticCssGlobalDomainExceptionAndExclusion() {
        AdFilterEngine.Index idx = build(
                "##.ad-banner",
                "example.com##.sponsor",
                "example.com#@#.sponsor",
                "example.com,~sub.example.com##.tracker");

        String css = idx.cosmeticCss("www.example.com");
        assertTrue(css.contains(".ad-banner"));
        assertFalse("#@# 例外选择器必须从 CSS 中剔除", css.contains(".sponsor"));
        assertTrue(css.contains(".tracker"));

        String cssSub = idx.cosmeticCss("sub.example.com");
        assertTrue(cssSub.contains(".ad-banner"));
        assertFalse("~域 排除对子域生效", cssSub.contains(".tracker"));

        String cssOther = idx.cosmeticCss("other.org");
        assertTrue(cssOther.contains(".ad-banner"));
        assertFalse(cssOther.contains(".sponsor"));
        assertFalse(cssOther.contains(".tracker"));
    }

    // ===== 8. hosts 格式 =====

    @Test
    public void hostsFileLinesBecomeHostRules() {
        AdFilterEngine.Index idx = build(
                "0.0.0.0 ads.example.com",
                "127.0.0.1 tracker.example.org",
                "! 注释行",
                "[AdBlock Plus 2.0]");
        assertTrue(blocked(idx, "https://ads.example.com/x.js", "other.com"));
        assertTrue(blocked(idx, "https://tracker.example.org/x.js", "other.com"));
        assertFalse(blocked(idx, "https://example.org/x.js", "other.com"));
    }

    // ===== 9. 根域判断（PSL 常见二级后缀）=====

    @Test
    public void rootDomainHandlesSecondLevelSuffixes() {
        assertEquals("example.co.uk", AdFilterEngine.rootDomain("www.example.co.uk"));
        assertEquals("qq.com", AdFilterEngine.rootDomain("v.qq.com"));
        assertEquals("com.cn", AdFilterEngine.rootDomain("com.cn"));
        // PSL 长后缀匹配（Via TLD 表 / 兜底表并集）
        assertEquals("example.co.jp", AdFilterEngine.rootDomain("www.example.co.jp"));
        assertEquals("example.co.uk", AdFilterEngine.rootDomain("example.co.uk"));
        // gov.cn 是公开后缀 → 可注册域含 www 标签
        assertEquals("www.gov.cn", AdFilterEngine.rootDomain("www.gov.cn"));
        assertEquals("example.com", AdFilterEngine.rootDomain("example.com"));
        assertTrue(AdFilterEngine.sameSite("a.example.com", "b.example.com"));
        assertFalse(AdFilterEngine.sameSite("example.com", "example.org"));
        assertTrue(AdFilterEngine.sameSite("x.example.co.uk", "y.example.co.uk"));
    }

    // ===== 10. 脏输入不崩、不误匹配 =====

    @Test
    public void degenerateLinesAreIgnoredSafely() {
        AdFilterEngine.Index idx = build(
                "",
                "   ",
                "! comment",
                "[Adblock Plus]",
                "#?#div:has(> p)",
                "@@",
                "||^",
                "|");
        assertFalse(blocked(idx, "https://example.com/anything.js", "example.com"));
    }
}
