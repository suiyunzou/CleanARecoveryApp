package com.example.cleanrecovery.ui.activity;

import com.example.cleanrecovery.ui.browser.ViaDialogBuilder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import com.example.cleanrecovery.ui.browser.ViaUi;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.io.File;
import java.io.OutputStream;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * VIA 书签/历史/离线页面三栏页（基准 .task/via-ref/56~78）。
 * 书签栏为树形：根目录=文件夹行在上、书签行在下；点文件夹钻入（搜索提示变为
 * 「搜索 文件夹名」、顶部出现「..」返回行）；长按菜单/排序/显示细节/导入备份
 * 均对齐真 Via 7.2.1。
 */
abstract class BrowserLibraryBaseActivity extends Activity {
    static final String EXTRA_LIBRARY_ACTION = "library_action";
    static final String ACTION_OPEN_TABS = "open_tabs";
    /** 新标签打开：BrowserActivity 新建 tab 并切换；后台打开：只建 tab 不切换。 */
    static final String ACTION_FOLDER_BG = "open_folder_bg";
    static final String ACTION_FOLDER_NEWTAB = "open_folder_newtab";

    private static final int REQ_EDITOR = 4001;
    private static final int REQ_IMPORT = 4002;

    static final int MODE_BOOKMARKS = 0;
    static final int MODE_HISTORY = 1;
    static final int MODE_OFFLINE = 2;

    /** 排序方式（真 Via：名称顺序/最新的优先/最旧的优先）。 */
    private static final int SORT_NAME = 0;
    private static final int SORT_NEWEST = 1;
    private static final int SORT_OLDEST = 2;

    private BrowserDatabaseHelper db;
    private LinearLayout root;
    private LinearLayout tabBar;
    private EditText search;
    private LinearLayout listBox;
    private LinearLayout bottomBar;
    private int mode;
    private String currentUrl = "";
    private String query = "";
    /** 书签栏当前所在文件夹（""=根目录）。 */
    private String bookmarkFolder = "";
    private int sortBy = SORT_NEWEST;
    private boolean showDetails = false;
    /** 历史页多选编辑模式（对齐 Via：列表内勾选，底栏 全选/删除/完成）。 */
    private boolean historyEditMode = false;
    private final HashSet<Long> historySelected = new HashSet<>();
    /** 当前渲染出的历史行 id（去重后），用于全选切换。 */
    private final List<Long> historyRowIds = new ArrayList<>();
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("yyyyMMdd", Locale.getDefault());
    private final Collator collator = Collator.getInstance(Locale.CHINA);

