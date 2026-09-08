package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.background.DownloadTaskDbHelper;
import com.example.cleanrecovery.download.UniversalDownloadManager;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.SnifferLog;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 资源嗅探整页（真 Via 基准 .task/via-ref/79-sniffer-panel.png）：
 * 顶栏 标题「资源嗅探」+ 刷新；提示行「下列是网页加载中的资源日志。」；
 * 日志条目 = 时间 + 红色 load 徽标 + 蓝色类型徽标 + URL（域名粗体）。
 * 点条目 = 新标签打开；长按 = 下载 / 新标签打开 / 复制链接；刷新 = 清空日志。
 */
public final class BrowserSnifferActivity extends Activity {

    public static final String EXTRA_OPEN_URL = "open_url";

    private static final int BADGE_LOAD_BG = 0xFFD97B74;
    private static final int BADGE_TYPE_BG = 0xFF6F8FD8;

    private final List<SnifferLog.Entry> entries = SnifferLog.snapshot();
    private EntryAdapter adapter;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private final Handler main = new Handler(Looper.getMainLooper());

    private final class EntryAdapter extends android.widget.BaseAdapter {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView : newEntryView();
            bindRow(row, entries.get(position));
            return row;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser_sniffer);

        findViewById(R.id.sn_page_back).setOnClickListener(v -> finish());
        findViewById(R.id.sn_page_action).setOnClickListener(v -> {
            SnifferLog.clear();
            entries.clear();
            adapter.notifyDataSetChanged();
        });

