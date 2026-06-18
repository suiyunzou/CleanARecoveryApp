package com.example.cleanrecovery.music.player;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.ui.MusicPlayerActivity;

public class MusicService extends Service implements MusicPlayer.Callback {

    public static final String ACTION_TOGGLE = "com.example.cleanrecovery.music.TOGGLE";
    public static final String ACTION_NEXT = "com.example.cleanrecovery.music.NEXT";
    public static final String ACTION_PREV = "com.example.cleanrecovery.music.PREV";
    public static final String ACTION_CLOSE = "com.example.cleanrecovery.music.CLOSE";

    private static final String CHANNEL_ID = "music_playback_channel";
    private static final int NOTIFICATION_ID = 1001;

    private MusicPlayer player;
    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        player = MusicPlayer.get();
        player.addCallback(this);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
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
                    player.pause();
                    stopForeground(true);
                    stopSelf();
                    break;
            }
        }
        updateNotification();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        player.removeCallback(this);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
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

    private void updateNotification() {
        SongInfo song = player.currentSong();
        if (song == null) {
            stopForeground(true);
            return;
        }

        boolean isPlaying = player.getState() == MusicPlayer.State.PLAYING;

        Intent contentIntent = new Intent(this, MusicPlayerActivity.class);
        PendingIntent pendingContent = PendingIntent.getActivity(this, 0, contentIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(song.title)
                .setContentText(song.artist)
                .setContentIntent(pendingContent)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying);

        builder.addAction(new NotificationCompat.Action(
                0, "Prev",
                PendingIntent.getService(this, 1, new Intent(this, MusicService.class).setAction(ACTION_PREV), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
        ));

        builder.addAction(new NotificationCompat.Action(
                0, isPlaying ? "Pause" : "Play",
                PendingIntent.getService(this, 2, new Intent(this, MusicService.class).setAction(ACTION_TOGGLE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
        ));

        builder.addAction(new NotificationCompat.Action(
                0, "Next",
                PendingIntent.getService(this, 3, new Intent(this, MusicService.class).setAction(ACTION_NEXT), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
        ));

        builder.addAction(new NotificationCompat.Action(
                0, "Close",
                PendingIntent.getService(this, 4, new Intent(this, MusicService.class).setAction(ACTION_CLOSE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
        ));

        Notification notification = builder.build();

        if (isPlaying) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            stopForeground(false);
            notificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onStateChanged(MusicPlayer.State state) {
        updateNotification();
    }

    @Override
    public void onProgressChanged(int currentMs, int totalMs) {
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
