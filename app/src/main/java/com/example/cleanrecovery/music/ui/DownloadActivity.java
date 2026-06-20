package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.DownloadedSong;
import com.example.cleanrecovery.music.data.SongInfo;
import com.example.cleanrecovery.music.download.DownloadManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class DownloadActivity extends Activity implements DownloadManager.ProgressListener {

    private MusicApp app;
    private SongInfo song;

    private TextView songTitle, songArtist, songStatus;
    private TextView downloadPathText;
    private View downloadActionButtons;
    private Button openFileButton;
    private Button viewFolderButton;
    private RadioGroup qualityGroup;
    private ProgressBar progressBar;
    private TextView progressText;
    private Button startButton;
    private Button cancelButton;
    private TextView storageUsed, storageFree;
    private TextView downloadedEmpty;
    private RecyclerView downloadedList;
    private DownloadedAdapter downloadedAdapter;
    private final List<DownloadedSong> downloadedItems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_download);
        app = MusicApp.init(this);

        song = songFromIntent();

        songTitle = findViewById(R.id.download_song_title);
        songArtist = findViewById(R.id.download_song_artist);
        songStatus = findViewById(R.id.download_song_status);
        downloadPathText = findViewById(R.id.download_path_text);
        downloadActionButtons = findViewById(R.id.download_action_buttons);
        openFileButton = findViewById(R.id.download_open_file_button);
        viewFolderButton = findViewById(R.id.download_view_folder_button);
        qualityGroup = findViewById(R.id.download_quality_group);
        progressBar = findViewById(R.id.download_progress_bar);
        progressText = findViewById(R.id.download_progress_text);
        startButton = findViewById(R.id.download_start_button);
        cancelButton = findViewById(R.id.download_cancel_button);
        storageUsed = findViewById(R.id.download_storage_used);
        storageFree = findViewById(R.id.download_storage_free);
        downloadedEmpty = findViewById(R.id.downloaded_empty);
        downloadedList = findViewById(R.id.downloaded_list);

        downloadedList.setLayoutManager(new LinearLayoutManager(this));
        downloadedAdapter = new DownloadedAdapter(downloadedItems, this::onDeleteDownloaded);
        downloadedList.setAdapter(downloadedAdapter);

        findViewById(R.id.download_back_button).setOnClickListener(v -> finish());
        startButton.setOnClickListener(v -> startDownload());
        cancelButton.setOnClickListener(v -> cancelDownload());
        findViewById(R.id.download_clear_button).setOnClickListener(v -> confirmClearAll());
        openFileButton.setOnClickListener(v -> openDownloadedFile());
        viewFolderButton.setOnClickListener(v -> openDownloadFolder());

        bindSongInfo();
        refreshDownloaded();
        refreshStorage();
        showDownloadPath();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshDownloaded();
        refreshStorage();
        showDownloadPath();
        // If the song is already downloaded, reflect that in the status.
        if (song != null && app.downloads.exists(song.hash)) {
            DownloadedSong d = app.downloads.get(song.hash);
            if (d != null) {
                songStatus.setText(getString(R.string.music_download_already, d.qualityLabel(), d.sizeFormatted()));
                songStatus.setVisibility(View.VISIBLE);
                startButton.setText(R.string.music_download_redownload);
                showActionButtons(d.localPath);
            }
        }
    }

    private SongInfo songFromIntent() {
        SongInfo s = new SongInfo();
        s.hash = getIntent().getStringExtra("song_hash");
        s.title = getIntent().getStringExtra("song_title");
        s.artist = getIntent().getStringExtra("song_artist");
        s.album = getIntent().getStringExtra("song_album");
        s.albumId = getIntent().getStringExtra("song_album_id");
        s.duration = getIntent().getIntExtra("song_duration", 0);
        s.vipRequired = getIntent().getBooleanExtra("song_vip", false);
        return s;
    }

    private void bindSongInfo() {
        if (song == null || song.hash == null) {
            // No song context — launched from home as a download manager.
            findViewById(R.id.download_song_card).setVisibility(View.GONE);
            // Hide the quality section title (the TextView above the RadioGroup) and the RadioGroup.
            View qualityGroup = findViewById(R.id.download_quality_group);
            ViewGroup parent = (ViewGroup) qualityGroup.getParent();
            int idx = parent.indexOfChild(qualityGroup);
            if (idx > 0) parent.getChildAt(idx - 1).setVisibility(View.GONE);
            qualityGroup.setVisibility(View.GONE);
            // Hide the start/cancel buttons and progress UI.
            startButton.setVisibility(View.GONE);
            cancelButton.setVisibility(View.GONE);
            progressBar.setVisibility(View.GONE);
            progressText.setVisibility(View.GONE);
            return;
        }
        songTitle.setText(song.title);
        songArtist.setText(song.artist + (song.vipRequired ? " · VIP" : ""));
    }

    private String selectedQuality() {
        int id = qualityGroup.getCheckedRadioButtonId();
        if (id == R.id.download_quality_high) return DownloadManager.QUALITY_HIGH;
        if (id == R.id.download_quality_lossless) return DownloadManager.QUALITY_LOSSLESS;
        return DownloadManager.QUALITY_STANDARD;
    }

    private void startDownload() {
        if (song == null || song.hash == null) {
            Toast.makeText(this, R.string.music_download_no_song, Toast.LENGTH_SHORT).show();
            return;
        }
        if (app.downloads.isDownloading(song.hash)) {
            Toast.makeText(this, R.string.music_download_in_progress, Toast.LENGTH_SHORT).show();
            return;
        }
        String q = selectedQuality();
        // Storage pre-check.
        long free = app.downloads.availableBytes();
        if (free < 5L * 1024 * 1024) {
            Toast.makeText(this, R.string.music_download_no_space, Toast.LENGTH_LONG).show();
            return;
        }
        progressBar.setVisibility(View.VISIBLE);
        progressText.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        progressText.setText(R.string.music_download_starting);
        startButton.setVisibility(View.GONE);
        cancelButton.setVisibility(View.VISIBLE);
        songStatus.setVisibility(View.GONE);
        app.downloads.enqueue(song, q, this);
    }

    private void cancelDownload() {
        if (song == null) return;
        app.downloads.cancel(song.hash);
    }

    private void resetButtons() {
        startButton.setVisibility(View.VISIBLE);
        cancelButton.setVisibility(View.GONE);
    }

    // ---- DownloadManager.ProgressListener ----

    @Override
    public void onProgress(SongInfo s, DownloadManager.State state,
                           long downloadedBytes, long totalBytes, String message) {
        if (song == null || s == null || !song.hash.equals(s.hash)) return;
        switch (state) {
            case RUNNING:
                if (totalBytes > 0) {
                    int pct = (int) (downloadedBytes * 100 / totalBytes);
                    progressBar.setProgress(pct);
                    progressText.setText(getString(R.string.music_download_progress_pct,
                            pct, formatBytes(downloadedBytes), formatBytes(totalBytes)));
                } else {
                    progressBar.setIndeterminate(true);
                    progressText.setText(getString(R.string.music_download_progress_unknown,
                            formatBytes(downloadedBytes)));
                }
                break;
            case COMPLETED:
                progressBar.setIndeterminate(false);
                progressBar.setProgress(100);
                progressText.setText(R.string.music_download_done);
                songStatus.setText(R.string.music_download_done);
                songStatus.setTextColor(getColor(R.color.status_success));
                songStatus.setVisibility(View.VISIBLE);
                resetButtons();
                refreshDownloaded();
                refreshStorage();
                showDownloadPath();
                // Show open-file / view-folder buttons for the just-downloaded song
                DownloadedSong completed = song != null ? app.downloads.get(song.hash) : null;
                showActionButtons(completed != null ? completed.localPath : null);
                Toast.makeText(this, R.string.music_download_done, Toast.LENGTH_SHORT).show();
                break;
            case FAILED:
                progressBar.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
                String msg = message != null ? message : getString(R.string.music_download_failed);
                songStatus.setText(msg);
                songStatus.setTextColor(getColor(R.color.status_warning));
                songStatus.setVisibility(View.VISIBLE);
                resetButtons();
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                break;
            case CANCELLED:
                progressBar.setVisibility(View.GONE);
                progressText.setVisibility(View.GONE);
                songStatus.setText(R.string.music_download_cancelled);
                songStatus.setTextColor(getColor(R.color.text_muted));
                songStatus.setVisibility(View.VISIBLE);
                resetButtons();
                break;
            default:
                break;
        }
    }

    // ---- Downloaded list ----

    private void refreshDownloaded() {
        downloadedItems.clear();
        downloadedItems.addAll(app.downloads.all());
        downloadedAdapter.notifyDataSetChanged();
        boolean none = downloadedItems.isEmpty();
        downloadedEmpty.setVisibility(none ? View.VISIBLE : View.GONE);
        downloadedList.setVisibility(none ? View.GONE : View.VISIBLE);
    }

    private void refreshStorage() {
        storageUsed.setText(getString(R.string.music_download_storage_used,
                formatBytes(app.downloads.usedBytes())));
        storageFree.setText(getString(R.string.music_download_storage_free,
                formatBytes(app.downloads.availableBytes())));
    }

    /** 显示下载目录路径，让用户知道文件保存在哪里。 */
    private void showDownloadPath() {
        File dir = app.downloads.downloadsDir();
        downloadPathText.setText(getString(R.string.music_download_path, dir.getAbsolutePath()));
        downloadPathText.setVisibility(View.VISIBLE);
    }

    /** 下载完成后显示"打开文件"和"查看文件夹"按钮。 */
    private void showActionButtons(String localPath) {
        if (localPath == null || !new File(localPath).exists()) {
            downloadActionButtons.setVisibility(View.GONE);
            return;
        }
        downloadActionButtons.setVisibility(View.VISIBLE);
    }

    /** 使用外部应用打开已下载的音频文件。 */
    private void openDownloadedFile() {
        if (song == null) return;
        DownloadedSong d = app.downloads.get(song.hash);
        if (d == null || d.localPath == null) {
            Toast.makeText(this, R.string.music_download_open_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        File file = new File(d.localPath);
        if (!file.exists()) {
            Toast.makeText(this, R.string.music_download_open_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String mime = d.localPath.toLowerCase().endsWith(".flac") ? "audio/flac" : "audio/*";
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, R.string.music_download_open_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 在系统文件管理器中打开下载目录。 */
    private void openDownloadFolder() {
        File dir = app.downloads.downloadsDir();
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", dir);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "resource/folder");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception e) {
            // Fallback: try opening the folder with ACTION_GET_CONTENT
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.setDataAndType(Uri.parse(dir.getAbsolutePath()), "audio/*");
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(fallback);
            } catch (Exception e2) {
                Toast.makeText(this, R.string.music_download_folder_failed, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void onDeleteDownloaded(DownloadedSong d) {
        if (d == null) return;
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_download_delete)
                .setMessage(getString(R.string.music_download_delete_confirm, d.title))
                .setPositiveButton(R.string.music_delete, (dlg, w) -> {
                    // Delete the file then the DB record.
                    if (d.localPath != null) {
                        try { new File(d.localPath).delete(); } catch (Exception ignored) {}
                    }
                    app.downloads.delete(d.hash);
                    refreshDownloaded();
                    refreshStorage();
                    Toast.makeText(this, R.string.music_download_deleted, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmClearAll() {
        if (downloadedItems.isEmpty()) {
            Toast.makeText(this, R.string.music_download_empty, Toast.LENGTH_SHORT).show();
            return;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.music_download_clear_all)
                .setMessage(R.string.music_download_clear_all_confirm)
                .setPositiveButton(R.string.music_delete, (dlg, w) -> {
                    for (DownloadedSong d : new ArrayList<>(downloadedItems)) {
                        if (d.localPath != null) {
                            try { new File(d.localPath).delete(); } catch (Exception ignored) {}
                        }
                        app.downloads.delete(d.hash);
                    }
                    refreshDownloaded();
                    refreshStorage();
                    Toast.makeText(this, R.string.music_download_cleared, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    // ---- Adapter for the downloaded songs list ----

    private static class DownloadedAdapter extends RecyclerView.Adapter<DownloadedAdapter.VH> {
        private final List<DownloadedSong> items;
        private final OnDelete listener;
        interface OnDelete { void onDelete(DownloadedSong d); }

        DownloadedAdapter(List<DownloadedSong> items, OnDelete listener) {
            this.items = items;
            this.listener = listener;
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_downloaded_song, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            DownloadedSong d = items.get(position);
            h.title.setText(d.title);
            h.subtitle.setText(d.artist + " · " + d.qualityLabel() + " · " + d.sizeFormatted());
            h.icon.setText(d.title.isEmpty() ? "♪"
                    : String.valueOf(d.title.charAt(0)).toUpperCase());
            h.delete.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p != RecyclerView.NO_POSITION) listener.onDelete(items.get(p));
            });
        }

        @Override
        public int getItemCount() { return items.size(); }

        static class VH extends RecyclerView.ViewHolder {
            final TextView title, subtitle, icon;
            final ImageButton delete;
            VH(View v) {
                super(v);
                title = v.findViewById(R.id.downloaded_row_title);
                subtitle = v.findViewById(R.id.downloaded_row_subtitle);
                icon = v.findViewById(R.id.downloaded_row_icon);
                delete = v.findViewById(R.id.downloaded_row_delete);
            }
        }
    }
}
