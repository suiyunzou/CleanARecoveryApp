package com.example.cleanrecovery.music.player;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.cleanrecovery.music.data.SongInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Singleton music player — manages playback across the app. */
public class MusicPlayer {

    private static final String TAG = "MusicPlayer";

    public enum Mode { SEQUENTIAL, REPEAT_ALL, REPEAT_ONE, SHUFFLE }
    public enum State { IDLE, LOADING, PLAYING, PAUSED, STOPPED, ERROR }
    public enum PlaySource {
        SEARCH,
        RECOMMENDATION,
        LOCAL_PLAYLIST,
        REMOTE_PLAYLIST,
        FAVORITES,
        RECENT,
        UNKNOWN
    }

    public interface Callback {
        void onStateChanged(State state);
        void onProgressChanged(int currentMs, int totalMs);
        void onSongChanged(SongInfo song);
        void onError(String message);
    }

    private static volatile MusicPlayer instance;
    public static MusicPlayer get() {
        if (instance == null) {
            synchronized (MusicPlayer.class) {
                if (instance == null) instance = new MusicPlayer();
            }
        }
        return instance;
    }

    private MediaPlayer player;
    private final List<SongInfo> queue = new ArrayList<>();
    private int queueIndex = -1;
    private Mode mode = Mode.SEQUENTIAL;
    private State state = State.IDLE;
    private PlaySource playSource = PlaySource.UNKNOWN;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Callback> callbacks = new ArrayList<>();
    private final ExecutorService resolverExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger playRequestId = new AtomicInteger();

    private MusicPlayer() {}

    public void addCallback(Callback cb) { if (!callbacks.contains(cb)) callbacks.add(cb); }

    public void removeCallback(Callback cb) { callbacks.remove(cb); }

    public void play(List<SongInfo> songs, int startIndex) {
        play(songs, startIndex, PlaySource.UNKNOWN);
    }

    public void play(List<SongInfo> songs, int startIndex, PlaySource source) {
        if (songs == null || songs.isEmpty()) return;
        queue.clear();
        queue.addAll(songs);
        queueIndex = Math.max(0, Math.min(startIndex, songs.size() - 1));
        playSource = source != null ? source : PlaySource.UNKNOWN;
        playCurrent();
    }

    public void playSingle(SongInfo song) {
        play(Collections.singletonList(song), 0, playSource);
    }

    public void seekTo(int positionMs) {
        if (player != null) player.seekTo(positionMs);
    }

    public void pause() {
        if (player != null && player.isPlaying()) {
            player.pause();
            setState(State.PAUSED);
        }
    }

    public void resume() {
        if (player != null && !player.isPlaying()) {
            player.start();
            setState(State.PLAYING);
        }
    }

    public void toggle() {
        if (state == State.PLAYING) pause(); else resume();
    }

    public void next() {
        if (queue.isEmpty()) return;
        queueIndex = nextIndex();
        playCurrent();
    }

    public void previous() {
        if (queue.isEmpty()) return;
        if (player != null && player.getCurrentPosition() > 3000) {
            // Below 3s → previous track; otherwise restart current
            seekTo(0);
            return;
        }
        queueIndex = prevIndex();
        playCurrent();
    }

    public SongInfo currentSong() {
        if (queueIndex >= 0 && queueIndex < queue.size()) return queue.get(queueIndex);
        return null;
    }

    public State getState() { return state; }
    public Mode getMode() { return mode; }
    public PlaySource getPlaySource() { return playSource; }

    public void setMode(Mode m) {
        mode = m;
        for (Callback cb : callbacks) cb.onStateChanged(state);
    }

    public void cycleMode() {
        Mode[] modes = Mode.values();
        mode = modes[(mode.ordinal() + 1) % modes.length];
        for (Callback cb : callbacks) cb.onStateChanged(state);
    }

    public int getCurrentPosition() {
        return player != null ? player.getCurrentPosition() : 0;
    }

    public int getDuration() {
        return player != null ? player.getDuration() : 0;
    }

    public List<SongInfo> getQueue() { return new ArrayList<>(queue); }
    public int getQueueIndex() { return queueIndex; }

