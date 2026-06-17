package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.SystemUiHelper;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.player.MusicPlayer;

import java.util.List;

public final class MusicPlayerActivity extends Activity implements MusicPlayer.Callback {

    private MusicApp app;
    private TextView songTitle, songArtist, currentTimeText, totalTimeText, coverText;
    private Button playButton;
    private SeekBar seekBar;
    private boolean seekTracking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_music_player);
        app = MusicApp.get();
        app.player.addCallback(this);

        songTitle = findViewById(R.id.player_song_title);
        songArtist = findViewById(R.id.player_song_artist);
        currentTimeText = findViewById(R.id.player_current_time);
        totalTimeText = findViewById(R.id.player_total_time);
        coverText = findViewById(R.id.player_cover_text);
        playButton = findViewById(R.id.player_play_button);
        seekBar = findViewById(R.id.player_seekbar);

        bindListeners();
        updateUI();
    }

    @Override
    protected void onDestroy() {
        app.player.removeCallback(this);
        super.onDestroy();
    }

    private void bindListeners() {
        findViewById(R.id.player_back_button).setOnClickListener(v -> finish());

        playButton.setOnClickListener(v -> app.player.toggle());
        findViewById(R.id.player_prev_button).setOnClickListener(v -> app.player.previous());
        findViewById(R.id.player_next_button).setOnClickListener(v -> app.player.next());
        findViewById(R.id.player_mode_button).setOnClickListener(v -> app.player.cycleMode());

        findViewById(R.id.player_fav_button).setOnClickListener(v -> {
            SongInfo s = app.player.currentSong();
            if (s == null) return;
            if (app.playlists.hasSong("Favorites", s)) {
                app.playlists.removeSong("Favorites", s);
                Toast.makeText(this, "Removed from Favorites", Toast.LENGTH_SHORT).show();
            } else {
                app.playlists.addSong("Favorites", s);
                Toast.makeText(this, "Added to Favorites", Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.player_add_to_playlist_button).setOnClickListener(v -> {
            SongInfo s = app.player.currentSong();
            if (s == null) return;
            List<String> names = app.playlists.listPlaylists();
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.music_add_to_playlist)
                    .setItems(names.toArray(new String[0]), (d, w) -> {
                        app.playlists.addSong(names.get(w), s);
                        Toast.makeText(this, "Added to " + names.get(w), Toast.LENGTH_SHORT).show();
                    })
                    .show();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                if (fromUser) app.player.seekTo(p * app.player.getDuration() / 1000);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seekTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { seekTracking = false; }
        });
    }

    private void updateUI() {
        SongInfo s = app.player.currentSong();
        if (s != null) {
            songTitle.setText(s.title);
            songArtist.setText(s.artist + (s.vipRequired ? " · VIP" : ""));
            totalTimeText.setText(s.durationFormatted());
            coverText.setText(s.title.isEmpty() ? "♪" : s.title.substring(0, 1).toUpperCase());
        }
        updatePlayButton(app.player.getState() == MusicPlayer.State.PLAYING);
    }

    private void updatePlayButton(boolean playing) {
        playButton.setText(playing ? "⏸" : "▶");
    }

    // MusicPlayer.Callback
    @Override
    public void onStateChanged(MusicPlayer.State state) {
        updatePlayButton(state == MusicPlayer.State.PLAYING);
    }

    @Override
    public void onProgressChanged(int cur, int total) {
        if (!seekTracking) {
            seekBar.setProgress(total > 0 ? cur * 1000 / total : 0);
        }
        currentTimeText.setText(formatMs(cur));
        totalTimeText.setText(formatMs(total));
    }

    @Override
    public void onSongChanged(SongInfo song) { updateUI(); }

    @Override
    public void onError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private static String formatMs(int ms) {
        int s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }
}
