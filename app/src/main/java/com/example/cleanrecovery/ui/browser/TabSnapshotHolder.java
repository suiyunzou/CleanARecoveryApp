package com.example.cleanrecovery.ui.browser;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签快照共享（BrowserActivity ↔ TabsActivity）。
 *
 * <p>多标签 WebView 列表保存在 {@code BrowserActivity} 内存中，TabsActivity 通过本静态
 * 持有者读取当前快照，并通过返回结果（action + index）通知 BrowserActivity 执行切换/关闭。</p>
 */
public final class TabSnapshotHolder {
    public static final int ACTION_SWITCH = 1;
    public static final int ACTION_CLOSE = 2;
    public static final int ACTION_NEW = 3;

    public static final String EXTRA_ACTION = "tab_action";
    public static final String EXTRA_INDEX = "tab_index";

    public static final class Snapshot {
        public final List<String> titles = new ArrayList<>();
        public final List<String> urls = new ArrayList<>();
        public int currentIndex = -1;
    }

    private static volatile Snapshot snapshot;

    public static void set(Snapshot s) { snapshot = s; }
    public static Snapshot get() { return snapshot; }

    private TabSnapshotHolder() {}
}
