package com.example.cleanrecovery.ui.browser;

/**
 * 导航劫持防护决策（Phase 2）。纯函数，JVM 可测。
 *
 * <p>决策序：</p>
 * <ol>
 *   <li>同站（可注册域相同）→ 放行</li>
 *   <li>会话放行名单（「继续访问」/「允许一次」）→ 放行</li>
 *   <li>跨站 + 透明全屏遮挡层点击 → 拦截，可允许一次</li>
 *   <li>跨站 + 重定向 + 无手势 + 无近期用户点击 + 询问模式 → 询问
 *       （点词条后的搜索跳转链有近期点击，静默放行）</li>
 *   <li>其余 → 放行（拦截目标只有广告/危险站点的劫持特征，不打正常跳转）</li>
 * </ol>
 *
 * <p>劫持信号来自 {@link PageClickCollector} 的 document-start 采集：
 * 页面只能上报证据，是否拦截由本类在 Java 层判定。</p>
 */
public final class NavigationGuard {

    public static final int ALLOW = 0;
    /** 可疑劫持：拦截并提示「允许一次」。 */
    public static final int BLOCK_HIJACK = 1;
    /** 无手势跨站重定向：交回调用方按偏好询问。 */
    public static final int ASK_REDIRECT = 2;

    /** 点击来源证据（页面采集，Java 层只信 2 秒内的信号）。 */
    public static final class ClickSignal {
        public final long timestampMs;
        /** 命中元素链上最大不透明度（0~1，链上任一层近透明即为劫持特征）。 */
        public final float minOpacity;
        /** 命中元素面积占视口比例（0~1，近全屏覆盖层为劫持特征）。 */
        public final float areaRatio;
        /** 点击点是否落在 video 元素矩形内（播放器区域）。 */
        public final boolean overPlayer;
        /** 命中元素是否为链接本身（a[href] 直点不算劫持）。 */
        public final boolean onAnchor;

        public ClickSignal(long timestampMs, float minOpacity, float areaRatio,
                           boolean overPlayer, boolean onAnchor) {
            this.timestampMs = timestampMs;
            this.minOpacity = minOpacity;
            this.areaRatio = areaRatio;
            this.overPlayer = overPlayer;
            this.onAnchor = onAnchor;
        }
    }

    /** 导航上下文（调用方从 WebView 回调 + 采集器组装）。 */
    public static final class Context {
        public final String pageHost;
        public final String targetUrl;
        public final String targetHost;
        public final boolean hasGesture;
        public final boolean isRedirect;
        public final ClickSignal click;
        /** 会话放行名单命中（「继续访问」/允许过的 host）。 */
        public final boolean bypassListed;
        /** 非手势跨站重定向询问开关（按站覆盖 + 全局）。 */
        public final boolean redirectAskEnabled;
        /** 信号可信时窗（毫秒）：超期信号视为过期丢弃。 */
        public final long signalMaxAgeMs;

        public Context(String pageHost, String targetUrl, String targetHost,
                       boolean hasGesture, boolean isRedirect, ClickSignal click,
                       boolean bypassListed, boolean redirectAskEnabled, long signalMaxAgeMs) {
            this.pageHost = pageHost;
            this.targetUrl = targetUrl;
            this.targetHost = targetHost;
            this.hasGesture = hasGesture;
            this.isRedirect = isRedirect;
            this.click = click;
            this.bypassListed = bypassListed;
            this.redirectAskEnabled = redirectAskEnabled;
            this.signalMaxAgeMs = signalMaxAgeMs;
        }
    }

    private static final float OPAQUE_ENOUGH = 0.10f;
    private static final float OVERLAY_AREA_RATIO = 0.55f;

    /** @return ALLOW / BLOCK_HIJACK / ASK_REDIRECT；BLOCK/ASK 时调用方需消费导航事件。 */
    public static int decide(Context c) {
        if (c == null || c.targetHost == null || c.targetHost.isEmpty()) return ALLOW;
        if (c.bypassListed) return ALLOW;
        if (AdFilterEngine.sameSite(c.targetHost, c.pageHost)) return ALLOW;

        ClickSignal click = freshClick(c);
        if (click != null && looksLikeHijackLayer(click)) {
            return BLOCK_HIJACK;
        }
        // 无手势跨站重定向：仅在「近期没有任何用户点击」时才询问——
        // 搜索结果点词条后的 /link → 302 跳转链是用户意图，必须静默放行
        if (!c.hasGesture && c.isRedirect && c.redirectAskEnabled && click == null) {
            return ASK_REDIRECT;
        }
        return ALLOW;
    }

    private static ClickSignal freshClick(Context c) {
        if (c.click == null) return null;
        long age = System.currentTimeMillis() - c.click.timestampMs;
        if (age < 0 || age > c.signalMaxAgeMs) return null;
        return c.click;
    }

    /**
     * 透明全屏遮挡层特征：点击链近透明（&lt;0.10）且面积大（&gt;55% 视口）。
     * 这是唯一的高置信劫持特征——正常 UI 不会长这样；播放器区域/半透明
     * 元素不再独立触发拦截（搜索页视频卡片、播放器内正常链接点击都会误杀，
     * 2026-09 真机反馈收窄）。直点链接本身（用户明确点了 a[href]）不算。
     */
    static boolean looksLikeHijackLayer(ClickSignal click) {
        if (click == null || click.onAnchor) return false;
        boolean nearlyInvisible = click.minOpacity < OPAQUE_ENOUGH;
        boolean largeOverlay = click.areaRatio >= OVERLAY_AREA_RATIO;
        return nearlyInvisible && largeOverlay;
    }

    private NavigationGuard() {
    }
}
