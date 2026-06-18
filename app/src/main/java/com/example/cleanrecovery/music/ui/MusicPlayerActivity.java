package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.SystemUiHelper;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.Lyrics;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.player.MusicPlayer;

import java.util.List;
import java.util.concurrent.Executors;

public final class MusicPlayerActivity extends Activity implements MusicPlayer.Callback {

    private static final String PREFS = "music_player_prefs";
    private static final String KEY_LYRICS_FONT = "lyrics_font";
    private static final String KEY_LYRICS_THEME = "lyrics_theme";
    private static final String KEY_LYRICS_VISIBLE = "lyrics_visible";

    private MusicApp app;
    private TextView songTitle, songArtist, currentTimeText, totalTimeText, coverText, statusText;
    private ImageButton playButton;
    private SeekBar seekBar;
    private boolean seekTracking;

    // Lyrics
    private View coverPanel;
    private View lyricsPanel;
    private LyricsView lyricsView;
    private TextView lyricsEmpty;
    private Button lyricsFontBtn;
    private Button lyricsColorBtn;
    private boolean lyricsVisible = false;
    private boolean lyricsLoading = false;
    private Lyrics currentLyrics = null;
    private String loadedLyricsHash = null;
    private final Handler ui = new Handler(Looper.getMainLooper());

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

        coverPanel = findViewById(R.id.player_cover_panel);
        lyricsPanel = findViewById(R.id.player_lyrics_panel);
        lyricsView = findViewById(R.id.player_lyrics_view);
        lyricsEmpty = findViewById(R.id.player_lyrics_empty);
        lyricsFontBtn = findViewById(R.id.player_lyrics_font);
        lyricsColorBtn = findViewById(R.id.player_lyrics_color);

        // Restore lyrics preferences
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        lyricsVisible = prefs.getBoolean(KEY_LYRICS_VISIBLE, false);
        try {
            lyricsView.setFontSize(LyricsView.FontSize.valueOf(
                    prefs.getString(KEY_LYRICS_FONT, LyricsView.FontSize.MEDIUM.name())));
            lyricsView.setTheme(LyricsView.Theme.valueOf(
                    prefs.getString(KEY_LYRICS_THEME, LyricsView.Theme.TEAL.name())));
        } catch (IllegalArgumentException ignored) {
            // Keep defaults if stored value is corrupt.
        }
        lyricsView.setOnSeekListener(ms -> app.player.seekTo((int) ms));

        bindListeners();
        updateUI();
        applyLyricsVisibility();
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
        findViewById(R.id.player_mode_button).setOnClickListener(v -> {
            app.player.cycleMode();
            updateModeButton();
            Toast.makeText(this, modeLabel(app.player.getMode()), Toast.LENGTH_SHORT).show();
        });

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

        findViewById(R.id.player_lyrics_toggle).setOnClickListener(v -> {
            lyricsVisible = !lyricsVisible;
            persistLyricsPrefs();
            applyLyricsVisibility();
            if (lyricsVisible) maybeLoadLyrics();
        });

        findViewById(R.id.player_download_button).setOnClickListener(v -> {
            SongInfo s = app.player.currentSong();
            if (s == null) {
                Toast.makeText(this, R.string.music_download_no_song, Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, DownloadActivity.class);
            intent.putExtra("song_hash", s.hash);
            intent.putExtra("song_title", s.title);
            intent.putExtra("song_artist", s.artist);
            intent.putExtra("song_album", s.album);
            intent.putExtra("song_duration", s.duration);
            intent.putExtra("song_album_id", s.albumId);
            intent.putExtra("song_vip", s.vipRequired);
            startActivity(intent);
        });

        lyricsFontBtn.setOnClickListener(v -> {
            lyricsView.cycleFontSize();
            persistLyricsPrefs();
            Toast.makeText(this, fontSizeLabel(lyricsView.getFontSize()), Toast.LENGTH_SHORT).show();
        });
        lyricsColorBtn.setOnClickListener(v -> {
            lyricsView.cycleTheme();
            persistLyricsPrefs();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                if (fromUser) app.player.seekTo(p * app.player.getDuration() / 1000);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seekTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { seekTracking = false; }
        });
    }

    private void applyLyricsVisibility() {
        coverPanel.setVisibility(lyricsVisible ? View.GONE : View.VISIBLE);
        lyricsPanel.setVisibility(lyricsVisible ? View.VISIBLE : View.GONE);
        ImageButton toggle = findViewById(R.id.player_lyrics_toggle);
        toggle.setColorFilter(lyricsVisible
                ? getResources().getColor(R.color.brand_primary, getTheme())
                : getResources().getColor(R.color.text_secondary, getTheme()));
    }

