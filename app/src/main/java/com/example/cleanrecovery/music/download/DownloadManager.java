package com.example.cleanrecovery.music.download;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;

import com.example.cleanrecovery.music.api.AudioFormatDetector;
import com.example.cleanrecovery.music.api.IMusicDataSource;
import com.example.cleanrecovery.music.data.DownloadedSong;
import com.example.cleanrecovery.music.data.DownloadStore;
import com.example.cleanrecovery.music.data.SongInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages song downloads: resolves a URL at the requested quality, streams the
 * body to local storage, reports progress, and records the result in
 * {@link DownloadStore}.
 *
 * <p>One download at a time per song hash; concurrent downloads of different
 * songs are allowed. Cancellation is best-effort: the worker checks a flag
 * between buffer reads and deletes the partial file.
 */
public class DownloadManager {

    private static final String TAG = "DownloadManager";
    private static final int BUFFER_SIZE = 8 * 1024;
    /** Safety margin kept free on disk after a download completes. */
    private static final long MIN_FREE_BYTES = 50L * 1024 * 1024; // 50 MB

    public static final String QUALITY_STANDARD = "128";
    public static final String QUALITY_HIGH = "320";
    public static final String QUALITY_LOSSLESS = "flac";

    public enum State { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    public interface ProgressListener {
        /**
         * Called on the main thread as the download progresses.
         *
         * @param song         the song being downloaded
         * @param state        current state (RUNNING while downloading, COMPLETED/FAILED/CANCELLED at the end)
         * @param downloadedBytes bytes written so far
         * @param totalBytes   total expected bytes (-1 if unknown)
         * @param message      human-readable message (error detail or status)
         */
        void onProgress(SongInfo song, State state, long downloadedBytes, long totalBytes, String message);
    }

    private final IMusicDataSource dataSource;
    private final DownloadStore store;
    private final Context context;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Per-hash cancellation flags. Presence implies an active download. */
    private final Map<String, AtomicLong> active = new HashMap<>();

    public DownloadManager(Context ctx, IMusicDataSource dataSource, DownloadStore store) {
        this.context = ctx.getApplicationContext();
        this.dataSource = dataSource;
        this.store = store;
    }

    /** True when a download for the given hash is currently running. */
    public boolean isDownloading(String hash) {
        synchronized (active) {
            return hash != null && active.containsKey(hash);
        }
    }

    /** Begin downloading {@code song} at the given quality. No-op if already running. */
    public void enqueue(SongInfo song, String quality, ProgressListener listener) {
        if (song == null || song.hash == null) return;
        synchronized (active) {
            if (active.containsKey(song.hash)) return;
            active.put(song.hash, new AtomicLong(-1));
        }
        final String q = quality == null ? QUALITY_STANDARD : quality;
        executor.execute(() -> runDownload(song, q, listener));
    }

    /** Request cancellation of the download for the given hash. */
    public void cancel(String hash) {
        if (hash == null) return;
        synchronized (active) {
            AtomicLong flag = active.get(hash);
            if (flag != null) flag.set(-2); // sentinel: cancel requested
        }
    }

    // ---- Storage helpers --------------------------------------------------

