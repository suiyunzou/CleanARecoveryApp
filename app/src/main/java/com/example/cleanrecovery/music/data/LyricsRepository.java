package com.example.cleanrecovery.music.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Shared, bounded lyric cache. Concurrent consumers share one request per song. */
public final class LyricsRepository {
    public interface Loader { Lyrics load(SongInfo song) throws Exception; }
    public interface Callback { void loaded(Lyrics lyrics, boolean failed); }
    private final Loader loader;
    private final Executor worker;
    private final Executor delivery;
    private final Map<String, Lyrics> cache = new LinkedHashMap<String, Lyrics>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Lyrics> entry) {
            return size() > 16;
        }
    };
    private final Map<String, List<Callback>> pending = new LinkedHashMap<>();

    public LyricsRepository(Loader loader, Executor worker, Executor delivery) {
        this.loader = loader;
        this.worker = worker;
        this.delivery = delivery;
    }

    public void load(SongInfo song, Callback callback) {
        String key = song == null ? null : song.hash;
        if (key == null || key.isEmpty()) {
            delivery.execute(() -> callback.loaded(Lyrics.empty(), false));
            return;
        }
        synchronized (this) {
            Lyrics hit = cache.get(key);
            if (hit != null) {
                delivery.execute(() -> callback.loaded(hit, false));
                return;
            }
            List<Callback> waiting = pending.get(key);
            if (waiting != null) {
                waiting.add(callback);
                return;
            }
            waiting = new ArrayList<>();
            waiting.add(callback);
            pending.put(key, waiting);
        }
        worker.execute(() -> {
            Lyrics result;
            boolean failed = false;
            try {
                result = loader.load(song);
                if (result == null) result = Lyrics.empty();
            } catch (Exception error) {
                result = Lyrics.empty();
                failed = true;
            }
            List<Callback> waiting;
            synchronized (this) {
                // Failures remain retryable when the user opens lyrics again.
                if (!failed) cache.put(key, result);
                waiting = pending.remove(key);
            }
            final Lyrics value = result;
            final boolean error = failed;
            for (Callback consumer : waiting) {
                delivery.execute(() -> consumer.loaded(value, error));
            }
        });
    }
}
