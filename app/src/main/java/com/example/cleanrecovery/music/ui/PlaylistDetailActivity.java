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
        app = MusicApp.get();
        playlistName = getIntent().getStringExtra("playlist_name");
        if (playlistName == null) { finish(); return; }

        ((TextView) findViewById(R.id.playlist_detail_title)).setText(playlistName);
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
        app.playlists.addRecentPlay(song);
        app.player.play(items, items.indexOf(song));
        startActivity(new Intent(this, MusicPlayerActivity.class));
    }
}
