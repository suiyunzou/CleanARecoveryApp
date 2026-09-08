package com.example.cleanrecovery.ui.activity;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import com.example.cleanrecovery.ui.browser.TabManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real WebView regression tests, opted in via scripts/android/p1-test.init.gradle. */
@RunWith(AndroidJUnit4.class)
public class BrowserP1Test {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private BrowserActivity activity;
    private TabManager tabs;
    private BrowserDatabaseHelper db;
    private WebView scriptView;
    private boolean stallGoogle;

    @Before public void start() throws Exception {
        assertTrue("Never clear the user's real browser data",
                instrumentation.getTargetContext().getPackageName().endsWith(".p1test"));
        Intent intent = new Intent(instrumentation.getTargetContext(), BrowserActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity = (BrowserActivity) instrumentation.startActivitySync(intent);
        tabs = (TabManager) field(activity, "tabs");
        db = (BrowserDatabaseHelper) field(activity, "dbHelper");
        onMain(() -> { tabs.current().webView.stopLoading(); return null; });
    }

    @After public void stop() throws Exception {
        if (activity != null) onMain(() -> {
            if (scriptView != null) scriptView.destroy();
            activity.finish();
            return null;
        });
        instrumentation.waitForIdleSync();
    }

    @Test public void favoriteAddsHomeLinkWithoutCreatingBookmark() throws Exception {
        String url = "https://favorite.p1.test/" + System.nanoTime();
        int bookmarks = db.listBookmarks().size();
        onMain(() -> {
            tabs.current().url = url;
            tabs.current().title = "P1 home favorite";
            menu(R.id.menu_add_favorite);
            return null;
        });
        assertTrue(db.listQuickLinks().stream().anyMatch(e -> url.equals(e.url)));
        assertEquals("Favorite must not reuse the bookmark dialog/action", bookmarks, db.listBookmarks().size());
    }

    @Test public void incognitoExitClearsVisitedPathsButKeepsUnrelatedLogin() throws Exception {
        String url = "https://a.b.private.p1.test/account/deep/page";
        String normal = "https://normal.other.test/";
        setCookie(normal, "login=keep;Path=/;Secure");
        setCookie(url, "root=private;Path=/;Secure;HttpOnly");
        setCookie(url, "nested=private;Path=/account;Domain=private.p1.test;Secure");
        setCookie(url, "slash=private;Path=/account/deep/;Secure");
        setCookie(url, "__Host-session=private;Path=/;Secure");
        onMain(() -> { tabs.current().url = url; menu(R.id.menu_incognito); return null; });
        assertTrue("Entering on an already loaded page must record it", visited(tabs.current()).contains(url));
        onMain(() -> { menu(R.id.menu_incognito); return null; });
        assertEquals("Unrelated normal login survives", "login=keep", cookie(normal));
        await(() -> cookie(url) == null || cookie(url).isEmpty());
        assertTrue("Completed session URLs must not leak into the next session", visited(tabs.current()).isEmpty());
    }

    @Test public void closingBackgroundIncognitoTabUsesItsOwnVisitedUrls() throws Exception {
        String privateUrl = "https://private.close.test/";
        String normalUrl = "https://normal.close.test/";
        setCookie(privateUrl, "session=private;Path=/;Secure");
        setCookie(normalUrl, "session=normal;Path=/;Secure");
        onMain(() -> {
            tabs.current().url = privateUrl;
            menu(R.id.menu_incognito);
            menu(R.id.menu_new_tab);
            tabs.current().url = normalUrl;
            invoke("closeAndDestroyTab", new Class<?>[]{int.class}, 0);
            return null;
        });
        await(() -> cookie(privateUrl) == null || cookie(privateUrl).isEmpty());
        assertEquals("Closing a background tab must not clear the foreground site's cookies",
                "session=normal", cookie(normalUrl));
    }

    @Test public void navigationRecordsEveryIncognitoPage() throws Exception {
        TabManager.Tab tab = tabs.current();
        onMain(() -> { menu(R.id.menu_incognito); return null; });
        load(tab.webView, "https://first.private.test/one", "<html><body>one</body></html>");
        load(tab.webView, "https://second.private.test/two", "<html><body>two</body></html>");
        assertTrue(visited(tab).contains("https://first.private.test/one"));
        assertTrue(visited(tab).contains("https://second.private.test/two"));
    }

    @Test public void menuClearDeletesLocalStorageAndCookies() throws Exception {
        WebView view = tabs.current().webView;
        String url = "https://storage.p1.test/";
        load(view, url, "<html><body>storage</body></html>");
        assertEquals("\"saved\"", js(view, "localStorage.setItem('p1','saved');localStorage.getItem('p1')"));
        setCookie(url, "session=clear;Path=/;Secure");
        onMain(() -> { menu(R.id.menu_clear_data); return null; });
        await(() -> "null".equals(js(view, "localStorage.getItem('p1')")));
        await(() -> cookie(url) == null || cookie(url).isEmpty());
    }

    @Test public void reportOnlyOpensMailDraftWithoutPrematureSuccessToast() throws Exception {
        AtomicReference<Intent> launched = new AtomicReference<>();
        java.util.List<String> notifications = new java.util.concurrent.CopyOnWriteArrayList<>();
        instrumentation.getUiAutomation().setOnAccessibilityEventListener(event -> {
            if (event.getEventType() == android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                notifications.add(event.getText().toString());
            }
        });
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                launched.set(intent);
                return new Instrumentation.ActivityResult(android.app.Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            onMain(() -> { tabs.current().url = "https://report.p1.test/"; menu(R.id.menu_report); return null; });
            assertNotNull(launched.get());
            assertEquals(Intent.ACTION_CHOOSER, launched.get().getAction());
            Intent draft = launched.get().getParcelableExtra(Intent.EXTRA_INTENT);
            assertEquals(Intent.ACTION_SENDTO, draft.getAction());
            assertEquals("mailto", draft.getData().getScheme());
            assertEquals("https://report.p1.test/", draft.getStringExtra(Intent.EXTRA_TEXT));
            Thread.sleep(500);
            assertTrue("Opening a chooser is not a successful send", notifications.stream()
                    .noneMatch(text -> text.contains(activity.getString(R.string.via_report_sent))));
        } finally {
            instrumentation.removeMonitor(monitor);
            instrumentation.getUiAutomation().setOnAccessibilityEventListener(null);
        }
    }

    @Test public void offlineArchiveIncludesImageAndOpensWithoutNetwork() throws Exception {
        WebView view = tabs.current().webView;
        onMain(() -> {
            view.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    if (r.getUrl().getPath().endsWith(".svg")) return response("image/svg+xml",
                            "<svg xmlns='http://www.w3.org/2000/svg' width='170' height='190'><rect width='170' height='190' fill='#ed1975'/></svg>");
                    return response("text/html", "<html><head><title>P1 archive</title></head><body><img src='/image.svg'></body></html>");
                }
            });
            view.loadUrl("https://archive.p1.test/page");
            return null;
        });
        await(() -> "true".equals(js(view, "document.images.length===1 && document.images[0].naturalWidth===170")));
        int before = db.listOfflinePages().size();
        onMain(() -> {
            tabs.current().url = "https://archive.p1.test/page";
            tabs.current().title = "P1 archive";
            menu(R.id.menu_save);
            return null;
        });
        await(() -> db.listOfflinePages().size() == before + 1);
        BrowserDatabaseHelper.OfflineEntry entry = db.listOfflinePages().get(0);
        assertTrue(entry.filePath.endsWith(".mht"));
        String archive = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(entry.filePath)), StandardCharsets.UTF_8);
        assertTrue("MHT must contain the resource, not just a remote HTML reference", archive.contains("image/svg+xml"));
        CountDownLatch archiveLoaded = new CountDownLatch(1);
        onMain(() -> {
            view.setWebViewClient(new WebViewClient() {
                @Override public void onPageFinished(WebView v, String url) {
                    if (url.startsWith("file:")) archiveLoaded.countDown();
                }
            });
            view.getSettings().setBlockNetworkLoads(true);
            view.clearCache(true);
            ((android.view.View) field(activity, "homeScroll")).setVisibility(android.view.View.GONE);
            view.loadUrl(Uri.fromFile(new java.io.File(entry.filePath)).toString());
            return null;
        });
        assertTrue("Local archive must finish loading", archiveLoaded.await(15, TimeUnit.SECONDS));
        // Chromium can disable JavaScript in MHTML. Check decoded pixels after commit,
        // not evaluateJavascript on the old page during navigation.
        await(() -> onMain(() -> {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(view.getWidth(), view.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            view.draw(new android.graphics.Canvas(bitmap));
            int colored = 0;
            for (int y = 0; y < bitmap.getHeight(); y += 4) {
                for (int x = 0; x < bitmap.getWidth(); x += 4) {
                    if (bitmap.getPixel(x, y) == 0xffed1975) colored++;
                }
            }
            if (colored > 100) {
                try (java.io.FileOutputStream out = activity.openFileOutput("p1-offline-render.png", 0)) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                }
            }
            bitmap.recycle();
            return colored > 100;
        }));
    }

    @Test public void translationGoogleStaysOnPageAndReusesBanner() throws Exception {
        createScriptView(false);
        js(scriptView, BrowserActivity.translationScript(Locale.FRENCH));
        await(() -> "1".equals(js(scriptView, "window.bannerCount")));
        assertEquals("\"fr\"", js(scriptView, "window.googleLanguage"));
        js(scriptView, BrowserActivity.translationScript(Locale.FRENCH));
        assertEquals("2", js(scriptView, "window.bannerCount"));
        assertEquals("\"https://translation.p1.test/\"", js(scriptView, "location.href"));
    }

    @Test public void translationCspFallsBackToEdgeWithLocaleMapping() throws Exception {
        createScriptView(true);
        js(scriptView, BrowserActivity.translationScript(Locale.TRADITIONAL_CHINESE));
        await(() -> "\"chinese_traditional\"".equals(js(scriptView, "window.edgeLanguage")));
        assertEquals("\"client.edge\"", js(scriptView, "window.edgeService"));
        assertEquals("false", js(scriptView, "translate.selectLanguageTag.show"));
        assertEquals("\"https://translation.p1.test/\"", js(scriptView, "location.href"));
        js(scriptView, BrowserActivity.translationScript(Locale.FRENCH));
        assertEquals("\"french\"", js(scriptView, "window.edgeLanguage"));
    }

    @Test public void translationIgnoresUnrelatedCspButHandlesGoogleApiAfterInit() throws Exception {
        createScriptView(false);
        js(scriptView, BrowserActivity.translationScript(Locale.FRENCH));
        await(() -> "1".equals(js(scriptView, "window.bannerCount")));
        js(scriptView, "document.dispatchEvent(new SecurityPolicyViolationEvent('securitypolicyviolation',{blockedURI:'https://unrelated.test/script.js'}))");
        assertEquals("null", js(scriptView, "window.edgeLanguage"));
        js(scriptView, "document.dispatchEvent(new SecurityPolicyViolationEvent('securitypolicyviolation',{blockedURI:'https://translate-pa.googleapis.com/v1/translate'}))");
        await(() -> "\"french\"".equals(js(scriptView, "window.edgeLanguage")));
    }

    @Test public void translationNetworkStallFallsBackAndAllowsRetry() throws Exception {
        stallGoogle = true;
        createScriptView(false);
        js(scriptView, BrowserActivity.translationScript(Locale.FRENCH));
        await(() -> "\"french\"".equals(js(scriptView, "window.edgeLanguage")));
        assertEquals("false", js(scriptView, "window.__via_translation_pending__"));
        js(scriptView, BrowserActivity.translationScript(Locale.TRADITIONAL_CHINESE));
        assertEquals("\"chinese_traditional\"", js(scriptView, "window.edgeLanguage"));
    }

    private void createScriptView(boolean csp) throws Exception {
        onMain(() -> {
            scriptView = new WebView(activity);
            scriptView.getSettings().setJavaScriptEnabled(true);
            scriptView.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                    if ("translate.google.com".equals(r.getUrl().getHost())) return response("text/javascript",
                            stallGoogle ? "/* Simulate a response without initialization */" :
                            "window.google={translate:{TranslateElement:function(o){window.googleLanguage=o.includedLanguages;this.showBanner=function(){window.bannerCount=(window.bannerCount||0)+1;};}}};googleTranslateElementInit();");
                    if ("fastly.jsdelivr.net".equals(r.getUrl().getHost())) return response("text/javascript", "window.translate={selectLanguageTag:{show:true},service:{use:function(s){window.edgeService=s;},edge:{language:{json:[{serviceId:'zh-CHT',id:'chinese_traditional'},{serviceId:'fr',id:'french'}]}}},changeLanguage:function(l){window.edgeLanguage=l;}};");
                    return null;
                }
            });
            return null;
        });
        String policy = csp ? "<meta http-equiv='Content-Security-Policy' content=\"script-src https://fastly.jsdelivr.net\">" : "";
        load(scriptView, "https://translation.p1.test/", "<html><head>" + policy + "</head><body>Translate me</body></html>");
    }

    private void load(WebView view, String url, String html) throws Exception {
        onMain(() -> { view.loadDataWithBaseURL(url, html, "text/html", "UTF-8", null); return null; });
        await(() -> ("\"" + url + "\"").equals(js(view, "location.href"))
                && "\"complete\"".equals(js(view, "document.readyState")));
        instrumentation.waitForIdleSync();
    }

    private static WebResourceResponse response(String mime, String body) {
        return new WebResourceResponse(mime, "UTF-8", new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void setCookie(String url, String cookie) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> accepted = new AtomicReference<>();
        onMain(() -> { CookieManager.getInstance().setCookie(url, cookie, ok -> { accepted.set(ok); done.countDown(); }); return null; });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals("Fixture cookie must be accepted", Boolean.TRUE, accepted.get());
    }

    private String cookie(String url) throws Exception {
        return onMain(() -> CookieManager.getInstance().getCookie(url));
    }

    @SuppressWarnings("unchecked") private Set<String> visited(TabManager.Tab tab) throws Exception {
        return onMain(() -> new java.util.HashSet<>((Set<String>) field(tab.tag, "incognitoVisitedUrls")));
    }

    private void menu(int id) throws Exception { invoke("onMenuAction", new Class<?>[]{int.class}, id); }

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

    private String js(WebView view, String script) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> value = new AtomicReference<>();
        onMain(() -> { view.evaluateJavascript(script, result -> { value.set(result); done.countDown(); }); return null; });
        assertTrue("WebView JS callback", done.await(5, TimeUnit.SECONDS));
        return value.get();
    }

    private <T> T onMain(java.util.concurrent.Callable<T> action) throws Exception {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(action);
        instrumentation.runOnMainSync(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    private void await(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            if (condition.call()) return;
            Thread.sleep(100);
        } while (System.nanoTime() < until);
        fail("Condition did not become true within 15 seconds");
    }
}
