package com.example.cleanrecovery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.algorithm.AlgorithmEvent;
import com.example.cleanrecovery.algorithm.AlgorithmRegistry;
import com.example.cleanrecovery.algorithm.RecoveryAlgorithm;
import com.example.cleanrecovery.algorithm.ScanMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int REQUEST_ONBOARDING = 5100;
    private static final long PROGRESS_TICK_MS = 200L;

    private final RecoveryState recoveryState = new RecoveryState();
    private final ScanProgressTracker scanProgressTracker = new ScanProgressTracker();
    private final Handler progressHandler = new Handler(Looper.getMainLooper());

    private StorageAccessController storageAccessController;
    private RecoveryCoordinator recoveryCoordinator;
    private RecoveryGridAdapter gridAdapter;
    private RecoveryType currentScanType;
    private boolean scanAllMode;
    private boolean experimentalMode;
    private boolean working;
    private boolean pathExpanded;
    private int lastScannedCount;
    private int lastFoundCount;
    private int multiTypeCompletedScanned;

    private View homePanel;
    private View scanPanel;
    private View resultsPanel;
    private LinearLayout permissionBanner;
    private TextView permissionBannerTitle;
    private TextView permissionBannerAction;
    private TextView lastScanSummary;
    private ParticleScanView scanParticleView;
    private TextView scanPathToggle;
    private TextView scanCurrentPath;
    private TextView resultsCount;
    private TextView selectedCount;
    private View resultsEmpty;
    private TextView resultsEmptyMessage;
    private RecyclerView resultsGrid;
    private TextView filterAllButton;
    private TextView filterExistingButton;
    private TextView filterDeletedButton;
    private ImageButton filterTypeButton;
    private View filterTypeRow;
    private TextView filterTypeAll;
    private TextView filterTypeImages;
    private TextView filterTypeVideos;
    private TextView filterTypeAudio;
    private TextView filterTypeDocuments;
    private RecoveryType currentTypeFilter;
    private View bottomNav;
    private ImageView navHomeIcon;
    private ImageView navResultsIcon;
    private ImageView navFolderIcon;
    private ImageView navAboutIcon;
    private TextView navHomeLabel;
    private TextView navResultsLabel;
    private TextView navFolderLabel;
    private TextView navAboutLabel;
    private View algorithmLogContainer;
    private RecyclerView algorithmLogList;
    private AlgorithmStepAdapter algorithmStepAdapter;

    private String scanCurrentPathValue = "";
    private String runningAlgorithmId;

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            if (!working || scanPanel.getVisibility() != View.VISIBLE) {
                return;
            }
            updateScanProgressUi();
            progressHandler.postDelayed(this, PROGRESS_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_main);
        storageAccessController = new StorageAccessController(this);
        recoveryCoordinator = new RecoveryCoordinator(this, createCoordinatorCallback());
        bindViews();
        bindCategoryCards();
        bindActions();
        restoreResultsSessionIfNeeded();
        refreshHome();
        maybeLaunchOnboarding();
        updateBottomNav(Panel.HOME);
        // 初始化应用工作目录并异步清理过期回收站条目（>30 天）
        initializeAppPaths();
    }

    /**
     * 确保应用工作目录就绪，并在后台线程清理过期回收站条目与缓存。
     * 不阻塞 UI 线程；失败仅记录日志，不影响主流程。
     */
    private void initializeAppPaths() {
        // 触发 PathManager 创建根目录（lazy mkdirs）
        PathManager.appRoot();
        PathManager.recoveredRoot();
        PathManager.trashRoot();
        PathManager.cacheRoot();
        PathManager.logsDir();

        // 后台异步清理
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RecycleBin bin = new RecycleBin();
                    int cleaned = bin.cleanupExpiredSync();
                    bin.shutdown();
                    if (cleaned > 0) {
                        android.util.Log.i("MainActivity",
                                "RecycleBin: cleaned " + cleaned + " expired entries on startup");
                    }
                } catch (Exception e) {
                    android.util.Log.w("MainActivity",
                            "RecycleBin cleanup failed: " + e.getMessage());
                }
            }
        }, "recyclebin-init").start();
    }

    @Override
    public void onBackPressed() {
        if (resultsPanel.getVisibility() == View.VISIBLE) {
            showPanel(Panel.HOME);
            return;
        }
        if (scanPanel.getVisibility() == View.VISIBLE) {
            recoveryCoordinator.cancelCurrentWork();
            scanProgressTracker.complete();
            finishScanUi(lastScannedCount, lastFoundCount);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPermissionBanner();
        refreshHome();
    }

    @Override
    protected void onDestroy() {
        progressHandler.removeCallbacks(progressTick);
        recoveryCoordinator.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_ONBOARDING) {
            refreshPermissionBanner();
            refreshHome();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPermissionBanner();
    }

    private void bindViews() {
        homePanel = findViewById(R.id.home_panel);
        scanPanel = findViewById(R.id.scan_panel);
        resultsPanel = findViewById(R.id.results_panel);
        permissionBanner = findViewById(R.id.permission_banner);
        permissionBannerTitle = findViewById(R.id.permission_banner_title);
        permissionBannerAction = findViewById(R.id.permission_banner_action);
        lastScanSummary = findViewById(R.id.last_scan_summary);
        scanParticleView = findViewById(R.id.scan_particle_view);
        resultsCount = findViewById(R.id.results_count);
        selectedCount = findViewById(R.id.selected_count);
        resultsEmpty = findViewById(R.id.results_empty);
        resultsEmptyMessage = findViewById(R.id.results_empty_message);
        resultsGrid = findViewById(R.id.results_grid);
        filterAllButton = findViewById(R.id.filter_all_button);
        filterExistingButton = findViewById(R.id.filter_existing_button);
        filterDeletedButton = findViewById(R.id.filter_deleted_button);
        filterTypeButton = findViewById(R.id.filter_type_button);
        filterTypeRow = findViewById(R.id.filter_type_row);
        filterTypeAll = findViewById(R.id.filter_type_all);
        filterTypeImages = findViewById(R.id.filter_type_images);
        filterTypeVideos = findViewById(R.id.filter_type_videos);
        filterTypeAudio = findViewById(R.id.filter_type_audio);
        filterTypeDocuments = findViewById(R.id.filter_type_documents);
        bottomNav = findViewById(R.id.bottom_nav);
        navHomeIcon = findViewById(R.id.nav_home_icon);
        navResultsIcon = findViewById(R.id.nav_results_icon);
        navFolderIcon = findViewById(R.id.nav_folder_icon);
        navAboutIcon = findViewById(R.id.nav_about_icon);
        navHomeLabel = findViewById(R.id.nav_home_label);
        navResultsLabel = findViewById(R.id.nav_results_label);
        navFolderLabel = findViewById(R.id.nav_folder_label);
        navAboutLabel = findViewById(R.id.nav_about_label);
        algorithmStepAdapter = new AlgorithmStepAdapter(this);

        gridAdapter = new RecoveryGridAdapter(this, recoveryState.getVisibleItems(), new RecoveryGridAdapter.Listener() {
            @Override
            public void onSelectionChanged() {
                updateCounters();
            }

            @Override
            public void onItemClicked(RecoveryItem item, int position) {
                openPreview(item, position);
            }
        });
        resultsGrid.setLayoutManager(new GridLayoutManager(this, 3));
        resultsGrid.setAdapter(gridAdapter);
    }

    private void bindCategoryCards() {
        setupCategoryCard(findViewById(R.id.card_images), RecoveryType.IMAGE, R.drawable.ic_type_image, R.color.accent_image);
        setupCategoryCard(findViewById(R.id.card_videos), RecoveryType.VIDEO, R.drawable.ic_type_video, R.color.accent_video);
        setupCategoryCard(findViewById(R.id.card_audio), RecoveryType.AUDIO, R.drawable.ic_type_audio, R.color.accent_audio);
        setupCategoryCard(findViewById(R.id.card_documents), RecoveryType.DOCUMENT, R.drawable.ic_type_document, R.color.accent_document);
    }

    private void setupCategoryCard(View card, final RecoveryType type, int iconResId, int accentColorRes) {
        ImageView icon = card.findViewById(R.id.category_icon);
        TextView title = card.findViewById(R.id.category_title);
        title.setText(type.labelResId);
        icon.setImageResource(iconResId);
        icon.setBackgroundTintList(android.content.res.ColorStateList.valueOf(resolveColorRes(accentColorRes)));
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                performLightHaptic(view);
                startScan(type);
            }
        });
    }

    private void bindActions() {
        findViewById(R.id.scan_all_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                performLightHaptic(view);
                startScanAll();
            }
        });
        findViewById(R.id.experimental_scan_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                performLightHaptic(view);
                showExperimentalTypePicker();
            }
        });
        permissionBanner.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (!storageAccessController.hasStorageAccess()) {
                    storageAccessController.requestStorageAccess();
                }
            }
        });
        findViewById(R.id.stop_scan_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                recoveryCoordinator.cancelCurrentWork();
                scanProgressTracker.complete();
                finishScanUi(lastScannedCount, lastFoundCount);
            }
        });
        findViewById(R.id.results_back_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showPanel(Panel.HOME);
            }
        });
        findViewById(R.id.nav_home).setOnClickListener(v -> showPanel(Panel.HOME));
        findViewById(R.id.nav_results).setOnClickListener(v -> openResultsTab());
        findViewById(R.id.nav_folder).setOnClickListener(v -> openRecoveryFolder());
        findViewById(R.id.nav_about).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, AboutActivity.class)));
        filterAllButton.setOnClickListener(v -> setFilter(RecoveryState.FilterMode.ALL));
        filterExistingButton.setOnClickListener(v -> setFilter(RecoveryState.FilterMode.EXISTING));
        filterDeletedButton.setOnClickListener(v -> setFilter(RecoveryState.FilterMode.DELETED));
        filterTypeButton.setOnClickListener(v -> toggleFilterTypeRow());
        filterTypeAll.setOnClickListener(v -> setTypeFilter(null));
        filterTypeImages.setOnClickListener(v -> setTypeFilter(RecoveryType.IMAGE));
        filterTypeVideos.setOnClickListener(v -> setTypeFilter(RecoveryType.VIDEO));
        filterTypeAudio.setOnClickListener(v -> setTypeFilter(RecoveryType.AUDIO));
        filterTypeDocuments.setOnClickListener(v -> setTypeFilter(RecoveryType.DOCUMENT));
        findViewById(R.id.select_all_button).setOnClickListener(v -> {
            recoveryState.setAllSelected(true);
            gridAdapter.notifyDataSetChanged();
            updateCounters();
        });
        findViewById(R.id.clear_selection_button).setOnClickListener(v -> {
            recoveryState.setAllSelected(false);
            gridAdapter.notifyDataSetChanged();
            updateCounters();
        });
        findViewById(R.id.recover_button).setOnClickListener(v -> recoverSelected());
        findViewById(R.id.rescan_button).setOnClickListener(v -> {
            if (scanAllMode) {
                startScanAll();
            } else if (experimentalMode && currentScanType != null) {
                startExperimentalScan(currentScanType);
            } else if (currentScanType != null) {
                startScan(currentScanType);
            } else {
                showPanel(Panel.HOME);
            }
        });
    }

    private void maybeLaunchOnboarding() {
        if (!ScanHistoryStore.isOnboardingComplete(this)) {
            startActivityForResult(new Intent(this, OnboardingActivity.class), REQUEST_ONBOARDING);
        }
    }

    private void refreshHome() {
        ScanHistoryStore.Snapshot snapshot = ScanHistoryStore.read(this);
        if (snapshot.hasScanHistory()) {
            CharSequence when = DateUtils.getRelativeTimeSpanString(
                    snapshot.lastScanTimeMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
            );
            lastScanSummary.setText(getString(
                    R.string.last_scan_compact,
                    when,
                    snapshot.lastFoundCount
            ));
        } else {
            lastScanSummary.setText(R.string.last_scan_empty);
        }
        updateCategoryCounts(snapshot);
        refreshPermissionBanner();
    }

    private void updateCategoryCounts(ScanHistoryStore.Snapshot snapshot) {
        updateCategoryCount(findViewById(R.id.card_images), snapshot.countForType(RecoveryType.IMAGE));
        updateCategoryCount(findViewById(R.id.card_videos), snapshot.countForType(RecoveryType.VIDEO));
        updateCategoryCount(findViewById(R.id.card_audio), snapshot.countForType(RecoveryType.AUDIO));
        updateCategoryCount(findViewById(R.id.card_documents), snapshot.countForType(RecoveryType.DOCUMENT));
    }

    private void updateCategoryCount(View card, int count) {
        TextView countView = card.findViewById(R.id.category_count);
        if (count > 0) {
            countView.setText(getString(R.string.category_count_found, count));
        } else {
            countView.setText(R.string.category_count_none);
        }
    }

    private void refreshPermissionBanner() {
        boolean granted = storageAccessController.hasStorageAccess();
        permissionBannerTitle.setText(granted ? R.string.storage_access_granted : R.string.storage_access_missing);
        permissionBannerTitle.setTextColor(resolveColorRes(granted ? R.color.status_success : R.color.status_warning));
        permissionBannerAction.setVisibility(granted ? View.GONE : View.VISIBLE);
        if (!granted) {
            permissionBannerAction.setText(R.string.button_grant_access);
        }
    }

    private void startScan(RecoveryType type) {
        beginScan(false, false, type);
    }

    private void startScanAll() {
        beginScan(true, false, null);
    }

    private void startExperimentalScan(RecoveryType type) {
        beginScan(false, true, type);
    }

    private void showExperimentalTypePicker() {
        final RecoveryType[] types = RecoveryType.scannableValues();
        CharSequence[] labels = new CharSequence[types.length];
        for (int i = 0; i < types.length; i++) {
            labels[i] = getString(types[i].labelResId);
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.experimental_scan_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        showExperimentalConfirmDialog(types[which]);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showExperimentalConfirmDialog(final RecoveryType type) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.experimental_scan_title)
                .setMessage(R.string.experimental_scan_warning)
                .setPositiveButton(R.string.experimental_scan_confirm, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startExperimentalScan(type);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void beginScan(boolean allTypes, boolean experimental, RecoveryType type) {
        if (working) {
            return;
        }
        if (!storageAccessController.hasStorageAccess()) {
            Toast.makeText(this, R.string.storage_access_required, Toast.LENGTH_SHORT).show();
            storageAccessController.requestStorageAccess();
            return;
        }
        scanAllMode = allTypes;
        experimentalMode = experimental;
        currentScanType = type;
        RecoveryResultsSession.clear();
        recoveryState.clear();
        gridAdapter.notifyDataSetChanged();
        ScanHistoryStore.Snapshot history = ScanHistoryStore.read(this);
        int historicalEstimate = history.lastScannedCount > 0 ? history.lastScannedCount : 10_000;
        if (allTypes) {
            scanProgressTracker.resetMultiType(historicalEstimate, RecoveryType.scannableCount());
        } else {
            scanProgressTracker.reset(historicalEstimate);
        }
        lastScannedCount = 0;
        lastFoundCount = 0;
        multiTypeCompletedScanned = 0;
        scanCurrentPathValue = "";
        pathExpanded = false;
        if (experimental && type != null) {
            prepareExperimentalAlgorithmLog(type);
        } else {
            algorithmStepAdapter.setRows(new ArrayList<AlgorithmStepAdapter.Row>());
            runningAlgorithmId = null;
        }
        if (allTypes) {
        } else if (experimental) {
        } else {
        }
        showPanel(Panel.SCAN);
        progressHandler.removeCallbacks(progressTick);
        progressHandler.post(progressTick);
        if (allTypes) {
            recoveryCoordinator.startScanAll();
        } else if (experimental) {
            recoveryCoordinator.startExperimentalScan(type);
        } else {
            recoveryCoordinator.startScan(type);
        }
    }

    private void recoverSelected() {
        if (working) {
            return;
        }
        List<RecoveryItem> selected = recoveryState.getSelectedItems();
        if (selected.isEmpty()) {
            Toast.makeText(this, R.string.no_files_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        recoveryCoordinator.recoverSelected(selected);
    }

    private void openPreview(RecoveryItem item, int position) {
        PreviewSession.setItems(recoveryState.getVisibleItems(), position);
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_PATH, item.path);
        intent.putExtra(PreviewActivity.EXTRA_NAME, item.name);
        intent.putExtra(PreviewActivity.EXTRA_TYPE, item.type.name());
        intent.putExtra(PreviewActivity.EXTRA_SUSPECTED_DELETED, item.suspectedDeleted);
        startActivity(intent);
    }

    private void openRecoveryFolder() {
        FileBrowserActivity.open(this, RecoveryOutputPaths.primaryDataRecoveryDir());
    }

    private void openResultsTab() {
        restoreResultsSessionIfNeeded();
        showPanel(Panel.RESULTS);
        styleFilterTabs();
        gridAdapter.notifyDataSetChanged();
        updateCounters();
    }

    private void restoreResultsSessionIfNeeded() {
        if (recoveryState.getAllCount() > 0 || !RecoveryResultsSession.hasResults()) {
            return;
        }
        RecoveryResultsSession.restoreTo(recoveryState);
        scanAllMode = RecoveryResultsSession.isScanAllMode();
        experimentalMode = RecoveryResultsSession.isExperimentalMode();
        currentScanType = RecoveryResultsSession.getScanType();
        lastScannedCount = RecoveryResultsSession.getScannedCount();
        lastFoundCount = RecoveryResultsSession.getFoundCount();
    }

    private void setFilter(RecoveryState.FilterMode mode) {
        recoveryState.setFilter(mode);
        gridAdapter.notifyDataSetChanged();
        styleFilterTabs();
        updateCounters();
        updateEmptyState();
    }

    private void toggleFilterTypeRow() {
        boolean show = filterTypeRow.getVisibility() != View.VISIBLE;
        filterTypeRow.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setTypeFilter(RecoveryType type) {
        currentTypeFilter = type;
        recoveryState.setTypeFilter(type);
        gridAdapter.notifyDataSetChanged();
        styleTypeFilterChips();
        updateCounters();
        updateEmptyState();
    }

    private void styleTypeFilterChips() {
        styleTypeChip(filterTypeAll, currentTypeFilter == null);
        styleTypeChip(filterTypeImages, currentTypeFilter == RecoveryType.IMAGE);
        styleTypeChip(filterTypeVideos, currentTypeFilter == RecoveryType.VIDEO);
        styleTypeChip(filterTypeAudio, currentTypeFilter == RecoveryType.AUDIO);
        styleTypeChip(filterTypeDocuments, currentTypeFilter == RecoveryType.DOCUMENT);
    }

    private void styleTypeChip(TextView chip, boolean active) {
        chip.setBackgroundResource(active ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
        chip.setTextColor(resolveColorRes(active ? R.color.text_on_primary : R.color.text_secondary));
    }

    private void styleFilterTabs() {
        styleFilterButton(filterAllButton, recoveryState.getFilter() == RecoveryState.FilterMode.ALL);
        styleFilterButton(filterExistingButton, recoveryState.getFilter() == RecoveryState.FilterMode.EXISTING);
        styleFilterButton(filterDeletedButton, recoveryState.getFilter() == RecoveryState.FilterMode.DELETED);
    }

    private void styleFilterButton(TextView button, boolean active) {
        button.setBackgroundResource(active ? R.drawable.bg_tab_selected : R.drawable.bg_tab_unselected);
        button.setTextColor(resolveColorRes(active ? R.color.text_on_primary : R.color.text_secondary));
    }

    private void updateCounters() {
        int visible = recoveryState.getVisibleCount();
        resultsCount.setText(getResources().getQuantityString(R.plurals.results_count, visible, visible));
        int selected = recoveryState.getSelectedCount();
        selectedCount.setText(getResources().getQuantityString(R.plurals.selected_count, selected, selected));
        updateEmptyState();
    }

    private void updateEmptyState() {
        boolean empty = recoveryState.getVisibleCount() == 0;
        resultsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        resultsGrid.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            boolean neverScanned = !RecoveryResultsSession.hasResults()
                    && !ScanHistoryStore.read(this).hasScanHistory();
            resultsEmptyMessage.setText(neverScanned
                    ? R.string.results_empty_never_scanned
                    : R.string.empty_results_title);
            findViewById(R.id.rescan_button).setVisibility(neverScanned ? View.GONE : View.VISIBLE);
        }
    }

    private void persistScanHistory() {
        ScanHistoryStore.saveScanResult(
                this,
                scanAllMode ? null : currentScanType,
                lastScannedCount,
                lastFoundCount,
                recoveryState.countByType(RecoveryType.IMAGE),
                recoveryState.countByType(RecoveryType.VIDEO),
                recoveryState.countByType(RecoveryType.AUDIO),
                recoveryState.countByType(RecoveryType.DOCUMENT)
        );
        refreshHome();
    }

    private void updateScanTitleForType(RecoveryType type, int typeIndex, int typeCount) {
    }

    private void prepareExperimentalAlgorithmLog(RecoveryType type) {
        ArrayList<AlgorithmStepAdapter.Row> rows = new ArrayList<>();
        List<RecoveryAlgorithm> algorithms = AlgorithmRegistry.runnableForMode(ScanMode.EXPERIMENTAL_ALL, type);
        for (RecoveryAlgorithm algorithm : algorithms) {
            rows.add(new AlgorithmStepAdapter.Row(algorithm.id()));
        }
        algorithmStepAdapter.setRows(rows);
        runningAlgorithmId = null;
    }

    private void handleAlgorithmEvent(AlgorithmEvent event) {
        if (!experimentalMode) {
            return;
        }
        AlgorithmStepAdapter.Row row = algorithmStepAdapter.findRow(event.algorithmId);
        if (row == null) {
            row = new AlgorithmStepAdapter.Row(event.algorithmId);
        }
        switch (event.kind) {
            case ALGORITHM_START:
                row.status = AlgorithmStepAdapter.Status.RUNNING;
                row.processed = 0;
                row.found = 0;
                row.durationMs = 0L;
                runningAlgorithmId = event.algorithmId;
                break;
            case ALGORITHM_END:
                row.status = AlgorithmStepAdapter.Status.COMPLETED;
                row.processed = event.processed;
                row.found = event.found;
                row.durationMs = event.durationMs;
                runningAlgorithmId = null;
                break;
            case ALGORITHM_SKIPPED:
                row.status = AlgorithmStepAdapter.Status.SKIPPED;
                row.reason = event.reason;
                break;
            case ALGORITHM_ERROR:
                row.status = AlgorithmStepAdapter.Status.ERROR;
                row.reason = event.reason;
                runningAlgorithmId = null;
                break;
            default:
                break;
        }
        algorithmStepAdapter.upsertRow(row);
        if (algorithmLogList != null
                && algorithmLogList.getAdapter() != null
                && algorithmLogList.getAdapter().getItemCount() > 0) {
            algorithmLogList.smoothScrollToPosition(algorithmStepAdapter.getItemCount() - 1);
        }
    }

    private void handleAlgorithmProgress(String algorithmId, int processed, int found) {
        if (!experimentalMode || runningAlgorithmId == null) {
            return;
        }
        AlgorithmStepAdapter.Row row = algorithmStepAdapter.findRow(runningAlgorithmId);
        if (row == null) {
            return;
        }
        row.processed = processed;
        row.found = found;
        row.status = AlgorithmStepAdapter.Status.RUNNING;
        algorithmStepAdapter.upsertRow(row);
    }

    private void finishScanUi(int scannedCount, int foundCount) {
        if (!scanAllMode) {
            scanProgressTracker.onScanProgress(scannedCount);
        }
        scanProgressTracker.complete();
        lastScannedCount = scannedCount;
        lastFoundCount = foundCount;
        RecoveryResultsSession.saveFrom(
                recoveryState,
                currentScanType,
                scanAllMode,
                experimentalMode,
                scannedCount,
                foundCount
        );
        updateScanProgressUi();
        progressHandler.removeCallbacks(progressTick);
        persistScanHistory();
        gridAdapter.notifyDataSetChanged();
        styleFilterTabs();
        updateCounters();
        showPanel(Panel.RESULTS);
    }

    private void updateScanProgressUi() {
        int percent = scanProgressTracker.getDisplayPercent();
        scanParticleView.setPercent(percent);
        scanParticleView.setFoundCount(lastFoundCount);

        ScanProgressTracker.Phase phase = scanProgressTracker.getPhase();
        if (phase == ScanProgressTracker.Phase.PREPARING) {
            scanParticleView.setPhaseText(getString(R.string.scan_phase_preparing));
            return;
        }
        if (phase == ScanProgressTracker.Phase.COMPLETE) {
            scanParticleView.setPhaseText(getString(R.string.scan_phase_complete));
            return;
        }
        if (phase == ScanProgressTracker.Phase.MEDIASTORE) {
            scanParticleView.setPhaseText(getString(R.string.scan_phase_mediastore));
            return;
        }
        if (phase == ScanProgressTracker.Phase.CACHE) {
            scanParticleView.setPhaseText(getString(R.string.scan_phase_cache));
            return;
        }

        scanParticleView.setPhaseText(getString(R.string.scan_phase_scanning));
    }

    private void showPanel(Panel panel) {
        homePanel.setVisibility(panel == Panel.HOME ? View.VISIBLE : View.GONE);
        scanPanel.setVisibility(panel == Panel.SCAN ? View.VISIBLE : View.GONE);
        resultsPanel.setVisibility(panel == Panel.RESULTS ? View.VISIBLE : View.GONE);
        if (scanParticleView != null) {
            if (panel == Panel.SCAN) {
                scanParticleView.start();
            } else {
                scanParticleView.stop();
            }
        }
        updateBottomNav(panel);
    }

    private void updateBottomNav(Panel panel) {
        bottomNav.setVisibility(panel == Panel.SCAN ? View.GONE : View.VISIBLE);
        if (panel == Panel.SCAN) {
            return;
        }
        int activeColor = resolveColorRes(R.color.brand_primary);
        int inactiveColor = resolveColorRes(R.color.text_secondary);

        styleNavItem(findViewById(R.id.nav_home), navHomeIcon, navHomeLabel, panel == Panel.HOME, activeColor, inactiveColor);
        styleNavItem(findViewById(R.id.nav_results), navResultsIcon, navResultsLabel, panel == Panel.RESULTS,
                activeColor, inactiveColor);
        navResultsIcon.setEnabled(true);
        navResultsLabel.setEnabled(true);
        findViewById(R.id.nav_results).setEnabled(true);
        styleNavItem(findViewById(R.id.nav_folder), navFolderIcon, navFolderLabel, false, activeColor, inactiveColor);
        styleNavItem(findViewById(R.id.nav_about), navAboutIcon, navAboutLabel, false, activeColor, inactiveColor);
    }

    private void styleNavItem(View container, ImageView icon, TextView label, boolean active, int activeColor, int inactiveColor) {
        int color = active ? activeColor : inactiveColor;
        container.setBackgroundResource(active ? R.drawable.bg_nav_item_active : android.R.color.transparent);
        icon.setColorFilter(color);
        label.setTextColor(color);
    }

    private void performLightHaptic(View view) {
        if (view != null) {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private int resolveColorRes(int resId) {
        return getResources().getColor(resId, getTheme());
    }

    private RecoveryCoordinator.ScanCallback createCoordinatorCallback() {
        return new RecoveryCoordinator.ScanCallback() {
            @Override
            public void onWorkingChanged(boolean value) {
                working = value;
                findViewById(R.id.recover_button).setEnabled(!value);
            }

            @Override
            public void onPrepareProgress(int countedSoFar, String currentPath) {
                scanProgressTracker.onPrepareProgress(countedSoFar);
                if (currentPath != null && !currentPath.isEmpty()) {
                    scanParticleView.setScanPath(currentPath);
                }
            }

            @Override
            public void onPrepareComplete(int totalEntries) {
                scanProgressTracker.onPrepared(totalEntries);
                updateScanProgressUi();
            }

            @Override
            public void onScanTypeChanged(RecoveryType type, int typeIndex, int typeCount) {
                if (typeIndex > 0) {
                    multiTypeCompletedScanned += scanProgressTracker.getPreparedTotal();
                }
                scanProgressTracker.beginType(typeIndex);
                updateScanTitleForType(type, typeIndex, typeCount);
                updateScanProgressUi();
            }

            @Override
            public void onScanPhaseChanged(ScanProgressTracker.Phase phase) {
                if (phase == ScanProgressTracker.Phase.MEDIASTORE) {
                    scanProgressTracker.onMediaStorePhaseStart();
                } else if (phase == ScanProgressTracker.Phase.CACHE) {
                    scanProgressTracker.onCachePhaseStart(0);
                }
                updateScanProgressUi();
            }

            @Override
            public void onPhaseProgress(ScanProgressTracker.Phase phase, int processedCount) {
                if (phase == ScanProgressTracker.Phase.MEDIASTORE) {
                    scanProgressTracker.onMediaStoreProgress(processedCount);
                } else if (phase == ScanProgressTracker.Phase.CACHE) {
                    scanProgressTracker.onCacheProgress(processedCount);
                }
                updateScanProgressUi();
            }

            @Override
            public void onProgress(int scannedCount, int foundCount, String currentPath) {
                lastScannedCount = scanAllMode ? multiTypeCompletedScanned + scannedCount : scannedCount;
                lastFoundCount = Math.max(lastFoundCount, foundCount);
                scanProgressTracker.onScanProgress(scannedCount);
                if (currentPath != null && !currentPath.isEmpty()) {
                    scanParticleView.setScanPath(currentPath);
                }
                updateScanProgressUi();
            }

            @Override
            public void onAlgorithmEvent(AlgorithmEvent event) {
                handleAlgorithmEvent(event);
            }

            @Override
            public void onAlgorithmProgress(String algorithmId, int processed, int found) {
                handleAlgorithmProgress(algorithmId, processed, found);
            }

            @Override
            public void onItemsBatch(List<RecoveryItem> items) {
                recoveryState.addAll(items);
                lastFoundCount = recoveryState.getAllCount();
                gridAdapter.notifyDataSetChanged();
                updateCounters();
            }

            @Override
            public void onScanComplete(int scannedCount, int foundCount) {
                lastScannedCount = scannedCount;
                lastFoundCount = foundCount;
                finishScanUi(lastScannedCount, lastFoundCount);
            }

            @Override
            public void onRecoverProgress(int successCount, int failedCount) {
                // Keep results visible during recovery.
            }

            @Override
            public void onRecoverComplete(int successCount, int failedCount, File lastOutput) {
                Intent intent = new Intent(MainActivity.this, RecoverCompleteActivity.class);
                intent.putExtra(RecoverCompleteActivity.EXTRA_SUCCESS, successCount);
                intent.putExtra(RecoverCompleteActivity.EXTRA_FAILED, failedCount);
                intent.putExtra(RecoverCompleteActivity.EXTRA_OUTPUT_PATH, lastOutput == null
                        ? RecoveryOutputPaths.primaryDisplayPath()
                        : lastOutput.getParent());
                startActivity(intent);
            }
        };
    }

    private enum Panel {
        HOME,
        SCAN,
        RESULTS
    }
}
