package com.example.cleanrecovery;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class PreviewActivity extends Activity {
    public static final String EXTRA_PATH = "com.example.cleanrecovery.extra.PATH";
    public static final String EXTRA_NAME = "com.example.cleanrecovery.extra.NAME";
    public static final String EXTRA_TYPE = "com.example.cleanrecovery.extra.TYPE";
    public static final String EXTRA_SUSPECTED_DELETED = "com.example.cleanrecovery.extra.SUSPECTED_DELETED";

    private static final ExecutorService RECOVER_EXECUTOR = Executors.newSingleThreadExecutor();

    private FrameLayout contentHost;
    private TextView previewIndex;
    private TextView previewFileName;
    private TextView previewMeta;
    private Button previewPrevButton;
    private Button previewNextButton;
    private Button previewRecoverButton;

    private MediaPlayer mediaPlayer;
    private Surface mediaSurface;
    private Button playbackButton;
    private TextView playbackStateView;
    private boolean mediaPrepared;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_preview);
        bindChrome();
        renderCurrentItem();
    }

    @Override
    protected void onPause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updatePlaybackState(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        releasePlayer();
        super.onDestroy();
    }

    private void bindChrome() {
        contentHost = findViewById(R.id.preview_content_host);
        previewIndex = findViewById(R.id.preview_index);
        previewFileName = findViewById(R.id.preview_file_name);
        previewMeta = findViewById(R.id.preview_meta);
        previewPrevButton = findViewById(R.id.preview_prev_button);
        previewNextButton = findViewById(R.id.preview_next_button);
        previewRecoverButton = findViewById(R.id.preview_recover_button);
        ImageButton backButton = findViewById(R.id.preview_back_button);

        previewPrevButton.setOnClickListener(v -> {
            if (PreviewSession.moveBy(-1)) {
                renderCurrentItem();
            }
        });
        previewNextButton.setOnClickListener(v -> {
            if (PreviewSession.moveBy(1)) {
                renderCurrentItem();
            }
        });
        backButton.setOnClickListener(v -> finish());
        previewRecoverButton.setOnClickListener(v -> recoverCurrentItem());
    }

    private void renderCurrentItem() {
        releasePlayer();
        contentHost.removeAllViews();
        playbackButton = null;
        playbackStateView = null;

        RecoveryItem item = PreviewSession.currentItem();
        if (item == null) {
            item = itemFromIntent();
        }
        if (item == null) {
            finish();
            return;
        }

        int total = PreviewSession.totalCount();
        if (total > 0) {
            previewIndex.setText(getString(R.string.preview_index_format, PreviewSession.currentIndex() + 1, total));
            previewPrevButton.setEnabled(PreviewSession.currentIndex() > 0);
            previewNextButton.setEnabled(PreviewSession.currentIndex() < total - 1);
        } else {
            previewIndex.setText("1 / 1");
            previewPrevButton.setEnabled(false);
            previewNextButton.setEnabled(false);
        }

        File file = item.asFile();
        previewFileName.setText(item.name);
        previewMeta.setText(buildMeta(file, item.suspectedDeleted));

        if (!file.exists() || !file.canRead()) {
            addMessage(getString(R.string.preview_missing));
            return;
        }

        if (item.type == RecoveryType.IMAGE) {
            addImagePreview(file);
        } else if (item.type == RecoveryType.VIDEO) {
            addVideoPreview(file);
        } else if (item.type == RecoveryType.AUDIO) {
            addAudioPreview(file);
        } else {
            addDocumentPreview(file);
        }
    }

    private RecoveryItem itemFromIntent() {
        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        String typeName = getIntent().getStringExtra(EXTRA_TYPE);
        boolean suspectedDeleted = getIntent().getBooleanExtra(EXTRA_SUSPECTED_DELETED, false);
        if (path == null) {
            return null;
        }
        if (name == null || name.isEmpty()) {
            name = new File(path).getName();
        }
        RecoveryType type = parseType(typeName);
        File file = new File(path);
        return new RecoveryItem(type, name, path, file.length(), file.lastModified(), 0, 0, suspectedDeleted);
    }

    private void recoverCurrentItem() {
        final RecoveryItem item = PreviewSession.currentItem() != null ? PreviewSession.currentItem() : itemFromIntent();
        if (item == null) {
            return;
        }
        previewRecoverButton.setEnabled(false);
        RECOVER_EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                int success = 0;
                int failed = 0;
                File output = null;
                try {
                    output = RecoveryCopier.copyToRecoveryDirectory(PreviewActivity.this, item);
                    success = 1;
                } catch (Exception exception) {
                    failed = 1;
                }
                final int finalSuccess = success;
                final int finalFailed = failed;
                final File finalOutput = output;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        previewRecoverButton.setEnabled(true);
                        Intent intent = new Intent(PreviewActivity.this, RecoverCompleteActivity.class);
                        intent.putExtra(RecoverCompleteActivity.EXTRA_SUCCESS, finalSuccess);
                        intent.putExtra(RecoverCompleteActivity.EXTRA_FAILED, finalFailed);
                        intent.putExtra(RecoverCompleteActivity.EXTRA_OUTPUT_PATH, finalOutput == null
                                ? RecoveryOutputPaths.primaryDisplayPath()
                                : finalOutput.getParent());
                        startActivity(intent);
                    }
                });
            }
        });
    }

    private String buildMeta(File file, boolean suspectedDeleted) {
        StringBuilder builder = new StringBuilder();
        builder.append(getString(suspectedDeleted ? R.string.status_deleted : R.string.status_existing));
        if (file != null) {
            builder.append(" | ").append(formatSize(file.length()));
            if (file.lastModified() > 0) {
                builder.append(" | ").append(DateFormat.getDateTimeInstance().format(new Date(file.lastModified())));
            }
            builder.append("\n").append(file.getAbsolutePath());
        }
        return builder.toString();
    }

    private void addImagePreview(File file) {
        ImageView imageView = new ImageView(this);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageURI(Uri.fromFile(file));
        contentHost.addView(imageView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
    }

    private void addVideoPreview(final File file) {
        FrameLayout previewFrame = new FrameLayout(this);
        final ImageView posterView = new ImageView(this);
        posterView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap poster = readVideoFrame(file);
        if (poster != null) {
            posterView.setImageBitmap(poster);
        }
        previewFrame.addView(posterView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        final TextureView textureView = new TextureView(this);
        textureView.setAlpha(0f);
        previewFrame.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        contentHost.addView(previewFrame, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        addPlaybackControlsToHost();

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                mediaSurface = new Surface(surfaceTexture);
                prepareVideoPlayer(file, textureView, posterView);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                releasePlayer();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            }
        });
    }

    private void addAudioPreview(File file) {
        addMessage(getString(R.string.preview_audio_ready));
        addPlaybackControlsToHost();
        prepareAudioPlayer(file);
    }

    private void addPlaybackControlsToHost() {
        View controls = getLayoutInflater().inflate(R.layout.partial_preview_playback, contentHost, false);
        playbackStateView = controls.findViewById(R.id.playback_state);
        playbackButton = controls.findViewById(R.id.playback_button);
        playbackButton.setEnabled(false);
        playbackButton.setOnClickListener(v -> togglePlayback());
        contentHost.addView(controls);
    }

    private void prepareVideoPlayer(File file, final TextureView textureView, final ImageView posterView) {
        releasePlayerOnly();
        mediaPrepared = false;
        if (playbackButton != null) {
            playbackButton.setEnabled(false);
        }
        if (playbackStateView != null) {
            playbackStateView.setText(R.string.preview_loading);
        }

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setSurface(mediaSurface);
        } catch (Exception exception) {
            showPlaybackError();
            return;
        }
        bindPlayerCallbacks(new Runnable() {
            @Override
            public void run() {
                textureView.setAlpha(1f);
                posterView.setVisibility(View.GONE);
            }
        });
        try {
            mediaPlayer.prepareAsync();
        } catch (IllegalStateException exception) {
            showPlaybackError();
        }
    }

    private void prepareAudioPlayer(File file) {
        releasePlayerOnly();
        mediaPrepared = false;
        if (playbackButton != null) {
            playbackButton.setEnabled(false);
        }
        if (playbackStateView != null) {
            playbackStateView.setText(R.string.preview_loading);
        }
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(file.getAbsolutePath());
        } catch (Exception exception) {
            showPlaybackError();
            return;
        }
        bindPlayerCallbacks(null);
        try {
            mediaPlayer.prepareAsync();
        } catch (IllegalStateException exception) {
            showPlaybackError();
        }
    }

    private void bindPlayerCallbacks(final Runnable onPreparedExtra) {
        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                mediaPrepared = true;
                mp.setLooping(false);
                if (playbackButton != null) {
                    playbackButton.setEnabled(true);
                }
                if (onPreparedExtra != null) {
                    onPreparedExtra.run();
                }
                mp.start();
                updatePlaybackState(true);
            }
        });
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                updatePlaybackState(false);
            }
        });
        mediaPlayer.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
                showPlaybackError();
                return true;
            }
        });
    }

    private void togglePlayback() {
        if (!mediaPrepared || mediaPlayer == null) {
            return;
        }
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updatePlaybackState(false);
        } else {
            mediaPlayer.start();
            updatePlaybackState(true);
        }
    }

    private void updatePlaybackState(boolean playing) {
        if (playbackButton != null) {
            playbackButton.setText(playing ? R.string.preview_pause : R.string.preview_play);
        }
        if (playbackStateView != null) {
            playbackStateView.setText(playing ? R.string.preview_playing : R.string.preview_paused);
        }
    }

    private void showPlaybackError() {
        mediaPrepared = false;
        if (playbackButton != null) {
            playbackButton.setEnabled(false);
        }
        if (playbackStateView != null) {
            playbackStateView.setText(R.string.preview_playback_error);
        }
        Toast.makeText(this, R.string.preview_playback_error, Toast.LENGTH_SHORT).show();
    }

    private Bitmap readVideoFrame(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
        } catch (RuntimeException exception) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void addDocumentPreview(File file) {
        ScrollView scrollView = new ScrollView(this);
        TextView message = new TextView(this);
        message.setText(R.string.preview_document_message);
        message.setTextColor(getResources().getColor(R.color.text_primary, getTheme()));
        message.setPadding(0, 0, 0, dp(8));
        scrollView.addView(message);
        contentHost.addView(scrollView);
    }

    private void addMessage(String message) {
        TextView textView = new TextView(this);
        textView.setText(message);
        textView.setTextColor(getResources().getColor(R.color.status_warning, getTheme()));
        textView.setPadding(dp(16), dp(16), dp(16), dp(16));
        contentHost.addView(textView);
    }

    private RecoveryType parseType(String typeName) {
        if (typeName == null) {
            return RecoveryType.DOCUMENT;
        }
        try {
            return RecoveryType.valueOf(typeName);
        } catch (IllegalArgumentException exception) {
            return RecoveryType.DOCUMENT;
        }
    }

    private void releasePlayer() {
        releasePlayerOnly();
        if (mediaSurface != null) {
            mediaSurface.release();
            mediaSurface = null;
        }
    }

    private void releasePlayerOnly() {
        mediaPrepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (IllegalStateException ignored) {
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
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
