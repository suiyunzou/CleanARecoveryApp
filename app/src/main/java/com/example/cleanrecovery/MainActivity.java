package com.example.cleanrecovery;

import android.app.Activity;
import android.app.AlertDialog;
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

import java.io.File;
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
    private TextView scanTitle;
    private TextView scanPhaseLabel;
    private TextView scanPercent;
    private TextView scanEta;
    private TextView scanDetail;
    private ProgressBar scanProgressBar;
    private TextView scanStats;
    private TextView scanPathToggle;
    private TextView scanCurrentPath;
    private TextView resultsCount;
    private TextView selectedCount;
    private View resultsEmpty;
    private RecyclerView resultsGrid;
    private Button filterAllButton;
    private Button filterExistingButton;
    private Button filterDeletedButton;
    private View bottomNav;
    private ImageView navHomeIcon;
    private ImageView navResultsIcon;
    private ImageView navFolderIcon;
    private ImageView navAboutIcon;
    private TextView navHomeLabel;
    private TextView navResultsLabel;
    private TextView navFolderLabel;
    private TextView navAboutLabel;

    private String scanCurrentPathValue = "";

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
        refreshHome();
        maybeLaunchOnboarding();
        updateBottomNav(Panel.HOME);
    }

    @Override
    public void onBackPressed() {
        if (resultsPanel.getVisibility() == View.VISIBLE) {
            showPanel(Panel.HOME);
            return;
        }
        if (scanPanel.getVisibility() == View.VISIBLE) {
            recoveryCoordinator.cancelCurrentWork();
            showPanel(Panel.HOME);
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
        scanTitle = findViewById(R.id.scan_title);
        scanPhaseLabel = findViewById(R.id.scan_phase_label);
        scanPercent = findViewById(R.id.scan_percent);
        scanEta = findViewById(R.id.scan_eta);
        scanDetail = findViewById(R.id.scan_detail);
        scanProgressBar = findViewById(R.id.scan_progress_bar);
        scanStats = findViewById(R.id.scan_stats);
        scanPathToggle = findViewById(R.id.scan_path_toggle);
        scanCurrentPath = findViewById(R.id.scan_current_path);
        resultsCount = findViewById(R.id.results_count);
        selectedCount = findViewById(R.id.selected_count);
        resultsEmpty = findViewById(R.id.results_empty);
        resultsGrid = findViewById(R.id.results_grid);
        filterAllButton = findViewById(R.id.filter_all_button);
        filterExistingButton = findViewById(R.id.filter_existing_button);
        filterDeletedButton = findViewById(R.id.filter_deleted_button);
        bottomNav = findViewById(R.id.bottom_nav);
        navHomeIcon = findViewById(R.id.nav_home_icon);
        navResultsIcon = findViewById(R.id.nav_results_icon);
        navFolderIcon = findViewById(R.id.nav_folder_icon);
        navAboutIcon = findViewById(R.id.nav_about_icon);
        navHomeLabel = findViewById(R.id.nav_home_label);
        navResultsLabel = findViewById(R.id.nav_results_label);
        navFolderLabel = findViewById(R.id.nav_folder_label);
        navAboutLabel = findViewById(R.id.nav_about_label);

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
        setupCategoryCard(findViewById(R.id.card_images), RecoveryType.IMAGE, R.drawable.ic_type_image);
        setupCategoryCard(findViewById(R.id.card_videos), RecoveryType.VIDEO, R.drawable.ic_type_video);
        setupCategoryCard(findViewById(R.id.card_audio), RecoveryType.AUDIO, R.drawable.ic_type_audio);
        setupCategoryCard(findViewById(R.id.card_documents), RecoveryType.DOCUMENT, R.drawable.ic_type_document);
    }

    private void setupCategoryCard(View card, final RecoveryType type, int iconResId) {
        ImageView icon = card.findViewById(R.id.category_icon);
        TextView title = card.findViewById(R.id.category_title);
        title.setText(type.labelResId);
        icon.setImageResource(iconResId);
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
        findViewById(R.id.home_settings_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, AboutActivity.class));
            }
        });
        findViewById(R.id.open_output_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openRecoveryFolder();
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
            }
        });
        scanPathToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pathExpanded = !pathExpanded;
                scanCurrentPath.setVisibility(pathExpanded ? View.VISIBLE : View.GONE);
                scanPathToggle.setText(pathExpanded ? R.string.hide_current_path : R.string.show_current_path);
            }
        });
        findViewById(R.id.results_back_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showPanel(Panel.HOME);
            }
        });
        findViewById(R.id.nav_home).setOnClickListener(v -> showPanel(Panel.HOME));
        findViewById(R.id.nav_results).setOnClickListener(v -> {
            showPanel(Panel.RESULTS);
            updateCounters();
        });
        findViewById(R.id.nav_folder).setOnClickListener(v -> openRecoveryFolder());
        findViewById(R.id.nav_about).setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, AboutActivity.class)));
        filterAllButton.setOnClickListener(v -> setFilter(RecoveryState.FilterMode.ALL));
        filterExistingButton.setOnClickListener(v -> setFilter(RecoveryState.FilterMode.EXISTING));
        filterDeletedButton.setOnClickListener(v -> setFilter(RecoveryState.FilterMode.DELETED));
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
                    R.string.last_scan_summary,
                    when,
                    snapshot.lastScannedCount,
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
        permissionBannerAction.setText(granted
                ? R.string.trust_non_destructive
                : R.string.button_grant_access);
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
                .setMessage(R.string.experimental_scan_warning)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        startExperimentalScan(types[which]);
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
        scanCurrentPath.setVisibility(View.GONE);
        scanPathToggle.setText(R.string.show_current_path);
        if (allTypes) {
            scanTitle.setText(R.string.scan_status_all);
        } else if (experimental) {
            scanTitle.setText(getString(R.string.experimental_scan_status, getString(type.labelResId)));
        } else {
            scanTitle.setText(getString(R.string.scan_status, getString(type.labelResId)));
        }
        scanStats.setText(getString(R.string.scan_stats_format, 0, 0));
        updateScanProgressUi();
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
        boolean opened = RecoveryOutputPaths.openPrimaryFolder(this);
        if (!opened) {
            Toast.makeText(this, R.string.folder_open_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void setFilter(RecoveryState.FilterMode mode) {
        recoveryState.setFilter(mode);
        gridAdapter.notifyDataSetChanged();
        styleFilterTabs();
        updateCounters();
        updateEmptyState();
    }

    private void styleFilterTabs() {
        styleFilterButton(filterAllButton, recoveryState.getFilter() == RecoveryState.FilterMode.ALL);
        styleFilterButton(filterExistingButton, recoveryState.getFilter() == RecoveryState.FilterMode.EXISTING);
        styleFilterButton(filterDeletedButton, recoveryState.getFilter() == RecoveryState.FilterMode.DELETED);
    }

    private void styleFilterButton(Button button, boolean active) {
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
        if (scanAllMode) {
            scanTitle.setText(getString(
                    R.string.scan_phase_type,
                    getString(type.labelResId),
                    typeIndex + 1,
                    typeCount
            ));
        }
    }

    private void finishScanUi(int scannedCount, int foundCount) {
        if (!scanAllMode) {
            scanProgressTracker.onScanProgress(scannedCount);
        }
        scanProgressTracker.complete();
        lastScannedCount = scannedCount;
        lastFoundCount = foundCount;
        updateScanProgressUi();
        scanStats.setText(getString(R.string.scan_stats_format, scannedCount, foundCount));
        progressHandler.removeCallbacks(progressTick);
        persistScanHistory();
        gridAdapter.notifyDataSetChanged();
        styleFilterTabs();
        updateCounters();
        showPanel(Panel.RESULTS);
    }

    private void updateScanProgressUi() {
        int percent = scanProgressTracker.getDisplayPercent();
        scanPercent.setText(getString(R.string.scan_percent_format, percent));
        scanProgressBar.setProgress(percent);
        scanStats.setText(getString(R.string.scan_stats_format, lastScannedCount, lastFoundCount));

        ScanProgressTracker.Phase phase = scanProgressTracker.getPhase();
        if (phase == ScanProgressTracker.Phase.PREPARING) {
            scanPhaseLabel.setText(R.string.scan_phase_preparing);
            scanDetail.setText(getString(R.string.scan_prepare_detail, scanProgressTracker.getPreparedCount()));
            scanEta.setText(R.string.scan_eta_calculating);
            return;
        }
        if (phase == ScanProgressTracker.Phase.COMPLETE) {
            scanPhaseLabel.setText(R.string.scan_phase_complete);
            scanDetail.setText(getString(
                    R.string.scan_progress_detail,
                    scanProgressTracker.getScannedCount(),
                    Math.max(scanProgressTracker.getPreparedTotal(), scanProgressTracker.getScannedCount())
            ));
            scanEta.setText(R.string.scan_eta_complete);
            return;
        }
        if (phase == ScanProgressTracker.Phase.MEDIASTORE) {
            scanPhaseLabel.setText(R.string.scan_phase_mediastore);
            scanDetail.setText(getString(R.string.scan_stats_format, lastScannedCount, lastFoundCount));
            scanEta.setText(R.string.scan_eta_calculating);
            return;
        }
        if (phase == ScanProgressTracker.Phase.CACHE) {
            scanPhaseLabel.setText(R.string.scan_phase_cache);
            scanDetail.setText(getString(R.string.scan_stats_format, lastScannedCount, lastFoundCount));
            scanEta.setText(R.string.scan_eta_calculating);
            return;
        }

        scanPhaseLabel.setText(R.string.scan_phase_scanning);
        int total = scanProgressTracker.getPreparedTotal();
        int scanned = scanProgressTracker.getScannedCount();
        if (scanAllMode && total > 0) {
            total *= scanProgressTracker.getTypeCount();
            scanned = multiTypeCompletedScanned + scanProgressTracker.getScannedCount();
        }
        scanDetail.setText(getString(R.string.scan_progress_detail, scanned, total));
        long etaMs = scanProgressTracker.getEstimatedRemainingMs();
        if (etaMs < 0L) {
            scanEta.setText(R.string.scan_eta_calculating);
        } else if (etaMs < 1_000L) {
            scanEta.setText(R.string.scan_eta_almost_done);
        } else {
            scanEta.setText(formatEta(etaMs));
        }
    }

    private String formatEta(long etaMs) {
        long totalSeconds = Math.max(1L, (etaMs + 999L) / 1_000L);
        if (totalSeconds < 60L) {
            return getString(R.string.scan_eta_seconds, totalSeconds);
        }
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (minutes < 60L) {
            return getString(R.string.scan_eta_minutes, minutes, seconds);
        }
        long hours = minutes / 60L;
        minutes = minutes % 60L;
        return getString(R.string.scan_eta_hours, hours, minutes);
    }

    private void showPanel(Panel panel) {
        homePanel.setVisibility(panel == Panel.HOME ? View.VISIBLE : View.GONE);
        scanPanel.setVisibility(panel == Panel.SCAN ? View.VISIBLE : View.GONE);
        resultsPanel.setVisibility(panel == Panel.RESULTS ? View.VISIBLE : View.GONE);
        updateBottomNav(panel);
    }

    private boolean hasScanResults() {
        return recoveryState.getAllCount() > 0;
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
                scanCurrentPathValue = currentPath;
                if (pathExpanded) {
                    scanCurrentPath.setText(currentPath);
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
                lastFoundCount = foundCount;
                scanProgressTracker.onScanProgress(scannedCount);
                scanCurrentPathValue = currentPath;
                if (pathExpanded) {
                    scanCurrentPath.setText(currentPath);
                }
            }

            @Override
            public void onItemsBatch(List<RecoveryItem> items) {
                recoveryState.addAll(items);
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
