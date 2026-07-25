package com.example.cleanrecovery.background;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

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
    private static final int DB_VERSION = 1;
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

    public DownloadTaskDbHelper(@Nullable Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
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
                COL_CREATED_AT + " INTEGER NOT NULL)");
        // 按状态查询的索引
        db.execSQL("CREATE INDEX idx_tasks_status ON " + TABLE + "(" + COL_STATUS + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // M1 简化：升级时重建（无历史数据需保留）
        Log.w(TAG, "DB upgrade " + oldVersion + "->" + newVersion + ", 重建表");
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
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
                COL_PRIORITY, COL_RETRY, COL_STATUS};
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
                                pageTitle, priority);
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
    public List<DownloadQueueManager.DownloadTask> getAllTasks() {
        List<DownloadQueueManager.DownloadTask> tasks = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        String[] cols = {COL_ID, COL_URL, COL_MIME, COL_PAGE_URL, COL_PAGE_TITLE,
                COL_PRIORITY, COL_RETRY, COL_STATUS, COL_ERROR, COL_RESULT_PATH,
                COL_FILE_HASH, COL_FILE_SIZE};
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
                                pageTitle, priority);
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
}
