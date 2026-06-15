package com.example.cleanrecovery;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final int REQUEST_STORAGE = 4100;

    private enum FilterMode {
        ALL,
        EXISTING,
        DELETED
    }

    private enum AppMode {
        RECOVERY,
        CLEANER
    }

    private enum JunkFilter {
        ALL,
        SAFE,
        REVIEW
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ArrayList<RecoveryItem> allItems = new ArrayList<>();
    private final ArrayList<RecoveryItem> items = new ArrayList<>();
    private final ArrayList<JunkItem> allJunkItems = new ArrayList<>();
    private final ArrayList<JunkItem> junkItems = new ArrayList<>();

    private RecoveryAdapter adapter;
    private JunkAdapter junkAdapter;
    private ListView listView;
    private TextView statusView;
    private TextView permissionStateView;
    private TextView resultCountView;
    private TextView selectedCountView;
    private LinearLayout categoryRow;
    private Button modeRecoveryButton;
    private Button modeCleanerButton;
    private Button scanCleanerButton;
    private Button filterAllButton;
    private Button filterExistingButton;
    private Button filterDeletedButton;
    private Button stopButton;
    private Button recoverButton;
    private AppMode appMode = AppMode.RECOVERY;
    private FilterMode currentFilter = FilterMode.ALL;
    private JunkFilter currentJunkFilter = JunkFilter.ALL;
    private boolean working;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        updateStatus(getString(R.string.ready_status));
        updatePermissionState();
        updateCounters();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (permissionStateView != null) {
            updatePermissionState();
        }
    }

    @Override
    protected void onDestroy() {
        cancelled.set(true);
        executor.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 250));
        root.setPadding(dp(14), dp(14), dp(14), dp(10));
        setContentView(root);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24f);
        title.setTextColor(Color.rgb(20, 33, 43));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.app_subtitle);
        subtitle.setTextSize(13f);
        subtitle.setTextColor(Color.rgb(82, 98, 109));
        subtitle.setPadding(0, dp(2), 0, dp(12));
        root.addView(subtitle, matchWrap());

        LinearLayout permissionPanel = panel();
        root.addView(permissionPanel, matchWrapWithBottom(10));

        TextView permissionTitle = sectionTitle(R.string.permission_title);
        permissionPanel.addView(permissionTitle);

        permissionStateView = new TextView(this);
        permissionStateView.setTextSize(14f);
        permissionStateView.setTypeface(Typeface.DEFAULT_BOLD);
        permissionPanel.addView(permissionStateView);

        TextView permissionDetail = new TextView(this);
        permissionDetail.setText(R.string.permission_detail);
        permissionDetail.setTextSize(12f);
        permissionDetail.setTextColor(Color.rgb(91, 105, 116));
        permissionDetail.setPadding(0, dp(4), 0, dp(8));
        permissionPanel.addView(permissionDetail);

        Button accessButton = primaryButton(R.string.button_grant_access);
        accessButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestStorageAccess();
            }
        });
        permissionPanel.addView(accessButton, matchWrap());

        LinearLayout scanPanel = panel();
        root.addView(scanPanel, matchWrapWithBottom(10));
        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setTextColor(Color.rgb(36, 50, 60));
        statusView.setPadding(0, 0, 0, dp(10));
        scanPanel.addView(statusView, matchWrap());

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, 0, 0, dp(8));
        scanPanel.addView(modeRow, matchWrap());

        modeRecoveryButton = secondaryButton(R.string.mode_recovery);
        modeRecoveryButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setMode(AppMode.RECOVERY);
            }
        });
        modeRow.addView(modeRecoveryButton, weightedButtonParams());

        modeCleanerButton = secondaryButton(R.string.mode_cleaner);
        modeCleanerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setMode(AppMode.CLEANER);
            }
        });
        modeRow.addView(modeCleanerButton, weightedButtonParams());

        categoryRow = new LinearLayout(this);
        categoryRow.setOrientation(LinearLayout.HORIZONTAL);
        scanPanel.addView(categoryRow, matchWrap());
        addTypeButton(categoryRow, RecoveryType.IMAGE);
        addTypeButton(categoryRow, RecoveryType.VIDEO);
        addTypeButton(categoryRow, RecoveryType.AUDIO);
        addTypeButton(categoryRow, RecoveryType.DOCUMENT);

        scanCleanerButton = primaryButton(R.string.button_scan_junk);
        scanCleanerButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startJunkScan();
            }
        });
        scanPanel.addView(scanCleanerButton, matchWrapWithTop(8));

        LinearLayout resultHeader = new LinearLayout(this);
        resultHeader.setOrientation(LinearLayout.HORIZONTAL);
        resultHeader.setGravity(Gravity.CENTER_VERTICAL);
        resultHeader.setPadding(dp(2), 0, dp(2), dp(6));
        root.addView(resultHeader, matchWrap());

        TextView resultsTitle = sectionTitle(R.string.results_title);
        resultHeader.addView(resultsTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        resultCountView = smallCounter();
        resultHeader.addView(resultCountView);
        selectedCountView = smallCounter();
        selectedCountView.setPadding(dp(10), 0, 0, 0);
        resultHeader.addView(selectedCountView);

        LinearLayout filterRow = new LinearLayout(this);
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(0, 0, 0, dp(8));
        root.addView(filterRow, matchWrap());

        filterAllButton = secondaryButton(R.string.filter_all);
        filterAllButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (appMode == AppMode.RECOVERY) {
                    setFilter(FilterMode.ALL);
                } else {
                    setJunkFilter(JunkFilter.ALL);
                }
            }
        });
        filterRow.addView(filterAllButton, weightedButtonParams());

        filterExistingButton = secondaryButton(R.string.filter_existing);
        filterExistingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (appMode == AppMode.RECOVERY) {
                    setFilter(FilterMode.EXISTING);
                } else {
                    setJunkFilter(JunkFilter.SAFE);
                }
            }
        });
        filterRow.addView(filterExistingButton, weightedButtonParams());

        filterDeletedButton = secondaryButton(R.string.filter_deleted);
        filterDeletedButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (appMode == AppMode.RECOVERY) {
                    setFilter(FilterMode.DELETED);
                } else {
                    setJunkFilter(JunkFilter.REVIEW);
                }
            }
        });
        filterRow.addView(filterDeletedButton, weightedButtonParams());

        listView = new ListView(this);
        listView.setDividerHeight(1);
        listView.setBackground(panelBackground(Color.WHITE));
        adapter = new RecoveryAdapter(this, items, new RecoveryAdapter.SelectionListener() {
            @Override
            public void onSelectionChanged() {
                updateCounters();
            }
        }, new RecoveryAdapter.ItemClickListener() {
            @Override
            public void onItemClicked(RecoveryItem item) {
                openPreview(item);
            }
        });
        junkAdapter = new JunkAdapter(this, junkItems, new JunkAdapter.SelectionListener() {
            @Override
            public void onSelectionChanged() {
                updateCounters();
            }
        }, new JunkAdapter.ItemClickListener() {
            @Override
            public void onItemClicked(JunkItem item) {
                openJunkItem(item);
            }
        });
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        TextView outputHint = new TextView(this);
        outputHint.setText(R.string.output_hint);
        outputHint.setTextSize(12f);
        outputHint.setTextColor(Color.rgb(91, 105, 116));
        outputHint.setPadding(dp(2), dp(8), dp(2), dp(6));
        root.addView(outputHint, matchWrap());

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(actionRow, matchWrap());

        Button selectButton = secondaryButton(R.string.button_select_all);
        selectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setAllSelected(true);
            }
        });
        actionRow.addView(selectButton, weightedButtonParams());

        Button clearButton = secondaryButton(R.string.button_clear);
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                setAllSelected(false);
            }
        });
        actionRow.addView(clearButton, weightedButtonParams());

        stopButton = secondaryButton(R.string.button_stop);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                cancelled.set(true);
                updateStatus(getString(R.string.stopping));
            }
        });
        actionRow.addView(stopButton, weightedButtonParams());

        recoverButton = primaryButton(R.string.button_recover);
        recoverButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (appMode == AppMode.RECOVERY) {
                    recoverSelected();
                } else {
                    deleteSelectedJunk();
                }
            }
        });
        root.addView(recoverButton, matchWrapWithTop(8));

        setWorking(false);
        setMode(AppMode.RECOVERY);
        updateFilterButtons();
    }

    private void addTypeButton(LinearLayout row, final RecoveryType type) {
        Button button = secondaryButton(type.labelResId);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startScan(type);
            }
        });
        row.addView(button, weightedButtonParams());
    }

    private void setFilter(FilterMode filterMode) {
        currentFilter = filterMode;
        rebuildVisibleItems();
        updateFilterButtons();
    }

    private void setJunkFilter(JunkFilter filterMode) {
        currentJunkFilter = filterMode;
        rebuildVisibleJunkItems();
        updateFilterButtons();
    }

    private void setMode(AppMode mode) {
        if (working) {
            return;
        }
        appMode = mode;
        if (listView != null) {
            listView.setAdapter(appMode == AppMode.RECOVERY ? adapter : junkAdapter);
        }
        if (categoryRow != null) {
            categoryRow.setVisibility(appMode == AppMode.RECOVERY ? View.VISIBLE : View.GONE);
        }
        if (scanCleanerButton != null) {
            scanCleanerButton.setVisibility(appMode == AppMode.CLEANER ? View.VISIBLE : View.GONE);
        }
        recoverButton.setText(appMode == AppMode.RECOVERY ? R.string.button_recover : R.string.button_delete_junk);
        updateModeButtons();
        updateFilterButtons();
        updateCounters();
        updateStatus(getString(appMode == AppMode.RECOVERY ? R.string.ready_status : R.string.junk_ready_status));
    }

    private void rebuildVisibleItems() {
        items.clear();
        for (RecoveryItem item : allItems) {
            if (matchesFilter(item)) {
                items.add(item);
            }
        }
        adapter.notifyDataSetChanged();
        updateCounters();
    }

    private boolean matchesFilter(RecoveryItem item) {
        if (currentFilter == FilterMode.EXISTING) {
            return !item.suspectedDeleted;
        }
        if (currentFilter == FilterMode.DELETED) {
            return item.suspectedDeleted;
        }
        return true;
    }

    private void rebuildVisibleJunkItems() {
        junkItems.clear();
        for (JunkItem item : allJunkItems) {
            if (matchesJunkFilter(item)) {
                junkItems.add(item);
            }
        }
        junkAdapter.notifyDataSetChanged();
        updateCounters();
    }

    private boolean matchesJunkFilter(JunkItem item) {
        if (currentJunkFilter == JunkFilter.SAFE) {
            return item.risk == JunkRisk.SAFE;
        }
        if (currentJunkFilter == JunkFilter.REVIEW) {
            return item.risk != JunkRisk.SAFE;
        }
        return true;
    }

    private void openPreview(RecoveryItem item) {
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_PATH, item.path);
        intent.putExtra(PreviewActivity.EXTRA_NAME, item.name);
        intent.putExtra(PreviewActivity.EXTRA_TYPE, item.type.name());
        intent.putExtra(PreviewActivity.EXTRA_SUSPECTED_DELETED, item.suspectedDeleted);
        startActivity(intent);
    }

    private void openJunkItem(JunkItem item) {
        if (item.directory) {
            Toast.makeText(this, item.reason, Toast.LENGTH_SHORT).show();
            return;
        }
        RecoveryType previewType = previewTypeFor(item.name);
        if (previewType == null) {
            Toast.makeText(this, item.reason, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_PATH, item.path);
        intent.putExtra(PreviewActivity.EXTRA_NAME, item.name);
        intent.putExtra(PreviewActivity.EXTRA_TYPE, previewType.name());
        intent.putExtra(PreviewActivity.EXTRA_SUSPECTED_DELETED, true);
        startActivity(intent);
    }

    private void startScan(final RecoveryType type) {
        if (working) {
            return;
        }
        if (!hasStorageAccess()) {
            requestStorageAccess();
            updateStatus(getString(R.string.storage_access_required));
            return;
        }

        cancelled.set(false);
        allItems.clear();
        items.clear();
        adapter.notifyDataSetChanged();
        updateCounters();
        setWorking(true);
        updateStatus(getString(R.string.scan_status, getString(type.labelResId)));

        executor.execute(new Runnable() {
            @Override
            public void run() {
                RecoveryScanner scanner = new RecoveryScanner();
                scanner.scan(type, new RecoveryScanner.Callback() {
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }

                    @Override
                    public void onProgress(final int scannedCount, final int foundCount, final String currentPath) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                updateStatus(getString(R.string.progress_status, scannedCount, foundCount, currentPath));
                            }
                        });
                    }

                    @Override
                    public void onItemFound(final RecoveryItem item) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                allItems.add(item);
                                if (matchesFilter(item)) {
                                    items.add(item);
                                    adapter.notifyDataSetChanged();
                                    updateCounters();
                                }
                            }
                        });
                    }

                    @Override
                    public void onDone(final int scannedCount, final int foundCount) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                setWorking(false);
                                updateStatus(getString(R.string.done_status, scannedCount, foundCount));
                                updateCounters();
                            }
                        });
                    }

                    @Override
                    public void onError(File file, Exception exception) {
                        // Keep scanning other readable directories.
                    }
                });
            }
        });
    }

    private void startJunkScan() {
        if (working) {
            return;
        }
        if (!hasStorageAccess()) {
            requestStorageAccess();
            updateStatus(getString(R.string.storage_access_required));
            return;
        }

        cancelled.set(false);
        allJunkItems.clear();
        junkItems.clear();
        junkAdapter.notifyDataSetChanged();
        updateCounters();
        setWorking(true);
        updateStatus(getString(R.string.junk_scan_status));

        executor.execute(new Runnable() {
            @Override
            public void run() {
                JunkScanner scanner = new JunkScanner(MainActivity.this);
                scanner.scan(new JunkScanner.Callback() {
                    @Override
                    public boolean isCancelled() {
                        return cancelled.get();
                    }

                    @Override
                    public void onProgress(final int scannedCount, final int foundCount, final String currentPath) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                updateStatus(getString(R.string.junk_progress_status, scannedCount, foundCount, currentPath));
                            }
                        });
                    }

                    @Override
                    public void onItemFound(final JunkItem item) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                allJunkItems.add(item);
                                if (matchesJunkFilter(item)) {
                                    junkItems.add(item);
                                    junkAdapter.notifyDataSetChanged();
                                    updateCounters();
                                }
                            }
                        });
                    }

                    @Override
                    public void onDone(final int scannedCount, final int foundCount, final long totalBytes) {
                        mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                setWorking(false);
                                updateStatus(getString(R.string.junk_done_status, scannedCount, foundCount, JunkItem.formatSize(totalBytes)));
                                updateCounters();
                            }
                        });
                    }

                    @Override
                    public void onError(File file, Exception exception) {
                        // Keep scanning readable directories.
                    }
                });
            }
        });
    }

    private void recoverSelected() {
        if (working) {
            return;
        }
        if (!hasStorageAccess()) {
            requestStorageAccess();
            updateStatus(getString(R.string.storage_access_required));
            return;
        }

        final List<RecoveryItem> selected = new ArrayList<>();
        for (RecoveryItem item : items) {
            if (item.selected) {
                selected.add(item);
            }
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.no_files_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        setWorking(true);
        cancelled.set(false);
        updateStatus(getResources().getQuantityString(R.plurals.recovering_status, selected.size(), selected.size()));

        executor.execute(new Runnable() {
            @Override
            public void run() {
                int success = 0;
                int failed = 0;
                File lastOutput = null;
                for (RecoveryItem item : selected) {
                    if (cancelled.get()) {
                        break;
                    }
                    try {
                        lastOutput = RecoveryCopier.copyToRecoveryDirectory(MainActivity.this, item);
                        success++;
                    } catch (Exception exception) {
                        failed++;
                    }
                    final int progressSuccess = success;
                    final int progressFailed = failed;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateStatus(getString(R.string.recover_progress_status, progressSuccess, progressFailed));
                        }
                    });
                }

                final int finalSuccess = success;
                final int finalFailed = failed;
                final File output = lastOutput;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        setWorking(false);
                        String outputPath = output == null ? "" : "\n" + getString(R.string.last_output, output.getParent());
                        updateStatus(getString(R.string.recover_done_status, finalSuccess, finalFailed, outputPath));
                    }
                });
            }
        });
    }

    private void deleteSelectedJunk() {
        if (working) {
            return;
        }
        if (!hasStorageAccess()) {
            requestStorageAccess();
            updateStatus(getString(R.string.storage_access_required));
            return;
        }

        final List<JunkItem> selected = new ArrayList<>();
        for (JunkItem item : junkItems) {
            if (item.selected) {
                selected.add(item);
            }
        }
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.no_files_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        setWorking(true);
        cancelled.set(false);
        updateStatus(getResources().getQuantityString(R.plurals.deleting_junk_status, selected.size(), selected.size()));

        executor.execute(new Runnable() {
            @Override
            public void run() {
                int success = 0;
                int failed = 0;
                long deletedBytes = 0L;
                for (JunkItem item : selected) {
                    if (cancelled.get()) {
                        break;
                    }
                    if (JunkCleaner.delete(item)) {
                        success++;
                        deletedBytes += item.size;
                    } else {
                        failed++;
                    }
                    final int progressSuccess = success;
                    final int progressFailed = failed;
                    final long progressBytes = deletedBytes;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            updateStatus(getString(R.string.junk_delete_progress_status, progressSuccess, progressFailed, JunkItem.formatSize(progressBytes)));
                        }
                    });
                }

                final int finalSuccess = success;
                final int finalFailed = failed;
                final long finalBytes = deletedBytes;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        setWorking(false);
                        removeDeletedJunk();
                        updateStatus(getString(R.string.junk_delete_done_status, finalSuccess, finalFailed, JunkItem.formatSize(finalBytes)));
                    }
                });
            }
        });
    }

    private void removeDeletedJunk() {
        for (int index = allJunkItems.size() - 1; index >= 0; index--) {
            if (allJunkItems.get(index).selected && !allJunkItems.get(index).asFile().exists()) {
                allJunkItems.remove(index);
            }
        }
        rebuildVisibleJunkItems();
    }

    private boolean hasStorageAccess() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? Environment.isExternalStorageManager()
                : checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception ignored) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            }
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, REQUEST_STORAGE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE && hasStorageAccess()) {
            updateStatus(getString(R.string.storage_access_granted));
        }
        updatePermissionState();
    }

    private void setAllSelected(boolean selected) {
        if (appMode == AppMode.RECOVERY) {
            for (RecoveryItem item : items) {
                item.selected = selected;
            }
            adapter.notifyDataSetChanged();
        } else {
            for (JunkItem item : junkItems) {
                item.selected = selected;
            }
            junkAdapter.notifyDataSetChanged();
        }
        updateCounters();
    }

    private void setWorking(boolean value) {
        working = value;
        stopButton.setEnabled(value);
        recoverButton.setEnabled(!value);
        if (scanCleanerButton != null) {
            scanCleanerButton.setEnabled(!value);
        }
    }

    private void updateFilterButtons() {
        if (appMode == AppMode.RECOVERY) {
            filterAllButton.setText(R.string.filter_all);
            filterExistingButton.setText(R.string.filter_existing);
            filterDeletedButton.setText(R.string.filter_deleted);
            styleFilterButton(filterAllButton, currentFilter == FilterMode.ALL);
            styleFilterButton(filterExistingButton, currentFilter == FilterMode.EXISTING);
            styleFilterButton(filterDeletedButton, currentFilter == FilterMode.DELETED);
        } else {
            filterAllButton.setText(R.string.filter_all);
            filterExistingButton.setText(R.string.junk_filter_safe);
            filterDeletedButton.setText(R.string.junk_filter_review);
            styleFilterButton(filterAllButton, currentJunkFilter == JunkFilter.ALL);
            styleFilterButton(filterExistingButton, currentJunkFilter == JunkFilter.SAFE);
            styleFilterButton(filterDeletedButton, currentJunkFilter == JunkFilter.REVIEW);
        }
    }

    private void updateModeButtons() {
        styleFilterButton(modeRecoveryButton, appMode == AppMode.RECOVERY);
        styleFilterButton(modeCleanerButton, appMode == AppMode.CLEANER);
    }

    private void styleFilterButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setTextColor(active ? Color.WHITE : Color.rgb(0, 105, 92));
        button.setBackground(active
                ? buttonBackground(Color.rgb(0, 137, 123), Color.rgb(0, 137, 123))
                : buttonBackground(Color.WHITE, Color.rgb(192, 210, 207)));
    }

    private void updatePermissionState() {
        boolean granted = hasStorageAccess();
        permissionStateView.setText(granted ? R.string.storage_access_granted : R.string.storage_access_missing);
        permissionStateView.setTextColor(granted ? Color.rgb(0, 118, 105) : Color.rgb(174, 89, 0));
    }

    private void updateCounters() {
        int selected = 0;
        if (appMode == AppMode.RECOVERY) {
            for (RecoveryItem item : items) {
                if (item.selected) {
                    selected++;
                }
            }
            if (resultCountView != null) {
                resultCountView.setText(getResources().getQuantityString(R.plurals.results_count, items.size(), items.size()));
            }
        } else {
            for (JunkItem item : junkItems) {
                if (item.selected) {
                    selected++;
                }
            }
            if (resultCountView != null) {
                long visibleBytes = 0L;
                for (JunkItem item : junkItems) {
                    visibleBytes += item.size;
                }
                resultCountView.setText(getResources().getQuantityString(
                        R.plurals.junk_results_count,
                        junkItems.size(),
                        junkItems.size(),
                        JunkItem.formatSize(visibleBytes)
                ));
            }
        }
        if (selectedCountView != null) {
            selectedCountView.setText(getResources().getQuantityString(R.plurals.selected_count, selected, selected));
        }
    }

    private RecoveryType previewTypeFor(String name) {
        String lower = name.toLowerCase(Locale.US);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".bmp")) {
            return RecoveryType.IMAGE;
        }
        if (lower.endsWith(".mp4") || lower.endsWith(".3gp") || lower.endsWith(".mkv") || lower.endsWith(".ts")) {
            return RecoveryType.VIDEO;
        }
        if (lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav") || lower.endsWith(".ogg")) {
            return RecoveryType.AUDIO;
        }
        if (lower.endsWith(".pdf") || lower.endsWith(".txt") || lower.endsWith(".doc")
                || lower.endsWith(".docx") || lower.endsWith(".ppt") || lower.endsWith(".pptx")
                || lower.endsWith(".xls") || lower.endsWith(".xlsx")) {
            return RecoveryType.DOCUMENT;
        }
        return null;
    }

    private void updateStatus(String message) {
        statusView.setText(message);
    }

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(panelBackground(Color.WHITE));
        return panel;
    }

    private TextView sectionTitle(int stringRes) {
        TextView textView = new TextView(this);
        textView.setText(stringRes);
        textView.setTextSize(14f);
        textView.setTextColor(Color.rgb(20, 33, 43));
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        return textView;
    }

    private TextView smallCounter() {
        TextView textView = new TextView(this);
        textView.setTextSize(12f);
        textView.setTextColor(Color.rgb(82, 98, 109));
        return textView;
    }

    private Button primaryButton(int stringRes) {
        Button button = baseButton(stringRes);
        button.setTextColor(Color.WHITE);
        button.setBackground(buttonBackground(Color.rgb(0, 137, 123), Color.rgb(0, 137, 123)));
        return button;
    }

    private Button secondaryButton(int stringRes) {
        Button button = baseButton(stringRes);
        button.setTextColor(Color.rgb(0, 105, 92));
        button.setBackground(buttonBackground(Color.WHITE, Color.rgb(192, 210, 207)));
        return button;
    }

    private Button baseButton(int stringRes) {
        Button button = new Button(this);
        button.setText(stringRes);
        button.setAllCaps(false);
        button.setMinHeight(dp(42));
        button.setTextSize(13f);
        return button;
    }

    private GradientDrawable panelBackground(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(1, Color.rgb(229, 234, 238));
        return drawable;
    }

    private GradientDrawable buttonBackground(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(8));
        drawable.setStroke(1, stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapWithBottom(int bottomDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(bottomDp));
        return params;
    }

    private LinearLayout.LayoutParams matchWrapWithTop(int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(topDp), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
