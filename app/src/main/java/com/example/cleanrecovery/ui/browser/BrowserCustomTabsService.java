package com.example.cleanrecovery.ui.browser;

import android.net.Uri;
import android.os.Bundle;

import androidx.browser.customtabs.CustomTabsService;
import androidx.browser.customtabs.CustomTabsSessionToken;

import java.util.List;

/** Exposes the browser to Android Custom Tabs callers, as Via does. */
public final class BrowserCustomTabsService extends CustomTabsService {
    @Override public boolean warmup(long flags) {
        return true;
    }

    @Override public boolean newSession(CustomTabsSessionToken sessionToken) {
        return true;
    }

    @Override public boolean mayLaunchUrl(CustomTabsSessionToken sessionToken, Uri url,
            Bundle extras, List<Bundle> otherLikelyBundles) {
        return true;
    }

    @Override public Bundle extraCommand(String commandName, Bundle args) {
        return Bundle.EMPTY;
    }

    @Override public boolean updateVisuals(CustomTabsSessionToken sessionToken, Bundle bundle) {
        return true;
    }

    @Override public boolean requestPostMessageChannel(CustomTabsSessionToken sessionToken,
            Uri postMessageOrigin) {
        return false;
    }

    @Override public int postMessage(CustomTabsSessionToken sessionToken, String message,
            Bundle extras) {
        return RESULT_FAILURE_DISALLOWED;
    }

    @Override public boolean validateRelationship(CustomTabsSessionToken sessionToken,
            int relation, Uri origin, Bundle extras) {
        return false;
    }

    @Override public boolean receiveFile(CustomTabsSessionToken sessionToken, Uri uri,
            int purpose, Bundle extras) {
        return false;
    }
}
