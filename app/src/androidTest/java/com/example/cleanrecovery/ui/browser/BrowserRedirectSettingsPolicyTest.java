package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserRedirectSettingsPolicyTest {
    private SharedPreferences storage;
    private Map<String, Object> original;
    private BrowserPrefs prefs;
    private String sourceHost;
    private String targetHost;
    private String nextHost;
    private String source;
    private String target;
    private String next;

    @Before public void prepareIndependentSites() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        storage = context.getSharedPreferences(BrowserPrefs.PREF, Context.MODE_PRIVATE);
        original = snapshot(storage);
        prefs = new BrowserPrefs(context);
        String fixture = UUID.randomUUID().toString();
        sourceHost = "source-" + fixture + ".test";
        targetHost = "target-" + fixture + ".test";
        nextHost = "next-" + fixture + ".test";
        source = "https://" + sourceHost + "/configured/page";
        target = "https://" + targetHost + "/redirected/page";
        next = "https://" + nextHost + "/following/page";
        prefs.setDesktopMode(false);
        prefs.setSiteSettingsEnabled(sourceHost, true);
        prefs.setSiteSettingsEnabled(targetHost, false);
        prefs.setSiteSettingsEnabled(nextHost, false);
    }

    @After public void restoreEveryPreference() {
        restore(storage, original);
        assertEquals("Policy tests must restore existing settings and remove newly introduced keys", original, snapshot(storage));
    }

    @Test public void automaticNavigationCanKeepAnEnabledSourcesCustomRendering() {
        prefs.setSiteUserAgent(sourceHost, "RedirectPolicy/1.0");

        assertEquals("An automatic navigation to an unconfigured destination must keep the configured source rendering",
                source, BrowserPageNavigationPolicy.settingsUrl(source, target, true, prefs));
    }

    @Test public void anOrdinaryUserGestureChoosesTheTargetInsteadOfInheritedRendering() {
        prefs.setSiteUserAgent(sourceHost, "RedirectPolicy/1.0");

        assertEquals("The inheritance exception must not make deliberate user navigation sticky to the previous site's settings",
                target, BrowserPageNavigationPolicy.settingsUrl(source, target, false, prefs));
    }

    @Test public void enabledTargetSettingsAlwaysKeepTheirOwnScope() {
        prefs.setSiteUserAgent(sourceHost, "RedirectPolicy/1.0");
        prefs.setSiteSettingsEnabled(targetHost, true);

        assertEquals("A destination with enabled site settings must control its own rendering even during a redirect",
                target, BrowserPageNavigationPolicy.settingsUrl(source, target, true, prefs));
    }

    @Test public void anEnabledSiteWithDefaultUaStillQualifiesForInheritance() {
        assertEquals("An untouched site UA retains the reference browser's inherit identity", -1000L, prefs.siteUaSelectedId(sourceHost));

        assertEquals("The default site UA choice is not the explicit Android preset exception",
                source, BrowserPageNavigationPolicy.settingsUrl(source, target, true, prefs));
    }

    @Test public void globalDesktopModeDoesNotTurnTheAndroidPresetIntoAnExplicitSiteDesktopChoice() {
        prefs.setSiteUaSelectedId(sourceHost, -1);
        prefs.setSiteDesktopMode(sourceHost, -1);

        assertEquals("Explicit Android UA with default site desktop mode must use the destination's rendering",
                target, BrowserPageNavigationPolicy.settingsUrl(source, target, true, prefs));
        prefs.setDesktopMode(true);
        assertEquals("Global desktop mode must not count as explicitly enabling desktop mode on the source site",
                target, BrowserPageNavigationPolicy.settingsUrl(source, target, true, prefs));
    }

    @Test public void anExplicitSiteDesktopChoiceAllowsInheritanceEvenWithTheAndroidPreset() {
        prefs.setSiteUaSelectedId(sourceHost, -1);
        prefs.setSiteDesktopMode(sourceHost, 1);

        assertEquals("An explicit source desktop setting must survive automatic navigation to an unconfigured site",
                source, BrowserPageNavigationPolicy.settingsUrl(source, target, true, prefs));
    }

    @Test public void customUaTextEqualToAndroidPresetMustKeepItsCustomIdentity() {
        String androidUa = BrowserPrefs.getPresetUaString(BrowserPrefs.UA_ID_ANDROID_PHONE);
        prefs.setSiteUserAgent(sourceHost, androidUa);
        prefs.setSiteDesktopMode(sourceHost, -1);
        assertEquals("Equal UA text must not silently convert a raw custom selection into a preset ID", -999L,
                prefs.siteUaSelectedId(sourceHost));

        assertEquals("Redirect inheritance follows the user's saved UA selection identity, not a guess based on its text",
                source, BrowserPageNavigationPolicy.settingsUrl(source, target, true, prefs));
    }

    @Test public void disabledOrMissingSourceCannotLendSettingsToTheTarget() {
        prefs.setSiteUserAgent(sourceHost, "RedirectPolicy/1.0");
        prefs.setSiteSettingsEnabled(sourceHost, false);
        assertEquals("Saved values from a disabled source site must not affect another site",
                target, BrowserPageNavigationPolicy.settingsUrl(source, target, true, prefs));

        prefs.setSiteSettingsEnabled(sourceHost, true);
        assertEquals("A newly created WebView without a source URL must use its target",
                target, BrowserPageNavigationPolicy.settingsUrl("", target, true, prefs));
        assertEquals("A missing source URL must not reuse an unrelated enabled site's settings",
                target, BrowserPageNavigationPolicy.settingsUrl(null, target, true, prefs));
    }

    @Test public void redirectChainsResolveEachActualSourceInsteadOfKeepingTheFirstOriginForever() {
        prefs.setSiteUserAgent(sourceHost, "RedirectPolicy/1.0");
        assertEquals("The first A to B redirect can inherit A's rendering",
                source, BrowserPageNavigationPolicy.settingsUrl(source, target, true, prefs));

        assertEquals("For the following B to C redirect, the real source B is disabled and must not forward A's inherited scope",
                next, BrowserPageNavigationPolicy.settingsUrl(target, next, true, prefs));
        assertFalse("Resolving inheritance must not persist an enabled setting onto B", prefs.siteSettingsEnabled(targetHost));
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
        assertTrue("Restored preferences must reach disk before instrumentation exits", editor.commit());
    }
}
