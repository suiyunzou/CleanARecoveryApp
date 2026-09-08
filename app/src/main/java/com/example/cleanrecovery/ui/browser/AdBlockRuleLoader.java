package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 规则装载器：内置 simple.txt + 自定义规则(prefs) + 订阅文件 → 共享 {@link AdFilterEngine} 全量重建。
 *
 * <p>装载顺序：先从本地（asset/缓存文件）快速建索引进即用态；
 * 随后按需联网更新过期订阅，有变更再重建一次。任何规则变更
 * （订阅增删/开关/手动标记广告/导入规则/内置开关）都应调用 {@link #reloadAsync}。</p>
 */
public final class AdBlockRuleLoader {

    private static final String TAG = "AdBlockRuleLoader";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean queued = new AtomicBoolean();

    private AdBlockRuleLoader() { }

    /** 异步重建索引（可重入去重：排队中的重复请求合并）。 */
    public static void reloadAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!queued.compareAndSet(false, true)) return;
        EXECUTOR.execute(() -> {
            queued.set(false);
            try {
                reload(app);
            } catch (Throwable t) {
                Log.w(TAG, "reload failed: " + t);
            }
        });
    }

    private static void reload(Context app) {
        BrowserPrefs prefs = new BrowserPrefs(app);
        // 1) 本地快速装载（无网络 IO）
        install(prefs, app);
        // 2) 按需联网更新过期订阅（距上次检查 >1h 才检查）
        long interval = prefs.adBlockSubUpdateInterval();
        long now = System.currentTimeMillis();
        if (interval >= 3600_000L && now - prefs.adBlockSubLastCheck() > 3600_000L) {
            prefs.setAdBlockSubLastCheck(now);
            boolean changed = AdSubscriptionManager.updateOutdated(app, interval);
            if (changed) install(prefs, app);
        }
    }

    private static void install(BrowserPrefs prefs, Context app) {
        AdFilterEngine.Builder builder = new AdFilterEngine.Builder();
        if (prefs.adBlockBuiltin()) {
            loadAsset(app, builder);
        }
        for (String h : prefs.blockedHosts()) {
            builder.addLine("||" + h + "^");
        }
        for (String r : prefs.blockedUrlRules()) {
            builder.addLine(r);
        }
        for (String entry : prefs.cosmeticRuleEntries()) {
            int split = entry.indexOf('\t');
            if (split > 0 && split < entry.length() - 1) {
                builder.addLine(entry.substring(0, split) + "##" + entry.substring(split + 1));
            }
        }
        for (AdSubscriptionManager.Subscription s : AdSubscriptionManager.load(app)) {
            if (!s.enabled) continue;
            List<String> lines = AdSubscriptionManager.readRuleLines(app, s.url);
            for (String line : lines) builder.addLine(line);
        }
        BrowserAdBlocker.installRules(builder);
    }

    private static void loadAsset(Context app, AdFilterEngine.Builder builder) {
        try {
            java.io.InputStream in = app.getAssets().open("adblock_simple.txt");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) builder.addLine(line);
            reader.close();
        } catch (Exception e) {
            Log.w(TAG, "load builtin rules failed: " + e);
        }
    }
}
