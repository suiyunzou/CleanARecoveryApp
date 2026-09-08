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
import com.example.cleanrecovery.ui.widget.GlassToast;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.DownloadedSong;
import com.example.cleanrecovery.music.data.Lyrics;
import com.example.cleanrecovery.music.data.RemotePlaylist;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.download.DownloadManager;
import com.example.cleanrecovery.music.player.MusicPlayer;
import com.example.cleanrecovery.music.player.PlaybackQueue;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public final class MusicPlayerActivity extends Activity implements MusicPlayer.Callback {

    /** 播放页是否在前台：主页/歌单页据此决定是否兜底弹错误提示，避免一条错误弹多个 toast。 */
    public static volatile boolean sPlayerForeground;

    /** 附带此 extra 启动时，进入播放页后自动弹出队列弹窗（迷你条"选择歌单"入口用）。 */
    public static final String EXTRA_OPEN_QUEUE = "extra_open_queue";

    private static final String PREFS = "music_player_prefs";
    private static final String KEY_LYRICS_FONT_SP = "lyrics_font_sp";
    private static final String KEY_LYRICS_THEME = "lyrics_theme_v2";
    private static final String KEY_LYRICS_VISIBLE = "lyrics_visible";
    private static final String KEY_VIPER_EFFECT = "viper_effect";
    private static final String KEY_PLAY_QUALITY = "play_quality";

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
    private View coverDisc;
    private android.animation.ObjectAnimator discRotate;
    private ImageButton playButton;
    private MusicSeekBar seekBar;
    private boolean seekTracking;
    private TextView seekPreview;
    private Dialog lyricsSettingsDialog;

    private View coverPanel;
    private View lyricsPanel;
    private LyricsView lyricsView;
    private TextView lyricsEmpty;
    private boolean lyricsVisible;
    private String requestedLyricsHash;
    private int lyricsRequestId;
    private long climaxStartMs = -1;
    private final java.util.concurrent.ExecutorService lyricsExecutor = Executors.newFixedThreadPool(2);
    private Lyrics currentLyrics = Lyrics.empty();
    private String loadedLyricsHash;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable wordProgress = new Runnable() {
        @Override public void run() {
            if (!sPlayerForeground || !lyricsVisible
                    || app.player.getState() != MusicPlayer.State.PLAYING) return;
            lyricsView.updatePosition(app.player.getCurrentPosition());
            lyricsView.postOnAnimation(this);
        }
    };

    private Dialog queueDialog;
    private QueueAdapter queueAdapter;
    private androidx.recyclerview.widget.ItemTouchHelper queueTouchHelper;
    private QueuePagerAdapter queuePagerAdapter;
    private RecyclerView queuePager;

    /** 拖动排序态：长按歌曲进入（出现把手+完成），点完成恢复常态。 */
    private boolean queueSortMode;
    private View queueHeaderActions;
    private View queueHeaderDone;
    private View lyricsCenterWidget;
    private TextView lyricsCenterTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_music_player);
        requestNotificationPermissionIfNeeded();
        app = MusicApp.init(this);
        app.player.addCallback(this);
        // 定时到点提示（进程级定时器在 MusicPlayer 内，页面销毁不失效）
        app.player.setSleepFireCallback(() -> GlassToast.makeText(getApplicationContext(),
                R.string.music_sleep_timer_stopped, GlassToast.LENGTH_SHORT).show());

        bindViews();
        // 概念版行为：播放中封面/唱盘匀速旋转，暂停即停住
        discRotate = android.animation.ObjectAnimator.ofFloat(coverDisc, View.ROTATION, 0f, 360f);
        discRotate.setDuration(24000);
        discRotate.setInterpolator(new android.view.animation.LinearInterpolator());
        discRotate.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        restorePrefs();
        bindListeners();
        updateUI();
        applyLyricsVisibility();
        maybeLoadLyrics();
    }

    /** Android 13+ 需要运行时通知权限,媒体通知卡片才能在状态栏显示。 */
    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 100);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sPlayerForeground = true;
        // 返回后再次进入时，没有进度回调（尤其暂停态），需主动按当前播放位置同步一次，
        // 否则进度条/时间/歌词会停留在上次离开时的旧值。
        updateUI();
        syncProgressFromPlayer();
        ui.removeCallbacks(wordProgress);
        lyricsView.removeCallbacks(wordProgress);
        ui.post(wordProgress);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(wordProgress);
        lyricsView.removeCallbacks(wordProgress);
        sPlayerForeground = false;
        super.onPause();
    }

    /** 用播放器的实时位置刷新进度条、时间文本与歌词位置。 */
    private void syncProgressFromPlayer() {
        int cur = app.player.getCurrentPosition();
        int total = app.player.getDuration();
        seekBar.setClimax(climaxStartMs, total);
        if (!seekTracking) {
            seekBar.setProgress(total > 0 ? cur * 1000 / total : 0);
            currentTimeText.setText(formatMs(cur));
        }
        if (total > 0) totalTimeText.setText(formatMs(total));
        lyricsView.updatePosition(cur);
        updateLyricSummary(cur);
    }

    @Override
    protected void onDestroy() {
        lyricsRequestId++;
        lyricsExecutor.shutdownNow();
        if (lyricsSettingsDialog != null) lyricsSettingsDialog.dismiss();
        app.player.removeCallback(this);
        super.onDestroy();
    }

    private void bindViews() {
        songTitle = findViewById(R.id.player_song_title);
        songArtist = findViewById(R.id.player_song_artist);
        currentTimeText = findViewById(R.id.player_current_time);
        totalTimeText = findViewById(R.id.player_total_time);
        coverText = findViewById(R.id.player_cover_text);
        coverImage = findViewById(R.id.player_cover_image);
        coverDisc = findViewById(R.id.player_cover_disc);
        statusText = findViewById(R.id.player_status_text);
        currentLyricText = findViewById(R.id.player_current_lyric);
        nextLyricText = findViewById(R.id.player_next_lyric);
        playButton = findViewById(R.id.player_play_button);
        seekBar = findViewById(R.id.player_seekbar);
        seekPreview = findViewById(R.id.player_seek_preview);
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
                    prefs.getString(KEY_LYRICS_THEME, LyricsView.Theme.DARK.name())));
        } catch (IllegalArgumentException ignored) {
            lyricsView.setTheme(LyricsView.Theme.DARK);
        }
    }

    private void bindListeners() {
        findViewById(R.id.player_back_button).setOnClickListener(v -> finish());
        // 爱心=我喜欢歌单开关；右上角书签=添加到歌单（我的歌单选择）
        findViewById(R.id.player_bookmark_button).setOnClickListener(v ->
                showAddToPlaylistDialog(app.player.currentSong()));
        findViewById(R.id.player_like_button).setOnClickListener(v -> toggleLike());
        findViewById(R.id.player_share_button).setOnClickListener(v -> shareCurrentSong());
        findViewById(R.id.player_more_button).setOnClickListener(v -> showPlayerSettings());
        findViewById(R.id.player_comment_button).setOnClickListener(v ->
                GlassToast.makeText(this, R.string.music_comment_todo, GlassToast.LENGTH_SHORT).show());
        findViewById(R.id.player_lyrics_settings).setOnClickListener(v -> showLyricsSettings());
        TextView translate = findViewById(R.id.player_lyrics_translate);
        translate.setOnClickListener(v -> {
            boolean enabled = !v.isSelected();
            v.setSelected(enabled);
            translate.setTextColor(enabled ? Color.BLACK : getColor(R.color.text_hint));
            lyricsView.setTranslationVisible(enabled);
        });
        coverPanel.setOnClickListener(v -> toggleLyricsMode());
        lyricsPanel.setOnClickListener(v -> toggleLyricsMode());
        // 歌词行点击 / 中轴浮动条播放钮：跳到该句并确保播放
        lyricsView.setOnSeekListener(ms -> {
            // 歌词时间可能超出短音频时长（本地测试音/裁剪曲），钳到结尾前，避免越过末尾触发切歌
            int duration = app.player.getDuration();
            int target = (int) Math.max(0, ms);
            if (duration > 0 && target > duration - 500) {
                target = Math.max(0, duration - 500);
            }
            app.player.seekTo(target);
            if (app.player.getState() == MusicPlayer.State.PAUSED) {
                app.player.resume();
            }
        });
        lyricsView.setOnSingleTapListener(() -> {
            if (lyricsVisible) toggleLyricsMode();
        });
        // 拖动歌词 → 中轴浮现"时间+进度线+播放钮"（概念版交互）
        lyricsCenterWidget = findViewById(R.id.player_lyrics_center_widget);
        lyricsCenterTime = findViewById(R.id.player_lyrics_center_time);
        ImageButton centerPlay = findViewById(R.id.player_lyrics_center_play);
        centerPlay.setOnClickListener(v -> lyricsView.exitBrowseMode(true));
        lyricsView.setOnBrowseListener(new LyricsView.OnBrowseListener() {
            @Override public void onBrowseChanged(boolean browse) {
                if (lyricsCenterWidget != null) {
                    lyricsCenterWidget.setVisibility(browse ? View.VISIBLE : View.GONE);
                }
            }

            @Override public void onCenterLineChanged(long timeMs) {
                if (lyricsCenterTime != null) {
                    lyricsCenterTime.setText(formatMs((int) Math.max(0, timeMs)));
                }
            }
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

        // 迷你条"选择歌单"胶囊直达：进入播放页即展开队列弹窗
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_QUEUE, false)) {
            findViewById(R.id.player_queue_button).post(this::showQueueSheet);
        }
        findViewById(R.id.player_mode_button).setOnClickListener(v -> {
            app.player.cycleMode();
            updateModeButton();
            GlassToast.makeText(this, modeLabel(app.player.getMode()), GlassToast.LENGTH_SHORT).show();
        });

        // 拖动期间只做时间预览，松手才真正 seek：
        // 网络流上逐帧 seek 会反复中断缓冲（表现为拖不动/卡顿），且未就绪时 seek 报 -38。
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                if (!fromUser) return;
                int duration = app.player.getDuration();
                if (duration > 0) currentTimeText.setText(formatMs(p * duration / 1000));
                updateSeekPreview();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { seekTracking = true; seekPreview.setVisibility(View.VISIBLE); updateSeekPreview(); }
            @Override public void onStopTrackingTouch(SeekBar bar) {
                seekTracking = false;
                seekPreview.setVisibility(View.INVISIBLE);
                int duration = app.player.getDuration();
                if (duration > 0) {
                    app.player.seekTo(bar.getProgress() * duration / 1000);
                    syncProgressFromPlayer();
                }
            }
        });
    }

    private void updateSeekPreview() {
        seekPreview.setText(formatMs((int) ((long) seekBar.getProgress() * app.player.getDuration() / 1000)));
        seekPreview.post(() -> {
            float x = seekBar.getX() + seekBar.positionX(seekBar.getProgress() / 1000f)
                    - seekPreview.getWidth() / 2f;
            seekPreview.setX(Math.max(seekBar.getX(), Math.min(x,
                    seekBar.getX() + seekBar.getWidth() - seekPreview.getWidth())));
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
        ui.removeCallbacks(wordProgress);
        lyricsView.removeCallbacks(wordProgress);
        if (lyricsVisible) ui.post(wordProgress);
    }

    private void showSpeedSheet() {
        Dialog dialog = bottomDialog();
        LinearLayout root = sheetRoot();
        float[] speeds = {0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
        for (float speed : speeds) {
            TextView option = sheetText(String.format(java.util.Locale.US, "%.2fx", speed));
            option.setOnClickListener(v -> {
                app.player.setPlaybackSpeed(speed);
                GlassToast.makeText(this,
                        getString(R.string.music_playback_speed_set, option.getText()),
                        GlassToast.LENGTH_SHORT).show();
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
        // 酷狗机制：播完当前歌曲 / 播完整个列表后停止
        TextView endOfSong = sheetText(getString(R.string.music_sleep_end_of_song));
        endOfSong.setOnClickListener(v -> {
            app.player.setSleepMode(MusicPlayer.SleepMode.END_OF_SONG);
            dialog.dismiss();
            GlassToast.makeText(this, R.string.music_sleep_end_of_song, GlassToast.LENGTH_SHORT).show();
        });
        root.addView(endOfSong);
        TextView endOfQueue = sheetText(getString(R.string.music_sleep_end_of_queue));
        endOfQueue.setOnClickListener(v -> {
            app.player.setSleepMode(MusicPlayer.SleepMode.END_OF_QUEUE);
            dialog.dismiss();
            GlassToast.makeText(this, R.string.music_sleep_end_of_queue, GlassToast.LENGTH_SHORT).show();
        });
        root.addView(endOfQueue);
        showBottomDialog(dialog, root);
    }

    private void scheduleSleepTimer(int minutes) {
        // 进程级定时（MusicPlayer 内部 Handler）：离开播放器页/锁屏不失效（酷狗机制）
        app.player.setSleepTimerMinutes(minutes);
        if (minutes <= 0) {
            GlassToast.makeText(this, R.string.music_sleep_timer_cancelled, GlassToast.LENGTH_SHORT).show();
            return;
        }
        GlassToast.makeText(this, getString(R.string.music_sleep_timer_set, minutes), GlassToast.LENGTH_SHORT).show();
    }

    private void showQueueSheet() {
        queueSortMode = false;
        Dialog dialog = bottomDialog();
        LinearLayout root = queueSheetRoot();
        enrichQueueVipAsync(); // 云端我喜欢歌单的 VIP 键集合（本地文件条目缺元数据）

        int pageCount = 1 + app.player.getQueueHistory().size();

        // 当前队列 + 历史队列，原歌单仍从音乐库进入。
        queuePagerIndicator = buildQueueIndicator(pageCount, 0);
        root.addView(queuePagerIndicator, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 只浏览历史页不切歌，点击其中一首才恢复该份队列。
        RecyclerView pager = new RecyclerView(this);
        pager.setLayoutManager(new LinearLayoutManager(
                this, LinearLayoutManager.HORIZONTAL, false));
        pager.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // 页数少：缓存全部页，避免翻页时页面被回收重建（页内持有头部/列表状态）
        pager.setItemViewCacheSize(pageCount);
        pager.getRecycledViewPool().setMaxRecycledViews(0, pageCount + 2);
        pager.getRecycledViewPool().setMaxRecycledViews(1, pageCount + 2);
        new androidx.recyclerview.widget.PagerSnapHelper().attachToRecyclerView(pager);
        if (pageCount > 1) {
            pager.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                    updateQueuePagerIndicator(rv);
                }

                @Override public void onScrollStateChanged(RecyclerView rv, int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        updateQueuePagerIndicator(rv);
                    }
                }
            });
        }
        queuePager = pager;
        queuePagerAdapter = new QueuePagerAdapter(dialog);
        pager.setAdapter(queuePagerAdapter);
        queueTouchHelper = new androidx.recyclerview.widget.ItemTouchHelper(new QueueDragCallback());

        root.addView(pager, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // 队列弹层固定 62% 屏高；对齐酷狗概念版：全宽贴底无侧缝，顶部圆角
        int sheetHeight = Math.round(getResources().getDisplayMetrics().heightPixels * 0.62f);
        queueDialog = dialog;
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        wrap.setPadding(0, 0, 0, 0);
        wrap.addView(root, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, sheetHeight));
        dialog.setContentView(wrap);
        dialog.show();
    }

    private LinearLayout queuePagerIndicator;


    /** 翻页时同步顶部指示器：当前页长横线，其余短点。 */
    private void updateQueuePagerIndicator(RecyclerView pager) {
        if (queuePagerIndicator == null || pager.getWidth() == 0) return;
        int page = Math.round((float) pager.computeHorizontalScrollOffset() / pager.getWidth());
        int count = queuePagerIndicator.getChildCount();
        if (page < 0) page = 0;
        if (page > count - 1) page = count - 1;
        for (int i = 0; i < count; i++) {
            View dot = queuePagerIndicator.getChildAt(i);
            dot.setBackgroundResource(i == page
                    ? R.drawable.bg_pager_page_on : R.drawable.bg_pager_page_off);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) dot.getLayoutParams();
            lp.width = dp(i == page ? 18 : 5);
            dot.setLayoutParams(lp);
        }
    }

    /** 云端「我喜欢」的 VIP 键集合（"h:hash" / "t:标题|歌手"），队列条目缺元数据时回填。 */
    private java.util.Set<String> cloudVipKeys;
    private boolean cloudVipLoading;

    /** 后台收集云端 VIP 键：我喜欢 + 推荐歌曲，再对仍缺 VIP 的队列条目定向搜索；
     *  完成后刷新队列行徽标（每会话一次）。 */
    private void enrichQueueVipAsync() {
        if (cloudVipKeys != null || cloudVipLoading || !app.auth.isLoggedIn()) return;
        cloudVipLoading = true;
        // 快照待回填的队列条目（后台线程只读这份副本）
        List<SongInfo> pending = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (SongInfo s : app.player.getQueue()) {
            if (s == null || s.vipRequired || s.title == null || s.title.isEmpty()) continue;
            String k = (s.title + "|" + s.artist).toLowerCase(java.util.Locale.ROOT);
            if (seen.add(k)) pending.add(s);
        }
        final List<SongInfo> toSearch = pending.size() > 10
                ? pending.subList(0, 10) : pending;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            java.util.Set<String> keys = new java.util.HashSet<>();
            // 历史播放（本地库，有云端歌曲的 VIP 元数据，离线可用）
            collectVipKeysFromSongs(keys, app.playlists.getRecentPlays(200));
            try {
                collectVipKeysFromSongs(keys, app.dataSource.getRecommendations(1));
            } catch (Exception ignored) {
            }
            try {
                RemotePlaylist fav = app.dataSource.getFavoriteCloudPlaylist();
                if (fav != null) {
                    collectVipKeysFromSongs(keys, app.dataSource.getAllUserPlaylistSongs(fav, 200));
                }
            } catch (Exception ignored) {
                // 未登录/弱网：队列回退到本地元数据，不阻塞面板
            }
            // 定向搜索兜底：抖音/本地文件来源的歌在目录里未必有，逐首查一次（限 10 首）
            for (SongInfo s : toSearch) {
                try {
                    collectVipKeysFromSongs(keys, app.dataSource.search(
                            (s.title + " " + (s.artist == null ? "" : s.artist)).trim(), 1));
                } catch (Exception ignored) {
                }
            }
            runOnUiThread(() -> {
                cloudVipKeys = keys;
                cloudVipLoading = false;
                // 当前队列与历史队列共用元数据徽标。
                if (queueDialog != null && queueDialog.isShowing()) {
                    if (queueAdapter != null) queueAdapter.notifyDataSetChanged();
                    if (queuePagerAdapter != null) queuePagerAdapter.notifyDataSetChanged();
                }
            });
        });
        pool.shutdown();
    }

    /** 把一批云端歌曲中的 VIP 条目登记进键集合（hash 键 + 标题|歌手 键）。 */
    private static void collectVipKeysFromSongs(java.util.Set<String> keys, List<SongInfo> songs) {
        if (songs == null) return;
        for (SongInfo s : songs) {
            if (s == null || !s.vipRequired) continue;
            if (s.hash != null && !s.hash.isEmpty()) {
                keys.add("h:" + s.hash.toLowerCase(java.util.Locale.ROOT));
            }
            String t = s.title == null ? "" : s.title;
            String a = s.artist == null ? "" : s.artist;
            keys.add("t:" + t.toLowerCase(java.util.Locale.ROOT)
                    + "|" + a.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /** 队列弹窗两态：常态（删除按钮）；长按歌曲进入排序态（把手+完成），点完成恢复。 */
    private void toggleQueueSortMode(boolean on) {        queueSortMode = on;
        if (queueHeaderActions != null) {
            queueHeaderActions.setVisibility(on ? View.GONE : View.VISIBLE);
        }
        if (queueHeaderDone != null) {
            queueHeaderDone.setVisibility(on ? View.VISIBLE : View.GONE);
        }
        if (queueAdapter != null) {
            queueAdapter.setSortMode(on);
            queueAdapter.notifyDataSetChanged();
        }
    }

    /** 播放列表横向翻页适配器：第 0 页当前队列（可长按排序），其余页历史队列快照。 */
    private final class QueuePagerAdapter extends RecyclerView.Adapter<QueuePageVH> {
        private final Dialog dialog;

        QueuePagerAdapter(Dialog dialog) {
            this.dialog = dialog;
        }

        @Override
        public int getItemViewType(int position) {
            // 每页独立 viewType：页数少，杜绝 RecycledViewPool 跨页复用导致的错页内容
            return position;
        }

        @Override
        public QueuePageVH onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout page = new LinearLayout(parent.getContext());
            page.setOrientation(LinearLayout.VERTICAL);
            // 每页必须占满 pager 宽度，否则相邻页会同屏露出
            page.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new QueuePageVH(page);
        }

        @Override
        public void onBindViewHolder(QueuePageVH h, int position) {
            if (position == 0) {
                bindQueuePage(h, dialog, app.player.getQueue());
            } else {
                bindHistoryPage(h, dialog, app.player.getQueueHistory().get(position - 1));
            }
        }

        @Override public int getItemCount() { return 1 + app.player.getQueueHistory().size(); }
    }

    static class QueuePageVH extends RecyclerView.ViewHolder {
        final LinearLayout page;

        QueuePageVH(View itemView) {
            super(itemView);
            page = (LinearLayout) itemView;
        }
    }

    /** 第 0 页：当前队列，待播标记直接显示在歌曲行中。 */
    private void bindQueuePage(QueuePageVH h, Dialog dialog, List<SongInfo> queue) {
        h.page.removeAllViews();
        h.page.setPadding(dp(12), dp(10), dp(10), dp(8));

        // 头部：常态=当前播放·来源…清空·模式；排序态=当前播放·来源…完成
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setText(R.string.music_current_play);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView source = new TextView(this);
        source.setText(sourceNameForQueue());
        source.setTextColor(getColor(R.color.text_hint));
        source.setTextSize(11);
        source.setSingleLine(true);
        source.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sourceLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sourceLp.setMarginStart(dp(8));
        sourceLp.setMarginEnd(dp(8));
        header.addView(source, sourceLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton clearAll = new ImageButton(this);
        clearAll.setBackgroundResource(R.drawable.bg_neu_button_circle);
        clearAll.setImageResource(R.drawable.ic_trash);
        clearAll.setColorFilter(getColor(R.color.text_secondary));
        clearAll.setPadding(dp(7), dp(7), dp(7), dp(7));
        clearAll.setContentDescription(getString(R.string.music_queue_clear));
        clearAll.setElevation(dp(3));
        clearAll.setEnabled(!queue.isEmpty());
        clearAll.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_queue_clear)
                .setMessage(R.string.music_queue_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, which) -> app.player.clearQueue())
                .show());
        actions.addView(clearAll, new LinearLayout.LayoutParams(dp(30), dp(30)));

        ImageButton mode = new ImageButton(this);
        mode.setBackgroundResource(R.drawable.bg_neu_button_circle);
        mode.setImageResource(modeIcon(app.player.getMode()));
        mode.setColorFilter(getColor(R.color.text_secondary));
        mode.setPadding(dp(7), dp(7), dp(7), dp(7));
        mode.setContentDescription(getString(R.string.music_mode));
        mode.setElevation(dp(3));
        mode.setOnClickListener(v -> {
            app.player.cycleMode();
            updateModeButton();
            mode.setImageResource(modeIcon(app.player.getMode()));
            // 与播放器主界面一致：切模式弹文字提示
            GlassToast.makeText(this, modeLabel(app.player.getMode()), GlassToast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        modeLp.setMarginStart(dp(8));
        actions.addView(mode, modeLp);
        header.addView(actions);

        TextView done = new TextView(this);
        done.setText(R.string.music_queue_done);
        done.setTextColor(getColor(R.color.text_primary));
        done.setTextSize(15);
        done.setTypeface(done.getTypeface(), android.graphics.Typeface.BOLD);
        done.setPadding(dp(14), dp(6), dp(2), dp(6));
        done.setOnClickListener(v -> toggleQueueSortMode(false));
        header.addView(done);

        queueHeaderActions = actions;
        queueHeaderDone = done;
        actions.setVisibility(queueSortMode ? View.GONE : View.VISIBLE);
        done.setVisibility(queueSortMode ? View.VISIBLE : View.GONE);
        h.page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        queueAdapter = new QueueAdapter(dialog, queue);
        queueAdapter.setSortMode(queueSortMode);
        list.setAdapter(queueAdapter);
        queueTouchHelper.attachToRecyclerView(list);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        listLp.setMargins(0, dp(10), 0, 0);
        h.page.addView(list, listLp);
    }

    private void bindHistoryPage(QueuePageVH h, Dialog dialog, PlaybackQueue.Snapshot snapshot) {
        h.page.removeAllViews();
        h.page.setPadding(dp(12), dp(10), dp(10), dp(8));
        TextView title = sheetText(getString(R.string.music_queue_history));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        h.page.addView(title);
        TextView source = sheetText(snapshot.sourceName());
        source.setTextSize(11);
        source.setTextColor(getColor(R.color.text_hint));
        h.page.addView(source);
        List<SongInfo> songs = snapshot.songs();
        RecyclerView list = new RecyclerView(this);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(new QueueAdapter(dialog, songs, snapshot));
        h.page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private LinearLayout buildQueueIndicator(int pageCount, int current) {
        LinearLayout indicator = new LinearLayout(this);
        indicator.setGravity(Gravity.CENTER_HORIZONTAL);
        for (int i = 0; i < pageCount; i++) {
            View dot = new View(this);
            dot.setBackgroundResource(i == current
                    ? R.drawable.bg_pager_page_on : R.drawable.bg_pager_page_off);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    dp(i == current ? 18 : 5), dp(3));
            lp.setMargins(dp(3), dp(10), dp(3), 0);
            indicator.addView(dot, lp);
        }
        return indicator;
    }

    /** 播放列表行（概念版）：当前首音频柱高亮，其余两位序号；常态垃圾桶删除，排序态把手拖动。 */
    private final class QueueAdapter extends RecyclerView.Adapter<QueueVH> {
        private final Dialog dialog;
        private final List<SongInfo> items;
        private final PlaybackQueue.Snapshot history;
        private boolean sortMode;

        QueueAdapter(Dialog dialog, List<SongInfo> items) {
            this(dialog, items, null);
        }

        QueueAdapter(Dialog dialog, List<SongInfo> items, PlaybackQueue.Snapshot history) {
            this.dialog = dialog;
            this.items = items;
            this.history = history;
        }

        void setSortMode(boolean on) {
            sortMode = on;
        }

        @Override
        public QueueVH onCreateViewHolder(ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(4), dp(9), dp(4), dp(9));
            row.setBackgroundResource(android.R.color.transparent);
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new QueueVH(row);
        }

        @Override
        public void onBindViewHolder(QueueVH h, int position) {
            SongInfo song = items.get(position);
            // 会话恢复/下载库可能存原始文件名："王菲 - 传奇.mp3" → 标题"传奇"+歌手"王菲"
            LocalSongNames.apply(song, getString(R.string.music_download_downloaded));
            // 队列条目若来自下载扫描/旧会话，缺 VIP 元数据 → 回歌单库与云端收藏补齐（图2 口径）
            if (!song.vipRequired) {
                song.vipRequired = app.playlists.isVipAnywhere(song.hash, song.title, song.artist);
            }
            if (!song.vipRequired && cloudVipKeys != null && !cloudVipKeys.isEmpty()) {
                if (song.hash != null && !song.hash.isEmpty()
                        && cloudVipKeys.contains("h:" + song.hash.toLowerCase(java.util.Locale.ROOT))) {
                    song.vipRequired = true;
                } else {
                    String t = song.title == null ? "" : song.title;
                    String a = song.artist == null ? "" : song.artist;
                    if (cloudVipKeys.contains("t:" + t.toLowerCase(java.util.Locale.ROOT)
                            + "|" + a.toLowerCase(java.util.Locale.ROOT))) {
                        song.vipRequired = true;
                    }
                }
            }
            boolean current = history == null && app.player.getQueueIndex() == position;
            h.pending.setVisibility(history == null && !sortMode && app.player.isPending(position) ? View.VISIBLE : View.GONE);

            // 概念图2：序号为普通数字（2/3/4，不补零）；概念图3：排序态整个序号列消失
            if (sortMode) {
                h.markerText.setVisibility(View.GONE);
                h.markerNote.setVisibility(View.GONE);
            } else {
                h.markerText.setVisibility(current ? View.GONE : View.VISIBLE);
                h.markerText.setText(String.valueOf(position + 1));
                h.markerNote.setVisibility(current ? View.VISIBLE : View.GONE);
            }

            h.title.setText(song.title == null || song.title.isEmpty()
                    ? getString(R.string.music_now_playing) : song.title);
            h.title.setSingleLine(true);
            h.title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            // 对齐酷狗概念版取色：正在播放=天蓝 #28C0F0，普通=中性深灰 #333
            h.title.setTextColor(current ? getColor(R.color.queue_playing_accent)
                    : getColor(R.color.queue_title_dark));
            h.title.setTextSize(14.5f);
            h.title.setTypeface(h.title.getTypeface(), current
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);

            h.vipBadge.setVisibility(song.vipRequired ? View.VISIBLE : View.GONE);

            h.artist.setText(song.artist == null ? "" : song.artist);
            h.artist.setSingleLine(true);
            h.artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
            h.artist.setTextColor(getColor(R.color.queue_artist_gray));
            h.artist.setTextSize(11.5f);

            // 两态行尾：常态=（正在播放行多一个链接图标）+垃圾桶；排序态只留把手（图2/图3）
            h.delete.setVisibility(sortMode || history != null ? View.GONE : View.VISIBLE);
            h.dragHandle.setVisibility(sortMode ? View.VISIBLE : View.GONE);
            h.linkIcon.setVisibility(current && !sortMode ? View.VISIBLE : View.GONE);
            h.linkIcon.setOnClickListener(v -> dialog.dismiss()); // 回到播放页

            h.delete.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                app.player.removeQueueIndex(pos);
                items.remove(pos);
                // 序号需要整体重排，notifyDataSetChanged 而不是 notifyItemRemoved
                notifyDataSetChanged();
            });

            // 把手拖动排序（仅排序态）
            h.dragHandle.setOnTouchListener((view, event) -> {
                if (sortMode
                        && event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN
                        && queueTouchHelper != null) {
                    queueTouchHelper.startDrag(h);
                    return true;
                }
                return false;
            });

            // 长按行进入排序态（图4）；再点右上角完成恢复常态（图3）
            h.itemView.setOnLongClickListener(v -> {
                if (history != null) return false;
                if (!sortMode) {
                    toggleQueueSortMode(true);
                }
                return true;
            });

            h.itemView.setOnClickListener(v -> {
                if (sortMode) return;
                int pos = h.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= items.size()) return;
                SongInfo clicked = items.get(pos);
                if (clicked.vipRequired && !app.auth.isLoggedIn()) {
                    promptVipSync();
                    return;
                }
                app.playlists.addRecentPlay(clicked);
                if (history == null) app.player.playQueueIndex(pos);
                else app.player.playHistory(history, pos);
                dialog.dismiss();
                if (history != null) showQueueSheet();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    static class QueueVH extends RecyclerView.ViewHolder {
        final TextView markerText;
        final ImageView markerNote;
        final TextView title;
        final ImageView pending;
        final TextView vipBadge;
        final TextView artist;
        final ImageView linkIcon;
        final ImageButton delete;
        final ImageView dragHandle;

        QueueVH(View row) {
            super(row);
            LinearLayout content = (LinearLayout) row;

            markerText = new TextView(row.getContext());
            markerText.setGravity(Gravity.CENTER);
            markerText.setTextColor(row.getResources().getColor(R.color.queue_number_gray));
            markerText.setTextSize(12);
            content.addView(markerText, new LinearLayout.LayoutParams(dpOf(26), dpOf(26)));

            markerNote = new ImageView(row.getContext());
            markerNote.setImageResource(R.drawable.ic_audio_bars);
            markerNote.setColorFilter(row.getResources().getColor(R.color.queue_playing_accent));
            markerNote.setVisibility(View.GONE);
            content.addView(markerNote, new LinearLayout.LayoutParams(dpOf(22), dpOf(22)));

            pending = new ImageView(row.getContext());
            pending.setImageResource(R.drawable.ic_play_outline);
            pending.setColorFilter(row.getResources().getColor(R.color.text_hint));
            pending.setContentDescription(row.getContext().getString(R.string.music_play_next));
            content.addView(pending, new LinearLayout.LayoutParams(dpOf(18), dpOf(22)));
            LinearLayout texts = new LinearLayout(row.getContext());
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dpOf(8), 0, 0, 0);
            title = new TextView(row.getContext());
            // 概念图2口径：VIP 徽标紧跟歌名文字（不推到行尾）。标题 wrap_content +
            // maxWidth 封顶：短名徽标贴字，长名先省略再贴徽标，与右侧图标永不相挤
            title.setMaxWidth(titleMaxWidthPx(row.getContext()));
            vipBadge = new TextView(row.getContext());
            vipBadge.setText("VIP");
            vipBadge.setTextColor(row.getResources().getColor(R.color.queue_vip_gold));
            vipBadge.setBackground(row.getResources().getDrawable(R.drawable.bg_vip_badge_gold, null));
            vipBadge.setPadding(dpOf(4), 0, dpOf(4), 0);
            vipBadge.setTextSize(8.5f);
            vipBadge.setTypeface(vipBadge.getTypeface(), android.graphics.Typeface.BOLD);
            vipBadge.setVisibility(View.GONE);
            LinearLayout titleRow = new LinearLayout(row.getContext());
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams vipLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vipLp.setMarginStart(dpOf(6));
            titleRow.addView(vipBadge, vipLp);
            // 弹性占位：把行尾空间留给右侧图标列
            View titleSpring = new View(row.getContext());
            titleRow.addView(titleSpring, new LinearLayout.LayoutParams(0, 1, 1f));
            artist = new TextView(row.getContext());
            artist.setPadding(0, dpOf(2), 0, 0);
            texts.addView(titleRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            texts.addView(artist, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            content.addView(texts, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            // 概念版口径：正在播放行尾部有「链接」小图标（回到播放页），垃圾桶之前
            linkIcon = new ImageView(row.getContext());
            linkIcon.setImageResource(R.drawable.ic_link_chain);
            linkIcon.setColorFilter(row.getResources().getColor(R.color.queue_delete_gray));
            linkIcon.setPadding(dpOf(3), dpOf(3), dpOf(3), dpOf(3));
            linkIcon.setVisibility(View.GONE);
            LinearLayout.LayoutParams linkLp = new LinearLayout.LayoutParams(dpOf(18), dpOf(18));
            linkLp.setMarginStart(dpOf(2));
            content.addView(linkIcon, linkLp);

            delete = new ImageButton(row.getContext());
            delete.setBackgroundResource(android.R.color.transparent);
            delete.setImageResource(R.drawable.ic_trash);
            // 概念版口径：浅灰小垃圾桶——20dp 触达 + 4dp 内缩 ≈ 12dp 视觉图标
            delete.setColorFilter(row.getResources().getColor(R.color.queue_delete_gray));
            delete.setPadding(dpOf(4), dpOf(4), dpOf(4), dpOf(4));
            LinearLayout.LayoutParams deleteLp = new LinearLayout.LayoutParams(dpOf(20), dpOf(20));
            deleteLp.setMarginStart(dpOf(4));
            content.addView(delete, deleteLp);

            // 拖动排序把手：仅排序态可见，按住上下拖即换序
            dragHandle = new ImageView(row.getContext());
            dragHandle.setImageResource(R.drawable.ic_drag_handle);
            dragHandle.setColorFilter(row.getResources().getColor(R.color.text_hint));
            dragHandle.setPadding(dpOf(4), dpOf(4), dpOf(2), dpOf(4));
            content.addView(dragHandle, new LinearLayout.LayoutParams(dpOf(24), dpOf(26)));
        }

        private static int dpOf(int value) {
            return Math.round(value * android.content.res.Resources.getSystem()
                    .getDisplayMetrics().density);
        }

        /** 标题列宽度上限：屏宽 - 序号/徽标/删除/内边距的固定预留，超出即省略。 */
        private static int titleMaxWidthPx(android.content.Context ctx) {
            int screen = ctx.getResources().getDisplayMetrics().widthPixels;
            int reserve = dpOf(24)   // 序号/均衡器列
                    + dpOf(8)        // 文本块左内边距
                    + dpOf(6)        // 徽标与标题间距
                    + dpOf(30)       // VIP 徽标宽度上限
                    + dpOf(4)        // 垃圾桶左间距
                    + dpOf(20)       // 垃圾桶
                    + dpOf(16);      // 行与页左右内边距合计
            return Math.max(dpOf(80), screen - reserve);
        }
    }

    /** 把手上下拖拽只调整当前队列，松手刷新序号与当前歌曲标记。 */
    private final class QueueDragCallback extends ItemTouchHelper.SimpleCallback {
        QueueDragCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            // 长按的语义是"进入排序态"；拖动统一由把手发起，避免两个手势打架
            return false;
        }

        @Override
        public boolean onMove(RecyclerView recyclerView,
                RecyclerView.ViewHolder viewHolder, RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) {
                return false;
            }
            app.player.reorderQueue(from, to);
            // 弹窗里的列表是快照副本，必须同步搬移，否则松手后行内容与实际队列错位
            if (queueAdapter != null) {
                SongInfo moved = queueAdapter.items.remove(from);
                queueAdapter.items.add(to, moved);
                queueAdapter.notifyItemMoved(from, to);
            }
            dragMoved = true;
            return true;
        }

        @Override
        public void onSwiped(RecyclerView.ViewHolder viewHolder, int direction) {
        }

        @Override
        public void clearView(RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            // 拖拽结束后整体刷新，让当前首标记与序号对齐
            if (queueAdapter != null) {
                queueAdapter.notifyDataSetChanged();
            }
            if (dragMoved) {
                dragMoved = false;
                GlassToast.makeText(MusicPlayerActivity.this,
                        R.string.music_queue_order_adjusted, GlassToast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean dragMoved;

    private void shareCurrentSong() {
        SongInfo song = app.player.currentSong();
        if (song == null) {
            GlassToast.makeText(this, R.string.music_queue_empty, GlassToast.LENGTH_SHORT).show();
            return;
        }
        android.content.Intent send = new android.content.Intent(android.content.Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(android.content.Intent.EXTRA_TEXT, song.title + " - " + song.artist);
        startActivity(android.content.Intent.createChooser(send, getString(R.string.music_share)));
    }

    private void toggleLike() {
        SongInfo song = app.player.currentSong();
        if (song == null) {
            GlassToast.makeText(this, R.string.music_queue_empty, GlassToast.LENGTH_SHORT).show();
            return;
        }
        final boolean like;
        if (app.playlists.hasSong("Favorites", song)) {
            app.playlists.removeSong("Favorites", song);
            like = false;
        } else {
            app.playlists.addSong("Favorites", song);
            like = true;
        }
        updateLikeState();
        // 爱心同步：把当前歌加进/移出酷狗云「我喜欢」歌单（未登录仅本地收藏）
        if (!app.auth.isLoggedIn()) {
            GlassToast.makeText(this, like ? R.string.music_like_toast_added
                    : R.string.music_like_toast_removed, GlassToast.LENGTH_SHORT).show();
            return;
        }
        final SongInfo syncSong = song;
        Executors.newSingleThreadExecutor().execute(() -> {
            int toastRes;
            try {
                app.refreshDataSourceAuth();
                RemotePlaylist favorites = app.dataSource.getFavoriteCloudPlaylist();
                if (favorites != null) {
                    List<SongInfo> one = java.util.Collections.singletonList(syncSong);
                    if (like) {
                        app.dataSource.addSongsToUserPlaylist(favorites, one);
                    } else {
                        app.dataSource.deleteSongsFromUserPlaylist(favorites, one);
                    }
                    toastRes = like ? R.string.music_like_synced
                            : R.string.music_like_sync_removed;
                } else {
                    toastRes = like ? R.string.music_like_toast_added
                            : R.string.music_like_toast_removed;
                }
            } catch (Exception exception) {
                android.util.Log.w("MusicPlayer", "like cloud sync failed", exception);
                toastRes = like ? R.string.music_like_sync_failed
                        : R.string.music_like_toast_removed;
            }
            final int finalToastRes = toastRes;
            ui.post(() -> GlassToast.makeText(this, finalToastRes, GlassToast.LENGTH_SHORT).show());
        });
    }

    /** 收藏态：爱心点亮为实心（=已加入「我喜欢」歌单）；书签固定为添加到歌单入口。 */
    private void updateLikeState() {
        SongInfo song = app.player.currentSong();
        boolean liked = song != null && app.playlists.hasSong("Favorites", song);
        ImageButton like = findViewById(R.id.player_like_button);
        if (like != null) {
            like.setImageResource(liked ? R.drawable.ic_player_like_filled
                    : R.drawable.ic_player_like);
            like.setColorFilter(getColor(liked ? R.color.brand_primary : R.color.text_primary));
        }
    }

    private void downloadCurrentSong() {
        SongInfo song = app.player.currentSong();
        if (song == null || song.hash == null) {
            GlassToast.makeText(this, R.string.music_download_no_song, GlassToast.LENGTH_SHORT).show();
            return;
        }
        if (app.downloads.exists(song.hash)) {
            GlassToast.makeText(this, R.string.music_download_already_exists, GlassToast.LENGTH_SHORT).show();
            return;
        }
        GlassToast.makeText(this, getString(R.string.music_download_single_started, song.title),
                GlassToast.LENGTH_SHORT).show();
        app.downloads.enqueue(song, DownloadManager.QUALITY_STANDARD, (s, state, done, total, msg) -> {
            if (state == DownloadManager.State.COMPLETED) {
                GlassToast.makeText(this,
                        getString(R.string.music_download_single_done_with_path,
                                s.title, app.downloads.downloadsDir().getAbsolutePath()),
                        GlassToast.LENGTH_LONG).show();
            } else if (state == DownloadManager.State.FAILED) {
                GlassToast.makeText(this,
                        getString(R.string.music_download_single_failed, s.title,
                                msg == null ? "" : msg),
                        GlassToast.LENGTH_LONG).show();
            }
        });
    }

    /** 添加到歌单弹层（播放页右上角书签）：本地歌单即时加入；酷狗云歌单（含默认收藏）异步加入。 */
    private void showAddToPlaylistDialog(SongInfo song) {
        if (song == null) {
            GlassToast.makeText(this, R.string.music_queue_empty, GlassToast.LENGTH_SHORT).show();
            return;
        }
        Dialog dialog = bottomDialog();
        LinearLayout root = sheetRoot();
        root.setPadding(dp(20), dp(18), dp(20), dp(22));

        TextView title = new TextView(this);
        title.setText(R.string.music_add_to_playlist);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 本地歌单：我喜欢置前；最近播放由系统维护，不作为收藏目标
        list.addView(sectionLabel(R.string.music_playlists));
        List<String> names = app.playlists.listPlaylists();
        names.remove("Recently Played");
        names.remove("Favorites");
        list.addView(playlistRow(displayPlaylistName("Favorites"),
                getString(R.string.music_playlist_song_count, app.playlists.songCount("Favorites")),
                v -> addToLocalPlaylist(dialog, "Favorites", song)));
        for (String name : names) {
            list.addView(playlistRow(displayPlaylistName(name),
                    getString(R.string.music_playlist_song_count, app.playlists.songCount(name)),
                    v -> addToLocalPlaylist(dialog, name, song)));
        }

        // 酷狗云歌单：登录后异步加载（默认收藏/自建歌单），点选即云同步
        list.addView(sectionLabel(R.string.music_remote_playlists));
        if (!app.auth.isLoggedIn()) {
            list.addView(statusRow(getString(R.string.music_remote_login_prompt)));
        } else {
            final TextView loading = statusRow(getString(R.string.music_remote_loading));
            list.addView(loading);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    app.refreshDataSourceAuth();
                    List<RemotePlaylist> remote = app.dataSource.getAllUserPlaylists(30);
                    ui.post(() -> {
                        loading.setVisibility(View.GONE);
                        for (RemotePlaylist playlist : remote) {
                            list.addView(playlistRow(playlist.name,
                                    getString(R.string.music_playlist_song_count, playlist.songCount),
                                    v -> addToCloudPlaylist(dialog, playlist, song)));
                        }
                        capSheetScroll(scroll, list);
                    });
                } catch (Exception exception) {
                    ui.post(() -> {
                        loading.setVisibility(View.GONE);
                        list.addView(statusRow(getString(R.string.music_remote_load_failed)));
                        capSheetScroll(scroll, list);
                    });
                }
            });
        }
        capSheetScroll(scroll, list);
        showBottomDialog(dialog, root);
    }

    private TextView sectionLabel(int textRes) {
        TextView label = new TextView(this);
        label.setText(textRes);
        label.setTextColor(getColor(R.color.text_hint));
        label.setTextSize(11.5f);
        label.setPadding(0, dp(14), 0, dp(2));
        return label;
    }

    private TextView statusRow(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.text_hint));
        view.setTextSize(13);
        view.setPadding(0, dp(12), 0, dp(12));
        return view;
    }

    private LinearLayout playlistRow(CharSequence name, CharSequence count,
            View.OnClickListener click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setClickable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);

        TextView nameView = new TextView(this);
        nameView.setText(name);
        nameView.setTextSize(15);
        nameView.setTextColor(getColor(R.color.text_primary));
        nameView.setSingleLine(true);
        nameView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(nameView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView countView = new TextView(this);
        countView.setText(count);
        countView.setTextSize(11.5f);
        countView.setTextColor(getColor(R.color.text_hint));
        row.addView(countView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(click);
        return row;
    }

    private void addToLocalPlaylist(Dialog dialog, String name, SongInfo song) {
        app.playlists.addSong(name, song);
        dialog.dismiss();
        if ("Favorites".equals(name)) updateLikeState();
        GlassToast.makeText(this, getString(R.string.music_added_to_playlist,
                displayPlaylistName(name)), GlassToast.LENGTH_SHORT).show();
    }

    private void addToCloudPlaylist(Dialog dialog, RemotePlaylist playlist, SongInfo song) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.refreshDataSourceAuth();
                app.dataSource.addSongsToUserPlaylist(playlist,
                        java.util.Collections.singletonList(song));
                ui.post(() -> {
                    dialog.dismiss();
                    GlassToast.makeText(this, getString(R.string.music_added_to_playlist,
                            playlist.name), GlassToast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                android.util.Log.w("MusicPlayer", "cloud playlist add failed", exception);
                ui.post(() -> GlassToast.makeText(this, R.string.music_cloud_add_failed,
                        GlassToast.LENGTH_SHORT).show());
            }
        });
    }

    /** 列表超过 400dp 时把滚动区压到上限，弹层不顶出屏幕；内容少时保持自适应高度。 */
    private void capSheetScroll(android.widget.ScrollView scroll, LinearLayout list) {
        scroll.post(() -> {
            int cap = dp(400);
            if (list.getHeight() > cap) {
                scroll.getLayoutParams().height = cap;
                scroll.requestLayout();
            }
        });
    }

    /** 歌词设置：字号/颜色（歌词页右下角浮钮）。 */
    private void showLyricsSettings() {
        if (lyricsSettingsDialog != null) lyricsSettingsDialog.dismiss();
        Dialog dialog = bottomDialog();
        lyricsSettingsDialog = dialog;
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
        addThemeButton(colors, LyricsView.Theme.DARK, R.string.music_lyrics_theme_dark, Color.parseColor("#15201D"));
        addThemeButton(colors, LyricsView.Theme.TEAL, R.string.music_lyrics_theme_teal, Color.parseColor("#14B8A6"));
        addThemeButton(colors, LyricsView.Theme.BLUE, R.string.music_lyrics_theme_blue, Color.parseColor("#3B82F6"));
        addThemeButton(colors, LyricsView.Theme.AMBER, R.string.music_lyrics_theme_amber, Color.parseColor("#F59E0B"));
        addThemeButton(colors, LyricsView.Theme.WHITE, R.string.music_lyrics_theme_white, Color.parseColor("#FFFFFF"));
        root.addView(colors);
        showBottomDialog(dialog, root);
    }

    /** 更多面板（封面页 ⋯）：歌名/歌手 + 两行五列操作网格（概念版样式）。 */
    private void showPlayerSettings() {
        Dialog dialog = bottomDialog();
        LinearLayout root = sheetRoot();
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(10), dp(26), dp(10), dp(30));

        SongInfo song = app.player.currentSong();
        TextView title = new TextView(this);
        title.setText(song == null || song.title == null ? "" : song.title);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(17);
        title.setMaxLines(2);
        title.setGravity(Gravity.CENTER);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView artist = new TextView(this);
        artist.setText(song == null || song.artist == null ? "" : song.artist);
        artist.setTextColor(getColor(R.color.text_hint));
        artist.setTextSize(12);
        artist.setPadding(0, dp(4), 0, 0);
        root.addView(artist, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row1 = new LinearLayout(this);
        row1.setPadding(0, dp(28), 0, 0);
        row1.addView(moreGridItem(R.drawable.ic_more_download_filled,
                getString(R.string.music_more_download), null, () -> {
                    dialog.dismiss();
                    downloadCurrentSong();
                }));
        row1.addView(moreGridItem(R.drawable.ic_more_sound_effect_filled,
                getString(R.string.music_more_sound_effect), null, () -> {
                    dialog.dismiss();
                    toggleViperEffect();
                }));
        row1.addView(moreGridItem(R.drawable.ic_more_speed_filled,
                getString(R.string.music_more_speed), null, () -> {
                    dialog.dismiss();
                    showSpeedSheet();
                }));
        row1.addView(moreGridItem(R.drawable.ic_more_song_info_filled,
                getString(R.string.music_more_song_info), null, () -> {
                    dialog.dismiss();
                    showSongInfo();
                }));
        row1.addView(moreGridItem(R.drawable.ic_more_add_song_filled,
                getString(R.string.music_more_add_to_playlist), null, () -> {
                    dialog.dismiss();
                    showAddToPlaylistDialog(app.player.currentSong());
                }));
        root.addView(row1, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row2 = new LinearLayout(this);
        row2.setPadding(0, dp(24), 0, 0);
        row2.addView(moreGridItem(R.drawable.ic_more_feedback_filled,
                getString(R.string.music_more_feedback), null, () -> {
                    dialog.dismiss();
                    GlassToast.makeText(this, R.string.music_more_feedback_todo,
                            GlassToast.LENGTH_SHORT).show();
                }));
        row2.addView(moreGridItem(R.drawable.ic_more_headphones_filled,
                getString(R.string.music_more_quality),
                getString(qualityNameRes(currentQuality())), () -> {
                    dialog.dismiss();
                    showQualitySheet();
                }));
        row2.addView(moreGridItem(R.drawable.ic_more_bell_filled,
                getString(R.string.music_more_ringtone), null, () -> {
                    dialog.dismiss();
                    setRingtoneCurrentSong();
                }));
        row2.addView(moreGridItem(R.drawable.ic_more_timer_filled,
                getString(R.string.music_sleep_timer), null, () -> {
                    dialog.dismiss();
                    showSleepTimerSheet();
                }));
        row2.addView(moreGridItem(R.drawable.ic_more_save_cover_filled,
                getString(R.string.music_more_save_cover), null, () -> {
                    dialog.dismiss();
                    saveCurrentCover();
                }));
        root.addView(row2, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        showBottomDialog(dialog, root);
    }

    /** 网格单元：软浮起方块图标 + 下方文字标签（可带灰色副标题），五列等宽铺满一行。 */
    private LinearLayout moreGridItem(int iconRes, CharSequence label,
            CharSequence subtitle, Runnable action) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClickable(true);
        col.setFocusable(true);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        col.setBackgroundResource(ripple.resourceId);
        col.setOnClickListener(v -> action.run());
        // 等分一行宽度，列间距随屏宽自然拉开，不再挤成一坨
        col.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton icon = new ImageButton(this);
        icon.setBackgroundResource(R.drawable.bg_neu_button_raised);
        icon.setImageResource(iconRes);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        icon.setPadding(dp(16), dp(16), dp(16), dp(16));
        icon.setElevation(dp(5));
        icon.setClickable(false);
        col.addView(icon, new LinearLayout.LayoutParams(dp(58), dp(58)));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(getColor(R.color.text_primary));
        labelView.setTextSize(11.5f);
        labelView.setSingleLine(true);
        labelView.setPadding(0, dp(8), 0, 0);
        labelView.setClickable(false);
        col.addView(labelView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (subtitle != null) {
            TextView subView = new TextView(this);
            subView.setText(subtitle);
            subView.setTextColor(getColor(R.color.text_hint));
            subView.setTextSize(10.5f);
            subView.setSingleLine(true);
            subView.setPadding(0, dp(2), 0, 0);
            subView.setClickable(false);
            col.addView(subView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return col;
    }

    /** 蝰蛇音效开关（状态持久化，暂作为音效入口占位）。 */
    private void toggleViperEffect() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean on = !prefs.getBoolean(KEY_VIPER_EFFECT, false);
        prefs.edit().putBoolean(KEY_VIPER_EFFECT, on).apply();
        GlassToast.makeText(this, on ? R.string.music_more_sound_effect_on
                : R.string.music_more_sound_effect_off, GlassToast.LENGTH_SHORT).show();
    }

    /** 歌曲信息：歌名/歌手/时长/当前音质。 */
    private void showSongInfo() {
        SongInfo song = app.player.currentSong();
        if (song == null) {
            GlassToast.makeText(this, R.string.music_queue_empty, GlassToast.LENGTH_SHORT).show();
            return;
        }
        String message = getString(R.string.music_song_info_format,
                song.title, song.artist, song.durationFormatted(),
                getString(qualityNameRes(currentQuality())));
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_more_song_info)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 音质选择弹层：标准/高清/无损，当前项高亮，选择即记忆。 */
    private void showQualitySheet() {
        Dialog dialog = bottomDialog();
        LinearLayout root = sheetRoot();
        root.setPadding(dp(20), dp(18), dp(20), dp(20));

        TextView title = new TextView(this);
        title.setText(R.string.music_more_quality);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTextSize(16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String[] tokens = {DownloadManager.QUALITY_STANDARD,
                DownloadManager.QUALITY_HIGH, DownloadManager.QUALITY_LOSSLESS};
        String current = currentQuality();
        for (String token : tokens) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(10), 0, dp(10));
            row.setClickable(true);
            android.util.TypedValue ripple = new android.util.TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);

            TextView name = new TextView(this);
            name.setText(qualityNameRes(token));
            name.setTextSize(15);
            name.setTextColor(getColor(token.equals(current)
                    ? R.color.brand_primary : R.color.text_primary));
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView hint = new TextView(this);
            hint.setText(qualityHintRes(token));
            hint.setTextSize(11.5f);
            hint.setTextColor(getColor(R.color.text_hint));
            row.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setOnClickListener(v -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString(KEY_PLAY_QUALITY, token).apply();
                dialog.dismiss();
                GlassToast.makeText(this, getString(R.string.music_quality_set,
                        getString(qualityNameRes(token))), GlassToast.LENGTH_SHORT).show();
            });
            root.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        showBottomDialog(dialog, root);
    }

    /** 设为铃声：仅对已下载歌曲生效，写入系统铃声前检查修改系统设置权限。 */
    private void setRingtoneCurrentSong() {
        SongInfo song = app.player.currentSong();
        if (song == null || song.hash == null) {
            GlassToast.makeText(this, R.string.music_queue_empty, GlassToast.LENGTH_SHORT).show();
            return;
        }
        DownloadedSong downloaded = app.downloads.get(song.hash);
        if (downloaded == null || downloaded.localPath == null
                || !new java.io.File(downloaded.localPath).exists()) {
            GlassToast.makeText(this, R.string.music_ringtone_need_download,
                    GlassToast.LENGTH_SHORT).show();
            return;
        }
        if (!android.provider.Settings.System.canWrite(this)) {
            GlassToast.makeText(this, R.string.music_ringtone_need_permission,
                    GlassToast.LENGTH_SHORT).show();
            return;
        }
        android.media.MediaScannerConnection.scanFile(this,
                new String[]{downloaded.localPath}, new String[]{"audio/mpeg"},
                (path, uri) -> ui.post(() -> {
                    if (uri == null) {
                        GlassToast.makeText(this, R.string.music_ringtone_failed,
                                GlassToast.LENGTH_SHORT).show();
                        return;
                    }
                    try {
                        android.media.RingtoneManager.setActualDefaultRingtoneUri(this,
                                android.media.RingtoneManager.TYPE_RINGTONE, uri);
                        GlassToast.makeText(this, R.string.music_ringtone_done,
                                GlassToast.LENGTH_SHORT).show();
                    } catch (Exception exception) {
                        GlassToast.makeText(this, R.string.music_ringtone_failed,
                                GlassToast.LENGTH_SHORT).show();
                    }
                }));
    }

    /** 保存图片：抓取当前歌曲封面写入系统相册 Pictures/CleanRecovery。 */
    private void saveCurrentCover() {
        SongInfo song = app.player.currentSong();
        if (song == null || song.imgUrl == null || song.imgUrl.isEmpty()) {
            GlassToast.makeText(this, R.string.music_save_cover_failed, GlassToast.LENGTH_SHORT).show();
            return;
        }
        String url = song.imgUrl;
        Executors.newSingleThreadExecutor().execute(() -> {
            boolean saved = false;
            try (InputStream input = new URL(url).openStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                if (bitmap != null) {
                    saved = persistCover(bitmap, song);
                }
            } catch (Exception ignored) {
            }
            boolean finalSaved = saved;
            ui.post(() -> GlassToast.makeText(this, finalSaved
                    ? R.string.music_save_cover_done : R.string.music_save_cover_failed,
                    GlassToast.LENGTH_SHORT).show());
        });
    }

    private boolean persistCover(Bitmap bitmap, SongInfo song) {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                (song.title == null || song.title.isEmpty() ? "cover" : song.title) + ".jpg");
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/CleanRecovery");
        }
        try {
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return false;
            try (java.io.OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) return false;
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out);
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    private String currentQuality() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(KEY_PLAY_QUALITY, DownloadManager.QUALITY_STANDARD);
    }

    private int qualityNameRes(String token) {
        if (DownloadManager.QUALITY_HIGH.equals(token)) return R.string.music_quality_high;
        if (DownloadManager.QUALITY_LOSSLESS.equals(token)) return R.string.music_quality_lossless;
        return R.string.music_quality_standard;
    }

    private int qualityHintRes(String token) {
        if (DownloadManager.QUALITY_HIGH.equals(token)) return R.string.music_quality_hint_high;
        if (DownloadManager.QUALITY_LOSSLESS.equals(token)) return R.string.music_quality_hint_lossless;
        return R.string.music_quality_hint_standard;
    }

    private void addThemeButton(LinearLayout row, LyricsView.Theme theme, int labelRes, int color) {
        Button button = new Button(this);
        button.setText(labelRes);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setMinHeight(0);
        button.setBackgroundTintList(ColorStateList.valueOf(
                lyricsView.getTheme() == theme ? color : getColor(R.color.brand_accent_soft)));
        button.setTextColor(theme == LyricsView.Theme.DARK && lyricsView.getTheme() == theme
                ? Color.WHITE
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
        if (song.hash == null || song.hash.isEmpty()) {
            lyricsRequestId++;
            requestedLyricsHash = null;
            loadedLyricsHash = null;
            currentLyrics = Lyrics.empty();
            climaxStartMs = -1;
            seekBar.setClimax(-1, 0);
            renderLyrics(currentLyrics);
            return;
        }
        if (song.hash.equals(requestedLyricsHash)) return;
        requestedLyricsHash = song.hash;
        loadedLyricsHash = null;
        int requestId = ++lyricsRequestId;
        climaxStartMs = -1;
        seekBar.setClimax(-1, 0);
        currentLyrics = Lyrics.empty();
        renderLyrics(currentLyrics);
        final SongInfo loadingSong = song;
        lyricsExecutor.execute(() -> {
            Lyrics result;
            try {
                result = app.dataSource.getLyrics(loadingSong);
            } catch (Exception exception) {
                result = Lyrics.empty();
            }
            Lyrics finalResult = result;
            ui.post(() -> {
                if (isDestroyed() || requestId != lyricsRequestId) return;
                loadedLyricsHash = loadingSong.hash;
                currentLyrics = finalResult;
                renderLyrics(finalResult);
                lyricsView.updatePosition(app.player.getCurrentPosition());
                updateLyricSummary(app.player.getCurrentPosition());
            });
        });
        lyricsExecutor.execute(() -> {
            long start = -1;
            try {
                start = app.dataSource.getClimaxStartMs(loadingSong);
            } catch (Exception exception) {
                android.util.Log.w("MusicPlayerActivity", "Climax metadata unavailable", exception);
            }
            final long result = start;
            ui.post(() -> {
                if (isDestroyed() || requestId != lyricsRequestId) return;
                climaxStartMs = result;
                seekBar.setClimax(result, app.player.getDuration());
            });
        });
    }

    private void renderLyrics(Lyrics lyrics) {
        lyricsView.setLyrics(lyrics);
        TextView translate = findViewById(R.id.player_lyrics_translate);
        boolean hasTranslation = false;
        if (lyrics != null) {
            for (Lyrics.Line line : lyrics.lines()) {
                if (!line.translation.isEmpty()) { hasTranslation = true; break; }
            }
        }
        translate.setEnabled(hasTranslation);
        if (!hasTranslation) {
            translate.setSelected(false);
            translate.setTextColor(getColor(R.color.text_hint));
            lyricsView.setTranslationVisible(false);
        }
        if (lyrics == null || lyrics.isEmpty()) {
            lyricsView.setVisibility(View.GONE);
            lyricsEmpty.setText(R.string.music_lyrics_empty);
            lyricsEmpty.setVisibility(View.VISIBLE);
        } else {
            lyricsEmpty.setVisibility(View.GONE);
            lyricsView.setVisibility(View.VISIBLE);
        }
    }

    /** 封面模式下的当前行/下一行歌词摘要（概念版样式）。 */
    private void updateLyricSummary(long positionMs) {
        if (currentLyricText == null || nextLyricText == null) {
            return;
        }
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
            // 本地文件来源的会话恢复条目可能是原始文件名——顶部一律展示清洗后的标题
            String displayTitle = LocalSongNames.displayTitle(song.title);
            String displayArtist = song.artist == null || song.artist.trim().isEmpty()
                    ? LocalSongNames.displayArtist(song.title, "") : song.artist;
            songTitle.setText(displayTitle);
            songArtist.setText(displayArtist + (song.vipRequired ? " · VIP" : ""));
            totalTimeText.setText(song.durationFormatted());
            coverText.setText(displayTitle.isEmpty()
                    ? "♪" : displayTitle.substring(0, 1).toUpperCase());
            loadCover(song);
            maybeLoadLyrics();
        } else {
            songTitle.setText(R.string.music_queue_empty);
            songArtist.setText("");
            coverText.setText("♪");
            loadCover(null);
            lyricsRequestId++;
            requestedLyricsHash = null;
            loadedLyricsHash = null;
            currentLyrics = Lyrics.empty();
            climaxStartMs = -1;
            renderLyrics(currentLyrics);
            seekBar.setClimax(-1, 0);
            seekBar.setProgress(0);
            currentTimeText.setText(formatMs(0));
            totalTimeText.setText(formatMs(0));
        }
        updatePlayButton(app.player.getState());
        updateStatusText(app.player.getState());
        updateModeButton();
        updateLikeState();
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
            playButton.setImageResource(R.drawable.ic_play_solid);
            playButton.setAlpha(0.45f);
            playButton.setEnabled(false);
        } else {
            playButton.setImageResource(state == MusicPlayer.State.PLAYING
                    ? R.drawable.ic_pause_solid : R.drawable.ic_play_solid);
            playButton.setAlpha(1f);
            playButton.setEnabled(true);
        }
        playButton.setColorFilter(getColor(R.color.text_primary));
        updateDiscRotation(state);
    }

    /** 播放中旋转唱盘，暂停/空闲时停在当前角度（概念版黑胶行为）。 */
    private void updateDiscRotation(MusicPlayer.State state) {
        if (discRotate == null) return;
        if (state == MusicPlayer.State.PLAYING) {
            if (discRotate.isStarted()) {
                discRotate.resume();
            } else {
                discRotate.start();
            }
        } else if (discRotate.isStarted()) {
            discRotate.pause();
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
                ? getColor(R.color.text_primary)
                : getColor(R.color.text_secondary));
    }

    private int modeIcon(MusicPlayer.Mode mode) {
        switch (mode) {
            case REPEAT_ONE: return R.drawable.ic_mode_repeat_one;
            case SHUFFLE: return R.drawable.ic_mode_shuffle;
            case SEQUENTIAL:
            default: return R.drawable.ic_mode_sequential;
        }
    }

    private String modeLabel(MusicPlayer.Mode mode) {
        switch (mode) {
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

    /**
     * 底部弹窗：窗口属性（透明背景/BOTTOM/尺寸/上滑动画）必须在 show() 之前设置——
     * 窗口一旦加进 WindowManager，入场动画就按当时的属性开跑，onShow 里再改就晚了，
     * 会出现“从中间/顶部冒出来”的错误观感。
     */
    private Dialog bottomDialog() {
        // 非浮动透明主题：fill×wrap 窗口宽度才能生效（见 MusicBottomSheetDialogTheme 注释）
        Dialog dialog = new Dialog(this, R.style.MusicBottomSheetDialogTheme);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(R.style.MusicBottomSheetAnimation);
        }
        return dialog;
    }

    private LinearLayout sheetRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(20));
        root.setBackgroundResource(R.drawable.bg_queue_sheet);
        return root;
    }

    /** 队列面板专用根容器：全宽贴底样式（中性灰白、仅顶部圆角，对齐酷狗概念版）。 */
    private LinearLayout queueSheetRoot() {
        LinearLayout root = sheetRoot();
        root.setBackgroundResource(R.drawable.bg_queue_sheet_flush);
        return root;
    }

    /**
     * 弹层内容统一包一层带边距的透明容器，让圆角浮卡四周留边悬浮（概念版样式），
     * 而不是通栏贴底。
     */
    private void showBottomDialog(Dialog dialog, View card) {
        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
        // 左右仅留 6dp，弹层几乎占满屏宽，网格才铺得开
        wrap.setPadding(dp(6), 0, dp(6), dp(8));
        wrap.addView(card, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        if (dialog == lyricsSettingsDialog) {
            wrap.setOnClickListener(v -> dialog.dismiss());
            card.setClickable(true);
        }
        dialog.setContentView(wrap);
        dialog.show();
        if (dialog == lyricsSettingsDialog && dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
        }
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
        ui.removeCallbacks(wordProgress);
        lyricsView.removeCallbacks(wordProgress);
        if (state == MusicPlayer.State.PLAYING) ui.post(wordProgress);
        updatePlayButton(state);
        updateStatusText(state);
        updateModeButton();
    }

    @Override
    public void onProgressChanged(int cur, int total) {
        seekBar.setClimax(climaxStartMs, total);
        if (!seekTracking) {
            seekBar.setProgress(total > 0 ? cur * 1000 / total : 0);
            currentTimeText.setText(formatMs(cur));
        }
        totalTimeText.setText(formatMs(total));
        lyricsView.updatePosition(cur);
        updateLyricSummary(cur);
    }

    @Override
    public void onSongChanged(SongInfo song) {
        updateUI();
        // 队列面板开着时同步各页「正在播放」高亮（自动切歌/跳歌场景）
        if (queueDialog != null && queueDialog.isShowing() && queuePagerAdapter != null) {
            ui.post(() -> {
                if (queueDialog == null || !queueDialog.isShowing()) return;
                queuePagerAdapter.notifyDataSetChanged();
                int count = queuePagerAdapter.getItemCount();
                if (queuePagerIndicator != null && queuePagerIndicator.getChildCount() != count) {
                    ViewGroup parent = (ViewGroup) queuePagerIndicator.getParent();
                    parent.removeView(queuePagerIndicator);
                    queuePagerIndicator = buildQueueIndicator(count, 0);
                    parent.addView(queuePagerIndicator, 0);
                }
            });
        }
    }

    @Override
    public void onError(String msg) {
        // 只提示友好文案，原始错误码（如 -38 / 服务端错误串）仅留在日志里；
        // MusicPlayer 已做 4 秒去重，主页/歌单页同时在线也只会弹这一条。
        android.util.Log.w("MusicPlayerActivity", "playback error: " + msg);
        showPlaybackErrorToast(this, msg);
    }

    /** 统一错误提示：解析出真实原因（VIP 权益/版权）时给针对性文案，其余用通用文案。 */
    public static void showPlaybackErrorToast(android.content.Context ctx, String rawMsg) {
        int res = R.string.music_playback_failed;
        if (rawMsg != null) {
            String lower = rawMsg.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("vip") || rawMsg.contains("会员")
                    || rawMsg.contains("权益") || rawMsg.contains("版权")) {
                res = R.string.music_vip_play_failed;
            }
        }
        GlassToast.makeText(ctx, res, GlassToast.LENGTH_SHORT).show();
    }

    private static String formatMs(int ms) {
        int s = Math.max(0, ms / 1000);
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private String displayPlaylistName(String name) {
        if ("Listen Later".equals(name)) return getString(R.string.music_playlist_listen_later);
        if ("Recently Played".equals(name)) return getString(R.string.music_recent);
        if ("Favorites".equals(name)) return getString(R.string.music_playlist_favorites);
        return name;
    }
}
