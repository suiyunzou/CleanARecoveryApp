package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.DownloadedSong;
import com.example.cleanrecovery.music.data.RemotePlaylist;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.player.MusicPlayer;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public final class MusicHomeActivity extends Activity implements MusicPlayer.Callback {

    private MusicApp app;
    private ImageButton loginButton;
    private RecyclerView recList;
    private LinearLayout playlistContainer;
    private LinearLayout remotePlaylistContainer;
    private View miniPlayer;
    private TextView miniTitle, miniIcon, miniArtist;
    private ImageView miniCover, miniPlayIcon;
    private ImageButton miniPrev, miniNext;
    private View miniCenter;
    private View miniVinyl;
    private View miniTonearm;
    private View miniSourcePill;
    private final MiniTurntable turntable = new MiniTurntable();
    // 保存当前推荐歌曲列表，用于点击时构建播放队列
    private List<SongInfo> recommendationSongs = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_music_home);

        app = MusicApp.init(this);
        app.player.addCallback(this);

        bindViews();
        loadRecommendations();
        loadPlaylists();
        // 云歌单加载由 onResume 触发，避免 onCreate+onResume 连发两次网络请求
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLoginState();
        loadPlaylists();
        loadRemotePlaylists();
        updateMiniPlayer();
    }

    @Override
    protected void onDestroy() {
        app.player.removeCallback(this);
        super.onDestroy();
    }

    private void bindViews() {
        miniPlayer = findViewById(R.id.mini_player_stub);
        loginButton = findViewById(R.id.music_home_login_button);
        recList = findViewById(R.id.music_home_rec_list);
        playlistContainer = findViewById(R.id.music_playlist_container);
        remotePlaylistContainer = findViewById(R.id.music_remote_playlist_container);

        recList.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.music_back_button).setOnClickListener(v -> finish());
        findViewById(R.id.music_search_bar).setOnClickListener(v ->
                startActivity(new Intent(this, MusicSearchActivity.class)));
        bindSectionChips();

        loginButton.setOnClickListener(v ->
                startActivity(new Intent(this, MusicLoginActivity.class)));
    }

    private void refreshLoginState() {
        loginButton.setColorFilter(app.auth.hasVip()
                ? getResources().getColor(R.color.status_success, getTheme())
                : getResources().getColor(R.color.text_secondary, getTheme()));
    }

    private void loadRecommendations() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<SongInfo> songs = app.dataSource.getRecommendations(1);
                new Handler(Looper.getMainLooper()).post(() -> {
                    recommendationSongs = songs;
                    recList.setAdapter(new SongListAdapter(songs, this::onRecommendationClicked));
                });
            } catch (Exception ignored) {}
        });
    }

    private void loadPlaylists() {
        playlistContainer.removeAllViews();
        // 本地歌单页只保留「已下载的歌曲」（用户口径：我喜欢/稍后听不经此入口展示）
        addPlaylistRow(PlaylistDetailActivity.EXTRA_DOWNLOADED_PLAYLIST,
                getString(R.string.music_download_downloaded),
                getString(R.string.music_playlist_song_count, downloadedPlayableCount()));
    }

    private boolean remoteLoadedOnce;

    private void loadRemotePlaylists() {
        if (!app.auth.isLoggedIn()) {
            remoteLoadedOnce = false;
            remotePlaylistContainer.removeAllViews();
            addRemoteStatusRow(getString(R.string.music_remote_login_prompt),
                    getString(R.string.music_login), v ->
                            startActivity(new Intent(this, MusicLoginActivity.class)));
            return;
        }

        // 首次加载显示"加载中"；之后刷新不清空旧列表（无闪烁，新建歌单响应不再显得卡顿），
        // 拉到数据后一次性重建
        if (!remoteLoadedOnce) {
            remotePlaylistContainer.removeAllViews();
            addRemoteStatusRow(getString(R.string.music_remote_loading), null, null);
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.refreshDataSourceAuth();
                List<RemotePlaylist> playlists = app.dataSource.getAllUserPlaylists(30);
                new Handler(Looper.getMainLooper()).post(() -> {
                    remoteLoadedOnce = true;
                    remotePlaylistContainer.removeAllViews();
                    addCreateRemotePlaylistRow();
                    if (playlists.isEmpty()) {
                        addRemoteStatusRow(getString(R.string.music_remote_empty), null, null);
                        return;
                    }
                    for (RemotePlaylist playlist : playlists) {
                        addRemotePlaylistRow(playlist);
                    }
                });
            } catch (Exception e) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!remoteLoadedOnce) {
                        remotePlaylistContainer.removeAllViews();
                        addRemoteStatusRow(getString(R.string.music_remote_load_failed), null, null);
                    }
                });
            }
        });
    }

    private void addPlaylistRow(String playlistName, String title, String subtitle) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_music_song, playlistContainer, false);
        TextView titleView = row.findViewById(R.id.song_row_title);
        TextView artistView = row.findViewById(R.id.song_row_artist);
        TextView durationView = row.findViewById(R.id.song_row_duration);
        TextView arrowView = row.findViewById(R.id.song_row_arrow);

        titleView.setText(title);
        if (subtitle != null) {
            artistView.setText(subtitle);
            durationView.setVisibility(View.GONE);
            arrowView.setVisibility(View.VISIBLE);
        } else {
            artistView.setVisibility(View.GONE);
            durationView.setVisibility(View.GONE);
            arrowView.setVisibility(View.GONE);
            titleView.setTextColor(getResources().getColor(R.color.brand_accent, getTheme()));
        }

        row.setOnClickListener(v -> {
            Intent intent = new Intent(this, PlaylistDetailActivity.class);
            if (PlaylistDetailActivity.EXTRA_DOWNLOADED_PLAYLIST.equals(playlistName)) {
                intent.putExtra(PlaylistDetailActivity.EXTRA_DOWNLOADED_PLAYLIST, true);
            } else {
                intent.putExtra("playlist_name", playlistName);
            }
            startActivity(intent);
        });
        playlistContainer.addView(row);
    }

    private int downloadedPlayableCount() {
        java.util.HashSet<String> paths = new java.util.HashSet<>();
        for (DownloadedSong song : app.downloadStore.all()) {
            if (song.localPath != null && new File(song.localPath).isFile()) {
                paths.add(new File(song.localPath).getAbsolutePath());
            }
        }
        File dir = app.downloads.downloadsDir();
        File[] files = dir.listFiles(file -> file != null
                && file.isFile()
                && (file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".mp3")
                || file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".flac")));
        if (files != null) {
            for (File file : files) {
                paths.add(file.getAbsolutePath());
            }
        }
        return paths.size();
    }

    private void addRemotePlaylistRow(RemotePlaylist playlist) {
        String subtitle = playlist.songCount > 0
                ? getString(R.string.music_playlist_song_count, playlist.songCount)
                : getString(R.string.music_remote_playlist_readonly);
        View row = createPlaylistLikeRow(playlist.name, subtitle, true);
        row.setOnClickListener(v -> {
            startActivity(remotePlaylistIntent(playlist));
        });
        remotePlaylistContainer.addView(row);
    }

    private void addCreateRemotePlaylistRow() {
        View row = createPlaylistLikeRow(getString(R.string.music_remote_create_playlist), null, true);
        TextView titleView = row.findViewById(R.id.song_row_title);
        titleView.setTextColor(getResources().getColor(R.color.brand_accent, getTheme()));
        row.setOnClickListener(v -> promptCreateRemotePlaylist());
        remotePlaylistContainer.addView(row);
    }

    private void addRemoteStatusRow(String title, String subtitle, View.OnClickListener click) {
        View row = createPlaylistLikeRow(title, subtitle, click != null);
        row.setOnClickListener(click);
        remotePlaylistContainer.addView(row);
    }

    private View createPlaylistLikeRow(String title, String subtitle, boolean showArrow) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_music_song, playlistContainer, false);
        TextView titleView = row.findViewById(R.id.song_row_title);
        TextView artistView = row.findViewById(R.id.song_row_artist);
        TextView durationView = row.findViewById(R.id.song_row_duration);
        TextView arrowView = row.findViewById(R.id.song_row_arrow);
        titleView.setText(title);
        if (subtitle == null || subtitle.isEmpty()) {
            artistView.setVisibility(View.GONE);
        } else {
            artistView.setText(subtitle);
            artistView.setVisibility(View.VISIBLE);
        }
        durationView.setVisibility(View.GONE);
        arrowView.setVisibility(showArrow ? View.VISIBLE : View.GONE);
        return row;
    }

    public String displayPlaylistName(String name) {
        if ("Favorites".equals(name)) return getString(R.string.music_playlist_favorites);
        if ("Listen Later".equals(name)) return getString(R.string.music_playlist_listen_later);
        if ("Recently Played".equals(name)) return getString(R.string.music_recent);
        return name;
    }

    private void promptCreateRemotePlaylist() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.music_create_playlist_hint);
        input.setSingleLine(true);
        input.setMaxLines(1);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_create_playlist_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        createRemotePlaylist(name);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createRemotePlaylist(String name) {
        GlassToast.makeText(this, R.string.music_remote_create_loading, GlassToast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.refreshDataSourceAuth();
                app.dataSource.createUserPlaylist(name, false);
                new Handler(Looper.getMainLooper()).post(() -> {
                    GlassToast.makeText(this, R.string.music_remote_create_done, GlassToast.LENGTH_SHORT).show();
                    loadRemotePlaylists();
                });
            } catch (Exception exception) {
                new Handler(Looper.getMainLooper()).post(() ->
                        GlassToast.makeText(this, R.string.music_remote_create_failed, GlassToast.LENGTH_SHORT).show());
            }
        });
    }

    private void onRecommendationClicked(SongInfo song) {
        playFromList(song, recommendationSongs);
    }

    /** 从指定列表中播放歌曲，构建完整播放队列使上一首/下一首按钮可用。 */
    private void playFromList(SongInfo song, List<SongInfo> list) {
        // 已登录时直接尝试播放（概念版 /v5/url 会带 token 解析 VIP URL）；
        // 未登录的 VIP 歌曲才提示登录/领取。
        if (song.vipRequired && !app.auth.isLoggedIn()) {
            promptVipSync();
            return;
        }
        app.playlists.addRecentPlay(song);
        int startIndex = list.indexOf(song);
        if (startIndex < 0) startIndex = 0;
        // 传入完整列表作为播放队列
        app.player.play(new java.util.ArrayList<>(list), startIndex,
                MusicPlayer.PlaySource.RECOMMENDATION, getString(R.string.music_recommend));
        startActivity(new Intent(this, MusicPlayerActivity.class));
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
                                startActivity(new Intent(this, MusicLoginActivity.class));
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void bindSectionChips() {
        // 三个分类是真 tab：点击切换显示对应区块，而非滚动定位
        int[] chips = {R.id.music_chip_rec, R.id.music_chip_playlist, R.id.music_chip_remote};
        for (int i = 0; i < chips.length; i++) {
            final int index = i;
            findViewById(chips[i]).setOnClickListener(v -> selectSection(index));
        }
        selectSection(0);
    }

    private void selectSection(int index) {
        int[] chips = {R.id.music_chip_rec, R.id.music_chip_playlist, R.id.music_chip_remote};
        for (int i = 0; i < chips.length; i++) {
            TextView chip = findViewById(chips[i]);
            boolean active = i == index;
            chip.setBackgroundResource(active
                    ? R.drawable.bg_chip_active : R.drawable.bg_chip_inactive);
            chip.setTextColor(getResources().getColor(active
                    ? R.color.text_on_primary : R.color.text_secondary, getTheme()));
            chip.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        setSectionVisible(R.id.music_sec_rec, R.id.music_home_rec_list, index == 0);
        setSectionVisible(R.id.music_sec_playlist, R.id.music_playlist_container, index == 1);
        setSectionVisible(R.id.music_sec_remote, R.id.music_remote_playlist_container, index == 2);
    }

    private void setSectionVisible(int headerId, int contentId, boolean visible) {
        View header = findViewById(headerId);
        if (header != null) {
            header.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
        View content = findViewById(contentId);
        if (content != null) {
            content.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    private void updateMiniPlayer() {
        // 常驻底部：首次调用时 inflate 布局并绑定事件
        if (miniTitle == null) {
            LayoutInflater.from(this).inflate(R.layout.bar_mini_player, (ViewGroup) miniPlayer, true);
            miniTitle = miniPlayer.findViewById(R.id.mini_player_title);
            miniArtist = miniPlayer.findViewById(R.id.mini_player_artist);
            miniIcon = miniPlayer.findViewById(R.id.mini_player_icon);
            miniCover = miniPlayer.findViewById(R.id.mini_player_cover);
            miniPlayIcon = miniPlayer.findViewById(R.id.mini_player_play);
            miniPrev = miniPlayer.findViewById(R.id.mini_player_prev);
            miniNext = miniPlayer.findViewById(R.id.mini_player_next);
            miniCenter = miniPlayer.findViewById(R.id.mini_player_center);
            miniVinyl = miniPlayer.findViewById(R.id.mini_player_vinyl);
            miniTonearm = miniPlayer.findViewById(R.id.mini_player_tonearm);
            miniSourcePill = miniPlayer.findViewById(R.id.mini_player_source_pill);
            turntable.bindArmPivot(miniTonearm);
            // 歌单入口胶囊：播放中打开播放页并自动弹出队列弹窗（选择歌单/切歌）
            if (miniSourcePill != null) {
                miniSourcePill.setOnClickListener(v -> {
                    if (app.player.currentSong() != null
                            && app.player.getState() != MusicPlayer.State.IDLE) {
                        startActivity(new Intent(this, MusicPlayerActivity.class)
                                .putExtra(MusicPlayerActivity.EXTRA_OPEN_QUEUE, true));
                    } else {
                        restoreLastPlaybackMenu();
                    }
                });
            }
            View miniPlay = miniPlayer.findViewById(R.id.mini_player_play);
            if (miniPlay != null) {
                // 原型交互：迷你条上的播放小键只切换播放状态，不打开播放页
                miniPlay.setOnClickListener(v -> {
                    if (app.player.currentSong() != null
                            && app.player.getState() != MusicPlayer.State.IDLE) {
                        app.player.toggle();
                    } else {
                        restoreLastPlaybackMenu();
                    }
                });
            }
            miniPrev.setOnClickListener(v -> app.player.previous());
            miniNext.setOnClickListener(v -> app.player.next());
            miniCenter.setOnClickListener(v -> {
                SongInfo cur = app.player.currentSong();
                if (cur != null && app.player.getState() != MusicPlayer.State.IDLE) {
                    startActivity(new Intent(this, MusicPlayerActivity.class));
                    return;
                }
                restoreLastPlaybackMenu();
            });
        }

        SongInfo current = app.player.currentSong();
        boolean active = current != null && app.player.getState() != MusicPlayer.State.IDLE;

        if (active) {
            miniTitle.setText(LocalSongNames.displayTitle(current.title));
            // 胶囊=当前播放的来源歌单（哪个列表一眼可见），无来源则隐藏
            String sourceName = app.player.getPlaySourceName();
            miniArtist.setText(sourceName);
            updateMiniArtwork(current);
            if (miniSourcePill != null) {
                miniSourcePill.setVisibility(sourceName.isEmpty() ? View.GONE : View.VISIBLE);
            }
            miniPrev.setEnabled(true);
            miniNext.setEnabled(true);
            miniPrev.setAlpha(1f);
            miniNext.setAlpha(1f);
        } else {
            // 空闲状态：显示默认图标，禁用上一首/下一首
            miniTitle.setText("");
            miniArtist.setText("");
            showMiniTextIcon(null);
            if (miniSourcePill != null) miniSourcePill.setVisibility(View.GONE);
            miniPrev.setEnabled(false);
            miniNext.setEnabled(false);
            miniPrev.setAlpha(0.4f);
            miniNext.setAlpha(0.4f);
        }
        updateMiniPlayIcon();
    }

    /** 迷你条播放键状态：播放中显示暂停，否则显示播放；黑胶唱片随之转动/暂停。 */
    private void updateMiniPlayIcon() {
        if (miniPlayIcon == null) return;
        boolean playing = app.player.getState() == MusicPlayer.State.PLAYING;
        miniPlayIcon.setImageResource(playing
                ? R.drawable.ic_pause_outline : R.drawable.ic_play_outline);
        miniPlayIcon.setColorFilter(0xFFFFFFFF);
        turntable.update(miniVinyl, miniTonearm, playing);
    }

    private void updateMiniArtwork(SongInfo song) {
        showMiniTextIcon(song);
        if (song == null || hasText(song.localPath) || !hasText(song.imgUrl)) {
            return;
        }
        String url = song.imgUrl;
        miniCover.setTag(url);
        Executors.newSingleThreadExecutor().execute(() -> {
            try (InputStream input = new URL(url).openStream()) {
                Bitmap bitmap = BitmapFactory.decodeStream(input);
                runOnUiThread(() -> {
                    SongInfo current = app.player.currentSong();
                    if (bitmap != null
                            && current == song
                            && url.equals(miniCover.getTag())) {
                        miniCover.setImageBitmap(bitmap);
                        miniCover.setVisibility(View.VISIBLE);
                        miniIcon.setVisibility(View.GONE);
                    }
                });
            } catch (Exception ignored) {
            }
        });
    }

    private void showMiniTextIcon(SongInfo song) {
        if (miniCover != null) {
            miniCover.setTag(null);
            miniCover.setVisibility(View.GONE);
            miniCover.setImageDrawable(null);
        }
        if (miniIcon == null) return;
        String title = song == null ? "" : song.title;
        miniIcon.setText(hasText(title)
                ? String.valueOf(title.charAt(0)).toUpperCase()
                : "♪");
        miniIcon.setVisibility(View.VISIBLE);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private void restoreLastPlaybackMenu() {
        List<SongInfo> currentQueue = app.player.getQueue();
        if (!currentQueue.isEmpty()) {
            int index = Math.max(0, app.player.getQueueIndex());
            // play(...) 会重置来源标签，恢复播放前先取出、播放后补回，保证排序回写仍指向原歌单
            String tag = app.player.getPlaySourceTag();
            app.player.play(currentQueue, index, app.player.getPlaySource(), app.player.getPlaySourceName(), tag);
            startActivity(new Intent(this, MusicPlayerActivity.class));
            return;
        }

        String type = app.lastMenuType();
        if ("downloaded".equals(type)) {
            List<SongInfo> songs = downloadedSongs();
            if (songs.isEmpty()) {
                GlassToast.makeText(this, R.string.music_playlist_empty, GlassToast.LENGTH_SHORT).show();
                return;
            }
            app.player.play(songs, 0, MusicPlayer.PlaySource.DOWNLOADED,
                    getString(R.string.music_download_downloaded));
            startActivity(new Intent(this, MusicPlayerActivity.class));
            return;
        }

        if ("local".equals(type)) {
            String name = app.lastMenuName();
            if (name == null || name.isEmpty()) {
                GlassToast.makeText(this, R.string.music_playlist_empty, GlassToast.LENGTH_SHORT).show();
                return;
            }
            List<SongInfo> songs = app.playlists.getSongs(name);
            if (songs.isEmpty()) {
                startActivity(new Intent(this, PlaylistDetailActivity.class)
                        .putExtra("playlist_name", name));
                return;
            }
            app.player.play(songs, 0, MusicPlayer.PlaySource.LOCAL_PLAYLIST,
                    displayPlaylistName(name), "local:" + name);
            startActivity(new Intent(this, MusicPlayerActivity.class));
            return;
        }

        if ("remote".equals(type)) {
            RemotePlaylist playlist = app.lastRemoteMenu();
            if (playlist != null) {
                startActivity(remotePlaylistIntent(playlist));
                return;
            }
        }

        GlassToast.makeText(this, R.string.music_playlist_empty, GlassToast.LENGTH_SHORT).show();
    }

    private List<SongInfo> downloadedSongs() {
        List<SongInfo> songs = new ArrayList<>();
        for (DownloadedSong downloaded : app.downloadStore.all()) {
            if (downloaded.localPath == null || !new File(downloaded.localPath).isFile()) {
                continue;
            }
            SongInfo song = new SongInfo();
            song.hash = downloaded.hash;
            song.title = downloaded.title;
            song.artist = downloaded.artist;
            song.album = downloaded.album;
            song.duration = downloaded.duration;
            song.imgUrl = downloaded.imgUrl;
            song.localPath = downloaded.localPath;
            songs.add(song);
        }
        return songs;
    }

    private Intent remotePlaylistIntent(RemotePlaylist playlist) {
        Intent intent = new Intent(this, PlaylistDetailActivity.class);
        intent.putExtra("remote_playlist_id", playlist.id);
        intent.putExtra("remote_playlist_global_id", playlist.globalCollectionId);
        intent.putExtra("remote_playlist_listid", playlist.listId);
        intent.putExtra("remote_playlist_name", playlist.name);
        intent.putExtra("remote_playlist_count", playlist.songCount);
        return intent;
    }

    // MusicPlayer.Callback
    @Override public void onStateChanged(MusicPlayer.State s) { updateMiniPlayer(); }
    @Override public void onProgressChanged(int c, int t) {}
    @Override public void onSongChanged(SongInfo song) { updateMiniPlayer(); }
    @Override public void onError(String msg) {
        // 播放页在前台时由播放页统一提示；这里只在播放页不在时兜底，
        // 避免同一条错误在多个页面各弹一次（toast 排队导致后续提示延迟 1-2s）
        if (!MusicPlayerActivity.sPlayerForeground) {
            MusicPlayerActivity.showPlaybackErrorToast(this, msg);
        }
    }

    // ---- Adapter ----

    public static class SongListAdapter extends RecyclerView.Adapter<SongListAdapter.VH> {
        private final List<SongInfo> items;
        private final OnSongClick listener;
        private final OnSongClick addToListener; // “添加到歌单”回调，可为 null
        public interface OnSongClick { void onClick(SongInfo s); }
        public SongListAdapter(List<SongInfo> items, OnSongClick listener) {
            this(items, listener, null);
        }
        public SongListAdapter(List<SongInfo> items, OnSongClick listener, OnSongClick addToListener) {
            this.items = items; this.listener = listener; this.addToListener = addToListener;
        }

        @Override public VH onCreateViewHolder(ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_music_song, parent, false);
            return new VH(v);
        }
        @Override public void onBindViewHolder(VH h, int pos) {
            SongInfo s = items.get(pos);
            // 本地文件名兜底："王菲 - 传奇.mp3" → 标题"传奇"+歌手"王菲"
            LocalSongNames.apply(s, h.itemView.getContext().getString(R.string.music_download_downloaded));
            h.title.setText(s.title);
            h.artist.setText(s.artist);
            h.duration.setText(s.durationFormatted());
            h.vipBadge.setVisibility(s.vipRequired ? View.VISIBLE : View.GONE);
            h.icon.setText(s.title.isEmpty() ? "♪" : String.valueOf(s.title.charAt(0)).toUpperCase());
            bindCover(h, s);
            h.itemView.setOnClickListener(v -> listener.onClick(s));
            // “添加到歌单”按钮：仅在传入回调时显示
            if (addToListener != null && h.addBtn != null) {
                h.addBtn.setVisibility(View.VISIBLE);
                h.addBtn.setOnClickListener(v -> addToListener.onClick(s));
            } else if (h.addBtn != null) {
                h.addBtn.setVisibility(View.GONE);
            }
        }
        @Override public int getItemCount() { return items.size(); }

        /** 行封面：有 imgUrl 时异步加载，失败/无图回退首字母占位（tag 防复用错位）。 */
        private void bindCover(VH h, SongInfo s) {
            android.widget.ImageView cover = h.cover;
            if (cover == null) return;
            String url = s == null ? null : s.imgUrl;
            if (url == null || url.isEmpty()) {
                cover.setTag(null);
                cover.setVisibility(View.GONE);
                h.icon.setVisibility(View.VISIBLE);
                return;
            }
            cover.setTag(url);
            java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
                android.graphics.Bitmap bmp = null;
                try (java.io.InputStream in = new java.net.URL(url).openStream()) {
                    bmp = android.graphics.BitmapFactory.decodeStream(in);
                } catch (Exception ignored) {
                }
                android.graphics.Bitmap finalBmp = bmp;
                cover.post(() -> {
                    if (!url.equals(cover.getTag())) return; // 行已复用给他歌
                    if (finalBmp != null) {
                        cover.setImageBitmap(finalBmp);
                        cover.setVisibility(View.VISIBLE);
                        h.icon.setVisibility(View.GONE);
                    } else {
                        cover.setVisibility(View.GONE);
                        h.icon.setVisibility(View.VISIBLE);
                    }
                });
            });
        }

        static class VH extends RecyclerView.ViewHolder {
            TextView title, artist, duration, vipBadge, icon;
            ImageView cover;
            ImageButton addBtn;
            VH(View v) {
                super(v);
                title = v.findViewById(R.id.song_row_title);
                artist = v.findViewById(R.id.song_row_artist);
                duration = v.findViewById(R.id.song_row_duration);
                vipBadge = v.findViewById(R.id.song_row_vip_badge);
                icon = v.findViewById(R.id.song_row_icon);
                cover = v.findViewById(R.id.song_row_cover);
                addBtn = v.findViewById(R.id.song_row_add);
            }
        }
    }
}
