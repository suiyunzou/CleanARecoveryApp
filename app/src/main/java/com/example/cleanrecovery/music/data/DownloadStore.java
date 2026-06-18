package com.example.cleanrecovery.music.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** SQLite store for downloaded songs. Tracks local paths, quality, and sizes. */
public class DownloadStore extends SQLiteOpenHelper {

    private static final String DB = "music_downloads.db";
    private static final int VERSION = 1;

    public DownloadStore(Context ctx) {
        super(ctx, DB, null, VERSION);
        getWritableDatabase();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE downloads (" +
                "hash TEXT PRIMARY KEY," +
                "title TEXT NOT NULL," +
                "artist TEXT," +
                "album TEXT," +
                "duration INTEGER DEFAULT 0," +
                "quality TEXT," +
                "local_path TEXT NOT NULL," +
                "size_bytes INTEGER DEFAULT 0," +
                "downloaded_at INTEGER DEFAULT 0," +
                "img_url TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    /** Insert or replace a completed download record. */
    public void upsert(DownloadedSong song) {
        if (song == null || song.hash == null) return;
        ContentValues cv = new ContentValues();
        cv.put("hash", song.hash);
        cv.put("title", song.title);
        cv.put("artist", song.artist);
        cv.put("album", song.album);
        cv.put("duration", song.duration);
        cv.put("quality", song.quality);
        cv.put("local_path", song.localPath);
        cv.put("size_bytes", song.sizeBytes);
        cv.put("downloaded_at", song.downloadedAt);
        cv.put("img_url", song.imgUrl);
        getWritableDatabase().insertWithOnConflict("downloads", null, cv,
                SQLiteDatabase.CONFLICT_REPLACE);
    }

    public boolean exists(String hash) {
        if (hash == null) return false;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM downloads WHERE hash=?", new String[]{hash})) {
            return c.moveToFirst();
        }
    }

    /** Return the local path for a downloaded hash, or null if not present. */
    public String localPath(String hash) {
        if (hash == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT local_path FROM downloads WHERE hash=?", new String[]{hash})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    public DownloadedSong get(String hash) {
        if (hash == null) return null;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT hash,title,artist,album,duration,quality,local_path,size_bytes,downloaded_at,img_url "
                        + "FROM downloads WHERE hash=?", new String[]{hash})) {
            if (!c.moveToFirst()) return null;
            return read(c);
        }
    }

    public List<DownloadedSong> all() {
        List<DownloadedSong> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT hash,title,artist,album,duration,quality,local_path,size_bytes,downloaded_at,img_url "
                        + "FROM downloads ORDER BY downloaded_at DESC", null)) {
            while (c.moveToNext()) list.add(read(c));
        }
        return list;
    }

    public void delete(String hash) {
        if (hash == null) return;
        getWritableDatabase().delete("downloads", "hash=?", new String[]{hash});
    }

    public int count() {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM downloads", null)) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Total bytes used by all downloaded songs (according to the DB). */
    public long totalSizeBytes() {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COALESCE(SUM(size_bytes),0) FROM downloads", null)) {
            return c.moveToFirst() ? c.getLong(0) : 0;
        }
    }

    private static DownloadedSong read(Cursor c) {
        DownloadedSong d = new DownloadedSong();
        d.hash = c.getString(0);
        d.title = c.getString(1);
        d.artist = c.getString(2);
        d.album = c.getString(3);
        d.duration = c.getInt(4);
        d.quality = c.getString(5);
        d.localPath = c.getString(6);
        d.sizeBytes = c.getLong(7);
        d.downloadedAt = c.getLong(8);
        d.imgUrl = c.getString(9);
        return d;
    }
}