    /** Root directory for downloaded music. Falls back to cache dir on failure. */
    public File downloadsDir() {
        File base = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (base == null) base = new File(context.getFilesDir(), "music");
        File dir = new File(base, "downloads");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "downloadsDir mkdirs failed: " + dir.getAbsolutePath());
        }
        return dir;
    }

    /** Free bytes on the volume hosting {@link #downloadsDir()}. */
    public long availableBytes() {
        try {
            StatFs stat = new StatFs(downloadsDir().getAbsolutePath());
            return stat.getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /** Total bytes used by all completed downloads (per the store). */
    public long usedBytes() {
        return store.totalSizeBytes();
    }

    // ---- Store pass-throughs (convenience for UI) -------------------------

    public boolean exists(String hash) { return store.exists(hash); }
    public DownloadedSong get(String hash) { return store.get(hash); }
    public java.util.List<DownloadedSong> all() { return store.all(); }
    public void delete(String hash) { store.delete(hash); }

    // ---- Internal ---------------------------------------------------------

    private void runDownload(SongInfo song, String quality, ProgressListener listener) {
        final AtomicLong flag;
        synchronized (active) {
            flag = active.get(song.hash);
            if (flag == null) return; // shouldn't happen
        }
        notify(listener, song, State.RUNNING, 0, -1, null);

        // 1. Resolve the download URL.
        String url;
        try {
            url = dataSource.resolveDownloadUrl(song, quality);
        } catch (Exception e) {
            finish(song, State.FAILED, listener, "URL resolution failed: " + e.getMessage());
            return;
        }
        if (isCancelled(flag)) { finish(song, State.CANCELLED, listener, null); return; }
        if (url == null || url.isEmpty()) {
            finish(song, State.FAILED, listener,
                    QUALITY_LOSSLESS.equals(quality) || QUALITY_HIGH.equals(quality)
                            ? "Selected quality requires VIP. Try a lower quality."
                            : "No downloadable URL for this song.");
            return;
        }

        // 2. Open the connection and stream to a temp file.
        File outFile = targetFile(song, quality);
        File tmp = new File(outFile.getAbsolutePath() + ".part");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(30_000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) KugouConcept/1.0");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                finish(song, State.FAILED, listener, "Server returned HTTP " + code);
                return;
            }
            long total = conn.getContentLength(); // -1 if unknown
            // Storage pre-check: ensure we have at least MIN_FREE_BYTES free after writing.
            long needed = (total > 0 ? total : 5L * 1024 * 1024) + MIN_FREE_BYTES;
            if (availableBytes() < needed) {
                finish(song, State.FAILED, listener, "Not enough storage space.");
                return;
            }

            try (InputStream in = conn.getInputStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[BUFFER_SIZE];
                long done = 0;
                int n;
                long lastReport = 0;
                while ((n = in.read(buf)) != -1) {
                    if (isCancelled(flag)) {
                        out.close();
                        tmp.delete();
                        finish(song, State.CANCELLED, listener, null);
                        return;
                    }
                    out.write(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (now - lastReport > 200) {
                        lastReport = now;
                        final long dDone = done;
                        final long dTotal = total;
                        ui.post(() -> listener.onProgress(song, State.RUNNING, dDone, dTotal, null));
                    }
                }
                out.flush();
            }

            // 3. Rename .part → final and record.
            if (tmp.exists()) {
                if (outFile.exists()) outFile.delete();
                if (!tmp.renameTo(outFile)) {
                    // Fallback: copy then delete.
                    throw new IOException("rename failed");
                }
            }

            // 4. 验证下载文件的音频格式（防止获取到加密的 KGM/KGG 文件）
            // 技术原理：通过 API CDN 直链下载的文件应为标准 MP3/FLAC 格式（未加密）。
            // 若检测到加密格式，说明 URL 指向了加密资源，需提示用户。
            AudioFormatDetector.Result formatResult = AudioFormatDetector.detect(outFile);
            if (formatResult.encrypted) {
                outFile.delete();
                finish(song, State.FAILED, listener,
                        "Downloaded file is encrypted (" + formatResult.format.displayName
                                + "). Cannot play without KuGou client.");
                return;
            }
            if (formatResult.format == AudioFormatDetector.Format.UNKNOWN) {
                Log.w(TAG, "Downloaded file format unknown: " + outFile.getAbsolutePath());
            } else {
                Log.d(TAG, "Downloaded file format: " + formatResult);
            }

            DownloadedSong record = new DownloadedSong();
            record.hash = song.hash;
            record.title = song.title;
            record.artist = song.artist;
            record.album = song.album;
            record.duration = song.duration;
            record.quality = quality;
            record.localPath = outFile.getAbsolutePath();
            record.sizeBytes = outFile.length();
            record.downloadedAt = System.currentTimeMillis();
            record.imgUrl = song.imgUrl;
            store.upsert(record);

            notify(listener, song, State.COMPLETED, record.sizeBytes, record.sizeBytes, null);
        } catch (IOException e) {
            tmp.delete();
            finish(song, State.FAILED, listener, "Download failed: " + e.getMessage());
        } catch (Exception e) {
            tmp.delete();
            finish(song, State.FAILED, listener, "Download error: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
            synchronized (active) { active.remove(song.hash); }
        }
    }

    private File targetFile(SongInfo song, String quality) {
        String ext = QUALITY_LOSSLESS.equals(quality) ? "flac" : "mp3";
        String safeTitle = sanitize(song.title == null ? song.hash : song.title);
        String name = safeTitle + "_" + song.hash.substring(0, Math.min(6, song.hash.length())) + "." + ext;
        return new File(downloadsDir(), name);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private static boolean isCancelled(AtomicLong flag) {
        return flag.get() == -2;
    }

    private void finish(SongInfo song, State state, ProgressListener listener, String message) {
        synchronized (active) { active.remove(song.hash); }
        notify(listener, song, state, 0, -1, message);
    }

    private void notify(final ProgressListener l, final SongInfo s, final State st,
                        final long done, final long total, final String msg) {
        if (l == null) return;
        ui.post(() -> l.onProgress(s, st, done, total, msg));
    }
}
