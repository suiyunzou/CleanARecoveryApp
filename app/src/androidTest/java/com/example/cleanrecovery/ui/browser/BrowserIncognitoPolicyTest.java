package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserIncognitoPolicyTest {
    private Context context;
    private SharedPreferences storage;
    private BrowserPrefs prefs;
    private boolean hadGlobalKey;
    private boolean originalGlobal;
    private String privateHost;
    private String ordinaryHost;
    private String privateUrl;
    private String ordinaryUrl;

    @Before public void prepareIsolatedSites() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        storage=context.getSharedPreferences(BrowserPrefs.PREF,Context.MODE_PRIVATE);
        hadGlobalKey=storage.contains("incognito_mode");
        originalGlobal=storage.getBoolean("incognito_mode",false);
        prefs=new BrowserPrefs(context);
        privateHost=UUID.randomUUID()+".test";
        ordinaryHost=UUID.randomUUID()+".test";
        privateUrl="https://"+privateHost+"/watch";
        ordinaryUrl="https://"+ordinaryHost+"/watch";
    }

    @After public void restoreOnlyFixturePreferences() {
        try {
            prefs.resetSiteSettings(privateHost);
            prefs.resetSiteSettings(ordinaryHost);
        } finally {
            SharedPreferences.Editor restore=storage.edit();
            if(hadGlobalKey) restore.putBoolean("incognito_mode",originalGlobal);
            else restore.remove("incognito_mode");
            assertTrue("Fixture cleanup must reach disk before instrumentation exits",restore.commit());
        }
    }

    @Test public void privateSiteDoesNotMakeLaterOrdinaryOrDefaultUrlsPrivate() {
        prefs.setIncognitoMode(false);
        prefs.setSiteSettingsEnabled(privateHost,true);
        prefs.setSiteIncognitoMode(privateHost,1);
        prefs.setSiteSettingsEnabled(ordinaryHost,true);

        assertFalse("Normal browsing starts from the global baseline",prefs.effectiveIncognito(ordinaryUrl));
        assertTrue("An enabled site may opt into private browsing",prefs.effectiveIncognito(privateUrl));
        assertFalse("Visiting a private site must not overwrite the global mode",prefs.incognitoMode());
        assertFalse("Another URL must not inherit the previously visited site's mode",prefs.effectiveIncognito(ordinaryUrl));
        assertTrue("Evaluating another URL must not clear the first site's override",prefs.effectiveIncognito(privateUrl));

        prefs.setSiteIncognitoMode(privateHost,-1);
        assertFalse("Returning the site option to default restores the global baseline",prefs.effectiveIncognito(privateUrl));
        assertFalse("The home page cannot retain the last site's override",prefs.effectiveIncognito("about:blank"));
    }

    @Test public void explicitOrdinarySiteOverridesPrivateGlobalUntilDisabledOrReset() {
        prefs.setIncognitoMode(true);
        prefs.setSiteSettingsEnabled(ordinaryHost,true);
        prefs.setSiteIncognitoMode(ordinaryHost,0);

        assertFalse("Explicit site-off must override globally enabled private browsing",prefs.effectiveIncognito(ordinaryUrl));
        assertTrue("Site-off must not turn off the global mode",prefs.incognitoMode());
        assertTrue("An unrelated site continues to follow global private browsing",prefs.effectiveIncognito(privateUrl));
        assertFalse("Alternating URLs must preserve the ordinary site's override",prefs.effectiveIncognito(ordinaryUrl));

        prefs.setSiteSettingsEnabled(ordinaryHost,false);
        assertTrue("Disabling the site settings master restores global private browsing",prefs.effectiveIncognito(ordinaryUrl));
        assertEquals("Disabling the master must preserve its saved choice",0,prefs.siteIncognitoMode(ordinaryHost));
        prefs.setSiteSettingsEnabled(ordinaryHost,true);
        assertFalse("Re-enabling site settings restores the saved site-off choice",prefs.effectiveIncognito(ordinaryUrl));

        prefs.resetSiteSettings(ordinaryHost);
        assertEquals("Reset removes the explicit site mode",-1,prefs.siteIncognitoMode(ordinaryHost));
        assertTrue("Reset restores the current global baseline",prefs.effectiveIncognito(ordinaryUrl));
        assertTrue("A home URL also follows globally enabled private browsing",prefs.effectiveIncognito("about:blank"));
    }

    @Test public void recreatedPreferencesReadSavedGlobalAndIndependentSiteOverrides() {
        prefs.setIncognitoMode(true);
        prefs.setSiteSettingsEnabled(ordinaryHost,true);
        prefs.setSiteIncognitoMode(ordinaryHost,0);
        prefs.setSiteSettingsEnabled(privateHost,true);
        prefs.setSiteIncognitoMode(privateHost,1);
        assertTrue("Flush saved settings before constructing a new reader",storage.edit().commit());

        BrowserPrefs restored=new BrowserPrefs(context);
        assertTrue("Global private mode is a saved setting",restored.incognitoMode());
        assertTrue("Site settings activation survives a new preference reader",restored.siteSettingsEnabled(ordinaryHost));
        assertFalse("Saved site-off takes precedence after reopening preferences",restored.effectiveIncognito(ordinaryUrl));
        assertTrue("The other saved site retains its independent site-on choice",restored.effectiveIncognito(privateUrl));

        restored.setIncognitoMode(false);
        assertTrue(storage.edit().commit());
        BrowserPrefs changed=new BrowserPrefs(context);
        assertFalse("A later reader observes the changed global baseline",changed.incognitoMode());
        assertTrue("Changing global mode does not erase an explicit site-on choice",changed.effectiveIncognito(privateUrl));
        assertFalse("The other site's explicit ordinary mode remains independent",changed.effectiveIncognito(ordinaryUrl));
    }
}
