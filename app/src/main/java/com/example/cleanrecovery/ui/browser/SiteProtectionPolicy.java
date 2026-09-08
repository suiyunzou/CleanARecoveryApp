package com.example.cleanrecovery.ui.browser;

import java.util.Set;

/**
 * 四维站点保护策略（Phase 1：替代"视频站整站 force-off"的一刀切）。
 *
 * <p>视频站需要的是跳过媒体网络规则（避免误杀播放器 CDN 分片），
 * 而不是连播放按钮跳转劫持防护一起关掉。四个维度独立开关：</p>
 * <ul>
 *   <li>networkBlocking：网络请求规则拦截</li>
 *   <li>cosmeticFiltering：引擎元素隐藏 CSS（手动标记规则不受此限制）</li>
 *   <li>navigationGuard：跨站跳转/弹窗劫持防护（Phase 2 NavigationGuard 消费）</li>
 *   <li>mediaProtection：媒体类型请求的网络规则（m3u8/ts/mp4 等易误杀视频）</li>
 * </ul>
 */
public final class SiteProtectionPolicy {

    public static final SiteProtectionPolicy FULL = new SiteProtectionPolicy(true, true, true, true);
    public static final SiteProtectionPolicy OFF = new SiteProtectionPolicy(false, false, false, false);

    public final boolean networkBlocking;
    public final boolean cosmeticFiltering;
    public final boolean navigationGuard;
    public final boolean mediaProtection;

    public SiteProtectionPolicy(boolean networkBlocking, boolean cosmeticFiltering,
                                boolean navigationGuard, boolean mediaProtection) {
        this.networkBlocking = networkBlocking;
        this.cosmeticFiltering = cosmeticFiltering;
        this.navigationGuard = navigationGuard;
        this.mediaProtection = mediaProtection;
    }

    /**
     * 纯函数解析（JVM 可测）。
     *
     * @param globalOn        全局广告拦截开关
     * @param pageHost        当前页 host（小写）
     * @param siteMode        按站覆盖：0=关 1=开 2=跟随全局
     * @param forceOffRoots   媒体保护豁免站根域（视频站，跳过媒体网络规则但保留其余防护）
     * @param cosmeticSkipRoots 引擎元素隐藏跳过站根域（播放器控制条易被误删）
     */
    public static SiteProtectionPolicy resolve(boolean globalOn, String pageHost, int siteMode,
                                               Set<String> forceOffRoots, Set<String> cosmeticSkipRoots) {
        if (!globalOn) return OFF;
        if (siteMode == 0) return OFF; // 用户显式关闭该站保护
        String root = pageHost == null ? "" : AdFilterEngine.rootDomain(pageHost);
        boolean mediaExempt = matchesScope(forceOffRoots, pageHost, root) && siteMode != 1;
        boolean cosmeticSkip = matchesScope(cosmeticSkipRoots, pageHost, root);

        return new SiteProtectionPolicy(
                true,            // 网络拦截：全局开且未被按站关闭
                !cosmeticSkip,   // 引擎元素隐藏可按站跳过（手动标记规则不受限）
                true,            // 导航防护永远保留：视频站豁免不得放过播放按钮劫持
                !mediaExempt);   // 视频站跳过媒体网络规则，避免误杀播放器 CDN
    }

    /** 豁免表既支持根域（qq.com）也支持完整 host（v.qq.com）。 */
    private static boolean matchesScope(Set<String> roots, String pageHost, String root) {
        if (roots == null || roots.isEmpty()) return false;
        if (!root.isEmpty() && roots.contains(root)) return true;
        return pageHost != null && !pageHost.isEmpty() && roots.contains(pageHost);
    }

    /** 该资源类型是否应走网络规则匹配（mediaProtection 关闭时跳过媒体类请求）。 */
    public boolean shouldFilterType(int type) {
        if (!networkBlocking) return false;
        if (!mediaProtection && type == AdFilterEngine.T_MEDIA) return false;
        return true;
    }
}
