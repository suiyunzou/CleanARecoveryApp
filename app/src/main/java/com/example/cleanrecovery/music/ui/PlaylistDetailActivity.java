package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.DownloadedSong;
import com.example.cleanrecovery.music.data.RemotePlaylist;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.data.UserInfo;
import com.example.cleanrecovery.music.download.DownloadManager;
import com.example.cleanrecovery.music.player.MusicPlayer;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

public final class PlaylistDetailActivity extends Activity implements MusicPlayer.Callback {
    public static final String EXTRA_DOWNLOADED_PLAYLIST = "downloaded_playlist";

    private MusicApp app;
    private String playlistName;
    private RemotePlaylist remotePlaylist;
    private boolean remoteMode;
    private boolean downloadedMode;

    private RecyclerView list;
    private TextView emptyView;
    private android.widget.ImageView coverView;
    private TextView headerTitleView;
    private TextView ownerView;
    private TextView countView;
    private View playAllButton;
    private View normalActions;
    private View searchActions;
    private View selectionActions;
    private View batchActions;
    private EditText searchInput;
    private TextView selectionCountView;
    private TextView selectionActionButton;
    private PlaylistSongAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    private View miniPlayer;
    private TextView miniTitle;
    private TextView miniArtist;
    private View miniVinyl;
    private View miniTonearm;
    private View miniSourcePill;
    private final MiniTurntable turntable = new MiniTurntable();
    private TextView miniIcon;
    private ImageView miniCover;
    private ImageView miniPlayIcon;
    private ImageButton miniPrev;
    private ImageButton miniNext;
    private View miniCenter;

    private final List<SongInfo> items = new ArrayList<>();
    private final List<SongInfo> visibleItems = new ArrayList<>();
    private final Set<String> selectedKeys = new HashSet<>();
    private String searchQuery = "";
    private boolean selectionMode;
    private boolean customSortMode;
    /** 程序内同步全选框状态时置真，避免触发监听造成递归。 */
    private boolean selectAllSync;

