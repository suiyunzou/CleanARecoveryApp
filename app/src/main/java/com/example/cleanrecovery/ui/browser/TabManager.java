package com.example.cleanrecovery.ui.browser;

import android.webkit.WebView;

import java.util.ArrayList;
import java.util.List;

/**
 * 多标签管理器（内存中维护标签与内部页面栈）。
 *
 * <p>每个标签保留独立页面栈，切换标签或内部页面即切换显示的 WebView。
 * 标签的创建/配置由 {@code BrowserActivity} 负责，本类只负责列表与索引管理。</p>
 */
public final class TabManager {

    public static final class Page {
        public WebView webView;
        public android.os.Bundle savedState;
        public int scrollX;
        public int scrollY;
        public long lastActiveMs;
        public String title = "";
        public String url = "";
        public Object tag;

        Page(WebView webView) {
            this.webView = webView;
        }
    }

    public static final class Tab {
        public final int id;
        public WebView webView;
        public String title = "";
        public String url = "";
        /** 自由附加状态（BrowserActivity 用于存放嗅探器/媒体列表等）。 */
        public Object tag;
        private final List<Page> pages = new ArrayList<>();
        private int pageIndex;

        Tab(int id, WebView webView) {
            this.id = id;
            this.webView = webView;
            pages.add(new Page(webView));
        }

        /** 返回页面列表快照；Page 中的状态仍可供页面回调更新。 */
        public List<Page> pages() {
            syncCurrentPage();
            return new ArrayList<>(pages);
        }

        public Page pageFor(WebView webView) {
            if (webView == null) return null;
            syncCurrentPage();
            for (Page page : pages) if (page.webView == webView) return page;
            return null;
        }

        /** 新导航截断前进页面，返回由 Activity 释放的页面。 */
        public List<Page> appendPage(WebView webView) {
            List<Page> removed = discardForwardPages();
            pages.add(new Page(webView));
            pageIndex = pages.size() - 1;
            readCurrentPage();
            return removed;
        }

        public List<Page> discardForwardPages() {
            syncCurrentPage();
            List<Page> removed = new ArrayList<>(pages.subList(pageIndex + 1, pages.size()));
            pages.subList(pageIndex + 1, pages.size()).clear();
            return removed;
        }

        public List<Page> discardOtherPages() {
            syncCurrentPage();
            Page current = pages.get(pageIndex);
            List<Page> removed = new ArrayList<>(pages);
            removed.remove(current);
            pages.clear();
            pages.add(current);
            pageIndex = 0;
            return removed;
        }

        public int pageIndex() { return pageIndex; }

        public void restoreCurrentView(WebView view) {
            webView = view;
            pages.get(pageIndex).webView = view;
        }

        /** 仅切换内部页面；当前 WebView 的原生历史由 Activity 优先处理。 */
        public boolean selectPage(int direction) {
            syncCurrentPage();
            int target = pageIndex + Integer.signum(direction);
            if (direction == 0 || target < 0 || target >= pages.size()) return false;
            pageIndex = target;
            readCurrentPage();
            return true;
        }

        public boolean canGoBack() {
            return webView != null && (webView.canGoBack() || pageIndex > 0);
        }

        public boolean canGoForward() {
            return webView != null && (webView.canGoForward() || pageIndex + 1 < pages.size());
        }

        /** 清空模型并返回所有页面；本类不销毁或加载 WebView。 */
        public List<Page> clearPages() {
            List<Page> removed = pages();
            pages.clear();
            pageIndex = -1;
            webView = null;
            title = "";
            url = "";
            tag = null;
            return removed;
        }

        private void syncCurrentPage() {
            if (pageIndex < 0 || pageIndex >= pages.size()) return;
            Page page = pages.get(pageIndex);
            page.title = title;
            page.url = url;
            page.tag = tag;
        }

        private void readCurrentPage() {
            Page page = pages.get(pageIndex);
            webView = page.webView;
            title = page.title;
            url = page.url;
            tag = page.tag;
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

    public int indexOf(Tab tab) { return tabs.indexOf(tab); }

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
