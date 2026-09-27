package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import com.example.cleanrecovery.ui.activity.AppStartup;
import com.example.cleanrecovery.ui.activity.MainActivity;
import com.example.cleanrecovery.scan.ScanHistoryStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ModuleNavigationUiTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    @Test public void pickerRemembersChoiceAndSwitchingPreservesRecoveryPage() throws Exception {
        Context c = instrumentation.getTargetContext();
        SharedPreferences startup = c.getSharedPreferences("app_startup", 0), history = c.getSharedPreferences("clean_recovery_scan_history", 0);
        Map<String, ?> saved = startup.getAll(), oldHistory = history.getAll();
        Activity[] initial = new Activity[1];
        try {
            startup.edit().clear().putBoolean("choice_made", true).putString("module", "home").commit();
            ScanHistoryStore.setOnboardingComplete(c);
            initial[0] = instrumentation.startActivitySync(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            main(() -> { initial[0].findViewById(com.example.cleanrecovery.R.id.home_panel).setTag("retained-page");
                startup.edit().putBoolean("choice_made", false).commit(); AppStartup.maybeShowFirstRunChoice(initial[0]); return null; });
            screenshot("startup-picker.png");
            tap("音乐", false);
            SystemClock.sleep(500);
            assertEquals("music", AppStartup.module(c)); assertTrue(AppStartup.choiceMade(c));
            assertTrue(main(() -> resumed() instanceof MusicHomeActivity));
            tap("切换功能", true);
            screenshot("module-switcher.png");
            tap("文件恢复", false);
            SystemClock.sleep(500);
            assertSame(initial[0], main(this::resumed));
            assertEquals("retained-page", main(() -> initial[0].findViewById(com.example.cleanrecovery.R.id.home_panel).getTag()));
            assertEquals("Switching must not change the default", "music", AppStartup.module(c));
            tap("切换功能", true);
            tap("整理常用功能", false);
            tap("收藏音乐", true);
            assertTrue(startup.getStringSet("favorites", java.util.Collections.emptySet()).contains("music"));
            tap("完成整理", false);
            tap("关闭", false);
            for (String destination : new String[]{"浏览器", "全网下载"}) {
                tap("切换功能", true); tap(destination, false); SystemClock.sleep(700);
                assertNotSame(initial[0], main(this::resumed));
                tap("切换功能", true); tap("文件恢复", false); SystemClock.sleep(400);
                assertSame(initial[0], main(this::resumed));
            }
        } finally {
            main(() -> { for (Activity a : new java.util.ArrayList<>(ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED))) a.finish();
                if (initial[0] != null) initial[0].finish(); restore(startup, saved); restore(history, oldHistory); return null; });
        }
    }
    private void tap(String value, boolean description) throws Exception {
        SystemClock.sleep(300);
        android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        android.view.accessibility.AccessibilityNodeInfo node = find(root, value, description);
        assertNotNull("Missing control: " + value, node);
        android.graphics.Rect r = new android.graphics.Rect(); node.getBoundsInScreen(r);
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, r.centerX(), r.centerY(), 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); instrumentation.getUiAutomation().injectInputEvent(event, true); event.recycle();
        }
        instrumentation.waitForIdleSync();
    }
    private android.view.accessibility.AccessibilityNodeInfo find(android.view.accessibility.AccessibilityNodeInfo node, String value, boolean description) {
        if (node == null) return null;
        if (value.contentEquals(description ? node.getContentDescription() == null ? "" : node.getContentDescription() : node.getText() == null ? "" : node.getText())) return node;
        for (int i = 0; i < node.getChildCount(); i++) { android.view.accessibility.AccessibilityNodeInfo result = find(node.getChild(i), value, description); if (result != null) return result; }
        return null;
    }
    private Activity resumed() { return ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).iterator().next(); }
    private void restore(SharedPreferences p, Map<String, ?> values) {
        SharedPreferences.Editor e = p.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) { Object v = entry.getValue(); String k = entry.getKey();
            if (v instanceof String) e.putString(k, (String) v); else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v); else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v); else if (v instanceof java.util.Set) e.putStringSet(k, (java.util.Set<String>) v);
        } e.commit();
    }
    private void screenshot(String name) throws Exception {
        SystemClock.sleep(500); java.io.File folder = new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null), "navigation-test"); folder.mkdirs();
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(folder, name))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } bitmap.recycle();
    }
    private <T> T main(Callable<T> call) throws Exception { FutureTask<T> task = new FutureTask<>(call); instrumentation.runOnMainSync(task); return task.get(); }
}