    private void persistLyricsPrefs() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putBoolean(KEY_LYRICS_VISIBLE, lyricsVisible);
        e.putString(KEY_LYRICS_FONT, lyricsView.getFontSize().name());
        e.putString(KEY_LYRICS_THEME, lyricsView.getTheme().name());
        e.apply();
    }

    /** Load lyrics for the current song (off-thread). Skips if already loaded. */
    private void maybeLoadLyrics() {
        SongInfo s = app.player.currentSong();
        if (s == null) return;
        if (s.hash != null && s.hash.equals(loadedLyricsHash) && currentLyrics != null) {
            renderLyrics(currentLyrics);
            return;
        }
        if (lyricsLoading) return;
        lyricsLoading = true;
        lyricsEmpty.setVisibility(View.GONE);
        lyricsView.setVisibility(View.GONE);
        lyricsEmpty.setText(R.string.music_lyrics_loading);
        lyricsEmpty.setVisibility(View.VISIBLE);
        final SongInfo song = s;
        Executors.newSingleThreadExecutor().execute(() -> {
            Lyrics result;
            try {
                result = app.dataSource.getLyrics(song);
            } catch (Exception ex) {
                result = Lyrics.parse("");
            }
            final Lyrics finalResult = result;
            ui.post(() -> {
                lyricsLoading = false;
                loadedLyricsHash = song.hash;
                currentLyrics = finalResult;
                renderLyrics(finalResult);
                // Sync to current position immediately.
                lyricsView.updatePosition(app.player.getCurrentPosition());
            });
        });
    }

    private void renderLyrics(Lyrics lyrics) {
        lyricsView.setLyrics(lyrics);
        if (lyrics == null || lyrics.isEmpty()) {
            lyricsView.setVisibility(View.GONE);
            lyricsEmpty.setText(R.string.music_lyrics_empty);
            lyricsEmpty.setVisibility(View.VISIBLE);
        } else {
            lyricsEmpty.setVisibility(View.GONE);
            lyricsView.setVisibility(View.VISIBLE);
        }
    }

    private String fontSizeLabel(LyricsView.FontSize size) {
        switch (size) {
            case SMALL: return getString(R.string.music_lyrics_font_small);
            case LARGE: return getString(R.string.music_lyrics_font_large);
            case MEDIUM:
            default: return getString(R.string.music_lyrics_font_medium);
        }
    }

    private void updateUI() {
        SongInfo s = app.player.currentSong();
        if (s != null) {
            songTitle.setText(s.title);
            songArtist.setText(s.artist + (s.vipRequired ? " · VIP" : ""));
            totalTimeText.setText(s.durationFormatted());
            coverText.setText(s.title.isEmpty() ? "♪" : s.title.substring(0, 1).toUpperCase());
            // Invalidate cached lyrics when the song changes.
            if (s.hash == null || !s.hash.equals(loadedLyricsHash)) {
                currentLyrics = null;
                loadedLyricsHash = null;
                if (lyricsVisible) maybeLoadLyrics();
            }
        }
        updatePlayButton(app.player.getState());
        updateStatusText(app.player.getState());
        updateModeButton();
        updateFavButton();
    }

    /** 根据当前播放模式更新模式按钮图标。 */
    private void updateModeButton() {
        ImageButton modeBtn = findViewById(R.id.player_mode_button);
        int resId;
        switch (app.player.getMode()) {
            case REPEAT_ALL:  resId = R.drawable.ic_repeat; break;
            case REPEAT_ONE:  resId = R.drawable.ic_repeat_one; break;
            case SHUFFLE:     resId = R.drawable.ic_shuffle; break;
            case SEQUENTIAL:
            default:          resId = R.drawable.ic_sequential; break;
        }
        modeBtn.setImageResource(resId);
        modeBtn.setColorFilter(app.player.getMode() != MusicPlayer.Mode.SEQUENTIAL
                ? getResources().getColor(R.color.brand_primary, getTheme())
                : getResources().getColor(R.color.text_secondary, getTheme()));
    }

    /** 更新收藏按钮状态（已收藏时高亮）。 */
    private void updateFavButton() {
        SongInfo s = app.player.currentSong();
        ImageButton favBtn = findViewById(R.id.player_fav_button);
        boolean isFav = s != null && app.playlists.hasSong("Favorites", s);
        favBtn.setColorFilter(isFav
                ? getResources().getColor(R.color.status_warning, getTheme())
                : getResources().getColor(R.color.text_secondary, getTheme()));
    }

    private String modeLabel(MusicPlayer.Mode mode) {
        switch (mode) {
            case REPEAT_ALL:  return getString(R.string.music_mode_repeat_all);
            case REPEAT_ONE:  return getString(R.string.music_mode_repeat_one);
            case SHUFFLE:     return getString(R.string.music_mode_shuffle);
            case SEQUENTIAL:
            default:          return getString(R.string.music_mode_sequential);
        }
    }

    private void updatePlayButton(MusicPlayer.State state) {
        if (state == MusicPlayer.State.LOADING) {
            playButton.setImageResource(R.drawable.ic_play_white);
            playButton.setEnabled(false);
        } else if (state == MusicPlayer.State.ERROR) {
            playButton.setImageResource(R.drawable.ic_play_white);
            playButton.setEnabled(true);
        } else {
            playButton.setImageResource(state == MusicPlayer.State.PLAYING
                    ? R.drawable.ic_pause : R.drawable.ic_play_white);
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
        if (lyricsVisible) {
            lyricsView.updatePosition(cur);
        }
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
