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
import com.example.cleanrecovery.SystemUiHelper;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.SongInfo;

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
                this::onMoveUp, this::onMoveDown, this::onRemove);
        list.setAdapter(adapter);

        findViewById(R.id.playlist_detail_back).setOnClickListener(v -> finish());
        findViewById(R.id.playlist_detail_delete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.playlist_detail_edit).setOnClickListener(v -> toggleEditMode());
        findViewById(R.id.playlist_detail_rename).setOnClickListener(v -> promptRename());
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
        private boolean editMode = false;

        interface OnSongClick { void onClick(SongInfo s); }
        interface OnPositionAction { void onAction(int pos); }

        EditableSongAdapter(List<SongInfo> items, OnSongClick clickListener,
                            OnPositionAction moveUp, OnPositionAction moveDown,
                            OnPositionAction remove) {
            this.items = items;
            this.clickListener = clickListener;
            this.moveUp = moveUp;
            this.moveDown = moveDown;
            this.remove = remove;
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
                h.itemView.setOnClickListener(v -> clickListener.onClick(s));
            }
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView title, artist, duration, vipBadge, icon;
            View editControls;
            ImageButton upBtn, downBtn, removeBtn;
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
            }
        }
    }
}
