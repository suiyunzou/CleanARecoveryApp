package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.SystemUiHelper;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.player.MusicPlayer;

import java.util.List;
import java.util.concurrent.Executors;

public final class MusicHomeActivity extends Activity implements MusicPlayer.Callback {

    private MusicApp app;
    private ImageButton loginButton;
    private RecyclerView recList;
    private RecyclerView recentList;
    private LinearLayout playlistContainer;
    private View miniPlayer;
    private TextView miniTitle, miniArtist, miniIcon;
    private ImageButton miniPlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_music_home);

        app = MusicApp.init(this);
        app.player.addCallback(this);

        bindViews();
        loadRecommendations();
        loadRecentPlays();
        loadPlaylists();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLoginState();
        loadRecentPlays();
        loadPlaylists();
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
        recentList = findViewById(R.id.music_home_recent_list);
        playlistContainer = findViewById(R.id.music_playlist_container);

        recList.setLayoutManager(new LinearLayoutManager(this));
        recentList.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.music_back_button).setOnClickListener(v -> finish());
        findViewById(R.id.music_search_bar).setOnClickListener(v ->
                startActivity(new Intent(this, MusicSearchActivity.class)));

        loginButton.setOnClickListener(v ->
                startActivity(new Intent(this, MusicLoginActivity.class)));
    }

    private void refreshLoginState() {
        loginButton.setColorFilter(app.auth.isLoggedIn()
                ? getResources().getColor(R.color.status_success, getTheme())
                : getResources().getColor(R.color.text_secondary, getTheme()));
    }

    private void loadRecommendations() {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<SongInfo> songs = app.dataSource.getRecommendations(1);
                new Handler(Looper.getMainLooper()).post(() ->
                        recList.setAdapter(new SongListAdapter(songs, this::onSongClicked)));
            } catch (Exception ignored) {}
        });
    }

    private void loadRecentPlays() {
        List<SongInfo> recent = app.playlists.getRecentPlays(10);
        if (recent.isEmpty()) {
            findViewById(R.id.music_recent_label).setVisibility(View.GONE);
            recentList.setVisibility(View.GONE);
        } else {
            findViewById(R.id.music_recent_label).setVisibility(View.VISIBLE);
            recentList.setVisibility(View.VISIBLE);
            recentList.setAdapter(new SongListAdapter(recent, this::onSongClicked));
        }
    }

    private void loadPlaylists() {
        playlistContainer.removeAllViews();
        List<String> names = app.playlists.listPlaylists();
        for (String name : names) {
            int count = app.playlists.songCount(name);
            addPlaylistRow(name, count + " songs");
        }
        // "New playlist" row
        addPlaylistRow(getString(R.string.music_new_playlist), null);
    }

    private void addPlaylistRow(String title, String subtitle) {
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
            if (subtitle != null) {
                Intent intent = new Intent(this, PlaylistDetailActivity.class);
                intent.putExtra("playlist_name", title);
                startActivity(intent);
            } else {
                promptCreatePlaylist();
            }
        });
        playlistContainer.addView(row);
    }

    private void promptCreatePlaylist() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setHint(R.string.music_create_playlist_hint);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_create_playlist_title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        app.playlists.createPlaylist(name);
                        loadPlaylists();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void onSongClicked(SongInfo song) {
        app.playlists.addRecentPlay(song);
        app.player.playSingle(song);
        startActivity(new Intent(this, MusicPlayerActivity.class));
    }

    private void updateMiniPlayer() {
        SongInfo current = app.player.currentSong();
        if (current != null && app.player.getState() != MusicPlayer.State.IDLE) {
            miniPlayer.setVisibility(View.VISIBLE);
            if (miniTitle == null) {
                LayoutInflater.from(this).inflate(R.layout.bar_mini_player, (ViewGroup) miniPlayer, true);
                miniTitle = miniPlayer.findViewById(R.id.mini_player_title);
                miniArtist = miniPlayer.findViewById(R.id.mini_player_artist);
                miniIcon = miniPlayer.findViewById(R.id.mini_player_icon);
                miniPlay = miniPlayer.findViewById(R.id.mini_player_play);
                miniPlay.setOnClickListener(v -> app.player.toggle());
                miniPlayer.findViewById(R.id.mini_player_next).setOnClickListener(v -> app.player.next());
                miniPlayer.setOnClickListener(v ->
                        startActivity(new Intent(this, MusicPlayerActivity.class)));
            }
            miniTitle.setText(current.title);
            miniArtist.setText(current.artist);
            miniIcon.setText(current.title.isEmpty() ? "♪" : String.valueOf(current.title.charAt(0)).toUpperCase());
            miniPlay.setColorFilter(
                    app.player.getState() == MusicPlayer.State.PLAYING
                            ? getResources().getColor(R.color.brand_primary, getTheme())
                            : getResources().getColor(R.color.text_secondary, getTheme()));
        } else {
            miniPlayer.setVisibility(View.GONE);
        }
    }

    // MusicPlayer.Callback
    @Override public void onStateChanged(MusicPlayer.State s) { updateMiniPlayer(); }
    @Override public void onProgressChanged(int c, int t) {}
    @Override public void onSongChanged(SongInfo song) { updateMiniPlayer(); }
    @Override public void onError(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ---- Adapter ----

    public static class SongListAdapter extends RecyclerView.Adapter<SongListAdapter.VH> {
        private final List<SongInfo> items;
        private final OnSongClick listener;
        public interface OnSongClick { void onClick(SongInfo s); }
        public SongListAdapter(List<SongInfo> items, OnSongClick listener) {
            this.items = items; this.listener = listener;
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
        }
        @Override public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            TextView title, artist, duration, vipBadge, icon;
            VH(View v) {
                super(v);
                title = v.findViewById(R.id.song_row_title);
                artist = v.findViewById(R.id.song_row_artist);
                duration = v.findViewById(R.id.song_row_duration);
                vipBadge = v.findViewById(R.id.song_row_vip_badge);
                icon = v.findViewById(R.id.song_row_icon);
            }
        }
    }
}
