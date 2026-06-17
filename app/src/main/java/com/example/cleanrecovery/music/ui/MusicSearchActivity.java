package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
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
import java.util.concurrent.Executors;

public final class MusicSearchActivity extends Activity {

    private MusicApp app;
    private EditText input;
    private RecyclerView results;
    private TextView empty;
    private MusicHomeActivity.SongListAdapter adapter;
    private final List<SongInfo> items = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_music_search);
        app = MusicApp.get();

        input = findViewById(R.id.search_input);
        results = findViewById(R.id.search_results);
        empty = findViewById(R.id.search_empty);
        results.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MusicHomeActivity.SongListAdapter(items, this::onSongClicked);
        results.setAdapter(adapter);

        findViewById(R.id.search_back_button).setOnClickListener(v -> finish());

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() >= 2) performSearch(s.toString());
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void performSearch(String keyword) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<SongInfo> found = app.dataSource.search(keyword, 1);
                handler.post(() -> {
                    items.clear();
                    items.addAll(found);
                    adapter.notifyDataSetChanged();
                    boolean none = items.isEmpty();
                    empty.setVisibility(none ? View.VISIBLE : View.GONE);
                    results.setVisibility(none ? View.GONE : View.VISIBLE);
                });
            } catch (Exception e) {
                handler.post(() ->
                        Toast.makeText(this, "Search failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void onSongClicked(SongInfo song) {
        if (song.vipRequired && !app.auth.isLoggedIn()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.music_vip_prompt_title)
                    .setMessage(R.string.music_vip_prompt)
                    .setPositiveButton(R.string.music_login, (d, w) ->
                            startActivity(new android.content.Intent(this, MusicLoginActivity.class)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        app.playlists.addRecentPlay(song);
        app.player.playSingle(song);
        startActivity(new android.content.Intent(this, MusicPlayerActivity.class));
    }
}
