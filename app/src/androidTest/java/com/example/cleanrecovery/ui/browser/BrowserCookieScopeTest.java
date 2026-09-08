package com.example.cleanrecovery.ui.browser;

import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.webkit.CookieManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.ui.activity.BrowserActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Method;
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
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserCookieScopeTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void domainDeletionStopsAtInstalledPublicSuffixAndPrivateTenantBoundaries() throws Exception {
        android.content.Context context = instrumentation.getTargetContext();
        assertEquals(java.util.Arrays.asList("watch.example.co.uk", ".watch.example.co.uk", "example.co.uk", ".example.co.uk"),
                BrowserCookies.hostScope(context, "https://watch.example.co.uk:8443/nested/page"));
        assertEquals("Private hosting tenants must not erase other tenants' parent cookies",
                java.util.Arrays.asList("a.tenant.github.io", ".a.tenant.github.io", "tenant.github.io", ".tenant.github.io"),
                BrowserCookies.hostScope(context, "https://a.tenant.github.io/"));
        assertEquals("A wildcard suffix makes b.ck a suffix, not a parent to clear",
                java.util.Arrays.asList("a.b.ck", ".a.b.ck"), BrowserCookies.hostScope(context, "https://a.b.ck/"));
        assertEquals("The PSL exception permits the www.ck registrable parent",
                java.util.Arrays.asList("a.www.ck", ".a.www.ck", "www.ck", ".www.ck"),
                BrowserCookies.hostScope(context, "https://a.www.ck/"));
        assertEquals(java.util.Arrays.asList("a.city.kawasaki.jp", ".a.city.kawasaki.jp", "city.kawasaki.jp", ".city.kawasaki.jp"),
                BrowserCookies.hostScope(context, "https://a.city.kawasaki.jp/"));
        String idn = java.net.IDN.toASCII("食狮.公司.cn");
        assertEquals("Unicode PSL matching must return the ASCII cookie-domain keys",
                java.util.Arrays.asList("a." + idn, ".a." + idn, idn, "." + idn),
                BrowserCookies.hostScope(context, "https://a." + idn + "/"));
        assertEquals(java.util.Arrays.asList("a.example.invalid", ".a.example.invalid", "example.invalid", ".example.invalid"),
                BrowserCookies.hostScope(context, "https://a.example.invalid/"));
        assertEquals("IP octets cannot be mistaken for parent domains", java.util.Arrays.asList("127.0.0.1", ".127.0.0.1"),
                BrowserCookies.hostScope(context, "http://127.0.0.1:39841/"));
        assertEquals(java.util.Arrays.asList("::1", ".::1"), BrowserCookies.hostScope(context, "http://[::1]/"));
        assertEquals("A public suffix itself does not imply permission to clear its parent", java.util.Arrays.asList("co.uk", ".co.uk"),
                BrowserCookies.hostScope(context, "https://co.uk/"));
    }

    @Test public void capturedPageDeletionRemovesStoredOtherPathsAndParentHostsButKeepsSiblingSites() throws Exception {
        SharedPreferences storage = instrumentation.getTargetContext().getSharedPreferences("via_browser_prefs", 0);
        Map<String,Object> original = snapshot(storage);
        BrowserPrefs prefs = new BrowserPrefs(instrumentation.getTargetContext());
        CookieManager cookies = CookieManager.getInstance();
        boolean accepted = cookies.acceptCookie();
        String parent = "cookie-scope-" + UUID.randomUUID() + ".test";
        String host = "watch." + parent, sibling = "peer." + parent, unrelated = "other-" + parent;
        String page = "https://" + host + "/nested/page";
        List<FixtureCookie> fixtures = new ArrayList<>();
        fixtures.add(new FixtureCookie(page, "scope_root=root; Path=/; Secure; HttpOnly; Max-Age=3600"));
        fixtures.add(new FixtureCookie(page, "__Host-scope_session=protected; Path=/; Secure; HttpOnly; Max-Age=3600"));
        fixtures.add(new FixtureCookie(page, "scope_other_path=other; Path=/other; Secure; HttpOnly; Max-Age=3600"));
        fixtures.add(new FixtureCookie(page, "scope_parent=parent; Domain=" + parent + "; Path=/; Secure; HttpOnly; Max-Age=3600"));
        fixtures.add(new FixtureCookie("https://" + parent + "/", "scope_parent_host=parenthost; Path=/hidden; Secure; HttpOnly; Max-Age=3600"));
        fixtures.add(new FixtureCookie("https://" + sibling + "/", "scope_sibling=keep; Path=/; Secure; HttpOnly; Max-Age=3600"));
        fixtures.add(new FixtureCookie("https://" + unrelated + "/", "scope_unrelated=keep; Path=/; Secure; HttpOnly; Max-Age=3600"));
        String[] removedHosts = {host, "." + host, parent, "." + parent};
        String[] allHosts = {host, "." + host, parent, "." + parent, sibling, unrelated};
        BrowserActivity browser = null;
        AlertDialog dialog = null;
        try {
            prefs.setRestoreTabs(0); prefs.setExitClearFlags(Collections.emptySet());
            prefs.setClearDataOnExit(false); prefs.setScriptsEnabled(false); prefs.setAdBlockEnabled(false);
            prefs.setCookiesEnabled(true);
            browser = (BrowserActivity) instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), BrowserActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
            main(() -> { cookies.setAcceptCookie(true); return null; });
            for (FixtureCookie fixture : fixtures) set(cookies, fixture.url, fixture.value);
            cookies.flush();
            assertEquals("Fixture must persist cookies invisible at the captured URL", 5, storedCount(removedHosts));
            assertTrue(cookies.getCookie(page).contains("scope_root=root"));
            assertTrue("The native WebView must accept the protected host-only login cookie", cookies.getCookie(page).contains("__Host-scope_session=protected"));
            assertFalse("Another path must be absent from the current-page header", cookies.getCookie(page).contains("scope_other_path"));
            BrowserActivity active = browser;
            dialog = main(() -> {
                Method show = BrowserActivity.class.getDeclaredMethod("showCookiesDialog", String.class);
                show.setAccessible(true); return (AlertDialog) show.invoke(active, page);
            });
            AlertDialog shown = dialog;
            instrumentation.getUiAutomation().executeAndWaitForEvent(
                    () -> instrumentation.runOnMainSync(() -> assertTrue(shown.getButton(AlertDialog.BUTTON_NEUTRAL).performClick())),
                    event -> event.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
                            && event.getText().toString().contains("已置空 " + host + " 的 Cookies"), 5000);
            long end = System.currentTimeMillis() + 5000;
            while (cookies.getCookie(page) != null && !cookies.getCookie(page).isEmpty() && System.currentTimeMillis() < end) Thread.sleep(25);
            assertTrue("The captured URL must stop exposing its cookie header", cookies.getCookie(page) == null || cookies.getCookie(page).isEmpty());
            while (storedCount(removedHosts) != 0 && System.currentTimeMillis() < end) Thread.sleep(25);
            assertEquals("Via's deletion scope includes stored other paths and ancestor host-only cookies", 0, storedCount(removedHosts));
            assertTrue("Sibling host-only cookies must survive", cookies.getCookie("https://" + sibling + "/").contains("scope_sibling=keep"));
            assertTrue("Unrelated site cookies must survive", cookies.getCookie("https://" + unrelated + "/").contains("scope_unrelated=keep"));
            assertEquals("Only this operation's host scope may be deleted", 2, storedCount(new String[]{sibling, unrelated}));
            assertEquals("Match the installed Via action label", "删除", main(() -> shown.getButton(AlertDialog.BUTTON_NEUTRAL).getText().toString()));
        } finally {
            try {
                if (dialog != null) { AlertDialog closing = dialog; main(() -> { closing.dismiss(); return null; }); }
                if (browser != null) {
                    BrowserActivity closing = browser; main(() -> { closing.finish(); return null; });
                    long end = System.currentTimeMillis() + 5000;
                    while (!main(closing::isDestroyed) && System.currentTimeMillis() < end) Thread.sleep(25);
                    assertTrue("Destroy before restoring user preferences", main(closing::isDestroyed)); instrumentation.waitForIdleSync();
                }
            } finally {
                try {
                    for (FixtureCookie fixture : fixtures) set(cookies, fixture.url, fixture.value.replace("Max-Age=3600", "Max-Age=0"));
                    cookies.flush();
                    assertEquals("Remove only the unique fixture cookies from disk", 0, storedCount(allHosts));
                } finally {
                    main(() -> { cookies.setAcceptCookie(accepted); return null; });
                    restore(storage, original); assertEquals("Restore every browser and session preference", original, snapshot(storage));
                }
            }
        }
    }

    private int storedCount(String[] hosts) {
        File file = new File(instrumentation.getTargetContext().getApplicationInfo().dataDir, "app_webview/Default/Cookies");
        assertTrue("Read the actual native WebView cookie database", file.isFile());
        String args = String.join(",", Collections.nCopies(hosts.length, "?"));
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = db.rawQuery("SELECT count(*) FROM cookies WHERE host_key IN (" + args + ")", hosts)) {
            assertTrue(cursor.moveToFirst()); return cursor.getInt(0);
        }
    }
    private void set(CookieManager manager, String url, String value) throws Exception {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        main(() -> { manager.setCookie(url, value, result::complete); return null; });
        assertTrue("The native cookie store must accept the fixture update", result.get(5, TimeUnit.SECONDS));
    }
    private <T> T main(Callable<T> action) throws Exception { FutureTask<T> result = new FutureTask<>(action); instrumentation.runOnMainSync(result); return result.get(); }
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
    private static class FixtureCookie { final String url, value; FixtureCookie(String url, String value) { this.url = url; this.value = value; } }
}
