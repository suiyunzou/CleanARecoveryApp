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
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.DownloadedSong;
import com.example.cleanrecovery.music.data.RemotePlaylist;
import com.example.cleanrecovery.music.data.SongInfo;
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
    private TextView titleView;
    private Button playAllButton;
    private View normalActions;
    private View searchActions;
    private View selectionActions;
    private View batchActions;
    private EditText searchInput;
    private TextView selectionCountView;
    private Button selectionActionButton;
    private PlaylistSongAdapter adapter;
    private ItemTouchHelper itemTouchHelper;

    private View miniPlayer;
    private TextView miniTitle;
    private TextView miniIcon;
    private ImageView miniCover;
    private ImageButton miniPrev;
    private ImageButton miniNext;
    private View miniCenter;

    private final List<SongInfo> items = new ArrayList<>();
    private final List<SongInfo> visibleItems = new ArrayList<>();
    private final Set<String> selectedKeys = new HashSet<>();
    private String searchQuery = "";
    private boolean selectionMode;
    private boolean customSortMode;

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
        titleView = findViewById(R.id.playlist_detail_title);
        titleView.setText(displayPlaylistName(playlistName));
        playAllButton = findViewById(R.id.playlist_detail_play_all);
        normalActions = findViewById(R.id.playlist_detail_normal_actions);
        searchActions = findViewById(R.id.playlist_detail_search_actions);
        selectionActions = findViewById(R.id.playlist_detail_selection_actions);
        batchActions = findViewById(R.id.playlist_detail_batch_actions);
        searchInput = findViewById(R.id.playlist_detail_search_input);
        selectionCountView = findViewById(R.id.playlist_detail_selection_count);
        selectionActionButton = findViewById(R.id.playlist_detail_selection_cancel);
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
        findViewById(R.id.playlist_detail_more).setOnClickListener(v -> enterSelectionMode());
        findViewById(R.id.playlist_detail_search_cancel).setOnClickListener(v -> exitSearchMode());
        selectionActionButton.setOnClickListener(v -> {
            if (customSortMode) {
                exitCustomSortMode(true);
            } else {
                exitSelectionMode();
            }
        });
        playAllButton.setOnClickListener(v -> playAll());

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
                this::toggleSelection, this::startDrag);
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
        refreshVisibleItems();
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
                    items.addAll(songs);
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
        playAllButton.setText(getString(R.string.music_playlist_play_all_count, items.size()));
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

    private void enterSelectionMode() {
        exitCustomSortMode(false);
        selectionMode = true;
        selectedKeys.clear();
        normalActions.setVisibility(View.GONE);
        searchActions.setVisibility(View.GONE);
        selectionActions.setVisibility(View.VISIBLE);
        batchActions.setVisibility(View.VISIBLE);
        selectionActionButton.setText(R.string.file_browser_cancel_select);
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
        adapter.setSelectionMode(false);
        adapter.notifyDataSetChanged();
        updateSelectionCount();
    }

    private void updateSelectionCount() {
        if (customSortMode) {
            selectionCountView.setText(R.string.music_sort_custom_done_hint);
            return;
        }
        selectionCountView.setText(getString(R.string.music_playlist_selected_count, selectedKeys.size()));
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
        reload();
    }

    private void sortByTitle() {
        exitCustomSortMode(false);
        Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(items, (left, right) -> collator.compare(safeTitle(left), safeTitle(right)));
        refreshVisibleItems();
    }

    private void sortByRecentPlay() {
        exitCustomSortMode(false);
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
        refreshVisibleItems();
    }

    private void enterCustomSortMode() {
        exitSelectionMode();
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
        if (!customSortMode && !remoteMode && !downloadedMode && searchQuery.trim().isEmpty()) {
            app.playlists.setOrder(playlistName, items);
            if (showToast) {
                Toast.makeText(this, R.string.music_playlist_order_saved, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startDrag(RecyclerView.ViewHolder holder) {
        if (customSortMode && itemTouchHelper != null) {
            itemTouchHelper.startDrag(holder);
        }
    }

    private void playAll() {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.music_queue_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        SongInfo first = items.get(0);
        if (!canPlay(first)) {
            return;
        }
        app.playlists.addRecentPlay(first);
        rememberCurrentMenu();
        app.player.play(new ArrayList<>(items), 0, playSource(), displayPlaylistName(playlistName));
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
        app.player.play(queue, index, playSource(), displayPlaylistName(playlistName));
        startActivity(new Intent(this, MusicPlayerActivity.class));
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
            Toast.makeText(this, R.string.music_playlist_select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        app.player.playNext(selected);
        Toast.makeText(this, getString(R.string.music_next_play_added, selected.size()), Toast.LENGTH_SHORT).show();
        exitSelectionMode();
    }

    private void downloadSelected() {
        List<SongInfo> selected = selectedSongs();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.music_playlist_select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        showQualityPicker(getString(R.string.music_download_quality_title),
                quality -> startBatchDownload(selected, quality));
        exitSelectionMode();
    }

    private void addSelectedToPlaylist() {
        List<SongInfo> selected = selectedSongs();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.music_playlist_select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        showAddToPlaylistDialog(selected);
    }

    private void removeSelected() {
        List<SongInfo> selected = selectedSongs();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.music_playlist_select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (downloadedMode) {
            Toast.makeText(this, R.string.music_playlist_readonly_action, Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(this, R.string.music_removed_from_playlist, Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(this, R.string.music_cloud_delete_done, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                runOnUiThread(() ->
                        Toast.makeText(this, R.string.music_cloud_delete_failed, Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showAddToPlaylistDialog(List<SongInfo> songs) {
        List<String> names = new ArrayList<>();
        names.add("Listen Later");
        if (names.isEmpty()) {
            Toast.makeText(this, R.string.music_playlist_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] displayNames = new String[names.size()];
        for (int i = 0; i < names.size(); i++) {
            displayNames[i] = displayPlaylistName(names.get(i));
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_add_to_playlist)
                .setItems(displayNames, (d, w) -> {
                    for (SongInfo song : songs) {
                        app.playlists.addSong(names.get(w), song);
                    }
                    Toast.makeText(this,
                            getString(R.string.music_added_to_playlist, displayNames[w]),
                            Toast.LENGTH_SHORT).show();
                    exitSelectionMode();
                })
                .show();
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
            Toast.makeText(this, R.string.music_download_already_exists, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, getString(R.string.music_download_batch_started, toDownload.size()),
                Toast.LENGTH_SHORT).show();
        final int total = toDownload.size();
        final int[] completed = {0};
        final int[] index = {0};
        downloadNext(toDownload, index, completed, total, quality);
    }

    private void downloadNext(List<SongInfo> songs, int[] index, int[] completed,
                              int total, String quality) {
        if (index[0] >= songs.size()) {
            Toast.makeText(this,
                    getString(R.string.music_download_batch_done_with_path, completed[0], total,
                            app.downloads.downloadsDir().getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
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
                }
            });
        }

        SongInfo current = app.player.currentSong();
        boolean active = current != null && app.player.getState() != MusicPlayer.State.IDLE;
        if (active) {
            String label = current.artist != null && !current.artist.isEmpty()
                    ? current.title + " - " + current.artist : current.title;
            miniTitle.setText(label);
            updateMiniArtwork(current);
            miniPrev.setEnabled(true);
            miniNext.setEnabled(true);
            miniPrev.setAlpha(1f);
            miniNext.setAlpha(1f);
        } else {
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

    @Override public void onStateChanged(MusicPlayer.State s) { updateMiniPlayer(); }
    @Override public void onProgressChanged(int c, int t) {}
    @Override public void onSongChanged(SongInfo song) { updateMiniPlayer(); }
    @Override public void onError(String msg) {
        Toast.makeText(this, getString(R.string.music_playback_failed), Toast.LENGTH_SHORT).show();
    }

    private static class PlaylistSongAdapter extends RecyclerView.Adapter<PlaylistSongAdapter.VH> {
        private final List<SongInfo> items;
        private final Set<String> selectedKeys;
        private final OnSongClick clickListener;
        private final OnSongClick selectionListener;
        private final OnStartDrag dragListener;
        private boolean selectionMode;
        private boolean customSortMode;

        interface OnSongClick { void onClick(SongInfo song); }
        interface OnStartDrag { void onStartDrag(RecyclerView.ViewHolder holder); }

        PlaylistSongAdapter(List<SongInfo> items, Set<String> selectedKeys,
                            OnSongClick clickListener, OnSongClick selectionListener,
                            OnStartDrag dragListener) {
            this.items = items;
            this.selectedKeys = selectedKeys;
            this.clickListener = clickListener;
            this.selectionListener = selectionListener;
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
            holder.title.setText(song.title);
            holder.artist.setText(song.artist);
            holder.duration.setText(song.durationFormatted());
            holder.icon.setText(song.title == null || song.title.isEmpty()
                    ? "♪" : String.valueOf(song.title.charAt(0)).toUpperCase());
            holder.vipBadge.setVisibility(song.vipRequired ? View.VISIBLE : View.GONE);
            holder.checkbox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
            holder.checkbox.setChecked(selectedKeys.contains(songKey(song)));
            holder.dragHandle.setVisibility(customSortMode ? View.VISIBLE : View.GONE);
            holder.itemView.setOnClickListener(v -> {
                if (selectionMode) {
                    selectionListener.onClick(song);
                } else {
                    clickListener.onClick(song);
                }
            });
            holder.checkbox.setOnClickListener(v -> selectionListener.onClick(song));
            holder.dragHandle.setOnTouchListener((view, event) -> {
                if (customSortMode && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    dragListener.onStartDrag(holder);
                    return true;
                }
                return false;
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
            final TextView duration;
            final TextView vipBadge;
            final TextView icon;
            final ImageButton dragHandle;

            VH(View view) {
                super(view);
                checkbox = view.findViewById(R.id.song_row_checkbox);
                title = view.findViewById(R.id.song_row_title);
                artist = view.findViewById(R.id.song_row_artist);
                duration = view.findViewById(R.id.song_row_duration);
                vipBadge = view.findViewById(R.id.song_row_vip_badge);
                icon = view.findViewById(R.id.song_row_icon);
                dragHandle = view.findViewById(R.id.song_row_drag_handle);
            }
        }
    }
}
