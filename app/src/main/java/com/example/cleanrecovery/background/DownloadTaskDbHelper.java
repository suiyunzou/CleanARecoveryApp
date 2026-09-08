package com.example.cleanrecovery.background;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 下载任务持久化数据库（P0 A2）。
 *
 * <p>对应 yt-dlp 的 {@code download_archive} 持久化思路 + 队列状态恢复。
 * 将 {@link DownloadQueueManager.DownloadTask} 持久化到 SQLite，使应用被系统杀死
 * 或用户主动关闭后，未完成的下载任务能在重启时恢复并继续执行。</p>
 *
 * <h3>表结构</h3>
 * <pre>
 * CREATE TABLE tasks (
 *   id            INTEGER PRIMARY KEY,
 *   url           TEXT NOT NULL UNIQUE,
 *   mime_type     TEXT,
 *   page_url      TEXT,
 *   page_title    TEXT,
 *   priority      INTEGER DEFAULT 0,
 *   retry_count   INTEGER DEFAULT 0,
 *   status        TEXT NOT NULL DEFAULT 'PENDING',
 *   error_message TEXT,
 *   result_path   TEXT,
 *   file_hash     TEXT,
 *   file_size     INTEGER DEFAULT 0,
 *   created_at    INTEGER NOT NULL
 * );
 * </pre>
 *
 * <p><b>恢复策略</b>：Service 重启时查询 status 为 PENDING 或 RUNNING 的任务，
 * 将 RUNNING 重置为 PENDING（因上次执行被中断），重新入队。
 * COMPLETED/FAILED/CANCELLED 任务保留作历史记录，不重新入队。</p>
 */
