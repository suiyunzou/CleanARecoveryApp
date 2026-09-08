package com.example.cleanrecovery.ui.browser;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.ui.activity.BrowserSiteSettingsActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

/** Site UA identity matters to redirect inheritance even when two UA strings are equal. */
@RunWith(AndroidJUnit4.class)
public class BrowserSiteUaTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final String host = "site-ua-" + UUID.randomUUID() + ".invalid";
    private static final String NATIVE_UA = "NativeWebView/1.0";
    private SharedPreferences storage;
    private Map<String, Object> original;
    private BrowserPrefs prefs;
    private BrowserSiteSettingsActivity activity;

    @Before public void prepare() {
        storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        original = snapshot();
        prefs = new BrowserPrefs(instrumentation.getTargetContext());
        prefs.setSimpleUa(false);
        prefs.setUaSelectedId(BrowserPrefs.UA_ID_DEFAULT);
        prefs.setCustomUserAgent("Global/1.0");
        prefs.setSiteSettingsEnabled(host, true);
    }

    @After public void restore() {
        if (activity != null) {
            instrumentation.runOnMainSync(() -> activity.finish());
            long deadline = SystemClock.uptimeMillis() + 5000;
            while (!activity.isDestroyed() && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync();
                SystemClock.sleep(25);
            }
        }
        SharedPreferences.Editor editor = storage.edit().clear();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
            else throw new AssertionError("Unsupported preference type: " + key);
        }
        assertTrue("Restore all original preferences synchronously", editor.commit());
        assertEquals(original, snapshot());
        if (activity != null) assertTrue("Settings must finish before preference restoration", activity.isDestroyed());
    }

    @Test public void siteIdsPreserveRawIdentityAndSeparateNativeFromGlobalAndDesktop() {
        assertEquals(BrowserPrefs.SITE_UA_ID_INHERIT, prefs.siteUaSelectedId(host));
        assertEquals("Global/1.0", effective(false));
        String androidUa = BrowserPrefs.getPresetUaString(BrowserPrefs.UA_ID_ANDROID_PHONE);
        prefs.setSiteUserAgent(host, androidUa);
        assertEquals("A raw string identical to Android must not acquire Android's redirect exception",
                BrowserPrefs.SITE_UA_ID_CUSTOM, prefs.siteUaSelectedId(host));
        assertEquals(androidUa, effective(false));
        prefs.setSiteUaSelectedId(host, BrowserPrefs.UA_ID_ANDROID_PHONE);
        assertEquals(BrowserPrefs.UA_ID_ANDROID_PHONE, prefs.siteUaSelectedId(host));
        assertEquals(androidUa, effective(false));
        prefs.setSiteUaSelectedId(host, BrowserPrefs.UA_ID_DEFAULT);
        assertEquals("Explicit site default means the native UA, not the legacy global raw UA", NATIVE_UA, effective(false));
        long customId = prefs.addCustomUa("Site UA fixture " + host, "Library/2.0");
        prefs.setSiteUaSelectedId(host, customId);
        assertEquals("Library/2.0", effective(false));
        prefs.setDesktopUaSelectedId(BrowserPrefs.UA_ID_WINDOWS_CHROME);
        assertEquals("Desktop selection takes precedence over a site's selected UA",
                BrowserPrefs.getPresetUaString(BrowserPrefs.UA_ID_WINDOWS_CHROME), effective(true));
        prefs.setSiteUaSelectedId(host, BrowserPrefs.SITE_UA_ID_INHERIT);
        assertEquals("Switching away from raw must preserve the value for later editing", androidUa, prefs.siteUserAgent(host));
        assertEquals("Global/1.0", effective(false));
        prefs.setSiteUaSelectedId(host, BrowserPrefs.UA_ID_IPAD);
        prefs.setSiteSettingsEnabled(host, false);
        assertEquals("Disabling the site master switch restores the global selection", "Global/1.0", effective(false));
        prefs.resetSiteSettings(host);
        assertEquals(BrowserPrefs.SITE_UA_ID_INHERIT, prefs.siteUaSelectedId(host));
        assertEquals("", prefs.siteUserAgent(host));
    }

    @Test public void realSiteSelectorPersistsPresetNativeAndInheritedIds() {
        prefs.setSiteUserAgent(host, "ExistingRaw/3.0");
        activity = (BrowserSiteSettingsActivity) instrumentation.startActivitySync(
                new Intent(instrumentation.getTargetContext(), BrowserSiteSettingsActivity.class)
                        .putExtra(BrowserSiteSettingsActivity.EXTRA_HOST, host)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        clickText("浏览器标识");
        clickText("Android (手机)");
        assertEquals("The actual selector must save the preset ID rather than a guessed UA string",
                BrowserPrefs.UA_ID_ANDROID_PHONE, prefs.siteUaSelectedId(host));
        assertEquals(BrowserPrefs.getPresetUaString(BrowserPrefs.UA_ID_ANDROID_PHONE), effective(false));
        clickText("浏览器标识");
        clickText("默认");
        assertEquals(BrowserPrefs.UA_ID_DEFAULT, prefs.siteUaSelectedId(host));
        assertEquals(NATIVE_UA, effective(false));
        clickText("浏览器标识");
        clickText("默认（默认）");
        assertEquals(BrowserPrefs.SITE_UA_ID_INHERIT, prefs.siteUaSelectedId(host));
        assertEquals("Global/1.0", effective(false));
        assertEquals("Choosing other options must not erase the site's existing raw value",
                "ExistingRaw/3.0", prefs.siteUserAgent(host));
    }

    private String effective(boolean desktop) {
        return prefs.getEffectiveUserAgent(desktop, host, NATIVE_UA);
    }

    private void clickText(String text) {
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            instrumentation.waitForIdleSync();
            AccessibilityNodeInfo node = findText(instrumentation.getUiAutomation().getRootInActiveWindow(), text);
            while (node != null && !node.isClickable()) node = node.getParent();
            if (node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                instrumentation.waitForIdleSync();
                return;
            }
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("Could not click the real settings control: " + text);
    }

    private AccessibilityNodeInfo findText(AccessibilityNodeInfo node, String text) {
        if (node == null) return null;
        if (text.contentEquals(node.getText() == null ? "" : node.getText())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findText(node.getChild(i), text);
            if (result != null) return result;
        }
        return null;
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : storage.getAll().entrySet()) {
            Object value = entry.getValue();
            result.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value);
        }
        return result;
    }
}
