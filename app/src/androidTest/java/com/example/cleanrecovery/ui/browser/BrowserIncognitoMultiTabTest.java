package com.example.cleanrecovery.ui.browser;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.activity.BrowserActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserIncognitoMultiTabTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void menuPrivacyUpdatesBackgroundTabsAndRetainsSiteExceptions() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String, Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        BrowserDatabaseHelper database = BrowserDatabaseHelper.getInstance(instrumentation.getTargetContext());
        BrowserActivity activity = null;
        List<String> fixtureUrls = new ArrayList<>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        String fixture = "__incognito_multitab_" + UUID.randomUUID();
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            Thread serving = new Thread(() -> {
                while (!server.isClosed()) try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = reader.readLine(), line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    if (first == null) continue;
                    byte[] body = ("<!doctype html><html><head><title>" + fixture
                            + "</title></head><body>Privacy tab fixture</body></html>").getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                            + "Cache-Control: no-store\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (Throwable error) {
                    if (!server.isClosed()) serverFailure.compareAndSet(null, error);
                }
            }, "incognito-multitab-fixture");
            serving.start();
            try {
                prefs.setRestoreTabs(0);
                prefs.setExitClearFlags(Collections.emptySet());
                prefs.setClearDataOnExit(false);
                prefs.setScriptsEnabled(false);
                prefs.setAdBlockEnabled(false);
                prefs.setIncognitoMode(false);
                prefs.setSiteSettingsEnabled("localhost:" + server.getLocalPort(), false);
                prefs.setSiteSettingsEnabled("127.0.0.1:" + server.getLocalPort(), false);
                prefs.setSiteCookiesOff("localhost:" + server.getLocalPort(), false);
                prefs.setSiteCookiesOff("127.0.0.1:" + server.getLocalPort(), false);

                String firstUrl = url(server, "localhost", fixture, "initial-a", fixtureUrls);
                activity = (BrowserActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(firstUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
                BrowserActivity browser = activity;
                TabManager.Tab first = main(() -> tabs(browser).current());
                awaitPage(browser, first, firstUrl, fixture);
                String secondUrl = url(server, "127.0.0.1", fixture, "initial-b", fixtureUrls);
                TabManager.Tab second = newTab(browser, secondUrl);
                awaitPage(browser, second, secondUrl, fixture);
                assertPrivacy(first, false);
                assertPrivacy(second, false);
                assertTrue(historyCount(database, firstUrl) > 0);
                assertTrue(historyCount(database, secondUrl) > 0);

                toggle(browser);
                assertTrue("The menu action must update the stored global baseline", freshPrefs().incognitoMode());
                assertPrivacy(first, true);
                assertPrivacy(second, true);
                assertSame("A global toggle must update background tabs without changing the selected tab", second,
                        main(() -> tabs(browser).current()));
                String globalUrl = url(server, "127.0.0.1", fixture, "global-private", fixtureUrls);
                main(() -> { second.webView.loadUrl(globalUrl); return null; });
                awaitPage(browser, second, globalUrl, fixture);
                assertEquals("New visits in a globally private tab must not be recorded", 0, historyCount(database, globalUrl));

                prefs.setSiteSettingsEnabled("localhost:" + server.getLocalPort(), true);
                prefs.setSiteIncognitoMode("localhost:" + server.getLocalPort(), 0);
                returnedSettings(browser);
                assertPrivacy(first, false);
                assertPrivacy(second, true);
                assertTrue("Changing a background site's override must preserve global privacy", freshPrefs().incognitoMode());
                showTab(browser, first);
                String exceptionUrl = url(server, "localhost", fixture, "site-off", fixtureUrls);
                main(() -> { first.webView.loadUrl(exceptionUrl); return null; });
                awaitPage(browser, first, exceptionUrl, fixture);
                assertTrue("Explicit site OFF must record real history even while the other tab remains private",
                        historyCount(database, exceptionUrl) > 0);

                prefs.setSiteSettingsEnabled("127.0.0.1:" + server.getLocalPort(), true);
                prefs.setSiteIncognitoMode("127.0.0.1:" + server.getLocalPort(), 1);
                returnedSettings(browser);
                toggle(browser);
                assertTrue("Opening the exit choice must not change privacy before the user's decision", freshPrefs().incognitoMode());
                instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
                instrumentation.waitForIdleSync();
                assertTrue("Dismissing the choice must keep global privacy enabled", freshPrefs().incognitoMode());
                assertEquals(2, (int) main(() -> tabs(browser).size()));
                toggle(browser);
                chooseExit("保留");
                assertFalse("A fresh preferences instance must read the menu's saved OFF state", freshPrefs().incognitoMode());
                assertPrivacy(first, false);
                assertPrivacy(second, true);
                assertSame("The inactive site's explicit ON must survive the global OFF action", first,
                        main(() -> tabs(browser).current()));
                String inheritedUrl = url(server, "127.0.0.1", fixture, "new-site-private-tab", fixtureUrls);
                TabManager.Tab inherited = newTab(browser, inheritedUrl);
                awaitPage(browser, inherited, inheritedUrl, fixture);
                assertPrivacy(inherited, true);
                assertEquals("A newly opened tab must inherit site ON without briefly recording the first visit",
                        0, historyCount(database, inheritedUrl));
                assertTrue("Switching modes must retain pre-existing normal visits", historyCount(database, firstUrl) > 0);
                assertTrue(historyCount(database, secondUrl) > 0);
                assertEquals(0, historyCount(database, globalUrl));

                toggle(browser);
                assertTrue(freshPrefs().incognitoMode());
                int tabCount = main(() -> tabs(browser).size());
                assertTrue(tabCount > 1);
                toggle(browser);
                assertTrue("Opening the close choice must preserve global ON until confirmed", freshPrefs().incognitoMode());
                assertEquals(tabCount, (int) main(() -> tabs(browser).size()));
                chooseExit("关闭");
                assertFalse(freshPrefs().incognitoMode());
                main(() -> {
                    assertEquals("Closing on private-mode exit must replace all fixture tabs with one home tab", 1, tabs(browser).size());
                    TabManager.Tab home = tabs(browser).current();
                    assertNotSame(first, home);
                    assertNotSame(second, home);
                    assertNotSame(inherited, home);
                    assertTrue(home.url == null || home.url.isEmpty());
                    return null;
                });
                assertTrue("Closing private tabs must retain previously recorded ordinary history", historyCount(database, firstUrl) > 0);
                toggle(browser);
                toggle(browser);
                assertTrue("A just-closed ordinary site remains available for restoration on the home page", freshPrefs().incognitoMode());
                chooseExit("保留");
                TabManager.Tab oldHome = main(() -> tabs(browser).current());
                String privateSeedUrl = url(server, "127.0.0.1", fixture, "clear-pending-close", fixtureUrls);
                TabManager.Tab privateSeed = newTab(browser, privateSeedUrl);
                awaitPage(browser, privateSeed, privateSeedUrl, fixture);
                newTab(browser, "");
                main(() -> {
                    invoke(browser, "closeAndDestroyTab", new Class<?>[]{int.class}, tabs(browser).indexOf(privateSeed));
                    invoke(browser, "closeAndDestroyTab", new Class<?>[]{int.class}, tabs(browser).indexOf(oldHome));
                    assertEquals(1, tabs(browser).size());
                    return null;
                });
                assertFalse("Old closed-tab records must remain stored after navigating", prefs.closedTabs().isEmpty());
                toggle(browser);
                assertTrue(freshPrefs().incognitoMode());
                toggle(browser);
                assertFalse("Old stored close records must not force a choice on a fresh home after new navigation",
                        freshPrefs().incognitoMode());
                TabManager.Tab covered = main(() -> tabs(browser).current());
                String coveredUrl = url(server, "127.0.0.1", fixture, "covered-by-home", fixtureUrls);
                main(() -> invoke(browser, "loadUrlInCurrent", new Class<?>[]{String.class}, coveredUrl));
                awaitPage(browser, covered, coveredUrl, fixture);
                main(() -> {
                    assertFalse(covered.webView.canGoBack());
                    assertFalse(covered.webView.canGoForward());
                    invoke(browser, "showHome", new Class<?>[0]);
                    assertTrue(covered.url.isEmpty());
                    return null;
                });
                toggle(browser);
                toggle(browser);
                assertTrue("A page covered by the native home still requires the exit choice", freshPrefs().incognitoMode());
                chooseExit("保留");
                assertNull(serverFailure.get());
            } finally {
                server.close();
                serving.join(3000);
                if (activity != null) {
                    BrowserActivity browser = activity;
                    main(() -> { browser.finish(); return null; });
                    long end = System.currentTimeMillis() + 5000;
                    while (!main(browser::isDestroyed) && System.currentTimeMillis() < end) Thread.sleep(25);
                    assertTrue("Destroy must finish before restoring session preferences", main(browser::isDestroyed));
                    instrumentation.waitForIdleSync();
                }
                assertFalse("Local HTTP fixture must stop", serving.isAlive());
            }
        } finally {
            for (String fixtureUrl : fixtureUrls) database.getWritableDatabase().delete(
                    BrowserDatabaseHelper.TABLE_HISTORY, "url=?", new String[]{fixtureUrl});
            restore(storage, original);
            assertEquals("All browser and session preferences must be restored, including originally absent keys",
                    original, snapshot(storage));
        }
    }

    private BrowserPrefs freshPrefs() {
        return new BrowserPrefs(instrumentation.getTargetContext());
    }

    private String url(ServerSocket server, String host, String fixture, String page, List<String> urls) {
        String url = "http://" + host + ":" + server.getLocalPort() + "/" + fixture + "/" + page;
        urls.add(url);
        return url;
    }

    private TabManager.Tab newTab(BrowserActivity browser, String url) throws Exception {
        return main(() -> (TabManager.Tab) invoke(browser, "newTab", new Class<?>[]{String.class}, url));
    }

    private void showTab(BrowserActivity browser, TabManager.Tab tab) throws Exception {
        main(() -> {
            TabManager manager = tabs(browser);
            manager.select(manager.indexOf(tab));
            invoke(browser, "showTab", new Class<?>[]{TabManager.Tab.class}, tab);
            return null;
        });
    }

    private void toggle(BrowserActivity browser) throws Exception {
        main(() -> invoke(browser, "toggleIncognito", new Class<?>[0]));
    }

    private void returnedSettings(BrowserActivity browser) throws Exception {
        main(() -> invoke(browser, "applyReturnedSettings", new Class<?>[]{Intent.class}, new Intent()));
    }

    private void chooseExit(String text) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null && !root.findAccessibilityNodeInfosByText("关闭所有标签").isEmpty()) {
                for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                    if (!text.contentEquals(node.getText())) continue;
                    while (node != null && !node.isClickable()) node = node.getParent();
                    assertNotNull("The exit choice must be clickable", node);
                    assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                    instrumentation.waitForIdleSync();
                    return;
                }
            }
            Thread.sleep(50);
        }
        fail("The actual private-mode exit dialog did not offer " + text);
    }

    private Object invoke(BrowserActivity browser, String name, Class<?>[] types, Object... args) throws Exception {
        Method method = BrowserActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(browser, args);
    }

    private void awaitPage(BrowserActivity browser, TabManager.Tab tab, String url, String title) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            if (main(() -> url.equals(tab.url) && url.equals(tab.webView.getUrl())
                    && title.equals(tab.webView.getTitle()) && tab.webView.getProgress() == 100
                    && browser.findViewById(R.id.browser_progress).getVisibility() == View.GONE)) {
                instrumentation.waitForIdleSync();
                return;
            }
            Thread.sleep(25);
        }
        fail("The real BrowserActivity page-finished consumer did not finish " + url);
    }

    private void assertPrivacy(TabManager.Tab tab, boolean expected) throws Exception {
        main(() -> {
            Field field = tab.tag.getClass().getDeclaredField("incognito");
            field.setAccessible(true);
            assertEquals("Every live tab must use its own site's override over the shared global baseline",
                    expected, field.getBoolean(tab.tag));
            return null;
        });
    }

    private int historyCount(BrowserDatabaseHelper database, String url) {
        try (Cursor cursor = database.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM history WHERE url=?", new String[]{url})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    private TabManager tabs(BrowserActivity browser) throws Exception {
        Field field = BrowserActivity.class.getDeclaredField("tabs");
        field.setAccessible(true);
        return (TabManager) field.get(browser);
    }

    private <T> T main(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        instrumentation.runOnMainSync(future);
        return future.get();
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
