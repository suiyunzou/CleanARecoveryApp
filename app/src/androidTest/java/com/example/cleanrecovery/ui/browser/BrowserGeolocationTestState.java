package com.example.cleanrecovery.ui.browser;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Instrumentation;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Preserves native location decisions around settings flows which deliberately clear all origins. */
final class BrowserGeolocationTestState {
    private final Instrumentation instrumentation;
    private final GeolocationPermissions cache;
    private final Map<String, Boolean> original;

    static BrowserGeolocationTestState capture(Instrumentation instrumentation) throws Exception {
        return new BrowserGeolocationTestState(instrumentation);
    }

    private BrowserGeolocationTestState(Instrumentation instrumentation) throws Exception {
        this.instrumentation = instrumentation;
        cache = main(GeolocationPermissions::getInstance);
        original = snapshotOrigins();
        for (Map.Entry<String, Boolean> entry : original.entrySet())
            if (!entry.getValue()) requireRestorableRefusal(entry.getKey());
    }

    Map<String, Boolean> snapshotOrigins() throws Exception {
        CompletableFuture<Set<String>> origins = new CompletableFuture<>();
        main(() -> { cache.getOrigins(value -> origins.complete(new HashSet<>(value))); return null; });
        Map<String, Boolean> values = new HashMap<>();
        for (String value : origins.get(5, TimeUnit.SECONDS)) {
            CompletableFuture<Boolean> allowed = new CompletableFuture<>();
            main(() -> { cache.getAllowed(value, allowed::complete); return null; });
            values.put(value, allowed.get(5, TimeUnit.SECONDS));
        }
        return values;
    }

    void retainAllowance(String origin) throws Exception {
        main(() -> { cache.allow(origin); return null; });
        await(() -> Boolean.TRUE.equals(snapshotOrigins().get(origin)));
    }

    void retainRefusal(String origin) throws Exception {
        requireRestorableRefusal(origin);
        // The public API has no deny method; retain a refusal through the native WebView callback.
        CompletableFuture<String> requested = new CompletableFuture<>();
        WebView page = main(() -> {
            cache.clear(origin);
            WebView view = new WebView(instrumentation.getTargetContext());
            view.getSettings().setJavaScriptEnabled(true);
            view.getSettings().setGeolocationEnabled(true);
            view.getSettings().setBlockNetworkLoads(true);
            view.setWebChromeClient(new WebChromeClient() {
                @Override public void onGeolocationPermissionsShowPrompt(String value, GeolocationPermissions.Callback callback) {
                    callback.invoke(value, false, true);
                    requested.complete(value);
                }
            });
            view.loadDataWithBaseURL(origin, "<script>navigator.geolocation.getCurrentPosition(function(){},function(){})</script>",
                    "text/html", "UTF-8", null);
            return view;
        });
        try {
            assertEquals("The native callback must belong to the origin being restored", origin, requested.get(5, TimeUnit.SECONDS));
            await(() -> Boolean.FALSE.equals(snapshotOrigins().get(origin)));
        } finally {
            main(() -> { page.stopLoading(); page.setWebChromeClient(null); page.destroy(); return null; });
        }
    }

    void restore(String... fixtureOrigins) throws Exception {
        for (String origin : fixtureOrigins) if (origin != null)
            main(() -> { cache.clear(origin); return null; });
        Map<String, Boolean> remaining = snapshotOrigins();
        AssertionError failure = null;
        for (Map.Entry<String, Boolean> entry : original.entrySet()) {
            if (entry.getValue().equals(remaining.get(entry.getKey()))) continue;
            try {
                if (entry.getValue()) retainAllowance(entry.getKey());
                else retainRefusal(entry.getKey());
            } catch (Exception | AssertionError error) {
                if (failure == null) failure = new AssertionError("Failed to restore native geolocation decisions");
                failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
        await(() -> original.equals(snapshotOrigins()));
    }

    private void requireRestorableRefusal(String origin) {
        Uri value = Uri.parse(origin);
        assertTrue("Do not clear a legacy denied origin that modern WebView cannot recreate: " + origin,
                "https".equals(value.getScheme()) || "localhost".equals(value.getHost())
                        || "127.0.0.1".equals(value.getHost()) || "[::1]".equals(value.getHost()));
        assertTrue("Grant Android location before a test clears stored refusals; this test will not change system permissions",
                instrumentation.getTargetContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                        || instrumentation.getTargetContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED);
    }

    private <T> T main(Callable<T> action) throws Exception {
        FutureTask<T> result = new FutureTask<>(action); instrumentation.runOnMainSync(result); return result.get();
    }

    private void await(Callable<Boolean> condition) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < end) { if (condition.call()) return; Thread.sleep(25); }
        fail("The expected native geolocation cache state did not arrive");
    }
}
