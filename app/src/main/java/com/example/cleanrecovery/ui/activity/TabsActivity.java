package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.TabSnapshotHolder;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.util.ArrayList;

/** 标签管理页：列表 + 关闭 + 新建。 */
public final class TabsActivity extends Activity {

    private ArrayAdapter<String> adapter;
    private final ArrayList<String> display = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser_list);

        ((TextView) findViewById(R.id.mgr_title)).setText(R.string.via_tabs_title);
        ImageButton action = findViewById(R.id.mgr_action);
        action.setVisibility(View.VISIBLE);
        action.setImageResource(R.drawable.ic_add);
        action.setContentDescription(getString(R.string.via_tabs_new));

        ListView list = findViewById(R.id.mgr_list);
        TextView empty = findViewById(R.id.mgr_empty);
        empty.setText(R.string.via_tabs_empty);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, display);
        list.setAdapter(adapter);
        list.setEmptyView(empty);

        findViewById(R.id.mgr_back).setOnClickListener(v -> finish());
        action.setOnClickListener(v -> {
            Intent data = new Intent();
            data.putExtra(TabSnapshotHolder.EXTRA_ACTION, TabSnapshotHolder.ACTION_NEW);
            setResult(RESULT_OK, data);
            finish();
        });

        list.setOnItemClickListener((parent, view, position, id) -> {
            Intent data = new Intent();
            data.putExtra(TabSnapshotHolder.EXTRA_ACTION, TabSnapshotHolder.ACTION_SWITCH);
            data.putExtra(TabSnapshotHolder.EXTRA_INDEX, position);
            setResult(RESULT_OK, data);
            finish();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Intent data = new Intent();
            data.putExtra(TabSnapshotHolder.EXTRA_ACTION, TabSnapshotHolder.ACTION_CLOSE);
            data.putExtra(TabSnapshotHolder.EXTRA_INDEX, position);
            setResult(RESULT_OK, data);
            finish();
            return true;
        });
        refresh();
    }

    private void refresh() {
        display.clear();
        TabSnapshotHolder.Snapshot s = TabSnapshotHolder.get();
        if (s != null) {
            for (int i = 0; i < s.urls.size(); i++) {
                String title = i < s.titles.size() && !s.titles.get(i).isEmpty()
                        ? s.titles.get(i) : s.urls.get(i);
                String mark = (i == s.currentIndex) ? "▶ " : "  ";
                display.add(mark + title + "\n" + s.urls.get(i));
            }
        }
        adapter.notifyDataSetChanged();
    }
}