    public void release() {
        playRequestId.incrementAndGet();
        handler.removeCallbacks(progressRunnable);
        if (player != null) {
            player.release();
            player = null;
        }
        setState(State.IDLE);
    }

    // ---- internal ---------------------------------------------------------

    private void playCurrent() {
        releasePlayer();
        SongInfo song = currentSong();
        if (song == null) return;

        int requestId = playRequestId.incrementAndGet();
        setState(State.LOADING);
        for (Callback cb : callbacks) cb.onSongChanged(song);
        Log.d(TAG, "playCurrent: song=" + song.title + " hash=" + song.hash + " vipRequired=" + song.vipRequired);

        resolverExecutor.execute(() -> {
            String url = null;
            String error = null;
            try {
                url = urlResolver != null ? urlResolver.resolve(song) : null;
                Log.d(TAG, "resolve url=" + (url != null ? url.substring(0, Math.min(80, url.length())) : "null"));
            } catch (Exception e) {
                error = e.getMessage();
                Log.w(TAG, "resolve failed", e);
            }
            String resolvedUrl = url;
            String resolvedError = error;
            handler.post(() -> {
                if (playRequestId.get() != requestId) return;
                if (resolvedUrl == null || resolvedUrl.isEmpty()) {
                    setState(State.ERROR);
                    String message = resolvedError != null && !resolvedError.isEmpty()
                            ? resolvedError
                            : "No playable URL for: " + song.title;
                    Log.w(TAG, "playCurrent: no url, error=" + message);
                    for (Callback cb : callbacks) cb.onError(message);
                    return;
                }
                prepareResolvedUrl(song, resolvedUrl);
            });
        });
    }

    private void prepareResolvedUrl(SongInfo song, String url) {
        try {
            // Start Foreground Service
            android.content.Context ctx = com.example.cleanrecovery.music.MusicApp.get().context;
            android.content.Intent serviceIntent = new android.content.Intent(ctx, MusicService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(serviceIntent);
            } else {
                ctx.startService(serviceIntent);
            }

            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(url);
            player.setOnPreparedListener(mp -> {
                mp.start();
                setState(State.PLAYING);
                for (Callback cb : callbacks) {
                    cb.onSongChanged(song);
                    cb.onProgressChanged(0, mp.getDuration());
                }
                handler.post(progressRunnable);
            });
            player.setOnCompletionListener(mp -> {
                if (mode == Mode.REPEAT_ONE) {
                    playCurrent();
                } else {
                    next();
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                setState(State.ERROR);
                for (Callback cb : callbacks) cb.onError("Playback error: " + what);
                return true;
            });
            player.prepareAsync();
        } catch (IOException e) {
            setState(State.ERROR);
            for (Callback cb : callbacks) cb.onError(e.getMessage());
        }
    }

    private void releasePlayer() {
        handler.removeCallbacks(progressRunnable);
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private final Runnable progressRunnable = new Runnable() {
        @Override public void run() {
            if (player != null && player.isPlaying()) {
                int cur = player.getCurrentPosition();
                int dur = player.getDuration();
                for (Callback cb : callbacks) cb.onProgressChanged(cur, dur);
                handler.postDelayed(this, 250);
            }
        }
    };

    private int nextIndex() {
        if (queue.isEmpty()) return -1;
        return switch (mode) {
            case SHUFFLE -> (int) (Math.random() * queue.size());
            default -> (queueIndex + 1) % queue.size();
        };
    }

    private int prevIndex() {
        if (queue.isEmpty()) return -1;
        return (queueIndex - 1 + queue.size()) % queue.size();
    }

    private void setState(State s) {
        state = s;
        for (Callback cb : callbacks) cb.onStateChanged(s);
    }

    /** Call this after auth/data source resolution succeeds to update song URLs. */
    public interface PlayUrlResolver {
        String resolve(SongInfo song);
    }

    private static volatile PlayUrlResolver urlResolver;

    public static void setPlayUrlResolver(PlayUrlResolver r) { urlResolver = r; }

    static {
        urlResolver = song -> null;
    }
}
