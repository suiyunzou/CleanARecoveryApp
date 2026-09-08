package com.example.cleanrecovery.ui.browser;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.activity.BrowserActivity;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserSiteImagesNavigationTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void nativeWebViewObservesViaSettingsOrderWhenImagesBecomeAllowed() throws Exception {
        String fixture = "__native_site_images_" + UUID.randomUUID();
        AtomicReference<String> finishedUrl = new AtomicReference<>();
        try (ImageServer server = new ImageServer(fixture)) {
            WebView web = main(() -> {
                WebView created = new WebView(instrumentation.getTargetContext());
                created.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) { finishedUrl.set(url); }
                });
                created.getSettings().setUseWideViewPort(true);
                created.getSettings().setLoadWithOverviewMode(true);
                return created;
            });
            try {
                String blocked = pageUrl(server, "localhost", "blocked", new ArrayList<>());
                String allowed = pageUrl(server, "127.0.0.1", "allowed", new ArrayList<>());
                String mobileUa = main(() -> WebSettings.getDefaultUserAgent(instrumentation.getTargetContext()));
                String desktopUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36";
                main(() -> {
                    applyNativeSiteSettings(web.getSettings(), desktopUa, false);
                    web.loadUrl(blocked);
                    return null;
                });
                awaitNativePage(web, finishedUrl, blocked, fixture + ":blocked");
                JSONArray blockedState = nativeImageState(web);
                int blockedMainBefore = server.count(blocked);
                int blockedImageBefore = server.count(blocked + ".png");
                assertEquals("The native control must actually start with image requests blocked", 0, blockedImageBefore);
                assertEquals(0, blockedState.getInt(0));
                assertEquals(0, blockedState.getInt(1));

                main(() -> {
                    applyNativeSiteSettings(web.getSettings(), mobileUa, true);
                    web.loadUrl(allowed);
                    return null;
                });
                awaitNativePage(web, finishedUrl, allowed, fixture + ":allowed");
                JSONArray allowedState = nativeImageState(web);
                Bundle evidence = new Bundle();
                evidence.putString("stream", "\nNative WebView UA->JS->loadsImages->blockNetworkImage->textZoom evidence: "
                        + "A before navigation main=" + blockedMainBefore + " image=" + blockedImageBefore + " DOM=" + blockedState
                        + "; after B main(A)=" + server.count(blocked) + " image(A)=" + server.count(blocked + ".png")
                        + " main(B)=" + server.count(allowed) + " image(B)=" + server.count(allowed + ".png")
                        + " DOM(B)=" + allowedState + "\n");
                instrumentation.sendStatus(0, evidence);
                assertEquals("The allowed destination image must complete its actual load event", 1, allowedState.getInt(0));
                assertEquals(1, allowedState.getInt(1));
                assertEquals(1, allowedState.getInt(2));
                assertEquals("complete", allowedState.getString(3));
                assertEquals("The destination image must be fetched exactly once by the native control", 1, server.count(allowed + ".png"));
                // Old-document requests are observations until Via itself is dynamically compared.
                assertNull("The native control HTTP fixture must not fail", server.failure.get());
            } finally {
                main(() -> { web.stopLoading(); web.destroy(); return null; });
                instrumentation.waitForIdleSync();
            }
        }
    }

    private void applyNativeSiteSettings(WebSettings settings, String ua, boolean images) {
        // Via g8/i.M site branch: preserve the ordering of these public WebSettings calls.
        // This control does not emulate Via's separate UA client-hints metadata helper.
        settings.setUserAgentString(ua);
        settings.setJavaScriptEnabled(true);
        settings.setLoadsImagesAutomatically(images);
        settings.setBlockNetworkImage(!images);
        settings.setTextZoom(100);
    }

    private void awaitNativePage(WebView web, AtomicReference<String> finishedUrl, String url, String title) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            if (main(() -> url.equals(finishedUrl.get()) && url.equals(web.getUrl())
                    && title.equals(web.getTitle()) && web.getProgress() == 100)) {
                instrumentation.waitForIdleSync();
                return;
            }
            Thread.sleep(25);
        }
        fail("The native WebView control did not finish " + url);
    }

    private JSONArray nativeImageState(WebView web) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        main(() -> {
            web.evaluateJavascript("[window.fixtureImageLoads,document.getElementById('fixture-image').naturalWidth,"
                    + "document.getElementById('fixture-image').naturalHeight,document.readyState]", result::complete);
            return null;
        });
        return new JSONArray(result.get(5, TimeUnit.SECONDS));
    }

    @Test public void addressNavigationAppliesImageScopeBeforeLoadingTheDestination() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String, Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        BrowserDatabaseHelper database = BrowserDatabaseHelper.getInstance(instrumentation.getTargetContext());
        List<String> fixtureUrls = new ArrayList<>();
        BrowserActivity activity = null;
        String fixture = "__site_images_navigation_" + UUID.randomUUID();
        try (ImageServer server = new ImageServer(fixture)) {
            try {
                prefs.setRestoreTabs(0);
                prefs.setExitClearFlags(Collections.emptySet());
                prefs.setClearDataOnExit(false);
                prefs.setScriptsEnabled(false);
                prefs.setAdBlockEnabled(false);
                prefs.setJsEnabled(true);
                prefs.setImagesEnabled(true);
                prefs.setDesktopMode(false);
                for (String host : new String[]{"localhost:" + server.socket.getLocalPort(), "127.0.0.1:" + server.socket.getLocalPort()}) {
                    prefs.resetSiteSettings(host);
                    prefs.setSiteSettingsEnabled(host, true);
                    prefs.setSiteJsMode(host, 1);
                    prefs.setSiteAdBlockMode(host, 0);
                    prefs.setSiteCookiesOff(host, false);
                }
                prefs.setSiteImagesMode("localhost:" + server.socket.getLocalPort(), 0);
                prefs.setSiteDesktopMode("localhost:" + server.socket.getLocalPort(), 1);
                prefs.setSiteImagesMode("127.0.0.1:" + server.socket.getLocalPort(), 1);
                prefs.setSiteDesktopMode("127.0.0.1:" + server.socket.getLocalPort(), 0);

                String blocked = pageUrl(server, "localhost", "blocked-first", fixtureUrls);
                String allowed = pageUrl(server, "127.0.0.1", "allowed", fixtureUrls);
                String blockedReturn = pageUrl(server, "localhost", "blocked-return", fixtureUrls);
                activity = (BrowserActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(blocked))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
                BrowserActivity browser = activity;
                TabManager.Tab tab = main(() -> tabs(browser).current());
                awaitPage(browser, tab, blocked, fixture + ":blocked-first");
                assertImageState(tab, false);
                assertEquals(1, server.count(blocked));
                assertEquals("A site's image prohibition must prevent the request itself", 0, server.count(blocked + ".png"));

                submitAddress(browser, allowed);
                awaitPage(browser, tab, allowed, fixture + ":allowed");
                assertImageState(tab, true);
                assertEquals("Leaving the blocked site must request the newly allowed image exactly once", 1, server.count(allowed + ".png"));
                assertEquals("Changing site settings must not reload the previous document", 1, server.count(blocked));
                assertEquals("The destination document must only be requested once", 1, server.count(allowed));
                Bundle evidence = new Bundle();
                evidence.putString("stream", "\nBrowser image scope after leaving A: old image(A)="
                        + server.count(blocked + ".png") + "; image(B)=" + server.count(allowed + ".png") + "\n");
                instrumentation.sendStatus(0, evidence);

                submitAddress(browser, blockedReturn);
                awaitPage(browser, tab, blockedReturn, fixture + ":blocked-return");
                assertImageState(tab, false);
                assertEquals(1, server.count(blockedReturn));
                assertEquals("Returning to the restricted site must block a fresh uncached image", 0, server.count(blockedReturn + ".png"));
                assertEquals("The permitted image must not be re-requested during the next navigation", 1, server.count(allowed + ".png"));
                // The native control also releases old blocked images when enabling images for B.
                // Keep that observation; zero old-document requests is not an established Via contract.
                assertNull("The local HTTP fixture must not fail", server.failure.get());
            } finally {
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
            for (String url : fixtureUrls) database.getWritableDatabase().delete(
                    BrowserDatabaseHelper.TABLE_HISTORY, "url=?", new String[]{url});
            restore(storage, original);
            assertEquals("All browser and session preferences must be restored", original, snapshot(storage));
        }
    }

    private String pageUrl(ImageServer server, String host, String page, List<String> urls) {
        String url = "http://" + host + ":" + server.socket.getLocalPort() + "/" + server.fixture + "/" + page;
        urls.add(url);
        return url;
    }

    private void submitAddress(BrowserActivity browser, String url) throws Exception {
        main(() -> {
            EditText input = browser.findViewById(R.id.browser_url_input);
            input.setText(url);
            input.onEditorAction(EditorInfo.IME_ACTION_GO);
            return null;
        });
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

    private void assertImageState(TabManager.Tab tab, boolean allowed) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        main(() -> {
            assertEquals("The image setting must follow the destination's explicit site override", allowed,
                    tab.webView.getSettings().getLoadsImagesAutomatically());
            assertEquals(!allowed, tab.webView.getSettings().getBlockNetworkImage());
            // Via enables both viewport flags in either UA mode; this does not claim page layout parity.
            assertTrue("Desktop mode must not disable Via's wide viewport configuration", tab.webView.getSettings().getUseWideViewPort());
            assertTrue("Desktop mode must not disable Via's overview configuration", tab.webView.getSettings().getLoadWithOverviewMode());
            tab.webView.evaluateJavascript("[window.fixtureImageLoads,document.getElementById('fixture-image').naturalWidth,"
                    + "document.getElementById('fixture-image').naturalHeight,document.readyState]", result::complete);
            return null;
        });
        JSONArray state = new JSONArray(result.get(5, TimeUnit.SECONDS));
        assertEquals("The page must finish before its image result is judged", "complete", state.getString(3));
        assertEquals("Image policy must control the actual HTML image load event", allowed ? 1 : 0, state.getInt(0));
        assertEquals("Allowed bytes must decode into the image, while blocked images have no intrinsic size", allowed ? 1 : 0, state.getInt(1));
        assertEquals(allowed ? 1 : 0, state.getInt(2));
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

    private static final class ImageServer implements AutoCloseable {
        final String fixture;
        final ServerSocket socket;
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
        final ExecutorService workers = Executors.newCachedThreadPool();
        final Thread accepting;
        final byte[] png = Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGMQCVgAAAGAAQUSaopEAAAAAElFTkSuQmCC", Base64.DEFAULT);

        ImageServer(String fixture) throws Exception {
            this.fixture = fixture;
            socket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            accepting = new Thread(() -> {
                while (!socket.isClosed()) try {
                    Socket client = socket.accept();
                    workers.execute(() -> serve(client));
                } catch (Throwable error) {
                    if (!socket.isClosed()) failure.compareAndSet(null, error);
                }
            }, "site-images-fixture");
            accepting.start();
        }

        void serve(Socket client) {
            try (Socket connection = client) {
                connection.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
                String first = reader.readLine(), line, host = "";
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    if (line.regionMatches(true, 0, "Host:", 0, 5)) host = line.substring(5).trim();
                }
                if (first == null) return;
                String path = first.split(" ")[1];
                requests.computeIfAbsent("http://" + host + path, ignored -> new AtomicInteger()).incrementAndGet();
                boolean fixturePath = path.startsWith("/" + fixture + "/");
                boolean image = fixturePath && path.endsWith(".png");
                String page = path.substring(path.lastIndexOf('/') + 1);
                byte[] body = image ? png : fixturePath ? ("<!doctype html><html><head><title>" + fixture + ":" + page
                        + "</title><link rel='icon' href='data:,'><script>window.fixtureImageLoads=0;</script></head><body>"
                        + "<img id='fixture-image' src='" + path + ".png' onload='window.fixtureImageLoads++'>"
                        + "</body></html>").getBytes(StandardCharsets.UTF_8) : new byte[0];
                connection.getOutputStream().write(((fixturePath ? "HTTP/1.1 200 OK" : "HTTP/1.1 404 Not Found")
                        + "\r\nContent-Type: " + (image ? "image/png" : "text/html; charset=utf-8")
                        + "\r\nCache-Control: no-store\r\nContent-Length: " + body.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                connection.getOutputStream().write(body);
                connection.getOutputStream().flush();
            } catch (Throwable error) {
                if (!socket.isClosed()) failure.compareAndSet(null, error);
            }
        }

        int count(String url) {
            AtomicInteger value = requests.get(url);
            return value == null ? 0 : value.get();
        }

        @Override public void close() throws Exception {
            socket.close();
            accepting.join(3000);
            workers.shutdown();
            assertTrue("All local HTTP responses must finish", workers.awaitTermination(5, TimeUnit.SECONDS));
            assertFalse("The local HTTP accept loop must stop", accepting.isAlive());
        }
    }
}
