package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.Lyrics;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.download.DownloadManager;
import com.example.cleanrecovery.music.player.MusicPlayer;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public final class MusicPlayerActivity extends Activity implements MusicPlayer.Callback {

    private static final String PREFS = "music_player_prefs";
    private static final String KEY_LYRICS_FONT_SP = "lyrics_font_sp";
    private static final String KEY_LYRICS_THEME = "lyrics_theme";
    private static final String KEY_LYRICS_VISIBLE = "lyrics_visible";

    private MusicApp app;
    private TextView songTitle;
    private TextView songArtist;
    private TextView currentTimeText;
    private TextView totalTimeText;
    private TextView coverText;
    private TextView statusText;
    private TextView currentLyricText;
    private TextView nextLyricText;
    private ImageView coverImage;
    private ImageButton playButton;
    private SeekBar seekBar;
    private boolean seekTracking;

    private View coverPanel;
    private View lyricsPanel;
    private LyricsView lyricsView;
    private TextView lyricsEmpty;
    private boolean lyricsVisible;
    private boolean lyricsLoading;
    private Lyrics currentLyrics = Lyrics.empty();
    private String loadedLyricsHash;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable sleepTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_music_player);
        app = MusicApp.init(this);
        app.player.addCallback(this);

        bindViews();
        restorePrefs();
        bindListeners();
        updateUI();
        applyLyricsVisibility();
        maybeLoadLyrics();
    }

    @Override
    protected void onDestroy() {
        app.player.removeCallback(this);
        if (sleepTimer != null) ui.removeCallbacks(sleepTimer);
        super.onDestroy();
    }

    private void bindViews() {
        songTitle = findViewById(R.id.player_song_title);
        songArtist = findViewById(R.id.player_song_artist);
        currentTimeText = findViewById(R.id.player_current_time);
        totalTimeText = findViewById(R.id.player_total_time);
        coverText = findViewById(R.id.player_cover_text);
        coverImage = findViewById(R.id.player_cover_image);
        statusText = findViewById(R.id.player_status_text);
        currentLyricText = findViewById(R.id.player_current_lyric);
        nextLyricText = findViewById(R.id.player_next_lyric);
        playButton = findViewById(R.id.player_play_button);
        seekBar = findViewById(R.id.player_seekbar);
        coverPanel = findViewById(R.id.player_cover_panel);
        lyricsPanel = findViewById(R.id.player_lyrics_panel);
        lyricsView = findViewById(R.id.player_lyrics_view);
        lyricsEmpty = findViewById(R.id.player_lyrics_empty);
    }

    private void restorePrefs() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        lyricsVisible = false;
        lyricsView.setCustomFontSizeSp(prefs.getInt(KEY_LYRICS_FONT_SP, 16));
        try {
            lyricsView.setTheme(LyricsView.Theme.valueOf(
                    prefs.getString(KEY_LYRICS_THEME, LyricsView.Theme.TEAL.name())));
        } catch (IllegalArgumentException ignored) {
            lyricsView.setTheme(LyricsView.Theme.TEAL);
        }
    }

    private void bindListeners() {
        findViewById(R.id.player_back_button).setOnClickListener(v -> finish());
        coverPanel.setOnClickListener(v -> toggleLyricsMode());
        lyricsPanel.setOnClickListener(v -> toggleLyricsMode());
        lyricsView.setOnSeekListener(ms -> app.player.seekTo((int) ms));
        lyricsView.setOnSingleTapListener(() -> {
            if (lyricsVisible) toggleLyricsMode();
        });

        playButton.setOnClickListener(v -> {
            if (app.player.getState() == MusicPlayer.State.ERROR) {
                app.player.retryCurrent();
            } else {
                app.player.toggle();
            }
        });
        findViewById(R.id.player_prev_button).setOnClickListener(v -> app.player.previous());
        findViewById(R.id.player_next_button).setOnClickListener(v -> app.player.next());
        findViewById(R.id.player_queue_button).setOnClickListener(v -> showQueueSheet());
        findViewById(R.id.player_more_button).setOnClickListener(v -> showMoreSheet());
        findViewById(R.id.player_lyrics_settings).setOnClickListener(v -> showLyricsSettings());
        findViewById(R.id.player_mode_button).setOnClickListener(v -> {
            app.player.cycleMode();
            updateModeButton();
            Toast.makeText(this, modeLabel(app.player.getMode()), Toast.LENGTH_SHORT).show();
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                if (!fromUser) return;
                int duration = app.player.getDuration();
                if (duration > 0) app.player.seekTo(p * duration / 1000);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seekTracking = true; }
            @Override public void onStopTrackingTouch(SeekBar bar) { seekTracking = false; }
        });
    }

    private void toggleLyricsMode() {
        lyricsVisible = !lyricsVisible;
        persistPrefs();
        applyLyricsVisibility();
        maybeLoadLyrics();
    }

    private void applyLyricsVisibility() {
        coverPanel.setVisibility(lyricsVisible ? View.GONE : View.VISIBLE);
        lyricsPanel.setVisibility(lyricsVisible ? View.VISIBLE : View.GONE);
    }

    private void showMoreSheet() {
        Dialog dialog = bottomDialog();
        LinearLayout root = sheetRoot();
        addSheetAction(root, R.drawable.ic_download, R.string.music_download_current, v -> {
            dialog.dismiss();
            downloadCurrentSong();
        });
        addSheetAction(root, R.drawable.ic_speed, R.string.music_playback_speed, v -> {
            dialog.dismiss();
            showSpeedSheet();
        });
        addSheetAction(root, R.drawable.ic_add, R.string.music_add_to_playlist, v -> {
            dialog.dismiss();
            showAddToPlaylistDialog(app.player.currentSong());
        });
        addSheetAction(root, R.drawable.ic_timer, R.string.music_sleep_timer, v -> {
            dialog.dismiss();
            showSleepTimerSheet();
        });
        showBottomDialog(dialog, root);
    }

    private void showSpeedSheet() {
        Dialog dialog = bottomDialog();
        LinearLayout root = sheetRoot();
        float[] speeds = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        for (float speed : speeds) {
            TextView option = sheetText(String.format(java.util.Locale.US, "%.2fx", speed));
            option.setOnClickListener(v -> {
                app.player.setPlaybackSpeed(speed);
                Toast.makeText(this,
                        getString(R.string.music_playback_speed_set, option.getText()),
                        Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            });
            root.addView(option);
        }
        showBottomDialog(dialog, root);
    }

    private void showSleepTimerSheet() {
        Dialog dialog = bottomDialog();
        LinearLayout root = sheetRoot();
        int[] minutes = {0, 10, 20, 30, 60};
        for (int minute : minutes) {
            int label = minute == 0 ? R.string.music_sleep_timer_off : R.string.music_sleep_timer_minutes;
            TextView option = sheetText(minute == 0
                    ? getString(label)
                    : getString(label, minute));
            option.setOnClickListener(v -> {
                scheduleSleepTimer(minute);
                dialog.dismiss();
            });
            root.addView(option);
        }
        showBottomDialog(dialog, root);
    }

    private void scheduleSleepTimer(int minutes) {
        if (sleepTimer != null) ui.removeCallbacks(sleepTimer);
        if (minutes <= 0) {
            sleepTimer = null;
            Toast.makeText(this, R.string.music_sleep_timer_cancelled, Toast.LENGTH_SHORT).show();
            return;
        }
        sleepTimer = () -> {
            app.player.pause();
            Toast.makeText(this, R.string.music_sleep_timer_stopped, Toast.LENGTH_SHORT).show();
        };
        ui.postDelayed(sleepTimer, minutes * 60_000L);
        Toast.makeText(this, getString(R.string.music_sleep_timer_set, minutes), Toast.LENGTH_SHORT).show();
    }

    private void showQueueSheet() {
        List<SongInfo> queue = app.player.getQueue();
        if (queue.isEmpty()) {
            Toast.makeText(this, R.string.music_queue_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        Dialog dialog = bottomDialog();
        LinearLayout root = sheetRoot();

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = sheetText(getString(R.string.music_queue_sheet_title,
                sourceNameForQueue()));
        title.setTextColor(getColor(R.color.text_primary));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ImageButton mode = new ImageButton(this);
        mode.setBackgroundResource(R.drawable.bg_icon_button);
        mode.setImageResource(modeIcon(app.player.getMode()));
        mode.setColorFilter(getColor(R.color.text_secondary));
        mode.setOnClickListener(v -> {
            app.player.cycleMode();
            updateModeButton();
            mode.setImageResource(modeIcon(app.player.getMode()));
        });
        header.addView(mode, new LinearLayout.LayoutParams(dp(44), dp(44)));
        ImageButton close = new ImageButton(this);
        close.setBackgroundResource(R.drawable.bg_icon_button);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(getColor(R.color.text_secondary));
        close.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        closeLp.setMarginStart(dp(8));
        header.addView(close, closeLp);
        root.addView(header);

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.border_subtle));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dividerLp.setMargins(0, dp(10), 0, dp(8));
        root.addView(divider, dividerLp);

        int current = app.player.getQueueIndex();
        for (int i = 0; i < queue.size(); i++) {
            View row = queueRow(dialog, queue.get(i), i, i == current);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, dp(4), 0, dp(4));
            root.addView(row, rowLp);
        }
        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        int maxHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.68f);
        scrollView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
        showBottomDialog(dialog, scrollView);
    }

    private View queueRow(Dialog dialog, SongInfo song, int index, boolean current) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(10), dp(8), dp(8), dp(8));
        row.setBackgroundResource(current ? R.drawable.bg_nav_item_active : R.drawable.bg_card);
        row.setOnClickListener(v -> {
            if (song.vipRequired && !app.auth.isLoggedIn()) {
                promptVipSync();
                return;
            }
            app.playlists.addRecentPlay(song);
            app.player.playQueueIndex(index);
            dialog.dismiss();
        });

        TextView marker = new TextView(this);
        marker.setGravity(Gravity.CENTER);
        marker.setText(current ? "▶" : String.valueOf(index + 1));
        marker.setTextColor(current ? getColor(R.color.brand_primary) : getColor(R.color.text_muted));
        marker.setTextSize(current ? 14 : 12);
        marker.setTypeface(marker.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(marker, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(10), 0, 0, 0);
        TextView title = new TextView(this);
        title.setText(song.title == null || song.title.isEmpty()
                ? getString(R.string.music_now_playing) : song.title);
        title.setSingleLine(true);
        title.setTextColor(current ? getColor(R.color.brand_primary) : getColor(R.color.text_primary));
        title.setTextSize(15);
        TextView artist = new TextView(this);
        artist.setText(song.artist == null ? "" : song.artist);
        artist.setSingleLine(true);
        artist.setTextColor(getColor(R.color.text_secondary));
        artist.setTextSize(12);
        texts.addView(title);
        texts.addView(artist);
        row.addView(texts, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton delete = new ImageButton(this);
        delete.setBackgroundResource(R.drawable.bg_icon_button);
        delete.setImageResource(R.drawable.ic_delete);
        delete.setColorFilter(getColor(R.color.accent_orange));
        delete.setOnClickListener(v -> {
            app.player.removeQueueIndex(index);
            dialog.dismiss();
            showQueueSheet();
        });
        row.addView(delete, new LinearLayout.LayoutParams(dp(38), dp(38)));
        return row;
    }

    private void downloadCurrentSong() {
        SongInfo song = app.player.currentSong();
        if (song == null || song.hash == null) {
            Toast.makeText(this, R.string.music_download_no_song, Toast.LENGTH_SHORT).show();
            return;
        }
        if (app.downloads.exists(song.hash)) {
            Toast.makeText(this, R.string.music_download_already_exists, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, getString(R.string.music_download_single_started, song.title),
                Toast.LENGTH_SHORT).show();
        app.downloads.enqueue(song, DownloadManager.QUALITY_STANDARD, (s, state, done, total, msg) -> {
            if (state == DownloadManager.State.COMPLETED) {
                Toast.makeText(this,
                        getString(R.string.music_download_single_done_with_path,
                                s.title, app.downloads.downloadsDir().getAbsolutePath()),
                        Toast.LENGTH_LONG).show();
            } else if (state == DownloadManager.State.FAILED) {
                Toast.makeText(this,
                        getString(R.string.music_download_single_failed, s.title,
                                msg == null ? "" : msg),
                        Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showAddToPlaylistDialog(SongInfo song) {
        if (song == null) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_add_to_playlist)
                .setItems(new String[]{displayPlaylistName("Listen Later")}, (d, w) -> {
                    app.playlists.addSong("Listen Later", song);
                    Toast.makeText(this,
                            getString(R.string.music_added_to_playlist,
                                    displayPlaylistName("Listen Later")),
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showLyricsSettings() {
        Dialog dialog = bottomDialog();
        LinearLayout root = sheetRoot();

        TextView sizeLabel = sheetText(getString(R.string.music_lyrics_font_size));
        root.addView(sizeLabel);
        SeekBar fontSeek = new SeekBar(this);
        fontSeek.setMax(12);
        fontSeek.setProgress(Math.max(0, lyricsView.getCurrentFontSizeSp() - 12));
        fontSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int sp = 12 + progress;
                lyricsView.setCustomFontSizeSp(sp);
                persistPrefs();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        root.addView(fontSeek);

        TextView colorLabel = sheetText(getString(R.string.music_lyrics_color));
        colorLabel.setPadding(0, dp(14), 0, dp(8));
        root.addView(colorLabel);
        LinearLayout colors = new LinearLayout(this);
        colors.setOrientation(LinearLayout.HORIZONTAL);
        addThemeButton(colors, LyricsView.Theme.TEAL, R.string.music_lyrics_theme_teal, Color.parseColor("#14B8A6"));
        addThemeButton(colors, LyricsView.Theme.BLUE, R.string.music_lyrics_theme_blue, Color.parseColor("#3B82F6"));
        addThemeButton(colors, LyricsView.Theme.AMBER, R.string.music_lyrics_theme_amber, Color.parseColor("#F59E0B"));
        addThemeButton(colors, LyricsView.Theme.WHITE, R.string.music_lyrics_theme_white, Color.parseColor("#FFFFFF"));
        root.addView(colors);
        showBottomDialog(dialog, root);
    }

    private void addThemeButton(LinearLayout row, LyricsView.Theme theme, int labelRes, int color) {
        Button button = new Button(this);
        button.setText(labelRes);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinHeight(0);
        button.setBackgroundTintList(ColorStateList.valueOf(
                lyricsView.getTheme() == theme ? color : getColor(R.color.brand_accent_soft)));
        button.setTextColor(theme == LyricsView.Theme.WHITE && lyricsView.getTheme() == theme
                ? getColor(R.color.text_primary)
                : getColor(R.color.text_primary));
        button.setOnClickListener(v -> {
            lyricsView.setTheme(theme);
            persistPrefs();
            showLyricsSettings();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1f);
        lp.setMarginEnd(dp(6));
        row.addView(button, lp);
    }

    private void maybeLoadLyrics() {
        SongInfo song = app.player.currentSong();
        if (song == null) return;
        if (song.hash != null && song.hash.equals(loadedLyricsHash) && currentLyrics != null) {
            renderLyrics(currentLyrics);
            return;
        }
        if (lyricsLoading) return;
        lyricsLoading = true;
        final SongInfo loadingSong = song;
        Executors.newSingleThreadExecutor().execute(() -> {
            Lyrics result;
            try {
                result = app.dataSource.getLyrics(loadingSong);
            } catch (Exception exception) {
                result = Lyrics.empty();
            }
            Lyrics finalResult = result;
            ui.post(() -> {
                lyricsLoading = false;
                loadedLyricsHash = loadingSong.hash;
                currentLyrics = finalResult;
                renderLyrics(finalResult);
                updateLyricSummary(app.player.getCurrentPosition());
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

    private void updateLyricSummary(long positionMs) {
        if (currentLyrics == null || currentLyrics.isEmpty()) {
            currentLyricText.setText(R.string.music_lyrics_empty);
            nextLyricText.setText("");
            return;
        }
        int index = currentLyrics.indexOfActive(positionMs);
        if (index < 0) index = 0;
        List<Lyrics.Line> lines = currentLyrics.lines();
        currentLyricText.setText(lines.get(index).text);
        nextLyricText.setText(index + 1 < lines.size() ? lines.get(index + 1).text : "");
    }

    private void updateUI() {
        SongInfo song = app.player.currentSong();
        if (song != null) {
            songTitle.setText(song.title);
            songArtist.setText(song.artist + (song.vipRequired ? " · VIP" : ""));
            totalTimeText.setText(song.durationFormatted());
            coverText.setText(song.title == null || song.title.isEmpty()
                    ? "♪" : song.title.substring(0, 1).toUpperCase());
            loadCover(song);
            if (song.hash == null || !song.hash.equals(loadedLyricsHash)) {
                currentLyrics = Lyrics.empty();
                loadedLyricsHash = null;
                maybeLoadLyrics();
            }
        }
        updatePlayButton(app.player.getState());
        updateStatusText(app.player.getState());
        updateModeButton();
        updateLyricSummary(app.player.getCurrentPosition());
    }

    private void loadCover(SongInfo song) {
        coverImage.setVisibility(View.GONE);
        coverText.setVisibility(View.VISIBLE);
        if (song == null || song.imgUrl == null || song.imgUrl.isEmpty()) return;
        String url = song.imgUrl;
        Executors.newSingleThreadExecutor().execute(() -> {
            try (InputStream input = new URL(url).openStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                ui.post(() -> {
                    if (bitmap != null && app.player.currentSong() == song) {
                        coverImage.setImageBitmap(bitmap);
                        coverImage.setVisibility(View.VISIBLE);
                        coverText.setVisibility(View.GONE);
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void updatePlayButton(MusicPlayer.State state) {
        if (state == MusicPlayer.State.LOADING) {
            playButton.setImageResource(R.drawable.ic_play_white);
            playButton.setEnabled(false);
        } else {
            playButton.setImageResource(state == MusicPlayer.State.PLAYING
                    ? R.drawable.ic_pause : R.drawable.ic_play_white);
            playButton.setEnabled(true);
        }
    }

    private void updateStatusText(MusicPlayer.State state) {
        if (state == MusicPlayer.State.LOADING) {
            statusText.setText(R.string.music_playback_loading);
            statusText.setTextColor(getColor(R.color.text_muted));
            statusText.setVisibility(View.VISIBLE);
        } else if (state == MusicPlayer.State.ERROR) {
            statusText.setText(R.string.music_playback_failed);
            statusText.setTextColor(getColor(R.color.status_warning));
            statusText.setVisibility(View.VISIBLE);
        } else {
            statusText.setVisibility(View.GONE);
        }
    }

    private void updateModeButton() {
        ImageButton modeBtn = findViewById(R.id.player_mode_button);
        modeBtn.setImageResource(modeIcon(app.player.getMode()));
        modeBtn.setColorFilter(app.player.getMode() != MusicPlayer.Mode.SEQUENTIAL
                ? getColor(R.color.brand_primary)
                : getColor(R.color.text_secondary));
    }

    private int modeIcon(MusicPlayer.Mode mode) {
        switch (mode) {
            case REPEAT_ALL: return R.drawable.ic_repeat;
            case REPEAT_ONE: return R.drawable.ic_repeat_one;
            case SHUFFLE: return R.drawable.ic_shuffle;
            case SEQUENTIAL:
            default: return R.drawable.ic_sequential;
        }
    }

    private String modeLabel(MusicPlayer.Mode mode) {
        switch (mode) {
            case REPEAT_ALL: return getString(R.string.music_mode_repeat_all);
            case REPEAT_ONE: return getString(R.string.music_mode_repeat_one);
            case SHUFFLE: return getString(R.string.music_mode_shuffle);
            case SEQUENTIAL:
            default: return getString(R.string.music_mode_sequential);
        }
    }

    private String sourceNameForQueue() {
        String sourceName = app.player.getPlaySourceName();
        return sourceName == null || sourceName.isEmpty()
                ? getString(R.string.music_queue)
                : sourceName;
    }

    private void promptVipSync() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(app.auth.isLoggedIn()
                        ? R.string.music_vip_prompt_title
                        : R.string.music_login_required_title)
                .setMessage(app.auth.isLoggedIn()
                        ? R.string.music_vip_prompt
                        : R.string.music_vip_login_prompt)
                .setPositiveButton(app.auth.isLoggedIn()
                        ? R.string.music_vip_login_or_skip
                        : R.string.music_login,
                        (d, w) -> {
                            if (app.auth.isLoggedIn()) {
                                app.refreshEntitlementAsync();
                            } else {
                                startActivity(new android.content.Intent(this, MusicLoginActivity.class));
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private Dialog bottomDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        Window initialWindow = dialog.getWindow();
        if (initialWindow != null) {
            initialWindow.setWindowAnimations(R.style.MusicBottomSheetAnimation);
        }
        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.setGravity(Gravity.BOTTOM);
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                window.setWindowAnimations(R.style.MusicBottomSheetAnimation);
            }
        });
        return dialog;
    }

    private LinearLayout sheetRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        root.setBackgroundResource(R.drawable.bg_bottom_sheet);
        return root;
    }

    private void showBottomDialog(Dialog dialog, View content) {
        dialog.setContentView(content);
        dialog.show();
    }

    private void addSheetAction(LinearLayout root, int iconRes, int textRes, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, dp(8));
        ImageButton icon = new ImageButton(this);
        icon.setImageResource(iconRes);
        icon.setBackgroundResource(R.drawable.bg_icon_button);
        icon.setColorFilter(getColor(R.color.text_secondary));
        row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView label = sheetText(getString(textRes));
        label.setPadding(dp(14), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(click);
        icon.setOnClickListener(click);
        root.addView(row);
    }

    private TextView sheetText(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.text_primary));
        view.setTextSize(15);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(40));
        return view;
    }

    private void persistPrefs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_LYRICS_VISIBLE, lyricsVisible)
                .putInt(KEY_LYRICS_FONT_SP, lyricsView.getCurrentFontSizeSp())
                .putString(KEY_LYRICS_THEME, lyricsView.getTheme().name())
                .apply();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onStateChanged(MusicPlayer.State state) {
        updatePlayButton(state);
        updateStatusText(state);
        updateModeButton();
    }

    @Override
    public void onProgressChanged(int cur, int total) {
        if (!seekTracking) {
            seekBar.setProgress(total > 0 ? cur * 1000 / total : 0);
        }
        currentTimeText.setText(formatMs(cur));
        totalTimeText.setText(formatMs(total));
        lyricsView.updatePosition(cur);
        updateLyricSummary(cur);
    }

    @Override
    public void onSongChanged(SongInfo song) {
        updateUI();
    }

    @Override
    public void onError(String msg) {
        if (msg != null && !msg.isEmpty()) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    private static String formatMs(int ms) {
        int s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private String displayPlaylistName(String name) {
        if ("Listen Later".equals(name)) return getString(R.string.music_playlist_listen_later);
        if ("Recently Played".equals(name)) return getString(R.string.music_recent);
        return name;
    }
}
