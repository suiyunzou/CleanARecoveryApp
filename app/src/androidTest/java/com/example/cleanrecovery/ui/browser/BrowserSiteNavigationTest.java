package com.example.cleanrecovery.ui.browser;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.background.DownloadTaskDbHelper;
import com.example.cleanrecovery.ui.activity.BrowserActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserSiteNavigationTest {
    private static final String FETCH_FIXTURE_HEADER = "X-Via-Navigation-Fixture";
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private String touchEvidence = "No link touch was attempted";

    @Test public void targetSiteUserAgentIsUsedOnTheFirstRequestForEveryNavigationEntry() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String, Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        BrowserDatabaseHelper database = BrowserDatabaseHelper.getInstance(instrumentation.getTargetContext());
        BrowserActivity activity = null;
        List<String> fixtureUrls = new ArrayList<>();
        String fixture = "__site_navigation_" + UUID.randomUUID();
        String uaA = "ViaSiteNavigation-A/1.0", uaB = "ViaSiteNavigation-B/2.0";
        try (FixtureServer server = new FixtureServer(fixture)) {
            String initialA = server.page("localhost", "initial-a", fixtureUrls);
            String navigationB = server.page("127.0.0.1", "navigation-b", fixtureUrls);
            String linkedA = server.page("localhost", "linked-a", fixtureUrls);
            String branchA = server.page("localhost", "branch-a", fixtureUrls);
            String backgroundB = server.page("127.0.0.1", "background-b", fixtureUrls);
            String mediaB = server.page("127.0.0.1", "page-b-media.m3u8", fixtureUrls);
            String mediaA = server.page("localhost", "page-a-media.m3u8", fixtureUrls);
            server.linkFrom(navigationB, linkedA);
            prefs.setRestoreTabs(0);
            prefs.setExitClearFlags(Collections.emptySet());
            prefs.setClearDataOnExit(false);
            prefs.setScriptsEnabled(false);
            prefs.setJsEnabled(true);
            prefs.setAdBlockEnabled(false);
            prefs.setIncognitoMode(false);
            prefs.setSimpleUa(false);
            prefs.setDesktopMode(false);
            prefs.setTextZoom(100);
            prefs.setBackNoReload(true);
            for (String host : new String[]{Uri.parse(initialA).getAuthority(), Uri.parse(navigationB).getAuthority()}) {
                prefs.resetSiteSettings(host);
                prefs.setSiteSettingsEnabled(host, true);
                prefs.setSiteJsMode(host, 1);
                prefs.setSiteDesktopMode(host, 0);
                prefs.setSiteAdBlockMode(host, 0);
                prefs.setSiteRedirectMode(host, 0);
                prefs.setSiteUserAgent(host, host.equals(Uri.parse(initialA).getAuthority()) ? uaA : uaB);
            }
            try {
                activity = (BrowserActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(initialA))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
                BrowserActivity browser = activity;
                TabManager.Tab foreground = main(() -> tabs(browser).current());
                awaitPage(browser, foreground, initialA, fixture, true);
                server.assertSingleRequest(initialA, uaA, "A new foreground tab must configure its destination before requesting it");

                main(() -> {
                    Method method = BrowserActivity.class.getDeclaredMethod("loadUrlInCurrent", String.class);
                    method.setAccessible(true);
                    method.invoke(browser, navigationB);
                    return null;
                });
                awaitPage(browser, foreground, navigationB, fixture, true);
                server.assertSingleRequest(navigationB, uaB, "Address navigation must not send the previous site's UA first");
                // Capture B only after navigation: an address load may legitimately replace its previous WebView.
                WebView retainedB = main(() -> foreground.webView);
                String marker = fixture + "-retained-memory";
                assertEquals("The fixture must place state in B's live DOM and JavaScript heap", "true", javascript(foreground,
                        "(function(){window.__siteNavigationMemory={token:" + JSONObject.quote(marker) + "};"
                                + "var input=document.createElement('input');input.id='retained-value';input.value=" + JSONObject.quote(marker)
                                + ";document.body.appendChild(input);return true;})()"));
                fetchMedia(foreground, mediaB, fixture);
                assertMediaUrls(browser, foreground, Collections.singletonList(mediaB), "B must expose its own captured media");

                tapLink(foreground);
                awaitPage(browser, foreground, linkedA, fixture, true);
                assertSame("An ordinary page link must stay in the same tab", foreground, main(() -> tabs(browser).current()));
                server.assertSingleRequest(linkedA, uaA, "A real link click must apply the destination UA without a corrective reload");
                WebView retainedA = main(() -> foreground.webView);
                assertMediaUrls(browser, foreground, Collections.emptyList(), "The newly opened A page must not inherit B's media");
                fetchMedia(foreground, mediaA, fixture);
                assertMediaUrls(browser, foreground, Collections.singletonList(mediaA), "A must expose only its own captured media");

                tapToolbar(browser, R.id.browser_nav_back);
                awaitPage(browser, foreground, navigationB, fixture, true);
                assertRetainedPageB(browser, foreground, retainedB, marker);
                assertMediaUrls(browser, foreground, Collections.singletonList(mediaB), "Back must restore B's captured media without mixing A");
                server.assertSingleRequest(navigationB, uaB, "Back without reload must restore B without requesting it again");
                server.assertSingleRequest(linkedA, uaA, "Leaving A through Back must not request A again");

                tapToolbar(browser, R.id.browser_nav_forward);
                awaitPage(browser, foreground, linkedA, fixture, true);
                assertSame("Forward must preserve the same browser tab", foreground, main(() -> tabs(browser).current()));
                assertSame("Forward must restore the retained A WebView", retainedA, main(() -> foreground.webView));
                assertMediaUrls(browser, foreground, Collections.singletonList(mediaA), "Forward must restore A's captured media without mixing B");
                server.assertSingleRequest(linkedA, uaA, "Forward must restore A without a new main-document request");
                server.assertSingleRequest(navigationB, uaB, "Forward must not reload the retained B page");

                tapToolbar(browser, R.id.browser_nav_back);
                awaitPage(browser, foreground, navigationB, fixture, true);
                assertRetainedPageB(browser, foreground, retainedB, marker);
                assertMediaUrls(browser, foreground, Collections.singletonList(mediaB), "A second Back must still retain B's original candidates");
                main(() -> {
                    Method method = BrowserActivity.class.getDeclaredMethod("loadUrlInCurrent", String.class);
                    method.setAccessible(true);
                    method.invoke(browser, branchA);
                    return null;
                });
                awaitPage(browser, foreground, branchA, fixture, true);
                server.assertSingleRequest(branchA, uaA, "A new branch must use its destination UA on its sole request");
                assertMediaUrls(browser, foreground, Collections.emptyList(), "A replacement branch must not inherit discarded or previous page media");
                assertEquals("A new navigation after Back must clear the old forward branch", 0.4f,
                        main(() -> browser.findViewById(R.id.browser_nav_forward).getAlpha()), 0.01f);
                // The toolbar deliberately remains enabled; an unavailable Forward action must do nothing.
                tapToolbar(browser, R.id.browser_nav_forward);
                awaitPage(browser, foreground, branchA, fixture, true);
                assertSame("An unavailable Forward action must not switch tabs", foreground, main(() -> tabs(browser).current()));
                server.assertSingleRequest(branchA, uaA, "Unavailable Forward must not reload the new branch");
                server.assertSingleRequest(linkedA, uaA, "Discarded A must not return through the Forward button");

                TabManager.Tab background = main(() -> {
                    Method method = BrowserActivity.class.getDeclaredMethod("newTab", String.class, boolean.class);
                    method.setAccessible(true);
                    return (TabManager.Tab) method.invoke(browser, backgroundB, false);
                });
                assertSame("Opening a background tab must preserve the foreground tab", foreground, main(() -> tabs(browser).current()));
                awaitPage(browser, background, backgroundB, fixture, false);
                server.assertSingleRequest(backgroundB, uaB, "A background tab must use its own destination settings for its first request");
                assertMediaUrls(browser, background, Collections.emptyList(), "A new background tab must not inherit another tab's media");
                assertMediaUrls(browser, foreground, Collections.emptyList(), "Opening a background tab must preserve the foreground page's media scope");
                assertEquals("The discarded forward page must remain inaccessible after opening another tab", branchA,
                        main(() -> foreground.webView.getUrl()));
                assertEquals("Background settings must not leak into the foreground tab", uaA,
                        main(() -> foreground.webView.getSettings().getUserAgentString()));

                // Recheck all paths after the last page finishes: a later corrective reload is a failure too.
                server.assertSingleRequest(initialA, uaA, "Initial navigation must remain a single request");
                server.assertSingleRequest(navigationB, uaB, "Address navigation must remain a single request");
                server.assertSingleRequest(linkedA, uaA, "Link navigation must remain a single request");
                server.assertSingleRequest(branchA, uaA, "The replacement branch must remain a single request");
                server.assertSingleRequest(backgroundB, uaB, "Background navigation must remain a single request");
                server.assertSinglePageFetch(mediaB, uaB, "Retained B media must not be recovered by issuing another page fetch");
                server.assertSinglePageFetch(mediaA, uaA, "Retained A media must not be recovered by issuing another page fetch");
                assertEquals("Discovering B's media must not issue an unrequested background download", Collections.emptyList(), server.unmarkedRequests(mediaB));
                assertEquals("Discovering A's media must not issue an unrequested background download", Collections.emptyList(), server.unmarkedRequests(mediaA));
                for (String media : Arrays.asList(mediaB, mediaA)) try (Cursor cursor = DownloadTaskDbHelper.getInstance(instrumentation.getTargetContext())
                        .getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tasks WHERE url=?", new String[]{media})) {
                    assertTrue(cursor.moveToFirst()); assertEquals("Sniffing alone must not create a download task", 0, cursor.getInt(0));
                }
                assertNull("The local HTTP fixture must not conceal connection errors", server.failure.get());
            } catch (Exception | AssertionError error) {
                throw new AssertionError(error.getMessage() + "\n" + touchEvidence
                        + "\nFixture requests: " + server.requests + "\n" + failureEvidence(activity), error);
            } finally {
                try {
                    if (activity != null) {
                        BrowserActivity browser = activity;
                        main(() -> { browser.finish(); return null; });
                        long end = SystemClock.uptimeMillis() + 5000;
                        while (!main(browser::isDestroyed) && SystemClock.uptimeMillis() < end) Thread.sleep(25);
                        assertTrue("Destroy must complete before restoring exit-clearing preferences", main(browser::isDestroyed));
                        instrumentation.waitForIdleSync();
                    }
                } finally {
                    try {
                        // Keep the fixture server alive while these exact native-download jobs reach a terminal state.
                        cleanFixtureDownloads(Arrays.asList(mediaB, mediaA));
                    } finally {
                        Bundle nativeRequests = new Bundle();
                        nativeRequests.putString("stream", "\nNON_PAGE_DOWNLOAD_REQUESTS: requests without the page-fetch fixture header; B=" + server.unmarkedRequests(mediaB)
                                + "; A=" + server.unmarkedRequests(mediaA) + "\n");
                        instrumentation.sendStatus(0, nativeRequests);
                    }
                }
            }
        } finally {
            try {
                for (String url : fixtureUrls) database.getWritableDatabase().delete(
                        BrowserDatabaseHelper.TABLE_HISTORY, "url=?", new String[]{url});
            } finally {
                restore(storage, original);
                assertEquals("The test must restore every browser and session preference", original, snapshot(storage));
            }
        }
    }

    private void cleanFixtureDownloads(List<String> urls) throws Exception {
        DownloadTaskDbHelper downloads = DownloadTaskDbHelper.getInstance(instrumentation.getTargetContext());
        long end = SystemClock.uptimeMillis() + 20000;
        while (true) {
            List<String> active = new ArrayList<>();
            for (String url : urls) try (Cursor cursor = downloads.getReadableDatabase().rawQuery(
                    "SELECT status FROM tasks WHERE url=?", new String[]{url})) {
                while (cursor.moveToNext()) {
                    String status = cursor.getString(0);
                    if ("PENDING".equals(status) || "RUNNING".equals(status)) active.add(url + " " + status);
                }
            }
            if (active.isEmpty()) break;
            if (SystemClock.uptimeMillis() >= end) fail("Fixture download cleanup timed out; other user tasks were not touched: " + active);
            Thread.sleep(50);
        }
        for (String url : urls) {
            downloads.getWritableDatabase().delete("tasks", "url=?", new String[]{url});
            try (Cursor cursor = downloads.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tasks WHERE url=?", new String[]{url})) {
                assertTrue(cursor.moveToFirst());
                assertEquals("The test must remove only its completed fixture download records", 0, cursor.getInt(0));
            }
        }
    }

    private void fetchMedia(TabManager.Tab tab, String url, String fixture) throws Exception {
        assertEquals("The page must initiate a real same-origin media request", "true", javascript(tab,
                "(function(){window.__siteNavigationFetch='pending';fetch(" + JSONObject.quote(url) + ",{cache:'no-store',headers:{"
                        + JSONObject.quote(FETCH_FIXTURE_HEADER) + ":" + JSONObject.quote(fixture) + "}})"
                        + ".then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.text();})"
                        + ".then(function(){window.__siteNavigationFetch='done';},function(e){window.__siteNavigationFetch='error:'+e;});return true;})()"));
        long end = SystemClock.uptimeMillis() + 5000;
        String state = "";
        while (SystemClock.uptimeMillis() < end) {
            state = javascript(tab, "window.__siteNavigationFetch");
            if ("\"done\"".equals(state)) return;
            assertFalse("The media request must complete through the real page: " + state, state.startsWith("\"error:"));
            Thread.sleep(25);
        }
        fail("The real media fetch did not finish: " + url + "; state=" + state);
    }

    private void assertMediaUrls(BrowserActivity browser, TabManager.Tab tab, List<String> expected, String reason) throws Exception {
        assertEquals(reason, expected, main(() -> {
            Field field = BrowserActivity.class.getDeclaredField("viaSniffer");
            field.setAccessible(true);
            return ((ViaSnifferStateMachine) field.get(browser)).mediaUrls(tab.id);
        }));
    }

    private void assertRetainedPageB(BrowserActivity browser, TabManager.Tab tab, WebView retained, String marker) throws Exception {
        assertSame("Back must preserve the browser tab", tab, main(() -> tabs(browser).current()));
        assertSame("Back without reload must restore B's original WebView", retained, main(() -> tab.webView));
        JSONArray state = new JSONArray(javascript(tab,
                "[window.__siteNavigationMemory&&window.__siteNavigationMemory.token,"
                        + "document.getElementById('retained-value')&&document.getElementById('retained-value').value]"));
        assertEquals("Back without reload must retain the JavaScript heap", marker, state.getString(0));
        assertEquals("Back without reload must retain unsaved DOM input", marker, state.getString(1));
    }

    private void tapToolbar(BrowserActivity browser, int id) throws Exception {
        instrumentation.waitForIdleSync();
        float[] screen = main(() -> {
            View button = browser.findViewById(id);
            Rect rectangle = new Rect();
            assertTrue("The actual navigation toolbar button must be visible", button.getGlobalVisibleRect(rectangle));
            touchEvidence += "\nToolbar touch: id=" + browser.getResources().getResourceEntryName(id)
                    + " rect=" + rectangle + " alpha=" + button.getAlpha();
            return new float[]{rectangle.exactCenterX(), rectangle.exactCenterY()};
        });
        tapScreen(screen[0], screen[1]);
        instrumentation.waitForIdleSync();
    }

    private void tapLink(TabManager.Tab tab) throws Exception {
        CompletableFuture<Void> visible = new CompletableFuture<>();
        main(() -> {
            tab.webView.postVisualStateCallback(SystemClock.uptimeMillis(), new WebView.VisualStateCallback() {
                @Override public void onComplete(long requestId) {
                    // The callback guarantees the next draw has this DOM; wait for that draw before touching it.
                    tab.webView.postOnAnimation(() -> tab.webView.postOnAnimation(() -> visible.complete(null)));
                }
            });
            return null;
        });
        visible.get(5, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
        String dom = javascript(tab, "(function(){var a=document.getElementById('next'),r=a.getBoundingClientRect();"
                + "var x=r.left+r.width/2,y=r.top+r.height/2,h=document.elementFromPoint(x,y);"
                + "window.__siteNavigationTouches=[];['pointerdown','pointerup','touchstart','touchend','click'].forEach(function(type){"
                + "document.addEventListener(type,function(e){var p=(e.changedTouches&&e.changedTouches[0])||e;"
                + "var event={type:e.type,target:e.target.id,x:p.clientX,y:p.clientY,trusted:e.isTrusted,prevented:e.defaultPrevented};"
                + "window.__siteNavigationTouches.push(event);Promise.resolve().then(function(){event.prevented=e.defaultPrevented;});"
                + "},{capture:true,passive:true});});"
                + "return {rect:{left:r.left,top:r.top,width:r.width,height:r.height},x:x,y:y,hit:h&&h.id,"
                + "href:a.href,viewport:{width:innerWidth,height:innerHeight,scale:visualViewport&&visualViewport.scale},"
                + "dpr:devicePixelRatio,scrollX:scrollX,scrollY:scrollY};})()");
        JSONObject point = new JSONObject(dom);
        touchEvidence = "Link DOM before touch: " + dom;
        assertEquals("The chosen CSS point must hit the real link before injecting a touch", "next", point.getString("hit"));
        float[] screen = main(() -> {
            int[] location = new int[2];
            tab.webView.getLocationOnScreen(location);
            Rect visibleRect = new Rect();
            boolean shown = tab.webView.getGlobalVisibleRect(visibleRect);
            float scale = tab.webView.getScale();
            float x = location[0] + (float) point.getDouble("x") * scale;
            float y = location[1] + (float) point.getDouble("y") * scale;
            touchEvidence += "\nWebView: location=" + location[0] + "," + location[1]
                    + " size=" + tab.webView.getWidth() + "x" + tab.webView.getHeight() + " scale=" + scale
                    + " visible=" + shown + " rect=" + visibleRect + " windowFocus=" + tab.webView.hasWindowFocus()
                    + " touch=" + x + "," + y;
            assertTrue("The touch must be inside the visible WebView", shown && visibleRect.contains((int) x, (int) y));
            return new float[]{x, y};
        });
        tapScreen(screen[0], screen[1]);
    }

    private void tapScreen(float x, float y) {
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            instrumentation.sendPointerSync(down);
            SystemClock.sleep(60);
            MotionEvent up = MotionEvent.obtain(time, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try { instrumentation.sendPointerSync(up); } finally { up.recycle(); }
        } finally { down.recycle(); }
    }

    private String javascript(TabManager.Tab tab, String script) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        main(() -> { tab.webView.evaluateJavascript(script, result::complete); return null; });
        return result.get(5, TimeUnit.SECONDS);
    }

    private String failureEvidence(BrowserActivity browser) {
        StringBuilder evidence = new StringBuilder();
        if (browser != null) try {
            TabManager.Tab tab = main(() -> tabs(browser).current());
            evidence.append("Current page: ").append(main(() -> "tabUrl=" + tab.url + " webUrl=" + tab.webView.getUrl()
                    + " title=" + tab.webView.getTitle() + " progress=" + tab.webView.getProgress()
                    + " progressBar=" + browser.findViewById(R.id.browser_progress).getVisibility()
                    + " UA=" + tab.webView.getSettings().getUserAgentString()));
            evidence.append("\nDOM after failure: ").append(javascript(tab,
                    "({url:location.href,ready:document.readyState,title:document.title,"
                            + "touches:window.__siteNavigationTouches||[],link:document.getElementById('next')&&document.getElementById('next').href})"));
        } catch (Exception error) { evidence.append("\nPage diagnostics failed: ").append(error); }
        Bitmap screenshot = null;
        try {
            screenshot = instrumentation.getUiAutomation().takeScreenshot();
            if (screenshot == null) throw new IllegalStateException("UiAutomation returned no screenshot");
            File directory = instrumentation.getTargetContext().getExternalFilesDir(null);
            if (directory == null) directory = instrumentation.getTargetContext().getFilesDir();
            File file = new File(directory, "browser-site-navigation-failure-" + SystemClock.uptimeMillis() + ".png");
            try (FileOutputStream output = new FileOutputStream(file)) {
                if (!screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("PNG compression failed");
            }
            evidence.append("\nFailure screenshot: ").append(file.getAbsolutePath());
        } catch (Exception error) { evidence.append("\nScreenshot failed: ").append(error); }
        finally { if (screenshot != null) screenshot.recycle(); }
        return evidence.toString();
    }

    private void awaitPage(BrowserActivity browser, TabManager.Tab tab, String url, String title, boolean foreground) throws Exception {
        String pageTitle = title + ":" + Uri.parse(url).getLastPathSegment();
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            if (main(() -> url.equals(tab.url) && url.equals(tab.webView.getUrl())
                    && pageTitle.equals(tab.webView.getTitle()) && tab.webView.getProgress() == 100
                    && (!foreground || browser.findViewById(R.id.browser_progress).getVisibility() == View.GONE))) {
                instrumentation.waitForIdleSync();
                return;
            }
            Thread.sleep(25);
        }
        fail("The real BrowserActivity navigation did not finish: " + url);
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

    private static final class FixtureServer implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService connections = Executors.newFixedThreadPool(4);
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final Map<String, CopyOnWriteArrayList<CapturedRequest>> requests = new ConcurrentHashMap<>();
        private final Map<String, String> links = new ConcurrentHashMap<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private final String fixture;
        private final Thread accepting;

        FixtureServer(String fixture) throws Exception {
            this.fixture = fixture;
            listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            accepting = new Thread(() -> {
                while (!listener.isClosed()) try {
                    Socket socket = listener.accept();
                    sockets.add(socket);
                    connections.execute(() -> serve(socket));
                } catch (Throwable error) {
                    if (!listener.isClosed()) failure.compareAndSet(null, error);
                }
            }, "site-navigation-fixture");
            accepting.start();
        }

        String page(String host, String name, List<String> urls) {
            String path = "/" + fixture + "/" + name;
            requests.put(path, new CopyOnWriteArrayList<>());
            String url = "http://" + host + ":" + listener.getLocalPort() + path;
            urls.add(url);
            return url;
        }

        void linkFrom(String source, String target) { links.put(Uri.parse(source).getPath(), target); }

        void assertSingleRequest(String url, String ua, String reason) {
            List<String> values = new ArrayList<>();
            for (CapturedRequest request : requests.get(Uri.parse(url).getPath())) {
                assertEquals(reason + "; main-document method", "GET", request.method);
                values.add(request.userAgent);
            }
            assertEquals(reason + "; observed request User-Agent values",
                    Collections.singletonList(ua), values);
        }

        void assertSinglePageFetch(String url, String ua, String reason) {
            List<String> values = new ArrayList<>();
            for (CapturedRequest request : requests.get(Uri.parse(url).getPath())) {
                if (request.fixtureHeader.isEmpty()) continue;
                assertEquals(reason + "; a marked fetch must belong to this test", fixture, request.fixtureHeader);
                values.add(request.method + " " + request.userAgent);
            }
            assertEquals(reason + "; marked fetch method and User-Agent must appear exactly once",
                    Collections.singletonList("GET " + ua), values);
        }

        List<CapturedRequest> unmarkedRequests(String url) {
            List<CapturedRequest> result = new ArrayList<>();
            for (CapturedRequest request : requests.get(Uri.parse(url).getPath())) {
                if (request.fixtureHeader.isEmpty()) result.add(request);
            }
            return result;
        }

        private void serve(Socket socket) {
            try (Socket client = socket) {
                client.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                String first = reader.readLine();
                if (first == null) return;
                String ua = "", fixtureHeader = "", line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("User-Agent")) ua = line.substring(colon + 1).trim();
                    if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(FETCH_FIXTURE_HEADER)) fixtureHeader = line.substring(colon + 1).trim();
                }
                String[] requestLine = first.split(" ");
                String path = requestLine[1];
                List<CapturedRequest> received = requests.get(path);
                if (received != null) received.add(new CapturedRequest(requestLine[0], ua, fixtureHeader));
                String target = links.get(path);
                boolean manifest = received != null && path.endsWith(".m3u8");
                byte[] body = (manifest ? "#EXTM3U\n#EXT-X-ENDLIST\n" : "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<link rel='icon' href='data:,'><title>" + fixture + ":" + Uri.parse(path).getLastPathSegment() + "</title></head><body>"
                        + (target == null ? "Site navigation fixture" : "<a id='next' style='display:block;margin:24px;padding:32px' href='"
                        + target + "'>Navigate to site A</a>") + "</body></html>").getBytes(StandardCharsets.UTF_8);
                client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: "
                        + (manifest ? "application/vnd.apple.mpegurl" : "text/html; charset=utf-8") + "\r\n"
                        + "Cache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().write(body);
                client.getOutputStream().flush();
            } catch (Throwable error) {
                if (!listener.isClosed()) failure.compareAndSet(null, error);
            } finally { sockets.remove(socket); }
        }

        @Override public void close() throws Exception {
            listener.close();
            accepting.join(3000);
            for (Socket socket : sockets) socket.close();
            connections.shutdownNow();
            assertFalse("HTTP accept loop must stop", accepting.isAlive());
            assertTrue("All HTTP connection workers must stop", connections.awaitTermination(4, TimeUnit.SECONDS));
        }

        private static final class CapturedRequest {
            final String method;
            final String userAgent;
            final String fixtureHeader;

            CapturedRequest(String method, String userAgent, String fixtureHeader) {
                this.method = method;
                this.userAgent = userAgent;
                this.fixtureHeader = fixtureHeader;
            }

            @Override public String toString() {
                return "{method=" + method + ", ua=" + userAgent + ", fixture="
                        + (fixtureHeader.isEmpty() ? "<absent>" : fixtureHeader) + "}";
            }
        }
    }
}
