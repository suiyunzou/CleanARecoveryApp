package com.example.cleanrecovery.ui.browser;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 浏览器数据库（书签 + 历史）。
 *
 * <p>纯 SQLite 实现，不引入新依赖。两张表：</p>
 * <ul>
 *   <li>{@code bookmarks}：title / url / add_time</li>
 *   <li>{@code history}：title / url / visit_time</li>
 * </ul>
 */
public final class BrowserDatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "via_browser.db";
    private static final int DB_VERSION = 5;

    public static final String TABLE_BOOKMARKS = "bookmarks";
    public static final String TABLE_HISTORY = "history";
    public static final String TABLE_QUICKLINKS = "quicklinks";
    public static final String TABLE_OFFLINE = "offline_pages";
    public static final String TABLE_FOLDERS = "bookmark_folders";

    private static volatile BrowserDatabaseHelper instance;

    public static synchronized BrowserDatabaseHelper getInstance(Context ctx) {
        if (instance == null) {
            instance = new BrowserDatabaseHelper(ctx.getApplicationContext());
        }
        return instance;
    }

    private BrowserDatabaseHelper(Context ctx) {
        super(ctx, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_BOOKMARKS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "url TEXT NOT NULL UNIQUE,"
                + "folder TEXT NOT NULL DEFAULT '根目录',"
                + "add_time INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FOLDERS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "name TEXT NOT NULL UNIQUE,"
                + "add_time INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE " + TABLE_HISTORY + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT,"
                + "url TEXT NOT NULL,"
                + "visit_time INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_history_time ON " + TABLE_HISTORY + "(visit_time)");
        db.execSQL("CREATE TABLE " + TABLE_QUICKLINKS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "url TEXT NOT NULL UNIQUE,"
                + "add_time INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE " + TABLE_OFFLINE + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "title TEXT NOT NULL,"
                + "url TEXT NOT NULL,"
                + "file_path TEXT NOT NULL UNIQUE,"
                + "add_time INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        if (oldV < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_QUICKLINKS + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "title TEXT NOT NULL,"
                    + "url TEXT NOT NULL UNIQUE,"
                    + "add_time INTEGER NOT NULL)");
        }
        if (oldV < 3) {
            // VIA 默认主页只露出 Logo + 搜索框；历史版本预置的六个快捷方式会让
            // 首页显得更像常规导航页。升级时删除这些内置种子，用户之后仍可手动添加。
            String[] builtIns = {
                    "https://www.baidu.com",
                    "https://www.bing.com",
                    "https://github.com",
                    "https://www.youtube.com",
                    "https://www.zhihu.com",
                    "https://m.weibo.cn"
            };
            for (String url : builtIns) {
                db.delete(TABLE_QUICKLINKS, "url=?", new String[]{url});
            }
        }
        if (oldV < 5) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_FOLDERS + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "name TEXT NOT NULL UNIQUE,"
                    + "add_time INTEGER NOT NULL)");
            try {
                db.execSQL("ALTER TABLE " + TABLE_BOOKMARKS
                        + " ADD COLUMN folder TEXT NOT NULL DEFAULT '根目录'");
            } catch (Exception ignored) {
                // 列已存在
            }
        }
        if (oldV < 4) {
            db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_OFFLINE + " ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "title TEXT NOT NULL,"
                    + "url TEXT NOT NULL,"
                    + "file_path TEXT NOT NULL UNIQUE,"
                    + "add_time INTEGER NOT NULL)");
        }
    }

    // ===== 快捷链接（主页九宫格） =====

    public boolean addQuickLink(String title, String url) {
        if (url == null || url.isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", title == null || title.isEmpty() ? url : title);
        cv.put("url", url);
        cv.put("add_time", System.currentTimeMillis());
        try {
            return db.insertWithOnConflict(TABLE_QUICKLINKS, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean removeQuickLink(long id) {
        return getWritableDatabase().delete(TABLE_QUICKLINKS, "id=?",
                new String[]{String.valueOf(id)}) > 0;
    }

    public List<Entry> listQuickLinks() {
        List<Entry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_QUICKLINKS, null, null, null,
                null, null, "add_time ASC");
        try {
            while (c.moveToNext()) {
                int fIdx = c.getColumnIndex("folder");
                out.add(new Entry(c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("title")),
                        c.getString(c.getColumnIndexOrThrow("url")),
                        c.getLong(c.getColumnIndexOrThrow("add_time")),
                        fIdx >= 0 ? c.getString(fIdx) : "根目录"));
            }
        } finally {
            c.close();
        }
        return out;
    }


    // ===== 离线页面 =====

    public boolean addOfflinePage(String title, String url, String filePath) {
        if (filePath == null || filePath.isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", title == null || title.isEmpty() ? filePath : title);
        cv.put("url", url == null ? "" : url);
        cv.put("file_path", filePath);
        cv.put("add_time", System.currentTimeMillis());
        try {
            return db.insertWithOnConflict(TABLE_OFFLINE, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean removeOfflinePage(long id) {
        return getWritableDatabase().delete(TABLE_OFFLINE, "id=?",
                new String[]{String.valueOf(id)}) > 0;
    }

    public List<OfflineEntry> listOfflinePages() {
        List<OfflineEntry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_OFFLINE, null, null, null,
                null, null, "add_time DESC");
        try {
            while (c.moveToNext()) {
                out.add(new OfflineEntry(c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("title")),
                        c.getString(c.getColumnIndexOrThrow("url")),
                        c.getString(c.getColumnIndexOrThrow("file_path")),
                        c.getLong(c.getColumnIndexOrThrow("add_time"))));
            }
        } finally {
            c.close();
        }
        return out;
    }

    // ===== 书签 =====

    public boolean addBookmark(String title, String url, String folder) {
        SQLiteDatabase database = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", title == null ? "" : title);
        cv.put("url", url);
        cv.put("folder", folder == null || folder.isEmpty() ? "根目录" : folder);
        cv.put("add_time", System.currentTimeMillis());
        long r = database.insertWithOnConflict(TABLE_BOOKMARKS, null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
        return r != -1;
    }

    // ===== 书签文件夹 =====

    public List<String> listFolders() {
        List<String> out = new ArrayList<>();
        SQLiteDatabase database = getReadableDatabase();
        Cursor c = database.query(TABLE_FOLDERS, new String[]{"name"}, null, null,
                null, null, "add_time ASC");
        try {
            while (c.moveToNext()) out.add(c.getString(0));
        } finally {
            c.close();
        }
        return out;
    }

    public boolean addFolder(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        SQLiteDatabase database = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("name", name.trim());
        cv.put("add_time", System.currentTimeMillis());
        return database.insertWithOnConflict(TABLE_FOLDERS, null, cv,
                SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public void removeFolder(String name) {
        SQLiteDatabase database = getWritableDatabase();
        database.delete(TABLE_FOLDERS, "name=?", new String[]{name});
        ContentValues cv = new ContentValues();
        cv.put("folder", "根目录");
        database.update(TABLE_BOOKMARKS, cv, "folder=?", new String[]{name});
    }

    /** 重命名文件夹并同步其下书签的 folder 列（名称唯一，冲突时返回 false）。 */
    public boolean renameFolder(String oldName, String newName) {
        if (oldName == null || newName == null) return false;
        String nn = newName.trim();
        if (nn.isEmpty() || nn.equals(oldName)) return false;
        try {
            SQLiteDatabase database = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put("name", nn);
            boolean ok = database.update(TABLE_FOLDERS, cv, "name=?",
                    new String[]{oldName}) > 0;
            if (ok) {
                cv.clear();
                cv.put("folder", nn);
                database.update(TABLE_BOOKMARKS, cv, "folder=?", new String[]{oldName});
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    /** 删除文件夹并连带删除其内书签（对齐真 Via：fg 文案明示连带删除）。 */
    public int deleteFolderWithBookmarks(String name) {
        SQLiteDatabase database = getWritableDatabase();
        int n = database.delete(TABLE_BOOKMARKS, "folder=?", new String[]{name});
        database.delete(TABLE_FOLDERS, "name=?", new String[]{name});
        return n;
    }

    public boolean addBookmark(String title, String url) {
        if (url == null || url.isEmpty()) return false;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", title == null ? url : title);
        cv.put("url", url);
        cv.put("add_time", System.currentTimeMillis());
        try {
            return db.insertWithOnConflict(TABLE_BOOKMARKS, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean removeBookmark(long id) {
        return getWritableDatabase().delete(TABLE_BOOKMARKS, "id=?",
                new String[]{String.valueOf(id)}) > 0;
    }

    /** 编辑书签（标题/地址/所在文件夹）。 */
    public boolean updateBookmark(long id, String title, String url, String folder) {
        if (url == null || url.trim().isEmpty()) return false;
        SQLiteDatabase database = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", title == null ? "" : title.trim());
        cv.put("url", url.trim());
        cv.put("folder", folder == null || folder.trim().isEmpty() ? "根目录" : folder.trim());
        try {
            return database.update(TABLE_BOOKMARKS, cv, "id=?",
                    new String[]{String.valueOf(id)}) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean removeBookmarkByUrl(String url) {
        return getWritableDatabase().delete(TABLE_BOOKMARKS, "url=?",
                new String[]{url}) > 0;
    }

    public boolean isBookmarked(String url) {
        Cursor c = getReadableDatabase().query(TABLE_BOOKMARKS, null, "url=?",
                new String[]{url}, null, null, null);
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public List<Entry> listBookmarks() {
        List<Entry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_BOOKMARKS, null, null, null,
                null, null, "add_time DESC");
        try {
            while (c.moveToNext()) {
                int fIdx = c.getColumnIndex("folder");
                out.add(new Entry(c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("title")),
                        c.getString(c.getColumnIndexOrThrow("url")),
                        c.getLong(c.getColumnIndexOrThrow("add_time")),
                        fIdx >= 0 ? c.getString(fIdx) : "根目录"));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** Atomically replaces the two data sets that Via cloud sync treats as snapshots. */
    public void replaceCloudEntries(List<Entry> bookmarks, List<Entry> favorites) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (bookmarks != null) {
                db.delete(TABLE_BOOKMARKS, null, null);
                db.delete(TABLE_FOLDERS, null, null);
                for (Entry entry : bookmarks) {
                    String folder = entry.folder == null || entry.folder.isEmpty() ? "根目录" : entry.folder;
                    if (!"根目录".equals(folder)) {
                        ContentValues fv = new ContentValues();
                        fv.put("name", folder);
                        fv.put("add_time", entry.time);
                        db.insertWithOnConflict(TABLE_FOLDERS, null, fv, SQLiteDatabase.CONFLICT_IGNORE);
                    }
                    ContentValues value = new ContentValues();
                    value.put("title", entry.title);
                    value.put("url", entry.url);
                    value.put("folder", folder);
                    value.put("add_time", entry.time);
                    db.insertWithOnConflict(TABLE_BOOKMARKS, null, value, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            if (favorites != null) {
                db.delete(TABLE_QUICKLINKS, null, null);
                for (Entry entry : favorites) {
                    ContentValues value = new ContentValues();
                    value.put("title", entry.title);
                    value.put("url", entry.url);
                    value.put("add_time", entry.time);
                    db.insertWithOnConflict(TABLE_QUICKLINKS, null, value, SQLiteDatabase.CONFLICT_REPLACE);
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ===== 历史 =====

    public void recordHistory(String title, String url) {
        if (url == null || url.isEmpty()) return;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put("title", title == null ? "" : title);
        cv.put("url", url);
        cv.put("visit_time", System.currentTimeMillis());
        try {
            db.insert(TABLE_HISTORY, null, cv);
        } catch (Exception ignored) {
        }
    }

    /** 删除单条历史。 */
    public boolean removeHistory(long id) {
        SQLiteDatabase database = getWritableDatabase();
        int n = database.delete(TABLE_HISTORY, "id=?", new String[]{String.valueOf(id)});
        return n > 0;
    }

    public void clearHistory() {
        getWritableDatabase().delete(TABLE_HISTORY, null, null);
    }

    public List<Entry> listHistory() {
        List<Entry> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(TABLE_HISTORY, null, null, null,
                null, null, "visit_time DESC", "500");
        try {
            while (c.moveToNext()) {
                int fIdx = c.getColumnIndex("folder");
                out.add(new Entry(c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getString(c.getColumnIndexOrThrow("title")),
                        c.getString(c.getColumnIndexOrThrow("url")),
                        c.getLong(c.getColumnIndexOrThrow("visit_time"))));
            }
        } finally {
            c.close();
        }
        return out;
    }

    /** 离线页面条目。 */
    public static final class OfflineEntry {
        public final long id;
        public final String title;
        public final String url;
        public final String filePath;
        public final long time;

        public OfflineEntry(long id, String title, String url, String filePath, long time) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.filePath = filePath;
            this.time = time;
        }
    }

    /** 书签/历史条目。 */
    public static final class Entry {
        public final long id;
        public final String title;
        public final String url;
        public final long time;
        /** 所属书签文件夹（v5 起）。 */
        public final String folder;

        public Entry(long id, String title, String url, long time) {
            this(id, title, url, time, "根目录");
        }

        public Entry(long id, String title, String url, long time, String folder) {
            this.id = id;
            this.title = title;
            this.url = url;
            this.time = time;
            this.folder = folder == null || folder.isEmpty() ? "根目录" : folder;
        }
    }
}
