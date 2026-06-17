package com.example.cleanrecovery.music.player;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;

import com.example.cleanrecovery.music.data.SongInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Singleton music player — manages playback across the app. */
public class MusicPlayer {

    public enum Mode { SEQUENTIAL, REPEAT_ALL, REPEAT_ONE, SHUFFLE }
    public enum State { IDLE, PLAYING, PAUSED, STOPPED }

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
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Callback> callbacks = new ArrayList<>();

    private MusicPlayer() {}

    public void addCallback(Callback cb) { if (!callbacks.contains(cb)) callbacks.add(cb); }

    public void removeCallback(Callback cb) { callbacks.remove(cb); }

    public void play(List<SongInfo> songs, int startIndex) {
        if (songs == null || songs.isEmpty()) return;
        queue.clear();
        queue.addAll(songs);
        queueIndex = Math.max(0, Math.min(startIndex, songs.size() - 1));
        playCurrent();
    }

    public void playSingle(SongInfo song) {
        play(Collections.singletonList(song), 0);
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

        // Defer URL resolution to data source callback; use prepared URLs
        String url = urlResolver.resolve(song);
        if (url == null) {
            for (Callback cb : callbacks) cb.onError("No playable URL for: " + song.title);
            return;
        }
        try {
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
                for (Callback cb : callbacks) cb.onError("Playback error: " + what);
                return true;
            });
            player.prepareAsync();
            setState(State.IDLE);
        } catch (IOException e) {
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
        urlResolver = song -> "http://fs.open.kugou.com/" + (song.hash != null ? song.hash : "") + ".mp3";
    }
}
