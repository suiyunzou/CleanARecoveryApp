package com.example.cleanrecovery.music.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.example.cleanrecovery.music.api.IMusicDataSource;
import com.example.cleanrecovery.music.data.SongInfo;

import java.util.ArrayList;
import java.util.List;

/** SQLite store for playlists and songs. UI-agnostic. */
public class PlaylistStore extends SQLiteOpenHelper {

    private static final String DB = "music_playlists.db";
    private static final int VERSION = 2;
    /** Reserved playlist names that cannot be deleted or renamed. */
    private static final java.util.Set<String> PROTECTED =
            new java.util.HashSet<>(java.util.Arrays.asList("Favorites", "Listen Later", "Recently Played"));

    public PlaylistStore(Context ctx) {
        super(ctx, DB, null, VERSION);
        getWritableDatabase();
        ensureDefaults();
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE playlists (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE)");
        db.execSQL("CREATE TABLE songs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "playlist_id INTEGER NOT NULL," +
                "hash TEXT," +
                "album_id TEXT," +
                "title TEXT NOT NULL," +
                "artist TEXT," +
                "album TEXT," +
                "duration INTEGER DEFAULT 0," +
                "img_url TEXT," +
                "vip_required INTEGER DEFAULT 0," +
                "position INTEGER DEFAULT 0," +
                "FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON DELETE CASCADE)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int o, int n) {
        // v1 → v2: no schema change needed (position column already existed);
        // added reorder/rename helpers. Keep data intact.
    }

    /** True for built-in playlists that cannot be renamed or deleted. */
    public boolean isProtected(String name) {
        return PROTECTED.contains(name);
    }

    private void ensureDefaults() {
        if (listPlaylists().isEmpty()) {
            createPlaylist("Favorites");
            createPlaylist("Listen Later");
        }
    }

    // ---- Playlists -------------------------------------------------------

    public List<String> listPlaylists() {
        List<String> names = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT name FROM playlists ORDER BY id", null)) {
            while (c.moveToNext()) names.add(c.getString(0));
        }
        return names;
    }

    public long createPlaylist(String name) {
        ContentValues cv = new ContentValues();
        cv.put("name", name);
        return getWritableDatabase().insert("playlists", null, cv);
    }

