package com.example.cleanrecovery.ui.browser;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Durable script source and configuration, separate from browser settings/cache. */
public final class BrowserScriptDatabase extends SQLiteOpenHelper {
    private static BrowserScriptDatabase instance;
    public static synchronized BrowserScriptDatabase getInstance(Context context) {
        if (instance == null) instance = new BrowserScriptDatabase(context.getApplicationContext(), "via_scripts.db");
        return instance;
    }
    BrowserScriptDatabase(Context context, String filename) { super(context, filename, null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE scripts (_id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE, "
                + "content TEXT NOT NULL, match_rules TEXT NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, "
                + "script_id TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, "
                + "match_override TEXT, run_override TEXT, exclude_override TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }
    public synchronized void migrate(SharedPreferences legacy) {
        migrate(legacy, false);
    }
    public synchronized void migrate(SharedPreferences legacy, boolean replaceExisting) {
        if (!legacy.contains("script_names")) return;
        List<String> names = new ArrayList<>(legacy.getStringSet("script_names", Collections.emptySet()));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String name : names) {
                ContentValues values = new ContentValues();
                values.put("name", name); values.put("content", legacy.getString("script_code_" + name, ""));
                values.put("match_rules", legacy.getString("script_match_" + name, "*"));
                values.put("enabled", legacy.getBoolean("script_enabled_" + name, true) ? 1 : 0);
                String id = legacy.getString("script_id_" + name, "");
                values.put("script_id", id.isEmpty() ? UUID.randomUUID().toString() : id);
                values.put("created_at", legacy.getLong("script_created_" + name, 0));
                values.put("updated_at", legacy.getLong("script_updated_" + name, 0));
                for (String key : new String[]{"match_override", "run_override", "exclude_override"})
                    if (legacy.contains("script_" + key + "_" + name)) values.put(key, legacy.getString("script_" + key + "_" + name, ""));
                // A replay after a crash must not overwrite newer database content.
                if (value(name, "name", null) == null) db.insertOrThrow("scripts", null, values);
                else if (replaceExisting) db.update("scripts", values, "name=?", new String[]{name});
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        // Never remove the legacy source until the database transaction has committed.
        SharedPreferences.Editor cleanup = legacy.edit().remove("script_names");
        for (String name : names) for (String key : new String[]{"code", "match", "enabled", "id", "created", "updated", "match_override", "run_override", "exclude_override"})
            cleanup.remove("script_" + key + "_" + name);
        if (!cleanup.commit()) throw new IllegalStateException("脚本已迁移，但旧设置清理失败，请重试");
    }
    public synchronized List<String> names() {
        List<String> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT name FROM scripts ORDER BY _id", null)) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }
    public synchronized String value(String name, String column, String fallback) {
        try (Cursor cursor = getReadableDatabase().query("scripts", new String[]{column}, "name=?", new String[]{name}, null, null, null)) {
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getString(0) : fallback;
        }
    }
    public synchronized String source(String name) {
        // Do not put an entire multi-megabyte script into a CursorWindow.
        StringBuilder source = new StringBuilder();
        for (int offset = 1; ; offset += 65536) {
            try (Cursor cursor = getReadableDatabase().rawQuery("SELECT substr(content,?,65536) FROM scripts WHERE name=?",
                    new String[]{String.valueOf(offset), name})) {
                if (!cursor.moveToFirst()) return "";
                String part = cursor.getString(0);
                if (part.isEmpty()) return source.toString();
                source.append(part);
            }
        }
    }
    public synchronized void set(String name, String column, String value) {
        ContentValues values = new ContentValues();
        if (value == null) values.putNull(column); else values.put(column, value);
        getWritableDatabase().update("scripts", values, "name=?", new String[]{name});
    }
    public synchronized void save(String name, String match, String code) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("content", code); values.put("match_rules", match); values.put("updated_at", System.currentTimeMillis());
        db.beginTransaction();
        try {
            if (db.update("scripts", values, "name=?", new String[]{name}) == 0) {
                values.put("name", name); values.put("created_at", System.currentTimeMillis());
                values.put("script_id", UUID.randomUUID().toString());
                db.insertOrThrow("scripts", null, values);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized void transferIdentity(String oldName, String newName) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            for (String key : new String[]{"script_id", "created_at", "match_override", "run_override", "exclude_override"})
                set(newName, key, value(oldName, key, null));
            // The caller deletes the old row next; its GM values now belong to the new name.
            set(oldName, "script_id", "");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized void remove(String name) { getWritableDatabase().delete("scripts", "name=?", new String[]{name}); }

    /** Keep the existing backup format readable by older versions. */
    public synchronized java.util.Map<String, Object> backupSettings() {
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        List<String> names = names();
        result.put("script_names", new java.util.LinkedHashSet<>(names));
        for (String name : names) {
            result.put("script_code_" + name, source(name));
            result.put("script_match_" + name, value(name, "match_rules", "*"));
            result.put("script_enabled_" + name, !"0".equals(value(name, "enabled", "1")));
            result.put("script_id_" + name, value(name, "script_id", ""));
            result.put("script_created_" + name, Long.parseLong(value(name, "created_at", "0")));
            result.put("script_updated_" + name, Long.parseLong(value(name, "updated_at", "0")));
            for (String key : new String[]{"match_override", "run_override", "exclude_override"}) {
                String override = value(name, key, null);
                if (override != null) result.put("script_" + key + "_" + name, override);
            }
        }
        return result;
    }
}
