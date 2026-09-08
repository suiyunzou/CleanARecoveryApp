package com.example.cleanrecovery.ui.browser;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Base64;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.activity.BrowserActivity;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
public class BrowserSiteRedirectTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void redirectInheritsSourceRenderingWhileHistoryAndDirectNavigationUseTheDestination() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String, Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        BrowserDatabaseHelper database = BrowserDatabaseHelper.getInstance(instrumentation.getTargetContext());
        BrowserActivity activity = null;
        List<String> fixtureUrls = new ArrayList<>();
        String fixture = "__site_redirect_" + UUID.randomUUID();
        try (RedirectServer server = new RedirectServer(fixture, fixtureUrls)) {
            prefs.setRestoreTabs(0);
            prefs.setExitClearFlags(Collections.emptySet());
            prefs.setClearDataOnExit(false);
            prefs.setScriptsEnabled(false);
            prefs.setAdBlockEnabled(false);
            prefs.setIncognitoMode(false);
            prefs.setDesktopMode(false);
            prefs.setSimpleUa(false);
            prefs.setUaSelectedId(0);
            prefs.setCustomUserAgent("Global/1.0");
            prefs.setJsEnabled(true);
            prefs.setImagesEnabled(true);
            prefs.setTextZoom(100);
            prefs.setBackNoReload(false);
            prefs.setRedirectAskMode(0);
            prefs.resetSiteSettings("localhost:" + server.listener.getLocalPort());
            prefs.resetSiteSettings("127.0.0.1:" + server.listener.getLocalPort());
            prefs.setSiteSettingsEnabled("localhost:" + server.listener.getLocalPort(), true);
            prefs.setSiteUserAgent("localhost:" + server.listener.getLocalPort(), "Redirect-A/1.0");
            prefs.setSiteJsMode("localhost:" + server.listener.getLocalPort(), 1);
            prefs.setSiteImagesMode("localhost:" + server.listener.getLocalPort(), 0);
            prefs.setSiteDesktopMode("localhost:" + server.listener.getLocalPort(), 0);
            prefs.setSiteTextZoom("localhost:" + server.listener.getLocalPort(), 140);
            prefs.setSiteRedirectMode("localhost:" + server.listener.getLocalPort(), 0);
            prefs.setSiteIncognitoMode("localhost:" + server.listener.getLocalPort(), 1);
            prefs.setSiteBackNoReloadMode("localhost:" + server.listener.getLocalPort(), 0);
            prefs.setSiteBackNoReloadMode("127.0.0.1:" + server.listener.getLocalPort(), 0);
            prefs.setSiteSettingsEnabled("127.0.0.1:" + server.listener.getLocalPort(), false);
            try {
                activity = (BrowserActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(server.landing))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
                BrowserActivity browser = activity;
                TabManager.Tab tab = main(() -> tabs(browser).current());
                awaitPage(browser, tab, server.landing, fixture + ":landing");
                server.assertSingleRequest(server.landing, "Redirect-A/1.0");
                JSONObject source = pageSettings(tab);
                assertEquals("The redirect must begin from the configured site's real UA", "Redirect-A/1.0", source.getString("ua"));
                assertFalse("The source fixture must actually disable automatic image loading", source.getBoolean("images"));
                assertTrue("The source fixture must actually block network images", source.getBoolean("blockImages"));
                assertEquals("The source fixture must have a distinct site text zoom", 140, source.getInt("textZoom"));
                assertTrue(source.getBoolean("javascript"));
                assertEquals("The configured private source must not write ordinary history", 0, historyCount(database, server.landing));
                WebView sourceView = main(() -> tab.webView);
                assertEquals("The redirect source must already expose its loaded URL", server.landing, main(sourceView::getUrl));

                main(() -> {
                    Method method = BrowserActivity.class.getDeclaredMethod("loadUrlInCurrent", String.class);
                    method.setAccessible(true);
                    method.invoke(browser, server.redirect);
                    assertSame("With Back without reload disabled, address navigation must retain the loaded source WebView", sourceView, tab.webView);
                    return null;
                });
                awaitPage(browser, tab, server.destination, fixture + ":final");
                assertSame("A same-tab HTTP redirect must not open another browser tab", tab, main(() -> tabs(browser).current()));
                JSONObject destination = pageSettings(tab);
                JSONObject dom = pageDom(tab);
                Bundle evidence = new Bundle();
                evidence.putString("stream", "\nSITE_HTTP_REDIRECT_OBSERVATION: requests=" + server.requests
                        + "; source=" + source + "; destination=" + destination + "; DOM=" + dom + "\n");
                instrumentation.sendStatus(0, evidence);
                server.assertSingleRequest(server.landing, "Redirect-A/1.0");
                server.assertSingleRequest(server.redirect, "Redirect-A/1.0");
                server.assertSingleRequest(server.destination, "Redirect-A/1.0");
                assertEquals("A 302 destination without enabled site settings must retain the source UA", "Redirect-A/1.0", destination.getString("ua"));
                assertEquals("The redirected document must observe the inherited UA", "Redirect-A/1.0", dom.getString("ua"));
                assertFalse("Source rendering inheritance must retain images OFF throughout the redirected page", destination.getBoolean("images"));
                assertTrue(destination.getBoolean("blockImages"));
                assertEquals("The redirected page must retain source text zoom", 140, destination.getInt("textZoom"));
                assertEquals("A blocked destination image must not start a network request", 0, server.requestCount(server.imageUrl));
                assertEquals("A blocked destination image must not fire a load event", 0, dom.getInt("loads"));
                assertEquals("A blocked destination image must not decode", 0, dom.getInt("width"));
                assertTrue("The destination's real page script must execute", dom.getBoolean("scriptRan"));
                assertEquals("complete", dom.getString("ready"));
                assertTrue("Rendering inheritance must not copy source privacy: history follows the real destination URL",
                        historyCount(database, server.destination) > 0);
                assertEquals(0, historyCount(database, server.redirect));

                main(() -> {
                    Method method = BrowserActivity.class.getDeclaredMethod("loadUrlInCurrent", String.class);
                    method.setAccessible(true);
                    server.directNavigationStarted = true;
                    method.invoke(browser, server.direct);
                    return null;
                });
                awaitPage(browser, tab, server.direct, fixture + ":direct");
                JSONObject direct = pageSettings(tab);
                JSONObject directDom = pageDom(tab);
                Bundle directEvidence = new Bundle();
                directEvidence.putString("stream", "\nSITE_DIRECT_AFTER_REDIRECT: requests=" + server.requests
                        + "; settings=" + direct + "; DOM=" + directDom + "\n");
                instrumentation.sendStatus(0, directEvidence);
                server.assertSingleRequest(server.direct, "Global/1.0");
                server.assertSingleRequest(server.directImage, "Global/1.0");
                assertEquals("An explicit address navigation must resolve B's settings afresh", "Global/1.0", direct.getString("ua"));
                assertEquals("The direct document must observe the global UA", "Global/1.0", directDom.getString("ua"));
                assertTrue("The direct B page must use global images ON", direct.getBoolean("images"));
                assertFalse(direct.getBoolean("blockImages"));
                assertEquals("Source text zoom must not remain sticky after direct navigation", 100, direct.getInt("textZoom"));
                assertEquals("The direct page must actually load its image", 1, directDom.getInt("loads"));
                assertEquals(1, directDom.getInt("width"));
                assertEquals(1, directDom.getInt("height"));
                assertTrue("The direct B page must record real destination history", historyCount(database, server.direct) > 0);
                server.assertSingleRequest(server.landing, "Redirect-A/1.0");
                server.assertSingleRequest(server.redirect, "Redirect-A/1.0");
                server.assertSingleRequest(server.destination, "Redirect-A/1.0");
                assertNull("The local redirect fixture must not conceal server failures", server.failure.get());
            } catch (Exception | AssertionError error) {
                String page = activity == null ? "Activity was not created" : diagnostic(activity);
                throw new AssertionError(error.getMessage() + "\nRedirect fixture requests: " + server.requests + "\n" + page, error);
            } finally {
                if (activity != null) {
                    BrowserActivity browser = activity;
                    main(() -> { browser.finish(); return null; });
                    long end = SystemClock.uptimeMillis() + 5000;
                    while (!main(browser::isDestroyed) && SystemClock.uptimeMillis() < end) Thread.sleep(25);
                    assertTrue("Destroy must finish while exit clearing is disabled", main(browser::isDestroyed));
                    instrumentation.waitForIdleSync();
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

    private int historyCount(BrowserDatabaseHelper database, String url) {
        try (Cursor cursor = database.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM history WHERE url=?", new String[]{url})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    private JSONObject pageSettings(TabManager.Tab tab) throws Exception {
        return main(() -> {
            WebSettings settings = tab.webView.getSettings();
            return new JSONObject().put("tabUrl", tab.url).put("webUrl", tab.webView.getUrl())
                    .put("title", tab.webView.getTitle()).put("progress", tab.webView.getProgress())
                    .put("ua", settings.getUserAgentString()).put("javascript", settings.getJavaScriptEnabled())
                    .put("images", settings.getLoadsImagesAutomatically()).put("blockImages", settings.getBlockNetworkImage())
                    .put("textZoom", settings.getTextZoom());
        });
    }

    private JSONObject pageDom(TabManager.Tab tab) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        main(() -> {
            tab.webView.evaluateJavascript("(function(){var i=document.getElementById('redirect-image');return {"
                    + "url:location.href,ready:document.readyState,ua:navigator.userAgent,scriptRan:window.redirectScriptRan===true,"
                    + "loads:window.redirectImageLoads||0,errors:window.redirectImageErrors||0,width:i?i.naturalWidth:0,"
                    + "height:i?i.naturalHeight:0,imageComplete:i?i.complete:false};})()", result::complete);
            return null;
        });
        return new JSONObject(result.get(5, TimeUnit.SECONDS));
    }

    private String diagnostic(BrowserActivity browser) {
        try {
            TabManager.Tab tab = main(() -> tabs(browser).current());
            return "Current settings=" + pageSettings(tab) + "; DOM=" + pageDom(tab);
        } catch (Exception error) { return "Page diagnostics failed: " + error; }
    }

    private void awaitPage(BrowserActivity browser, TabManager.Tab tab, String url, String title) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            if (main(() -> url.equals(tab.url) && url.equals(tab.webView.getUrl()) && title.equals(tab.webView.getTitle())
                    && tab.webView.getProgress() == 100 && browser.findViewById(R.id.browser_progress).getVisibility() == View.GONE)) {
                instrumentation.waitForIdleSync();
                return;
            }
            Thread.sleep(25);
        }
        fail("The real redirect navigation did not finish: " + url);
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

    private static final class RedirectServer implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService connections = Executors.newFixedThreadPool(4);
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        private final Map<String, CopyOnWriteArrayList<String>> requests = new ConcurrentHashMap<>();
        private final List<String> navigationCancellations = new CopyOnWriteArrayList<>();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean directNavigationStarted;
        private final String fixture;
        private final String landing;
        private final String redirect;
        private final String destination;
        private final String direct;
        private final String imageUrl;
        private final String directImage;
        private final Thread accepting;

        RedirectServer(String fixture, List<String> fixtureUrls) throws Exception {
            this.fixture = fixture;
            listener = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
            landing = register("localhost", "landing", fixtureUrls);
            redirect = register("localhost", "redirect", fixtureUrls);
            destination = register("127.0.0.1", "final", fixtureUrls);
            direct = register("127.0.0.1", "direct", fixtureUrls);
            imageUrl = register("127.0.0.1", "image.png", fixtureUrls);
            directImage = register("127.0.0.1", "direct-image.png", fixtureUrls);
            accepting = new Thread(() -> {
                while (!listener.isClosed()) try {
                    Socket socket = listener.accept();
                    sockets.add(socket);
                    connections.execute(() -> serve(socket));
                } catch (Throwable error) {
                    if (!listener.isClosed()) failure.compareAndSet(null, error);
                }
            }, "site-redirect-fixture");
            accepting.start();
        }

        private String register(String host, String name, List<String> fixtureUrls) {
            String path = "/" + fixture + "/" + name;
            requests.put(path, new CopyOnWriteArrayList<>());
            String url = "http://" + host + ":" + listener.getLocalPort() + path;
            fixtureUrls.add(url);
            return url;
        }

        void assertSingleRequest(String url, String ua) {
            assertEquals("Each navigation must make only one request with its intended UA: " + url,
                    Collections.singletonList("GET " + ua), new ArrayList<>(requests.get(Uri.parse(url).getPath())));
        }

        int requestCount(String url) { return requests.get(Uri.parse(url).getPath()).size(); }

        private void serve(Socket socket) {
            String path = "<unread>", host = "", method = "<unread>", stage = "read-request-line";
            boolean completeRequest = false;
            try (Socket client = socket) {
                client.setSoTimeout(3000);
                BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                String first = reader.readLine();
                if (first == null) return;
                String[] requestLine = first.split(" ");
                method = requestLine[0];
                path = requestLine[1];
                stage = "read-request-headers";
                String ua = "", contentLength = "", transferEncoding = "", line;
                while ((line = reader.readLine()) != null && !line.isEmpty()) {
                    int colon = line.indexOf(':');
                    if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("User-Agent")) ua = line.substring(colon + 1).trim();
                    if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Host")) host = line.substring(colon + 1).trim();
                    if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length")) contentLength = line.substring(colon + 1).trim();
                    if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Transfer-Encoding")) transferEncoding = line.substring(colon + 1).trim();
                }
                completeRequest = line != null && "GET".equals(method) && transferEncoding.isEmpty()
                        && (contentLength.isEmpty() || "0".equals(contentLength));
                if (!completeRequest) throw new IOException("The fixture did not receive a complete bodyless GET request");
                stage = "build-response";
                List<String> received = requests.get(path);
                if (received != null) received.add(method + " " + ua);
                if (Uri.parse(redirect).getPath().equals(path)) {
                    stage = "write-response-headers";
                    client.getOutputStream().write(("HTTP/1.1 302 Found\r\nLocation: " + destination
                            + "\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                } else {
                    boolean isImage = Uri.parse(imageUrl).getPath().equals(path) || Uri.parse(directImage).getPath().equals(path);
                    boolean isDirect = Uri.parse(direct).getPath().equals(path);
                    boolean isDestination = Uri.parse(destination).getPath().equals(path) || isDirect;
                    byte[] body = isImage
                            ? Base64.decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7", Base64.DEFAULT)
                            : ("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                            + "<link rel='icon' href='data:,'><title>" + fixture + ":" + Uri.parse(path).getLastPathSegment()
                            + "</title><script>window.redirectScriptRan=true;window.redirectImageLoads=0;window.redirectImageErrors=0;</script>"
                            + "</head><body>HTTP redirect fixture" + (isDestination ? "<img id='redirect-image' src='" + (isDirect ? directImage : imageUrl)
                            + "' onload='window.redirectImageLoads++' onerror='window.redirectImageErrors++'>" : "")
                            + "</body></html>").getBytes(StandardCharsets.UTF_8);
                    stage = "write-response-headers";
                    client.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: "
                            + (isImage ? "image/gif" : "text/html; charset=utf-8")
                            + "\r\nCache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    stage = "write-response-body";
                    client.getOutputStream().write(body);
                }
                stage = "flush-response";
                client.getOutputStream().flush();
                stage = "close-completed-response";
            } catch (Throwable error) {
                if (!listener.isClosed()) {
                    String detail = "method=" + method + " host=" + host + " path=" + path + " stage=" + stage
                            + " completeRequest=" + completeRequest + " directNavigationStarted=" + directNavigationStarted
                            + " error=" + error;
                    String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
                    boolean writingResponse = "write-response-headers".equals(stage) || "write-response-body".equals(stage)
                            || "flush-response".equals(stage);
                    boolean oldImageCancelled = directNavigationStarted && completeRequest && writingResponse
                            && Uri.parse(imageUrl).getAuthority().equalsIgnoreCase(host)
                            && Uri.parse(imageUrl).getPath().equals(path) && error instanceof SocketException
                            && (message.contains("broken pipe") || message.contains("connection reset"));
                    if (oldImageCancelled) navigationCancellations.add(detail);
                    else failure.compareAndSet(null, new IOException(detail, error));
                }
            } finally { sockets.remove(socket); }
        }

        @Override public void close() throws Exception {
            listener.close();
            accepting.join(3000);
            for (Socket socket : sockets) socket.close();
            connections.shutdownNow();
            assertFalse("HTTP redirect accept loop must stop", accepting.isAlive());
            assertTrue("All redirect fixture connection workers must stop", connections.awaitTermination(4, TimeUnit.SECONDS));
            Bundle evidence = new Bundle();
            evidence.putString("stream", "\nSITE_REDIRECT_HTTP_SERVER: observed old-image response cancellations="
                    + navigationCancellations + "; unexpectedFailure=" + failure.get() + "\n");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence);
            assertNull("All other paths, request-read stages and server errors remain failures", failure.get());
        }
    }
}
