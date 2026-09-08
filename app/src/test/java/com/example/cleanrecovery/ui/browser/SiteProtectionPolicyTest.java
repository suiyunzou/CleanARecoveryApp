package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 四维站点保护策略语义（Phase 1：视频站豁免只关媒体网络规则，
 * 导航防护永不豁免——播放按钮跳转劫持不能因为"视频站"就放行）。
 */
public class SiteProtectionPolicyTest {

    private static final Set<String> FORCE_OFF = new HashSet<>(Arrays.asList(
            "youku.com", "iqiyi.com", "mgtv.com", "qq.com"));
    private static final Set<String> COSMETIC_SKIP = new HashSet<>(Arrays.asList(
            "v.qq.com", "film.qq.com", "bilibili.com", "ximalaya.com"));

    private static SiteProtectionPolicy resolve(String pageHost, int siteMode) {
        return SiteProtectionPolicy.resolve(true, pageHost, siteMode, FORCE_OFF, COSMETIC_SKIP);
    }

    @Test
    public void normalSiteGetsFullProtection() {
        SiteProtectionPolicy p = resolve("www.example.com", 2);
        assertTrue(p.networkBlocking);
        assertTrue(p.cosmeticFiltering);
        assertTrue(p.navigationGuard);
        assertTrue(p.mediaProtection);
    }

    @Test
    public void globalOffDisablesEverything() {
        SiteProtectionPolicy p = SiteProtectionPolicy.resolve(
                false, "www.example.com", 2, FORCE_OFF, COSMETIC_SKIP);
        assertFalse(p.networkBlocking);
        assertFalse(p.cosmeticFiltering);
        assertFalse(p.navigationGuard);
        assertFalse(p.mediaProtection);
    }

    @Test
    public void siteExplicitOffDisablesEverything() {
        SiteProtectionPolicy p = resolve("www.example.com", 0);
        assertFalse(p.networkBlocking);
        assertFalse(p.navigationGuard);
    }

    @Test
    public void videoRootSkipsMediaRulesButKeepsGuard() {
        SiteProtectionPolicy p = resolve("static.youku.com", 2);
        assertTrue("视频站网络拦截保持开启", p.networkBlocking);
        assertFalse("媒体网络规则豁免（避免误杀播放器 CDN）", p.mediaProtection);
        assertTrue("导航防护必须保留：豁免不放过播放按钮劫持", p.navigationGuard);
        assertTrue(p.cosmeticFiltering);
    }

    @Test
    public void explicitSiteOnRestoresMediaFiltering() {
        SiteProtectionPolicy p = resolve("static.youku.com", 1);
        assertTrue("用户显式开启该站拦截 → 媒体豁免让位", p.mediaProtection);
    }

    @Test
    public void cosmeticSkipByFullHostAndByRoot() {
        // 完整 host 命中（v.qq.com 是 host，qq.com 是根域——两种口径都要支持）
        SiteProtectionPolicy qq = resolve("v.qq.com", 2);
        assertFalse(qq.cosmeticFiltering);
        assertFalse("根域 qq.com 命中媒体豁免", qq.mediaProtection);
        assertTrue(qq.navigationGuard);

        // 根域命中（bilibili.com 本身就是豁免表条目）
        SiteProtectionPolicy bili = resolve("www.bilibili.com", 2);
        assertFalse(bili.cosmeticFiltering);
        assertTrue("B站不在媒体豁免表：媒体规则照常", bili.mediaProtection);
    }

    @Test
    public void shouldFilterTypeRespectsMediaExemption() {
        SiteProtectionPolicy full = resolve("www.example.com", 2);
        assertTrue(full.shouldFilterType(AdFilterEngine.T_MEDIA));
        assertTrue(full.shouldFilterType(AdFilterEngine.T_SCRIPT));

        SiteProtectionPolicy video = resolve("static.youku.com", 2);
        assertTrue("脚本等非媒体资源照常过滤", video.shouldFilterType(AdFilterEngine.T_SCRIPT));
        assertFalse("媒体资源不过滤", video.shouldFilterType(AdFilterEngine.T_MEDIA));

        SiteProtectionPolicy off = resolve("www.example.com", 0);
        assertFalse(off.shouldFilterType(AdFilterEngine.T_SCRIPT));
    }
}
