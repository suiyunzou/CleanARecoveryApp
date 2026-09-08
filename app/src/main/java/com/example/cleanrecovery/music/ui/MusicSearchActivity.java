package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.player.MusicPlayer;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public final class MusicSearchActivity extends Activity {

    private static final String HISTORY_PREFS = "music_search_history";
    private static final String HISTORY_KEY = "keywords";
    private static final int HISTORY_MAX = 20;

    private MusicApp app;
    private EditText input;
    private RecyclerView results;
    private TextView empty;
    private View historyBox;
    private LinearLayout historyChips;
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
        historyBox = findViewById(R.id.search_history_box);
        historyChips = findViewById(R.id.search_history_chips);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        results.setLayoutManager(layoutManager);
        adapter = new MusicHomeActivity.SongListAdapter(items, this::onSongClicked, this::showSongActions);
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
        findViewById(R.id.search_history_clear).setOnClickListener(v -> {
            historyPrefs().edit().remove(HISTORY_KEY).apply();
            renderHistory();
        });

        // 输入法搜索键：记录历史并搜索
        input.setOnEditorActionListener((v, actionId, event) -> {
            String kw = input.getText().toString().trim();
            if (kw.length() >= 2) {
                addHistory(kw);
                currentKeyword = kw;
                performSearch(kw, 1);
            }
            return true;
        });

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 输入为空时展示搜索历史，输入时隐藏
                historyBox.setVisibility(s.length() == 0 ? View.VISIBLE : View.GONE);
                if (s.length() >= 2) {
                    currentKeyword = s.toString();
                    performSearch(currentKeyword, 1);
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        renderHistory();

        // 进入即聚焦搜索框并弹键盘：主页点搜索一步直达，不用再点一次输入框
        input.requestFocus();
        input.post(() -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            }
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
                    GlassToast.makeText(this, getString(R.string.music_search_failed, e.getMessage()), GlassToast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void onSongClicked(SongInfo song) {
        // 已登录时直接尝试播放（概念版 /v5/url 会带 token 解析 VIP URL）；
        // 未登录的 VIP 歌曲才提示登录/领取。
        if (song.vipRequired && !app.auth.isLoggedIn()) {
            new AlertDialog.Builder(this)
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
        addHistory(currentKeyword);
        app.playlists.addRecentPlay(song);
        app.player.playSearch(song, getString(R.string.music_search_source_fmt, currentKeyword));
        startActivity(new android.content.Intent(this, MusicPlayerActivity.class));
    }

    /** 结果行「⋯」菜单（对齐酷狗：非破坏性入口 + 收藏）。 */
    private void showSongActions(SongInfo song) {
        String[] options = {
                getString(R.string.music_play_next),
                getString(R.string.music_add_to_queue),
                getString(R.string.music_playlist_listen_later),
                getString(R.string.music_add_to_playlist)
        };
        new AlertDialog.Builder(this)
                .setTitle(song.title)
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0:
                            app.player.playNext(java.util.Collections.singletonList(song));
                            GlassToast.makeText(this, R.string.music_added_play_next, GlassToast.LENGTH_SHORT).show();
                            break;
                        case 1:
                            app.player.appendQueue(java.util.Collections.singletonList(song));
                            GlassToast.makeText(this, R.string.music_added_to_queue, GlassToast.LENGTH_SHORT).show();
                            break;
                        case 2:
                            app.player.addLater(song);
                            GlassToast.makeText(this, R.string.music_later_added, GlassToast.LENGTH_SHORT).show();
                            break;
                        default:
                            pickPlaylistAndAdd(song);
                            break;
                    }
                })
                .show();
    }

    /**
     * 收藏到歌单弹层：本地歌单即时加入；酷狗云歌单（含自建歌单）登录后异步加载，
     * 点选即云同步——修复"搜索结果只能添加到我喜欢"的问题。
     */
    private void pickPlaylistAndAdd(SongInfo song) {
        android.app.Dialog dialog = new android.app.Dialog(this, R.style.MusicBottomSheetDialogTheme);
        dialog.setCanceledOnTouchOutside(true);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setWindowAnimations(R.style.MusicBottomSheetAnimation);
        }

        android.widget.FrameLayout wrap = new android.widget.FrameLayout(this);
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
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new android.view.ViewGroup.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // 本地歌单（我喜欢置前；稍后听/最近播放为系统分区不作为目标）
        list.addView(sectionLabel(R.string.music_playlists));
        List<String> names = new ArrayList<>();
        for (String name : app.playlists.listPlaylists()) {
            if ("Listen Later".equals(name) || "Recently Played".equals(name)) continue;
            names.add(name);
        }
        if (names.isEmpty()) names.add("Favorites");
        for (String name : names) {
            list.addView(playlistRow(displayPlaylistName(name),
                    getString(R.string.music_playlist_song_count, app.playlists.songCount(name)),
                    v -> {
                        app.playlists.addSong(name, song);
                        dialog.dismiss();
                        GlassToast.makeText(this,
                                getString(R.string.music_added_to_playlist, displayPlaylistName(name)),
                                GlassToast.LENGTH_SHORT).show();
                    }));
        }

        // 云歌单：登录后异步加载（默认收藏/自建歌单），点选即云同步
        list.addView(sectionLabel(R.string.music_remote_playlists));
        if (!app.auth.isLoggedIn()) {
            list.addView(statusRow(getString(R.string.music_remote_login_prompt)));
        } else {
            final TextView loading = statusRow(getString(R.string.music_remote_loading));
            list.addView(loading);
            Executors.newSingleThreadExecutor().execute(() -> {
                try {
                    app.refreshDataSourceAuth();
                    List<com.example.cleanrecovery.music.data.RemotePlaylist> remote =
                            app.dataSource.getAllUserPlaylists(30);
                    runOnUiThread(() -> {
                        loading.setVisibility(View.GONE);
                        for (com.example.cleanrecovery.music.data.RemotePlaylist playlist : remote) {
                            list.addView(playlistRow(playlist.name,
                                    getString(R.string.music_playlist_song_count, playlist.songCount),
                                    v -> addToCloudPlaylist(dialog, playlist, song)));
                        }
                        capSheetScroll(scroll, list);
                    });
                } catch (Exception exception) {
                    runOnUiThread(() -> {
                        loading.setVisibility(View.GONE);
                        list.addView(statusRow(getString(R.string.music_remote_load_failed)));
                        capSheetScroll(scroll, list);
                    });
                }
            });
        }
        capSheetScroll(scroll, list);
        wrap.addView(root, new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        dialog.setContentView(wrap);
        dialog.show();
    }

    private void addToCloudPlaylist(android.app.Dialog dialog,
            com.example.cleanrecovery.music.data.RemotePlaylist playlist, SongInfo song) {
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                app.refreshDataSourceAuth();
                app.dataSource.addSongsToUserPlaylist(playlist,
                        java.util.Collections.singletonList(song));
                runOnUiThread(() -> {
                    dialog.dismiss();
                    GlassToast.makeText(this,
                            getString(R.string.music_added_to_playlist, playlist.name),
                            GlassToast.LENGTH_SHORT).show();
                });
            } catch (Exception exception) {
                android.util.Log.w("MusicSearch", "cloud playlist add failed", exception);
                runOnUiThread(() -> GlassToast.makeText(this, R.string.music_cloud_add_failed,
                        GlassToast.LENGTH_SHORT).show());
            }
        });
    }

    private TextView sectionLabel(int textRes) {
        TextView label = new TextView(this);
        label.setText(textRes);
        label.setTextColor(getColor(R.color.text_hint));
        label.setTextSize(11.5f);
        label.setPadding(0, dp(14), 0, dp(2));
        return label;
    }

    private TextView statusRow(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColor(R.color.text_hint));
        view.setTextSize(13);
        view.setPadding(0, dp(12), 0, dp(12));
        return view;
    }

    private LinearLayout playlistRow(CharSequence name, CharSequence count,
            View.OnClickListener click) {
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
        row.setOnClickListener(click);
        return row;
    }

    /** 列表超过 400dp 时把滚动区压到上限，弹层不顶出屏幕。 */
    private void capSheetScroll(android.widget.ScrollView scroll, LinearLayout list) {
        scroll.post(() -> {
            int cap = dp(400);
            if (list.getHeight() > cap) {
                scroll.getLayoutParams().height = cap;
                scroll.requestLayout();
            }
        });
    }

    // ---- 搜索历史 ----------------------------------------------------------

    private SharedPreferences historyPrefs() {
        return getSharedPreferences(HISTORY_PREFS, MODE_PRIVATE);
    }

    private List<String> readHistory() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(historyPrefs().getString(HISTORY_KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) out.add(arr.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 记录搜索词：去重置顶，上限 20。 */
    private void addHistory(String keyword) {
        if (keyword == null || keyword.trim().length() < 2) return;
        String kw = keyword.trim();
        List<String> list = readHistory();
        list.remove(kw);
        list.add(0, kw);
        while (list.size() > HISTORY_MAX) list.remove(list.size() - 1);
        JSONArray arr = new JSONArray();
        for (String s : list) arr.put(s);
        historyPrefs().edit().putString(HISTORY_KEY, arr.toString()).apply();
        renderHistory();
    }

    private void renderHistory() {
        List<String> list = readHistory();
        historyChips.removeAllViews();
        historyBox.setVisibility(list.isEmpty() || input.getText().length() > 0 ? View.GONE : View.VISIBLE);
        if (list.isEmpty()) return;
        for (String kw : list) {
            TextView chip = new TextView(this);
            chip.setText(kw);
            chip.setTextSize(13);
            chip.setTextColor(getColor(R.color.text_primary));
            chip.setBackgroundResource(R.drawable.bg_card);
            chip.setSingleLine(true);
            chip.setPadding(dp(14), dp(6), dp(14), dp(6));
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> {
                input.setText(kw);
                input.setSelection(kw.length());
            });
            chip.setOnLongClickListener(v -> {
                List<String> remain = readHistory();
                remain.remove(kw);
                JSONArray arr = new JSONArray();
                for (String sItem : remain) arr.put(sItem);
                historyPrefs().edit().putString(HISTORY_KEY, arr.toString()).apply();
                renderHistory();
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            historyChips.addView(chip, lp);
        }
    }

    private String displayPlaylistName(String name) {
        if ("Favorites".equals(name)) return getString(R.string.music_playlist_favorites);
        if ("Listen Later".equals(name)) return getString(R.string.music_playlist_listen_later);
        if ("Recently Played".equals(name)) return getString(R.string.music_recent);
        return name;
    }

    private int dp(int v) {
        return (int) (TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()) + 0.5f);
    }
}
