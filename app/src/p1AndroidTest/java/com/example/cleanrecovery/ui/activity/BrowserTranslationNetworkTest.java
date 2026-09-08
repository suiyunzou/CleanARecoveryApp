package com.example.cleanrecovery.ui.activity;

import android.content.Intent;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.TabManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Opt-in live-provider smoke test; not included in the default instrumentation suite. */
@RunWith(AndroidJUnit4.class)
public class BrowserTranslationNetworkTest {
    @Test public void liveProvider() throws Exception {
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        assertTrue(instrumentation.getTargetContext().getPackageName().endsWith(".p1test"));
        boolean edge = "edge".equals(InstrumentationRegistry.getArguments().getString("p1.engine"));
        BrowserActivity activity = (BrowserActivity) instrumentation.startActivitySync(new Intent(
                instrumentation.getTargetContext(), BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Field field = BrowserActivity.class.getDeclaredField("tabs");
        field.setAccessible(true);
        TabManager.Tab tab = ((TabManager) field.get(activity)).current();
        CountDownLatch loaded = new CountDownLatch(1);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            instrumentation.runOnMainSync(() -> {
                tab.webView.stopLoading();
                tab.webView.setWebViewClient(new WebViewClient() {
                    @Override public void onPageFinished(WebView view, String url) { loaded.countDown(); }
                });
                tab.webView.loadDataWithBaseURL("https://example.com/", "<html><head>"
                        + (edge ? "<meta http-equiv='Content-Security-Policy' content=\"script-src https://fastly.jsdelivr.net\">" : "")
                        + "</head><body><p id='sample'>Hello, welcome to this beautiful world.</p></body></html>",
                        "text/html", "UTF-8", null);
            });
            assertTrue(loaded.await(10, TimeUnit.SECONDS));
            Method menu = BrowserActivity.class.getDeclaredMethod("onMenuAction", int.class);
            menu.setAccessible(true);
            instrumentation.runOnMainSync(() -> {
                try { tab.url = "https://example.com/"; menu.invoke(activity, R.id.menu_translate); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            String result = null;
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
            do {
                CountDownLatch evaluated = new CountDownLatch(1);
                AtomicReference<String> value = new AtomicReference<>();
                instrumentation.runOnMainSync(() -> tab.webView.evaluateJavascript(
                        "JSON.stringify({google:!!window.__via_translator__,edge:!!window.__via_edge_translation__,text:document.getElementById('sample').innerText,iframes:document.querySelectorAll('iframe').length})",
                        v -> { value.set(v); evaluated.countDown(); }));
                assertTrue(evaluated.await(5, TimeUnit.SECONDS));
                result = value.get();
                android.util.Log.i("P1TranslationLive", String.valueOf(result));
                org.json.JSONObject json = new org.json.JSONObject(new org.json.JSONTokener(result).nextValue().toString());
                if (edge ? json.getBoolean("edge") && !json.getString("text").startsWith("Hello")
                        : json.getBoolean("google") && json.getInt("iframes") > 0) return;
                Thread.sleep(500);
            } while (System.nanoTime() < until);
            fail("Live " + (edge ? "Edge" : "Google") + " provider unavailable or not rendered: " + result);
        } finally {
            Locale.setDefault(previous);
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
