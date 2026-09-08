package com.example.cleanrecovery.ui.browser;

/** Via's action ids, shared by the gesture editor and browser event dispatcher. */
public final class BrowserActions {
    private BrowserActions() { }

    public static final String[] LABELS = {
            "无操作", "刷新网页", "返回顶部", "滑到底部", "输入网址", "新建标签", "添加书签",
            "打开书签", "打开历史", "关闭标签", "上一个标签", "下一个标签", "网页后退", "网页前进",
            "页内查找", "翻译", "关闭所有标签页", "向上翻页", "向下翻页", "保存网页", "源码",
            "", "", "", "朗读网页", "阅读模式", "打开设置", "复制标签页", "打开下载", "打开 AI 面板"
    };
    public static final String[] SECTIONS = {"", "标签页行为", "特性行为", "地址栏行为", "网页行为"};
    public static final int[][] GROUPS = {
            {0}, {5, 10, 11, 12, 13, 9, 16, 27}, {7, 8, 28, 26, 14, 15, 24, 25, 29},
            {4}, {19, 1, 20, 6, 18, 17, 2, 3}
    };

    public static int defaultAction(String key) {
        switch (key) {
            case "back": return 2;
            case "forward": return 3;
            case "home": return 4;
            case "tab": return 5;
            case "menu": return 1;
            case "left": return 10;
            case "right": return 11;
            default: throw new IllegalArgumentException("Unknown gesture: " + key);
        }
    }
}
