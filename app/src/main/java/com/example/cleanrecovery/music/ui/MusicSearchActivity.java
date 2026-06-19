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

    private int currentPage = 1;
    private boolean isLoading = false;
    private boolean hasMore = true;
    private String currentKeyword = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_music_search);
        app = MusicApp.init(this);

        input = findViewById(R.id.search_input);
        results = findViewById(R.id.search_results);
        empty = findViewById(R.id.search_empty);
        
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        results.setLayoutManager(layoutManager);
        adapter = new MusicHomeActivity.SongListAdapter(items, this::onSongClicked, this::onAddToPlaylist);
        results.setAdapter(adapter);

        results.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0 && !isLoading && hasMore) {
                    int visibleItemCount = layoutManager.getChildCount();
                    int totalItemCount = layoutManager.getItemCount();
                    int pastVisibleItems = layoutManager.findFirstVisibleItemPosition();

                    if ((visibleItemCount + pastVisibleItems) >= totalItemCount - 5) {
                        performSearch(currentKeyword, currentPage + 1);
                    }
                }
            }
        });

        findViewById(R.id.search_back_button).setOnClickListener(v -> finish());

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (s.length() >= 2) {
                    currentKeyword = s.toString();
                    performSearch(currentKeyword, 1);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void performSearch(String keyword, int page) {
        if (isLoading) return;
        isLoading = true;
        
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                List<SongInfo> found = app.dataSource.search(keyword, page);
                handler.post(() -> {
                    isLoading = false;
                    if (page == 1) {
                        items.clear();
                    }
                    if (found.isEmpty()) {
                        hasMore = false;
                    } else {
                        items.addAll(found);
                        currentPage = page;
                        hasMore = true;
                    }
                    adapter.notifyDataSetChanged();
                    boolean none = items.isEmpty();
                    empty.setVisibility(none ? View.VISIBLE : View.GONE);
                    results.setVisibility(none ? View.GONE : View.VISIBLE);
                });
            } catch (Exception e) {
                handler.post(() -> {
                    isLoading = false;
                    Toast.makeText(this, getString(R.string.music_search_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                });
            }
        });
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
                                    startActivity(new android.content.Intent(this, MusicLoginActivity.class));
                                }
                            })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        app.playlists.addRecentPlay(song);
        // 传入完整搜索结果列表作为播放队列，使上一首/下一首按钮可用
        int startIndex = items.indexOf(song);
        if (startIndex < 0) startIndex = 0;
        app.player.play(new ArrayList<>(items), startIndex);
        startActivity(new android.content.Intent(this, MusicPlayerActivity.class));
    }

    /** 将搜索结果中的歌曲添加到指定歌单，不打断当前播放。 */
    private void onAddToPlaylist(SongInfo song) {
        List<String> names = app.playlists.listPlaylists();
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
                    app.playlists.addSong(names.get(w), song);
                    Toast.makeText(this,
                            getString(R.string.music_added_to_playlist, displayNames[w]),
                            Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private String displayPlaylistName(String name) {
        if ("Favorites".equals(name)) return getString(R.string.music_playlist_favorites);
        if ("Listen Later".equals(name)) return getString(R.string.music_playlist_listen_later);
        if ("Recently Played".equals(name)) return getString(R.string.music_recent);
        return name;
    }
}
