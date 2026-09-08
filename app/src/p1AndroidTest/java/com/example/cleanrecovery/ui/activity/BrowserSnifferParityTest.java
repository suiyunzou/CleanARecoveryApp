package com.example.cleanrecovery.ui.activity;

import android.app.Instrumentation;
import android.content.Intent;
import android.view.View;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.TabManager;
import com.example.cleanrecovery.ui.browser.ViaSnifferStateMachine;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Browser shell and real WebView navigation tests; never run against the user's data. */
@RunWith(AndroidJUnit4.class)
public class BrowserSnifferParityTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private BrowserActivity activity;
    private TabManager tabs;
    private ViaSnifferStateMachine sniffer;
    private TabManager.Tab source;

    @Before public void start() throws Exception {
        assertTrue(instrumentation.getTargetContext().getPackageName().endsWith(".p1test"));
        Intent intent = new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = (BrowserActivity) instrumentation.startActivitySync(intent);
        tabs = (TabManager) field(activity, "tabs");
        sniffer = (ViaSnifferStateMachine) field(activity, "viaSniffer");
        source = tabs.current();
        main(() -> { source.webView.stopLoading(); source.url = "https://source.test/page"; return null; });
        instrumentation.waitForIdleSync();
        seed(source, "https://source.test/one.mp4", false);
    }

    @After public void stop() throws Exception {
        if (activity != null) main(() -> { activity.finish(); return null; });
        instrumentation.waitForIdleSync();
    }

    @Test public void menuUsesBrowserTabAndOnlyItsSourceResources() throws Exception {
        TabManager.Tab other = main(() -> (TabManager.Tab) invoke("newTab", new Class[]{String.class, boolean.class}, null, false));
        seed(other, "https://other.test/private.mp4", false);
        TabManager.Tab log = open();
        assertEquals("Opening the log adds one real browser tab", 3, main(tabs::size).intValue());
        assertEquals(View.VISIBLE, main(() -> activity.findViewById(R.id.browser_bottom_bar).getVisibility()).intValue());
        assertNull("The standalone sniffer screen is no longer used", activity.findViewById(R.id.sn_page_back));
        assertEquals("1", js(log.webView, "document.querySelectorAll('.box').length"));
        assertTrue(js(log.webView, "document.body.innerText").contains("source.test/one.mp4"));
        assertFalse(js(log.webView, "document.body.innerText").contains("private.mp4"));
        assertEquals("\"source.test\"", js(log.webView, "document.querySelector('.url b').textContent"));
        assertEquals("\"\"", js(log.webView, "document.querySelector('.box a').target"));
    }

    @Test public void refreshingDoesNotClearTheSourceAndMenuReusesLogTab() throws Exception {
        TabManager.Tab log = open();
        sniffer.onRequest(source.id, System.identityHashCode(source.webView), "https://source.test/two.mp3", true, null);
        main(() -> { menu(R.id.menu_reload); return null; });
        await(() -> "2".equals(js(log.webView, "document.querySelectorAll('.box').length")));
        assertEquals(2, sniffer.candidates(source.id).size());
        assertEquals("\"block\"", js(log.webView, "document.querySelector('.tag').textContent"));
        main(() -> { menu(R.id.menu_sniff); return null; });
        assertEquals("Repeated menu invocation must not create nested log tabs", 2, main(tabs::size).intValue());
        assertSame(log, main(tabs::current));
    }

    @Test public void closingSourceNeverFallsBackToAnotherTabsLog() throws Exception {
        TabManager.Tab log = open();
        main(() -> { invoke("closeAndDestroyTab", new Class[]{int.class}, 0); menu(R.id.menu_reload); return null; });
        await(() -> "0".equals(js(log.webView, "document.querySelectorAll('.box').length")));
        assertTrue(sniffer.candidates(source.id).isEmpty());
        assertTrue(js(log.webView, "document.body.innerText").contains(activity.getString(R.string.via_page_resource_none)));
    }

    @Test public void internalLogDoesNotPolluteHistoryOrSniffItself() throws Exception {
        BrowserDatabaseHelper db = (BrowserDatabaseHelper) field(activity, "dbHelper");
        int beforeHistory = db.listHistory().size();
        TabManager.Tab log = open();
        assertTrue(sniffer.candidates(log.id).isEmpty());
        assertEquals("1", js(log.webView, "document.querySelectorAll('style').length"));
        assertEquals("0", js(log.webView, "document.querySelectorAll('link').length"));
        assertEquals("A private internal resource page must not enter browsing history", beforeHistory, db.listHistory().size());
        main(() -> { activity.findViewById(R.id.browser_nav_back).performClick(); return null; });
        assertSame("Back from a fresh log returns to its source tab", source, main(tabs::current));
    }

    @Test public void realRequestsDriveButtonAndLinksNavigateWithinTheLogTab() throws Exception {
        try (FixtureServer server = new FixtureServer()) {
            String page = server.base() + "/page";
            String resource = server.base() + "/resource.mp4";
            main(() -> { source.webView.loadUrl(page); return null; });
            await(() -> sniffer.mediaUrls(source.id).contains(resource) && sniffer.shouldShowButton(source.id));
            View button = (View) field(activity, "snifferButton");
            await(() -> main(() -> button.getVisibility() == View.VISIBLE));
            BrowserPrefs prefs = (BrowserPrefs) field(activity, "prefs");
            main(() -> { prefs.setAutoSnifferButton(false); invoke("applyToolbarMode", new Class[0]); return null; });
            assertEquals(View.GONE, main(button::getVisibility).intValue());
            main(() -> { prefs.setAutoSnifferButton(true); invoke("applyToolbarMode", new Class[0]); button.performClick(); return null; });
            TabManager.Tab log = main(tabs::current);
            assertNotSame(source, log);
            await(() -> "1".equals(js(log.webView, "document.querySelectorAll('.box').length")));
            assertEquals(View.GONE, main(button::getVisibility).intValue());
            assertEquals("0", js(log.webView, "document.querySelectorAll('link').length"));
            android.graphics.Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(
                    instrumentation.getTargetContext().getExternalFilesDir(null), "sniffer-parity.png"))) {
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
            } finally { screenshot.recycle(); }
            // Real touch matters: Chromium can skip history entries without user activation.
            org.json.JSONArray point = new org.json.JSONArray(js(log.webView,
                    "(function(){var r=document.querySelector('.box a').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2]})()"));
            int[] origin = main(() -> { int[] xy = new int[2]; log.webView.getLocationOnScreen(xy); return xy; });
            float scale = main(() -> log.webView.getResources().getDisplayMetrics().density);
            float x = origin[0] + (float) point.getDouble(0) * scale;
            float y = origin[1] + (float) point.getDouble(1) * scale;
            long down = android.os.SystemClock.uptimeMillis();
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, x, y, 0));
            instrumentation.sendPointerSync(android.view.MotionEvent.obtain(down, down + 80, android.view.MotionEvent.ACTION_UP, x, y, 0));
            await(() -> "\"Opened resource\"".equals(js(log.webView, "document.title")));
            assertEquals("Row clicks navigate, rather than create a third tab", 2, main(tabs::size).intValue());
            main(() -> { activity.findViewById(R.id.browser_nav_back).performClick(); return null; });
            try {
                await(() -> "1".equals(js(log.webView, "document.querySelectorAll('.box').length")));
            } catch (AssertionError error) {
                throw new AssertionError("After back: " + js(log.webView, "[location.href,document.body.innerText]")
                        + " tabs=" + main(tabs::size) + " canBack=" + main(log.webView::canGoBack), error);
            }
            assertTrue("The source log survives navigation in its separate log tab", sniffer.mediaUrls(source.id).contains(resource));
            main(() -> { activity.findViewById(R.id.browser_nav_forward).performClick(); return null; });
            await(() -> "\"Opened resource\"".equals(js(log.webView, "document.title")));
        }
    }

    @Test public void emptyAndUnsupportedSourcesDoNotOpenUnrelatedGlobalLogs() throws Exception {
        sniffer.clear(source.id);
        main(() -> { menu(R.id.menu_sniff); return null; });
        assertEquals(1, main(tabs::size).intValue());
        seed(source, "https://cdn.test/private.mp4", false);
        main(() -> { source.url = "https://www.bilibili.com/video"; menu(R.id.menu_sniff); return null; });
        assertEquals("Unsupported-site guard is applied at the entry point", 1, main(tabs::size).intValue());
    }

    @Test public void backReturnsToSourceEvenWhenAnotherBackgroundTabExists() throws Exception {
        main(() -> invoke("newTab", new Class[]{String.class, boolean.class}, null, false));
        open();
        main(() -> { activity.findViewById(R.id.browser_nav_back).performClick(); return null; });
        assertSame(source, main(tabs::current));
    }

    @Test public void clearRequiresConfirmationAndOnlyClearsTheBoundSource() throws Exception {
        TabManager.Tab other = main(() -> (TabManager.Tab) invoke("newTab", new Class[]{String.class, boolean.class}, null, false));
        seed(other, "https://other.test/keep.mp4", false);
        TabManager.Tab log = open();
        resourceMenu(log, "https://source.test/one.mp4");
        clickText(activity.getString(R.string.via_sniffer_clear));
        clickText(activity.getString(R.string.via_cancel));
        assertEquals("Cancel must leave the source log untouched", 1, sniffer.candidates(source.id).size());
        resourceMenu(log, "https://source.test/one.mp4");
        clickText(activity.getString(R.string.via_sniffer_clear));
        clickText(activity.getString(R.string.via_sniffer_clear));
        await(() -> "0".equals(js(log.webView, "document.querySelectorAll('.box').length")));
        assertTrue(sniffer.candidates(source.id).isEmpty());
        assertEquals("Clearing a log must not clear another tab", 1, sniffer.candidates(other.id).size());
    }

    @Test public void customizedMenuMoreAndIncognitoNewTabKeepTheirSemantics() throws Exception {
        try (FixtureServer server = new FixtureServer()) {
            main(() -> { menu(R.id.menu_incognito); return null; });
            TabManager.Tab log = open();
            assertEquals(android.webkit.WebSettings.LOAD_NO_CACHE, main(() -> log.webView.getSettings().getCacheMode()).intValue());
            BrowserPrefs prefs = (BrowserPrefs) field(activity, "prefs");
            java.util.Set<String> before = new java.util.HashSet<>(prefs.longPressFlags());
            try {
                main(() -> { prefs.setLongPressFlags(java.util.Collections.singleton("copy_link")); return null; });
                resourceMenu(log, server.base() + "/resource.mp4");
                assertFalse(hasText(activity.getString(R.string.via_bk_open_newtab)));
                assertTrue(hasText(activity.getString(R.string.via_sniffer_play)));
                clickText(activity.getString(R.string.via_sniffer_more));
                assertTrue(hasText(activity.getString(R.string.via_bk_open_newtab)));
                clickText(activity.getString(R.string.via_bk_open_newtab));
                TabManager.Tab opened = main(tabs::current);
                assertNotSame(log, opened);
                await(() -> "\"Opened resource\"".equals(js(opened.webView, "document.title")));
                assertEquals(Boolean.TRUE, field(opened.tag, "incognito"));
                assertEquals("Navigation settings must not silently restore the normal disk cache",
                        android.webkit.WebSettings.LOAD_NO_CACHE, main(() -> opened.webView.getSettings().getCacheMode()).intValue());
                assertFalse(main(() -> opened.webView.getSettings().getSaveFormData()));
            } finally { main(() -> { prefs.setLongPressFlags(before); return null; }); }
        }
    }

    @Test public void duplicateDownloadHasEditableNameAndDoesNotClaimNewSuccess() throws Exception {
        try (FixtureServer server = new FixtureServer()) {
            String url = server.base() + "/duplicate.mp4";
            com.example.cleanrecovery.background.DownloadQueueManager queue = com.example.cleanrecovery.background.DownloadQueueManager.getInstance();
            assertTrue(queue.enqueue(url, null, source.url, "fixture") >= 0);
            TabManager.Tab log = open();
            resourceMenu(log, url);
            clickText(activity.getString(R.string.via_menu_download));
            assertTrue("Download confirmation includes an editable filename", hasClass(instrumentation.getUiAutomation().getRootInActiveWindow(), "android.widget.EditText"));
            java.util.List<String> notifications = new java.util.concurrent.CopyOnWriteArrayList<>();
            instrumentation.getUiAutomation().setOnAccessibilityEventListener(event -> {
                if (event.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) notifications.add(event.getText().toString());
            });
            try {
                clickText(activity.getString(R.string.via_dl_action));
                await(() -> notifications.stream().anyMatch(text -> text.contains(activity.getString(R.string.via_sniffer_already_queued))));
                assertFalse(notifications.stream().anyMatch(text -> text.contains(activity.getString(R.string.via_sniffer_download_queued))));
            } finally { instrumentation.getUiAutomation().setOnAccessibilityEventListener(null); }
        }
    }

    private void resourceMenu(TabManager.Tab log, String url) throws Exception {
        main(() -> invoke("showResourceMenu", new Class[]{TabManager.Tab.class, String.class}, log, url));
        instrumentation.waitForIdleSync();
    }

    @Test public void editMenuOpensExistingLongPressSettingsAndBackReturnsToLog() throws Exception {
        TabManager.Tab log = open();
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(BrowserSettingsActivity.class.getName(), null, false);
        try {
            resourceMenu(log, "https://source.test/one.mp4");
            clickText(activity.getString(R.string.via_bk_edit));
            BrowserSettingsActivity settings = (BrowserSettingsActivity) instrumentation.waitForMonitorWithTimeout(monitor, 5000);
            assertNotNull(settings);
            assertEquals("LONGPRESS", field(settings, "current").toString());
            main(() -> { settings.onBackPressed(); return null; });
            instrumentation.waitForIdleSync();
            assertSame(log, main(tabs::current));
        } finally { instrumentation.removeMonitor(monitor); }
    }
    private boolean hasText(String label) {
        android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        return root != null && root.findAccessibilityNodeInfosByText(label).stream().anyMatch(node -> label.contentEquals(node.getText() == null ? "" : node.getText()));
    }
    private boolean hasClass(android.view.accessibility.AccessibilityNodeInfo node, String className) {
        if (node == null) return false;
        if (className.contentEquals(node.getClassName())) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (hasClass(node.getChild(i), className)) return true;
        return false;
    }
    private void clickText(String label) throws Exception {
        await(() -> {
            android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label)) {
                if (!label.contentEquals(node.getText() == null ? "" : node.getText())) continue;
                for (int depth = 0; node != null && depth < 3; depth++, node = node.getParent()) {
                    if (node.isClickable() && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
                }
            }
            return false;
        });
        instrumentation.waitForIdleSync();
    }

    /** Local deterministic HTTP fixture; exercises the production client, not a substituted client. */
    private static final class FixtureServer implements AutoCloseable {
        private final java.net.ServerSocket server = new java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"));
        private final Thread worker;
        FixtureServer() throws Exception {
            worker = new Thread(() -> {
                while (!server.isClosed()) {
                    try (java.net.Socket socket = server.accept()) {
                        socket.setSoTimeout(3000);
                        java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                        String request = input.readLine();
                        String header;
                        while ((header = input.readLine()) != null && !header.isEmpty()) { }
                        String body = request != null && request.contains("/page ")
                                ? "<html><head><title>Resource source</title></head><body>Fixture<script>fetch('/resource.mp4')</script></body></html>"
                                : "<html><head><title>Opened resource</title></head><body>Resource target</body></html>";
                        byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: "
                                + bytes.length + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                        socket.getOutputStream().write(bytes);
                    } catch (java.io.IOException ignored) {
                        if (server.isClosed()) return;
                    }
                }
            }, "sniffer-fixture");
            worker.start();
        }
        String base() { return "http://127.0.0.1:" + server.getLocalPort(); }
        @Override public void close() throws Exception { server.close(); worker.join(4000); }
    }

    private void seed(TabManager.Tab tab, String resource, boolean blocked) {
        int viewId = System.identityHashCode(tab.webView);
        sniffer.onPageStarted(tab.id, viewId, "https://source.test/page");
        sniffer.onPageCommitVisible(tab.id, viewId);
        sniffer.onRequest(tab.id, viewId, resource, blocked, null);
    }

    private TabManager.Tab open() throws Exception {
        int before = main(tabs::size);
        main(() -> { menu(R.id.menu_sniff); return null; });
        assertEquals("Resource sniffing must stay inside BrowserActivity's tab manager", before + 1, main(tabs::size).intValue());
        TabManager.Tab log = main(tabs::current);
        await(() -> js(log.webView, "document.title").equals("\"" + activity.getString(R.string.via_menu_sniff) + "\""));
        return log;
    }

    private void menu(int id) throws Exception { invoke("onMenuAction", new Class[]{int.class}, id); }
    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = BrowserActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(activity, args);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        instrumentation.runOnMainSync(task);
        return task.get(10, TimeUnit.SECONDS);
    }
    private String js(WebView view, String script) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        main(() -> { view.evaluateJavascript(script, value -> { result.set(value); latch.countDown(); }); return null; });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        return result.get();
    }
    private void await(Callable<Boolean> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            if (check.call()) return;
            Thread.sleep(100);
        } while (System.nanoTime() < end);
        fail("Expected WebView state within 15 seconds");
    }
}
