package com.example.cleanrecovery.ui.browser;

import android.webkit.WebSettings;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.List;

/** Installed Via g8.i.O's client-hints adjustment after setting the user-agent string. */
public final class BrowserUserAgentMetadata {
    private BrowserUserAgentMetadata() { }

    public static void apply(WebSettings settings, String userAgent) {
        if (settings == null || !WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)
                || userAgent == null || userAgent.isEmpty() || userAgent.contains("Android")) return;

        UserAgentMetadata current = WebSettingsCompat.getUserAgentMetadata(settings);
        String platform = "Windows";
        boolean mobile = false;
        if (!userAgent.contains("Windows")) {
            if (userAgent.contains("iPhone") || userAgent.contains("iPad")) {
                platform = "iOS";
                mobile = true;
            } else {
                platform = userAgent.contains("Macintosh") ? "macOS" : "Unknown";
            }
        }
        List<UserAgentMetadata.BrandVersion> brands = new ArrayList<>();
        for (UserAgentMetadata.BrandVersion brand : current.getBrandVersionList()) {
            if (!brand.getBrand().contains("Android")) brands.add(brand);
        }
        String fullVersion = current.getFullVersion();
        if (fullVersion != null) {
            fullVersion = fullVersion.trim();
            if (fullVersion.isEmpty()) fullVersion = null;
        }
        WebSettingsCompat.setUserAgentMetadata(settings, new UserAgentMetadata.Builder()
                .setPlatform(platform)
                .setPlatformVersion(current.getPlatformVersion())
                .setFullVersion(fullVersion)
                .setModel(current.getModel())
                .setBitness(current.getBitness())
                .setArchitecture(current.getArchitecture())
                .setWow64(current.isWow64())
                .setMobile(mobile)
                .setBrandVersionList(brands)
                .build());
    }
}
