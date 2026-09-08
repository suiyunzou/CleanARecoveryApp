package com.example.cleanrecovery.music.player;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
// androidx.media 的兼容类保留 support.v4.media 包名,这是官方既定安排。
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.ui.MusicPlayerActivity;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 音乐前台服务:标准媒体通知(MediaStyle + 活动 MediaSession)。
 *
 * <p>国内各家 ROM(小米 HyperOS、vivo OriginOS、OPPO ColorOS、华为 EMUI/HarmonyOS)
 * 都以「MediaStyle 通知 + 活动 MediaSession」作为媒体卡片识别契约:只有走这条路径,
 * 通知才会被渲染成带封面/歌名/控制键的媒体卡片并常驻通知栏,否则会被当作普通
 * 自定义视图通知折叠进「更多通知」。进度条由系统按 PlaybackState 自动推进
 * (Android 11+ 媒体卡片自带),无需高频重建通知。
 */
public class MusicService extends Service implements MusicPlayer.Callback {

    public static final String ACTION_TOGGLE = "com.example.cleanrecovery.music.TOGGLE";
    public static final String ACTION_NEXT = "com.example.cleanrecovery.music.NEXT";
    public static final String ACTION_PREV = "com.example.cleanrecovery.music.PREV";
    public static final String ACTION_CLOSE = "com.example.cleanrecovery.music.CLOSE";

    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int NOTIFICATION_ID = 1001;

    private MusicPlayer player;
    private NotificationManager notificationManager;
    private MediaSessionCompat mediaSession;
    private android.os.PowerManager.WakeLock transitionWakeLock;
    private final ExecutorService coverExecutor = Executors.newSingleThreadExecutor();
    /** 已加载的圆角封面(按歌曲 hash 缓存);coverSongKey 标记归属,切歌失效。 */
    private final AtomicReference<Bitmap> coverBitmap = new AtomicReference<>(null);
    private volatile String coverSongKey;
    private volatile boolean notificationShowing;

