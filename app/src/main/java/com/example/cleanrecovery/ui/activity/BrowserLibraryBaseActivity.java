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
import android.widget.PopupWindow;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
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
    static final String ACTION_OPEN_URLS = "open_urls";
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
    private boolean closedTabsMode;
    private boolean tabSettingsMode;
    private boolean bookmarkEditMode;
    private final HashSet<String> bookmarkSelected = new HashSet<>();
    private final List<String> bookmarkRowKeys = new ArrayList<>();
    private PopupWindow bookmarkMorePopup;
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
        if (savedInstanceState != null) {
            mode = savedInstanceState.getInt("mode", mode);
            bookmarkFolder = savedInstanceState.getString("folder", "");
            closedTabsMode = savedInstanceState.getBoolean("closedTabs");
            tabSettingsMode = savedInstanceState.getBoolean("tabSettings");
            historyEditMode = savedInstanceState.getBoolean("historyEdit");
            bookmarkEditMode = savedInstanceState.getBoolean("bookmarkEdit");
            ArrayList<String> selected = savedInstanceState.getStringArrayList("bookmarkSelected");
            if (selected != null) bookmarkSelected.addAll(selected);
            long[] history = savedInstanceState.getLongArray("historySelected");
            if (history != null) for (long id : history) historySelected.add(id);
        }
        buildShell();
        render();
        String initialQuery = getIntent().getStringExtra("initial_query");
        if (savedInstanceState != null) search.setText(savedInstanceState.getString("query", ""));
        else if (initialQuery != null) search.setText(initialQuery);
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
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(root, new LinearLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ViaUi.toolbarInset(this), dp(12), ViaUi.toolbarInset(this), dp(2));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(66)));

        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.via_toolbar_back);
        back.setBackgroundResource(R.drawable.bg_via_toolbar_button);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setOnClickListener(v -> onBackPressed());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(tabBar, new LinearLayout.LayoutParams(0, -1, 1));

        View headerLine = new View(this); headerLine.setBackgroundColor(0xffeeeeee);
        root.addView(headerLine, new LinearLayout.LayoutParams(-1, 1));
        search = new EditText(this);
        search.setHint("搜索");
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setPadding(dp(16), 0, dp(16), 0);
        search.setBackground(pill(0xfff1f1f1, 0, 0));
        search.setTextColor(getColor(R.color.text_primary));
        search.setHintTextColor(0xffb0b0b0);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { query = s.toString().trim().toLowerCase(Locale.ROOT); renderList(); renderBottom(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(40));
        sp.setMargins(ViaUi.pageInset(this), dp(10), ViaUi.pageInset(this), dp(10));
        root.addView(search, sp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, 0, 0, dp(24));
        scroll.addView(listBox, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        View bottomLine = new View(this); bottomLine.setBackgroundColor(0xffeeeeee);
        root.addView(bottomLine, new LinearLayout.LayoutParams(-1, 1));
        bottomBar = new LinearLayout(this);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        bottomBar.setPadding(ViaUi.toolbarInset(this), 0, ViaUi.toolbarInset(this), 0);
        root.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    private void render() {
        renderTabs();
        search.setVisibility(tabSettingsMode ? View.GONE : View.VISIBLE);
        bottomBar.setVisibility(tabSettingsMode ? View.GONE : View.VISIBLE);
        search.setText("");
        query = "";
        renderList();
        renderBottom();
    }

    private void renderTabs() {
        tabBar.removeAllViews();
        if (closedTabsMode) {
            TextView title = rowText(tabSettingsMode ? "标签页设置" : "关闭的标签页");
            title.setTextSize(18);
            tabBar.addView(title);
            return;
        }
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
        tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        tv.setOnClickListener(v -> {
            if (mode != target) {
                mode = target;
                bookmarkFolder = "";
                bookmarkEditMode = false;
                bookmarkSelected.clear();
                exitHistoryEditMode();
                render();
            }
        });
        tabBar.addView(tv, new LinearLayout.LayoutParams(dp(target == MODE_OFFLINE ? 88 : 56), -1));
    }

    private void renderList() {
        if (listBox == null) return;
        listBox.removeAllViews();
        if (tabSettingsMode) renderClosedTabSettings();
        else if (closedTabsMode) renderClosedTabs();
        else if (mode == MODE_BOOKMARKS) renderBookmarks();
        else if (mode == MODE_HISTORY) renderHistory();
        else renderOffline();
    }

    // ===== 书签：树形（文件夹行 + 书签行，钻入式） =====

    private void renderBookmarks() {
        bookmarkRowKeys.clear();
        if (bookmarkFolder.isEmpty()) {
            search.setHint("搜索");
        } else {
            search.setHint(getString(R.string.via_search_hint_folder, bookmarkFolder));
        }
        List<BrowserDatabaseHelper.Entry> rows = sortBookmarks(db.listBookmarks());
        int shown = 0;
        if (!bookmarkFolder.isEmpty()) addParentRow();
        {
            for (String folder : sortFolders(db.listFolders())) {
                if (!db.folderParent(folder).equals(bookmarkFolder)) continue;
                if (!match(folder, "")) continue;
                addFolderRow(folder);
                shown++;
            }
        }
        for (BrowserDatabaseHelper.Entry e : rows) {
            boolean inFolder = bookmarkFolder.isEmpty()
                    ? "根目录".equals(e.folder) : bookmarkFolder.equals(e.folder);
            if (!inFolder || !match(e.title, e.url)) continue;
            addBookmarkRow(e);
            shown++;
        }
        if (shown == 0) addEmpty(R.string.via_bookmarks_empty);
        bookmarkSelected.retainAll(bookmarkRowKeys);
        if (sortBy == -1) applyBookmarkOrder();
    }

    private void bindBookmarkSelection(LinearLayout row, String key) {
        bookmarkRowKeys.add(key);
        row.setTag(key);
        if (!bookmarkEditMode) return;
        ImageView drag = new ImageView(this);
        drag.setImageResource(R.drawable.ic_via_library_reorder);
        drag.setColorFilter(0xff333333);
        drag.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        drag.setContentDescription("排序");
        drag.setPadding(dp(12), dp(12), dp(12), dp(12));
        row.addView(drag, new LinearLayout.LayoutParams(dp(48), dp(48)));
        drag.setOnLongClickListener(v -> {
            if (!query.isEmpty()) return false;
            if (android.os.Build.VERSION.SDK_INT >= 24)
                v.startDragAndDrop(ClipData.newPlainText("bookmark", key), new View.DragShadowBuilder(row), key, 0);
            else v.startDrag(ClipData.newPlainText("bookmark", key), new View.DragShadowBuilder(row), key, 0);
            return true;
        });
        row.setOnDragListener((v, event) -> {
            if (!(event.getLocalState() instanceof String)) return false;
            if (event.getAction() == android.view.DragEvent.ACTION_DROP) {
                String from = (String) event.getLocalState();
                if (bookmarkRowKeys.contains(from) && !from.equals(key)) {
                    bookmarkRowKeys.remove(from);
                    bookmarkRowKeys.add(bookmarkRowKeys.indexOf(key), from);
                    sortBy = -1;
                    getSharedPreferences("via_library", MODE_PRIVATE).edit()
                            .putString("order:" + bookmarkFolder, new org.json.JSONArray(bookmarkRowKeys).toString()).apply();
                    persistLibraryPrefs(); renderList(); renderBottom();
                }
            }
            return true;
        });
        ImageView check = selectionIndicator(bookmarkSelected.contains(key));
        LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        checkParams.leftMargin = dp(18);
        checkParams.rightMargin = dp(2);
        row.addView(check, checkParams);
        row.setOnClickListener(v -> {
            if (!bookmarkSelected.remove(key)) bookmarkSelected.add(key);
            updateSelectionIndicator(check, bookmarkSelected.contains(key));
            renderBottom();
        });
        row.setOnLongClickListener(null);
    }

    private void applyBookmarkOrder() {
        List<String> order = new ArrayList<>();
        try {
            org.json.JSONArray saved = new org.json.JSONArray(getSharedPreferences("via_library", MODE_PRIVATE)
                    .getString("order:" + bookmarkFolder, "[]"));
            for (int i = 0; i < saved.length(); i++) order.add(saved.getString(i));
        } catch (org.json.JSONException ignored) { }
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < listBox.getChildCount(); i++) {
            View row = listBox.getChildAt(i);
            if (row.getTag() instanceof String) rows.add(row);
        }
        rows.sort((a, b) -> Integer.compare(order.contains(a.getTag()) ? order.indexOf(a.getTag()) : Integer.MAX_VALUE,
                order.contains(b.getTag()) ? order.indexOf(b.getTag()) : Integer.MAX_VALUE));
        bookmarkRowKeys.clear();
        for (View row : rows) { listBox.removeView(row); listBox.addView(row); bookmarkRowKeys.add((String) row.getTag()); }
    }

    private void renderBookmarkEditBottom() {
        boolean all = !bookmarkRowKeys.isEmpty() && bookmarkSelected.containsAll(bookmarkRowKeys);
        addBottom(all ? "全不选" : "全选", v -> {
            if (all) bookmarkSelected.clear(); else bookmarkSelected.addAll(bookmarkRowKeys);
            renderList(); renderBottom();
        }, 0);
        TextView move = addBottom("移动", v -> moveSelectedBookmarks(), 0);
        TextView delete = addBottom(deleteLabel(bookmarkSelected.size()), v -> showLibraryConfirm("删除",
                "你确定继续吗？", () -> {
                    for (String key : new HashSet<>(bookmarkSelected)) {
                        if (key.startsWith("f:")) db.deleteFolderWithBookmarks(key.substring(2));
                        else db.removeBookmark(Long.parseLong(key.substring(2)));
                    }
                    bookmarkSelected.clear(); renderList(); renderBottom();
                }), 0);
        if (!bookmarkSelected.isEmpty()) delete.setTextColor(0xffd44343);
        TextView open = addBottom("打开", v -> {
            ArrayList<String> urls = new ArrayList<>();
            for (BrowserDatabaseHelper.Entry e : selectedBookmarkEntries()) urls.add(e.url);
            if (urls.isEmpty()) return;
            Intent data = new Intent().putExtra(EXTRA_LIBRARY_ACTION, ACTION_OPEN_URLS);
            data.putStringArrayListExtra("urls", urls);
            setResult(RESULT_OK, data); finish();
        }, 0);
        for (TextView action : new TextView[]{move, delete, open}) {
            action.setEnabled(!bookmarkSelected.isEmpty());
        }
        bottomBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        addBottom("完成", v -> { bookmarkEditMode = false; bookmarkSelected.clear(); renderList(); renderBottom(); }, 0);
        // Five actions share the available width, including narrow screens and larger text.
        for (int i = bottomBar.getChildCount() - 1; i >= 0; i--) {
            View child = bottomBar.getChildAt(i);
            if (!(child instanceof TextView)) { bottomBar.removeViewAt(i); continue; }
            TextView action = (TextView) child;
            action.setMinWidth(0);
            action.setPadding(dp(4), 0, dp(4), 0);
            action.setEllipsize(TextUtils.TruncateAt.END);
            action.setContentDescription(action.getText());
            action.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
        }
    }

    private List<BrowserDatabaseHelper.Entry> selectedBookmarkEntries() {
        List<BrowserDatabaseHelper.Entry> entries = new ArrayList<>();
        for (BrowserDatabaseHelper.Entry e : sortBookmarks(db.listBookmarks())) {
            boolean selected = bookmarkSelected.contains("b:" + e.id);
            for (String key : bookmarkSelected) {
                if (key.startsWith("f:") && db.isFolderWithin(e.folder, key.substring(2))) selected = true;
            }
            if (selected) entries.add(e);
        }
        return entries;
    }

    private void moveSelectedBookmarks() {
        List<String> targets = new ArrayList<>();
        targets.add("根目录");
        for (String folder : db.listFolders()) {
            boolean excluded = false;
            for (String key : bookmarkSelected) {
                if (key.startsWith("f:") && db.isFolderWithin(folder, key.substring(2))) excluded = true;
            }
            if (!excluded) targets.add(folder);
        }
        new ViaDialogBuilder(this).setTitle("选择文件夹")
                .setItems(targets.toArray(new String[0]), (d, which) -> moveSelectionTo(targets.get(which)))
                .setNeutralButton(R.string.via_new_folder, (d, w) -> ViaUi.inputDialog(this, "新建文件夹",
                        new ViaUi.InputField("标题", ""), null, false, null, (values, check) -> {
                            if (db.addFolder(values[0])) moveSelectionTo(values[0].trim());
                        })).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void moveSelectionTo(String folder) {
        for (BrowserDatabaseHelper.Entry e : db.listBookmarks()) {
            if (bookmarkSelected.contains("b:" + e.id)) db.updateBookmark(e.id, e.title, e.url, folder);
        }
        for (String key : bookmarkSelected) if (key.startsWith("f:")) db.moveFolder(key.substring(2), folder);
        bookmarkSelected.clear(); renderList(); renderBottom();
    }

    private void renderClosedTabs() {
        search.setHint("搜索");
        int shown = 0;
        try {
            org.json.JSONArray tabs = new org.json.JSONArray(new BrowserPrefs(this).closedTabs().isEmpty()
                    ? "[]" : new BrowserPrefs(this).closedTabs());
            for (int i = 0; i < tabs.length(); i++) {
                org.json.JSONObject tab = tabs.optJSONObject(i);
                if (tab == null) continue;
                String url = tab.optString("u"), title = tab.optString("t");
                if (url.isEmpty() || !match(title, url)) continue;
                final int index = i;
                addEntryRow("", 0, title, url.replaceFirst("^https?://", ""),
                        v -> openUrlResult(url, "open_newtab"),
                        v -> { showEntryMenu(title, url, () -> {
                            tabs.remove(index); new BrowserPrefs(this).setClosedTabs(tabs.toString()); renderList();
                        }); return true; });
                shown++;
            }
        } catch (org.json.JSONException ignored) { }
        if (shown == 0) addEmpty(R.string.via_history_empty);
    }

    private void showClosedTabSettings() {
        tabSettingsMode = true;
        render();
    }

    private void renderClosedTabSettings() {
        BrowserPrefs prefs = new BrowserPrefs(this);
        String[] modes = {"禁用恢复", "总是恢复", "优先询问"};
        TextView restore = rowText("启动时恢复未关闭标签\n" + modes[Math.max(0, Math.min(2, prefs.restoreTabs()))]);
        restore.setSingleLine(false);
        restore.setPadding(ViaUi.pageInset(this), dp(20), ViaUi.pageInset(this), dp(20));
        restore.setOnClickListener(v -> ViaUi.radioDialog(this, "启动时恢复未关闭标签", modes, prefs.restoreTabs(), index -> {
            prefs.setRestoreTabs(index); renderList();
        }).show());
        listBox.addView(restore, new LinearLayout.LayoutParams(-1, -2));
        android.widget.Switch undo = new android.widget.Switch(this);
        undo.setText("显示撤销关闭标签的提示\n如果开启了隐身模式，则不会显示提示");
        undo.setTextSize(15);
        undo.setPadding(ViaUi.pageInset(this), dp(20), ViaUi.pageInset(this), dp(20));
        undo.setChecked(prefs.undoCloseToast());
        undo.setOnCheckedChangeListener((button, checked) -> prefs.setUndoCloseToast(checked));
        listBox.addView(undo, new LinearLayout.LayoutParams(-1, -2));
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("mode", mode); out.putString("folder", bookmarkFolder);
        out.putBoolean("closedTabs", closedTabsMode); out.putBoolean("tabSettings", tabSettingsMode);
        out.putBoolean("historyEdit", historyEditMode); out.putBoolean("bookmarkEdit", bookmarkEditMode);
        out.putStringArrayList("bookmarkSelected", new ArrayList<>(bookmarkSelected));
        long[] ids = new long[historySelected.size()]; int i = 0;
        for (long id : historySelected) ids[i++] = id;
        out.putLongArray("historySelected", ids); out.putString("query", search.getText().toString());
    }

    @Override public void onBackPressed() {
        if (tabSettingsMode) { tabSettingsMode = false; render(); return; }
        if (bookmarkMorePopup != null && bookmarkMorePopup.isShowing()) { bookmarkMorePopup.dismiss(); return; }
        if (historyEditMode) { exitHistoryEditMode(); return; }
        if (bookmarkEditMode) { bookmarkEditMode = false; bookmarkSelected.clear(); renderList(); renderBottom(); return; }
        if (closedTabsMode) { closedTabsMode = false; render(); return; }
        if (mode == MODE_BOOKMARKS && !bookmarkFolder.isEmpty()) {
            bookmarkFolder = db.folderParent(bookmarkFolder); renderList(); renderBottom(); return;
        }
        super.onBackPressed();
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
        addLibraryIcon(row, null);
        TextView tv = rowText(name);
        tv.setPadding(0, 0, 0, 0);
        row.addView(tv, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> { bookmarkFolder = name; renderList(); renderBottom(); });
        row.setOnLongClickListener(v -> { showFolderMenu(name); return true; });
        bindBookmarkSelection(row, "f:" + name);
        listBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 文件夹内顶部的「..」返回行（基准 77）。 */
    private void addParentRow() {
        LinearLayout row = baseIconRow();
        addLibraryIcon(row, null);
        TextView parent = rowText("..");
        parent.setPadding(0, 0, 0, 0);
        row.addView(parent, new LinearLayout.LayoutParams(0, -2, 1));
        row.setOnClickListener(v -> { bookmarkFolder = db.folderParent(bookmarkFolder); renderList(); renderBottom(); });
        listBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** 网站图标优先，未缓存时使用 Via 细描边星形。 */
    private void addBookmarkRow(BrowserDatabaseHelper.Entry e) {
        LinearLayout row = baseIconRow();
        addLibraryIcon(row, e.url);
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
        bindBookmarkSelection(row, "b:" + e.id);
        listBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout baseIconRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ViaUi.pageInset(this), dp(8), ViaUi.pageInset(this), dp(8));
        row.setMinimumHeight(dp(54));
        return row;
    }

    private void addLibraryIcon(LinearLayout row, String url) {
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        android.graphics.Bitmap favicon = url == null ? null
                : com.example.cleanrecovery.ui.browser.BrowserFavicons.get(url);
        if (favicon != null) {
            icon.setImageBitmap(favicon);
            // Via 网站图标约 18dp，保留原始颜色。
            icon.setPadding(dp(3), dp(3), dp(3), dp(3));
        } else {
            icon.setImageResource(url == null ? R.drawable.ic_via_library_folder : R.drawable.ic_via_star);
            icon.setColorFilter(0xff333333);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(24), dp(24));
        params.rightMargin = dp(14);
        row.addView(icon, params);
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
                                            if (name.equals(bookmarkFolder)) {
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
            ImageView check = historyEditMode ? selectionIndicator(historySelected.contains(e.id)) : null;
            View.OnLongClickListener longClick = historyEditMode ? null
                    : v -> { showEntryMenu(e.title, e.url, () -> { db.removeHistory(e.id); renderList(); }); return true; };
            addEntryRow("◷", 0xff333333,
                    TextUtils.isEmpty(e.title) ? e.url : e.title, e.url.replaceFirst("^https?://", ""),
                    v -> {
                        if (historyEditMode) {
                            if (historySelected.contains(e.id)) historySelected.remove(e.id);
                            else historySelected.add(e.id);
                            if (check != null) updateSelectionIndicator(check, historySelected.contains(e.id));
                            renderBottom();
                        } else {
                            returnUrl(e.url);
                        }
                    }, longClick, check);
            historyRowIds.add(e.id);
            count++;
        }
        if (count == 0) addEmpty(R.string.via_history_empty);
        historySelected.retainAll(historyRowIds);
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
        tv.setPadding(ViaUi.pageInset(this), dp(10), ViaUi.pageInset(this), dp(8));
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
        row.setPadding(ViaUi.pageInset(this), dp(8), ViaUi.pageInset(this), dp(8));
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
        iconParams.setMargins(0, 0, dp(16), 0);
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
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(dp(16), dp(16));
            tp.setMargins(dp(8), 0, dp(6), 0);
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
        if (closedTabsMode) {
            addBottom("更多", v -> showClosedTabSettings(), 1);
            addBottom("清空", v -> new ViaDialogBuilder(this).setTitle("清空关闭的标签页")
                    .setMessage("确定清空关闭的标签页？")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        new BrowserPrefs(this).setClosedTabs(""); renderList();
                    }).show(), 0);
        } else if (mode == MODE_BOOKMARKS) {
            if (bookmarkEditMode) renderBookmarkEditBottom();
            else {
                addBottom("更多", this::showBookmarkMore, 1);
                TextView edit = addBottom("编辑", v -> {
                    bookmarkEditMode = true; bookmarkSelected.clear(); renderList(); renderBottom();
                }, 0);
                edit.setEnabled(!bookmarkRowKeys.isEmpty());
            }
        } else if (mode == MODE_HISTORY) {
            if (historyEditMode) {
                // 对齐 Via：编辑模式底栏为 全选/删除（左） + 完成（右），未选中时删除置灰
                boolean all = !historyRowIds.isEmpty() && historySelected.containsAll(historyRowIds);
                addBottom(all ? "全不选" : "全选", v -> {
                    if (all) historySelected.clear();
                    else historySelected.addAll(historyRowIds);
                    renderList();
                    renderBottom();
                }, 0);
                TextView del = addBottom(deleteLabel(historySelected.size()), v -> deleteSelectedHistory(), 0);
                del.setEnabled(!historySelected.isEmpty());
                if (!historySelected.isEmpty()) del.setTextColor(0xffd44343);
                View spacer = new View(this);
                bottomBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 3));
                addBottom("完成", v -> exitHistoryEditMode(), 0);
            } else {
                addBottom("标签页", v -> { closedTabsMode = true; render(); }, 1);
                addBottom("清空", this::showClearHistoryRangeDialog, 0);
                TextView edit = addBottom("编辑", v -> {
                    historyEditMode = true;
                    historySelected.clear();
                    renderList();
                    renderBottom();
                }, 0);
                edit.setEnabled(!historyRowIds.isEmpty());
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
    private void showClearHistoryRangeDialog(View anchor) {
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
        showLibraryPopup(anchor, getString(R.string.via_history_clear_browsing), options,
                index -> confirmClearHistoryRange(picked.get(index), pickedLabels.get(index)), true);
    }

    private void confirmClearHistoryRange(long sinceMs, String label) {
        showLibraryConfirm(getString(R.string.via_history_clear_browsing),
                getString(R.string.via_history_clear_confirm, label), () -> {
                    db.clearHistorySince(sinceMs);
                    exitHistoryEditMode();
                    renderList();
                });
    }

    /** 更多菜单（基准 _via_bkmore：添加书签/新建文件夹/排序方式/显示细节/导入书签/备份书签）。 */
    private void showBookmarkMore(View anchor) {
        List<String> items = new ArrayList<>();
        items.add(getString(R.string.via_menu_add_bookmark));
        items.add(getString(R.string.via_new_folder));
        items.add(getString(R.string.via_sort_by));
        items.add(getString(showDetails
                ? R.string.via_hide_details : R.string.via_show_details));
        items.add(getString(R.string.via_import_bookmarks));
        items.add(getString(R.string.via_backup_bookmarks));
        showLibraryPopup(anchor, null, items, which -> {
            switch (which) {
                case 0: addBookmarkDialog(); break;
                case 1: launchNewFolder(bookmarkFolder); break;
                case 2: pickSort(); break;
                case 3:
                    showDetails = !showDetails; persistLibraryPrefs(); renderList(); break;
                case 4: importBookmarks(); break;
                case 5: backupBookmarks(); break;
            }
        }, false);
    }

    private void showLibraryPopup(View anchor, String title, List<String> items, ViaUi.OnPick pick, boolean alignEnd) {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, dp(14), 0, dp(14));
        GradientDrawable menuBackground = pill(Color.WHITE, 0xffe5e5e5, dp(1));
        menuBackground.setCornerRadius(dp(18));
        menu.setBackground(menuBackground);
        int width = Math.min(dp(234), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight() - 2 * ViaUi.pageInset(this));
        bookmarkMorePopup = new PopupWindow(menu, width, -2, true);
        bookmarkMorePopup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        bookmarkMorePopup.setOutsideTouchable(true);
        if (title != null) {
            TextView heading = popupText(title, true);
            menu.addView(heading, new LinearLayout.LayoutParams(-1, dp(44)));
        }
        for (int i = 0; i < items.size(); i++) {
            final int which = i;
            TextView item = popupText(items.get(i), false);
            menu.addView(item, new LinearLayout.LayoutParams(-1, dp(44)));
            item.setOnClickListener(v -> {
                    bookmarkMorePopup.dismiss();
                    pick.onPick(which);
                });
        }
        menu.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int[] position = new int[2];
        anchor.getLocationInWindow(position);
        int left = alignEnd ? position[0] + anchor.getWidth() - width : root.getPaddingLeft() + ViaUi.pageInset(this);
        int bottom = position[1] + (alignEnd ? anchor.getHeight() / 2 : 0);
        bookmarkMorePopup.showAtLocation(root, Gravity.TOP | Gravity.LEFT,
                Math.max(root.getPaddingLeft() + ViaUi.pageInset(this), left), Math.max(root.getPaddingTop(), bottom - menu.getMeasuredHeight()));
    }

    private TextView popupText(String text, boolean bold) {
        TextView label = rowText(text);
        label.setTextSize(14);
        label.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        label.setTextColor(0xff222222);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(16), 0, dp(16), 0);
        return label;
    }

    private String deleteLabel(int count) { return count == 0 ? "删除" : "删除(" + count + ")"; }

    private ImageView selectionIndicator(boolean selected) {
        ImageView view = new ImageView(this);
        updateSelectionIndicator(view, selected);
        return view;
    }

    private void updateSelectionIndicator(ImageView view, boolean selected) {
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(selected ? ViaUi.ACCENT : Color.TRANSPARENT);
        if (!selected) circle.setStroke(dp(2), 0xffe5e5e5);
        view.setImageDrawable(circle);
    }

    private android.app.Dialog libraryConfirmDialog;

    private void showLibraryConfirm(String title, String message, Runnable confirm) {
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, dp(12), 0, 0);
        GradientDrawable cardBackground = pill(Color.WHITE, 0, 0);
        cardBackground.setCornerRadius(dp(18));
        card.setBackground(cardBackground);
        TextView heading = popupText(title, true);
        heading.setTextSize(16);
        card.addView(heading, new LinearLayout.LayoutParams(-1, dp(40)));
        View gap = new View(this); card.addView(gap, new LinearLayout.LayoutParams(1, dp(6)));
        TextView body = popupText(message, false);
        body.setSingleLine(false); body.setEllipsize(null);
        body.setGravity(Gravity.TOP); body.setPadding(dp(16), dp(8), dp(16), 0);
        body.setMinHeight(dp(76));
        card.addView(body, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        buttons.setPadding(0, dp(12), 0, 0);
        for (int i = 0; i < 2; i++) {
            final boolean accept = i == 1;
            TextView button = popupText(getString(accept ? android.R.string.ok : android.R.string.cancel), false);
            button.setTextColor(ViaUi.ACCENT); button.setGravity(Gravity.CENTER);
            button.setPadding(0, 0, 0, 0);
            button.setOnClickListener(v -> { dialog.dismiss(); if (accept) confirm.run(); });
            buttons.addView(button, new LinearLayout.LayoutParams(dp(60), dp(52)));
        }
        card.addView(buttons, new LinearLayout.LayoutParams(-1, -2));
        dialog.setContentView(card);
        libraryConfirmDialog = dialog;
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setDimAmount(.4f);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setGravity(Gravity.CENTER);
            window.setLayout(Math.min(dp(338), root.getWidth() - dp(32)), -2);
        }
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
                        for (String parent : folders) if (!parent.isEmpty()) { db.moveFolder(folder, parent); break; }
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
            if (db.folderParent(folder).isEmpty()) appendFolderBackup(sb, folder);
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

    private void appendFolderBackup(StringBuilder out, String folder) {
        out.append("<DT><H3>").append(esc(folder)).append("</H3>\n<DL><p>\n");
        for (String child : db.listFolders()) if (db.folderParent(child).equals(folder)) appendFolderBackup(out, child);
        for (BrowserDatabaseHelper.Entry e : sortBookmarks(db.listBookmarks())) {
            if (folder.equals(e.folder)) out.append("<DT><A HREF=\"").append(esc(e.url)).append("\">")
                    .append(esc(e.title)).append("</A>\n");
        }
        out.append("</DL><p>\n");
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
        tv.setGravity(Gravity.CENTER);
        tv.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        tv.setSingleLine(true);
        tv.setMinWidth(dp(60));
        tv.setPadding(dp(16), 0, dp(16), 0);
        tv.setTextColor(new android.content.res.ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{0xff888888, 0xff222222}));
        tv.setOnClickListener(click);
        bottomBar.addView(tv, new LinearLayout.LayoutParams(-2, -1));
        if (weight > 0) bottomBar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, weight));
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
