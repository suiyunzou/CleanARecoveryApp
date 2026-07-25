package com.example.cleanrecovery.ui.browser;

import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

/**
 * 多标签管理器（内存中维护 WebView 列表）。
 *
 * <p>VIA 风格：每个标签一个独立 WebView，切换标签即切换显示的 WebView。
 * 标签的创建/配置由 {@code BrowserActivity} 负责，本类只负责列表与索引管理。</p>
 */
public final class TabManager {

    public static final class Tab {
        public final int id;
        public final WebView webView;
        public String title = "";
        public String url = "";
        /** 自由附加状态（BrowserActivity 用于存放嗅探器/媒体列表等）。 */
        public Object tag;

        Tab(int id, WebView webView) {
            this.id = id;
            this.webView = webView;
        }
    }

    private final List<Tab> tabs = new ArrayList<>();
    private int currentIndex = -1;
    private int nextId = 1;

    public Tab add(Tab tab) {
        tabs.add(tab);
        currentIndex = tabs.size() - 1;
        return tab;
    }

    public Tab newTab(WebView webView) {
        Tab t = new Tab(nextId++, webView);
        tabs.add(t);
        currentIndex = tabs.size() - 1;
        return t;
    }

    public Tab current() {
        if (currentIndex < 0 || currentIndex >= tabs.size()) return null;
        return tabs.get(currentIndex);
    }

    public int currentIndex() { return currentIndex; }

    public int size() { return tabs.size(); }

    public List<Tab> all() { return tabs; }

    public void select(int index) {
        if (index >= 0 && index < tabs.size()) currentIndex = index;
    }

    /** 关闭指定标签，返回新的当前标签（可能为 null）。 */
    public Tab close(int index) {
        if (index < 0 || index >= tabs.size()) return null;
        tabs.remove(index);
        if (tabs.isEmpty()) {
            currentIndex = -1;
            return null;
        }
        if (currentIndex >= tabs.size()) currentIndex = tabs.size() - 1;
        else if (currentIndex > index) currentIndex--;
        return tabs.get(currentIndex);
    }
}
