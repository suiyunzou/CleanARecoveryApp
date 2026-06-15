package com.example.cleanrecovery;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
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
    private View newFolderButton;
    private View sortButton;
    private View toolbar;
    private View multiSelectBar;
    private ImageButton selectButton;
    private TextView selectedCountLabel;
    private TextView titleLabel;
    private LinearLayout breadcrumbContainer;
    private HorizontalScrollView breadcrumbScroll;
    private EditText searchInput;

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
        newFolderButton = findViewById(R.id.file_browser_new_folder);
        breadcrumbContainer = findViewById(R.id.file_browser_breadcrumb);
        breadcrumbScroll = findViewById(R.id.file_browser_breadcrumb_scroll);
        searchInput = findViewById(R.id.file_browser_search);
        listView = findViewById(R.id.file_browser_list);

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

        selectButton = findViewById(R.id.file_browser_select);
        sortButton = findViewById(R.id.file_browser_sort);
        findViewById(R.id.file_browser_back).setOnClickListener(v -> finish());
        findViewById(R.id.file_browser_up).setOnClickListener(v -> navigateUp());
        selectButton.setOnClickListener(v -> toggleMultiSelectMode());
        sortButton.setOnClickListener(this::showToolbarMenu);
        newFolderButton = findViewById(R.id.file_browser_new_folder);
        newFolderButton.setOnClickListener(v -> showCreateFolderDialog());
        findViewById(R.id.file_browser_batch_delete).setOnClickListener(v -> confirmBatchDelete());
        findViewById(R.id.file_browser_cancel_select).setOnClickListener(v -> exitMultiSelectMode());

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterQuery = s == null ? "" : s.toString().trim().toLowerCase(Locale.US);
                applyFilterAndSort();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        File initial = resolveInitialDirectory(getIntent().getStringExtra(EXTRA_INITIAL_PATH));
        openDirectory(initial);
    }

    @Override
    public void onBackPressed() {
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
        currentDir = directory;
        updateBreadcrumbs();
        newFolderButton.setVisibility(canWriteInCurrentDir() ? View.VISIBLE : View.GONE);
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
            TextView label = new TextView(this);
            label.setText(segment.label);
            label.setTextColor(getColor(i == segments.size() - 1 ? R.color.brand_primary_dark : R.color.text_muted));
            label.setTextSize(getResources().getDimension(R.dimen.text_caption) / getResources().getDisplayMetrics().scaledDensity);
            label.setPadding(0, 0, 0, 0);
            label.setClickable(i < segments.size() - 1);
            label.setFocusable(i < segments.size() - 1);
            if (i < segments.size() - 1) {
                final File target = segment.directory;
                label.setOnClickListener(v -> openDirectory(target));
            }
            breadcrumbContainer.addView(label);

            if (i < segments.size() - 1) {
                TextView separator = new TextView(this);
                separator.setText(" / ");
                separator.setTextColor(getColor(R.color.text_muted));
                separator.setTextSize(getResources().getDimension(R.dimen.text_caption) / getResources().getDisplayMetrics().scaledDensity);
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
        File root = nearestAllowedRoot(directory);
        File cursor = directory;
        while (cursor != null) {
            segments.add(new BreadcrumbSegment(displayNameFor(cursor, root), cursor));
            if (root != null && cursor.getAbsolutePath().equals(root.getAbsolutePath())) {
                break;
            }
            File parent = cursor.getParentFile();
            if (parent == null || !isUnderAllowedRoot(parent)) {
                break;
            }
            cursor = parent;
        }
        java.util.Collections.reverse(segments);
        return segments;
    }

    private static File nearestAllowedRoot(File directory) {
        String path = directory.getAbsolutePath();
        File best = null;
        int bestLength = -1;
        for (File root : allowedRoots()) {
            String rootPath = root.getAbsolutePath();
            if (path.startsWith(rootPath) && rootPath.length() > bestLength) {
                best = root;
                bestLength = rootPath.length();
            }
        }
        return best;
    }

    private String displayNameFor(File directory, File root) {
        if (root != null && directory.getAbsolutePath().equals(root.getAbsolutePath())) {
            String name = root.getName();
            if (name == null || name.isEmpty()) {
                return root.getAbsolutePath();
            }
            return name;
        }
        return directory.getName();
    }

    private void showToolbarMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor, Gravity.END);
        menu.getMenuInflater().inflate(R.menu.menu_file_browser, menu.getMenu());
        menu.getMenu().findItem(R.id.menu_show_hidden).setChecked(showHiddenFiles);
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int id = item.getItemId();
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
        newFolderButton.setVisibility(selecting || !canWriteInCurrentDir() ? View.GONE : View.VISIBLE);
        selectButton.setVisibility(selecting || !canWriteInCurrentDir() ? View.GONE : View.VISIBLE);
        if (selecting) {
            titleLabel.setText(getString(R.string.file_browser_selected_count, adapter.getSelectedCount()));
            selectedCountLabel.setText(getString(R.string.file_browser_selected_count, adapter.getSelectedCount()));
            findViewById(R.id.file_browser_batch_delete).setEnabled(adapter.getSelectedCount() > 0);
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
        int deleted = 0;
        for (String path : selectedPaths) {
            File file = new File(path);
            if (!canModify(file)) {
                continue;
            }
            if (file.isDirectory()) {
                if (isDirectoryEmpty(file) && deleteEmptyDirectory(file)) {
                    deleted++;
                }
            } else if (file.delete()) {
                deleted++;
            }
        }
        exitMultiSelectMode();
        reloadEntries();
        if (deleted == 0) {
            Toast.makeText(this, R.string.file_browser_delete_failed, Toast.LENGTH_SHORT).show();
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
        CharSequence[] actions = canModify(entry.file)
                ? new CharSequence[]{
                getString(R.string.file_browser_rename),
                getString(R.string.file_browser_delete),
                getString(R.string.file_browser_details)
        }
                : new CharSequence[]{getString(R.string.file_browser_details)};
        new AlertDialog.Builder(this)
                .setTitle(entry.name)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (canModify(entry.file)) {
                            if (which == 0) {
                                showRenameDialog(entry);
                            } else if (which == 1) {
                                confirmDelete(entry);
                            } else if (which == 2) {
                                showEntryDetails(entry);
                            }
                        } else if (which == 0) {
                            showEntryDetails(entry);
                        }
                    }
                })
                .show();
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
        boolean deleted = entry.directory ? deleteEmptyDirectory(entry.file) : entry.file.delete();
        if (deleted) {
            reloadEntries();
        } else {
            Toast.makeText(this, R.string.file_browser_delete_failed, Toast.LENGTH_SHORT).show();
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
        String path = directory.getAbsolutePath();
        for (File root : roots) {
            if (path.startsWith(root.getAbsolutePath())) {
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

    static final class FileEntry {
        final File file;
        final String name;
        final boolean directory;
        final long size;
        final long lastModified;

        private FileEntry(File file, String name, boolean directory, long size, long lastModified) {
            this.file = file;
            this.name = name;
            this.directory = directory;
            this.size = size;
            this.lastModified = lastModified;
        }

        static FileEntry from(File file) {
            return new FileEntry(
                    file,
                    file.getName(),
                    file.isDirectory(),
                    file.isFile() ? file.length() : 0L,
                    file.lastModified()
            );
        }

        String formattedSize() {
            if (directory) {
                return "";
            }
            return formatSize(size);
        }

        String formattedMeta() {
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
