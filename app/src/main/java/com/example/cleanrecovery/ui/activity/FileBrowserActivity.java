package com.example.cleanrecovery.ui.activity;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.recovery.RecoveryOutputPaths;
import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.recycle.RecycleBin;
import com.example.cleanrecovery.ui.adapter.FileBrowserAdapter;
import com.example.cleanrecovery.ui.widget.FileBrowserMime;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.widget.SearchView;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class FileBrowserActivity extends Activity {
    public static final String EXTRA_INITIAL_PATH = "com.example.cleanrecovery.extra.INITIAL_PATH";

    private enum SortMode {
        NAME,
        SIZE,
        DATE
    }

    private final ArrayList<FileEntry> entries = new ArrayList<>();
    private final ArrayList<FileEntry> allEntries = new ArrayList<>();
    private FileBrowserAdapter adapter;
    private File currentDir;
    private SortMode sortMode = SortMode.NAME;
    private boolean showHiddenFiles;
    private String filterQuery = "";

    private RecyclerView listView;
    private View emptyPanel;
    private TextView emptyLabel;
    private View moreButton;
    private View toolbar;
    private View multiSelectBar;
    private TextView selectedCountLabel;
    private TextView titleLabel;
    private LinearLayout breadcrumbContainer;
    private HorizontalScrollView breadcrumbScroll;
    private SearchView searchInput;
    // 文件管理 V2：底部操作栏（多选模式）
    private View bottomActionBar;
    private final Handler undoSnackbarHandler = new Handler(Looper.getMainLooper());
    private View undoSnackbarView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_file_browser);

        toolbar = findViewById(R.id.file_browser_toolbar);
        multiSelectBar = findViewById(R.id.file_browser_multiselect_bar);
        selectedCountLabel = findViewById(R.id.file_browser_selected_count);
        titleLabel = findViewById(R.id.file_browser_title);
        emptyPanel = findViewById(R.id.file_browser_empty_panel);
        emptyLabel = findViewById(R.id.file_browser_empty);
        breadcrumbContainer = findViewById(R.id.file_browser_breadcrumb);
        breadcrumbScroll = findViewById(R.id.file_browser_breadcrumb_scroll);
        searchInput = findViewById(R.id.file_browser_search);
        listView = findViewById(R.id.file_browser_list);
        // 文件管理 V2：底部操作栏
        bottomActionBar = findViewById(R.id.file_browser_bottom_action_bar);
        findViewById(R.id.file_browser_batch_move).setOnClickListener(v -> confirmBatchMove());
        findViewById(R.id.file_browser_batch_copy).setOnClickListener(v -> confirmBatchCopy());
        findViewById(R.id.file_browser_batch_share).setOnClickListener(v -> shareSelected());
        findViewById(R.id.file_browser_batch_delete_bottom).setOnClickListener(v -> confirmBatchDelete());

        adapter = new FileBrowserAdapter(entries, new FileBrowserAdapter.Listener() {
            @Override
            public void onEntryClicked(FileEntry entry) {
                handleEntryClick(entry);
            }

            @Override
            public void onEntryLongClicked(FileEntry entry) {
                if (adapter.isMultiSelectMode()) {
                    return;
                }
                showEntryActions(entry);
            }

            @Override
            public void onEntryInfoClicked(FileEntry entry) {
                showEntryDetails(entry);
            }

            @Override
            public void onSelectionChanged(FileEntry entry, boolean selected) {
                Set<String> paths = adapter.getSelectedPaths();
                if (selected) {
                    paths.add(entry.file.getAbsolutePath());
                } else {
                    paths.remove(entry.file.getAbsolutePath());
                }
                adapter.setSelectedPaths(paths);
                updateMultiSelectUi();
            }
        });
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);

        moreButton = findViewById(R.id.file_browser_more);
        findViewById(R.id.file_browser_back).setOnClickListener(v -> finish());
        moreButton.setOnClickListener(this::showToolbarMenu);
        findViewById(R.id.file_browser_batch_delete).setOnClickListener(v -> confirmBatchDelete());
        findViewById(R.id.file_browser_cancel_select).setOnClickListener(v -> exitMultiSelectMode());

        configureSearchView();

        File initial = resolveInitialDirectory(getIntent().getStringExtra(EXTRA_INITIAL_PATH));
        openDirectory(initial);
    }

    private void configureSearchView() {
        searchInput.setIconifiedByDefault(false);
        searchInput.setSubmitButtonEnabled(false);
        searchInput.setQueryHint(getString(R.string.file_browser_search_hint));
        searchInput.setMaxWidth(Integer.MAX_VALUE);

        SearchView.SearchAutoComplete searchText =
                searchInput.findViewById(androidx.appcompat.R.id.search_src_text);
        if (searchText != null) {
            searchText.setTextColor(getColorCompat(R.color.text_primary));
            searchText.setHintTextColor(getColorCompat(R.color.text_muted));
            searchText.setSingleLine(true);
        }

        searchInput.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                searchInput.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                filterQuery = newText == null ? "" : newText.trim().toLowerCase(Locale.US);
                applyFilterAndSort();
                return true;
            }
        });
    }

    private boolean isSearchOpen() {
        return searchInput != null && searchInput.getVisibility() == View.VISIBLE;
    }

    private void openSearch() {
        if (searchInput == null) {
            return;
        }
        searchInput.setVisibility(View.VISIBLE);
        searchInput.post(new Runnable() {
            @Override
            public void run() {
                searchInput.requestFocus();
                SearchView.SearchAutoComplete searchText =
                        searchInput.findViewById(androidx.appcompat.R.id.search_src_text);
                View focusTarget = searchInput;
                if (searchText != null) {
                    searchText.requestFocus();
                    searchText.setSelection(searchText.length());
                    focusTarget = searchText;
                }
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(focusTarget, InputMethodManager.SHOW_IMPLICIT);
                }
            }
        });
    }

    private void closeSearch() {
        closeSearch(true);
    }

    private void closeSearch(boolean refresh) {
        if (searchInput == null || (!isSearchOpen() && filterQuery.isEmpty())) {
            return;
        }
        boolean hadQuery = !filterQuery.isEmpty();
        filterQuery = "";
        searchInput.setQuery("", false);
        searchInput.clearFocus();
        searchInput.setVisibility(View.GONE);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        }
        if (refresh && hadQuery) {
            applyFilterAndSort();
        }
    }

    @Override
    public void onBackPressed() {
        if (isSearchOpen()) {
            closeSearch();
            return;
        }
        if (adapter.isMultiSelectMode()) {
            exitMultiSelectMode();
            return;
        }
        if (currentDir != null && canNavigateUp(currentDir)) {
            navigateUp();
            return;
        }
        super.onBackPressed();
    }

    public static void open(Activity activity, File directory) {
        Intent intent = new Intent(activity, FileBrowserActivity.class);
        if (directory != null) {
            intent.putExtra(EXTRA_INITIAL_PATH, directory.getAbsolutePath());
        }
        activity.startActivity(intent);
    }

    private File resolveInitialDirectory(String requestedPath) {
        if (requestedPath != null && !requestedPath.isEmpty()) {
            File requested = new File(requestedPath);
            if (requested.isDirectory() && isAccessible(requested)) {
                return requested;
            }
            File parent = requested.getParentFile();
            if (parent != null && parent.isDirectory() && isAccessible(parent)) {
                return parent;
            }
        }
        File recovery = RecoveryOutputPaths.primaryDataRecoveryDir();
        if (!recovery.exists()) {
            recovery.mkdirs();
        }
        if (isAccessible(recovery)) {
            return recovery;
        }
        File external = Environment.getExternalStorageDirectory();
        return isAccessible(external) ? external : recovery;
    }

    private void openDirectory(File directory) {
        if (directory == null || !directory.isDirectory() || !isAccessible(directory)) {
            Toast.makeText(this, R.string.file_browser_unreadable, Toast.LENGTH_SHORT).show();
            return;
        }
        exitMultiSelectMode();
        closeSearch(false);
        currentDir = directory;
        updateBreadcrumbs();
        reloadEntries();
    }

    private void reloadEntries() {
        allEntries.clear();
        if (currentDir == null) {
            applyFilterAndSort();
            return;
        }

        File[] children = currentDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!isAccessible(child)) {
                    continue;
                }
                if (!showHiddenFiles && child.getName().startsWith(".")) {
                    continue;
                }
                allEntries.add(FileEntry.from(child));
            }
        }
        applyFilterAndSort();
    }

    private void applyFilterAndSort() {
        entries.clear();
        for (FileEntry entry : allEntries) {
            if (matchesFilter(entry)) {
                entries.add(entry);
            }
        }
        sortEntries(entries);
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private boolean matchesFilter(FileEntry entry) {
        if (filterQuery.isEmpty()) {
            return true;
        }
        return entry.name.toLowerCase(Locale.US).contains(filterQuery);
    }

    private void sortEntries(List<FileEntry> target) {
        target.sort(new Comparator<FileEntry>() {
            @Override
            public int compare(FileEntry left, FileEntry right) {
                if (left.directory != right.directory) {
                    return left.directory ? -1 : 1;
                }
                switch (sortMode) {
                    case SIZE:
                        int sizeCompare = Long.compare(right.size, left.size);
                        return sizeCompare != 0 ? sizeCompare : left.name.compareToIgnoreCase(right.name);
                    case DATE:
                        int dateCompare = Long.compare(right.lastModified, left.lastModified);
                        return dateCompare != 0 ? dateCompare : left.name.compareToIgnoreCase(right.name);
                    case NAME:
                    default:
                        return left.name.compareToIgnoreCase(right.name);
                }
            }
        });
    }

    private void updateEmptyState() {
        boolean folderEmpty = allEntries.isEmpty();
        boolean noMatches = !folderEmpty && entries.isEmpty();
        boolean showEmpty = folderEmpty || noMatches;
        listView.setVisibility(showEmpty ? View.GONE : View.VISIBLE);
        emptyPanel.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
        if (folderEmpty) {
            emptyLabel.setText(R.string.file_browser_empty);
        } else if (noMatches) {
            emptyLabel.setText(R.string.file_browser_no_matches);
        }
    }

    private void updateBreadcrumbs() {
        breadcrumbContainer.removeAllViews();
        if (currentDir == null) {
            return;
        }
        List<BreadcrumbSegment> segments = buildBreadcrumbSegments(currentDir);
        for (int i = 0; i < segments.size(); i++) {
            BreadcrumbSegment segment = segments.get(i);
            boolean current = i == segments.size() - 1;
            TextView label = new TextView(this);
            label.setText(segment.label);
            label.setTextColor(getColorCompat(current ? R.color.brand_primary_dark : R.color.text_muted));
            label.setTextSize(getResources().getDimension(R.dimen.text_caption) / getResources().getDisplayMetrics().scaledDensity);
            label.setGravity(Gravity.CENTER_VERTICAL);
            label.setMinHeight(dp(32));
            label.setPadding(dp(2), 0, dp(2), 0);
            label.setTypeface(label.getTypeface(), current ? Typeface.BOLD : Typeface.NORMAL);
            label.setClickable(!current);
            label.setFocusable(!current);
            if (!current) {
                final File target = segment.directory;
                label.setOnClickListener(v -> openDirectory(target));
            }
            breadcrumbContainer.addView(label);

            if (!current) {
                TextView separator = new TextView(this);
                separator.setText(" \u203A ");
                separator.setTextColor(getColorCompat(R.color.text_muted));
                separator.setTextSize(getResources().getDimension(R.dimen.text_caption) / getResources().getDisplayMetrics().scaledDensity);
                separator.setGravity(Gravity.CENTER_VERTICAL);
                separator.setMinHeight(dp(32));
                breadcrumbContainer.addView(separator);
            }
        }
        breadcrumbScroll.post(new Runnable() {
            @Override
            public void run() {
                breadcrumbScroll.fullScroll(View.FOCUS_RIGHT);
            }
        });
    }

    private List<BreadcrumbSegment> buildBreadcrumbSegments(File directory) {
        ArrayList<BreadcrumbSegment> segments = new ArrayList<>();
        File root = breadcrumbRoot(directory);
        File cursor = directory;
        while (cursor != null) {
            segments.add(new BreadcrumbSegment(displayNameFor(cursor, root), cursor));
            if (root != null && isSamePath(cursor, root)) {
                break;
            }
            File parent = cursor.getParentFile();
            if (parent == null) {
                break;
            }
            if (root != null) {
                if (!isPathAtOrUnder(parent, root)) {
                    break;
                }
            } else if (!isUnderAllowedRoot(parent)) {
                break;
            }
            cursor = parent;
        }
        java.util.Collections.reverse(segments);
        return segments;
    }

    private static File breadcrumbRoot(File directory) {
        File external = Environment.getExternalStorageDirectory();
        if (external != null && isPathAtOrUnder(directory, external)) {
            return external;
        }
        File recoveryParent = RecoveryOutputPaths.primaryDataRecoveryDir().getParentFile();
        if (recoveryParent != null && isPathAtOrUnder(directory, recoveryParent)) {
            return recoveryParent;
        }
        return nearestAllowedRoot(directory);
    }

    private static File nearestAllowedRoot(File directory) {
        File best = null;
        int bestLength = -1;
        for (File root : allowedRoots()) {
            String rootPath = root.getAbsolutePath();
            if (isPathAtOrUnder(directory, root) && rootPath.length() > bestLength) {
                best = root;
                bestLength = rootPath.length();
            }
        }
        return best;
    }

    private String displayNameFor(File directory, File root) {
        if (root != null && isSamePath(directory, root)) {
            File external = Environment.getExternalStorageDirectory();
            if (external != null && isSamePath(root, external)) {
                return getString(R.string.file_browser_internal_storage);
            }
            String name = root.getName();
            if (name == null || name.isEmpty()) {
                return root.getAbsolutePath();
            }
            return name;
        }
        return directory.getName();
    }

    private static boolean isSamePath(File left, File right) {
        return left != null && right != null && left.getAbsolutePath().equals(right.getAbsolutePath());
    }

    private static boolean isPathAtOrUnder(File child, File root) {
        if (child == null || root == null) {
            return false;
        }
        String childPath = child.getAbsolutePath();
        String rootPath = root.getAbsolutePath();
        return childPath.equals(rootPath) || childPath.startsWith(rootPath + File.separator);
    }

    private void showToolbarMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        menu.getMenuInflater().inflate(R.menu.menu_file_browser, menu.getMenu());
        menu.getMenu().findItem(R.id.menu_search).setTitle(
                isSearchOpen() ? R.string.file_browser_close_search : R.string.file_browser_search_files);
        boolean writable = canWriteInCurrentDir();
        menu.getMenu().findItem(R.id.menu_new_folder).setEnabled(writable);
        menu.getMenu().findItem(R.id.menu_select_items).setEnabled(writable);
        menu.getMenu().findItem(R.id.menu_sort_name).setChecked(sortMode == SortMode.NAME);
        menu.getMenu().findItem(R.id.menu_sort_size).setChecked(sortMode == SortMode.SIZE);
        menu.getMenu().findItem(R.id.menu_sort_date).setChecked(sortMode == SortMode.DATE);
        menu.getMenu().findItem(R.id.menu_show_hidden).setChecked(showHiddenFiles);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
                if (id == R.id.menu_search) {
                    if (isSearchOpen()) {
                        closeSearch();
                    } else {
                        openSearch();
                    }
                    return true;
                }
                if (id == R.id.menu_new_folder) {
                    showCreateFolderDialog();
                    return true;
                }
                if (id == R.id.menu_select_items) {
                    toggleMultiSelectMode();
                    return true;
                }
                if (id == R.id.menu_recycle_bin) {
                    openRecycleBin();
                    return true;
                }
                if (id == R.id.menu_sort_name) {
                    sortMode = SortMode.NAME;
                    applyFilterAndSort();
                    return true;
                }
                if (id == R.id.menu_sort_size) {
                    sortMode = SortMode.SIZE;
                    applyFilterAndSort();
                    return true;
                }
                if (id == R.id.menu_sort_date) {
                    sortMode = SortMode.DATE;
                    applyFilterAndSort();
                    return true;
                }
                if (id == R.id.menu_show_hidden) {
                    showHiddenFiles = !showHiddenFiles;
                    reloadEntries();
                    return true;
                }
                return false;
            }
        });
        menu.show();
    }

    private void toggleMultiSelectMode() {
        if (adapter.isMultiSelectMode()) {
            exitMultiSelectMode();
        } else {
            enterMultiSelectMode();
        }
    }

    private void enterMultiSelectMode() {
        if (!canWriteInCurrentDir()) {
            Toast.makeText(this, R.string.file_browser_not_writable, Toast.LENGTH_SHORT).show();
            return;
        }
        closeSearch(false);
        adapter.setMultiSelectMode(true);
        updateMultiSelectUi();
    }

    private void exitMultiSelectMode() {
        adapter.setMultiSelectMode(false);
        updateMultiSelectUi();
    }

    private void updateMultiSelectUi() {
        boolean selecting = adapter.isMultiSelectMode();
        multiSelectBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        toolbar.setVisibility(selecting ? View.GONE : View.VISIBLE);
        // 文件管理 V2：多选时显示底部操作栏
        bottomActionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) {
            titleLabel.setText(getString(R.string.file_browser_selected_count, adapter.getSelectedCount()));
            selectedCountLabel.setText(getString(R.string.file_browser_selected_count, adapter.getSelectedCount()));
            findViewById(R.id.file_browser_batch_delete).setEnabled(adapter.getSelectedCount() > 0);
            boolean hasSelection = adapter.getSelectedCount() > 0;
            findViewById(R.id.file_browser_batch_move).setEnabled(hasSelection);
            findViewById(R.id.file_browser_batch_copy).setEnabled(hasSelection);
            findViewById(R.id.file_browser_batch_share).setEnabled(hasSelection);
            findViewById(R.id.file_browser_batch_delete_bottom).setEnabled(hasSelection);
        } else {
            titleLabel.setText(R.string.file_browser_title);
        }
    }

    private void confirmBatchDelete() {
        final Set<String> selectedPaths = adapter.getSelectedPaths();
        if (selectedPaths.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_browser_delete)
                .setMessage(getString(R.string.file_browser_batch_delete_confirm, selectedPaths.size()))
                .setPositiveButton(R.string.file_browser_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        batchDelete(selectedPaths);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void batchDelete(Set<String> selectedPaths) {
        final RecycleBin bin = new RecycleBin();
        final int[] moved = {0};
        final int[] total = {0};
        final List<String> deletedNames = new ArrayList<>();
        for (String path : selectedPaths) {
            File file = new File(path);
            if (!canModify(file)) {
                continue;
            }
            if (file.isDirectory()) {
                // 空目录直接删除，无需进回收站
                if (isDirectoryEmpty(file) && deleteEmptyDirectory(file)) {
                    moved[0]++;
                }
                continue;
            }
            total[0]++;
            try {
                if (bin.moveToTrashSync(file)) {
                    moved[0]++;
                    deletedNames.add(file.getName());
                }
            } catch (IOException ignored) {
            }
        }
        exitMultiSelectMode();
        reloadEntries();
        if (moved[0] == 0) {
            Toast.makeText(this, R.string.file_browser_recycle_bin_move_failed, Toast.LENGTH_SHORT).show();
        } else if (total[0] > 0) {
            // 文件管理 V2：批量删除也支持 Snackbar 撤销
            showUndoSnackbar(getString(R.string.file_browser_snackbar_deleted),
                    R.string.file_browser_undo, () -> {
                        for (String name : deletedNames) {
                            try {
                                bin.restoreByNameSync(name);
                            } catch (IOException ignored) {
                            }
                        }
                        reloadEntries();
                    });
        }
    }

    private void openRecycleBin() {
        Intent intent = new Intent(this, RecycleBinActivity.class);
        startActivity(intent);
    }

    // ==================== 文件管理 V2：批量移动 / 复制 / 分享 ====================

    /** 单文件分享 */
    private void shareSingle(File file) {
        ArrayList<Uri> uris = new ArrayList<>();
        uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file));
        launchShareIntent(uris);
    }

    /** 批量分享选中项 */
    private void shareSelected() {
        Set<String> selectedPaths = adapter.getSelectedPaths();
        if (selectedPaths.isEmpty()) {
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        for (String path : selectedPaths) {
            File file = new File(path);
            if (file.isFile()) {
                uris.add(FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file));
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.file_browser_share, Toast.LENGTH_SHORT).show();
            return;
        }
        launchShareIntent(uris);
        exitMultiSelectMode();
    }

    private void launchShareIntent(ArrayList<Uri> uris) {
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("*/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.file_browser_share)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.file_browser_share, Toast.LENGTH_SHORT).show();
        }
    }

    /** 批量移动：弹出目录选择对话框 */
    private void confirmBatchMove() {
        final Set<String> selectedPaths = adapter.getSelectedPaths();
        if (selectedPaths.isEmpty()) {
            return;
        }
        if (currentDir == null) {
            return;
        }
        // 列出当前根下所有可写子目录作为候选目标
        final List<File> candidates = listMoveDestinations();
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.file_browser_move_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).getAbsolutePath().equals(
                    Environment.getExternalStorageDirectory().getAbsolutePath())
                    ? getString(R.string.file_browser_title)
                    : candidates.get(i).getName();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_browser_move_to)
                .setItems(names, (dialog, which) -> {
                    File dest = candidates.get(which);
                    performBatchMove(selectedPaths, dest);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 批量复制：弹出目录选择对话框 */
    private void confirmBatchCopy() {
        final Set<String> selectedPaths = adapter.getSelectedPaths();
        if (selectedPaths.isEmpty()) {
            return;
        }
        if (currentDir == null) {
            return;
        }
        final List<File> candidates = listMoveDestinations();
        if (candidates.isEmpty()) {
            Toast.makeText(this, R.string.file_browser_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] names = new String[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            names[i] = candidates.get(i).getAbsolutePath().equals(
                    Environment.getExternalStorageDirectory().getAbsolutePath())
                    ? getString(R.string.file_browser_title)
                    : candidates.get(i).getName();
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_browser_copy_to)
                .setItems(names, (dialog, which) -> {
                    File dest = candidates.get(which);
                    performBatchCopy(selectedPaths, dest);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 列出可用的移动/复制目标目录（当前根的子目录 + 公共根） */
    private List<File> listMoveDestinations() {
        ArrayList<File> result = new ArrayList<>();
        File external = Environment.getExternalStorageDirectory();
        if (external != null && isAccessible(external)) {
            result.add(external);
            addSubDirs(result, external, 1);
        }
        return result;
    }

    private void addSubDirs(List<File> out, File dir, int depth) {
        if (depth <= 0 || dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File c : children) {
            if (c.isDirectory() && !c.getName().startsWith(".")) {
                out.add(c);
            }
        }
    }

    /** 执行批量移动，完成后显示 Snackbar 撤销 */
    private void performBatchMove(Set<String> selectedPaths, final File dest) {
        if (dest.equals(currentDir)) {
            Toast.makeText(this, R.string.file_browser_move_same_dir, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String[]> moved = new ArrayList<>(); // {src, dest}
        for (String path : selectedPaths) {
            File src = new File(path);
            if (!canModify(src)) continue;
            File target = new File(dest, src.getName());
            if (target.exists()) {
                target = uniqueName(target);
            }
            if (src.renameTo(target)) {
                moved.add(new String[]{src.getAbsolutePath(), target.getAbsolutePath()});
            }
        }
        exitMultiSelectMode();
        reloadEntries();
        if (moved.isEmpty()) {
            Toast.makeText(this, R.string.file_browser_move_failed, Toast.LENGTH_SHORT).show();
        } else {
            showUndoSnackbar(getString(R.string.file_browser_snackbar_moved, dest.getName()),
                    R.string.file_browser_undo, () -> {
                        for (String[] pair : moved) {
                            File back = new File(pair[0]);
                            File cur = new File(pair[1]);
                            cur.renameTo(back);
                        }
                        reloadEntries();
                    });
        }
    }

    /** 执行批量复制，完成后显示 Snackbar */
    private void performBatchCopy(Set<String> selectedPaths, final File dest) {
        if (dest.equals(currentDir)) {
            Toast.makeText(this, R.string.file_browser_move_same_dir, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> copied = new ArrayList<>();
        for (String path : selectedPaths) {
            File src = new File(path);
            File target = new File(dest, src.getName());
            if (target.exists()) {
                target = uniqueName(target);
            }
            try {
                if (src.isFile()) {
                    copyFile(src, target);
                    copied.add(target.getAbsolutePath());
                }
            } catch (IOException ignored) {
            }
        }
        exitMultiSelectMode();
        reloadEntries();
        if (copied.isEmpty()) {
            Toast.makeText(this, R.string.file_browser_copy_failed, Toast.LENGTH_SHORT).show();
        } else {
            showUndoSnackbar(getString(R.string.file_browser_snackbar_copied, dest.getName()),
                    R.string.file_browser_undo, () -> {
                        for (String p : copied) {
                            new File(p).delete();
                        }
                        reloadEntries();
                    });
        }
    }

    private File uniqueName(File target) {
        if (!target.exists()) return target;
        String name = target.getName();
        int dot = name.lastIndexOf('.');
        String base, ext;
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        } else {
            base = name;
            ext = "";
        }
        int i = 1;
        File candidate;
        do {
            candidate = new File(target.getParentFile(), base + " (" + i + ")" + ext);
            i++;
        } while (candidate.exists());
        return candidate;
    }

    private void copyFile(File src, File dest) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dest);
             FileChannel inCh = in.getChannel();
             FileChannel outCh = out.getChannel()) {
            inCh.transferTo(0, inCh.size(), outCh);
        }
    }

    private void handleEntryClick(FileEntry entry) {
        if (entry.directory) {
            openDirectory(entry.file);
            return;
        }
        FileBrowserMime.Kind kind = FileBrowserMime.kindFor(entry.file, entry.name, false);
        if (kind == FileBrowserMime.Kind.APK) {
            installApk(entry.file);
            return;
        }
        if (kind == FileBrowserMime.Kind.VIDEO
                || kind == FileBrowserMime.Kind.IMAGE
                || kind == FileBrowserMime.Kind.AUDIO) {
            openInAppPreview(entry.file, kind);
            return;
        }
        if (kind == FileBrowserMime.Kind.TEXT) {
            TextViewerActivity.open(this, entry.file);
            return;
        }
        openWithExternalApp(entry.file);
    }

    private void installApk(File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.file_browser_apk_install_blocked_title)
                        .setMessage(R.string.file_browser_apk_install_blocked_message)
                        .setPositiveButton(R.string.file_browser_open_settings, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                settings.setData(Uri.parse("package:" + getPackageName()));
                                try {
                                    startActivity(settings);
                                } catch (ActivityNotFoundException ignored) {
                                    Toast.makeText(
                                            FileBrowserActivity.this,
                                            R.string.file_browser_apk_install_blocked_message,
                                            Toast.LENGTH_LONG
                                    ).show();
                                }
                            }
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return;
            }
        }
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            showNoAppDialog(file);
        }
    }

    private void openInAppPreview(File file, FileBrowserMime.Kind kind) {
        RecoveryType type = FileBrowserMime.recoveryTypeFor(kind);
        Intent intent = new Intent(this, PreviewActivity.class);
        intent.putExtra(PreviewActivity.EXTRA_PATH, file.getAbsolutePath());
        intent.putExtra(PreviewActivity.EXTRA_NAME, file.getName());
        intent.putExtra(PreviewActivity.EXTRA_TYPE, type.name());
        intent.putExtra(PreviewActivity.EXTRA_SUSPECTED_DELETED, false);
        startActivity(intent);
    }

    private void openWithExternalApp(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        String mime = FileBrowserMime.mimeTypeFor(file, file.getName());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.file_browser_open_with)));
        } catch (ActivityNotFoundException exception) {
            showNoAppDialog(file);
        }
    }

    private void showNoAppDialog(File file) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_browser_no_app_title)
                .setMessage(getString(R.string.file_browser_no_app_message, file.getName()))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void showEntryDetails(FileEntry entry) {
        FileBrowserMime.Kind kind = FileBrowserMime.kindFor(entry.file, entry.name, entry.directory);
        String typeLabel = localizedTypeLabel(kind);
        String modified = DateFormat.getMediumDateFormat(this).format(new Date(entry.lastModified))
                + " "
                + DateFormat.getTimeFormat(this).format(new Date(entry.lastModified));
        String size = entry.directory ? "—" : entry.formattedSize();
        String message = getString(R.string.file_browser_detail_name) + ": " + entry.name + "\n"
                + getString(R.string.file_browser_detail_type) + ": " + typeLabel + "\n"
                + getString(R.string.file_browser_detail_size) + ": " + size + "\n"
                + getString(R.string.file_browser_detail_modified) + ": " + modified + "\n"
                + getString(R.string.file_browser_detail_path) + ":\n" + entry.file.getAbsolutePath();
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_browser_details)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String localizedTypeLabel(FileBrowserMime.Kind kind) {
        switch (kind) {
            case DIRECTORY:
                return getString(R.string.file_browser_folder);
            case VIDEO:
                return getString(R.string.file_browser_type_video);
            case IMAGE:
                return getString(R.string.file_browser_type_image);
            case AUDIO:
                return getString(R.string.file_browser_type_audio);
            case TEXT:
                return getString(R.string.file_browser_type_text);
            case PDF:
                return getString(R.string.file_browser_type_pdf);
            case ZIP:
                return getString(R.string.file_browser_type_zip);
            case APK:
                return getString(R.string.file_browser_type_apk);
            case DOC:
                return getString(R.string.file_browser_type_doc);
            case XLS:
                return getString(R.string.file_browser_type_xls);
            case PPT:
                return getString(R.string.file_browser_type_ppt);
            case UNKNOWN:
            default:
                return getString(R.string.file_browser_type_file);
        }
    }

    private void showCreateFolderDialog() {
        if (!canWriteInCurrentDir()) {
            Toast.makeText(this, R.string.file_browser_not_writable, Toast.LENGTH_SHORT).show();
            return;
        }
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.file_browser_new_folder_hint);
        int padding = getResources().getDimensionPixelSize(R.dimen.space_md);
        input.setPadding(padding, padding / 2, padding, padding / 2);

        new AlertDialog.Builder(this)
                .setTitle(R.string.file_browser_new_folder)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        createFolder(input.getText().toString());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void createFolder(String rawName) {
        if (currentDir == null || !canWriteInCurrentDir()) {
            Toast.makeText(this, R.string.file_browser_not_writable, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = rawName == null ? "" : rawName.trim();
        if (!FileBrowserMime.isValidName(name)) {
            Toast.makeText(this, R.string.file_browser_invalid_name, Toast.LENGTH_SHORT).show();
            return;
        }
        File target = new File(currentDir, name);
        if (target.exists()) {
            Toast.makeText(this, R.string.file_browser_name_exists, Toast.LENGTH_SHORT).show();
            return;
        }
        if (target.mkdir()) {
            reloadEntries();
        } else {
            Toast.makeText(this, R.string.file_browser_create_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void showEntryActions(final FileEntry entry) {
        // 文件管理 V2：使用 BottomSheet 替代 AlertDialog，符合现代交互趋势
        final View sheet = getLayoutInflater().inflate(R.layout.bottom_sheet_file_actions, null);
        final BottomSheetHolder holder = new BottomSheetHolder(sheet);
        holder.title.setText(entry.name);

        // 不可修改时隐藏破坏性操作与重命名
        boolean canModify = canModify(entry.file);
        holder.actionRename.setVisibility(canModify ? View.VISIBLE : View.GONE);
        holder.actionDelete.setVisibility(canModify ? View.VISIBLE : View.GONE);
        holder.divider.setVisibility(canModify ? View.VISIBLE : View.GONE);

        final android.app.Dialog dialog = new android.app.Dialog(this,
                android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        dialog.setContentView(sheet);
        dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
        dialog.setCanceledOnTouchOutside(true);

        holder.actionOpenWith.setOnClickListener(v -> {
            dialog.dismiss();
            openWithExternalApp(entry.file);
        });
        holder.actionRename.setOnClickListener(v -> {
            dialog.dismiss();
            showRenameDialog(entry);
        });
        holder.actionShare.setOnClickListener(v -> {
            dialog.dismiss();
            shareSingle(entry.file);
        });
        holder.actionDetails.setOnClickListener(v -> {
            dialog.dismiss();
            showEntryDetails(entry);
        });
        holder.actionDelete.setOnClickListener(v -> {
            dialog.dismiss();
            confirmDelete(entry);
        });

        dialog.show();
    }

    /** BottomSheet 视图持有者，避免重复 findViewById */
    private static final class BottomSheetHolder {
        final TextView title;
        final View actionOpenWith;
        final View actionRename;
        final View actionShare;
        final View actionDetails;
        final View actionDelete;
        final View divider;

        BottomSheetHolder(View root) {
            title = root.findViewById(R.id.bottom_sheet_title);
            actionOpenWith = root.findViewById(R.id.action_open_with);
            actionRename = root.findViewById(R.id.action_rename);
            actionShare = root.findViewById(R.id.action_share);
            actionDetails = root.findViewById(R.id.action_details);
            actionDelete = root.findViewById(R.id.action_delete);
            divider = root.findViewById(R.id.bottom_sheet_divider);
        }
    }

    private void showRenameDialog(final FileEntry entry) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(entry.name);
        input.setSelection(entry.name.length());
        int padding = getResources().getDimensionPixelSize(R.dimen.space_md);
        input.setPadding(padding, padding / 2, padding, padding / 2);

        new AlertDialog.Builder(this)
                .setTitle(R.string.file_browser_rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        renameEntry(entry, input.getText().toString());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void renameEntry(FileEntry entry, String rawName) {
        if (!canModify(entry.file)) {
            Toast.makeText(this, R.string.file_browser_not_writable, Toast.LENGTH_SHORT).show();
            return;
        }
        String name = rawName == null ? "" : rawName.trim();
        if (!FileBrowserMime.isValidName(name)) {
            Toast.makeText(this, R.string.file_browser_invalid_name, Toast.LENGTH_SHORT).show();
            return;
        }
        File target = new File(entry.file.getParentFile(), name);
        if (target.exists()) {
            Toast.makeText(this, R.string.file_browser_name_exists, Toast.LENGTH_SHORT).show();
            return;
        }
        if (entry.file.renameTo(target)) {
            reloadEntries();
        } else {
            Toast.makeText(this, R.string.file_browser_rename_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(final FileEntry entry) {
        if (entry.directory && !isDirectoryEmpty(entry.file)) {
            Toast.makeText(this, R.string.file_browser_delete_not_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.file_browser_delete)
                .setMessage(getString(R.string.file_browser_delete_confirm, entry.name))
                .setPositiveButton(R.string.file_browser_delete, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deleteEntry(entry);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void deleteEntry(FileEntry entry) {
        if (!canModify(entry.file)) {
            Toast.makeText(this, R.string.file_browser_not_writable, Toast.LENGTH_SHORT).show();
            return;
        }
        // 空目录直接删除；非空目录不允许删除；文件进回收站
        if (entry.directory) {
            boolean deleted = deleteEmptyDirectory(entry.file);
            if (deleted) {
                reloadEntries();
            } else {
                Toast.makeText(this, R.string.file_browser_delete_failed, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        RecycleBin bin = new RecycleBin();
        try {
            if (bin.moveToTrashSync(entry.file)) {
                reloadEntries();
                // 文件管理 V2：Snackbar 撤销机制，5 秒内可恢复
                showUndoSnackbar(getString(R.string.file_browser_snackbar_deleted),
                        R.string.file_browser_undo, () -> restoreFromTrash(bin, entry.file.getName()));
            } else {
                Toast.makeText(this, R.string.file_browser_recycle_bin_move_failed, Toast.LENGTH_SHORT).show();
            }
        } catch (IOException e) {
            Toast.makeText(this, R.string.file_browser_recycle_bin_move_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 文件管理 V2：显示撤销 Snackbar，5 秒内点击撤销则执行 undoAction */
    private void showUndoSnackbar(String message, int actionLabel, final Runnable undoAction) {
        FrameLayout root = findViewById(android.R.id.content);
        if (root == null) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return;
        }

        removeUndoSnackbar();

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        int horizontalPadding = dp(16);
        int verticalPadding = dp(10);
        bar.setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding);

        GradientDrawable background = new GradientDrawable();
        background.setColor(0xFF263238);
        background.setCornerRadius(dp(6));
        bar.setBackground(background);
        bar.setElevation(dp(6));

        TextView label = new TextView(this);
        label.setText(message);
        label.setTextColor(getColorCompat(R.color.text_on_primary));
        label.setTextSize(14);
        label.setMaxLines(2);
        label.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bar.addView(label, labelParams);

        Button action = new Button(this);
        action.setText(actionLabel);
        action.setTextColor(getColorCompat(R.color.brand_secondary));
        action.setAllCaps(false);
        action.setBackgroundColor(0x00000000);
        action.setMinWidth(dp(48));
        action.setPadding(dp(12), 0, dp(12), 0);
        bar.addView(action, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        int margin = dp(16);
        params.setMargins(margin, margin, margin, margin);
        root.addView(bar, params);
        undoSnackbarView = bar;

        Runnable dismissAction = this::removeUndoSnackbar;
        undoSnackbarHandler.postDelayed(dismissAction, 5000);
        action.setOnClickListener(v -> {
            undoSnackbarHandler.removeCallbacks(dismissAction);
            removeUndoSnackbar();
            undoAction.run();
            Toast.makeText(this, R.string.file_browser_snackbar_restored, Toast.LENGTH_SHORT).show();
        });
    }

    private void removeUndoSnackbar() {
        undoSnackbarHandler.removeCallbacksAndMessages(null);
        if (undoSnackbarView == null) {
            return;
        }
        View parent = (View) undoSnackbarView.getParent();
        if (parent instanceof FrameLayout) {
            ((FrameLayout) parent).removeView(undoSnackbarView);
        }
        undoSnackbarView = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int getColorCompat(int colorRes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return getColor(colorRes);
        }
        return getResources().getColor(colorRes);
    }

    /** 文件管理 V2：从回收站恢复指定文件名 */
    private void restoreFromTrash(RecycleBin bin, String fileName) {
        try {
            bin.restoreByNameSync(fileName);
            reloadEntries();
        } catch (IOException e) {
            Toast.makeText(this, R.string.file_browser_recycle_bin_move_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean deleteEmptyDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children != null && children.length > 0) {
            return false;
        }
        return directory.delete();
    }

    private static boolean isDirectoryEmpty(File directory) {
        File[] children = directory.listFiles();
        return children == null || children.length == 0;
    }

    private void navigateUp() {
        if (currentDir == null) {
            finish();
            return;
        }
        File parent = currentDir.getParentFile();
        if (parent != null && isAccessible(parent) && isUnderAllowedRoot(parent)) {
            openDirectory(parent);
        } else {
            finish();
        }
    }

    private boolean canNavigateUp(File directory) {
        File parent = directory.getParentFile();
        return parent != null && isAccessible(parent) && isUnderAllowedRoot(parent);
    }

    private boolean canWriteInCurrentDir() {
        return currentDir != null && canModify(currentDir);
    }

    private boolean canModify(File file) {
        if (file == null || !isUnderAllowedRoot(file)) {
            return false;
        }
        File parent = file.isDirectory() ? file : file.getParentFile();
        return parent != null && parent.canWrite();
    }

    private static boolean isAccessible(File file) {
        try {
            return file.exists() && file.canRead();
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static boolean isUnderAllowedRoot(File directory) {
        List<File> roots = allowedRoots();
        for (File root : roots) {
            if (isPathAtOrUnder(directory, root)) {
                return true;
            }
        }
        return false;
    }

    static List<File> allowedRoots() {
        ArrayList<File> roots = new ArrayList<>();
        File external = Environment.getExternalStorageDirectory();
        if (external != null) {
            roots.add(external);
        }
        addPublicRoot(roots, Environment.DIRECTORY_DCIM);
        addPublicRoot(roots, Environment.DIRECTORY_DOWNLOADS);
        addPublicRoot(roots, Environment.DIRECTORY_DOCUMENTS);
        addPublicRoot(roots, Environment.DIRECTORY_PICTURES);
        addPublicRoot(roots, Environment.DIRECTORY_MUSIC);
        addPublicRoot(roots, Environment.DIRECTORY_MOVIES);
        roots.add(RecoveryOutputPaths.primaryDataRecoveryDir());
        return roots;
    }

    private static void addPublicRoot(List<File> roots, String type) {
        File dir = Environment.getExternalStoragePublicDirectory(type);
        if (dir != null) {
            roots.add(dir);
        }
    }

    private static final class BreadcrumbSegment {
        final String label;
        final File directory;

        BreadcrumbSegment(String label, File directory) {
            this.label = label;
            this.directory = directory;
        }
    }

    public static final class FileEntry {
        public final File file;
        public final String name;
        public final boolean directory;
        public final long size;
        public final long lastModified;

        public FileEntry(File file, String name, boolean directory, long size, long lastModified) {
            this.file = file;
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.lastModified = lastModified;
        }

        public static FileEntry from(File file) {
            return new FileEntry(
                    file,
                    file.getName(),
                    file.isDirectory(),
                    file.isFile() ? file.length() : 0L,
                    file.lastModified()
            );
        }

        public String formattedSize() {
            if (directory) {
                return "";
            }
            return formatSize(size);
        }

        public String formattedMeta() {
            if (directory) {
                return "";
            }
            return formattedSize();
        }

        private static String formatSize(long bytes) {
            if (bytes < 1024L) {
                return bytes + " B";
            }
            double value = bytes;
            String[] units = {"KB", "MB", "GB", "TB"};
            int index = -1;
            do {
                value = value / 1024.0d;
                index++;
            } while (value >= 1024.0d && index < units.length - 1);
            return String.format(Locale.US, "%.1f %s", value, units[index]);
        }
    }
}