    public void deletePlaylist(String name) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("songs", "playlist_id=(SELECT id FROM playlists WHERE name=?)",
                new String[]{name});
        db.delete("playlists", "name=?", new String[]{name});
    }

    /** Rename a playlist. Returns false if the new name is taken or playlist is protected. */
    public boolean renamePlaylist(String oldName, String newName) {
        if (isProtected(oldName)) return false;
        if (newName == null || newName.trim().isEmpty()) return false;
        if (playlistId(newName) > 0) return false; // name already exists
        long pid = playlistId(oldName);
        if (pid < 0) return false;
        ContentValues cv = new ContentValues();
        cv.put("name", newName.trim());
        return getWritableDatabase().update("playlists", cv,
                "id=?", new String[]{String.valueOf(pid)}) > 0;
    }

    /** Remove all songs from a playlist but keep the playlist itself. */
    public void clearPlaylist(String name) {
        long pid = playlistId(name);
        if (pid < 0) return;
        getWritableDatabase().delete("songs", "playlist_id=?",
                new String[]{String.valueOf(pid)});
    }

    public long playlistId(String name) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id FROM playlists WHERE name=?", new String[]{name})) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    // ---- Songs -----------------------------------------------------------

    public void addSong(String playlistName, SongInfo song) {
        long pid = playlistId(playlistName);
        if (pid < 0) pid = createPlaylist(playlistName);
        if (hasSong(playlistName, song)) return;

        int maxPos = 0;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT MAX(position) FROM songs WHERE playlist_id=?", new String[]{String.valueOf(pid)})) {
            if (c.moveToFirst()) maxPos = c.getInt(0);
        }

        ContentValues cv = new ContentValues();
        cv.put("playlist_id", pid);
        cv.put("hash", song.hash);
        cv.put("album_id", song.albumId);
        cv.put("title", song.title);
        cv.put("artist", song.artist);
        cv.put("album", song.album);
        cv.put("duration", song.duration);
        cv.put("img_url", song.imgUrl);
        cv.put("vip_required", song.vipRequired ? 1 : 0);
        cv.put("position", maxPos + 1);
        getWritableDatabase().insert("songs", null, cv);
    }

    public void removeSong(String playlistName, SongInfo song) {
        long pid = playlistId(playlistName);
        if (pid < 0) return;
        getWritableDatabase().delete("songs", "playlist_id=? AND hash=?",
                new String[]{String.valueOf(pid), song.hash});
    }

    public boolean hasSong(String playlistName, SongInfo song) {
        long pid = playlistId(playlistName);
        if (pid < 0) return false;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT 1 FROM songs WHERE playlist_id=? AND hash=?",
                new String[]{String.valueOf(pid), song.hash})) {
            return c.moveToFirst();
        }
    }

    /**
     * Move a song within a playlist from {@code fromPos} to {@code toPos}
     * (0-based positions in the current ordered list). Other songs shift to
     * fill the gap. Positions are re-sequenced starting from 1.
     */
    public void moveSong(String playlistName, int fromPos, int toPos) {
        long pid = playlistId(playlistName);
        if (pid < 0) return;
        List<SongInfo> songs = getSongs(playlistName);
        if (fromPos < 0 || fromPos >= songs.size() || toPos < 0 || toPos >= songs.size()) return;
        if (fromPos == toPos) return;
        SongInfo moved = songs.remove(fromPos);
        songs.add(toPos, moved);
        resequence(pid, songs);
    }

    /** Persist the given order (by hash) for a playlist. Used by drag-to-reorder UIs. */
    public void setOrder(String playlistName, List<SongInfo> ordered) {
        long pid = playlistId(playlistName);
        if (pid < 0) return;
        resequence(pid, ordered);
    }

    private void resequence(long pid, List<SongInfo> ordered) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ordered.size(); i++) {
                SongInfo s = ordered.get(i);
                if (s.hash == null || s.hash.isEmpty()) continue;
                ContentValues cv = new ContentValues();
                cv.put("position", i + 1);
                db.update("songs", cv, "playlist_id=? AND hash=?",
                        new String[]{String.valueOf(pid), s.hash});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<SongInfo> getSongs(String playlistName) {
        List<SongInfo> list = new ArrayList<>();
        long pid = playlistId(playlistName);
        if (pid < 0) return list;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT hash,album_id,title,artist,album,duration,img_url,vip_required " +
                        "FROM songs WHERE playlist_id=? ORDER BY position",
                new String[]{String.valueOf(pid)})) {
            while (c.moveToNext()) {
                SongInfo s = new SongInfo();
                s.hash = c.getString(0);
                s.albumId = c.getString(1);
                s.title = c.getString(2);
                s.artist = c.getString(3);
                s.album = c.getString(4);
                s.duration = c.getInt(5);
                s.imgUrl = c.getString(6);
                s.vipRequired = c.getInt(7) == 1;
                list.add(s);
            }
        }
        return list;
    }

    public int songCount(String playlistName) {
        long pid = playlistId(playlistName);
        if (pid < 0) return 0;
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM songs WHERE playlist_id=?",
                new String[]{String.valueOf(pid)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    public List<SongInfo> getRecentPlays(int limit) {
        List<SongInfo> list = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT hash,album_id,title,artist,album,duration,img_url,vip_required " +
                        "FROM songs WHERE playlist_id=(SELECT id FROM playlists WHERE name='Recently Played') " +
                        "ORDER BY id DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                SongInfo s = new SongInfo();
                s.hash = c.getString(0);
                s.albumId = c.getString(1);
                s.title = c.getString(2);
                s.artist = c.getString(3);
                s.album = c.getString(4);
                s.duration = c.getInt(5);
                s.imgUrl = c.getString(6);
                s.vipRequired = c.getInt(7) == 1;
                list.add(s);
            }
        }
        return list;
    }

    public void addRecentPlay(SongInfo song) {
        long pid = playlistId("Recently Played");
        if (pid < 0) {
            pid = createPlaylist("Recently Played");
        }
        // Remove old entry then re-add (moves to top)
        getWritableDatabase().delete("songs", "playlist_id=? AND hash=?",
                new String[]{String.valueOf(pid), song.hash});
        ContentValues cv = new ContentValues();
        cv.put("playlist_id", pid);
        cv.put("hash", song.hash);
        cv.put("album_id", song.albumId);
        cv.put("title", song.title);
        cv.put("artist", song.artist);
        cv.put("album", song.album);
        cv.put("duration", song.duration);
        cv.put("img_url", song.imgUrl);
        cv.put("vip_required", song.vipRequired ? 1 : 0);
        cv.put("position", 0);
        getWritableDatabase().insert("songs", null, cv);
        // Keep last 50
        getWritableDatabase().execSQL(
                "DELETE FROM songs WHERE playlist_id=? AND id NOT IN (" +
                        "SELECT id FROM songs WHERE playlist_id=? ORDER BY id DESC LIMIT 50)",
                new Object[]{pid, pid});
    }
}