    /** 本地歌单的排序方式。自定义顺序落库为 position，因此等同于 ADDED。 */
    private enum SortMode { ADDED, TITLE, RECENT }
    private static final String SORT_PREFS = "music_playlist_sort";
    /** 云歌单自定义顺序的本地覆盖层（酷狗 API 无云端重排接口，仅本机生效）。key=云歌单稳定 id，value=换行分隔的 songKey 顺序。 */
    private static final String REMOTE_ORDER_PREFS = "music_remote_order";
    private SortMode sortMode = SortMode.ADDED;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_playlist_detail);
        app = MusicApp.init(this);
        app.player.addCallback(this);

        remotePlaylist = readRemotePlaylist();
        remoteMode = remotePlaylist != null;
        downloadedMode = getIntent().getBooleanExtra(EXTRA_DOWNLOADED_PLAYLIST, false);
        playlistName = downloadedMode
                ? getString(R.string.music_download_downloaded)
                : (remoteMode ? remotePlaylist.name : getIntent().getStringExtra("playlist_name"));
        if (playlistName == null) {
            finish();
            return;
        }
        sortMode = (remoteMode || downloadedMode) ? SortMode.ADDED : loadSortMode();

        bindViews();
        bindListeners();
        configureList();
        updateMiniPlayer();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
        updateMiniPlayer();
    }

    @Override
    protected void onDestroy() {
        app.player.removeCallback(this);
        super.onDestroy();
    }

    private void bindViews() {
        coverView = findViewById(R.id.playlist_detail_cover);
        headerTitleView = findViewById(R.id.playlist_detail_header_title);
        ownerView = findViewById(R.id.playlist_detail_owner);
        // 酷狗口径：胶囊(仅播放三角) + 胶囊外的歌曲数文字，两个独立视图
        countView = findViewById(R.id.playlist_detail_count);
        playAllButton = findViewById(R.id.playlist_detail_play_all);
        normalActions = findViewById(R.id.playlist_detail_normal_actions);
        searchActions = findViewById(R.id.playlist_detail_search_actions);
        selectionActions = findViewById(R.id.playlist_detail_selection_actions);
        batchActions = findViewById(R.id.playlist_detail_batch_actions);
        searchInput = findViewById(R.id.playlist_detail_search_input);
        selectionCountView = findViewById(R.id.playlist_detail_selection_count);
        selectionActionButton = findViewById(R.id.playlist_detail_selection_cancel);
        android.widget.CheckBox selectAll = findViewById(R.id.playlist_detail_select_all);
        selectAll.setOnCheckedChangeListener((button, checked) -> {
            if (selectAllSync) return;
            setAllVisibleSelected(checked);
        });
        list = findViewById(R.id.playlist_detail_list);
        emptyView = findViewById(R.id.playlist_detail_empty);
        miniPlayer = findViewById(R.id.playlist_mini_player_stub);
    }

    private void bindListeners() {
        findViewById(R.id.playlist_detail_back).setOnClickListener(v -> finish());
        findViewById(R.id.playlist_detail_search).setOnClickListener(v -> enterSearchMode());
        findViewById(R.id.playlist_detail_sort).setOnClickListener(v -> {
            if (customSortMode) {
                exitCustomSortMode(true);
            } else {
                showSortPanel();
            }
        });
        // ⋮：歌单菜单（重命名/批量管理）；默认歌单不提供重命名
        findViewById(R.id.playlist_detail_more).setOnClickListener(v -> showPlaylistMenuSheet());
        findViewById(R.id.playlist_detail_search_cancel).setOnClickListener(v -> exitSearchMode());
        selectionActionButton.setOnClickListener(v -> {
            if (customSortMode) {
                exitCustomSortMode(true);
            } else {
                exitSelectionMode();
            }
        });
        playAllButton.setOnClickListener(v -> playAll());
        findViewById(R.id.playlist_detail_share).setOnClickListener(v -> sharePlaylist());
        findViewById(R.id.playlist_detail_download).setOnClickListener(v -> downloadAll());

        findViewById(R.id.playlist_batch_next).setOnClickListener(v -> addSelectedNext());
        findViewById(R.id.playlist_batch_download).setOnClickListener(v -> downloadSelected());
        findViewById(R.id.playlist_batch_add).setOnClickListener(v -> addSelectedToPlaylist());
        findViewById(R.id.playlist_batch_delete).setOnClickListener(v -> removeSelected());

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                searchQuery = s == null ? "" : s.toString();
                refreshVisibleItems();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void configureList() {
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PlaylistSongAdapter(visibleItems, selectedKeys, this::onSongClicked,
                this::toggleSelection, this::onSongMenuRequested, this::startDrag);
        list.setAdapter(adapter);
        itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean isLongPressDragEnabled() {
                return false;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder from,
                                  @NonNull RecyclerView.ViewHolder to) {
                if (!customSortMode) {
                    return false;
                }
                int fromPos = from.getBindingAdapterPosition();
                int toPos = to.getBindingAdapterPosition();
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) {
                    return false;
                }
                Collections.swap(visibleItems, fromPos, toPos);
                items.clear();
                items.addAll(visibleItems);
                adapter.notifyItemMoved(fromPos, toPos);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView,
                                  @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                saveCustomOrderIfNeeded(false);
            }
        });
        itemTouchHelper.attachToRecyclerView(list);
    }

    private RemotePlaylist readRemotePlaylist() {
        String remoteName = getIntent().getStringExtra("remote_playlist_name");
        String id = getIntent().getStringExtra("remote_playlist_id");
        String listId = getIntent().getStringExtra("remote_playlist_listid");
        String globalId = getIntent().getStringExtra("remote_playlist_global_id");
        if ((remoteName == null || remoteName.isEmpty())
                && (id == null || id.isEmpty())
                && (listId == null || listId.isEmpty())
                && (globalId == null || globalId.isEmpty())) {
            return null;
        }
        RemotePlaylist playlist = new RemotePlaylist();
        playlist.name = remoteName != null && !remoteName.isEmpty()
                ? remoteName : getString(R.string.music_remote_playlists);
        playlist.id = id;
        playlist.listId = listId;
        playlist.globalCollectionId = globalId;
        playlist.songCount = getIntent().getIntExtra("remote_playlist_count", 0);
        return playlist;
    }

    /** 头部：名称 / 来源 / 封面（远程封面异步加载，本地用占位封面）。 */
    private void renderPlaylistHeader() {
        headerTitleView.setText(displayPlaylistName(playlistName));
        countView.setText(getString(R.string.music_playlist_song_count, items.size()));
        String owner = null;
        if (remoteMode && remotePlaylist.ownerName != null
                && !remotePlaylist.ownerName.isEmpty()) {
            owner = remotePlaylist.ownerName;
        } else {
            UserInfo user = app.auth.currentUser();
            if (user != null && user.nickname != null && !user.nickname.isEmpty()) {
                owner = user.nickname;
            }
        }
        ownerView.setText(owner == null
                ? getString(R.string.music_playlist_local_source)
                : getString(R.string.music_playlist_from, owner));

        if (remoteMode && remotePlaylist.coverUrl != null
                && !remotePlaylist.coverUrl.isEmpty()) {
            coverView.setBackgroundResource(R.drawable.bg_playlist_cover);
            loadRemoteCover(coverView, remotePlaylist.coverUrl);
        } else if ("Favorites".equals(playlistName)) {
            // 我喜欢：粉色爱心占位封面（参考原型）
            coverView.setImageDrawable(null);
            coverView.setBackgroundResource(R.drawable.bg_playlist_cover_favorite);
        } else {
            coverView.setBackgroundResource(R.drawable.bg_playlist_cover);
            coverView.setImageResource(R.drawable.ic_queue_music);
        }
    }

    private void loadRemoteCover(android.widget.ImageView view, String url) {
        if (url.equals(view.getTag())) return; // 已在加载/已加载
        view.setTag(url);
        Executors.newSingleThreadExecutor().execute(() -> {
            Bitmap bitmap = null;
            try (InputStream input = new URL(url).openStream()) {
                bitmap = BitmapFactory.decodeStream(input);
            } catch (Exception ignored) {
            }
            if (bitmap == null) return;
            final Bitmap loaded = bitmap;
            view.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(loaded);
            });
        });
    }

    private void sharePlaylist() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, getString(R.string.music_playlist_share_text,
                displayPlaylistName(playlistName), items.size()));
        startActivity(Intent.createChooser(send, getString(R.string.music_share)));
    }

    private void downloadAll() {
        if (items.isEmpty()) {
            GlassToast.makeText(this, R.string.music_playlist_empty, GlassToast.LENGTH_SHORT).show();
            return;
        }
        showQualityPicker(getString(R.string.music_download_quality_title),
                quality -> startBatchDownload(new ArrayList<>(items), quality));
    }

    private void reload() {
        if (downloadedMode) {
            reloadDownloaded();
            return;
        }
        if (remoteMode) {
            reloadRemote();
            return;
        }
        items.clear();
        items.addAll(app.playlists.getSongs(playlistName));
        applySortMode();
        refreshVisibleItems();
    }

    /** 按当前持久化的排序方式对 items 重新排序（仅本地歌单）。 */
    private void applySortMode() {
        switch (sortMode) {
            case TITLE:
                Collator collator = Collator.getInstance(Locale.CHINA);
                Collections.sort(items, (left, right) -> collator.compare(safeTitle(left), safeTitle(right)));
                break;
            case RECENT:
                sortItemsByRecentPlay();
                break;
            case ADDED:
            default:
                // items 已按数据库 position 顺序加载，无需额外处理
                break;
        }
    }

    private SortMode loadSortMode() {
        try {
            String v = getSharedPreferences(SORT_PREFS, MODE_PRIVATE).getString(playlistName, SortMode.ADDED.name());
            return SortMode.valueOf(v);
        } catch (Exception e) {
            return SortMode.ADDED;
        }
    }

    private void persistSortMode(SortMode mode) {
        sortMode = mode;
        getSharedPreferences(SORT_PREFS, MODE_PRIVATE).edit().putString(playlistName, mode.name()).apply();
    }

    private void reloadDownloaded() {
        items.clear();
        for (DownloadedSong downloaded : app.downloadStore.all()) {
            SongInfo song = songFromDownload(downloaded);
            if (song != null) {
                items.add(song);
            }
        }
        if (items.isEmpty()) {
            File dir = app.downloads.downloadsDir();
            File[] files = dir.listFiles(file -> file != null
                    && file.isFile()
                    && (file.getName().toLowerCase(Locale.ROOT).endsWith(".mp3")
                    || file.getName().toLowerCase(Locale.ROOT).endsWith(".flac")));
            if (files != null) {
                for (File file : files) {
                    SongInfo song = new SongInfo();
                    song.hash = file.getName();
                    song.title = stripAudioExtension(file.getName());
                    song.artist = getString(R.string.music_download_downloaded);
                    song.localPath = file.getAbsolutePath();
                    items.add(song);
                }
            }
        }
        refreshVisibleItems();
    }

    private String stripAudioExtension(String name) {
        if (name == null) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) {
            return name.substring(0, name.length() - 4);
        }
        if (lower.endsWith(".flac")) {
            return name.substring(0, name.length() - 5);
        }
        return name;
    }

    private void reloadRemote() {
        items.clear();
        visibleItems.clear();
        adapter.notifyDataSetChanged();
        emptyView.setText(R.string.music_remote_loading);
        emptyView.setVisibility(View.VISIBLE);
        list.setVisibility(View.GONE);
        updatePlayAllButton();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.refreshDataSourceAuth();
                List<SongInfo> songs = app.dataSource.getAllUserPlaylistSongs(remotePlaylist, 200);
                runOnUiThread(() -> {
                    items.clear();
                    items.addAll(applyRemoteOrder(songs));
                    refreshVisibleItems();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    emptyView.setText(R.string.music_remote_load_failed);
                    emptyView.setVisibility(View.VISIBLE);
                    list.setVisibility(View.GONE);
                    updatePlayAllButton();
                });
            }
        });
    }

    private void refreshVisibleItems() {
        visibleItems.clear();
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.getDefault());
        for (SongInfo song : items) {
            if (query.isEmpty() || matches(song, query)) {
                visibleItems.add(song);
            }
        }
        renderPlaylistHeader();
        adapter.setSelectionMode(selectionMode);
        adapter.setCustomSortMode(customSortMode);
        adapter.notifyDataSetChanged();
        updateEmptyState();
        updatePlayAllButton();
        updateSelectionCount();
    }

    private boolean matches(SongInfo song, String query) {
        return contains(song.title, query) || contains(song.artist, query) || contains(song.album, query);
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.getDefault()).contains(query);
    }

    private void updateEmptyState() {
        boolean none = visibleItems.isEmpty();
        emptyView.setText(searchQuery == null || searchQuery.trim().isEmpty()
                ? R.string.music_playlist_empty
                : R.string.music_playlist_search_no_matches);
        emptyView.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    private void updatePlayAllButton() {
        // 播放钮已是圆形图标，数量展示移到头部 countView（renderPlaylistHeader）
        playAllButton.setEnabled(!items.isEmpty());
        playAllButton.setAlpha(items.isEmpty() ? 0.45f : 1f);
    }

    private void enterSearchMode() {
        exitSelectionMode();
        exitCustomSortMode(false);
        normalActions.setVisibility(View.GONE);
        searchActions.setVisibility(View.VISIBLE);
        searchInput.requestFocus();
    }

    private void exitSearchMode() {
        searchInput.setText("");
        searchQuery = "";
        searchActions.setVisibility(View.GONE);
        normalActions.setVisibility(View.VISIBLE);
        refreshVisibleItems();
    }

    /** ⋮ 菜单：重命名（仅自建歌单）+ 批量管理 + 删除（仅云自建歌单）。 */
    private void showPlaylistMenuSheet() {
        Dialog dialog = bottomSheetDialog();
        LinearLayout root = bottomSheetRoot();
        if (canRenamePlaylist()) {
            root.addView(sheetActionRow(R.string.music_rename_playlist, () -> {
                dialog.dismiss();
                promptRenamePlaylist();
            }));
        }
        root.addView(sheetActionRow(R.string.music_playlist_batch_manage, () -> {
            dialog.dismiss();
            enterSelectionMode();
        }));
        if (canDeletePlaylist()) {
            root.addView(sheetActionRow(R.string.music_delete_playlist_title, () -> {
                dialog.dismiss();
                confirmDeletePlaylist();
            }));
        }
        showBottomSheet(dialog, root);
    }

    /** 酷狗默认歌单（服务端拒绝改名），界面直接隐藏入口。 */
    private static final boolean isDefaultCloudPlaylist(String name) {
        return "我喜欢".equals(name) || "默认收藏".equals(name);
    }

    /** 本地：系统歌单（我喜欢/稍后听/最近播放）受保护；云歌单：默认歌单不可改。 */
    private boolean canRenamePlaylist() {
        if (downloadedMode) return false;
        if (remoteMode) {
            // /v1/modify_list 加密协议已打通（AES+RSA，见 KugouDataSource）；
            // 默认歌单服务端仍会拒绝，入口保持隐藏
            return playlistName != null && !isDefaultCloudPlaylist(playlistName);
        }
        return playlistName != null && !app.playlists.isProtected(playlistName);
    }

    /** 重命名对话框：本地即时落库；云歌单走 /v1/rename_list 云同步。 */
    private void promptRenamePlaylist() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setMaxLines(1);
        input.setText(playlistName);
        input.setSelection(input.getText().length());
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_rename_playlist)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(playlistName)) return;
                    renamePlaylist(newName);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 云歌单删除入口：与改名同保护名单（默认歌单服务端拒绝）。 */
    private boolean canDeletePlaylist() {
        return remoteMode && !downloadedMode
                && playlistName != null && !isDefaultCloudPlaylist(playlistName);
    }

    private void confirmDeletePlaylist() {
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_delete_playlist_title)
                .setMessage(getString(R.string.music_delete_playlist_confirm, playlistName))
                .setPositiveButton(R.string.music_delete, (d, w) -> deleteCloudPlaylist())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteCloudPlaylist() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.refreshDataSourceAuth();
                app.dataSource.deleteUserPlaylist(remotePlaylist);
                runOnUiThread(() -> {
                    GlassToast.makeText(this, R.string.music_playlist_deleted, GlassToast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (Exception exception) {
                android.util.Log.w("PlaylistDetail", "cloud delete failed", exception);
                runOnUiThread(() ->
                        GlassToast.makeText(this, R.string.music_rename_failed, GlassToast.LENGTH_SHORT).show());
            }
        });
    }

    private void renamePlaylist(String newName) {
        if (remoteMode) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    app.refreshDataSourceAuth();
                    app.dataSource.renameUserPlaylist(remotePlaylist, newName);
                    runOnUiThread(() -> {
                        remotePlaylist.name = newName;
                        playlistName = newName;
                        renderPlaylistHeader();
                        // 最近使用的云歌单记忆同步改名
                        if (app.lastMenuType().equals("remote")) {
                            app.rememberLastRemoteMenu(remotePlaylist);
                        }
                        GlassToast.makeText(this, R.string.music_rename_done, GlassToast.LENGTH_SHORT).show();
                    });
                } catch (Exception exception) {
                    android.util.Log.w("PlaylistDetail", "cloud rename failed", exception);
                    runOnUiThread(() ->
                            GlassToast.makeText(this, R.string.music_rename_failed, GlassToast.LENGTH_SHORT).show());
                }
            });
            return;
        }
        boolean ok = app.playlists.renamePlaylist(playlistName, newName);
        if (!ok) {
            GlassToast.makeText(this, R.string.music_rename_failed, GlassToast.LENGTH_SHORT).show();
            return;
        }
        // 本地"最近菜单"记忆同步改名（oldName 才是与记忆匹配的值）
        if ("local".equals(app.lastMenuType()) && playlistName.equals(app.lastMenuName())) {
            app.rememberLastLocalMenu(newName);
        }
        playlistName = newName;
        reload();
        renderPlaylistHeader();
        GlassToast.makeText(this, R.string.music_rename_done, GlassToast.LENGTH_SHORT).show();
    }

    private void enterSelectionMode() {
        exitCustomSortMode(false);
        android.widget.CheckBox selectAllBox = findViewById(R.id.playlist_detail_select_all);
        if (selectAllBox != null) selectAllBox.setVisibility(View.VISIBLE);
        selectionMode = true;
        selectedKeys.clear();
        normalActions.setVisibility(View.GONE);
        searchActions.setVisibility(View.GONE);
        selectionActions.setVisibility(View.VISIBLE);
        batchActions.setVisibility(View.VISIBLE);
        selectionActionButton.setText(R.string.music_playlist_done);
        adapter.setSelectionMode(true);
        adapter.notifyDataSetChanged();
        updateSelectionCount();
    }

    private void exitSelectionMode() {
        if (!selectionMode && batchActions.getVisibility() == View.GONE) {
            return;
        }
        selectionMode = false;
        selectedKeys.clear();
        selectionActions.setVisibility(View.GONE);
        batchActions.setVisibility(View.GONE);
        if (searchActions.getVisibility() != View.VISIBLE) {
            normalActions.setVisibility(View.VISIBLE);
        }
        selectionActionButton.setText(R.string.music_playlist_done);
        adapter.setSelectionMode(false);
        adapter.notifyDataSetChanged();
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        if (customSortMode) {
            selectionCountView.setText(R.string.music_sort_custom_done_hint);
            return;
        }
        selectionCountView.setText(
                getString(R.string.music_select_all_fmt, selectedKeys.size()));
        syncSelectAllState();
    }

    /** 全选框状态与当前可见列表一致（全部被选=勾选）。 */
    private void syncSelectAllState() {
        android.widget.CheckBox box = findViewById(R.id.playlist_detail_select_all);
        if (box == null) return;
        boolean all = !visibleItems.isEmpty();
        for (SongInfo song : visibleItems) {
            if (!selectedKeys.contains(songKey(song))) {
                all = false;
                break;
            }
        }
        selectAllSync = true;
        box.setChecked(all);
        selectAllSync = false;
    }

    /** 全选/全不选当前可见列表。 */
    private void setAllVisibleSelected(boolean on) {
        if (on) {
            for (SongInfo song : visibleItems) selectedKeys.add(songKey(song));
        } else {
            selectedKeys.clear();
        }
        adapter.notifyDataSetChanged();
        updateSelectionCount();
    }

    private void toggleSelection(SongInfo song) {
        if (!selectionMode) {
            return;
        }
        String key = songKey(song);
        if (selectedKeys.contains(key)) {
            selectedKeys.remove(key);
        } else {
            selectedKeys.add(key);
        }
        adapter.notifyDataSetChanged();
        updateSelectionCount();
    }

    private List<SongInfo> selectedSongs() {
        List<SongInfo> selected = new ArrayList<>();
        for (SongInfo song : visibleItems) {
            if (selectedKeys.contains(songKey(song))) {
                selected.add(song);
            }
        }
        return selected;
    }

    private void showSortPanel() {
        final Dialog dialog = new Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(14), dp(20), dp(18));
        root.setBackgroundColor(getColor(R.color.background_app));
        addSortOption(root, R.string.music_sort_added_time, () -> {
            dialog.dismiss();
            sortByAddedTime();
        });
        addSortOption(root, R.string.music_sort_title, () -> {
            dialog.dismiss();
            sortByTitle();
        });
        addSortOption(root, R.string.music_sort_play_count, () -> {
            dialog.dismiss();
            sortByRecentPlay();
        });
        addSortOption(root, R.string.music_sort_custom, () -> {
            dialog.dismiss();
            enterCustomSortMode();
        });
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        dialog.setOnShowListener(d -> {
            Window shownWindow = dialog.getWindow();
            if (shownWindow != null) {
                shownWindow.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
                shownWindow.setGravity(Gravity.BOTTOM);
                shownWindow.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        if (window != null) {
            window.setGravity(Gravity.BOTTOM);
        }
        dialog.show();
    }

    private void addSortOption(LinearLayout root, int labelRes, Runnable action) {
        TextView option = new TextView(this);
        option.setText(labelRes);
        option.setTextColor(getColor(R.color.text_primary));
        option.setTextSize(16);
        option.setGravity(Gravity.CENTER_VERTICAL);
        option.setPadding(0, dp(14), 0, dp(14));
        option.setOnClickListener(v -> action.run());
        root.addView(option, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void sortByAddedTime() {
        exitCustomSortMode(false);
        if (!remoteMode && !downloadedMode) persistSortMode(SortMode.ADDED);
        reload();
    }

    private void sortByTitle() {
        exitCustomSortMode(false);
        if (!remoteMode && !downloadedMode) persistSortMode(SortMode.TITLE);
        Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(items, (left, right) -> collator.compare(safeTitle(left), safeTitle(right)));
        refreshVisibleItems();
    }

    private void sortByRecentPlay() {
        exitCustomSortMode(false);
        if (!remoteMode && !downloadedMode) persistSortMode(SortMode.RECENT);
        sortItemsByRecentPlay();
        refreshVisibleItems();
    }

    private void sortItemsByRecentPlay() {
        List<String> recentKeys = new ArrayList<>();
        if (remoteMode) {
            try {
                for (SongInfo song : app.dataSource.getUserListenRanking(1)) {
                    recentKeys.add(songKey(song));
                }
            } catch (Exception ignored) {
            }
        }
        for (SongInfo song : app.playlists.getRecentPlays(500)) {
            String key = songKey(song);
            if (!recentKeys.contains(key)) {
                recentKeys.add(key);
            }
        }
        Collections.sort(items, new Comparator<SongInfo>() {
            @Override
            public int compare(SongInfo left, SongInfo right) {
                int leftIndex = recentKeys.indexOf(songKey(left));
                int rightIndex = recentKeys.indexOf(songKey(right));
                leftIndex = leftIndex < 0 ? Integer.MAX_VALUE : leftIndex;
                rightIndex = rightIndex < 0 ? Integer.MAX_VALUE : rightIndex;
                return Integer.compare(leftIndex, rightIndex);
            }
        });
    }

    private void enterCustomSortMode() {
        exitSelectionMode();
        android.widget.CheckBox selectAllBox = findViewById(R.id.playlist_detail_select_all);
        if (selectAllBox != null) selectAllBox.setVisibility(View.GONE);
        if (searchActions.getVisibility() == View.VISIBLE) {
            exitSearchMode();
        }
        customSortMode = true;
        normalActions.setVisibility(View.GONE);
        selectionActions.setVisibility(View.VISIBLE);
        batchActions.setVisibility(View.GONE);
        selectionCountView.setText(R.string.music_sort_custom_done_hint);
        selectionActionButton.setText(R.string.music_playlist_done);
        adapter.setCustomSortMode(true);
        adapter.notifyDataSetChanged();
    }

    private void exitCustomSortMode(boolean showToast) {
        if (!customSortMode) {
            return;
        }
        customSortMode = false;
        selectionActions.setVisibility(View.GONE);
        normalActions.setVisibility(View.VISIBLE);
        selectionActionButton.setText(R.string.file_browser_cancel_select);
        adapter.setCustomSortMode(false);
        adapter.notifyDataSetChanged();
        saveCustomOrderIfNeeded(showToast);
    }

    private void saveCustomOrderIfNeeded(boolean showToast) {
        // 拖拽过程中（customSortMode 仍为 true）不落盘，统一在退出自定义模式时保存。
        if (customSortMode) return;
        // 不持久化“搜索过滤态”下的局部顺序。
        if (!searchQuery.trim().isEmpty()) return;
        if (downloadedMode) return;

        if (remoteMode) {
            // 酷狗云端无重排接口，自定义顺序仅按云歌单 id 存本机，拉取后覆盖回来。
            saveRemoteOrder();
        } else {
            app.playlists.setOrder(playlistName, items);
            // 自定义顺序已落库为 position，后续按"添加时间"(=DB 顺序)展示即为该自定义顺序
            persistSortMode(SortMode.ADDED);
        }
        if (showToast) {
            GlassToast.makeText(this, R.string.music_playlist_order_saved, GlassToast.LENGTH_SHORT).show();
        }
    }

    // ---- 云歌单自定义顺序：本地覆盖层 -------------------------------------

    /** 云歌单的稳定标识，用作本地顺序存储的 key。 */
    private String remoteOrderKey() {
        if (remotePlaylist == null) return "";
        String id = firstNonEmpty(remotePlaylist.globalCollectionId, remotePlaylist.listId, remotePlaylist.id);
        return id == null ? "" : id;
    }

    /** 将当前 items 的 songKey 顺序保存到本地。 */
    private void saveRemoteOrder() {
        String key = remoteOrderKey();
        if (key.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (SongInfo song : items) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(songKey(song));
        }
        getSharedPreferences(REMOTE_ORDER_PREFS, MODE_PRIVATE).edit().putString(key, sb.toString()).apply();
    }

    private List<String> loadRemoteOrder() {
        String key = remoteOrderKey();
        List<String> out = new ArrayList<>();
        if (key.isEmpty()) return out;
        String value = getSharedPreferences(REMOTE_ORDER_PREFS, MODE_PRIVATE).getString(key, "");
        if (value.isEmpty()) return out;
        for (String part : value.split("\n")) {
            if (!part.isEmpty()) out.add(part);
        }
        return out;
    }

    /**
     * 用本地保存的自定义顺序重排服务器返回的歌曲：
     * 已记录的歌按记录顺序排前；新出现（未记录）的歌按服务器顺序追加到末尾；
     * 已删除的歌自然忽略。无本地记录时原样返回。
     */
    private List<SongInfo> applyRemoteOrder(List<SongInfo> songs) {
        List<SongInfo> source = songs == null ? new ArrayList<>() : songs;
        List<String> savedOrder = loadRemoteOrder();
        if (savedOrder.isEmpty()) return new ArrayList<>(source);
        boolean[] used = new boolean[source.size()];
        List<SongInfo> result = new ArrayList<>(source.size());
        for (String key : savedOrder) {
            for (int i = 0; i < source.size(); i++) {
                if (!used[i] && key.equals(songKey(source.get(i)))) {
                    result.add(source.get(i));
                    used[i] = true;
                    break; // 每个记录顺序项消费一首，兼容重复歌曲
                }
            }
        }
        for (int i = 0; i < source.size(); i++) {
            if (!used[i]) result.add(source.get(i)); // 新歌：保持服务器顺序追加
        }
        return result;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (value != null && !value.isEmpty()) return value;
        }
        return "";
    }

    private void startDrag(RecyclerView.ViewHolder holder) {
        if (customSortMode && itemTouchHelper != null) {
            itemTouchHelper.startDrag(holder);
        }
    }

    private void playAll() {
        if (items.isEmpty()) {
            GlassToast.makeText(this, R.string.music_queue_empty, GlassToast.LENGTH_SHORT).show();
            return;
        }
        SongInfo first = items.get(0);
        if (!canPlay(first)) {
            return;
        }
        app.playlists.addRecentPlay(first);
        rememberCurrentMenu();
        app.player.play(new ArrayList<>(items), 0, playSource(), displayPlaylistName(playlistName),
                playbackSourceTag());
        startActivity(new Intent(this, MusicPlayerActivity.class));
    }

    private void onSongClicked(SongInfo song) {
        if (selectionMode) {
            toggleSelection(song);
            return;
        }
        if (customSortMode) {
            return;
        }
        if (!canPlay(song)) {
            return;
        }
        List<SongInfo> queue = new ArrayList<>(visibleItems);
        int index = queue.indexOf(song);
        if (index < 0) {
            index = 0;
        }
        app.playlists.addRecentPlay(song);
        rememberCurrentMenu();
        app.player.play(queue, index, playSource(), displayPlaylistName(playlistName),
                playbackSourceTag());
        startActivity(new Intent(this, MusicPlayerActivity.class));
    }

    /**
     * 播放来源的持久化标识，播放队列拖动排序后据此回写歌单：
     * 本地歌单 → "local:歌单名"；云歌单 → "remote:云歌单id"（与 saveRemoteOrder 同 key）。
     */
    private String playbackSourceTag() {
        if (downloadedMode) return null;
        if (remoteMode) return "remote:" + remoteOrderKey();
        return "local:" + playlistName;
    }

    private void rememberCurrentMenu() {
        if (downloadedMode) {
            app.rememberLastDownloadedMenu();
        } else if (remoteMode) {
            app.rememberLastRemoteMenu(remotePlaylist);
        } else {
            app.rememberLastLocalMenu(playlistName);
        }
    }

    private boolean canPlay(SongInfo song) {
        if (song != null && song.vipRequired && !app.auth.isLoggedIn()) {
            promptVipSync();
            return false;
        }
        return true;
    }

    private MusicPlayer.PlaySource playSource() {
        if (downloadedMode) {
            return MusicPlayer.PlaySource.DOWNLOADED;
        }
        return remoteMode ? MusicPlayer.PlaySource.REMOTE_PLAYLIST : MusicPlayer.PlaySource.LOCAL_PLAYLIST;
    }

    private void addSelectedNext() {
        List<SongInfo> selected = selectedSongs();
        if (selected.isEmpty()) {
            GlassToast.makeText(this, R.string.music_playlist_select_first, GlassToast.LENGTH_SHORT).show();
            return;
        }
        app.player.playNext(selected);
        GlassToast.makeText(this, getString(R.string.music_next_play_added, selected.size()), GlassToast.LENGTH_SHORT).show();
        exitSelectionMode();
    }

    private void downloadSelected() {
        List<SongInfo> selected = selectedSongs();
        if (selected.isEmpty()) {
            GlassToast.makeText(this, R.string.music_playlist_select_first, GlassToast.LENGTH_SHORT).show();
            return;
        }
        showQualityPicker(getString(R.string.music_download_quality_title),
                quality -> startBatchDownload(selected, quality));
        exitSelectionMode();
    }

    private void addSelectedToPlaylist() {
        List<SongInfo> selected = selectedSongs();
        if (selected.isEmpty()) {
            GlassToast.makeText(this, R.string.music_playlist_select_first, GlassToast.LENGTH_SHORT).show();
            return;
        }
        showAddToPlaylistDialog(selected);
    }

    private void removeSelected() {
        List<SongInfo> selected = selectedSongs();
        if (selected.isEmpty()) {
            GlassToast.makeText(this, R.string.music_playlist_select_first, GlassToast.LENGTH_SHORT).show();
            return;
        }
        if (downloadedMode) {
            GlassToast.makeText(this, R.string.music_playlist_readonly_action, GlassToast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_remove_from_playlist)
                .setMessage(getString(R.string.music_playlist_remove_selected_confirm, selected.size()))
                .setPositiveButton(R.string.music_delete, (dialog, which) -> {
                    if (remoteMode) {
                        deleteRemoteSelected(selected);
                        return;
                    }
                    for (SongInfo song : selected) app.playlists.removeSong(playlistName, song);
                    selectedKeys.clear();
                    reload();
                    exitSelectionMode();
                    GlassToast.makeText(this, R.string.music_removed_from_playlist, GlassToast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteRemoteSelected(List<SongInfo> selected) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.dataSource.deleteSongsFromUserPlaylist(remotePlaylist, selected);
                runOnUiThread(() -> {
                    selectedKeys.clear();
                    exitSelectionMode();
                    reloadRemote();
                    GlassToast.makeText(this, R.string.music_cloud_delete_done, GlassToast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                runOnUiThread(() ->
                        GlassToast.makeText(this, R.string.music_cloud_delete_failed, GlassToast.LENGTH_SHORT).show());
            }
        });
    }

    /** 行内“…”：歌单操作底部弹层（下一首播放 / 加入歌单 / 下载 / 移除），复刻原型交互。 */
    private void onSongMenuRequested(SongInfo song) {
        Dialog dialog = bottomSheetDialog();
        LinearLayout root = bottomSheetRoot();
        root.addView(sheetActionRow(R.string.music_next_play, () -> {
            dialog.dismiss();
            if (!canPlay(song)) return;
            app.player.playNext(Collections.singletonList(song));
            GlassToast.makeText(this, getString(R.string.music_next_play_added, 1),
                    GlassToast.LENGTH_SHORT).show();
        }));
        root.addView(sheetActionRow(R.string.music_add_to_playlist, () -> {
            dialog.dismiss();
            showAddToPlaylistDialog(Collections.singletonList(song));
        }));
        root.addView(sheetActionRow(R.string.music_download, () -> {
            dialog.dismiss();
            showQualityPicker(getString(R.string.music_download_quality_title),
                    quality -> startBatchDownload(Collections.singletonList(song), quality));
        }));
        if (!downloadedMode) {
            root.addView(sheetActionRow(R.string.music_remove_from_playlist, () -> {
                dialog.dismiss();
                removeSingleSong(song);
            }));
        }
        showBottomSheet(dialog, root);
    }

    private void removeSingleSong(SongInfo song) {
        if (remoteMode && remotePlaylist != null) {
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    app.dataSource.deleteSongsFromUserPlaylist(remotePlaylist,
                            Collections.singletonList(song));
                    runOnUiThread(() -> {
                        items.remove(song);
                        refreshVisibleItems();
                        GlassToast.makeText(this, R.string.music_cloud_delete_done,
                                GlassToast.LENGTH_SHORT).show();
                    });
                } catch (Exception exception) {
                    runOnUiThread(() -> GlassToast.makeText(this,
                            R.string.music_cloud_delete_failed, GlassToast.LENGTH_SHORT).show());
                }
            });
            return;
        }
        app.playlists.removeSong(playlistName, song);
        items.remove(song);
        refreshVisibleItems();
        GlassToast.makeText(this, R.string.music_removed_from_playlist, GlassToast.LENGTH_SHORT).show();
    }

    /** 底部弹层：非浮动透明主题 + 上滑动画（与播放页弹层一致）。 */
    private Dialog bottomSheetDialog() {
        Dialog dialog = new Dialog(this, R.style.MusicBottomSheetDialogTheme);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(R.style.MusicBottomSheetAnimation);
        }
        return dialog;
    }

    private LinearLayout bottomSheetRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(6), dp(8), dp(6), dp(16));
        root.setBackgroundResource(R.drawable.bg_queue_sheet);
        return root;
    }

    private void showBottomSheet(Dialog dialog, View card) {
        FrameLayout wrap = new FrameLayout(this);
        wrap.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        dialog.setContentView(wrap);
        dialog.show();
    }

    private TextView sheetActionRow(int textRes, Runnable action) {
        TextView row = new TextView(this);
        row.setText(textRes);
        row.setTextSize(15);
        row.setTextColor(getColor(R.color.text_primary));
        row.setMinHeight(dp(48));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), 0, dp(16), 0);
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /**
     * 添加到歌单弹层：本地歌单（我喜欢+自定义，最近播放除外）即时加入；
     * 登录后异步加载酷狗云歌单，点选即云同步。修复此前"只能加到稍后听"的问题。
     */
    private void showAddToPlaylistDialog(List<SongInfo> songs) {
        if (songs == null || songs.isEmpty()) return;
        Dialog dialog = new Dialog(this, R.style.MusicBottomSheetDialogTheme);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(R.style.MusicBottomSheetAnimation);
        }
        FrameLayout wrap = new FrameLayout(this);
        wrap.setPadding(dp(6), 0, dp(6), dp(8));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(22));
        root.setBackgroundResource(R.drawable.bg_queue_sheet);

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
        list.addView(sheetSectionLabel(R.string.music_playlists));
        List<String> names = new ArrayList<>();
        for (String name : app.playlists.listPlaylists()) {
            if ("Recently Played".equals(name)) continue;
            names.add(name);
        }
        if (names.isEmpty()) names.add("Favorites");
        for (String name : names) {
            list.addView(sheetPlaylistRow(displayPlaylistName(name),
                    getString(R.string.music_playlist_song_count, app.playlists.songCount(name)),
                    () -> {
                        for (SongInfo song : songs) app.playlists.addSong(name, song);
                        dialog.dismiss();
                        GlassToast.makeText(this,
                                getString(R.string.music_added_to_playlist, displayPlaylistName(name)),
                                GlassToast.LENGTH_SHORT).show();
                        exitSelectionMode();
                    }));
        }

        // 云歌单：登录后异步加载，点选即云同步
        list.addView(sheetSectionLabel(R.string.music_remote_playlists));
        if (!app.auth.isLoggedIn()) {
            list.addView(sheetStatusRow(getString(R.string.music_remote_login_prompt)));
        } else {
            final TextView loading = sheetStatusRow(getString(R.string.music_remote_loading));
            list.addView(loading);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    app.refreshDataSourceAuth();
                    List<RemotePlaylist> remote = app.dataSource.getAllUserPlaylists(30);
                    runOnUiThread(() -> {
                        loading.setVisibility(View.GONE);
                        for (RemotePlaylist playlist : remote) {
                            list.addView(sheetPlaylistRow(playlist.name,
                                    getString(R.string.music_playlist_song_count, playlist.songCount),
                                    () -> addSongsToCloudPlaylist(dialog, playlist, songs)));
                        }
                        capSheetScrollHeight(scroll, list);
                    });
                } catch (Exception exception) {
                    runOnUiThread(() -> {
                        loading.setVisibility(View.GONE);
                        list.addView(sheetStatusRow(getString(R.string.music_remote_load_failed)));
                        capSheetScrollHeight(scroll, list);
                    });
                }
            });
        }
        capSheetScrollHeight(scroll, list);
        wrap.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));
        dialog.setContentView(wrap);
        dialog.show();
    }

    private void addSongsToCloudPlaylist(Dialog dialog, RemotePlaylist playlist, List<SongInfo> songs) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.refreshDataSourceAuth();
                app.dataSource.addSongsToUserPlaylist(playlist, new ArrayList<>(songs));
                runOnUiThread(() -> {
                    dialog.dismiss();
                    GlassToast.makeText(this,
                            getString(R.string.music_added_to_playlist, playlist.name),
                            GlassToast.LENGTH_SHORT).show();
                    exitSelectionMode();
                });
            } catch (Exception exception) {
                android.util.Log.w("PlaylistDetail", "cloud playlist add failed", exception);
                runOnUiThread(() -> GlassToast.makeText(this, R.string.music_cloud_add_failed,
                        GlassToast.LENGTH_SHORT).show());
            }
        });
    }

    private TextView sheetSectionLabel(int textRes) {
        TextView label = new TextView(this);
        label.setText(textRes);
        label.setTextColor(getColor(R.color.text_hint));
        label.setTextSize(11.5f);
        label.setPadding(0, dp(14), 0, dp(2));
        return label;
    }

    private TextView sheetStatusRow(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.text_hint));
        view.setTextSize(13);
        view.setPadding(0, dp(12), 0, dp(12));
        return view;
    }

    private LinearLayout sheetPlaylistRow(CharSequence name, CharSequence count, Runnable action) {
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
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView countView = new TextView(this);
        countView.setText(count);
        countView.setTextSize(11.5f);
        countView.setTextColor(getColor(R.color.text_hint));
        row.addView(countView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    /** 列表超过 400dp 时把滚动区压到上限，弹层不顶出屏幕。 */
    private void capSheetScrollHeight(android.widget.ScrollView scroll, LinearLayout list) {
        scroll.post(() -> {
            int cap = dp(400);
            if (list.getHeight() > cap) {
                scroll.getLayoutParams().height = cap;
                scroll.requestLayout();
            }
        });
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

    private SongInfo songFromDownload(DownloadedSong downloaded) {
        if (downloaded == null || downloaded.localPath == null || !new File(downloaded.localPath).isFile()) {
            return null;
        }
        SongInfo song = new SongInfo();
        song.hash = downloaded.hash;
        song.title = downloaded.title;
        song.artist = downloaded.artist;
        song.album = downloaded.album;
        song.duration = downloaded.duration;
        song.imgUrl = downloaded.imgUrl;
        song.localPath = downloaded.localPath;
        song.vipRequired = false;
        return song;
    }

    private static final String[] QUALITY_VALUES = {
            DownloadManager.QUALITY_STANDARD,
            DownloadManager.QUALITY_HIGH,
            DownloadManager.QUALITY_LOSSLESS
    };

    private interface QualityCallback { void onPicked(String quality); }

    private void showQualityPicker(String title, QualityCallback onPicked) {
        String[] labels = {
                getString(R.string.music_download_quality_standard),
                getString(R.string.music_download_quality_high),
                getString(R.string.music_download_quality_lossless)
        };
        final int[] selected = {0};
        new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, 0, (d, which) -> selected[0] = which)
                .setPositiveButton(R.string.music_download,
                        (d, w) -> onPicked.onPicked(QUALITY_VALUES[selected[0]]))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startBatchDownload(List<SongInfo> songs, String quality) {
        List<SongInfo> toDownload = new ArrayList<>();
        for (SongInfo song : songs) {
            if (song.hash != null && !app.downloads.exists(song.hash) && !app.downloads.isDownloading(song.hash)) {
                toDownload.add(song);
            }
        }
        if (toDownload.isEmpty()) {
            GlassToast.makeText(this, R.string.music_download_already_exists, GlassToast.LENGTH_SHORT).show();
            return;
        }
        GlassToast.makeText(this, getString(R.string.music_download_batch_started, toDownload.size()),
                GlassToast.LENGTH_SHORT).show();
        final int total = toDownload.size();
        final int[] completed = {0};
        final int[] index = {0};
        downloadNext(toDownload, index, completed, total, quality);
    }

    private void downloadNext(List<SongInfo> songs, int[] index, int[] completed,
                              int total, String quality) {
        if (index[0] >= songs.size()) {
            GlassToast.makeText(this,
                    getString(R.string.music_download_batch_done_with_path, completed[0], total,
                            app.downloads.downloadsDir().getAbsolutePath()),
                    GlassToast.LENGTH_LONG).show();
            return;
        }
        SongInfo song = songs.get(index[0]);
        app.downloads.enqueue(song, quality,
                (s, state, done, totalBytes, msg) -> {
                    if (state == DownloadManager.State.COMPLETED
                            || state == DownloadManager.State.FAILED
                            || state == DownloadManager.State.CANCELLED) {
                        if (state == DownloadManager.State.COMPLETED) {
                            completed[0]++;
                        }
                        index[0]++;
                        downloadNext(songs, index, completed, total, quality);
                    }
                });
    }

    private String displayPlaylistName(String name) {
        if ("Favorites".equals(name)) return getString(R.string.music_playlist_favorites);
        if ("Listen Later".equals(name)) return getString(R.string.music_playlist_listen_later);
        if ("Recently Played".equals(name)) return getString(R.string.music_recent);
        return name;
    }

    private String safeTitle(SongInfo song) {
        return song == null || song.title == null ? "" : song.title;
    }

    private String songKey(SongInfo song) {
        if (song == null) {
            return "";
        }
        if (song.hash != null && !song.hash.isEmpty()) {
            return song.hash;
        }
        String title = song.title == null ? "" : song.title;
        String artist = song.artist == null ? "" : song.artist;
        return title + "|" + artist + "|" + song.duration;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void updateMiniPlayer() {
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
            if (miniSourcePill != null) {
                miniSourcePill.setOnClickListener(v -> {
                    if (app.player.currentSong() != null
                            && app.player.getState() != MusicPlayer.State.IDLE) {
                        startActivity(new Intent(this, MusicPlayerActivity.class)
                                .putExtra(MusicPlayerActivity.EXTRA_OPEN_QUEUE, true));
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
                    }
                });
            }
            miniPrev.setOnClickListener(v -> app.player.previous());
            miniNext.setOnClickListener(v -> app.player.next());
            miniCenter.setOnClickListener(v -> {
                SongInfo cur = app.player.currentSong();
                if (cur != null && app.player.getState() != MusicPlayer.State.IDLE) {
                    startActivity(new Intent(this, MusicPlayerActivity.class));
                }
            });
        }

        SongInfo current = app.player.currentSong();
        boolean active = current != null && app.player.getState() != MusicPlayer.State.IDLE;
        if (active) {
            miniTitle.setText(current.title);
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
            miniTitle.setText("");
            miniArtist.setText("");
            if (miniSourcePill != null) miniSourcePill.setVisibility(View.GONE);
            showMiniTextIcon(null);
            miniPrev.setEnabled(false);
            miniNext.setEnabled(false);
            miniPrev.setAlpha(0.4f);
            miniNext.setAlpha(0.4f);
        }
        updateMiniPlayIcon();
    }

    /** 迷你条播放键状态：播放中显示暂停，否则显示播放（单色，原型 .mini）。 */
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

    @Override public void onStateChanged(MusicPlayer.State s) { updateMiniPlayer(); }
    @Override public void onProgressChanged(int c, int t) {}
    @Override public void onSongChanged(SongInfo song) { updateMiniPlayer(); }
    @Override public void onError(String msg) {
        // 播放页在前台时由播放页统一提示；这里只在播放页不在时兜底，避免重复弹窗
        if (!MusicPlayerActivity.sPlayerForeground) {
            MusicPlayerActivity.showPlaybackErrorToast(this, msg);
        }
    }

    private static class PlaylistSongAdapter extends RecyclerView.Adapter<PlaylistSongAdapter.VH> {
        /** 行封面缩略图内存缓存（url → bitmap）。 */
        private static final android.util.LruCache<String, Bitmap> COVER_CACHE =
                new android.util.LruCache<>(64);

        private final List<SongInfo> items;
        private final Set<String> selectedKeys;
        private final OnSongClick clickListener;
        private final OnSongClick selectionListener;
        private final OnSongClick menuListener;
        private final OnStartDrag dragListener;
        private boolean selectionMode;
        private boolean customSortMode;

        interface OnSongClick { void onClick(SongInfo song); }
        interface OnStartDrag { void onStartDrag(RecyclerView.ViewHolder holder); }

        PlaylistSongAdapter(List<SongInfo> items, Set<String> selectedKeys,
                            OnSongClick clickListener, OnSongClick selectionListener,
                            OnSongClick menuListener, OnStartDrag dragListener) {
            this.items = items;
            this.selectedKeys = selectedKeys;
            this.clickListener = clickListener;
            this.selectionListener = selectionListener;
            this.menuListener = menuListener;
            this.dragListener = dragListener;
        }

        void setSelectionMode(boolean selectionMode) {
            this.selectionMode = selectionMode;
        }

        void setCustomSortMode(boolean customSortMode) {
            this.customSortMode = customSortMode;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_playlist_song_editable, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            SongInfo song = items.get(position);
            applyDisplayTitle(song, holder.title, holder.artist);
            holder.vipBadge.setVisibility(song.vipRequired ? View.VISIBLE : View.GONE);
            holder.checkbox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            holder.checkbox.setChecked(selectedKeys.contains(songKey(song)));
            holder.dragHandle.setVisibility(customSortMode ? View.VISIBLE : View.GONE);
            // 选择模式行尾不显示播放/更多（对齐概念版样式）
            holder.playButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            holder.moreButton.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
            holder.itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    selectionListener.onClick(song);
                } else {
                    clickListener.onClick(song);
                }
            });
            holder.checkbox.setOnClickListener(v -> selectionListener.onClick(song));
            // 行内迷你播放钮：与整行点击一致，从这首歌起播
            holder.playButton.setOnClickListener(v -> {
                if (selectionMode) {
                    selectionListener.onClick(song);
                } else {
                    clickListener.onClick(song);
                }
            });
            // 行内“…”：歌单操作底部弹层
            holder.moreButton.setOnClickListener(v -> menuListener.onClick(song));
            holder.dragHandle.setOnTouchListener((view, event) -> {
                if (customSortMode && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    dragListener.onStartDrag(holder);
                    return true;
                }
                return false;
            });
            loadRowCover(holder.cover, song);
        }

        /** 云歌单歌曲常是“歌手 - 歌名.mp3”整体串，展示时拆成 歌名 + 歌手 两行。 */
        private static void applyDisplayTitle(SongInfo song, TextView titleView, TextView artistView) {
            String title = song.title == null ? "" : song.title;
            String artist = song.artist == null ? "" : song.artist;
            if (artist.isEmpty()) {
                int split = title.indexOf(" - ");
                if (split > 0) {
                    artist = title.substring(0, split);
                    title = title.substring(split + 3);
                }
            }
            titleView.setText(stripAudioExtension(title));
            artistView.setText(artist);
            artistView.setVisibility(artist.isEmpty() ? View.GONE : View.VISIBLE);
        }

        private static String stripAudioExtension(String title) {
            int dot = title.lastIndexOf('.');
            if (dot > 0) {
                String ext = title.substring(dot + 1).toLowerCase(Locale.US);
                if (ext.equals("mp3") || ext.equals("flac") || ext.equals("m4a")
                        || ext.equals("wav") || ext.equals("ape") || ext.equals("ogg")) {
                    return title.substring(0, dot);
                }
            }
            return title;
        }

        /** 行封面：有 imgUrl 异步加载缩略图，无图保持 ♪ 占位。 */
        private static void loadRowCover(ImageView view, SongInfo song) {
            String url = song.imgUrl == null ? "" : song.imgUrl;
            if (url.isEmpty()) {
                view.setVisibility(View.GONE);
                return;
            }
            Bitmap cached = COVER_CACHE.get(url);
            if (cached != null) {
                view.setImageBitmap(cached);
                view.setVisibility(View.VISIBLE);
                return;
            }
            view.setTag(url);
            view.setVisibility(View.GONE);
            Executors.newSingleThreadExecutor().execute(() -> {
                Bitmap bitmap = null;
                try (InputStream input = new URL(url).openStream()) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    bitmap = BitmapFactory.decodeStream(input, null, options);
                } catch (Exception ignored) {
                }
                if (bitmap == null) return;
                final Bitmap loaded = bitmap;
                COVER_CACHE.put(url, loaded);
                view.post(() -> {
                    if (url.equals(view.getTag())) {
                        view.setImageBitmap(loaded);
                        view.setVisibility(View.VISIBLE);
                    }
                });
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private static String songKey(SongInfo song) {
            if (song == null) {
                return "";
            }
            if (song.hash != null && !song.hash.isEmpty()) {
                return song.hash;
            }
            String title = song.title == null ? "" : song.title;
            String artist = song.artist == null ? "" : song.artist;
            return title + "|" + artist + "|" + song.duration;
        }

        static class VH extends RecyclerView.ViewHolder {
            final CheckBox checkbox;
            final TextView title;
            final TextView artist;
            final TextView vipBadge;
            final TextView icon;
            final ImageView cover;
            final ImageButton playButton;
            final ImageButton moreButton;
            final ImageButton dragHandle;

            VH(View view) {
                super(view);
                checkbox = view.findViewById(R.id.song_row_checkbox);
                title = view.findViewById(R.id.song_row_title);
                artist = view.findViewById(R.id.song_row_artist);
                vipBadge = view.findViewById(R.id.song_row_vip_badge);
                icon = view.findViewById(R.id.song_row_icon);
                cover = view.findViewById(R.id.song_row_cover);
                playButton = view.findViewById(R.id.song_row_play);
                moreButton = view.findViewById(R.id.song_row_more);
                dragHandle = view.findViewById(R.id.song_row_drag_handle);
            }
        }
    }
}
