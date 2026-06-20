package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.download.DownloadManager;

import java.util.ArrayList;
import java.util.List;

public final class PlaylistDetailActivity extends Activity {

    private MusicApp app;
    private String playlistName;
    private RecyclerView list;
    private TextView emptyView;
    private TextView titleView;
    private EditableSongAdapter adapter;
    private final List<SongInfo> items = new ArrayList<>();
    private boolean editMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_playlist_detail);
        app = MusicApp.init(this);
        playlistName = getIntent().getStringExtra("playlist_name");
        if (playlistName == null) { finish(); return; }

        titleView = findViewById(R.id.playlist_detail_title);
        titleView.setText(displayPlaylistName(playlistName));
        list = findViewById(R.id.playlist_detail_list);
        emptyView = findViewById(R.id.playlist_detail_empty);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new EditableSongAdapter(items, this::onSongClicked,
                this::onMoveUp, this::onMoveDown, this::onRemove, this::onDownloadSingle);
        list.setAdapter(adapter);

        findViewById(R.id.playlist_detail_back).setOnClickListener(v -> finish());
        findViewById(R.id.playlist_detail_delete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.playlist_detail_edit).setOnClickListener(v -> toggleEditMode());
        findViewById(R.id.playlist_detail_rename).setOnClickListener(v -> promptRename());
        findViewById(R.id.playlist_detail_download).setOnClickListener(v -> downloadAll());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        items.clear();
        items.addAll(app.playlists.getSongs(playlistName));
        adapter.setEditMode(editMode);
        adapter.notifyDataSetChanged();
        boolean none = items.isEmpty();
        emptyView.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    private void toggleEditMode() {
        editMode = !editMode;
        adapter.setEditMode(editMode);
        adapter.notifyDataSetChanged();
        ImageButton editBtn = findViewById(R.id.playlist_detail_edit);
        editBtn.setColorFilter(editMode
                ? getResources().getColor(R.color.brand_primary, getTheme())
                : getResources().getColor(R.color.text_secondary, getTheme()));
        if (!editMode) {
            // Persist the current order when leaving edit mode
            app.playlists.setOrder(playlistName, items);
            Toast.makeText(this, R.string.music_playlist_order_saved, Toast.LENGTH_SHORT).show();
        }
    }

    private void onMoveUp(int pos) {
        if (pos <= 0) return;
        SongInfo s = items.remove(pos);
        items.add(pos - 1, s);
        adapter.notifyItemMoved(pos, pos - 1);
        app.playlists.setOrder(playlistName, items);
    }

    private void onMoveDown(int pos) {
        if (pos >= items.size() - 1) return;
        SongInfo s = items.remove(pos);
        items.add(pos + 1, s);
        adapter.notifyItemMoved(pos, pos + 1);
        app.playlists.setOrder(playlistName, items);
    }

    private void onRemove(int pos) {
        if (pos < 0 || pos >= items.size()) return;
        SongInfo removed = items.remove(pos);
        app.playlists.removeSong(playlistName, removed);
        adapter.notifyItemRemoved(pos);
        adapter.notifyItemRangeChanged(pos, items.size() - pos);
        if (items.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            list.setVisibility(View.GONE);
        }
        Toast.makeText(this, R.string.music_removed_from_playlist, Toast.LENGTH_SHORT).show();
    }

    private void promptRename() {
        if (app.playlists.isProtected(playlistName)) {
            Toast.makeText(this, R.string.music_playlist_rename_protected, Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setText(playlistName);
        input.setSelection(playlistName.length());
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_rename_playlist_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty() || newName.equals(playlistName)) return;
                    if (app.playlists.renamePlaylist(playlistName, newName)) {
                        playlistName = newName;
                        titleView.setText(displayPlaylistName(playlistName));
                        Toast.makeText(this,
                                getString(R.string.music_playlist_renamed, displayPlaylistName(playlistName)),
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, R.string.music_playlist_rename_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDelete() {
        if (app.playlists.isProtected(playlistName)) {
            Toast.makeText(this, R.string.music_playlist_delete_protected, Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_delete_playlist_title)
                .setMessage(getString(R.string.music_delete_playlist_confirm, displayPlaylistName(playlistName)))
                .setPositiveButton(R.string.music_delete, (d, w) -> {
                    app.playlists.deletePlaylist(playlistName);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onSongClicked(SongInfo song) {
        if (editMode) return; // ignore taps while reordering
        if (song.vipRequired && !app.auth.isLoggedIn()) {
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
            return;
        }
        app.playlists.addRecentPlay(song);
        app.player.play(items, items.indexOf(song));
        startActivity(new Intent(this, MusicPlayerActivity.class));
    }

    // ---- Download (single + batch) --------------------------------------

    /** 质量选项数组（与 QUALITY_* 常量一一对应）。 */
    private static final String[] QUALITY_VALUES = {
            DownloadManager.QUALITY_STANDARD,
            DownloadManager.QUALITY_HIGH,
            DownloadManager.QUALITY_LOSSLESS
    };

    /** 质量选择回调（避免使用 java.util.function.Consumer 以兼容 minSdk 23）。 */
    private interface QualityCallback { void onPicked(String quality); }

    /** 弹出音质选择对话框，选择后回调 quality。默认选中标准音质。 */
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

    private void onDownloadSingle(SongInfo song) {
        if (song == null || song.hash == null) return;
        if (app.downloads.exists(song.hash)) {
            Toast.makeText(this, R.string.music_download_already_exists, Toast.LENGTH_SHORT).show();
            return;
        }
        if (app.downloads.isDownloading(song.hash)) {
            Toast.makeText(this, getString(R.string.music_download_single_started, song.title),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        showQualityPicker(getString(R.string.music_download_quality_title),
                quality -> startSingleDownload(song, quality));
    }

    private void startSingleDownload(SongInfo song, String quality) {
        Toast.makeText(this, getString(R.string.music_download_single_started, song.title),
                Toast.LENGTH_SHORT).show();
        app.downloads.enqueue(song, quality,
                (s, state, done, total, msg) -> {
                    if (state == DownloadManager.State.COMPLETED) {
                        Toast.makeText(this,
                                getString(R.string.music_download_single_done_with_path,
                                        song.title, app.downloads.downloadsDir().getAbsolutePath()),
                                Toast.LENGTH_LONG).show();
                    } else if (state == DownloadManager.State.FAILED) {
                        Toast.makeText(this,
                                getString(R.string.music_download_single_failed, song.title,
                                        msg != null ? msg : ""),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void downloadAll() {
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.music_download_batch_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        // Filter out already-downloaded and currently-downloading songs
        List<SongInfo> toDownload = new ArrayList<>();
        for (SongInfo s : items) {
            if (!app.downloads.exists(s.hash) && !app.downloads.isDownloading(s.hash)) {
                toDownload.add(s);
            }
        }
        if (toDownload.isEmpty()) {
            Toast.makeText(this, R.string.music_download_already_exists, Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_download_all)
                .setMessage(getString(R.string.music_download_all_confirm, toDownload.size()))
                .setPositiveButton(R.string.music_download,
                        (d, w) -> showQualityPicker(getString(R.string.music_download_quality_title),
                                quality -> startBatchDownload(toDownload, quality)))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void startBatchDownload(List<SongInfo> songs, String quality) {
        Toast.makeText(this, getString(R.string.music_download_batch_started, songs.size()),
                Toast.LENGTH_SHORT).show();
        final int total = songs.size();
        final int[] completed = {0};
        final int[] index = {0};
        downloadNext(songs, index, completed, total, quality);
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

    /** Adapter that can toggle an edit mode exposing move-up / move-down / remove controls. */
    private static class EditableSongAdapter extends RecyclerView.Adapter<EditableSongAdapter.VH> {
        private final List<SongInfo> items;
        private final OnSongClick clickListener;
        private final OnPositionAction moveUp;
        private final OnPositionAction moveDown;
        private final OnPositionAction remove;
        private final OnSongDownload downloadListener;
        private boolean editMode = false;

        interface OnSongClick { void onClick(SongInfo s); }
        interface OnPositionAction { void onAction(int pos); }
        interface OnSongDownload { void onDownload(SongInfo s); }

        EditableSongAdapter(List<SongInfo> items, OnSongClick clickListener,
                            OnPositionAction moveUp, OnPositionAction moveDown,
                            OnPositionAction remove, OnSongDownload downloadListener) {
            this.items = items;
            this.clickListener = clickListener;
            this.moveUp = moveUp;
            this.moveDown = moveDown;
            this.remove = remove;
            this.downloadListener = downloadListener;
        }

        void setEditMode(boolean mode) { this.editMode = mode; }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_playlist_song_editable, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int pos) {
            SongInfo s = items.get(pos);
            h.title.setText(s.title);
            h.artist.setText(s.artist);
            h.duration.setText(s.durationFormatted());
            h.icon.setText(s.title.isEmpty() ? "♪"
                    : String.valueOf(s.title.charAt(0)).toUpperCase());
            h.vipBadge.setVisibility(s.vipRequired ? View.VISIBLE : View.GONE);

            if (editMode) {
                h.editControls.setVisibility(View.VISIBLE);
                h.downloadBtn.setVisibility(View.GONE);
                h.upBtn.setEnabled(pos > 0);
                h.downBtn.setEnabled(pos < items.size() - 1);
                h.upBtn.setOnClickListener(v -> {
                    int p = h.getBindingAdapterPosition();
                    if (p != RecyclerView.NO_POSITION) moveUp.onAction(p);
                });
                h.downBtn.setOnClickListener(v -> {
                    int p = h.getBindingAdapterPosition();
                    if (p != RecyclerView.NO_POSITION) moveDown.onAction(p);
                });
                h.removeBtn.setOnClickListener(v -> {
                    int p = h.getBindingAdapterPosition();
                    if (p != RecyclerView.NO_POSITION) remove.onAction(p);
                });
                h.itemView.setOnClickListener(null);
            } else {
                h.editControls.setVisibility(View.GONE);
                h.downloadBtn.setVisibility(View.VISIBLE);
                h.downloadBtn.setOnClickListener(v -> downloadListener.onDownload(s));
                h.itemView.setOnClickListener(v -> clickListener.onClick(s));
            }
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView title, artist, duration, vipBadge, icon;
            View editControls;
            ImageButton upBtn, downBtn, removeBtn, downloadBtn;
            VH(View v) {
                super(v);
                title = v.findViewById(R.id.song_row_title);
                artist = v.findViewById(R.id.song_row_artist);
                duration = v.findViewById(R.id.song_row_duration);
                vipBadge = v.findViewById(R.id.song_row_vip_badge);
                icon = v.findViewById(R.id.song_row_icon);
                editControls = v.findViewById(R.id.song_row_edit_controls);
                upBtn = v.findViewById(R.id.song_row_up);
                downBtn = v.findViewById(R.id.song_row_down);
                removeBtn = v.findViewById(R.id.song_row_remove);
                downloadBtn = v.findViewById(R.id.song_row_download);
            }
        }
    }
}
