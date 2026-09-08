package com.example.cleanrecovery.music.player;

import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.cleanrecovery.music.data.SongInfo;

import java.io.File;
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

    public enum Mode { SEQUENTIAL, REPEAT_ONE, SHUFFLE }
    public enum State { IDLE, LOADING, PLAYING, PAUSED, STOPPED, ERROR }
    /** 定时关闭（对齐酷狗：分钟倒计时 / 播完当前歌曲 / 播完整个列表）。 */
    public enum SleepMode { OFF, MINUTES, END_OF_SONG, END_OF_QUEUE }
    public enum PlaySource {
        SEARCH,
        RECOMMENDATION,
        LOCAL_PLAYLIST,
        REMOTE_PLAYLIST,
        DOWNLOADED,
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
    private final PlaybackQueue queue = new PlaybackQueue();
    private Mode mode = Mode.SEQUENTIAL;
    private State state = State.IDLE;
    private static final String KEY_LAST_SESSION = "last_playback_session";
    private final com.google.gson.Gson gson = new com.google.gson.Gson();
    /** 恢复会话后待 seek 的历史进度（prepare 完成后消费一次）。 */
    private int restoreSeekMs;
    private float playbackSpeed = 1.0f;
    private int autoSkipFailures;
    /** 当前 MediaPlayer 是否已 prepare 完成；未就绪时 seek/pause 等调用一律忽略（防 -38）。 */
    private volatile boolean prepared;
    /** 错误去重：同一消息 4 秒内只向 UI 派发一次，避免多页面回调重复弹 toast。 */
    private String lastErrorMsg;
    private long lastErrorAt;
    private SleepMode sleepMode = SleepMode.OFF;
    private volatile Runnable sleepFireCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<Callback> callbacks = new ArrayList<>();
    private final ExecutorService resolverExecutor = Executors.newSingleThreadExecutor();
    private final AtomicInteger playRequestId = new AtomicInteger();

    private MusicPlayer() {}

    // ---- 上次播放会话恢复 ----------------------------------------------

    /** 重启后恢复上次队列/曲目为暂停态：迷你条可见上次歌曲，点播放键起播。 */
    /** 由 MusicApp 在构造完成后调用（构造期间 MusicApp.get() 尚为 null）。 */
    public void restoreLastSession(android.content.Context ctx) {
        try {
            String json = ctx.getSharedPreferences("music_player_prefs",
                    android.content.Context.MODE_PRIVATE).getString(KEY_LAST_SESSION, null);
            if (json == null || json.isEmpty()) return;
            LastSession ls = gson.fromJson(json, LastSession.class);
            if (ls == null) return;
            if (ls.playback != null) {
                queue.restore(ls.playback, ls.history);
            } else if (ls.queue != null && !ls.queue.isEmpty()) {
                queue.replace(ls.queue, ls.index, ls.source, ls.sourceName, ls.tag);
            } else return;
            try { mode = Mode.valueOf(ls.mode); } catch (Exception ignored) {}
            restoreSeekMs = Math.max(0, ls.positionMs);
            state = queue.size() == 0 ? State.IDLE : State.PAUSED;
        } catch (Exception e) {
            Log.w(TAG, "restoreLastSession failed", e);
        }
    }

    /** 播放状态变化时持久化队列/曲目/来源/模式（IDLE 不清空，重启后仍可见上次歌曲）。 */
    private void persistSession(State s) {
        try {
            android.content.Context ctx = com.example.cleanrecovery.music.MusicApp.get().context;
            int positionMs = 0;
            if (s == State.PAUSED) {
                positionMs = (player != null && prepared) ? player.getCurrentPosition() : restoreSeekMs;
            }
            LastSession ls = new LastSession();
            ls.playback = queue.snapshot();
            ls.history = queue.history();
            ls.positionMs = positionMs;
            ls.mode = mode == null ? null : mode.name();
            ctx.getSharedPreferences("music_player_prefs",
                    android.content.Context.MODE_PRIVATE)
                    .edit().putString(KEY_LAST_SESSION, gson.toJson(ls)).apply();
        } catch (Exception e) {
            Log.w(TAG, "persistSession failed", e);
        }
    }

    /** 会话快照（Gson 持久化载体）。 */
    private static class LastSession {
        PlaybackQueue.Snapshot playback;
        List<PlaybackQueue.Snapshot> history;
        List<SongInfo> queue;
        int index;
        int positionMs;
        String source;
        String sourceName;
        String tag;
        String mode;
    }

    public void addCallback(Callback cb) { if (!callbacks.contains(cb)) callbacks.add(cb); }

    public void removeCallback(Callback cb) { callbacks.remove(cb); }

    public void play(List<SongInfo> songs, int startIndex) {
        play(songs, startIndex, PlaySource.UNKNOWN);
    }

    public void play(List<SongInfo> songs, int startIndex, PlaySource source) {
        play(songs, startIndex, source, "");
    }

    public void play(List<SongInfo> songs, int startIndex, PlaySource source, String sourceName) {
        play(songs, startIndex, source, sourceName, null);
    }

    /** @param tag 来源歌单标识（"local:名称"/"remote:id"）；队列编辑不会回写歌单。 */
    public void play(List<SongInfo> songs, int startIndex, PlaySource source, String sourceName, String tag) {
        if (songs == null || songs.isEmpty()) return;
        queue.replace(songs, startIndex, source == null ? "UNKNOWN" : source.name(), sourceName, tag);
        restoreSeekMs = 0;
        autoSkipFailures = 0;
        playCurrent();
    }

    public void playSingle(SongInfo song) {
        playSearch(song, "");
    }

    public void retryCurrent() {
        if (currentSong() != null) {
            playCurrent();
        }
    }

    /** Search clicks insert and play, preserving the current queue and its source. */
    public void playSearch(SongInfo song, String searchName) {
        if (song == null) return;
        queue.insertSearch(song, searchName);
        restoreSeekMs = 0;
        autoSkipFailures = 0;
        playCurrent();
    }

    public void playNext(List<SongInfo> songs) {
        if (songs == null || songs.isEmpty()) return;
        boolean empty = queue.size() == 0;
        queue.insertNext(songs);
        if (empty) { restoreSeekMs = 0; playCurrent(); }
        else notifyQueueChanged();
    }

    public void appendQueue(List<SongInfo> songs) {
        if (songs == null || songs.isEmpty()) return;
        boolean empty = queue.size() == 0;
        queue.append(songs);
        if (empty) { restoreSeekMs = 0; playCurrent(); }
        else notifyQueueChanged();
    }

    /** Existing “listen later” action uses the same pending entries as “play next”. */
    public void addLater(SongInfo song) { playNext(Collections.singletonList(song)); }

    public boolean isPending(int index) { return queue.isPending(index); }
    public List<PlaybackQueue.Snapshot> getQueueHistory() { return queue.history(); }

    public void playHistory(PlaybackQueue.Snapshot snapshot, int index) {
        queue.playHistory(snapshot, index);
        restoreSeekMs = 0;
        autoSkipFailures = 0;
        playCurrent();
    }

    private void notifyQueueChanged() {
        persistSession(state);
        for (Callback cb : callbacks) cb.onSongChanged(currentSong());
    }

    // ---- 定时关闭（酷狗机制） ----------------------------------------------

    public SleepMode getSleepMode() { return sleepMode; }

    /** 定时到点回调（主线程），界面层用它弹 toast。 */
    public void setSleepFireCallback(Runnable r) { sleepFireCallback = r; }

    /** 分钟倒计时；minutes<=0 取消。进程级：不随播放器页面销毁而取消。 */
    public void setSleepTimerMinutes(int minutes) {
        handler.removeCallbacks(sleepFireRunnable);
        if (minutes <= 0) {
            sleepMode = SleepMode.OFF;
            return;
        }
        sleepMode = SleepMode.MINUTES;
        handler.postDelayed(sleepFireRunnable, minutes * 60_000L);
    }

    private final Runnable sleepFireRunnable = new Runnable() {
        @Override public void run() {
            sleepMode = SleepMode.OFF;
            pause();
            if (sleepFireCallback != null) sleepFireCallback.run();
        }
    };

    public void setSleepMode(SleepMode m) { sleepMode = m == null ? SleepMode.OFF : m; }

    /** 未就绪（未 prepare 完成）时忽略 seek：对 Preparing 中的播放器 seek 会报 (-38) 错误。 */
    public void seekTo(int positionMs) {
        if (player == null || !prepared) return;
        try {
            player.seekTo(Math.max(0, positionMs));
        } catch (Exception ignored) {
        }
    }

    public void pause() {
        if (player == null || !prepared) return;
        try {
            if (player.isPlaying()) {
                player.pause();
                // 停止轮询并推送一次精确位置，确保进度条/歌词停在当前点而非上一帧
                handler.removeCallbacks(progressRunnable);
                int cur = player.getCurrentPosition();
                int dur = player.getDuration();
                for (Callback cb : callbacks) cb.onProgressChanged(cur, dur);
                setState(State.PAUSED);
            }
        } catch (Exception ignored) {
        }
    }

    public void resume() {
        if (player == null || !prepared) return;
        try {
            if (!player.isPlaying()) {
                player.start();
                setState(State.PLAYING);
                ensureNotificationServiceRunning();
                // 立即同步一次并重启轮询：progressRunnable 在暂停时已自行停止，
                // 不重新 post 会导致恢复后进度条/歌词永久冻结。
                int cur = player.getCurrentPosition();
                int dur = player.getDuration();
                for (Callback cb : callbacks) cb.onProgressChanged(cur, dur);
                handler.removeCallbacks(progressRunnable);
                handler.post(progressRunnable);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * resume() 不经过 prepareResolvedUrl(复用已有 MediaPlayer),
     * 若前台服务已被"通知关闭"结束,须补启,否则播放中无媒体通知。
     */
    private void ensureNotificationServiceRunning() {
        try {
            android.content.Context ctx = com.example.cleanrecovery.music.MusicApp.get().context;
            android.content.Intent serviceIntent = new android.content.Intent(ctx, MusicService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                ctx.startForegroundService(serviceIntent);
            } else {
                ctx.startService(serviceIntent);
            }
        } catch (Exception denied) {
            // 后台自启限制时放弃,前台播放路径稍后仍会补启
            android.util.Log.w(TAG, "ensureNotificationServiceRunning denied", denied);
        }
    }

    public void toggle() {
        if (state == State.PLAYING) {
            pause();
            return;
        }
        if (state == State.PAUSED && player != null && prepared) {
            resume();
            return;
        }
        // 恢复会话的暂停态（尚未 prepare）：点播放键起播上次队列
        if (currentSong() != null) {
            autoSkipFailures = 0;
            playCurrent();
            return;
        }
        resume();
    }

    public void next() {
        if (queue.size() == 0) return;
        autoSkipFailures = 0;
        queue.next(mode == Mode.SHUFFLE, false);
        restoreSeekMs = 0;
        playCurrent();
    }

    public void previous() {
        if (queue.size() == 0) return;
        if (mode != Mode.SHUFFLE && player != null && prepared && player.getCurrentPosition() > 3000) {
            // Below 3s → previous track; otherwise restart current
            seekTo(0);
            return;
        }
        autoSkipFailures = 0;
        queue.previous(mode == Mode.SHUFFLE);
        restoreSeekMs = 0;
        playCurrent();
    }

    public SongInfo currentSong() { return queue.current(); }

    public State getState() { return state; }
    public Mode getMode() { return mode; }
    public PlaySource getPlaySource() { return PlaySource.valueOf(queue.source()); }
    public String getPlaySourceName() { return queue.sourceName(); }
    public String getPlaySourceTag() { return queue.sourceTag(); }
    public void setPlaySourceTag(String tag) {
        queue.setSourceTag(tag == null || tag.isEmpty() ? null : tag);
        persistSession(state);
    }
    public float getPlaybackSpeed() { return playbackSpeed; }

    public void setMode(Mode m) {
        mode = m == null ? Mode.SEQUENTIAL : m;
        queue.resetRandom();
        persistSession(state);
        for (Callback cb : callbacks) cb.onStateChanged(state);
    }

    public void cycleMode() {
        setMode(switch (mode) {
            case SEQUENTIAL -> Mode.SHUFFLE;
            case SHUFFLE -> Mode.REPEAT_ONE;
            case REPEAT_ONE -> Mode.SEQUENTIAL;
        });
    }

    /** 未就绪时返回 0：对 Preparing 中的播放器查询会触发 (-38) 错误事件。 */
    public int getCurrentPosition() {
        return player != null && prepared ? player.getCurrentPosition() : 0;
    }

    public int getDuration() {
        return player != null && prepared ? player.getDuration() : 0;
    }

    public List<SongInfo> getQueue() { return queue.songs(); }
    public int getQueueIndex() { return queue.index(); }

    public void playQueueIndex(int index) {
        if (index < 0 || index >= queue.size()) return;
        autoSkipFailures = 0;
        restoreSeekMs = 0;
        queue.select(index);
        playCurrent();
    }

    public void removeQueueIndex(int index) {
        if (index < 0 || index >= queue.size()) return;
        boolean removingCurrent = index == queue.index();
        queue.remove(index);
        if (queue.size() == 0) { clearQueue(); return; }
        if (removingCurrent) {
            restoreSeekMs = 0;
            autoSkipFailures = 0;
            playCurrent();
        } else notifyQueueChanged();
    }

    /** Reorder only the playback copy; saved playlists are never written here. */
    public void reorderQueue(int from, int to) {
        queue.move(from, to);
        // The drag adapter updates its own snapshot; do not rebind while RecyclerView is moving.
        persistSession(state);
    }

    public void clearQueue() {
        queue.clear();
        restoreSeekMs = 0;
        release();
        notifyQueueChanged();
    }

    public void setPlaybackSpeed(float speed) {
        playbackSpeed = speed <= 0 ? 1.0f : speed;
        applyPlaybackSpeed();
        for (Callback cb : callbacks) cb.onStateChanged(state);
    }

    public void release() {
        playRequestId.incrementAndGet();
        handler.removeCallbacks(progressRunnable);
        if (player != null) {
            player.release();
            player = null;
        }
        prepared = false;
        setState(State.IDLE);
    }

    // ---- internal ---------------------------------------------------------

    private void playCurrent() {
        playSong(currentSong());
    }

    private void playSong(SongInfo song) {
        releasePlayer();
        if (song == null) return;

        int requestId = playRequestId.incrementAndGet();
        setState(State.LOADING);
        for (Callback cb : callbacks) cb.onSongChanged(song);
        Log.d(TAG, "playCurrent: song=" + song.title + " hash=" + song.hash + " vipRequired=" + song.vipRequired);

        String localPath = song.localPath;
        if (localPath != null && !localPath.isEmpty() && new File(localPath).isFile()) {
            prepareResolvedUrl(song, localPath);
            return;
        }

        resolverExecutor.execute(() -> {
            String url = null;
            String error = null;
            // 解析失败自动重试：网络抖动/CDN 闪断时"之前能听的歌"会偶发失败。
            // 弱网（真机几 KB/s）下 2 次常不够，3 次并阶梯退避 600ms/1500ms。
            for (int attempt = 0; attempt < 3 && (url == null || url.isEmpty()); attempt++) {
                if (attempt > 0) {
                    if (playRequestId.get() != requestId) return;
                    try {
                        Thread.sleep(attempt == 1 ? 600 : 1500);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
                try {
                    url = urlResolver != null ? urlResolver.resolve(song) : null;
                    if (url != null && !url.isEmpty()) error = null;
                    Log.d(TAG, "resolve url=" + (url != null ? url.substring(0, Math.min(80, url.length())) : "null"));
                } catch (Exception e) {
                    error = e.getMessage();
                    Log.w(TAG, "resolve failed", e);
                }
            }
            String resolvedUrl = url;
            String resolvedError = error;
            handler.post(() -> {
                if (playRequestId.get() != requestId) return;
                if (resolvedUrl == null || resolvedUrl.isEmpty()) {
                    // 自动跳下一首成功时不惊扰用户：保持 LOADING 静默切歌，
                    // 只有整条队列都失败才进入 ERROR 并提示
                    String message = resolvedError != null && !resolvedError.isEmpty()
                            ? resolvedError
                            : "No playable URL for: " + song.title;
                    Log.w(TAG, "playCurrent: no url, error=" + message);
                    boolean willSkip = maybeAutoSkipAfterFailure();
                    if (!willSkip) {
                        setState(State.ERROR);
                        notifyPlaybackError(message);
                    }
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
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(serviceIntent);
                } else {
                    ctx.startService(serviceIntent);
                }
            } catch (Exception denied) {
                // 装包覆盖/后台自启恢复播放时，系统禁止后台进程启动前台服务：
                // 跳过通知栏服务继续本地播放，待前台播放时再补启，避免崩溃循环
                Log.w(TAG, "startForegroundService denied in background", denied);
            }

            player = new MediaPlayer();
            // 前台服务提高进程优先级，但不会阻止熄屏后的 CPU 深度休眠。
            player.setWakeMode(ctx, android.os.PowerManager.PARTIAL_WAKE_LOCK);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(url);
            player.setOnPreparedListener(mp -> {
                prepared = true;
                applyPlaybackSpeed();
                if (restoreSeekMs > 0) {
                    try { mp.seekTo(restoreSeekMs); } catch (Exception ignored) {}
                    restoreSeekMs = 0;
                }
                mp.start();
                autoSkipFailures = 0;
                setState(State.PLAYING);
                for (Callback cb : callbacks) {
                    cb.onSongChanged(song);
                    cb.onProgressChanged(0, mp.getDuration());
                }
                handler.post(progressRunnable);
            });
            player.setOnCompletionListener(mp -> {
                // 定时关闭：播完当前歌曲停止（酷狗机制）
                if (sleepMode == SleepMode.END_OF_SONG) {
                    sleepMode = SleepMode.OFF;
                    pause();
                    if (sleepFireCallback != null) sleepFireCallback.run();
                    return;
                }
                if (mode == Mode.REPEAT_ONE) {
                    playCurrent();
                    return;
                }
                // 定时关闭：播完整个列表后停止（顺序走到队尾）
                if (sleepMode == SleepMode.END_OF_QUEUE
                        && mode != Mode.SHUFFLE
                        && queue.index() >= queue.size() - 1) {
                    sleepMode = SleepMode.OFF;
                    pause();
                    if (sleepFireCallback != null) sleepFireCallback.run();
                    return;
                }
                next();
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                // -38 = 非法状态（多为加载完成前被 seek/控制），此时媒体本身无恙，
                // 仅记日志不弹失败；真失败时按跳过策略处理，跳不动才提示。
                if (what == -38) {
                    return true;
                }
                boolean willSkip = maybeAutoSkipAfterFailure();
                if (!willSkip) {
                    setState(State.ERROR);
                    notifyPlaybackError("Playback error: " + what);
                }
                return true;
            });
            player.prepareAsync();
        } catch (IOException e) {
            boolean willSkip = maybeAutoSkipAfterFailure();
            if (!willSkip) {
                setState(State.ERROR);
                notifyPlaybackError(e.getMessage());
            }
        }
    }

    /** 整条队列播不动时的唯一错误出口；同一消息 4 秒内只派发一次，防止多页面重复弹窗。 */
    private void notifyPlaybackError(String message) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (message != null && message.equals(lastErrorMsg) && now - lastErrorAt < 4000) return;
        lastErrorMsg = message;
        lastErrorAt = now;
        for (Callback cb : callbacks) cb.onError(message);
    }

    /**
     * 失败后按策略自动跳下一首。
     * @return true 表示已安排跳过（错误不应再上报 UI）；false 表示队列已尽，需要上报。
     */
    private boolean maybeAutoSkipAfterFailure() {
        if (queue.size() <= 1 || autoSkipFailures >= queue.size()) return false;
        autoSkipFailures++;
        int request = playRequestId.get();
        handler.postDelayed(() -> {
            if (playRequestId.get() != request) return;
            queue.next(mode == Mode.SHUFFLE, false);
            restoreSeekMs = 0;
            playCurrent();
        }, 800);
        return true;
    }

    private void releasePlayer() {
        handler.removeCallbacks(progressRunnable);
        prepared = false;
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
    }

    private void applyPlaybackSpeed() {
        if (player == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) return;
        try {
            player.setPlaybackParams(player.getPlaybackParams().setSpeed(playbackSpeed));
        } catch (Exception ignored) {
        }
    }

    private final Runnable progressRunnable = new Runnable() {
        @Override public void run() {
            try {
                if (player != null && player.isPlaying()) {
                    int cur = player.getCurrentPosition();
                    int dur = player.getDuration();
                    for (Callback cb : callbacks) cb.onProgressChanged(cur, dur);
                    handler.postDelayed(this, 250);
                }
            } catch (Exception ignored) {
                // 播放器已被释放/进入异常态：停止轮询即可
            }
        }
    };

    private void setState(State s) {
        state = s;
        if (s != State.IDLE) persistSession(s);
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
