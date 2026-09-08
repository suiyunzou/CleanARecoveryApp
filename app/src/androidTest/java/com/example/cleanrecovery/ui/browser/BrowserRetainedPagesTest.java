package com.example.cleanrecovery.ui.browser;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.webkit.WebView;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserRetainedPagesTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void frozenPagesRestoreThroughBackAndForwardWithoutOpeningAnotherTab() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String, Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        BrowserDatabaseHelper database = BrowserDatabaseHelper.getInstance(instrumentation.getTargetContext());
        List<String> fixtureUrls = new ArrayList<>();
        BrowserActivity activity = null;
        String fixture = "__retained_pages_" + UUID.randomUUID();
        try (PageServer server = new PageServer(fixture)) {
            try {
                prefs.setRestoreTabs(0);
                prefs.setExitClearFlags(Collections.emptySet());
                prefs.setClearDataOnExit(false);
                prefs.setScriptsEnabled(false);
                prefs.setAdBlockEnabled(false);
                prefs.setJsEnabled(true);
                prefs.setBackNoReload(true);
                prefs.setSiteSettingsEnabled("127.0.0.1:" + server.server.getLocalPort(), false);
                for (int page = 1; page <= 6; page++) fixtureUrls.add(server.url(page));

                activity = (BrowserActivity) instrumentation.startActivitySync(
                        new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                                .setAction(Intent.ACTION_VIEW).setData(Uri.parse(fixtureUrls.get(0)))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
                BrowserActivity browser = activity;
                TabManager.Tab tab = main(() -> tabs(browser).current());
                awaitPage(browser, tab, fixtureUrls.get(0), server.title(1));
                assertEquals("This isolated launch must start with one top-level tab", 1, main(() -> tabs(browser).size()).intValue());
                List<WebView> firstInstances = new ArrayList<>();
                firstInstances.add(main(() -> tab.webView));
                for (int page = 2; page <= 6; page++) {
                    loadAddress(browser, fixtureUrls.get(page - 1));
                    awaitPage(browser, tab, fixtureUrls.get(page - 1), server.title(page));
                    firstInstances.add(main(() -> tab.webView));
                }

                main(() -> {
                    assertEquals("Back-without-reload must retain six internal page records", 6, tab.pages().size());
                    assertEquals(5, tab.pageIndex());
                    assertEquals("Internal pages must not become extra top-level tabs", 1, tabs(browser).size());
                    List<TabManager.Page> pages = tab.pages();
                    for (int index = 0; index < 2; index++) {
                        assertNull("Pages outside Via's three-page back window must release their WebView", pages.get(index).webView);
                        assertNotNull("Released pages must retain state for history navigation", pages.get(index).savedState);
                        assertFalse("A frozen record must contain actual saved browser state", pages.get(index).savedState.isEmpty());
                        assertEquals(fixtureUrls.get(index), pages.get(index).url);
                    }
                    for (int index = 2; index < 6; index++) {
                        assertSame("Recent pages must keep their original live document", firstInstances.get(index), pages.get(index).webView);
                    }
                    return null;
                });

                // Exercise the real toolbar consumer, including the transition into a frozen record.
                for (int page = 5; page >= 2; page--) {
                    clickHistory(browser, R.id.browser_nav_back);
                    awaitPage(browser, tab, fixtureUrls.get(page - 1), server.title(page));
                    int expectedIndex = page - 1;
                    main(() -> {
                        assertEquals(expectedIndex, tab.pageIndex());
                        assertSame("Back must stay inside the original top-level tab", tab, tabs(browser).current());
                        assertEquals(1, tabs(browser).size());
                        return null;
                    });
                }
                WebView restoredSecond = main(() -> tab.webView);
                assertNotNull("Selecting a frozen page must recreate its WebView", restoredSecond);
                assertNotSame("The released WebView must not be reused after destruction", firstInstances.get(1), restoredSecond);
                for (int page = 3; page <= 6; page++) {
                    clickHistory(browser, R.id.browser_nav_forward);
                    awaitPage(browser, tab, fixtureUrls.get(page - 1), server.title(page));
                    int expectedIndex = page - 1;
                    main(() -> {
                        assertEquals("Restoration must preserve the forward branch", expectedIndex, tab.pageIndex());
                        assertEquals(6, tab.pages().size());
                        assertEquals(1, tabs(browser).size());
                        return null;
                    });
                }

                main(() -> {
                    List<TabManager.Page> pages = tab.pages();
                    TabManager.Page stale = pages.get(3), fresh = pages.get(4), current = pages.get(5);
                    WebView freshWeb = fresh.webView, currentWeb = current.webView;
                    assertNotNull(stale.webView);
                    assertNotNull(freshWeb);
                    stale.lastActiveMs = SystemClock.elapsedRealtime() - 300001L;
                    fresh.lastActiveMs = SystemClock.elapsedRealtime();
                    Method trim = BrowserActivity.class.getDeclaredMethod("trimRetainedPages", TabManager.Tab.class);
                    trim.setAccessible(true);
                    trim.invoke(browser, tab);
                    assertNull("Even an in-window page must freeze after five minutes away", stale.webView);
                    assertNotNull(stale.savedState);
                    assertFalse(stale.savedState.isEmpty());
                    assertSame("A fresh neighbouring page must remain live", freshWeb, fresh.webView);
                    assertSame("Age trimming must never destroy the current document", currentWeb, current.webView);
                    assertSame(currentWeb, tab.webView);
                    return null;
                });
                assertNull("The concurrent HTTP fixture must not fail", server.failure.get());
            } finally {
                if (activity != null) {
                    BrowserActivity browser = activity;
                    main(() -> { browser.finish(); return null; });
                    long end = SystemClock.elapsedRealtime() + 5000;
                    while (!main(browser::isDestroyed) && SystemClock.elapsedRealtime() < end) Thread.sleep(25);
                    assertTrue("All retained pages must be destroyed before restoring exit preferences", main(browser::isDestroyed));
                    instrumentation.waitForIdleSync();
                }
            }
        } finally {
            // Never run clear-data actions: remove only this run's exact, UUID-scoped visit URLs.
            try {
                for (String url : fixtureUrls) database.getWritableDatabase().delete(
                        BrowserDatabaseHelper.TABLE_HISTORY, "url=?", new String[]{url});
            } finally {
                restore(storage, original);
                assertEquals("The fixture must restore browser and session preferences exactly", original, snapshot(storage));
            }
        }
    }

    private void loadAddress(BrowserActivity browser, String url) throws Exception {
        main(() -> {
            Method load = BrowserActivity.class.getDeclaredMethod("loadUrlInCurrent", String.class);
            load.setAccessible(true);
            load.invoke(browser, url);
            return null;
        });
    }

    private void clickHistory(BrowserActivity browser, int buttonId) throws Exception {
        main(() -> {
            View button = browser.findViewById(buttonId);
            assertNotNull("The history action must use the real toolbar button", button);
            assertTrue("The toolbar history action must accept the click", button.performClick());
            return null;
        });
    }

    private void awaitPage(BrowserActivity browser, TabManager.Tab tab, String url, String title) throws Exception {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < end) {
            if (main(() -> tab.webView != null && url.equals(tab.url) && url.equals(tab.webView.getUrl())
                    && title.equals(tab.webView.getTitle()) && tab.webView.getProgress() == 100
                    && browser.findViewById(R.id.browser_progress).getVisibility() == View.GONE)) {
                instrumentation.waitForIdleSync();
                return;
            }
            Thread.sleep(25);
        }
        fail("The selected page did not restore its expected URL and title: " + url);
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

    private static final class PageServer implements AutoCloseable {
        final String fixture;
        final ServerSocket server;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread accepting;

        PageServer(String fixture) throws Exception {
            this.fixture = fixture;
            server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            accepting = new Thread(() -> {
                while (!server.isClosed()) try {
                    Socket socket = server.accept();
                    workers.execute(() -> serve(socket));
                } catch (Throwable error) {
                    if (!server.isClosed()) failure.compareAndSet(null, error);
                }
            }, "retained-page-fixture");
            accepting.start();
        }

        String url(int page) { return "http://127.0.0.1:" + server.getLocalPort() + "/" + fixture + "/page-" + page; }
        String title(int page) { return fixture + ":page-" + page; }

        private void serve(Socket accepted) {
            try (Socket socket = accepted) {
                socket.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                String first = reader.readLine(), line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                if (first == null) return;
                String path = first.split(" ")[1];
                String title = null;
                for (int page = 1; page <= 6; page++) {
                    if (path.equals("/" + fixture + "/page-" + page)) title = title(page);
                }
                byte[] body = title == null ? new byte[0] : ("<!doctype html><html><head><title>" + title
                        + "</title><link rel=\"icon\" href=\"data:,\"></head><body><h1>" + title
                        + "</h1><p>Independent retained page document.</p></body></html>").getBytes(StandardCharsets.UTF_8);
                socket.getOutputStream().write(("HTTP/1.1 " + (title == null ? "404 Not Found" : "200 OK")
                        + "\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().write(body);
                socket.getOutputStream().flush();
            } catch (Throwable error) {
                if (!server.isClosed()) failure.compareAndSet(null, error);
            }
        }

        @Override public void close() throws Exception {
            server.close();
            accepting.join(3000);
            workers.shutdownNow();
            assertFalse("The HTTP accept loop must stop", accepting.isAlive());
            assertTrue("Concurrent fixture requests must stop", workers.awaitTermination(4, TimeUnit.SECONDS));
        }
    }
}