        adapter = new EntryAdapter();
        ListView list = findViewById(R.id.sn_list);
        list.setAdapter(adapter);
        list.setEmptyView(findViewById(R.id.sn_empty));
        list.setOnItemClickListener((p, v, pos, id) -> openEntry(entries.get(pos).url));
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            showEntryMenu(entries.get(pos).url);
            return true;
        });
    }

    @Override protected void onResume() {
        super.onResume();
        entries.clear();
        entries.addAll(SnifferLog.snapshot());
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private View newEntryView() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(18), dp(16), dp(10));
        TextView head = new TextView(this);
        head.setId(android.R.id.text1);
        head.setSingleLine(true);
        head.setEllipsize(null);
        row.addView(head, new LinearLayout.LayoutParams(-2, -2));
        TextView url = new TextView(this);
        url.setId(android.R.id.text2);
        url.setTextSize(14);
        url.setTextColor(getColor(R.color.text_primary));
        url.setSingleLine(true);
        url.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1, -2);
        up.topMargin = dp(6);
        row.addView(url, up);
        return row;
    }

    private void bindRow(View row, SnifferLog.Entry e) {
        TextView head = row.findViewById(android.R.id.text1);
        head.setText(headSpans(e));
        TextView url = row.findViewById(android.R.id.text2);
        url.setText(urlSpans(e.url));
    }

    /** 行首：时间（灰） + load 徽标（红底白字） + 类型徽标（蓝底白字）。 */
    private CharSequence headSpans(SnifferLog.Entry e) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        String time = timeFmt.format(new Date(e.timeMs));
        b.append(time);
        b.setSpan(new ForegroundColorSpan(0xff9e9e9e), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new AbsoluteSizeSpan(13, true), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        badge(b, " load ", BADGE_LOAD_BG);
        badge(b, " " + e.type + " ", BADGE_TYPE_BG);
        return b;
    }

    private void badge(SpannableStringBuilder b, String text, int bg) {
        int start = b.length();
        b.append("  ");
        b.append(text);
        int end = b.length();
        b.setSpan(new BackgroundColorSpan(bg), start + 2, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(Color.WHITE), start + 2, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new AbsoluteSizeSpan(12, true), start + 2, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** URL 行：域名粗体（基准 79：10.0.2.2:8765 加粗）。 */
    private CharSequence urlSpans(String url) {
        SpannableStringBuilder b = new SpannableStringBuilder(url == null ? "" : url);
        int hostEnd = hostEndOf(url);
        if (hostEnd > 0) {
            b.setSpan(new StyleSpan(Typeface.BOLD), 0, hostEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return b;
    }

    private static int hostEndOf(String url) {
        if (url == null) return 0;
        int scheme = url.indexOf("://");
        if (scheme < 0) return 0;
        int start = scheme + 3;
        int slash = url.indexOf('/', start);
        return slash > start ? slash : url.length();
    }

    private void openEntry(String url) {
        Intent data = new Intent();
        data.putExtra(EXTRA_OPEN_URL, url);
        setResult(RESULT_OK, data);
        finish();
    }

    private void showEntryMenu(String url) {
        String[] items = {
                getString(R.string.via_menu_download),
                getString(R.string.via_bk_open_newtab),
                getString(R.string.via_bk_copy_link),
                "调用外部播放器播放"
        };
        new AlertDialog.Builder(this)
                .setTitle(shortOf(url))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        download(url);
                    } else if (which == 1) {
                        openEntry(url);
                    } else if (which == 2) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("url", url));
                        GlassToast.makeText(this, R.string.via_copied, GlassToast.LENGTH_SHORT).show();
                    } else {
                        playWithExternalPlayer(url);
                    }
                }).show();
    }

    private void playWithExternalPlayer(String url) {
        try {
            BrowserPrefs prefs = new BrowserPrefs(this);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.parse(url), "video/*");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (prefs.externalPlayer() == 1) {
                startActivity(Intent.createChooser(intent, "选择视频播放器"));
            } else {
                startActivity(intent);
            }
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(Intent.createChooser(fallback, "选择打开方式"));
            } catch (Exception ex) {
                GlassToast.makeText(this, "无法启动外部播放器", GlassToast.LENGTH_SHORT).show();
            }
        }
    }

    /** 直接下载（UniversalDownloadManager），进度/结果写入任务库供下载页展示。 */
    private void download(String url) {
        DownloadTaskDbHelper taskDb = DownloadTaskDbHelper.getInstance(this);
        String fileName = fileNameOf(url);
        final int taskId = taskDb.startSniffedTask(url, null, "", fileName,
                DownloadTaskDbHelper.categoryOf(url, null));
        File dir = downloadDir();
        File outFile = new File(dir, fileName);
        GlassToast.makeText(this, R.string.via_menu_download, GlassToast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                new UniversalDownloadManager().downloadSmart(url, outFile,
                        new com.example.cleanrecovery.download.DownloadProgressCallback() {
                            @Override public void onProgress(long downloadedBytes, long totalBytes,
                                                             long speedBps, int percent) {
                                if (taskId > 0) taskDb.updateProgress(taskId, downloadedBytes, totalBytes);
                            }
                            @Override public void onStatusChanged(String status, String message) { }
                            @Override public void onComplete(String path) {
                                File f = new File(path);
                                if (taskId > 0) {
                                    taskDb.finishSniffedTask(taskId, path, f.isFile() ? f.length() : 0);
                                }
                            }
                            @Override public void onError(String errorCode, String message) {
                                if (taskId > 0) taskDb.failSniffedTask(taskId, message);
                            }
                        });
            } catch (Exception e) {
                if (taskId > 0) taskDb.failSniffedTask(taskId, e.getMessage());
                main.post(() -> GlassToast.makeText(this,
                        R.string.via_downloads_file_missing, GlassToast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private File downloadDir() {
        // P0③ 下载目录合一：设置→下载目录 优先，不可用回退历史路径
        File configured = new BrowserPrefs(this).downloadDirFile();
        if (configured != null) return configured;
        File externalRoot = Environment.getExternalStorageDirectory();
        File dir;
        if (externalRoot != null && "mounted".equals(Environment.getExternalStorageState())) {
            dir = new File(new File(externalRoot, "DataRecovery"), "Downloads");
        } else {
            File base = getExternalFilesDir(null);
            if (base == null) base = getFilesDir();
            dir = new File(base, "DataRecovery/Downloads");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
        }
        return dir;
    }

    private static String shortOf(String url) {
        if (url == null) return "";
        return url.length() > 80 ? url.substring(0, 80) + "…" : url;
    }

    private static String fileNameOf(String url) {
        String path = url;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.isEmpty()) name = "resource_" + System.currentTimeMillis();
        return name;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
