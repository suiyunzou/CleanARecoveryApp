package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.ui.activity.BrowserSiteSettingsActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserSiteUaScrollTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void longSiteUaLibraryCanScrollToAndSelectItsLastEntry() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String, Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        Activity activity = null;
        String fixture = "ua-scroll-" + UUID.randomUUID();
        String host = fixture + ".test";
        String lastName = fixture + "-15";
        String lastUa = "FixtureBrowser/15 " + fixture;
        try {
            long lastId = -1;
            for (int i = 1; i <= 15; i++) {
                lastId = prefs.addCustomUa(fixture + "-" + i, "FixtureBrowser/" + i + " " + fixture);
            }
            prefs.setSiteSettingsEnabled(host, true);
            prefs.setSiteUaSelectedId(host, BrowserPrefs.SITE_UA_ID_INHERIT);
            activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserSiteSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                    .putExtra(BrowserSiteSettingsActivity.EXTRA_HOST, host));
            Activity site = activity;
            main(() -> {
                View row = find(site.findViewById(android.R.id.content), "浏览器标识");
                assertNotNull("The real site-settings UA entry must exist", row);
                while (!row.isClickable()) row = (View) row.getParent();
                assertTrue(row.performClick());
                return null;
            });
            Rect titleBefore = settledTitleBounds();
            assertNull("The fixture must place the final library item below the initial viewport", visibleText(lastName));
            int scrollCount = 0;
            int maxScrolls = prefs.customUaList().size() + BrowserPrefs.PRESET_UA_IDS.length + 3;
            while (scrollCount < maxScrolls) {
                AccessibilityNodeInfo scroll = scrollNode(instrumentation.getUiAutomation().getRootInActiveWindow());
                assertNotNull("Long radio choices must expose a scrollable accessibility container", scroll);
                if ((scroll.getActions() & AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == 0) break;
                instrumentation.getUiAutomation().executeAndWaitForEvent(
                        () -> assertTrue("The options must accept a real accessibility scroll", scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)),
                        event -> event.getEventType() == AccessibilityEvent.TYPE_VIEW_SCROLLED, 3000);
                instrumentation.waitForIdleSync();
                scrollCount++;
            }
            assertTrue("Reaching the final option must require actual scrolling", scrollCount > 0);
            assertTrue("The options must reach a finite end", scrollCount < maxScrolls);
            assertEquals("The dialog title must stay fixed while only its choices scroll", titleBefore, settledTitleBounds());
            AccessibilityNodeInfo choice = awaitVisibleText(lastName);
            while (choice != null && !choice.isClickable()) choice = choice.getParent();
            assertNotNull("The final UA label must belong to a clickable option", choice);
            assertTrue(choice.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            instrumentation.waitForIdleSync();
            assertEquals("The final library option must save its exact persistent ID", lastId, prefs.siteUaSelectedId(host));
            assertEquals("Site UA resolution must use the selected library entry", lastUa,
                    prefs.getEffectiveUserAgent(false, host, "NativeFallback/1.0"));
        } finally {
            try {
                if (activity != null) {
                    Activity site = activity;
                    main(() -> { site.finish(); return null; });
                    long end = System.currentTimeMillis() + 5000;
                    while (!main(site::isDestroyed) && System.currentTimeMillis() < end) Thread.sleep(25);
                    assertTrue("Destroy the settings activity before restoring preferences", main(site::isDestroyed));
                    instrumentation.waitForIdleSync();
                }
            } finally {
                restore(storage, original);
                assertEquals("The test must restore all settings, including the user's UA library", original, snapshot(storage));
            }
        }
    }

    private Rect settledTitleBounds() throws Exception {
        Rect previous = new Rect();
        int stable = 0;
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo title = visibleText("浏览器标识");
            if (title != null) {
                Rect bounds = new Rect(); title.getBoundsInScreen(bounds);
                stable = bounds.equals(previous) ? stable + 1 : 1;
                previous.set(bounds);
                if (stable >= 3) return bounds;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("The UA dialog title never settled");
    }

    private AccessibilityNodeInfo awaitVisibleText(String text) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo node = visibleText(text);
            if (node != null) return node;
            Thread.sleep(50);
        }
        throw new AssertionError("Visible option missing after scrolling: " + text);
    }

    private AccessibilityNodeInfo visibleText(String text) {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root != null) for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
            if (node.isVisibleToUser() && text.contentEquals(node.getText())) return node;
        }
        return null;
    }

    private AccessibilityNodeInfo scrollNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable() && "android.widget.ScrollView".contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = scrollNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        instrumentation.runOnMainSync(task);
        return task.get();
    }

    private Map<String, Object> snapshot(SharedPreferences prefs) {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            Object value = entry.getValue();
            result.put(entry.getKey(), value instanceof Set ? new HashSet<>((Set<?>) value) : value);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void restore(SharedPreferences prefs, Map<String, Object> values) {
        SharedPreferences.Editor editor = prefs.edit().clear();
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Set) editor.putStringSet(key, new HashSet<>((Set<String>) value));
            else throw new AssertionError("Unexpected preference type for " + key);
        }
        assertTrue("Preference restoration must reach disk", editor.commit());
    }
}
