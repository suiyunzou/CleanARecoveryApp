package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

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
    private MusicHomeActivity.SongListAdapter adapter;
    private final List<SongInfo> items = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_playlist_detail);
        app = MusicApp.init(this);
        playlistName = getIntent().getStringExtra("playlist_name");
        if (playlistName == null) { finish(); return; }

        ((TextView) findViewById(R.id.playlist_detail_title)).setText(displayPlaylistName(playlistName));
        list = findViewById(R.id.playlist_detail_list);
        emptyView = findViewById(R.id.playlist_detail_empty);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MusicHomeActivity.SongListAdapter(items, this::onSongClicked);
        list.setAdapter(adapter);

        findViewById(R.id.playlist_detail_back).setOnClickListener(v -> finish());
        findViewById(R.id.playlist_detail_delete).setOnClickListener(v -> {
            app.playlists.deletePlaylist(playlistName);
            finish();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        items.clear();
        items.addAll(app.playlists.getSongs(playlistName));
        adapter.notifyDataSetChanged();
        boolean none = items.isEmpty();
        emptyView.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    private void onSongClicked(SongInfo song) {
        // 已登录时直接尝试播放（概念版 /v5/url 会带 token 解析 VIP URL）；
        // 未登录的 VIP 歌曲才提示登录/领取。
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
}
