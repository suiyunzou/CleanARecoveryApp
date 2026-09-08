package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewDatabase;

import java.io.File;
import java.io.IOException;
import java.util.Set;

/** Shared targets for settings, browser menu and exit cleanup. Call on the UI thread. */
public final class BrowserDataCleaner {
    private static final Set<WebView> LIVE_VIEWS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private BrowserDataCleaner() { }

    static void register(WebView view) { LIVE_VIEWS.add(view); }
    static void unregister(WebView view) { LIVE_VIEWS.remove(view); }

    public static void clear(Context context, Set<String> flags, Iterable<WebView> views,
                             Runnable complete) throws IOException {
        BrowserPrefs prefs = new BrowserPrefs(context);
        Set<WebView> targets = new java.util.LinkedHashSet<>(LIVE_VIEWS);
        for (WebView view : views) targets.add(view);
        for (WebView view : targets) {
            if (flags.contains("cache")) view.clearCache(true);
            if (flags.contains("form")) view.clearFormData();
            if (flags.contains("history")) view.clearHistory();
        }
        if (flags.contains("cache")) {
            WebView cache = new WebView(context);
            cache.clearCache(true);
            cache.destroy();
        }
        if (flags.contains("form")) WebViewDatabase.getInstance(context).clearFormData();
        if (flags.contains("history")) prefs.dbHelper().clearHistory();
        if (flags.contains("closed_tabs")) prefs.setClosedTabs("");
        if (flags.contains("web_storage")) WebStorage.getInstance().deleteAllData();
        if (flags.contains("app_cache")) {
            File root = context.getCacheDir().getCanonicalFile();
            File[] children = root.listFiles();
            if (children == null) throw new IOException("无法读取应用缓存");
            for (File child : children) deleteCache(child, root);
        }
        int[] pending = {1};
        Runnable finished = () -> { if (--pending[0] == 0) complete.run(); };
        if (flags.contains("web_storage")) {
            for (WebView view : targets) {
                pending[0]++;
                view.evaluateJavascript("try{localStorage.clear();sessionStorage.clear()}catch(e){}", value -> finished.run());
            }
        }
        if (flags.contains("cookies")) {
            pending[0]++;
            CookieManager.getInstance().removeAllCookies(removed -> {
                CookieManager.getInstance().flush();
                finished.run();
            });
        }
        finished.run();
    }

    private static void deleteCache(File file, File root) throws IOException {
        if (!file.getCanonicalPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("缓存路径超出应用目录");
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("无法读取缓存目录");
            for (File child : children) deleteCache(child, root);
        }
        if (file.exists() && !file.delete()) throw new IOException("无法删除缓存：" + file.getName());
    }
}
