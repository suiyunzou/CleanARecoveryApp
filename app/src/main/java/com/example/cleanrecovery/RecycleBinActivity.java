package com.example.cleanrecovery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 回收站浏览/恢复/彻底删除界面。
 *
 * - 进入时自动清理已过期条目（>30 天）。
 * - 列表项展示原文件名、原路径、大小、删除时间、过期时间。
 * - 单项支持「恢复」「彻底删除」。
 * - 顶部支持「清空回收站」。
 */
public final class RecycleBinActivity extends Activity {
    private RecycleBin recycleBin;
    private RecycleBinAdapter adapter;
    private TextView summaryText;
    private View emptyPanel;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_recycle_bin);

        recycleBin = new RecycleBin();

        findViewById(R.id.recycle_bin_back).setOnClickListener(v -> finish());
        summaryText = findViewById(R.id.recycle_bin_summary);
        emptyPanel = findViewById(R.id.recycle_bin_empty_panel);
        RecyclerView list = findViewById(R.id.recycle_bin_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        adapter = new RecycleBinAdapter();
        list.setAdapter(adapter);

        findViewById(R.id.recycle_bin_clear).setOnClickListener(v -> confirmClearAll());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        recycleBin.shutdown();
    }

    private void reload() {
        recycleBin.list(new RecycleBin.ListCallback() {
            @Override
            public void onResult(final List<RecycleBin.RecycleEntry> entries) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        adapter.submit(entries);
                        updateSummary(entries);
                    }
                });
            }
        });
    }

    private void updateSummary(List<RecycleBin.RecycleEntry> entries) {
        if (entries.isEmpty()) {
            summaryText.setText("");
            emptyPanel.setVisibility(View.VISIBLE);
            findViewById(R.id.recycle_bin_list).setVisibility(View.GONE);
            return;
        }
        emptyPanel.setVisibility(View.GONE);
        findViewById(R.id.recycle_bin_list).setVisibility(View.VISIBLE);
        long totalBytes = 0L;
        for (RecycleBin.RecycleEntry e : entries) totalBytes += e.size;
        summaryText.setText(String.format(Locale.US,
                getString(R.string.file_browser_recycle_bin_count) + " · %s",
                entries.size(), formatSize(totalBytes)));
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_browser_recycle_bin_clear)
                .setMessage(R.string.file_browser_recycle_bin_clear_confirm)
                .setPositiveButton(R.string.file_browser_recycle_bin_clear, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        recycleBin.emptyAll(new RecycleBin.OpCallback() {
                            @Override
                            public void onComplete(final boolean success, String message) {
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (success) {
                                            reload();
                                        } else {
                                            Toast.makeText(RecycleBinActivity.this,
                                                    R.string.file_browser_delete_failed,
                                                    Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void restoreEntry(final RecycleBin.RecycleEntry entry) {
        recycleBin.restore(entry.id, new RecycleBin.OpCallback() {
            @Override
            public void onComplete(final boolean success, final String message) {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            Toast.makeText(RecycleBinActivity.this,
                                    String.format(Locale.US,
                                            getString(R.string.file_browser_recycle_bin_restore_ok),
                                            message),
                                    Toast.LENGTH_LONG).show();
                            reload();
                        } else {
                            Toast.makeText(RecycleBinActivity.this,
                                    R.string.file_browser_recycle_bin_restore_failed,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        });
    }

    private void permanentDeleteEntry(final RecycleBin.RecycleEntry entry) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_browser_recycle_bin_delete_permanent)
                .setMessage(getString(R.string.file_browser_delete_confirm, entry.originalName))
                .setPositiveButton(R.string.file_browser_recycle_bin_delete_permanent, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        recycleBin.permanentDelete(entry.id, new RecycleBin.OpCallback() {
                            @Override
                            public void onComplete(final boolean success, String message) {
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (success) reload();
                                        else Toast.makeText(RecycleBinActivity.this,
                                                R.string.file_browser_delete_failed,
                                                Toast.LENGTH_SHORT).show();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int index = -1;
        do {
            value /= 1024.0d;
            index++;
        } while (value >= 1024.0d && index < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[index]);
    }

    private final class RecycleBinAdapter extends RecyclerView.Adapter<RecycleBinAdapter.VH> {
        private final List<RecycleBin.RecycleEntry> items = new ArrayList<>();

        void submit(List<RecycleBin.RecycleEntry> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recycle_bin, parent, false);
            return new VH(view);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            RecycleBin.RecycleEntry entry = items.get(position);
            holder.name.setText(entry.originalName);
            holder.path.setText(entry.originalPath);
            String meta = formatSize(entry.size)
                    + " | " + DateFormat.getDateTimeInstance().format(new Date(entry.deletedAt))
                    + " | " + getString(R.string.file_browser_recycle_bin_restore) + ": "
                    + DateFormat.getDateTimeInstance().format(new Date(entry.expiresAt));
            holder.meta.setText(meta);
            holder.restore.setOnClickListener(v -> restoreEntry(entry));
            holder.delete.setOnClickListener(v -> permanentDeleteEntry(entry));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView path;
            final TextView meta;
            final TextView restore;
            final TextView delete;

            VH(View itemView) {
                super(itemView);
                name = itemView.findViewById(R.id.recycle_item_name);
                path = itemView.findViewById(R.id.recycle_item_path);
                meta = itemView.findViewById(R.id.recycle_item_meta);
                restore = itemView.findViewById(R.id.recycle_item_restore);
                delete = itemView.findViewById(R.id.recycle_item_delete);
            }
        }
    }
}
