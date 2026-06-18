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
    private TextView songTitle, songArtist, currentTimeText, totalTimeText, coverText, statusText;
    private Button playButton;
    private SeekBar seekBar;
    private boolean seekTracking;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_music_player);
        app = MusicApp.init(this);
        app.player.addCallback(this);

        songTitle = findViewById(R.id.player_song_title);
        songArtist = findViewById(R.id.player_song_artist);
        currentTimeText = findViewById(R.id.player_current_time);
        totalTimeText = findViewById(R.id.player_total_time);
        coverText = findViewById(R.id.player_cover_text);
        statusText = findViewById(R.id.player_status_text);
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

        playButton.setOnClickListener(v -> {
            // If in ERROR state, retry playback; otherwise toggle play/pause
            if (app.player.getState() == MusicPlayer.State.ERROR) {
                SongInfo s = app.player.currentSong();
                if (s != null) app.player.playSingle(s);
            } else {
                app.player.toggle();
            }
        });
        findViewById(R.id.player_prev_button).setOnClickListener(v -> app.player.previous());
        findViewById(R.id.player_next_button).setOnClickListener(v -> app.player.next());
        findViewById(R.id.player_mode_button).setOnClickListener(v -> app.player.cycleMode());

        findViewById(R.id.player_fav_button).setOnClickListener(v -> {
            SongInfo s = app.player.currentSong();
            if (s == null) return;
            if (app.playlists.hasSong("Favorites", s)) {
                app.playlists.removeSong("Favorites", s);
                Toast.makeText(this, R.string.music_removed_from_favorites, Toast.LENGTH_SHORT).show();
            } else {
                app.playlists.addSong("Favorites", s);
                Toast.makeText(this, R.string.music_added_to_favorites, Toast.LENGTH_SHORT).show();
            }
        });

        findViewById(R.id.player_add_to_playlist_button).setOnClickListener(v -> {
            SongInfo s = app.player.currentSong();
            if (s == null) return;
            List<String> names = app.playlists.listPlaylists();
            String[] displayNames = new String[names.size()];
            for (int i = 0; i < names.size(); i++) displayNames[i] = displayPlaylistName(names.get(i));
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.music_add_to_playlist)
                    .setItems(displayNames, (d, w) -> {
                        app.playlists.addSong(names.get(w), s);
                        Toast.makeText(this,
                                getString(R.string.music_added_to_playlist, displayPlaylistName(names.get(w))),
                                Toast.LENGTH_SHORT).show();
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
        updatePlayButton(app.player.getState());
        updateStatusText(app.player.getState());
    }

    private void updatePlayButton(MusicPlayer.State state) {
        if (state == MusicPlayer.State.LOADING) {
            playButton.setText("…");
            playButton.setEnabled(false);
        } else if (state == MusicPlayer.State.ERROR) {
            playButton.setText("↻");
            playButton.setEnabled(true);
        } else {
            playButton.setText(state == MusicPlayer.State.PLAYING ? "⏸" : "▶");
            playButton.setEnabled(true);
        }
    }

    private void updateStatusText(MusicPlayer.State state) {
        switch (state) {
            case LOADING:
                statusText.setText(R.string.music_playback_loading);
                statusText.setTextColor(getColor(R.color.text_muted));
                statusText.setVisibility(View.VISIBLE);
                break;
            case ERROR:
                statusText.setText(R.string.music_playback_failed);
                statusText.setTextColor(getColor(R.color.status_warning));
                statusText.setVisibility(View.VISIBLE);
                break;
            default:
                statusText.setVisibility(View.GONE);
                break;
        }
    }

    // MusicPlayer.Callback
    @Override
    public void onStateChanged(MusicPlayer.State state) {
        updatePlayButton(state);
        updateStatusText(state);
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
        // Status text already set by onStateChanged(ERROR).
        // Show a Toast with the specific error message for easier diagnosis.
        String display = (msg != null && !msg.isEmpty()) ? msg
                : getString(R.string.music_playback_failed);
        Toast.makeText(this, display, Toast.LENGTH_LONG).show();
    }

    private static String formatMs(int ms) {
        int s = ms / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private String displayPlaylistName(String name) {
        if ("Favorites".equals(name)) return getString(R.string.music_playlist_favorites);
        if ("Listen Later".equals(name)) return getString(R.string.music_playlist_listen_later);
        if ("Recently Played".equals(name)) return getString(R.string.music_recent);
        return name;
    }
}
