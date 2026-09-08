package com.example.cleanrecovery.ui.browser;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.BrowserActivity;
import com.example.cleanrecovery.ui.activity.BrowserSiteSettingsActivity;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserSiteAuthorityTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void authorityKeysKeepSchemesSharedAndPortsAndSubdomainsSeparate() {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String,Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        String host = UUID.randomUUID() + ".test";
        try {
            prefs.setIncognitoMode(false);
            prefs.setSiteSettingsEnabled(host, true); prefs.setSiteIncognitoMode(host, 1);
            assertTrue("A stored bare-host choice remains valid for its ordinary URL", prefs.effectiveIncognito("https://" + host + "/watch"));
            assertTrue("Via shares an authority across HTTP and HTTPS", prefs.effectiveIncognito("http://" + host + "/other"));
            assertFalse("A bare-host rule must not leak into an explicit port", prefs.effectiveIncognito("https://" + host + ":8443/watch"));
            assertFalse("A parent-site rule must not implicitly cover a subdomain", prefs.effectiveIncognito("https://child." + host + "/watch"));
            assertEquals("An IPv6 port must survive the same extraction", "[::1]:8443", BrowserPrefs.siteKey("https://[::1]:8443/watch"));
            assertEquals("Via keeps the authority spelling, rather than normalizing a configuration key", "EXAMPLE.test:443", BrowserPrefs.siteKey("https://EXAMPLE.test:443/watch"));
            assertEquals("Internal URLs have no network site key", "", BrowserPrefs.siteKey("about:blank"));
            assertEquals("", BrowserPrefs.siteKey(null));
        } finally { restore(storage, original); assertEquals(original, snapshot(storage)); }
    }

    @Test public void permissionAndAdBlockConsumersUseTheSamePortSpecificRule() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String,Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        String host = UUID.randomUUID() + ".test", a = host + ":8443", b = host + ":9443";
        try {
            prefs.setAdBlockEnabled(true); prefs.setPermission("camera", "allow"); prefs.setPermission("location", "allow");
            prefs.setSiteSettingsEnabled(a, true); prefs.setSiteAdBlockMode(a, 0);
            prefs.setSitePermissionMode("camera", a, 2); prefs.setSitePermissionMode("location", a, 2);
            BrowserAdBlocker blocker = new BrowserAdBlocker(prefs);
            blocker.setPageUrl("https://" + a + "/watch");
            assertFalse("Site-off applies to the selected port", blocker.navigationGuardActive());
            blocker.setPageUrl("https://" + b + "/watch");
            assertTrue("The other port keeps global ad protection", blocker.navigationGuardActive());
            main(() -> {
                Activity androidPermissionsGranted = new Activity() {
                    { attachBaseContext(instrumentation.getTargetContext()); }
                    @Override public int checkSelfPermission(String permission) { return android.content.pm.PackageManager.PERMISSION_GRANTED; }
                };
                BrowserPermissionController controller = new BrowserPermissionController(androidPermissionsGranted, prefs);
                try {
                    RecordedPermission blocked = new RecordedPermission("https://" + a + "/");
                    RecordedPermission allowed = new RecordedPermission("https://" + b + "/");
                    controller.request(blocked); controller.request(allowed);
                    assertEquals("Camera denial must apply to the exact request origin", "deny", blocked.result);
                    assertEquals("The other port must reach the existing Android-granted branch", "grant", allowed.result);
                    List<Boolean> locations = new ArrayList<>();
                    controller.requestLocation("https://" + a + "/", (origin, allow, retain) -> locations.add(allow));
                    controller.requestLocation("https://" + b + "/", (origin, allow, retain) -> locations.add(allow));
                    assertEquals(java.util.Arrays.asList(false, true), locations);
                } finally { controller.destroy(); }
                return null;
            });
        } finally { restore(storage, original); assertEquals(original, snapshot(storage)); }
    }

    private static class RecordedPermission extends android.webkit.PermissionRequest {
        final Uri origin; String result;
        RecordedPermission(String origin) { this.origin = Uri.parse(origin); }
        @Override public Uri getOrigin() { return origin; }
        @Override public String[] getResources() { return new String[]{RESOURCE_VIDEO_CAPTURE}; }
        @Override public void grant(String[] resources) { assertArrayEquals(getResources(), resources); result = "grant"; }
        @Override public void deny() { result = "deny"; }
    }

    @Test public void sameHostnamePortsKeepIndependentRenderingPrivacyAndSettingsReset() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String,Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        BrowserDatabaseHelper database = BrowserDatabaseHelper.getInstance(instrumentation.getTargetContext());
        BrowserActivity browser = null;
        Activity settings = null;
        Instrumentation.ActivityMonitor monitor = null;
        BrowserGeolocationTestState geolocation = null;
        List<String> urls = new ArrayList<>();
        String fixture = "__site_authority_" + UUID.randomUUID();
        try (SiteServer a = new SiteServer(fixture + "a"); SiteServer b = new SiteServer(fixture + "b")) {
            geolocation = BrowserGeolocationTestState.capture(instrumentation);
            urls.add(a.url); urls.add(b.url);
            prefs.setRestoreTabs(0); prefs.setExitClearFlags(Collections.emptySet());
            prefs.setClearDataOnExit(false); prefs.setScriptsEnabled(false);
            prefs.setAdBlockEnabled(false); prefs.setIncognitoMode(false);
            prefs.setDesktopMode(false); prefs.setSimpleUa(false);
            prefs.setUaSelectedId(0); prefs.setCustomUserAgent("Global/1.0");
            prefs.setJsEnabled(true); prefs.setImagesEnabled(true); prefs.setTextZoom(100);
            prefs.setBackNoReload(false);
            prefs.resetSiteSettings("127.0.0.1");
            configure(prefs, a.authority, "Authority-A/1.0", false, 140, true);
            configure(prefs, b.authority, "Authority-B/1.0", true, 80, false);
            try {
                browser = (BrowserActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                        .setAction(Intent.ACTION_VIEW).setData(Uri.parse(a.url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
                BrowserActivity active = browser;
                TabManager.Tab tab = main(() -> tabs(active).current());
                awaitPage(tab, a);
                assertSettings(tab, "Authority-A/1.0", false, 140, true);
                assertEquals("The first request must already use the first port's UA", Collections.singletonList("Authority-A/1.0"), a.requests);
                assertEquals("Private port must not write history", 0, visits(database, a.url));
                main(() -> { invoke(active, "loadUrlInCurrent", new Class[]{String.class}, b.url); return null; });
                awaitPage(tab, b);
                assertSettings(tab, "Authority-B/1.0", true, 80, false);
                assertEquals("Switching ports must use the target UA before its single request", Collections.singletonList("Authority-B/1.0"), b.requests);
                assertTrue("The normal port must write its own history", visits(database, b.url) > 0);
                assertEquals(0, visits(database, a.url));

                monitor = instrumentation.addMonitor(BrowserSiteSettingsActivity.class.getName(), null, false);
                main(() -> { invoke(active, "showSiteConfiguration", new Class[0]); return null; });
                settings = instrumentation.waitForMonitorWithTimeout(monitor, 5000);
                assertNotNull("Open the actual site-settings activity", settings);
                Activity shown = settings;
                main(() -> {
                    assertNotNull("The enabled-site label must include the current port",
                            find(shown.findViewById(android.R.id.content), "启用 \"" + b.authority + "\" 的网站设定"));
                    View reset = find(shown.findViewById(android.R.id.content), "重置");
                    assertNotNull(reset); assertTrue(reset.performClick());
                    return null;
                });
                assertFalse("Reset applies only to the current authority", prefs.siteSettingsEnabled(b.authority));
                assertTrue("Reset must retain the other port", prefs.siteSettingsEnabled(a.authority));
                assertEquals("Authority-A/1.0", prefs.siteUserAgent(a.authority));
                assertEquals(140, prefs.siteTextZoom(a.authority, -1));
                main(() -> { shown.onBackPressed(); return null; });
                awaitDestroyed(shown); settings = null;
                long end = System.currentTimeMillis() + 5000;
                while (!"Global/1.0".equals(main(() -> tab.webView.getSettings().getUserAgentString())) && System.currentTimeMillis() < end) Thread.sleep(25);
                assertSettings(tab, "Global/1.0", true, 100, false);
            } finally {
                try {
                    if (settings != null) { Activity closing = settings; main(() -> { closing.finish(); return null; }); awaitDestroyed(closing); }
                } finally {
                    try {
                        if (browser != null) { BrowserActivity closing = browser; main(() -> { closing.finish(); return null; }); awaitDestroyed(closing); }
                    } finally { if (monitor != null) instrumentation.removeMonitor(monitor); }
                }
            }
        } finally {
            try { for (String url : urls) database.getWritableDatabase().delete(BrowserDatabaseHelper.TABLE_HISTORY, "url=?", new String[]{url}); }
            finally {
                try { if (geolocation != null) geolocation.restore(); }
                finally { restore(storage, original); assertEquals("Restore all user settings and session values", original, snapshot(storage)); }
            }
        }
    }

    private void configure(BrowserPrefs prefs, String key, String ua, boolean images, int zoom, boolean privateMode) {
        prefs.resetSiteSettings(key); prefs.setSiteSettingsEnabled(key, true);
        prefs.setSiteUserAgent(key, ua); prefs.setSiteDesktopMode(key, 0); prefs.setSiteJsMode(key, 1);
        prefs.setSiteImagesMode(key, images ? 1 : 0); prefs.setSiteTextZoom(key, zoom);
        prefs.setSiteIncognitoMode(key, privateMode ? 1 : 0); prefs.setSiteBackNoReloadMode(key, 0);
    }

    private void assertSettings(TabManager.Tab tab, String ua, boolean images, int zoom, boolean privateMode) throws Exception {
        main(() -> {
            WebSettings s = tab.webView.getSettings();
            assertEquals("Settings must use the complete authority", ua, s.getUserAgentString());
            assertEquals(images, s.getLoadsImagesAutomatically()); assertEquals(!images, s.getBlockNetworkImage());
            assertEquals(zoom, s.getTextZoom());
            Field privacy = tab.tag.getClass().getDeclaredField("incognito"); privacy.setAccessible(true);
            assertEquals("Privacy must follow the target port", privateMode, privacy.getBoolean(tab.tag));
            return null;
        });
    }
    private void awaitPage(TabManager.Tab tab, SiteServer server) throws Exception {
        long end = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < end) {
            if (main(() -> server.url.equals(tab.url) && server.url.equals(tab.webView.getUrl())
                    && server.title.equals(tab.webView.getTitle()) && tab.webView.getProgress() == 100)) { instrumentation.waitForIdleSync(); return; }
            Thread.sleep(25);
        }
        fail("Page did not finish: " + server.url);
    }
    private void awaitDestroyed(Activity activity) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!main(activity::isDestroyed) && System.currentTimeMillis() < end) Thread.sleep(25);
        assertTrue("Destroy activities before restoring settings", main(activity::isDestroyed)); instrumentation.waitForIdleSync();
    }
    private int visits(BrowserDatabaseHelper db, String url) {
        try (Cursor c = db.getReadableDatabase().rawQuery("SELECT count(*) FROM history WHERE url=?", new String[]{url})) { assertTrue(c.moveToFirst()); return c.getInt(0); }
    }
    private TabManager tabs(BrowserActivity activity) throws Exception { Field f = BrowserActivity.class.getDeclaredField("tabs"); f.setAccessible(true); return (TabManager) f.get(activity); }
    private void invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args); }
    private View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) { View found = find(((ViewGroup) root).getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private <T> T main(Callable<T> action) throws Exception { FutureTask<T> future = new FutureTask<>(action); instrumentation.runOnMainSync(future); return future.get(); }
    private Map<String,Object> snapshot(SharedPreferences prefs) { Map<String,Object> map = new HashMap<>(); for (Map.Entry<String,?> e : prefs.getAll().entrySet()) map.put(e.getKey(), e.getValue() instanceof Set ? new HashSet<>((Set<?>) e.getValue()) : e.getValue()); return map; }
    @SuppressWarnings("unchecked") private void restore(SharedPreferences prefs, Map<String,Object> values) {
        SharedPreferences.Editor e = prefs.edit().clear();
        for (Map.Entry<String,Object> item : values.entrySet()) {
            String k = item.getKey(); Object v = item.getValue();
            if (v instanceof String) e.putString(k, (String) v); else if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) e.putInt(k, (Integer) v); else if (v instanceof Long) e.putLong(k, (Long) v);
            else if (v instanceof Float) e.putFloat(k, (Float) v); else if (v instanceof Set) e.putStringSet(k, new HashSet<>((Set<String>) v));
            else throw new AssertionError("Unexpected preference type: " + k);
        }
        assertTrue("Restore must reach disk", e.commit());
    }

    private static class SiteServer implements AutoCloseable {
        final ServerSocket server;
        final String authority, url, title;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final Thread worker;
        volatile Throwable failure;
        SiteServer(String title) throws Exception {
            this.title = title; server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            authority = "127.0.0.1:" + server.getLocalPort(); url = "http://" + authority + "/" + title;
            worker = new Thread(() -> {
                while (!server.isClosed()) try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = reader.readLine(); if (first == null) continue;
                    String line, ua = "";
                    while ((line = reader.readLine()) != null && !line.isEmpty()) if (line.regionMatches(true, 0, "User-Agent:", 0, 11)) ua = line.substring(11).trim();
                    if (first.startsWith("GET /" + title + " ")) requests.add(ua);
                    byte[] body = ("<!doctype html><html><head><link rel='icon' href='data:,'><title>" + title + "</title></head><body>Authority isolation</body></html>").getBytes(StandardCharsets.UTF_8);
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nCache-Control: no-store\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().write(body); socket.getOutputStream().flush();
                } catch (Throwable error) { if (!server.isClosed()) failure = error; }
            }, "site-authority-fixture"); worker.start();
        }
        @Override public void close() throws Exception { server.close(); worker.join(4000); assertFalse(worker.isAlive()); assertNull("Real HTTP fixture must not hide server errors", failure); }
    }
}
