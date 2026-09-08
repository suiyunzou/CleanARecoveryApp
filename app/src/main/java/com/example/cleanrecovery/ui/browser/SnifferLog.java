package com.example.cleanrecovery.ui.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 资源嗅探日志（真 Via：菜单→资源嗅探=整页「网页加载中的资源日志」）。
 * 每条 = 时间 + load 徽标 + 类型徽标 + URL；全局滚动缓存，供 BrowserSnifferActivity 展示。
 */
public final class SnifferLog {
    public static final class Entry {
        public final long timeMs;
        public final String url;
        /** 类型徽标文本（ext 或 mime 子类型，如 mp4/m3u8/png）。 */
        public final String type;

        Entry(long timeMs, String url, String type) {
            this.timeMs = timeMs;
            this.url = url;
            this.type = type;
        }
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final int MAX = 300;

    private SnifferLog() {
    }

    public static synchronized void add(String url) {
        if (url == null || url.isEmpty()) return;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:") || lower.startsWith("about:")
                || lower.startsWith("blob:") || lower.startsWith("javascript:")) return;
        if (ENTRIES.size() >= MAX) ENTRIES.remove(0);
        ENTRIES.add(new Entry(System.currentTimeMillis(), url, typeOf(lower)));
    }

    public static synchronized List<Entry> snapshot() {
        return new ArrayList<>(ENTRIES);
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    public static synchronized int size() {
        return ENTRIES.size();
    }

    /** 类型徽标：取路径扩展名，无则取 mime 子类型，再无则 web。 */
    private static String typeOf(String lower) {
        String path = lower;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        if (slash >= 0) path = path.substring(slash + 1);
        int dot = path.lastIndexOf('.');
        if (dot >= 0 && dot < path.length() - 1 && path.length() - dot <= 6) {
            return path.substring(dot + 1);
        }
        return "web";
    }
}