public final class DownloadTaskDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "DownloadTaskDb";
    private static final String DB_NAME = "download_tasks.db";
    private static final int DB_VERSION = 3;
    private static final String TABLE = "tasks";

    // 列名
    static final String COL_ID = "id";
    static final String COL_URL = "url";
    static final String COL_MIME = "mime_type";
    static final String COL_PAGE_URL = "page_url";
    static final String COL_PAGE_TITLE = "page_title";
    static final String COL_PRIORITY = "priority";
    static final String COL_RETRY = "retry_count";
    static final String COL_STATUS = "status";
    static final String COL_ERROR = "error_message";
    static final String COL_RESULT_PATH = "result_path";
    static final String COL_FILE_HASH = "file_hash";
    static final String COL_FILE_SIZE = "file_size";
    static final String COL_CREATED_AT = "created_at";
    // v2：下载页展示用（嗅探下载联动）
    static final String COL_FILE_NAME = "file_name";
    static final String COL_CATEGORY = "category";
    static final String COL_DOWNLOADED = "downloaded";
    static final String COL_TOTAL_SIZE = "total_size";
    // v3：用户发起下载时的请求头快照。
    static final String COL_REQUEST_HEADERS = "request_headers";
    static final String COL_RAW_RESOURCE = "raw_resource";

    private static volatile DownloadTaskDbHelper sInstance;

    /** 进程级单例（浏览器多处共用，避免多句柄写竞争）。 */
    public static DownloadTaskDbHelper getInstance(Context context) {
        if (sInstance == null) {
            synchronized (DownloadTaskDbHelper.class) {
                if (sInstance == null) {
                    sInstance = new DownloadTaskDbHelper(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    public DownloadTaskDbHelper(@Nullable Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                COL_ID + " INTEGER PRIMARY KEY, " +
                COL_URL + " TEXT NOT NULL UNIQUE, " +
                COL_MIME + " TEXT, " +
                COL_PAGE_URL + " TEXT, " +
                COL_PAGE_TITLE + " TEXT, " +
                COL_PRIORITY + " INTEGER DEFAULT 0, " +
                COL_RETRY + " INTEGER DEFAULT 0, " +
                COL_STATUS + " TEXT NOT NULL DEFAULT 'PENDING', " +
                COL_ERROR + " TEXT, " +
                COL_RESULT_PATH + " TEXT, " +
                COL_FILE_HASH + " TEXT, " +
                COL_FILE_SIZE + " INTEGER DEFAULT 0, " +
                COL_CREATED_AT + " INTEGER NOT NULL, " +
                COL_FILE_NAME + " TEXT, " +
                COL_CATEGORY + " TEXT, " +
                COL_DOWNLOADED + " INTEGER DEFAULT 0, " +
                COL_TOTAL_SIZE + " INTEGER DEFAULT 0, " +
                COL_REQUEST_HEADERS + " TEXT NOT NULL DEFAULT '{}', " +
                COL_RAW_RESOURCE + " INTEGER NOT NULL DEFAULT 0)");
        // 按状态查询的索引
        db.execSQL("CREATE INDEX idx_tasks_status ON " + TABLE + "(" + COL_STATUS + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v2 追加展示列（嗅探下载联动），不重建保留历史
            try {
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_FILE_NAME + " TEXT");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_CATEGORY + " TEXT");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_DOWNLOADED + " INTEGER DEFAULT 0");
                db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_TOTAL_SIZE + " INTEGER DEFAULT 0");
            } catch (Exception e) {
                Log.w(TAG, "v2 升级失败，重建: " + e.getMessage());
                db.execSQL("DROP TABLE IF EXISTS " + TABLE);
                onCreate(db);
                return;
            }
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_REQUEST_HEADERS
                    + " TEXT NOT NULL DEFAULT '{}'");
            db.execSQL("ALTER TABLE " + TABLE + " ADD COLUMN " + COL_RAW_RESOURCE
                    + " INTEGER NOT NULL DEFAULT 0");
        }
    }

    /** 插入新任务（PENDING 状态）。 */
    public long insertTask(DownloadQueueManager.DownloadTask task) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_ID, task.id);
        cv.put(COL_URL, task.url);
        cv.put(COL_MIME, task.mimeType);
        cv.put(COL_PAGE_URL, task.pageUrl);
        cv.put(COL_PAGE_TITLE, task.pageTitle);
        cv.put(COL_PRIORITY, task.priority);
        cv.put(COL_RETRY, task.retryCount);
        cv.put(COL_STATUS, task.status.name());
        cv.put(COL_REQUEST_HEADERS, new JSONObject(task.requestHeaders).toString());
        cv.put(COL_RAW_RESOURCE, task.rawResource ? 1 : 0);
        if (task.fileName != null) cv.put(COL_FILE_NAME, task.fileName);
        cv.put(COL_CREATED_AT, System.currentTimeMillis());
        try {
            long rowId = db.insertWithOnConflict(TABLE, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);
            Log.d(TAG, "插入任务#" + task.id + " rowId=" + rowId);
            return rowId;
        } catch (Exception e) {
            Log.w(TAG, "插入任务失败: " + e.getMessage());
            return -1;
        }
    }

    /** 更新任务状态。 */
    public void updateStatus(int taskId, DownloadQueueManager.DownloadTask.TaskStatus status,
                              String errorMessage) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_STATUS, status.name());
        if (errorMessage != null) cv.put(COL_ERROR, errorMessage);
        try {
            db.update(TABLE, cv, COL_ID + "=?", new String[]{String.valueOf(taskId)});
        } catch (Exception e) {
            Log.w(TAG, "更新状态失败: " + e.getMessage());
        }
    }

    /** 更新下载结果（路径 + 大小 + 哈希 + 状态 COMPLETED）。 */
    public void updateResult(int taskId, String resultPath, long fileSize, String fileHash) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_STATUS, "COMPLETED");
        cv.put(COL_RESULT_PATH, resultPath);
        cv.put(COL_FILE_SIZE, fileSize);
        cv.put(COL_FILE_HASH, fileHash);
        try {
            db.update(TABLE, cv, COL_ID + "=?", new String[]{String.valueOf(taskId)});
        } catch (Exception e) {
            Log.w(TAG, "更新结果失败: " + e.getMessage());
        }
    }

    /**
     * 查询可恢复的任务（PENDING + RUNNING）。
     *
     * <p>RUNNING 任务会被重置为 PENDING（因上次执行被中断）。</p>
     *
     * @return 待恢复任务列表（按优先级降序）
     */
    public List<DownloadQueueManager.DownloadTask> getRestorableTasks() {
        List<DownloadQueueManager.DownloadTask> tasks = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String[] cols = {COL_ID, COL_URL, COL_MIME, COL_PAGE_URL, COL_PAGE_TITLE,
                COL_PRIORITY, COL_RETRY, COL_STATUS, COL_FILE_NAME, COL_REQUEST_HEADERS,
                COL_RAW_RESOURCE};
        try (Cursor c = db.query(TABLE, cols,
                COL_STATUS + " IN ('PENDING','RUNNING')",
                null, null, null, COL_PRIORITY + " DESC")) {
            while (c.moveToNext()) {
                int id = c.getInt(c.getColumnIndexOrThrow(COL_ID));
                String url = c.getString(c.getColumnIndexOrThrow(COL_URL));
                String mime = c.getString(c.getColumnIndexOrThrow(COL_MIME));
                String pageUrl = c.getString(c.getColumnIndexOrThrow(COL_PAGE_URL));
                String pageTitle = c.getString(c.getColumnIndexOrThrow(COL_PAGE_TITLE));
                int priority = c.getInt(c.getColumnIndexOrThrow(COL_PRIORITY));
                int retry = c.getInt(c.getColumnIndexOrThrow(COL_RETRY));

                DownloadQueueManager.DownloadTask task =
                        new DownloadQueueManager.DownloadTask(id, url, mime, pageUrl,
                                pageTitle, priority, requestHeaders(c),
                                c.getInt(c.getColumnIndexOrThrow(COL_RAW_RESOURCE)) != 0);
                task.fileName = c.getString(c.getColumnIndexOrThrow(COL_FILE_NAME));
                task.retryCount = retry;
                // 重置为 PENDING（上次 RUNNING 被中断）
                task.status = DownloadQueueManager.DownloadTask.TaskStatus.PENDING;
                tasks.add(task);
            }
        } catch (Exception e) {
            Log.w(TAG, "查询可恢复任务失败: " + e.getMessage());
        }
        return tasks;
    }

    /** 查询数据库中最大的任务 ID（用于恢复 taskIdGenerator）。 */
    public int getMaxTaskId() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT MAX(" + COL_ID + ") FROM " + TABLE, null)) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getInt(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "查询 max id 失败: " + e.getMessage());
        }
        return 0;
    }

    /** 删除已完成/失败的任务记录（清理历史，保留 PENDING/RUNNING）。 */
    public int clearFinishedTasks() {
        SQLiteDatabase db = getWritableDatabase();
        try {
            int deleted = db.delete(TABLE,
                    COL_STATUS + " IN ('COMPLETED','FAILED','CANCELLED')", null);
            Log.i(TAG, "清理已完成/失败任务: " + deleted + " 条");
            return deleted;
        } catch (Exception e) {
            Log.w(TAG, "清理任务失败: " + e.getMessage());
            return 0;
        }
    }

    /** 查询全部下载任务（浏览器「下载」管理页使用）。 */
    public List<DownloadQueueManager.DownloadTask> getAllTasks() {        List<DownloadQueueManager.DownloadTask> tasks = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String[] cols = {COL_ID, COL_URL, COL_MIME, COL_PAGE_URL, COL_PAGE_TITLE,
                COL_PRIORITY, COL_RETRY, COL_STATUS, COL_ERROR, COL_RESULT_PATH,
                COL_FILE_HASH, COL_FILE_SIZE, COL_FILE_NAME, COL_REQUEST_HEADERS, COL_RAW_RESOURCE};
        try (Cursor c = db.query(TABLE, cols,
                null, null, null, null, COL_CREATED_AT + " DESC")) {
            while (c.moveToNext()) {
                int id = c.getInt(c.getColumnIndexOrThrow(COL_ID));
                String url = c.getString(c.getColumnIndexOrThrow(COL_URL));
                String mime = c.getString(c.getColumnIndexOrThrow(COL_MIME));
                String pageUrl = c.getString(c.getColumnIndexOrThrow(COL_PAGE_URL));
                String pageTitle = c.getString(c.getColumnIndexOrThrow(COL_PAGE_TITLE));
                int priority = c.getInt(c.getColumnIndexOrThrow(COL_PRIORITY));
                int retry = c.getInt(c.getColumnIndexOrThrow(COL_RETRY));

                DownloadQueueManager.DownloadTask task =
                        new DownloadQueueManager.DownloadTask(id, url, mime, pageUrl,
                                pageTitle, priority, requestHeaders(c),
                                c.getInt(c.getColumnIndexOrThrow(COL_RAW_RESOURCE)) != 0);
                task.fileName = c.getString(c.getColumnIndexOrThrow(COL_FILE_NAME));
                task.retryCount = retry;
                try {
                    task.status = DownloadQueueManager.DownloadTask.TaskStatus.valueOf(
                            c.getString(c.getColumnIndexOrThrow(COL_STATUS)));
                } catch (Exception ignored) {
                    task.status = DownloadQueueManager.DownloadTask.TaskStatus.PENDING;
                }
                task.errorMessage = c.getString(c.getColumnIndexOrThrow(COL_ERROR));
                task.resultPath = c.getString(c.getColumnIndexOrThrow(COL_RESULT_PATH));
                task.fileHash = c.getString(c.getColumnIndexOrThrow(COL_FILE_HASH));
                task.fileSize = c.getLong(c.getColumnIndexOrThrow(COL_FILE_SIZE));
                tasks.add(task);
            }
        } catch (Exception e) {
            Log.w(TAG, "查询全部任务失败: " + e.getMessage());
        }
        return tasks;
    }

    private static Map<String, String> requestHeaders(Cursor cursor) {
        String value = cursor.getString(cursor.getColumnIndexOrThrow(COL_REQUEST_HEADERS));
        if (value == null || value.isEmpty()) return Collections.emptyMap();
        try {
            JSONObject json = new JSONObject(value);
            Map<String, String> headers = new HashMap<>();
            Iterator<String> names = json.keys();
            while (names.hasNext()) {
                String name = names.next();
                Object header = json.opt(name);
                if (header instanceof String) headers.put(name, (String) header);
            }
            return headers;
        } catch (JSONException e) {
            Log.w(TAG, "下载任务请求头无效，使用默认请求头");
            return Collections.emptyMap();
        }
    }

    // ===== 嗅探下载联动（下载页展示：分类/进度/大小） =====

    /** 下载页条目（含 v2 展示列）。 */
    public static final class DownloadRow {
        public int id;
        public String url, mimeType, pageTitle, fileName, category, status, errorMessage, resultPath;
        public long downloaded, totalSize, createdAt;

        public boolean running() { return "RUNNING".equals(status) || "PENDING".equals(status); }
    }

    /** 记录嗅探下载开始（RUNNING）。返回任务 id。 */
    public int startSniffedTask(String url, String mime, String pageTitle,
                                String fileName, String category) {
        SQLiteDatabase db = getWritableDatabase();
        int id = getMaxTaskId() + 1;
        ContentValues cv = new ContentValues();
        cv.put(COL_ID, id);
        cv.put(COL_URL, url);
        cv.put(COL_MIME, mime);
        cv.put(COL_PAGE_TITLE, pageTitle);
        cv.put(COL_STATUS, "RUNNING");
        cv.put(COL_FILE_NAME, fileName);
        cv.put(COL_CATEGORY, category);
        cv.put(COL_CREATED_AT, System.currentTimeMillis());
        try {
            db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
            return id;
        } catch (Exception e) {
            Log.w(TAG, "嗅探任务插入失败: " + e.getMessage());
            return -1;
        }
    }

    public void updateProgress(int taskId, long downloaded, long totalSize) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_DOWNLOADED, downloaded);
        if (totalSize > 0) cv.put(COL_TOTAL_SIZE, totalSize);
        try {
            db.update(TABLE, cv, COL_ID + "=?", new String[]{String.valueOf(taskId)});
        } catch (Exception ignored) {
        }
    }

    /** 嗅探下载完成：路径 + 大小 + COMPLETED。 */
    public void finishSniffedTask(int taskId, String resultPath, long size) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_STATUS, "COMPLETED");
        cv.put(COL_RESULT_PATH, resultPath);
        cv.put(COL_DOWNLOADED, size);
        if (size > 0) {
            cv.put(COL_TOTAL_SIZE, size);
            cv.put(COL_FILE_SIZE, size);
        }
        try {
            db.update(TABLE, cv, COL_ID + "=?", new String[]{String.valueOf(taskId)});
        } catch (Exception ignored) {
        }
    }

    public void failSniffedTask(int taskId, String message) {
        updateStatus(taskId, DownloadQueueManager.DownloadTask.TaskStatus.FAILED, message);
    }

    /** 下载页查询：全部行（新到旧），含展示列。 */
    public List<DownloadRow> listRows() {
        List<DownloadRow> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query(TABLE, null, null, null, null, null,
                COL_CREATED_AT + " DESC")) {
            while (c.moveToNext()) {
                DownloadRow r = new DownloadRow();
                r.id = c.getInt(c.getColumnIndexOrThrow(COL_ID));
                r.url = c.getString(c.getColumnIndexOrThrow(COL_URL));
                r.mimeType = c.getString(c.getColumnIndexOrThrow(COL_MIME));
                r.pageTitle = c.getString(c.getColumnIndexOrThrow(COL_PAGE_TITLE));
                r.status = c.getString(c.getColumnIndexOrThrow(COL_STATUS));
                r.errorMessage = c.getString(c.getColumnIndexOrThrow(COL_ERROR));
                r.resultPath = c.getString(c.getColumnIndexOrThrow(COL_RESULT_PATH));
                r.downloaded = c.getLong(c.getColumnIndexOrThrow(COL_DOWNLOADED));
                r.totalSize = c.getLong(c.getColumnIndexOrThrow(COL_TOTAL_SIZE));
                r.createdAt = c.getLong(c.getColumnIndexOrThrow(COL_CREATED_AT));
                int fn = c.getColumnIndex(COL_FILE_NAME);
                r.fileName = fn >= 0 ? c.getString(fn) : null;
                int cat = c.getColumnIndex(COL_CATEGORY);
                r.category = cat >= 0 ? c.getString(cat) : null;
                if (r.fileName == null || r.fileName.isEmpty()) {
                    // 队列任务没有 file_name 列值：从路径或 URL 推导
                    String src = (r.resultPath != null && !r.resultPath.isEmpty())
                            ? r.resultPath : r.url;
                    if (src != null) {
                        int slash = src.lastIndexOf('/');
                        r.fileName = slash >= 0 ? src.substring(slash + 1) : src;
                        int q = r.fileName.indexOf('?');
                        if (q > 0) r.fileName = r.fileName.substring(0, q);
                    }
                }
                if (r.category == null || r.category.isEmpty()) {
                    r.category = categoryOf(r.url, r.mimeType);
                }
                out.add(r);
            }
        } catch (Exception e) {
            Log.w(TAG, "查询下载行失败: " + e.getMessage());
        }
        return out;
    }

    /** 删除单条下载记录。 */
    public int deleteRow(int id) {
        return getWritableDatabase().delete(TABLE, COL_ID + "=?",
                new String[]{String.valueOf(id)});
    }

    /** 七分类（对齐真 Via 下载页 chips）：文档/压缩包/安装包/图片/视频/音频，其余=文档。 */
    public static String categoryOf(String url, String mime) {
        String u = (url == null ? "" : url).toLowerCase(Locale.ROOT);
        String m = (mime == null ? "" : mime).toLowerCase(Locale.ROOT);
        if (m.startsWith("video/") || u.contains(".m3u8") || u.contains(".mp4")
                || u.contains(".mkv") || u.contains(".webm") || u.contains(".flv")
                || u.contains(".ts")) return "视频";
        if (m.startsWith("audio/") || u.contains(".mp3") || u.contains(".m4a")
                || u.contains(".flac") || u.contains(".wav") || u.contains(".aac")
                || u.contains(".ogg")) return "音频";
        if (u.contains(".apk") || m.contains("android.package-archive")
                || u.contains(".ipa") || u.contains(".exe")) return "安装包";
        if (u.contains(".zip") || u.contains(".rar") || u.contains(".7z")
                || u.contains(".tar") || u.contains(".gz")) return "压缩包";
        if (m.startsWith("image/") || u.contains(".jpg") || u.contains(".jpeg")
                || u.contains(".png") || u.contains(".gif") || u.contains(".webp")) return "图片";
        return "文档";
    }
}
