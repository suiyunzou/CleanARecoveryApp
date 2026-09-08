package com.example.cleanrecovery.ui.browser;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.CookieManager;
import android.webkit.WebView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.background.BackgroundDownloadService;
import com.example.cleanrecovery.background.DownloadTaskDbHelper;
import com.example.cleanrecovery.ui.activity.BrowserActivity;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class BrowserDownloadFlowTest {
    private final android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final String globalUa = "DownloadGlobal/1.0", siteUa = "DownloadSite/2.0";
    private BrowserActivity activity;
    private DownloadTaskDbHelper downloads;

    @Test public void discoveryCancelAndConfirmedDownloadsKeepOriginalBytesAndEntrySpecificHeaders() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String,Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        downloads = DownloadTaskDbHelper.getInstance(instrumentation.getTargetContext());
        assertTrue("Run without active user downloads so a temporary output directory cannot affect them", downloads.getRestorableTasks().isEmpty());
        String fixture = "download-flow-" + UUID.randomUUID();
        String cookieName = fixture.replace("-", "");
        boolean cookiePolicy = CookieManager.getInstance().acceptCookie();
        File directory = new File(instrumentation.getTargetContext().getExternalFilesDir(null), fixture);
        assertTrue(directory.mkdir());
        try (Server server = new Server(fixture)) {
            try {
                prefs.setRestoreTabs(0); prefs.setExitClearFlags(Collections.emptySet()); prefs.setClearDataOnExit(false);
                prefs.setScriptsEnabled(false); prefs.setAdBlockEnabled(false); prefs.setJsEnabled(true); prefs.setIncognitoMode(true);
                prefs.setCookiesEnabled(true); CookieManager.getInstance().setAcceptCookie(true);
                prefs.setDownloadManager(0); prefs.setDownloadDir(directory.getAbsolutePath());
                prefs.setDesktopMode(false); prefs.setSimpleUa(false); prefs.setUaSelectedId(0); prefs.setCustomUserAgent(globalUa);
                prefs.setSiteSettingsEnabled(server.authority, true); prefs.setSiteUserAgent(server.authority, siteUa);
                prefs.setSiteDesktopMode(server.authority, 0); prefs.setSiteJsMode(server.authority, 1);
                setCookie(server.base, cookieName + "=" + fixture + "; Path=/; Max-Age=600");
                activity = (BrowserActivity)instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(server.pageUrl))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
                awaitPage(server.pageUrl);
                WebView source = main(() -> tabs().current().webView);
                javascript(source, "document.querySelector('#discover').click();true");
                awaitJs(source, "window.fetched", "true");
                await(() -> main(() -> sniffer().mediaUrls(tabs().current().id).contains(server.manifestUrl)));
                assertEquals("Discovery has only the page's own fetch", 1, server.requests("/playlist.m3u8").size());
                assertEquals("The page fetch must use its site UA", siteUa, server.requests("/playlist.m3u8").get(0).get("user-agent"));
                assertEquals(0, taskCount(server.manifestUrl));

                main(() -> { invoke("openResourcePage", new Class[0]); return null; });
                TabManager.Tab resource = main(() -> tabs().current());
                await(() -> main(() -> resource.webView.getUrl() != null && resource.webView.getUrl().contains("appassets.androidplatform.net")));
                openDownloadMenu(resource, server.manifestUrl); node("文件大小：" + server.manifest.length + ".0 B"); click("取消");
                assertEquals("Cancelling the real filename dialog must not queue", 0, taskCount(server.manifestUrl));
                assertEquals("Metadata HEAD is allowed; cancelling must not perform another GET", 1, server.getRequests("/playlist.m3u8").size());
                openDownloadMenu(resource, server.manifestUrl); node("文件大小：" + server.manifest.length + ".0 B"); setFilename("chosen.m3u8");
                prefs.setCustomUserAgent("ChangedAfterPrompt/9.0"); click("确定");
                File manifest = awaitFile(server.manifestUrl);
                assertEquals("The edited manifest filename is preserved", "chosen.m3u8", manifest.getName());
                assertArrayEquals("Downloading m3u8 saves its exact text, not merged media", server.manifest, Files.readAllBytes(manifest.toPath()));
                assertEquals(2, server.getRequests("/playlist.m3u8").size());
                assertEquals("Both resource prompts fetch their missing metadata", 4, server.requests("/playlist.m3u8").size());
                for (Map<String,String> request : server.requests("/playlist.m3u8")) if ("HEAD".equals(request.get("method"))) {
                    assertEquals(server.manifestUrl, request.get("referer"));
                    assertEquals(cookieName + "=" + fixture, request.get("cookie"));
                }
                Map<String,String> manifestRequest = server.getRequests("/playlist.m3u8").get(1);
                assertEquals("Resource-menu download captures the global UA at dialog creation", globalUa, manifestRequest.get("user-agent"));
                assertNull("Via's resource context-menu request has no invented source Referer", manifestRequest.get("referer"));
                assertEquals(cookieName + "=" + fixture, manifestRequest.get("cookie"));
                assertTrue("Downloading the manifest must not follow its media segments", server.requests("/segment.ts").isEmpty());
                assertStoredRequest(server.manifestUrl, globalUa, null);

                main(() -> { invoke("loadUrlInCurrent", new Class[]{String.class}, server.pageUrl); return null; });
                awaitPage(server.pageUrl); WebView page = main(() -> tabs().current().webView);
                javascript(page, "document.querySelector('a').click();true");
                node("取消"); click("取消");
                assertEquals("The WebView download callback also requires confirmation", 0, taskCount(server.attachmentUrl));
                javascript(page, "document.querySelector('a').click();true"); node("取消"); setFilename("web-file.dat"); click("确定");
                File attachment = awaitFile(server.attachmentUrl);
                assertEquals("web-file.dat", attachment.getName()); assertArrayEquals(server.attachment, Files.readAllBytes(attachment.toPath()));
                List<Map<String,String>> attachmentRequests = server.requests("/attachment.dat");
                assertEquals("Two WebView probes plus one confirmed native download", 3, attachmentRequests.size());
                Map<String,String> nativeRequest = attachmentRequests.get(2);
                assertEquals(siteUa, nativeRequest.get("user-agent")); assertEquals(server.pageUrl, nativeRequest.get("referer"));
                assertEquals(cookieName + "=" + fixture, nativeRequest.get("cookie"));
                assertStoredRequest(server.attachmentUrl, siteUa, server.pageUrl);
            } finally {
                try {
                    if (activity != null) { main(() -> { activity.finish(); return null; }); await(() -> main(activity::isDestroyed)); }
                    settle(server.manifestUrl, server.attachmentUrl);
                    instrumentation.getTargetContext().stopService(new Intent(instrumentation.getTargetContext(), BackgroundDownloadService.class));
                    for (String url : new String[]{server.manifestUrl, server.attachmentUrl}) {
                        downloads.getWritableDatabase().delete("tasks", "url=?", new String[]{url}); assertEquals(0, taskCount(url));
                    }
                    BrowserDatabaseHelper database = BrowserDatabaseHelper.getInstance(instrumentation.getTargetContext());
                    for (String url : new String[]{server.pageUrl, server.manifestUrl, server.attachmentUrl})
                        database.getWritableDatabase().delete(BrowserDatabaseHelper.TABLE_HISTORY, "url=?", new String[]{url});
                } finally {
                    try { setCookie(server.base, cookieName + "=; Path=/; Max-Age=0"); }
                    finally { CookieManager.getInstance().setAcceptCookie(cookiePolicy); restore(storage, original); }
                }
            }
        } finally {
            for (File file : Objects.requireNonNull(directory.listFiles())) {
                assertEquals("Delete only this test's flat output files", directory.getCanonicalPath(), file.getCanonicalFile().getParent());
                assertTrue(file.isFile()); assertTrue(file.delete());
            }
            assertTrue(directory.delete());
        }
    }

    private void assertStoredRequest(String url, String ua, String referer) throws Exception {
        try (Cursor c = downloads.getReadableDatabase().rawQuery("SELECT raw_resource, request_headers FROM tasks WHERE url=?", new String[]{url})) {
            assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)); org.json.JSONObject headers = new org.json.JSONObject(c.getString(1));
            assertEquals(ua, headers.getString("User-Agent"));
            if (referer == null) assertFalse(headers.has("Referer")); else assertEquals(referer, headers.getString("Referer"));
            assertFalse("Cookie is resolved for each request, not persisted as a stale header", headers.has("Cookie"));
        }
    }
    private File awaitFile(String url) throws Exception {
        await(() -> taskCount(url) == 1);
        settle(url);
        try (Cursor c = downloads.getReadableDatabase().rawQuery("SELECT status,result_path,error_message FROM tasks WHERE url=?", new String[]{url})) {
            assertTrue("A confirmed download must create a persisted task", c.moveToFirst());
            assertEquals("Download failed: " + c.getString(2), "COMPLETED", c.getString(0)); return new File(c.getString(1));
        }
    }
    private void settle(String... urls) throws Exception {
        long end = System.currentTimeMillis() + 20000;
        while (true) {
            boolean pending = false;
            for (String url : urls) try (Cursor c = downloads.getReadableDatabase().rawQuery("SELECT status FROM tasks WHERE url=?", new String[]{url})) {
                if (c.moveToFirst()) pending |= "PENDING".equals(c.getString(0)) || "RUNNING".equals(c.getString(0));
            }
            if (!pending) return; assertTrue("Finish fixture requests before closing their server", System.currentTimeMillis() < end); Thread.sleep(30);
        }
    }
    private int taskCount(String url) { try (Cursor c = downloads.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM tasks WHERE url=?", new String[]{url})) { assertTrue(c.moveToFirst()); return c.getInt(0); } }
    private void openDownloadMenu(TabManager.Tab log, String url) throws Exception {
        main(() -> { invoke("showResourceMenu", new Class[]{TabManager.Tab.class, String.class, boolean.class}, log, url, false); return null; });
        click("下载"); node("取消");
    }
    private void setFilename(String name) throws Exception {
        AccessibilityNodeInfo input = input(instrumentation.getUiAutomation().getRootInActiveWindow()); assertNotNull(input);
        Bundle value = new Bundle(); value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, name);
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, value));
    }
    private AccessibilityNodeInfo input(AccessibilityNodeInfo root) {
        if (root == null) return null; if ("android.widget.EditText".contentEquals(root.getClassName())) return root;
        for (int i = 0; i < root.getChildCount(); i++) { AccessibilityNodeInfo match = input(root.getChild(i)); if (match != null) return match; } return null;
    }
    private AccessibilityNodeInfo node(String text) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) for (AccessibilityNodeInfo child : root.findAccessibilityNodeInfosByText(text))
                if (text.contentEquals(child.getText()) && child.isVisibleToUser()) return child;
            Thread.sleep(30);
        }
        throw new AssertionError("Missing actual dialog action " + text);
    }
    private void click(String text) throws Exception {
        node(text);
        AccessibilityNodeInfo target = null;
        for (AccessibilityNodeInfo candidate : instrumentation.getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(text)) {
            if (!text.contentEquals(candidate.getText()) || !candidate.isVisibleToUser()) continue;
            while (!candidate.isClickable() && candidate.getParent() != null) candidate = candidate.getParent();
            if (candidate.isClickable()) { target = candidate; break; }
        }
        assertNotNull("The matching dialog action must be clickable", target);
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK)); instrumentation.getUiAutomation().waitForIdle(100, 5000);
    }
    private void awaitPage(String url) throws Exception {
        await(() -> { WebView page = main(() -> tabs().current().webView); return url.equals(main(page::getUrl)) && "\"complete\"".equals(javascript(page, "document.readyState")); });
    }
    private void awaitJs(WebView page, String script, String expected) throws Exception { await(() -> expected.equals(javascript(page, script))); }
    private String javascript(WebView page, String script) throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>(); main(() -> { page.evaluateJavascript(script, result::complete); return null; }); return result.get(5, TimeUnit.SECONDS);
    }
    private TabManager tabs() throws Exception { Field field = BrowserActivity.class.getDeclaredField("tabs"); field.setAccessible(true); return (TabManager)field.get(activity); }
    private ViaSnifferStateMachine sniffer() throws Exception { Field field = BrowserActivity.class.getDeclaredField("viaSniffer"); field.setAccessible(true); return (ViaSnifferStateMachine)field.get(activity); }
    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception { Method method = BrowserActivity.class.getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(activity, args); }
    private <T> T main(Callable<T> action) throws Exception { FutureTask<T> result = new FutureTask<>(action); instrumentation.runOnMainSync(result); return result.get(); }
    private void await(Callable<Boolean> condition) throws Exception { long end = System.currentTimeMillis() + 10000; while (System.currentTimeMillis() < end) { if (condition.call()) return; Thread.sleep(30); } fail("Expected browser state did not arrive"); }
    private void setCookie(String url, String cookie) throws Exception { CompletableFuture<Boolean> result = new CompletableFuture<>(); main(() -> { CookieManager.getInstance().setCookie(url, cookie, result::complete); return null; }); assertTrue(result.get(5, TimeUnit.SECONDS)); CookieManager.getInstance().flush(); }
    private Map<String,Object> snapshot(SharedPreferences prefs) { Map<String,Object> values = new HashMap<>(); for (Map.Entry<String,?> entry : prefs.getAll().entrySet()) values.put(entry.getKey(), entry.getValue() instanceof Set ? new HashSet<>((Set<?>)entry.getValue()) : entry.getValue()); return values; }
    @SuppressWarnings("unchecked") private void restore(SharedPreferences prefs, Map<String,Object> values) {
        SharedPreferences.Editor edit = prefs.edit().clear(); for (Map.Entry<String,Object> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String) edit.putString(key, (String)value); else if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
            else if (value instanceof Integer) edit.putInt(key, (Integer)value); else if (value instanceof Long) edit.putLong(key, (Long)value);
            else if (value instanceof Float) edit.putFloat(key, (Float)value); else if (value instanceof Set) edit.putStringSet(key, new HashSet<>((Set<String>)value));
            else throw new AssertionError("Unsupported preference " + key);
        } assertTrue(edit.commit()); assertEquals(values, snapshot(prefs));
    }

    private static class Server implements AutoCloseable {
        final ServerSocket listener; final Thread worker; final String authority, base, pageUrl, manifestUrl, attachmentUrl;
        final byte[] manifest = "#EXTM3U\n#EXT-X-TARGETDURATION:1\n#EXTINF:1,\nsegment.ts\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8);
        final byte[] attachment = "raw attachment fixture\n".getBytes(StandardCharsets.UTF_8);
        final Map<String,List<Map<String,String>>> captured = new ConcurrentHashMap<>(); volatile Throwable failure;
        Server(String fixture) throws Exception {
            listener = new ServerSocket(0, 10, InetAddress.getByName("127.77.20.3")); authority = "127.77.20.3:" + listener.getLocalPort();
            base = "http://" + authority; pageUrl = base + "/" + fixture; manifestUrl = base + "/playlist.m3u8"; attachmentUrl = base + "/attachment.dat";
            worker = new Thread(() -> {
                while (!listener.isClosed()) try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(4000); BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = reader.readLine(); if (first == null) continue; String path = first.split(" ")[1];
                    Map<String,String> headers = new HashMap<>(); headers.put("method", first.split(" ")[0]); String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) { int colon = line.indexOf(':'); if (colon > 0) headers.put(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim()); }
                    captured.computeIfAbsent(path, key -> new CopyOnWriteArrayList<>()).add(headers);
                    byte[] body; String type, disposition = "";
                    if (path.equals("/playlist.m3u8")) { body = manifest; type = "application/vnd.apple.mpegurl"; }
                    else if (path.equals("/attachment.dat")) { body = attachment; type = "application/octet-stream"; disposition = "Content-Disposition: attachment; filename=server.dat\r\n"; }
                    else if (path.equals("/segment.ts")) { body = new byte[]{1,2,3}; type = "video/mp2t"; }
                    else { body = ("<!doctype html><html><head><link rel='icon' href='data:,'></head><body><a href='/attachment.dat'>Download</a><button id='discover'>Discover resource</button><script>window.fetched=false;document.querySelector('#discover').onclick=()=>fetch('/playlist.m3u8',{headers:{'X-Page-Probe':'1'}}).then(r=>r.text()).then(t=>window.fetched=t.startsWith('#EXTM3U'));</script></body></html>").getBytes(StandardCharsets.UTF_8); type = "text/html"; }
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\n" + disposition + "Cache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    if (!"HEAD".equals(headers.get("method"))) socket.getOutputStream().write(body); socket.getOutputStream().flush();
                } catch (Throwable error) { if (!listener.isClosed()) failure = error; }
            }, "browser-download-fixture"); worker.start();
        }
        List<Map<String,String>> requests(String path) { return captured.getOrDefault(path, Collections.emptyList()); }
        List<Map<String,String>> getRequests(String path) {
            List<Map<String,String>> result = new ArrayList<>();
            for (Map<String,String> request : requests(path)) if ("GET".equals(request.get("method"))) result.add(request);
            return result;
        }
        @Override public void close() throws Exception { listener.close(); worker.join(5000); assertFalse(worker.isAlive()); assertNull("Do not hide fixture server failures", failure); }
    }
}