    protected abstract int initialMode();

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        db = BrowserDatabaseHelper.getInstance(this);
        SharedPreferences sp = getSharedPreferences("via_library", MODE_PRIVATE);
        sortBy = sp.getInt("sort", SORT_NEWEST);
        showDetails = sp.getBoolean("details", false);
        currentUrl = getIntent().getStringExtra("current_url");
        if (currentUrl == null) currentUrl = "";
        mode = initialMode();
        buildShell();
        render();
        String initialQuery = getIntent().getStringExtra("initial_query");
        if (initialQuery != null) search.setText(initialQuery);
    }

    private void buildShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFitsSystemWindows(false);
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom + dp(8));
            return insets;
        });
        setContentView(root, new LinearLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, dp(6), dp(8), dp(2));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(56)));

        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.via_toolbar_back);
        back.setBackgroundResource(R.drawable.bg_via_toolbar_button);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tabBar, new LinearLayout.LayoutParams(0, -1, 1));

        search = new EditText(this);
        search.setHint(R.string.via_search_hint_simple);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(pill(0xfff1f1f1, 0, 0));
        search.setTextColor(getColor(R.color.text_primary));
        search.setHintTextColor(0xffb0b0b0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString().trim().toLowerCase(Locale.ROOT); renderList(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(40));
        sp.setMargins(dp(8), 0, dp(8), dp(14));
        root.addView(search, sp);

        View line = new View(this); line.setBackgroundColor(0xffeeeeee);
        root.addView(line, new LinearLayout.LayoutParams(-1, 1));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(10), 0, dp(24));
        scroll.addView(listBox, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        View bottomLine = new View(this); bottomLine.setBackgroundColor(0xffeeeeee);
        root.addView(bottomLine, new LinearLayout.LayoutParams(-1, 1));
        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(dp(8), 0, dp(8), 0);
        root.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void render() {
        renderTabs();
        search.setText("");
        query = "";
        renderList();
        renderBottom();
    }

    private void renderTabs() {
        tabBar.removeAllViews();
        addTab("书签", MODE_BOOKMARKS);
        addTab("历史", MODE_HISTORY);
        addTab("离线页面", MODE_OFFLINE);
    }

    private void addTab(String text, int target) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setGravity(Gravity.CENTER);
        tv.setTextColor(mode == target ? getColor(R.color.text_primary) : 0xff9e9e9e);
        tv.setTypeface(Typeface.DEFAULT, mode == target ? Typeface.BOLD : Typeface.NORMAL);
        tv.setOnClickListener(v -> {
            if (mode != target) {
                mode = target;
                bookmarkFolder = "";
                exitHistoryEditMode();
                render();
            }
        });
        tabBar.addView(tv, new LinearLayout.LayoutParams(dp(target == MODE_OFFLINE ? 92 : 58), -1));
    }

    private void renderList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        if (mode == MODE_BOOKMARKS) renderBookmarks();
        else if (mode == MODE_HISTORY) renderHistory();
        else renderOffline();
    }

    // ===== 书签：树形（文件夹行 + 书签行，钻入式） =====

    private void renderBookmarks() {
        if (bookmarkFolder.isEmpty()) {
            search.setHint(R.string.via_search_hint_simple);
        } else {
            search.setHint(getString(R.string.via_search_hint_folder, bookmarkFolder));
        }
        List<BrowserDatabaseHelper.Entry> rows = sortBookmarks(db.listBookmarks());
        int shown = 0;
        if (bookmarkFolder.isEmpty()) {
            for (String folder : sortFolders(db.listFolders())) {
                if (!match(folder, "")) continue;
                addFolderRow(folder);
                shown++;
            }
        } else {
            addParentRow();
        }
        for (BrowserDatabaseHelper.Entry e : rows) {
            boolean inFolder = bookmarkFolder.isEmpty()
                    ? "根目录".equals(e.folder) : bookmarkFolder.equals(e.folder);
            if (!inFolder || !match(e.title, e.url)) continue;
            addBookmarkRow(e);
            shown++;
        }
        if (shown == 0) addEmpty(R.string.via_bookmarks_empty);
    }

    private List<BrowserDatabaseHelper.Entry> sortBookmarks(List<BrowserDatabaseHelper.Entry> in) {
        List<BrowserDatabaseHelper.Entry> rows = new ArrayList<>(in);
        if (sortBy == SORT_NAME) {
            Collections.sort(rows, (a, b) -> collator.compare(
                    a.title == null ? "" : a.title, b.title == null ? "" : b.title));
        } else if (sortBy == SORT_OLDEST) {
            Collections.sort(rows, (a, b) -> Long.compare(a.time, b.time));
        } else {
            Collections.sort(rows, (a, b) -> Long.compare(b.time, a.time));
        }
        return rows;
    }

    private List<String> sortFolders(List<String> in) {
        List<String> folders = new ArrayList<>(in);
        if (sortBy == SORT_NAME) Collections.sort(folders, collator);
        return folders;
    }

    /** 文件夹行（基准 73：描边文件夹图标 + 名称，无副标题）。 */
    private void addFolderRow(String name) {
        LinearLayout row = baseIconRow();
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_nav_folder);
        ic.setColorFilter(0xff3c3c3c);
        row.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(34)));
        TextView tv = rowText(name);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> { bookmarkFolder = name; renderList(); });
        row.setOnLongClickListener(v -> { showFolderMenu(name); return true; });
        listBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 文件夹内顶部的「..」返回行（基准 77）。 */
    private void addParentRow() {
        LinearLayout row = baseIconRow();
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_nav_folder);
        ic.setColorFilter(0xff3c3c3c);
        row.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(34)));
        row.addView(rowText(".."), new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> { bookmarkFolder = ""; renderList(); });
        listBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 书签行（基准 74：描边星形 + 标题；显示细节时副标题为 URL）。 */
    private void addBookmarkRow(BrowserDatabaseHelper.Entry e) {
        LinearLayout row = baseIconRow();
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_via_star);
        ic.setColorFilter(0xff3c3c3c);
        row.addView(ic, new LinearLayout.LayoutParams(dp(30), dp(34)));
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(TextUtils.isEmpty(e.title) ? e.url : e.title);
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(getColor(R.color.text_primary));
        texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (showDetails && !TextUtils.isEmpty(e.url)) {
            TextView sub = new TextView(this);
            sub.setText(e.url);
            sub.setTextSize(12);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setTextColor(0xff999999);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(3), 0, 0);
            texts.addView(sub, lp);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> returnUrl(e.url));
        row.setOnLongClickListener(v -> { showBookmarkMenu(e); return true; });
        listBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout baseIconRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(16), dp(12));
        row.setMinimumHeight(dp(58));
        return row;
    }

    private TextView rowText(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(15);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setPadding(dp(16), 0, 0, 0);
        return tv;
    }

    /** 书签长按菜单（基准 75：后台打开/新标签打开/编辑/添加到主页收藏/删除/复制链接/分享）。 */
    private void showBookmarkMenu(BrowserDatabaseHelper.Entry e) {
        String[] items = {
                getString(R.string.via_bk_open_bg),
                getString(R.string.via_bk_open_newtab),
                getString(R.string.via_bk_edit),
                getString(R.string.via_add_to_home_fav),
                getString(R.string.via_bk_delete),
                getString(R.string.via_bk_copy_link),
                getString(R.string.via_bk_share)
        };
        new ViaDialogBuilder(this)
                .setTitle(TextUtils.isEmpty(e.title) ? e.url : e.title)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: openUrlResult(e.url, "open_bg"); break;
                        case 1: openUrlResult(e.url, "open_newtab"); break;
                        case 2: editBookmark(e); break;
                        case 3:
                            db.addQuickLink(TextUtils.isEmpty(e.title) ? e.url : e.title, e.url);
                            GlassToast.makeText(this, R.string.via_quicklink_added_current,
                                    GlassToast.LENGTH_SHORT).show();
                            break;
                        case 4: db.removeBookmark(e.id); renderList(); break;
                        case 5: copyLink(e.url); break;
                        case 6: shareLink(e.url); break;
                    }
                }).show();
    }

    /** 文件夹长按菜单（基准 78）。后台/新标签打开=打开文件夹内全部书签。 */
    private void showFolderMenu(String name) {
        String[] items = {
                getString(R.string.via_bk_open_bg),
                getString(R.string.via_bk_open_newtab),
                getString(R.string.via_bk_edit),
                getString(R.string.via_add_to_home_fav),
                getString(R.string.via_bk_delete)
        };
        new ViaDialogBuilder(this)
                .setTitle(name)
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0: openFolderResult(name, ACTION_FOLDER_BG); break;
                        case 1: openFolderResult(name, ACTION_FOLDER_NEWTAB); break;
                        case 2:
                            ViaUi.inputDialog(this, getString(R.string.via_bk_edit),
                                    new ViaUi.InputField(getString(R.string.via_bk_field_title), name),
                                    null, false, null,
                                    (values, check) -> {
                                        if (db.renameFolder(name, values[0].trim())) {
                                            if (values[0].trim().equals(bookmarkFolder)) {
                                                bookmarkFolder = values[0].trim();
                                            }
                                            renderList();
                                        }
                                    });
                            break;
                        case 3:
                            // 整夹加入主页收藏：把文件夹内全部书签逐条加为快捷链接
                            int added = 0;
                            for (BrowserDatabaseHelper.Entry b : db.listBookmarks()) {
                                if (name.equals(b.folder)) {
                                    db.addQuickLink(TextUtils.isEmpty(b.title) ? b.url : b.title, b.url);
                                    added++;
                                }
                            }
                            GlassToast.makeText(this, added > 0
                                            ? getString(R.string.via_quicklink_added_current)
                                            : getString(R.string.via_folder_no_bookmarks),
                                    GlassToast.LENGTH_SHORT).show();
                            break;
                        case 4:
                            new ViaDialogBuilder(this)
                                    .setTitle(getString(R.string.via_folder_delete_confirm, name))
                                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                                        db.deleteFolderWithBookmarks(name);
                                        if (name.equals(bookmarkFolder)) bookmarkFolder = "";
                                        renderList();
                                    })
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .show();
                            break;
                    }
                }).show();
    }

    private void editBookmark(BrowserDatabaseHelper.Entry e) {
        Intent it = new Intent(this, BookmarkEditorActivity.class);
        it.putExtra(BookmarkEditorActivity.EXTRA_MODE, BookmarkEditorActivity.MODE_EDIT_BOOKMARK);
        it.putExtra(BookmarkEditorActivity.EXTRA_ID, e.id);
        it.putExtra(BookmarkEditorActivity.EXTRA_TITLE, e.title);
        it.putExtra(BookmarkEditorActivity.EXTRA_URL, e.url);
        it.putExtra(BookmarkEditorActivity.EXTRA_FOLDER, e.folder);
        startActivityForResult(it, REQ_EDITOR);
    }

    private void openUrlResult(String url, String how) {
        Intent data = new Intent();
        data.putExtra("url", url);
        if ("open_bg".equals(how)) data.putExtra("open_bg", true);
        if ("open_newtab".equals(how)) data.putExtra("open_newtab", true);
        setResult(RESULT_OK, data);
        finish();
    }

    private void openFolderResult(String folder, String action) {
        Intent data = new Intent();
        data.putExtra(EXTRA_LIBRARY_ACTION, action);
        data.putExtra("folder", folder);
        setResult(RESULT_OK, data);
        finish();
    }

    private void copyLink(String url) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", url));
        GlassToast.makeText(this, R.string.via_copied, GlassToast.LENGTH_SHORT).show();
    }

    private void shareLink(String url) {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(send, getString(R.string.via_bk_share)));
    }

    // ===== 历史 / 离线页面（维持原有逻辑） =====

    private void renderHistory() {
        historyRowIds.clear();
        List<BrowserDatabaseHelper.Entry> raw = db.listHistory();
        HashSet<String> seen = new HashSet<>();
        String today = dayFmt.format(new Date());
        String yesterday = dayFmt.format(new Date(System.currentTimeMillis() - 86400000L));
        boolean todayTitle = false, yesterdayTitle = false, olderTitle = false;
        int count = 0;
        for (BrowserDatabaseHelper.Entry e : raw) {
            if (TextUtils.isEmpty(e.url) || seen.contains(e.url) || !match(e.title, e.url)) continue;
            seen.add(e.url);
            String d = dayFmt.format(new Date(e.time));
            if (d.equals(today) && !todayTitle) { addSection("今天"); todayTitle = true; }
            else if (d.equals(yesterday) && !yesterdayTitle) { addSection("昨天"); yesterdayTitle = true; }
            else if (!d.equals(today) && !d.equals(yesterday) && !olderTitle) { addSection("更早"); olderTitle = true; }
            String host = hostText(e.url);
            // 多选模式（对齐 Via 编辑）：行尾勾选圈，点击切换选中，不打开页面
            ImageView check = historyEditMode ? new ImageView(this) : null;
            if (check != null) {
                check.setScaleType(ImageView.ScaleType.CENTER);
                check.setImageResource(historySelected.contains(e.id)
                        ? R.drawable.bg_via_radio_on : R.drawable.bg_via_radio);
            }
            View.OnLongClickListener longClick = historyEditMode ? null
                    : v -> { showEntryMenu(e.title, e.url, () -> { db.removeHistory(e.id); renderList(); }); return true; };
            addEntryRow("◷", 0xff333333,
                    TextUtils.isEmpty(e.title) ? e.url : e.title, e.url.replaceFirst("^https?://", ""),
                    v -> {
                        if (historyEditMode) {
                            if (historySelected.contains(e.id)) historySelected.remove(e.id);
                            else historySelected.add(e.id);
                            if (check != null) check.setImageResource(historySelected.contains(e.id)
                                    ? R.drawable.bg_via_radio_on : R.drawable.bg_via_radio);
                            renderBottom();
                        } else {
                            returnUrl(e.url);
                        }
                    }, longClick, check);
            historyRowIds.add(e.id);
            count++;
        }
        if (count == 0) addEmpty(R.string.via_history_empty);
    }

    private void renderOffline() {
        List<BrowserDatabaseHelper.OfflineEntry> rows = db.listOfflinePages();
        int count = 0;
        for (BrowserDatabaseHelper.OfflineEntry e : rows) {
            if (!match(e.title, e.url)) continue;
            addEntryRow("◉", 0xff777777, e.title, hostText(e.url), v -> openOffline(e), v -> { deleteOffline(e); return true; });
            count++;
        }
        if (count == 0) addEmpty(R.string.via_offline_empty);
    }

    private boolean match(String title, String url) {
        return query.isEmpty()
                || (!TextUtils.isEmpty(title) && title.toLowerCase(Locale.ROOT).contains(query))
                || (!TextUtils.isEmpty(url) && url.toLowerCase(Locale.ROOT).contains(query));
    }

    private void addSection(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(0xff9e9e9e);
        tv.setPadding(dp(8), dp(10), dp(8), dp(8));
        listBox.addView(tv, new LinearLayout.LayoutParams(-1, -2));
    }

    /** VIA 长按菜单：打开 / 复制链接 / 删除。 */
    private void showEntryMenu(String title, String url, Runnable onDelete) {
        String[] items = {"打开", "复制链接", "删除"};
        new ViaDialogBuilder(this)
                .setTitle(title == null || title.isEmpty() ? url : title)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        returnUrl(url);
                    } else if (which == 1) {
                        copyLink(url);
                    } else {
                        onDelete.run();
                    }
                })
                .show();
    }

    private void addEntryRow(String icon, int iconColor, String titleText, String subText, View.OnClickListener click, View.OnLongClickListener longClick) {
        addEntryRow(icon, iconColor, titleText, subText, click, longClick, null);
    }

    private void addEntryRow(String icon, int iconColor, String titleText, String subText,
                             View.OnClickListener click, View.OnLongClickListener longClick, View trailing) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(12), dp(8));
        row.setMinimumHeight(dp(58));
        row.setOnClickListener(click);
        if (longClick != null) row.setOnLongClickListener(longClick);
        ImageView ic = new ImageView(this);
        ic.setImageResource(mode == MODE_HISTORY ? R.drawable.ic_via_history2 : R.drawable.via_menu_offline);
        if (mode == MODE_HISTORY) {
            android.graphics.Bitmap favicon = com.example.cleanrecovery.ui.browser.BrowserFavicons.get(
                    "https://" + subText);
            if (favicon != null) ic.setImageBitmap(favicon);
        }
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(20), dp(20));
        iconParams.setMargins(dp(8), 0, dp(16), 0);
        row.addView(ic, iconParams);
        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(TextUtils.isEmpty(titleText) ? subText : titleText);
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(getColor(R.color.text_primary));
        texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
        if (!TextUtils.isEmpty(subText)) {
            TextView sub = new TextView(this);
            sub.setText(subText);
            sub.setTextSize(12);
            sub.setSingleLine(true);
            sub.setEllipsize(TextUtils.TruncateAt.END);
            sub.setTextColor(0xff999999);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(3), 0, 0);
            texts.addView(sub, lp);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        if (trailing != null) {
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(20), dp(20));
            tp.setMargins(dp(8), 0, 0, 0);
            row.addView(trailing, tp);
        }
        listBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void addEmpty(int res) {
        TextView tv = new TextView(this);
        tv.setText(res);
        tv.setTextColor(0xff9e9e9e);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(0, dp(80), 0, 0);
        listBox.addView(tv, new LinearLayout.LayoutParams(-1, -2));
    }

    // ===== 底栏（书签=真 Via 六项菜单；其余栏维持原样） =====

    private void renderBottom() {
        bottomBar.removeAllViews();
        if (mode == MODE_BOOKMARKS) {
            addBottom("更多", v -> showBookmarkMore(), 1);
            addBottom("编辑", v -> GlassToast.makeText(this, "长按条目编辑或删除", GlassToast.LENGTH_SHORT).show(), 0);
        } else if (mode == MODE_HISTORY) {
            if (historyEditMode) {
                // 对齐 Via：编辑模式底栏为 全选/删除（左） + 完成（右），未选中时删除置灰
                boolean all = !historyRowIds.isEmpty() && historySelected.containsAll(historyRowIds);
                addBottom(all ? "全不选" : "全选", v -> {
                    if (all) historySelected.clear();
                    else historySelected.addAll(historyRowIds);
                    renderList();
                    renderBottom();
                }, 1);
                TextView del = addBottom("删除", v -> deleteSelectedHistory(), 0);
                del.setAlpha(historySelected.isEmpty() ? 0.35f : 1f);
                View spacer = new View(this);
                bottomBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 3));
                addBottom("完成", v -> exitHistoryEditMode(), 0);
            } else {
                addBottom("标签页", v -> finishWithLibraryAction(ACTION_OPEN_TABS), 1);
                addBottom("清空", v -> showClearHistoryRangeDialog(), 0);
                addBottom("编辑", v -> {
                    historyEditMode = true;
                    historySelected.clear();
                    renderList();
                    renderBottom();
                }, 0);
            }
        } else {
            addBottom("标签页", v -> finishWithLibraryAction(ACTION_OPEN_TABS), 1);
            addBottom("清空", v -> clearMissingOffline(), 0);
            addBottom("编辑", v -> GlassToast.makeText(this, "长按条目删除", GlassToast.LENGTH_SHORT).show(), 0);
        }
    }

    private void exitHistoryEditMode() {
        if (!historyEditMode && historySelected.isEmpty()) return;
        historyEditMode = false;
        historySelected.clear();
        renderList();
        renderBottom();
    }

    private void deleteSelectedHistory() {
        if (historySelected.isEmpty()) return;
        for (Long id : new ArrayList<>(historySelected)) db.removeHistory(id);
        historySelected.clear();
        historyEditMode = false;
        renderList();
        renderBottom();
    }

    /** 清空（对齐 Via「清除浏览历史」）：时间范围列表（带条数）→ 二次确认后删除。 */
    private void showClearHistoryRangeDialog() {
        long now = System.currentTimeMillis();
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        long todayStart = cal.getTimeInMillis();
        long[] starts = {
                now - 3600000L,          // 过去一小时
                todayStart,              // 今天
                todayStart - 86400000L,  // 今天和昨天
                now - 7L * 86400000L,    // 过去 7 天
                0L                       // 所有时间
        };
        String[] labels = {
                getString(R.string.via_history_range_hour),
                getString(R.string.via_history_range_today),
                getString(R.string.via_history_range_today_yesterday),
                getString(R.string.via_history_range_7days),
                getString(R.string.via_history_range_all)
        };
        List<BrowserDatabaseHelper.Entry> all = db.listHistory();
        // 对齐 Via：条数为 0 或与前一项相同的范围不展示；大范围排在前面
        List<String> options = new ArrayList<>();
        List<Long> picked = new ArrayList<>();
        List<String> pickedLabels = new ArrayList<>();
        int lastCount = 0;
        for (int i = starts.length - 1; i >= 0; i--) {
            int n = 0;
            for (BrowserDatabaseHelper.Entry e : all) {
                if (e.time >= starts[i]) n++;
            }
            if (n == 0 || n == lastCount) continue;
            lastCount = n;
            options.add(labels[i] + " (" + n + ")");
            picked.add(starts[i]);
            pickedLabels.add(labels[i]);
        }
        android.app.AlertDialog dialog = new ViaDialogBuilder(this)
                .setTitle(R.string.via_history_clear_browsing)
                .setItems(options.toArray(new String[0]), (d, which) ->
                        confirmClearHistoryRange(picked.get(which), pickedLabels.get(which)))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        // 对齐 Via：弹窗锚定在底栏「清空」按钮上方，而非屏幕居中
        dialog.show();
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setGravity(Gravity.BOTTOM | Gravity.END);
            android.view.WindowManager.LayoutParams lp = w.getAttributes();
            lp.y = dp(56);
            lp.x = dp(12);
            lp.width = Math.min(dp(290),
                    getResources().getDisplayMetrics().widthPixels - dp(24));
            w.setAttributes(lp);
        }
    }

    private void confirmClearHistoryRange(long sinceMs, String label) {
        new ViaDialogBuilder(this)
                .setTitle(R.string.via_history_clear_browsing)
                .setMessage(getString(R.string.via_history_clear_confirm, label))
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    db.clearHistorySince(sinceMs);
                    exitHistoryEditMode();
                    renderList();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 更多菜单（基准 _via_bkmore：添加书签/新建文件夹/排序方式/显示细节/导入书签/备份书签）。 */
    private void showBookmarkMore() {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.via_menu_add_bookmark));
        items.add(getString(R.string.via_new_folder));
        items.add(getString(R.string.via_sort_by));
        items.add(getString(showDetails
                ? R.string.via_hide_details : R.string.via_show_details));
        items.add(getString(R.string.via_import_bookmarks));
        items.add(getString(R.string.via_backup_bookmarks));
        new ViaDialogBuilder(this)
                .setItems(items.toArray(new String[0]), (dialog, which) -> {
                    switch (which) {
                        case 0: addBookmarkDialog(); break;
                        case 1: launchNewFolder(bookmarkFolder); break;
                        case 2: pickSort(); break;
                        case 3:
                            showDetails = !showDetails;
                            persistLibraryPrefs();
                            renderList();
                            break;
                        case 4: importBookmarks(); break;
                        case 5: backupBookmarks(); break;
                    }
                }).show();
    }

    /** 添加书签弹窗（基准 55：标题/地址/目录 + 添加到主页收藏 + 取消·确定）。 */
    private void addBookmarkDialog() {
        ViaUi.inputDialog(this, getString(R.string.via_menu_add_bookmark),
                new ViaUi.InputField[]{
                        new ViaUi.InputField(getString(R.string.via_bk_field_title), ""),
                        new ViaUi.InputField(getString(R.string.via_bk_field_url), "https://"),
                        bookmarkFolderField()
                },
                getString(R.string.via_add_to_home_fav), false,
                null,
                (values, toHome) -> {
                    String title = values[0].trim();
                    String url = values[1].trim();
                    if (url.isEmpty()) return;
                    if (title.isEmpty()) title = url;
                    db.addBookmark(title, url, values[2].trim());
                    if (toHome) db.addQuickLink(title, url);
                    GlassToast.makeText(this, R.string.via_bookmark_added_current,
                            GlassToast.LENGTH_SHORT).show();
                    renderList();
                });
    }

    private ViaUi.InputField bookmarkFolderField() {
        ViaUi.InputField field = new ViaUi.InputField("目录",
                bookmarkFolder.isEmpty() ? "根目录" : bookmarkFolder);
        field.onFieldClick = target -> pickFolder(target);
        return field;
    }

    /** 目录只读行 → 选择器（根目录/已有文件夹/新建文件夹…）。 */
    private void pickFolder(EditText target) {
        List<String> folders = new ArrayList<>();
        folders.add("根目录");
        folders.addAll(db.listFolders());
        String[] options = folders.toArray(new String[0]);
        new ViaDialogBuilder(this)
                .setTitle(R.string.via_pick_folder)
                .setItems(options, (dialog, which) -> target.setText(options[which]))
                .setNeutralButton(R.string.via_new_folder_dots, (d, w) ->
                        ViaUi.inputDialog(this, getString(R.string.via_new_folder),
                                new ViaUi.InputField(getString(R.string.via_bk_field_title), ""),
                                null, false, null,
                                (values, check) -> {
                                    String n = values[0].trim();
                                    if (!n.isEmpty()) {
                                        db.addFolder(n);
                                        target.setText(n);
                                    }
                                }))
                .show();
    }

    private void launchNewFolder(String parent) {
        Intent it = new Intent(this, BookmarkEditorActivity.class);
        it.putExtra(BookmarkEditorActivity.EXTRA_MODE, BookmarkEditorActivity.MODE_NEW_FOLDER);
        it.putExtra(BookmarkEditorActivity.EXTRA_PARENT, parent == null ? "" : parent);
        startActivityForResult(it, REQ_EDITOR);
    }

    private void pickSort() {
        String[] options = {
                getString(R.string.via_sort_name),
                getString(R.string.via_sort_newest),
                getString(R.string.via_sort_oldest)
        };
        ViaUi.radioDialog(this, getString(R.string.via_sort_by), options, sortBy, idx -> {
            sortBy = idx;
            persistLibraryPrefs();
            renderList();
        }).show();
    }

    /** 导入书签：解析 Netscape HTML 的 <a href>。 */
    private void importBookmarks() {
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        startActivityForResult(it, REQ_IMPORT);
    }

    /**
     * 导入书签：解析 Netscape HTML，保留 <H3>/<DL> 文件夹层级。
     * 文件夹落在层级名内（与 Via 一致）；重复文件夹自动合并到同名目录。
     */
    private void handleImportResult(Intent data) {
        try {
            String html = readTextStream(getContentResolver().openInputStream(data.getData()));
            if (html == null) return;
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("<h3\\b[^>]*>(.*?)</h3>|<a\\b[^>]*?href=(?:\"([^\"]*)\"|'([^']*)')[^>]*>(.*?)</a>|<dl[^>]*>|</dl",
                            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                    .matcher(html);
            // ArrayDeque 不允许 null，用 "" 表示无标题层级（如顶层 <DL>）
            java.util.ArrayDeque<String> folders = new java.util.ArrayDeque<>();
            String pendingFolder = null;
            int count = 0;
            while (m.find()) {
                String h3 = m.group(1);
                if (h3 != null) {
                    pendingFolder = stripTags(h3);
                    continue;
                }
                String tag = m.group(0).toLowerCase(Locale.ROOT);
                if (tag.startsWith("<dl")) {
                    String folder = pendingFolder;
                    pendingFolder = null;
                    if (folder != null && !folder.isEmpty()) {
                        db.addFolder(folder);
                        folders.push(folder);
                    } else {
                        folders.push("");
                    }
                } else if (tag.startsWith("</dl")) {
                    if (!folders.isEmpty()) folders.pop();
                } else {
                    String url = m.group(2) != null ? m.group(2) : m.group(3);
                    String title = stripTags(m.group(4) == null ? "" : m.group(4));
                    if (url == null || url.trim().isEmpty()) continue;
                    String folder = "根目录";
                    for (String f : folders) {
                        if (f != null && !f.isEmpty()) { folder = f; break; }
                    }
                    db.addBookmark(title.isEmpty() ? url.trim() : title, url.trim(), folder);
                    count++;
                }
            }
            renderList();
            GlassToast.makeText(this, getString(R.string.via_bk_import_done, count),
                    GlassToast.LENGTH_SHORT).show();
        } catch (Exception e) {
            GlassToast.makeText(this, getString(R.string.via_bk_import_failed,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                    GlassToast.LENGTH_SHORT).show();
        }
    }

    /** 反转义 HTML 实体并去掉内嵌标签。 */
    private String stripTags(String html) {
        String text = html.replaceAll("<[^>]+>", "").trim();
        return text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }

    private String readTextStream(java.io.InputStream in) throws Exception {
        if (in == null) return null;
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return bos.toString("UTF-8");
    }

    /** 备份书签：导出 Netscape HTML 到系统下载目录（API<29 落应用目录）。 */
    private void backupBookmarks() {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE NETSCAPE-Bookmark-file-1>\n<META HTTP-EQUIV=\"Content-Type\" CONTENT=\"text/html; charset=UTF-8\">\n<TITLE>Bookmarks</TITLE>\n<H1>Bookmarks</H1>\n<DL><p>\n");
        for (String folder : db.listFolders()) {
            sb.append("    <DT><H3>").append(esc(folder)).append("</H3>\n    <DL><p>\n");
            for (BrowserDatabaseHelper.Entry e : sortBookmarks(db.listBookmarks())) {
                if (!folder.equals(e.folder)) continue;
                sb.append("        <DT><A HREF=\"").append(esc(e.url)).append("\">")
                        .append(esc(e.title)).append("</A>\n");
            }
            sb.append("    </DL><p>\n");
        }
        for (BrowserDatabaseHelper.Entry e : sortBookmarks(db.listBookmarks())) {
            if (!"根目录".equals(e.folder)) continue;
            sb.append("    <DT><A HREF=\"").append(esc(e.url)).append("\">")
                    .append(esc(e.title)).append("</A>\n");
        }
        sb.append("</DL><p>\n");
        String name = "via_bookmarks_backup.html";
        try {
            String where;
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/html");
                Uri uri = getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    os.write(sb.toString().getBytes("UTF-8"));
                }
                where = "Download/" + name;
            } else {
                File dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS);
                File out = new File(dir, name);
                java.io.FileOutputStream fo = new java.io.FileOutputStream(out);
                fo.write(sb.toString().getBytes("UTF-8"));
                fo.close();
                where = out.getAbsolutePath();
            }
            GlassToast.makeText(this, getString(R.string.via_bk_backup_done, where),
                    GlassToast.LENGTH_LONG).show();
        } catch (Exception e) {
            GlassToast.makeText(this, getString(R.string.via_bk_export_failed,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()),
                    GlassToast.LENGTH_SHORT).show();
        }
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void persistLibraryPrefs() {
        getSharedPreferences("via_library", MODE_PRIVATE).edit()
                .putInt("sort", sortBy)
                .putBoolean("details", showDetails)
                .apply();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDITOR && resultCode == RESULT_OK) {
            renderList();
        } else if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null) {
            handleImportResult(data);
        }
    }

    private TextView addBottom(String text, View.OnClickListener click, float weight) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setGravity(weight > 0 ? Gravity.CENTER_VERTICAL | Gravity.START : Gravity.CENTER);
        tv.setTextColor(getColor(R.color.text_secondary));
        tv.setOnClickListener(click);
        bottomBar.addView(tv, new LinearLayout.LayoutParams(weight > 0 ? 0 : dp(64), -1, weight));
        return tv;
    }

    private void clearMissingOffline() {
        int count = 0;
        for (BrowserDatabaseHelper.OfflineEntry e : db.listOfflinePages()) {
            if (!new File(e.filePath).isFile() && db.removeOfflinePage(e.id)) count++;
        }
        renderList();
        GlassToast.makeText(this, getString(R.string.via_offline_missing_cleared, count), GlassToast.LENGTH_SHORT).show();
    }

    private void openOffline(BrowserDatabaseHelper.OfflineEntry e) {
        File file = new File(e.filePath);
        if (!file.isFile()) { GlassToast.makeText(this, R.string.via_downloads_file_missing, GlassToast.LENGTH_SHORT).show(); return; }
        returnUrl(Uri.fromFile(file).toString());
    }

    private void deleteOffline(BrowserDatabaseHelper.OfflineEntry e) {
        db.removeOfflinePage(e.id);
        try { File f = new File(e.filePath); if (f.isFile()) f.delete(); } catch (Exception ignored) {}
        renderList();
    }

    private void finishWithLibraryAction(String action) {
        Intent data = new Intent();
        data.putExtra(EXTRA_LIBRARY_ACTION, action);
        setResult(RESULT_OK, data);
        finish();
    }

    private void returnUrl(String url) {
        Intent data = new Intent();
        data.putExtra("url", url);
        setResult(RESULT_OK, data);
        finish();
    }

    private String hostText(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            return TextUtils.isEmpty(host) ? url : host;
        } catch (Exception ignored) { return url; }
    }

    private GradientDrawable pill(int color, int strokeColor, int strokeWidth) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color); gd.setCornerRadius(dp(16));
        if (strokeWidth > 0) gd.setStroke(strokeWidth, strokeColor);
        return gd;
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
