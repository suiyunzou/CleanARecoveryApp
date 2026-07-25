package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** VIA 风格离线页面列表：点击打开已保存 MHTML，长按删除记录与文件。 */
public final class BrowserOfflinePagesActivity extends Activity {

    private BrowserDatabaseHelper db;
    private List<BrowserDatabaseHelper.OfflineEntry> entries = new ArrayList<>();
    private final ArrayList<String> display = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private final SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser_list);

        db = BrowserDatabaseHelper.getInstance(this);
        ((TextView) findViewById(R.id.mgr_title)).setText(R.string.via_menu_offline);
        ImageButton action = findViewById(R.id.mgr_action);
        action.setVisibility(View.VISIBLE);
        action.setImageResource(R.drawable.ic_delete);
        action.setContentDescription(getString(R.string.via_offline_clear_missing));

        ListView list = findViewById(R.id.mgr_list);
        TextView empty = findViewById(R.id.mgr_empty);
        empty.setText(R.string.via_offline_empty);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, display);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        findViewById(R.id.mgr_back).setOnClickListener(v -> finish());
        action.setOnClickListener(v -> clearMissing());

        list.setOnItemClickListener((parent, view, position, id) -> open(position));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            delete(position);
            return true;
        });
        refresh();
    }

    private void open(int position) {
        if (position < 0 || position >= entries.size()) return;
        BrowserDatabaseHelper.OfflineEntry e = entries.get(position);
        File file = new File(e.filePath);
        if (!file.isFile()) {
            Toast.makeText(this, R.string.via_downloads_file_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent data = new Intent();
        data.putExtra("url", Uri.fromFile(file).toString());
        setResult(RESULT_OK, data);
        finish();
    }

    private void delete(int position) {
        if (position < 0 || position >= entries.size()) return;
        BrowserDatabaseHelper.OfflineEntry e = entries.get(position);
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_offline_delete)
                .setMessage(e.title + "\n" + e.url)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    db.removeOfflinePage(e.id);
                    try {
                        File f = new File(e.filePath);
                        if (f.isFile()) f.delete();
                    } catch (Exception ignored) {
                    }
                    refresh();
                    Toast.makeText(this, R.string.via_offline_deleted, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void clearMissing() {
        int count = 0;
        for (BrowserDatabaseHelper.OfflineEntry e : new ArrayList<>(entries)) {
            if (!new File(e.filePath).isFile()) {
                if (db.removeOfflinePage(e.id)) count++;
            }
        }
        refresh();
        Toast.makeText(this, getString(R.string.via_offline_missing_cleared, count),
                Toast.LENGTH_SHORT).show();
    }

    private void refresh() {
        entries = db.listOfflinePages();
        display.clear();
        for (BrowserDatabaseHelper.OfflineEntry e : entries) {
            String title = e.title == null || e.title.isEmpty() ? e.filePath : e.title;
            display.add(sdf.format(new Date(e.time)) + "  " + title + "\n" + e.url);
        }
        adapter.notifyDataSetChanged();
    }
}
