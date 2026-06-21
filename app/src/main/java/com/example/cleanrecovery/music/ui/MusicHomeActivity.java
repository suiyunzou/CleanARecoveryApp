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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
    private TextView miniTitle, miniIcon;
    private ImageView miniCover;
    private ImageButton miniPrev, miniNext;
    private View miniCenter;
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
        loadRemotePlaylists();
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
        addPlaylistRow(PlaylistDetailActivity.EXTRA_DOWNLOADED_PLAYLIST,
                getString(R.string.music_download_downloaded),
                getString(R.string.music_playlist_song_count, downloadedPlayableCount()));
        addPlaylistRow("Listen Later",
                getString(R.string.music_playlist_listen_later),
                getString(R.string.music_playlist_song_count, app.playlists.songCount("Listen Later")));
    }

    private void loadRemotePlaylists() {
        remotePlaylistContainer.removeAllViews();
        if (!app.auth.isLoggedIn()) {
            addRemoteStatusRow(getString(R.string.music_remote_login_prompt),
                    getString(R.string.music_login), v ->
                            startActivity(new Intent(this, MusicLoginActivity.class)));
            return;
        }

        addRemoteStatusRow(getString(R.string.music_remote_loading), null, null);
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.refreshDataSourceAuth();
                List<RemotePlaylist> playlists = app.dataSource.getAllUserPlaylists(30);
                new Handler(Looper.getMainLooper()).post(() -> {
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
                    remotePlaylistContainer.removeAllViews();
                    addRemoteStatusRow(getString(R.string.music_remote_load_failed), null, null);
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
        Toast.makeText(this, R.string.music_remote_create_loading, Toast.LENGTH_SHORT).show();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.refreshDataSourceAuth();
                app.dataSource.createUserPlaylist(name, false);
                new Handler(Looper.getMainLooper()).post(() -> {
                    Toast.makeText(this, R.string.music_remote_create_done, Toast.LENGTH_SHORT).show();
                    loadRemotePlaylists();
                });
            } catch (Exception exception) {
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(this, R.string.music_remote_create_failed, Toast.LENGTH_SHORT).show());
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

    private void updateMiniPlayer() {
        // 常驻底部：首次调用时 inflate 布局并绑定事件
        if (miniTitle == null) {
            LayoutInflater.from(this).inflate(R.layout.bar_mini_player, (ViewGroup) miniPlayer, true);
            miniTitle = miniPlayer.findViewById(R.id.mini_player_title);
            miniIcon = miniPlayer.findViewById(R.id.mini_player_icon);
            miniCover = miniPlayer.findViewById(R.id.mini_player_cover);
            miniPrev = miniPlayer.findViewById(R.id.mini_player_prev);
            miniNext = miniPlayer.findViewById(R.id.mini_player_next);
            miniCenter = miniPlayer.findViewById(R.id.mini_player_center);
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
            // 标题行合并显示「歌曲名 - 艺术家」，居中
            String label = current.artist != null && !current.artist.isEmpty()
                    ? current.title + " - " + current.artist : current.title;
            miniTitle.setText(label);
            updateMiniArtwork(current);
            miniPrev.setEnabled(true);
            miniNext.setEnabled(true);
            miniPrev.setAlpha(1f);
            miniNext.setAlpha(1f);
        } else {
            // 空闲状态：显示默认图标，禁用上一首/下一首
            miniTitle.setText("");
            showMiniTextIcon(null);
            miniPrev.setEnabled(false);
            miniNext.setEnabled(false);
            miniPrev.setAlpha(0.4f);
            miniNext.setAlpha(0.4f);
        }
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
            app.player.play(currentQueue, index, app.player.getPlaySource(), app.player.getPlaySourceName());
            startActivity(new Intent(this, MusicPlayerActivity.class));
            return;
        }

        String type = app.lastMenuType();
        if ("downloaded".equals(type)) {
            List<SongInfo> songs = downloadedSongs();
            if (songs.isEmpty()) {
                Toast.makeText(this, R.string.music_playlist_empty, Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, R.string.music_playlist_empty, Toast.LENGTH_SHORT).show();
                return;
            }
            List<SongInfo> songs = app.playlists.getSongs(name);
            if (songs.isEmpty()) {
                startActivity(new Intent(this, PlaylistDetailActivity.class)
                        .putExtra("playlist_name", name));
                return;
            }
            app.player.play(songs, 0, MusicPlayer.PlaySource.LOCAL_PLAYLIST,
                    displayPlaylistName(name));
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

        Toast.makeText(this, R.string.music_playlist_empty, Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, getString(R.string.music_playback_failed), Toast.LENGTH_SHORT).show();
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
            h.title.setText(s.title);
            h.artist.setText(s.artist);
            h.duration.setText(s.durationFormatted());
            h.vipBadge.setVisibility(s.vipRequired ? View.VISIBLE : View.GONE);
            h.icon.setText(s.title.isEmpty() ? "♪" : String.valueOf(s.title.charAt(0)).toUpperCase());
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

        static class VH extends RecyclerView.ViewHolder {
            TextView title, artist, duration, vipBadge, icon;
            ImageButton addBtn;
            VH(View v) {
                super(v);
                title = v.findViewById(R.id.song_row_title);
                artist = v.findViewById(R.id.song_row_artist);
                duration = v.findViewById(R.id.song_row_duration);
                vipBadge = v.findViewById(R.id.song_row_vip_badge);
                icon = v.findViewById(R.id.song_row_icon);
                addBtn = v.findViewById(R.id.song_row_add);
            }
        }
    }
}
