package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.background.DownloadQueueManager;
import com.example.cleanrecovery.background.DownloadTaskDbHelper;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** VIA 风格下载管理页：展示浏览器后台下载队列与历史。 */
public final class BrowserDownloadsActivity extends Activity {

    private DownloadTaskDbHelper db;
    private List<DownloadQueueManager.DownloadTask> tasks = new ArrayList<>();
    private final ArrayList<String> display = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser_list);

        db = new DownloadTaskDbHelper(this);
        ((TextView) findViewById(R.id.mgr_title)).setText(R.string.via_downloads_title);
        ImageButton action = findViewById(R.id.mgr_action);
        action.setVisibility(android.view.View.VISIBLE);
        action.setImageResource(R.drawable.ic_delete);
        action.setContentDescription(getString(R.string.via_downloads_clear_finished));

        ListView list = findViewById(R.id.mgr_list);
        TextView empty = findViewById(R.id.mgr_empty);
        empty.setText(R.string.via_downloads_empty);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, display);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        findViewById(R.id.mgr_back).setOnClickListener(v -> finish());
        action.setOnClickListener(v -> confirmClearFinished());
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < tasks.size()) openTask(tasks.get(position));
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < tasks.size()) {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("url", tasks.get(position).url));
                    Toast.makeText(this, R.string.via_downloads_url_copied,
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        });
        refresh();
    }

    private void refresh() {
        tasks = db.getAllTasks();
        display.clear();
        for (DownloadQueueManager.DownloadTask task : tasks) display.add(label(task));
        adapter.notifyDataSetChanged();
    }

    private String label(DownloadQueueManager.DownloadTask task) {
        String title = task.pageTitle != null && !task.pageTitle.isEmpty()
                ? task.pageTitle : shortUrl(task.url);
        StringBuilder b = new StringBuilder();
        b.append(statusLabel(task.status)).append("  ").append(title);
        b.append("\n").append(shortUrl(task.url));
        if (task.resultPath != null && !task.resultPath.isEmpty()) {
            b.append("\n").append(task.resultPath);
        } else if (task.errorMessage != null && !task.errorMessage.isEmpty()) {
            b.append("\n").append(task.errorMessage);
        }
        return b.toString();
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        return url.length() > 96 ? url.substring(0, 96) + "…" : url;
    }

    private String statusLabel(DownloadQueueManager.DownloadTask.TaskStatus status) {
        if (status == DownloadQueueManager.DownloadTask.TaskStatus.COMPLETED) {
            return getString(R.string.via_download_status_completed);
        }
        if (status == DownloadQueueManager.DownloadTask.TaskStatus.RUNNING) {
            return getString(R.string.via_download_status_running);
        }
        if (status == DownloadQueueManager.DownloadTask.TaskStatus.FAILED) {
            return getString(R.string.via_download_status_failed);
        }
        if (status == DownloadQueueManager.DownloadTask.TaskStatus.CANCELLED) {
            return getString(R.string.via_download_status_cancelled);
        }
        return getString(R.string.via_download_status_pending);
    }

    private void openTask(DownloadQueueManager.DownloadTask task) {
        if (task.resultPath == null || task.resultPath.isEmpty()) {
            Toast.makeText(this, statusLabel(task.status), Toast.LENGTH_SHORT).show();
            return;
        }
        File file = new File(task.resultPath);
        if (!file.exists()) {
            Toast.makeText(this, R.string.via_downloads_file_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, task.mimeType != null ? task.mimeType : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.via_menu_open_with)));
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClearFinished() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_downloads_clear_finished)
                .setMessage(R.string.via_downloads_clear_finished_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    int count = db.clearFinishedTasks();
                    Toast.makeText(this,
                            getString(R.string.via_downloads_cleared, count),
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .show();
    }
}
