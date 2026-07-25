package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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

import java.util.ArrayList;
import java.util.List;

/** 书签管理页：列表 + 删除 + 点击打开。 */
public final class BookmarksActivity extends Activity {

    private BrowserDatabaseHelper db;
    private List<BrowserDatabaseHelper.Entry> entries = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ArrayList<String> display = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser_list);

        db = BrowserDatabaseHelper.getInstance(this);
        ((TextView) findViewById(R.id.mgr_title)).setText(R.string.via_bookmarks_title);
        findViewById(R.id.mgr_action).setVisibility(View.VISIBLE);
        findViewById(R.id.mgr_action).setContentDescription(getString(R.string.via_bookmark_add_current));

        ListView list = findViewById(R.id.mgr_list);
        TextView empty = findViewById(R.id.mgr_empty);
        empty.setText(R.string.via_bookmarks_empty);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, display);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        findViewById(R.id.mgr_back).setOnClickListener(v -> finish());
        findViewById(R.id.mgr_action).setOnClickListener(v -> addCurrent());

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < entries.size()) {
                Intent data = new Intent();
                data.putExtra("url", entries.get(position).url);
                setResult(RESULT_OK, data);
                finish();
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < entries.size()) {
                BrowserDatabaseHelper.Entry e = entries.get(position);
                db.removeBookmark(e.id);
                Toast.makeText(this, R.string.via_bookmark_removed, Toast.LENGTH_SHORT).show();
                refresh();
                return true;
            }
            return false;
        });
        refresh();
    }

    private void addCurrent() {
        String current = getIntent().getStringExtra("current_url");
        final String url = current != null ? current : "";
        if (url.isEmpty()) {
            Toast.makeText(this, R.string.via_bookmarks_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_bookmark_add_current)
                .setMessage(url)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    db.addBookmark(url, url);
                    Toast.makeText(this, R.string.via_bookmark_added, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refresh() {
        entries = db.listBookmarks();
        display.clear();
        for (BrowserDatabaseHelper.Entry e : entries) {
            String t = e.title == null || e.title.isEmpty() ? e.url : e.title;
            display.add(t + "\n" + e.url);
        }
        adapter.notifyDataSetChanged();
    }
}
