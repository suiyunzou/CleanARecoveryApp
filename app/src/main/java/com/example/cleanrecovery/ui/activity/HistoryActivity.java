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

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 历史管理页：列表 + 清空 + 点击打开。 */
public final class HistoryActivity extends Activity {

    private BrowserDatabaseHelper db;
    private List<BrowserDatabaseHelper.Entry> entries = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ArrayList<String> display = new ArrayList<>();
    private final SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser_list);

        db = BrowserDatabaseHelper.getInstance(this);
        ((TextView) findViewById(R.id.mgr_title)).setText(R.string.via_history_title);
        ImageButton action = findViewById(R.id.mgr_action);
        action.setVisibility(View.VISIBLE);
        action.setImageResource(R.drawable.ic_delete);
        action.setContentDescription(getString(R.string.via_history_clear));

        ListView list = findViewById(R.id.mgr_list);
        TextView empty = findViewById(R.id.mgr_empty);
        empty.setText(R.string.via_history_empty);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, display);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        findViewById(R.id.mgr_back).setOnClickListener(v -> finish());
        action.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.via_history_clear)
                .setMessage(R.string.via_history_clear)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    db.clearHistory();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show());

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < entries.size()) {
                Intent data = new Intent();
                data.putExtra("url", entries.get(position).url);
                setResult(RESULT_OK, data);
                finish();
            }
        });
        refresh();
    }

    private void refresh() {
        entries = db.listHistory();
        display.clear();
        for (BrowserDatabaseHelper.Entry e : entries) {
            String t = e.title == null || e.title.isEmpty() ? e.url : e.title;
            display.add(sdf.format(new Date(e.time)) + "  " + t + "\n" + e.url);
        }
        adapter.notifyDataSetChanged();
    }
}
