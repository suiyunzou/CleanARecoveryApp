package com.example.cleanrecovery.ui.browser;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class BrowserScriptStorageTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Test public void migrationIsDurableAndReplayCannotOverwriteNewSource() throws Exception {
        String id = "script-migration-" + java.util.UUID.randomUUID();
        SharedPreferences legacy = context.getSharedPreferences(id, 0);
        String code = "/*" + new String(new char[3000000]).replace('\0', '字') + "*/";
        try (BrowserScriptDatabase db = new BrowserScriptDatabase(context, id + ".db")) {
            legacy.edit().putStringSet("script_names", Collections.singleton("fixture"))
                    .putString("script_code_fixture", code).putString("script_match_fixture", "https://example.invalid/*")
                    .putBoolean("script_enabled_fixture", false).putString("script_id_fixture", "stable-id")
                    .putLong("script_created_fixture", 123).putLong("script_updated_fixture", 456)
                    .putString("script_run_override_fixture", "document-start").putString("unrelated", "keep").commit();
            db.migrate(legacy);
            assertEquals(code, db.source("fixture"));
            assertEquals("0", db.value("fixture", "enabled", null));
            assertEquals("stable-id", db.value("fixture", "script_id", null));
            assertEquals("123", db.value("fixture", "created_at", null));
            assertEquals("document-start", db.value("fixture", "run_override", null));
            assertFalse(legacy.contains("script_code_fixture"));
            assertEquals("keep", legacy.getString("unrelated", ""));
            db.save("fixture", "*", "new source");
            legacy.edit().putStringSet("script_names", Collections.singleton("fixture")).putString("script_code_fixture", "old source").commit();
            db.migrate(legacy);
            assertEquals("new source", db.source("fixture"));
            db.close();
            try (BrowserScriptDatabase reopened = new BrowserScriptDatabase(context, id + ".db")) {
                assertEquals("new source", reopened.source("fixture"));
            }
        } finally { context.deleteDatabase(id + ".db"); context.deleteSharedPreferences(id); }
    }

    @Test public void draftsAreNonCacheAndBackupRestoresInstalledSource() throws Exception {
        String name = "__storage_" + java.util.UUID.randomUUID();
        BrowserPrefs prefs = new BrowserPrefs(context);
        String token = BrowserScriptDraftStore.write(context, null, "private draft");
        try {
            assertTrue(new java.io.File(context.getFilesDir(), "userscript-drafts/" + token + ".txt").isFile());
            assertFalse(new java.io.File(context.getCacheDir(), "userscript-drafts/" + token + ".txt").exists());
            prefs.saveScript(name, "*", "source");
            prefs.setScriptEnabled(name, false);
            String identity = prefs.scriptStorageId(name);
            String backup = BrowserBackupManager.settingsJson(context);
            prefs.saveScript(name, "*", "changed");
            BrowserBackupManager.restoreSettings(context, backup);
            assertEquals("source", prefs.scriptCode(name));
            assertFalse(prefs.isScriptEnabled(name));
            assertEquals(identity, prefs.scriptStorageId(name));
            assertEquals("private draft", BrowserScriptDraftStore.read(context, token));
            assertFalse(context.getSharedPreferences(BrowserPrefs.PREF, 0).contains("script_code_" + name));
        } finally { prefs.removeScript(name); BrowserScriptDraftStore.delete(context, token); }
    }
}
