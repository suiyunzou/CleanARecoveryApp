package com.example.cleanrecovery.ui.browser;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.view.View;
import android.webkit.WebSettings;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserIncognitoNavigationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void navigationAndReturnedSettingsRecomputePrivacyWithoutLosingNormalHistory() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        // Restore absent keys as absent too, including session values written by onStop.
        Map<String, Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        BrowserDatabaseHelper database = BrowserDatabaseHelper.getInstance(instrumentation.getTargetContext());
        BrowserActivity activity = null;
        List<String> fixtureUrls = new ArrayList<>();
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        String fixture = "__incognito_navigation_" + UUID.randomUUID();
        try (ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))) {
            Thread serving = new Thread(() -> {
                while (!server.isClosed()) try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = reader.readLine(), line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                    if (first == null) continue;
                    byte[] body = ("<!doctype html><html><head><title>" + fixture
                            + "</title></head><body>Privacy navigation fixture</body></html>").getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                            + "Cache-Control: no-store\r\nContent-Length: " + body.length
                            + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body);
                    socket.getOutputStream().flush();
                } catch (Throwable error) {
                    if (!server.isClosed()) serverFailure.compareAndSet(null, error);
                }
            }, "incognito-navigation-fixture");
            serving.start();
            try {
                prefs.setRestoreTabs(0);
                prefs.setExitClearFlags(Collections.emptySet());
                prefs.setClearDataOnExit(false);
                for (String name : prefs.scriptNames()) prefs.setScriptEnabled(name, false);
                prefs.setScriptsEnabled(true);
                prefs.setJsEnabled(true);
                prefs.setSiteJsMode("localhost:" + server.getLocalPort(), 1);
                prefs.setSiteJsMode("127.0.0.1:" + server.getLocalPort(), 1);
                prefs.saveScript(fixture, "http://localhost:" + server.getLocalPort() + "/" + fixture + "/*\n"
                        + "http://127.0.0.1:" + server.getLocalPort() + "/" + fixture + "/*",
                        "// ==UserScript==\n// @run-at document-start\n// @grant none\n// ==/UserScript==\n"
                                + "window.incognitoScriptCount=(window.incognitoScriptCount||0)+1;");
                prefs.setAdBlockEnabled(false);
                prefs.setIncognitoMode(false);
                prefs.setSiteSettingsEnabled("localhost:" + server.getLocalPort(), true);
                prefs.setSiteIncognitoMode("localhost:" + server.getLocalPort(), 1);
                prefs.setSiteCookiesOff("localhost:" + server.getLocalPort(), false);
                prefs.setSiteSettingsEnabled("127.0.0.1:" + server.getLocalPort(), false);

                String privateUrl = url(server, "localhost", fixture, "site-private", fixtureUrls);
                activity = (BrowserActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(privateUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
                BrowserActivity browser = activity;
                TabManager.Tab tab = main(() -> tabs(browser).current());
                awaitPage(browser, tab, privateUrl, fixture);
                assertPrivacy(tab, true);
                assertEquals("The site override must suppress visits from this private page", 0, historyCount(database, privateUrl));

                String resourceTarget = url(server, "127.0.0.1", fixture, "normal-after-resource", fixtureUrls);
                resourcePagePrivacyEndsAtItsOrdinaryDestination(browser, tab, prefs, database, resourceTarget, fixture);

                String normalUrl = url(server, "127.0.0.1", fixture, "normal-after-private", fixtureUrls);
                main(() -> { tab.webView.loadUrl(normalUrl); return null; });
                awaitPage(browser, tab, normalUrl, fixture);
                assertPrivacy(tab, false);
                assertTrue("Leaving a private site must restore real history recording in the same tab", historyCount(database, normalUrl) > 0);
                assertEquals(0, historyCount(database, privateUrl));

                prefs.setIncognitoMode(true);
                returnedSettings(browser);
                assertPrivacy(tab, true);
                String globalPrivateUrl = url(server, "127.0.0.1", fixture, "global-private", fixtureUrls);
                main(() -> { tab.webView.loadUrl(globalPrivateUrl); return null; });
                awaitPage(browser, tab, globalPrivateUrl, fixture);
                assertEquals("Default site mode must follow global privacy", 0, historyCount(database, globalPrivateUrl));

                prefs.setSiteSettingsEnabled("127.0.0.1:" + server.getLocalPort(), true);
                prefs.setSiteCookiesOff("127.0.0.1:" + server.getLocalPort(), false);
                prefs.setSiteIncognitoMode("127.0.0.1:" + server.getLocalPort(), 0);
                returnedSettings(browser);
                assertPrivacy(tab, false);
                assertTrue("The site exception must not disable the user's global setting", prefs.incognitoMode());
                String exceptionUrl = url(server, "127.0.0.1", fixture, "site-exception", fixtureUrls);
                main(() -> { tab.webView.loadUrl(exceptionUrl); return null; });
                awaitPage(browser, tab, exceptionUrl, fixture);
                assertTrue("Explicit site OFF must record history even when global privacy is ON", historyCount(database, exceptionUrl) > 0);
                assertEquals(0, historyCount(database, globalPrivateUrl));

                prefs.setSiteSettingsEnabled("127.0.0.1:" + server.getLocalPort(), false);
                returnedSettings(browser);
                assertPrivacy(tab, true);
                assertTrue("Changing effective privacy must retain already-recorded ordinary visits", historyCount(database, exceptionUrl) > 0);
                server.close();
                serving.join(3000);
                assertFalse("Local HTTP fixture must stop", serving.isAlive());
                assertNull(serverFailure.get());
            } finally {
                server.close();
                serving.join(3000);
                if (activity != null) {
                    BrowserActivity browser = activity;
                    main(() -> { browser.finish(); return null; });
                    long end = System.currentTimeMillis() + 5000;
                    while (!main(browser::isDestroyed) && System.currentTimeMillis() < end) Thread.sleep(25);
                    assertTrue("Destroy must finish while exit clearing is disabled", main(browser::isDestroyed));
                    instrumentation.waitForIdleSync();
                }
            }
        } finally {
            for (String fixtureUrl : fixtureUrls) database.getWritableDatabase().delete(
                    BrowserDatabaseHelper.TABLE_HISTORY, "url=?", new String[]{fixtureUrl});
            restore(storage, original);
            assertEquals("The test must restore all browser and session preferences", original, snapshot(storage));
        }
    }

    private void resourcePagePrivacyEndsAtItsOrdinaryDestination(BrowserActivity browser, TabManager.Tab source,
            BrowserPrefs prefs, BrowserDatabaseHelper database, String destination, String title) throws Exception {
        // Force the same WebView branch: otherwise a new Page would hide a stale resourceSourceId.
        prefs.setBackNoReload(false);
        prefs.setSiteSettingsEnabled("appassets.androidplatform.net", false);
        Field snifferField = BrowserActivity.class.getDeclaredField("viaSniffer");
        snifferField.setAccessible(true);
        ViaSnifferStateMachine sniffer = main(() -> (ViaSnifferStateMachine) snifferField.get(browser));
        // Seed only resource-list eligibility; the actual navigation below is ordinary HTML.
        main(() -> {
            sniffer.onRequest(source.id, System.identityHashCode(source.webView), destination,
                    false, Collections.singletonMap("Range", "bytes=0-"));
            return null;
        });
        assertTrue("The isolated candidate must be available to the real resource page", sniffer.mediaUrls(source.id).contains(destination));
        main(() -> {
            Method open = BrowserActivity.class.getDeclaredMethod("openResourcePage");
            open.setAccessible(true);
            open.invoke(browser);
            return null;
        });
        TabManager.Tab resource = main(() -> tabs(browser).current());
        assertNotSame("Opening resources must create its actual internal page", source, resource);
        awaitPage(browser, resource, BrowserResourcePage.url(resource.id), instrumentation.getTargetContext().getString(R.string.via_menu_sniff));
        main(() -> {
            Field privacy = resource.tag.getClass().getDeclaredField("incognito");
            privacy.setAccessible(true);
            assertTrue("The internal resource page must inherit its private source", privacy.getBoolean(resource.tag));
            return null;
        });
        android.webkit.WebView resourceView = main(() -> resource.webView);
        main(() -> {
            Method navigate = BrowserActivity.class.getDeclaredMethod("loadUrlInCurrent", String.class);
            navigate.setAccessible(true);
            navigate.invoke(browser, destination);
            return null;
        });
        awaitPage(browser, resource, destination, title);
        assertSame("Exercise departure from the internal page without replacing its WebView", resourceView, main(() -> resource.webView));
        assertPrivacy(resource, false);
        assertTrue("The ordinary destination must record its own visit", historyCount(database, destination) > 0);
        main(() -> {
            Method close = BrowserActivity.class.getDeclaredMethod("closeAndDestroyTab", int.class);
            close.setAccessible(true);
            close.invoke(browser, tabs(browser).indexOf(resource));
            tabs(browser).select(tabs(browser).indexOf(source));
            Method show = BrowserActivity.class.getDeclaredMethod("showTab", TabManager.Tab.class);
            show.setAccessible(true);
            show.invoke(browser, source);
            return null;
        });
    }

    private String url(ServerSocket server, String host, String fixture, String page, List<String> urls) {
        String url = "http://" + host + ":" + server.getLocalPort() + "/" + fixture + "/" + page;
        urls.add(url);
        return url;
    }

    private void awaitPage(BrowserActivity browser, TabManager.Tab tab, String url, String title) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            boolean finished = main(() -> url.equals(tab.url) && url.equals(tab.webView.getUrl())
                    && title.equals(tab.webView.getTitle()) && tab.webView.getProgress() == 100
                    && browser.findViewById(R.id.browser_progress).getVisibility() == View.GONE);
            if (finished) { instrumentation.waitForIdleSync(); return; }
            Thread.sleep(25);
        }
        fail("The real BrowserActivity page-finished consumer did not finish " + url);
    }

    private void assertPrivacy(TabManager.Tab tab, boolean expected) throws Exception {
        CompletableFuture<String> scriptCount = new CompletableFuture<>();
        main(() -> {
            tab.webView.evaluateJavascript("window.incognitoScriptCount||0", scriptCount::complete);
            return null;
        });
        assertEquals("An enabled matching script must still run once in a private page", "1", scriptCount.get(5, TimeUnit.SECONDS));
        main(() -> {
            Field field = tab.tag.getClass().getDeclaredField("incognito");
            field.setAccessible(true);
            assertEquals("Tab state must follow the current URL and settings", expected, field.getBoolean(tab.tag));
            assertEquals("Via keeps normal WebView caching independent of private history", WebSettings.LOAD_DEFAULT,
                    tab.webView.getSettings().getCacheMode());
            // Android O+ disables WebView form storage; its getter always returns false.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                assertTrue("Via leaves legacy WebView form storage enabled", tab.webView.getSettings().getSaveFormData());
            } else {
                assertEquals("Private history must not disable the platform autofill configuration",
                        View.IMPORTANT_FOR_AUTOFILL_YES, tab.webView.getImportantForAutofill());
            }
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

    private void returnedSettings(BrowserActivity browser) throws Exception {
        main(() -> {
            Method method = BrowserActivity.class.getDeclaredMethod("applyReturnedSettings", Intent.class);
            method.setAccessible(true);
            method.invoke(browser, new Intent());
            return null;
        });
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
