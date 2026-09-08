package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import androidx.core.content.FileProvider;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.background.BackgroundDownloadService;
import com.example.cleanrecovery.background.DownloadQueueManager;
import com.example.cleanrecovery.background.DownloadTaskDbHelper;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * VIA 下载页（基准 .task/via-ref/58-downloads.png）：
 * 搜索胶囊 + 七类 chips（全部/文档/压缩包/安装包/图片/视频/音频）+ 日期分组头 +
 * 图标行（文件名 + 大小/进度副标题）+ 底栏 新建/编辑。
 * 数据源 = 下载任务库：嗅探「下载所选项」与后台队列任务均入库联动。
 */
public final class BrowserDownloadsActivity extends Activity {

    private static final String[] CHIPS = {"全部", "文档", "压缩包", "安装包", "图片", "视频", "音频"};

    private DownloadTaskDbHelper db;
    private final List<DownloadTaskDbHelper.DownloadRow> rows = new ArrayList<>();
    private final List<Object> listItems = new ArrayList<>(); // DownloadRow 或 String(日期头)
    private String chip = "全部";
    private String query = "";
    private LinearLayout chipsBox;
    private BaseAdapter adapter;
    private final Handler poll = new Handler(Looper.getMainLooper());
    private boolean polling;
    private final SimpleDateFormat dayFmt =
            new SimpleDateFormat("MM月dd日 EEE", Locale.CHINA);
    private final SimpleDateFormat dayKey =
            new SimpleDateFormat("yyyyMMdd", Locale.getDefault());

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            if (hasRunning()) poll.postDelayed(this, 1000);
            else polling = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser_downloads);
        db = DownloadTaskDbHelper.getInstance(this);
        // 进程重建可能直接恢复下载页，不能依赖先创建浏览器页来续跑已有任务。
        try {
            if (!db.getRestorableTasks().isEmpty()) {
                startService(new Intent(this, BackgroundDownloadService.class));
            }
        } catch (Exception e) {
            android.util.Log.w("BrowserDownloads", "后台下载服务启动失败: " + e.getMessage());
        }

        findViewById(R.id.dl_back).setOnClickListener(v -> finish());
        EditText search = findViewById(R.id.dl_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                rebuild();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        chipsBox = findViewById(R.id.dl_chips);
        buildChips();

        ListView list = findViewById(R.id.dl_list);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return listItems.size(); }
            @Override public Object getItem(int position) { return listItems.get(position); }
            @Override public long getItemId(int position) { return position; }

            @Override public View getView(int position, View convertView, ViewGroup parent) {
                Object item = listItems.get(position);
                if (item instanceof String) {
                    TextView head = convertView instanceof TextView && convertView.getTag() == "header"
                            ? (TextView) convertView : new TextView(BrowserDownloadsActivity.this);
                    head.setTag("header");
                    head.setText((String) item);
                    head.setTextSize(13);
                    head.setTextColor(0xff9e9e9e);
                    head.setPadding(dp(16), dp(14), dp(16), dp(6));
                    return head;
                }
                View row = convertView != null && convertView.getTag() == null
                        ? convertView : buildRowView();
                bindRow(row, (DownloadTaskDbHelper.DownloadRow) item);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.dl_empty));
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (listItems.get(pos) instanceof DownloadTaskDbHelper.DownloadRow) {
                openRow((DownloadTaskDbHelper.DownloadRow) listItems.get(pos));
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Object item = listItems.get(pos);
            if (!(item instanceof DownloadTaskDbHelper.DownloadRow)) return false;
            DownloadTaskDbHelper.DownloadRow row = (DownloadTaskDbHelper.DownloadRow) item;
            new AlertDialog.Builder(this)
                    .setTitle(R.string.via_dl_delete_confirm)
                    .setMessage(row.fileName)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        db.deleteRow(row.id);
                        GlassToast.makeText(this, R.string.via_dl_deleted, GlassToast.LENGTH_SHORT).show();
                        refresh();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });

        findViewById(R.id.dl_new).setOnClickListener(v -> showNewDownloadDialog());
        findViewById(R.id.dl_edit).setOnClickListener(v ->
                GlassToast.makeText(this, R.string.via_dl_edit_hint, GlassToast.LENGTH_SHORT).show());

        refresh();
    }

    /**
     * 新建下载（基准 .task/via-ref/86-newdownload-dialog.png）：
     * 标题「新建」+ URL（预填 https://）与文件名两行下划线输入 + 取消/下载 文字按钮。
     */
    private void showNewDownloadDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);

        final EditText urlInput = new EditText(this);
        urlInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setSingleLine(true);
        urlInput.setText("https://");
        box.addView(urlInput, new LinearLayout.LayoutParams(-1, -2));

        final EditText nameInput = new EditText(this);
        nameInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        nameInput.setSingleLine(true);
        nameInput.setHint(R.string.via_dl_filename_hint);
        box.addView(nameInput, new LinearLayout.LayoutParams(-1, -2));

        new AlertDialog.Builder(this)
                .setTitle(R.string.via_dl_new)
                .setView(box)
                .setPositiveButton(R.string.via_dl_action, (d, w) -> {
                    String url = urlInput.getText().toString().trim();
                    if (url.isEmpty() || "https://".equals(url) || "http://".equals(url)) {
                        GlassToast.makeText(this, R.string.via_dl_url_required, GlassToast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!url.matches("(?i)^[a-z][a-z0-9+.-]*://.*")) url = "https://" + url;
                    String name = nameInput.getText().toString().trim();
                    DownloadQueueManager queue = DownloadQueueManager.getInstance();
                    queue.init(this);
                    com.example.cleanrecovery.ui.browser.BrowserPrefs browserPrefs = new com.example.cleanrecovery.ui.browser.BrowserPrefs(this);
                    java.util.Map<String, String> headers = java.util.Collections.singletonMap("User-Agent",
                            browserPrefs.getEffectiveUserAgent(false, "", android.webkit.WebSettings.getDefaultUserAgent(this)));
                    int id = queue.enqueue(url, null, null, null, name.isEmpty() ? null : name, headers);
                    if (id >= 0) startService(new Intent(this, BackgroundDownloadService.class));
                    GlassToast.makeText(this, R.string.via_sniffer_download_started, GlassToast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(R.string.via_cancel, null)
                .show();
    }

    private void buildChips() {
        chipsBox.removeAllViews();
        for (final String label : CHIPS) {
            TextView tv = new TextView(this);
            tv.setText(label);
            tv.setTextSize(14);
            tv.setPadding(dp(18), dp(7), dp(18), dp(7));
            tv.setGravity(Gravity.CENTER);
            styleChip(tv, label.equals(chip));
            tv.setOnClickListener(v -> {
                chip = label;
                for (int i = 0; i < chipsBox.getChildCount(); i++) {
                    styleChip((TextView) chipsBox.getChildAt(i), CHIPS[i].equals(chip));
                }
                rebuild();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(10);
            chipsBox.addView(tv, lp);
        }
    }

    private void styleChip(TextView tv, boolean selected) {
        tv.setBackgroundResource(selected
                ? R.drawable.bg_via_chip_selected : R.drawable.bg_via_chip);
        tv.setTextColor(selected ? 0xff6f8de1 : getColor(R.color.text_primary));
    }

    private void refresh() {
        rows.clear();
        rows.addAll(db.listRows());
        rebuild();
        if (hasRunning() && !polling) {
            polling = true;
            poll.postDelayed(tick, 1000);
        }
    }

    private boolean hasRunning() {
        for (DownloadTaskDbHelper.DownloadRow r : rows) {
            if (r.running()) return true;
        }
        return false;
    }

    private void rebuild() {
        listItems.clear();
        String lastDay = null;
        for (DownloadTaskDbHelper.DownloadRow r : rows) {
            if (!"全部".equals(chip) && !chip.equals(r.category)) continue;
            String hay = ((r.fileName == null ? "" : r.fileName) + " "
                    + (r.pageTitle == null ? "" : r.pageTitle) + " "
                    + (r.url == null ? "" : r.url)).toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !hay.contains(query)) continue;
            String day = dayKey.format(new Date(r.createdAt));
            if (!day.equals(lastDay)) {
                listItems.add(dayFmt.format(new Date(r.createdAt)));
                lastDay = day;
            }
            listItems.add(r);
        }
        adapter.notifyDataSetChanged();
    }

    // ===== 行视图（代码构建：描边圆角图标 + 文件名 + 状态/大小） =====

    private View buildRowView() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(14);
        row.setPadding(pad, dp(10), dp(16), dp(10));
        row.setMinimumHeight(dp(64));

        FrameLayout iconBox = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xffffffff);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xff3c3c3c);
        iconBox.setBackground(bg);
        TextView glyph = new TextView(this);
        glyph.setId(android.R.id.icon);
        glyph.setGravity(Gravity.CENTER);
        glyph.setTextSize(16);
        glyph.setTextColor(0xff3c3c3c);
        iconBox.addView(glyph, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(40), dp(40));
        ip.rightMargin = dp(14);
        row.addView(iconBox, ip);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setId(android.R.id.text1);
        title.setTextSize(15);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTextColor(getColor(R.color.text_primary));
        texts.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView sub = new TextView(this);
        sub.setId(android.R.id.text2);
        sub.setTextSize(12);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        sub.setTextColor(0xff999999);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(3);
        texts.addView(sub, sp);
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    private void bindRow(View row, DownloadTaskDbHelper.DownloadRow r) {
        TextView glyph = row.findViewById(android.R.id.icon);
        glyph.setText(glyphFor(r.category));
        TextView title = row.findViewById(android.R.id.text1);
        title.setText(r.fileName == null || r.fileName.isEmpty()
                ? shortUrl(r.url) : r.fileName);
        TextView sub = row.findViewById(android.R.id.text2);
        sub.setText(subtitle(r));
    }

    private String glyphFor(String category) {
        if (category == null) return "≡";
        switch (category) {
            case "视频": return "▶";
            case "音频": return "♪";
            case "图片": return "▣";
            case "安装包": return "◆";
            case "压缩包": return "▤";
            default: return "≡";
        }
    }

    private String subtitle(DownloadTaskDbHelper.DownloadRow r) {
        switch (r.status == null ? "" : r.status) {
            case "RUNNING": {
                String done = humanBytes(r.downloaded);
                if (r.totalSize > 0 && r.downloaded > 0) {
                    int pct = (int) Math.min(100, r.downloaded * 100 / r.totalSize);
                    return getString(R.string.via_dl_running_fmt, pct,
                            done + " / " + humanBytes(r.totalSize));
                }
                if (r.downloaded > 0) return getString(R.string.via_dl_running_no_total, done);
                return getString(R.string.via_dl_pending);
            }
            case "PENDING":
                return getString(R.string.via_dl_pending);
            case "FAILED":
                return getString(R.string.via_dl_failed_fmt,
                        r.errorMessage == null ? "" : r.errorMessage);
            case "CANCELLED":
                return getString(R.string.via_dl_cancelled);
            default:
                long size = r.totalSize > 0 ? r.totalSize
                        : (r.downloaded > 0 ? r.downloaded : 0);
                if (size == 0 && r.resultPath != null) {
                    File f = new File(r.resultPath);
                    if (f.isFile()) size = f.length();
                }
                return size > 0 ? humanBytes(size)
                        : (r.resultPath == null || r.resultPath.isEmpty()
                                ? "" : shortUrl(r.url));
        }
    }

    private static String humanBytes(long b) {
        if (b <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        double v = b;
        int u = 0;
        while (v >= 1024 && u < units.length - 1) { v /= 1024; u++; }
        return u == 0 ? String.format(Locale.US, "%.1f %s", v, units[u])
                : String.format(Locale.US, "%.1f %s", v, units[u]);
    }

    private void openRow(DownloadTaskDbHelper.DownloadRow r) {
        if (r.running()) {
            GlassToast.makeText(this, getString(R.string.via_dl_running_no_total,
                    humanBytes(r.downloaded)), GlassToast.LENGTH_SHORT).show();
            return;
        }
        if (r.resultPath == null || r.resultPath.isEmpty()) {
            GlassToast.makeText(this, R.string.via_downloads_file_missing, GlassToast.LENGTH_SHORT).show();
            return;
        }
        File file = new File(r.resultPath);
        if (!file.exists()) {
            GlassToast.makeText(this, R.string.via_downloads_file_missing, GlassToast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, r.mimeType != null ? r.mimeType : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.via_menu_open_with)));
        } catch (Exception e) {
            GlassToast.makeText(this, e.getMessage(), GlassToast.LENGTH_SHORT).show();
        }
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        return url.length() > 96 ? url.substring(0, 96) + "…" : url;
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onPause() {
        super.onPause();
        poll.removeCallbacks(tick);
        polling = false;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
