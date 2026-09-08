package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * VIA 整页编辑器（基准 .task/via-ref/72-newfolder.png、76-edit-bk-dropdown.png）：
 * 顶栏 返回 + 标题 + 完成；表单为下划线输入行 + 文件夹下拉行（内联展开）。
 * 模式：新建文件夹（标题 + 父目录）／编辑书签（标题 + 地址 + 目录）。
 */
public final class BookmarkEditorActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    public static final int MODE_NEW_FOLDER = 0;
    public static final int MODE_EDIT_BOOKMARK = 1;
    public static final String EXTRA_PARENT = "parent";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_FOLDER = "folder";
    /** 新建文件夹完成后回传名称。 */
    public static final String EXTRA_RESULT_NAME = "result_name";

    private static final int REQ_CHILD_FOLDER = 5001;

    private BrowserDatabaseHelper db;
    private int mode;
    private long bookmarkId;
    /** 当前选中目录（新建文件夹=父目录；编辑书签=目标目录）。 */
    private String folder = "根目录";
    private EditText titleInput;
    private EditText urlInput;
    private TextView folderValue;
    private LinearLayout dropdownBox;
    private ImageView chevron;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        db = BrowserDatabaseHelper.getInstance(this);
        mode = getIntent().getIntExtra(EXTRA_MODE, MODE_NEW_FOLDER);
        bookmarkId = getIntent().getLongExtra(EXTRA_ID, -1);
        String parent = mode == MODE_EDIT_BOOKMARK
                ? getIntent().getStringExtra(EXTRA_FOLDER)
                : getIntent().getStringExtra(EXTRA_PARENT);
        folder = TextUtils.isEmpty(parent) ? "根目录" : parent;
        buildPage();
    }

    private void buildPage() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(0, statusBarHeight(), 0, 0);
        setContentView(root, new LinearLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(16), dp(6));
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(56)));

        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(R.drawable.bg_via_toolbar_button);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText(mode == MODE_NEW_FOLDER
                ? getString(R.string.via_new_folder) : getString(R.string.via_edit_bookmark));
        title.setTextSize(19);
        title.setTextColor(getColor(R.color.text_primary));
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = dp(14);
        top.addView(title, tp);

        TextView done = new TextView(this);
        done.setText(R.string.via_done);
        done.setTextSize(16);
        done.setTextColor(getColor(R.color.text_primary));
        done.setPadding(dp(12), dp(8), dp(4), dp(8));
        done.setOnClickListener(v -> onDone());
        top.addView(done, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(18), dp(20), 0);
        root.addView(form, new LinearLayout.LayoutParams(-1, -2));

        titleInput = addInput(form, getString(R.string.via_bk_field_title),
                getIntent().getStringExtra(EXTRA_TITLE));
        urlInput = null;
        if (mode == MODE_EDIT_BOOKMARK) {
            urlInput = addInput(form, getString(R.string.via_bk_field_url),
                    getIntent().getStringExtra(EXTRA_URL));
        }
        addFolderRow(form);
        dropdownBox = new LinearLayout(this);
        dropdownBox.setOrientation(LinearLayout.VERTICAL);
        dropdownBox.setVisibility(View.GONE);
        form.addView(dropdownBox, new LinearLayout.LayoutParams(-1, -2));
    }

    private EditText addInput(LinearLayout form, String hint, String initial) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setText(initial == null ? "" : initial);
        et.setTextSize(16);
        et.setTextColor(getColor(R.color.text_primary));
        et.setHintTextColor(0xff9e9e9e);
        et.setBackgroundResource(R.drawable.bg_via_input);
        et.setPadding(dp(2), dp(10), dp(2), dp(12));
        et.setSingleLine(true);
        form.addView(et, new LinearLayout.LayoutParams(-1, -2));
        return et;
    }

    /** 文件夹下拉行：图标 + 当前目录（只读） + 展开箭头，点击内联展开目录列表。 */
    private void addFolderRow(LinearLayout form) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setOnClickListener(v -> toggleDropdown());

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_nav_folder);
        icon.setColorFilter(0xff3c3c3c);
        row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

        folderValue = new TextView(this);
        folderValue.setText(folder);
        folderValue.setTextSize(16);
        folderValue.setTextColor(getColor(R.color.text_primary));
        folderValue.setBackgroundResource(R.drawable.bg_via_input);
        folderValue.setPadding(dp(14), dp(10), dp(40), dp(12));
        row.addView(folderValue, new LinearLayout.LayoutParams(0, -2, 1));

        chevron = new ImageView(this);
        chevron.setImageResource(R.drawable.ic_chevron_down);
        chevron.setColorFilter(0xff3c3c3c);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(22), dp(22));
        cp.leftMargin = dp(-34);
        row.addView(chevron, cp);

        form.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void toggleDropdown() {
        if (dropdownBox.getVisibility() == View.VISIBLE) {
            dropdownBox.setVisibility(View.GONE);
            chevron.setRotation(0);
            return;
        }
        rebuildDropdown();
        dropdownBox.setVisibility(View.VISIBLE);
        chevron.setRotation(180);
    }

    private void rebuildDropdown() {
        dropdownBox.removeAllViews();
        List<String> options = new ArrayList<>();
        options.add("根目录");
        options.addAll(db.listFolders());
        int pad = dp(10);
        for (int i = 0; i < options.size(); i++) {
            String name = options.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            boolean selected = name.equals(folder);
            row.setPadding(pad, dp(12), pad, dp(12));
            row.setClickable(true);
            row.setBackgroundResource(selected
                    ? R.drawable.bg_via_select_pill : R.drawable.bg_via_menu_cell);
            ImageView ic = new ImageView(this);
            ic.setImageResource(R.drawable.ic_nav_folder);
            ic.setColorFilter(0xff3c3c3c);
            row.addView(ic, new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView tv = new TextView(this);
            tv.setText(name);
            tv.setTextSize(15);
            tv.setTextColor(getColor(R.color.text_primary));
            tv.setPadding(dp(16), 0, 0, 0);
            row.addView(tv);
            final String picked = name;
            row.setOnClickListener(v -> {
                folder = picked;
                folderValue.setText(picked);
                dropdownBox.setVisibility(View.GONE);
                chevron.setRotation(0);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(2), 0, dp(2));
            dropdownBox.addView(row, lp);
        }
        // 末行：新建文件夹…（对齐基准 76：跳到新建文件夹页）
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(pad, dp(12), pad, dp(12));
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.bg_via_menu_cell);
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_new_folder);
        ic.setColorFilter(0xff3c3c3c);
        row.addView(ic, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView tv = new TextView(this);
        tv.setText(R.string.via_new_folder_dots);
        tv.setTextSize(15);
        tv.setTextColor(getColor(R.color.text_primary));
        tv.setPadding(dp(16), 0, 0, 0);
        row.addView(tv);
        row.setOnClickListener(v -> {
            Intent it = new Intent(this, BookmarkEditorActivity.class);
            it.putExtra(EXTRA_MODE, MODE_NEW_FOLDER);
            it.putExtra(EXTRA_PARENT, folder);
            startActivityForResult(it, REQ_CHILD_FOLDER);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, dp(2), 0, dp(2));
        dropdownBox.addView(row, lp);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CHILD_FOLDER && resultCode == RESULT_OK
                && data != null && dropdownBox != null) {
            String name = data.getStringExtra(EXTRA_RESULT_NAME);
            if (name != null && !name.isEmpty()) {
                folder = name;
                folderValue.setText(name);
                rebuildDropdown();
            }
        }
    }

    private void onDone() {
        String title = titleInput.getText().toString().trim();
        if (title.isEmpty()) {
            GlassToast.makeText(this, getString(R.string.via_title_required, "标题"),
                    GlassToast.LENGTH_SHORT).show();
            return;
        }
        if (mode == MODE_NEW_FOLDER) {
            if (!db.addFolder(title)) {
                GlassToast.makeText(this, R.string.via_bk_folder_exists, GlassToast.LENGTH_SHORT).show();
                return;
            }
            Intent data = new Intent();
            data.putExtra(EXTRA_RESULT_NAME, title);
            setResult(RESULT_OK, data);
            finish();
            return;
        }
        String url = urlInput == null ? "" : urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            GlassToast.makeText(this, getString(R.string.via_title_required, "地址"),
                    GlassToast.LENGTH_SHORT).show();
            return;
        }
        db.updateBookmark(bookmarkId, title, url, folder);
        setResult(RESULT_OK);
        finish();
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
