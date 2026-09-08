package com.example.cleanrecovery.ui.browser;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.BrowserSettingsActivity;
import com.example.cleanrecovery.ui.activity.BrowserSiteSettingsActivity;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Exercises the native origin-permission cache, independently of GPS position delivery. */
@RunWith(AndroidJUnit4.class)
public class BrowserGeolocationSettingsCacheTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private SharedPreferences storage;
    private Map<String, Object> originalPrefs;
    private BrowserGeolocationTestState geolocation;
    private BrowserPrefs prefs;
    private Activity activity;
    private String host, origin, secondOrigin;

    @Before public void setup() throws Exception {
        storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        originalPrefs = snapshotPrefs();
        assertEquals("The coordinated device run must grant location without revoking it inside this process",
                PackageManager.PERMISSION_GRANTED, instrumentation.getTargetContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION));
        assertEquals(PackageManager.PERMISSION_GRANTED,
                instrumentation.getTargetContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION));
        geolocation = BrowserGeolocationTestState.capture(instrumentation);
        host = "geo-cache-" + UUID.randomUUID() + ".test";
        origin = "https://" + host + "/";
        prefs = new BrowserPrefs(instrumentation.getTargetContext());
        prefs.setPermission("location", "ask");
        prefs.setSiteSettingsEnabled(host, true);
    }

    @After public void cleanup() throws Exception {
        try {
            if (activity != null) {
                main(() -> { activity.finish(); return null; });
                await(() -> main(activity::isDestroyed));
            }
        } finally {
            try {
                if (geolocation != null) geolocation.restore(origin, secondOrigin);
            } finally {
                if (originalPrefs != null) {
                    restorePrefs();
                    assertEquals("Restore all user settings after the settings activity is destroyed", originalPrefs, snapshotPrefs());
                }
            }
        }
    }

    @Test public void siteLocationModeChangesInvalidateRetainedAllowances() throws Exception {
        startSite();
        String[] labels = {"禁止", "允许", "询问", "跟随全局（默认）"};
        int[] modes = {2, 1, 3, -1};
        for (int i = 0; i < labels.length; i++) {
            geolocation.retainAllowance(origin);
            clickRow("位置信息");
            clickDialog(labels[i]);
            assertEquals(modes[i], prefs.sitePermissionMode("location", host));
            await(() -> !geolocation.snapshotOrigins().containsKey(origin));
        }
    }

    @Test public void siteResetInvalidatesRetainedRefusal() throws Exception {
        startSite();
        prefs.setSitePermissionMode("location", host, 2);
        geolocation.retainRefusal(origin);
        assertEquals(Boolean.FALSE, geolocation.snapshotOrigins().get(origin));
        clickRow("重置");
        assertFalse(prefs.siteSettingsEnabled(host));
        assertEquals(-1, prefs.sitePermissionMode("location", host));
        await(() -> !geolocation.snapshotOrigins().containsKey(origin));
    }

    @Test public void globalLocationPageClearsCacheWithoutChangingPermission() throws Exception {
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        clickRow("通用");
        clickRow("网站设定");
        geolocation.retainAllowance(origin);
        clickRow("摄像头");
        assertEquals("Opening a different permission must not clear location decisions", Boolean.TRUE, geolocation.snapshotOrigins().get(origin));
        main(() -> { activity.onBackPressed(); return null; });
        clickRow("位置信息");
        await(() -> !geolocation.snapshotOrigins().containsKey(origin));
        assertEquals("Entering Via's location page clears native cache without changing the global choice", "ask", prefs.permission("location"));
    }

    @Test public void testIsolationRestoresBothPreexistingAllowAndRefusalAfterSettingsReset() throws Exception {
        startSite(); secondOrigin = "https://other." + host + "/";
        geolocation.retainRefusal(origin); geolocation.retainAllowance(secondOrigin);
        BrowserGeolocationTestState saved = BrowserGeolocationTestState.capture(instrumentation);
        clickRow("重置"); await(() -> geolocation.snapshotOrigins().isEmpty());
        saved.restore();
        assertEquals("The helper must restore a real retained refusal, not just allowed origins", Boolean.FALSE, geolocation.snapshotOrigins().get(origin));
        assertEquals(Boolean.TRUE, geolocation.snapshotOrigins().get(secondOrigin));
    }

    private void startSite() {
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserSiteSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(BrowserSiteSettingsActivity.EXTRA_HOST, host));
    }

    private void clickRow(String text) throws Exception {
        main(() -> {
            View row = find(activity.findViewById(android.R.id.content), text);
            assertNotNull("Missing real settings row: " + text, row);
            while (!row.isClickable() && row.getParent() instanceof View) row = (View) row.getParent();
            assertTrue(row.performClick()); return null;
        });
        instrumentation.waitForIdleSync();
    }

    private void clickDialog(String text) throws Exception {
        await(() -> {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) for (AccessibilityNodeInfo row : root.findAccessibilityNodeInfosByText(text)) {
                if (!row.isVisibleToUser() || !text.contentEquals(row.getText())) continue;
                while (!row.isClickable() && row.getParent() != null) row = row.getParent();
                assertTrue(row.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                instrumentation.waitForIdleSync(); return true;
            }
            return false;
        });
    }

    private View find(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            View found = find(((ViewGroup) view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }

    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> result = new FutureTask<>(action); instrumentation.runOnMainSync(result); return result.get();
    }

    private void await(Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) { if (condition.call()) return; Thread.sleep(25); }
        fail("The expected native cache or activity state did not arrive");
    }

    private Map<String, Object> snapshotPrefs() {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : storage.getAll().entrySet())
            result.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>) entry.getValue()) : entry.getValue());
        return result;
    }

    @SuppressWarnings("unchecked") private void restorePrefs() {
        SharedPreferences.Editor edit = storage.edit().clear();
        for (Map.Entry<String, Object> entry : originalPrefs.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) edit.putString(key, (String) value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) edit.putInt(key, (Integer) value);
            else if (value instanceof Long) edit.putLong(key, (Long) value);
            else if (value instanceof Float) edit.putFloat(key, (Float) value);
            else if (value instanceof Set) edit.putStringSet(key, new HashSet<>((Set<String>) value));
            else throw new AssertionError("Unexpected preference type: " + key);
        }
        assertTrue(edit.commit());
    }
}
