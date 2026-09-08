package com.example.cleanrecovery.ui.browser;

import android.app.Instrumentation;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(AndroidJUnit4.class)
public class BrowserUserAgentMetadataTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void nonAndroidUaChangesOnlyPlatformMobileAndAndroidBrandHints() throws Exception {
        assumeTrue("The installed WebView must support user-agent metadata for this device test",
                WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA));
        main(() -> {
            WebView web = new WebView(instrumentation.getTargetContext());
            try {
                String[][] cases = {
                        {"Mozilla/5.0 (Windows NT 10.0; Win64; x64)", "Windows", "false"},
                        {"Mozilla/5.0 (iPhone; CPU iPhone OS 18_0)", "iOS", "true"},
                        {"Mozilla/5.0 (iPad; CPU OS 18_0)", "iOS", "true"},
                        {"Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0)", "macOS", "false"},
                        {"FixtureBrowser/1.0", "Unknown", "false"},
                        {"FixtureBrowser Windows iPhone Macintosh", "Windows", "false"}
                };
                WebSettings settings = web.getSettings();
                for (String[] item : cases) {
                    settings.setUserAgentString(item[0]);
                    WebSettingsCompat.setUserAgentMetadata(settings, fixtureMetadata());
                    UserAgentMetadata before = WebSettingsCompat.getUserAgentMetadata(settings);
                    assertEquals("The fixture must contain the Android brand that Via removes", 2, before.getBrandVersionList().size());

                    BrowserUserAgentMetadata.apply(settings, settings.getUserAgentString());
                    UserAgentMetadata after = WebSettingsCompat.getUserAgentMetadata(settings);
                    assertEquals("Client-hints platform must match Via's UA precedence", item[1], after.getPlatform());
                    assertEquals(Boolean.parseBoolean(item[2]), after.isMobile());
                    assertEquals("Non-Android brands keep their versions and order", Collections.singletonList(before.getBrandVersionList().get(1)),
                            after.getBrandVersionList());
                    assertEquals("Via trims a non-empty full version before rebuilding metadata", "148.1.2.3", after.getFullVersion());
                    assertPreservedProperties(before, after);
                    assertEquals("The metadata adjustment must leave the chosen UA string intact", item[0], settings.getUserAgentString());
                }
            } finally { web.destroy(); }
            return null;
        });
    }

    @Test public void androidAndMissingUaLeaveExistingMetadataUntouched() throws Exception {
        assumeTrue("The installed WebView must support user-agent metadata for this device test",
                WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA));
        main(() -> {
            WebView web = new WebView(instrumentation.getTargetContext());
            try {
                WebSettings settings = web.getSettings();
                settings.setUserAgentString("Mozilla/5.0 (Windows; Android 15; iPhone)");
                WebSettingsCompat.setUserAgentMetadata(settings, fixtureMetadata());
                UserAgentMetadata before = WebSettingsCompat.getUserAgentMetadata(settings);
                BrowserUserAgentMetadata.apply(settings, settings.getUserAgentString());
                assertEquals("Via skips Android UA even if another platform name also appears", before,
                        WebSettingsCompat.getUserAgentMetadata(settings));
                BrowserUserAgentMetadata.apply(settings, null);
                BrowserUserAgentMetadata.apply(settings, "");
                BrowserUserAgentMetadata.apply(null, "Windows");
                assertEquals("An absent UA cannot replace existing client hints", before,
                        WebSettingsCompat.getUserAgentMetadata(settings));
            } finally { web.destroy(); }
            return null;
        });
    }

    @Test public void freshWebViewDefaultFullVersionCanBeAdjustedWithoutBlankVersionFailure() throws Exception {
        assumeTrue("The installed WebView must support user-agent metadata for this device test",
                WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA));
        main(() -> {
            WebView web = new WebView(instrumentation.getTargetContext());
            try {
                WebSettings settings = web.getSettings();
                settings.setUserAgentString("Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
                UserAgentMetadata before = WebSettingsCompat.getUserAgentMetadata(settings);
                BrowserUserAgentMetadata.apply(settings, settings.getUserAgentString());
                UserAgentMetadata after = WebSettingsCompat.getUserAgentMetadata(settings);
                assertEquals("A fresh native metadata result must survive Via's normalization", "Windows", after.getPlatform());
                assertFalse(after.isMobile());
                assertPreservedProperties(before, after);
            } finally { web.destroy(); }
            return null;
        });
    }

    private UserAgentMetadata fixtureMetadata() {
        UserAgentMetadata.BrandVersion android = new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Android WebView").setMajorVersion("148").setFullVersion("148.1.2.3").build();
        UserAgentMetadata.BrandVersion chromium = new UserAgentMetadata.BrandVersion.Builder()
                .setBrand("Chromium").setMajorVersion("148").setFullVersion("148.4.5.6").build();
        return new UserAgentMetadata.Builder()
                .setBrandVersionList(Arrays.asList(android, chromium))
                .setFullVersion(" 148.1.2.3 ")
                .setPlatform("FixtureOS")
                .setPlatformVersion("12.3.4")
                .setArchitecture("x86")
                .setModel("Fixture model")
                .setBitness(64)
                .setWow64(true)
                .setMobile(true)
                .build();
    }

    private void assertPreservedProperties(UserAgentMetadata before, UserAgentMetadata after) {
        assertEquals("UA switching must retain platform-version metadata", before.getPlatformVersion(), after.getPlatformVersion());
        assertEquals(before.getArchitecture(), after.getArchitecture());
        assertEquals(before.getModel(), after.getModel());
        assertEquals(before.getBitness(), after.getBitness());
        assertEquals(before.isWow64(), after.isWow64());
    }

    private <T> T main(Callable<T> task) throws Exception {
        FutureTask<T> future = new FutureTask<>(task);
        instrumentation.runOnMainSync(future);
        return future.get();
    }
}
