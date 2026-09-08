package com.example.cleanrecovery.ui.browser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persistent AI topics and ordered messages, separate from browser history. */
public final class BrowserAiStore extends SQLiteOpenHelper {
    private static BrowserAiStore instance;

    public static synchronized BrowserAiStore getInstance(Context context) {
        if (instance == null) instance = new BrowserAiStore(context.getApplicationContext());
        return instance;
    }

    private BrowserAiStore(Context context) { super(context, "via_copilot.db", null, 4); }

    @Override public void onConfigure(SQLiteDatabase db) { db.setForeignKeyConstraintsEnabled(true); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE threads (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, updated_at INTEGER NOT NULL, page_url TEXT)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id TEXT NOT NULL "
                + "REFERENCES threads(id) ON DELETE CASCADE, role TEXT NOT NULL, content TEXT NOT NULL, "
                + "reasoning TEXT NOT NULL DEFAULT '', flags INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX messages_thread ON messages(thread_id, id)");
        createPrompts(db);
        createProviders(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createPrompts(db);
            db.execSQL("ALTER TABLE threads ADD COLUMN page_url TEXT");
        }
        if (oldVersion < 3 && newVersion >= 3) createProviders(db);
        if (oldVersion < 4 && newVersion >= 4) db.execSQL("ALTER TABLE messages ADD COLUMN flags INTEGER NOT NULL DEFAULT 0");
    }

    private void createProviders(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE providers (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, endpoint TEXT NOT NULL, api_key TEXT NOT NULL, models TEXT NOT NULL, model TEXT NOT NULL)");
    }

    public static final class Provider {
        public final String id, name, endpoint, key, models, model;
        public Provider(String id, String name, String endpoint, String key, String models, String model) {
            this.id = id; this.name = name; this.endpoint = endpoint; this.key = key; this.models = models; this.model = model;
        }
    }

    public List<Provider> providers() {
        List<Provider> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("providers", new String[]{"id", "name", "endpoint", "api_key", "models", "model"},
                null, null, null, null, "rowid ASC")) {
            while (cursor.moveToNext()) result.add(new Provider(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                    cursor.getString(3), cursor.getString(4), cursor.getString(5)));
        }
        return result;
    }

    public Provider saveProvider(String id, String name, String endpoint, String key, String models, String model) {
        ContentValues values = new ContentValues();
        values.put("name", name); values.put("endpoint", endpoint); values.put("api_key", key); values.put("models", models); values.put("model", model);
        if (id == null) {
            id = UUID.randomUUID().toString(); values.put("id", id);
            getWritableDatabase().insertOrThrow("providers", null, values);
        } else getWritableDatabase().update("providers", values, "id=?", new String[]{id});
        return new Provider(id, name, endpoint, key, models, model);
    }

    public void deleteProvider(String id) { getWritableDatabase().delete("providers", "id=?", new String[]{id}); }

    private void createPrompts(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE prompts (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, content TEXT NOT NULL, type INTEGER NOT NULL)");
    }

    public List<BrowserAiPrompt> prompts() {
        List<BrowserAiPrompt> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("prompts", new String[]{"id", "name", "content", "type"},
                null, null, null, null, "type ASC, rowid ASC")) {
            while (cursor.moveToNext()) result.add(new BrowserAiPrompt(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getInt(3)));
        }
        return result;
    }

    public BrowserAiPrompt savePrompt(String id, String name, String content, int type) {
        if (name.trim().isEmpty() || content.trim().isEmpty() || (type != 1 && type != 2)) throw new IllegalArgumentException("提示词标题和内容不能为空");
        ContentValues values = new ContentValues();
        values.put("name", name.trim()); values.put("content", content.trim()); values.put("type", type);
        if (id == null) {
            id = UUID.randomUUID().toString(); values.put("id", id);
            getWritableDatabase().insertOrThrow("prompts", null, values);
        } else getWritableDatabase().update("prompts", values, "id=?", new String[]{id});
        return new BrowserAiPrompt(id, name.trim(), content.trim(), type);
    }

    public void deletePrompt(String id) { getWritableDatabase().delete("prompts", "id=?", new String[]{id}); }

    public void bindPage(String topicId, String url) {
        ContentValues values = new ContentValues(); values.put("page_url", url);
        getWritableDatabase().update("threads", values, "id=?", new String[]{topicId});
    }

    public Topic topicForPage(String url) {
        try (Cursor cursor = getReadableDatabase().query("threads", new String[]{"id", "name"},
                "page_url=?", new String[]{url}, null, null, "updated_at DESC", "1")) {
            return cursor.moveToFirst() ? new Topic(cursor.getString(0), cursor.getString(1)) : null;
        }
    }

    public void importLegacy(Set<String> names) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (String name : names) {
                ContentValues values = new ContentValues();
                values.put("id", "legacy:" + name);
                values.put("name", name);
                values.put("updated_at", System.currentTimeMillis());
                db.insertWithOnConflict("threads", null, values, SQLiteDatabase.CONFLICT_IGNORE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public Topic create(String name, String systemPrompt) {
        String id = UUID.randomUUID().toString();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("id", id);
            values.put("name", name);
            values.put("updated_at", System.currentTimeMillis());
            db.insertOrThrow("threads", null, values);
            if (systemPrompt != null && !systemPrompt.isEmpty()) append(id, "system", systemPrompt, "");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return new Topic(id, name);
    }

    public List<Topic> topics() {
        List<Topic> topics = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query("threads", new String[]{"id", "name"},
                null, null, null, null, "updated_at DESC, rowid DESC")) {
            while (cursor.moveToNext()) topics.add(new Topic(cursor.getString(0), cursor.getString(1)));
        }
        return topics;
    }

    public List<Message> messages(String topicId) {
        List<Message> messages = new ArrayList<>();
        if (topicId == null) return messages;
        try (Cursor cursor = getReadableDatabase().query("messages", new String[]{"id", "role", "content", "reasoning", "flags"},
                "thread_id=?", new String[]{topicId}, null, null, "id ASC")) {
            while (cursor.moveToNext()) messages.add(new Message(cursor.getLong(0), cursor.getString(1),
                    cursor.getString(2), cursor.getString(3), cursor.getInt(4)));
        }
        return messages;
    }

    public JSONArray requestMessages(String topicId) throws JSONException {
        JSONArray result = new JSONArray();
        List<Message> history = messages(topicId);
        for (int i = 0; i < history.size(); i++) {
            Message message = history.get(i);
            if ((message.flags & 1) != 0) continue;
            if (!"system".equals(message.role) && i < history.size() - 19) continue;
            if (!message.content.isEmpty()) result.put(new JSONObject()
                    .put("role", message.role).put("content", message.content));
        }
        return result;
    }

    public String exportText(String topicId) {
        StringBuilder text = new StringBuilder();
        for (Message message : messages(topicId)) {
            if ("user".equals(message.role)) text.append("## ");
            else if (!message.reasoning.isEmpty()) text.append("> ").append(message.reasoning.replace("\n", "\n> "));
            text.append(message.content).append("\n\n");
        }
        return text.toString();
    }

    public long append(String topicId, String role, String content, String reasoning) {
        return append(topicId, role, content, reasoning, 0);
    }

    public long append(String topicId, String role, String content, String reasoning, int flags) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues values = new ContentValues();
            values.put("thread_id", topicId);
            values.put("role", role);
            values.put("content", content);
            values.put("reasoning", reasoning);
            values.put("flags", flags);
            long id = db.insertOrThrow("messages", null, values);
            touch(topicId);
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    public void rename(String topicId, String name) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        getWritableDatabase().update("threads", values, "id=?", new String[]{topicId});
    }

    private void touch(String topicId) {
        ContentValues values = new ContentValues();
        values.put("updated_at", System.currentTimeMillis());
        getWritableDatabase().update("threads", values, "id=?", new String[]{topicId});
    }

    public void clear(String topicId) {
        getWritableDatabase().delete("messages", "thread_id=? AND role != 'system'", new String[]{topicId});
    }

    public void delete(String topicId) {
        getWritableDatabase().delete("threads", "id=?", new String[]{topicId});
    }

    public void deleteMessage(String topicId, long messageId) {
        getWritableDatabase().delete("messages", "thread_id=? AND id=? AND role != 'system'",
                new String[]{topicId, String.valueOf(messageId)});
    }

    public static final class Topic {
        public final String id;
        public final String name;
        public Topic(String id, String name) { this.id = id; this.name = name; }
    }

    public static final class Message {
        public final long id;
        public final String role;
        public final String content;
        public final String reasoning;
        public final int flags;
        Message(long id, String role, String content, String reasoning, int flags) {
            this.id = id; this.role = role; this.content = content; this.reasoning = reasoning;
            this.flags = flags;
        }
    }
}