    @Override
    public void onCreate() {
        super.onCreate();
        player = MusicPlayer.get();
        player.addCallback(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        createMediaSession();
        android.os.PowerManager power = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        transitionWakeLock = power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":music-transition");
        transitionWakeLock.setReferenceCounted(false);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_TOGGLE:
                    player.toggle();
                    break;
                case ACTION_NEXT:
                    player.next();
                    break;
                case ACTION_PREV:
                    player.previous();
                    break;
                case ACTION_CLOSE:
                    closePlayback();
                    return START_NOT_STICKY; // 关闭后不得再走 updateNotification(否则通知"复活")
            }
        }
        updateTransitionWakeLock();
        updateNotification();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        player.removeCallback(this);
        coverExecutor.shutdownNow();
        if (mediaSession != null) {
            mediaSession.release();
            mediaSession = null;
        }
        if (transitionWakeLock != null && transitionWakeLock.isHeld()) transitionWakeLock.release();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 关闭播放器通知:暂停保留队列,撤销通知与前台状态,停服务。 */
    private void closePlayback() {
        player.pause();
        notificationShowing = false;
        notificationManager.cancel(NOTIFICATION_ID);
        stopForeground(true);
        stopSelf();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows music playback controls");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void createMediaSession() {
        mediaSession = new MediaSessionCompat(this, "ShuMusic");
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                player.resume();
            }

            @Override
            public void onPause() {
                player.pause();
            }

            @Override
            public void onSkipToNext() {
                player.next();
            }

            @Override
            public void onSkipToPrevious() {
                player.previous();
            }

            @Override
            public void onSeekTo(long pos) {
                player.seekTo((int) pos);
                syncPlaybackState();
            }

            @Override
            public void onStop() {
                closePlayback();
            }
        });
        mediaSession.setActive(true);
        syncPlaybackState();
    }

    /** 同步 PlaybackState:位置+速度,系统据此外推进度(媒体卡片/耳机/锁屏同样生效)。 */
    private void syncPlaybackState() {
        if (mediaSession == null) return;
        boolean playing = player.getState() == MusicPlayer.State.PLAYING;
        int position = player.getCurrentPosition();
        PlaybackStateCompat.Builder psb = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        position, playing ? 1f : 0f);
        mediaSession.setPlaybackState(psb.build());
    }

    private void syncMetadata(SongInfo song) {
        if (mediaSession == null || song == null) return;
        MediaMetadataCompat.Builder mb = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, player.getDuration());
        Bitmap cover = coverBitmap.get();
        if (cover != null) mb.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, cover);
        mediaSession.setMetadata(mb.build());
    }

    private void updateNotification() {
        SongInfo song = player.currentSong();
        if (song == null) {
            notificationShowing = false;
            stopForeground(true);
            notificationManager.cancel(NOTIFICATION_ID);
            return;
        }

        boolean isPlaying = player.getState() == MusicPlayer.State.PLAYING;
        boolean keepForeground = isPlaying || player.getState() == MusicPlayer.State.LOADING;

        // 切歌则作废旧封面并异步加载新封面
        String key = song.hash != null ? song.hash : String.valueOf(song.title);
        if (!key.equals(coverSongKey)) {
            coverSongKey = key;
            coverBitmap.set(null);
            loadCoverAsync(song);
        }

        Intent contentIntent = new Intent(this, MusicPlayerActivity.class);
        PendingIntent pendingContent = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // 媒体控制键以 addAction 提供,MediaStyle 按序渲染;
        // 折叠态显示 0/1/2(上一首/播放暂停/下一首),关闭键在展开态。
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_music)
                .setContentTitle(song.title)
                .setContentText(song.artist)
                .setLargeIcon(coverBitmap.get())
                .setContentIntent(pendingContent)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(keepForeground)
                .addAction(R.drawable.ic_notif_prev, getString(R.string.notif_action_prev),
                        servicePending(ACTION_PREV, 1))
                .addAction(isPlaying ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play,
                        getString(isPlaying ? R.string.notif_action_pause : R.string.notif_action_play),
                        servicePending(ACTION_TOGGLE, 2))
                .addAction(R.drawable.ic_notif_next, getString(R.string.notif_action_next),
                        servicePending(ACTION_NEXT, 3))
                .addAction(R.drawable.ic_notif_close, getString(R.string.notif_action_close),
                        servicePending(ACTION_CLOSE, 4))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));

        if (keepForeground) {
            startForeground(NOTIFICATION_ID, builder.build());
        } else {
            stopForeground(false);
            notificationManager.notify(NOTIFICATION_ID, builder.build());
        }
        notificationShowing = true;
        syncMetadata(song);
        syncPlaybackState();
    }

    private PendingIntent servicePending(String action, int requestCode) {
        Intent it = new Intent(this, MusicService.class).setAction(action);
        return PendingIntent.getService(this, requestCode, it,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void loadCoverAsync(final SongInfo song) {
        final String url = song.imgUrl;
        final String key = coverSongKey;
        if (url == null || url.isEmpty()) return;
        coverExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                InputStream input = conn.getInputStream();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 4; // 通知封面较小,解码 1/4 足够且省内存
                Bitmap raw = BitmapFactory.decodeStream(input, null, opts);
                if (raw != null && key.equals(coverSongKey)) {
                    int side = Math.min(raw.getWidth(), raw.getHeight());
                    Bitmap square = Bitmap.createBitmap(raw,
                            (raw.getWidth() - side) / 2, (raw.getHeight() - side) / 2, side, side);
                    coverBitmap.set(roundCorners(square, 26));
                    if (notificationShowing) updateNotification(); // 回填封面
                }
            } catch (Exception ignored) {
                // 封面加载失败保持无封面(系统用默认图)
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    /** 圆角位图(通知卡片封面用),radius 以目标位图像素计。 */
    private static Bitmap roundCorners(Bitmap src, float radiusPx) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setShader(new BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(0, 0, src.getWidth(), src.getHeight(), radiusPx, radiusPx, paint);
        return out;
    }

    // ---- MusicPlayer.Callback ------------------------------------------------

    @Override
    public void onStateChanged(MusicPlayer.State state) {
        updateTransitionWakeLock();
        updateNotification();
    }

    /** URL 解析/换源期间 MediaPlayer 尚未持锁，短暂补住熄屏切歌的 CPU 空窗。 */
    private void updateTransitionWakeLock() {
        if (transitionWakeLock == null) return;
        if (player.getState() == MusicPlayer.State.LOADING) {
            if (!transitionWakeLock.isHeld()) transitionWakeLock.acquire(60_000L);
        } else if (transitionWakeLock.isHeld()) {
            transitionWakeLock.release();
        }
    }

    @Override
    public void onProgressChanged(int currentMs, int totalMs) {
        // 进度不再重建通知:媒体卡片的进度由系统按 PlaybackState(位置+速度)
        // 自动推进,Android 11+ 卡片自带进度条;高频 notify 反而招致 ROM 限流。
    }

    @Override
    public void onSongChanged(SongInfo song) {
        updateNotification();
    }

    @Override
    public void onError(String message) {
        updateNotification();
    }
}
