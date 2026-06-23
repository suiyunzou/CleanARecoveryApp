package com.example.cleanrecovery.ui.activity;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.recovery.PreviewSession;
import com.example.cleanrecovery.recovery.RecoveryCopier;
import com.example.cleanrecovery.recovery.RecoveryItem;
import com.example.cleanrecovery.recovery.RecoveryOutputPaths;
import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
    private static final int OVERLAY_HIDE_DELAY_MS = 3000;
    private static final int PROGRESS_UPDATE_MS = 500;
    private static final int SEEK_BAR_MAX = 1000;
    private static final int GESTURE_ZONE_EDGE_PERCENT = 28;
    private static final int GESTURE_ZONE_LEFT = 1;
    private static final int GESTURE_ZONE_CENTER = 2;
    private static final int GESTURE_ZONE_RIGHT = 3;
    private static final float FAST_PLAYBACK_SPEED = 2.0f;
    private static final String PREVIEW_CACHE_DIR = "preview_sources";

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable hideOverlayRunnable = new Runnable() {
        @Override
        public void run() {
            hideOverlay();
        }
    };
    private final Runnable progressUpdater = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                uiHandler.postDelayed(this, PROGRESS_UPDATE_MS);
            }
        }
    };
    private final Runnable hideGestureFeedbackRunnable = new Runnable() {
        @Override
        public void run() {
            if (gestureFeedbackView != null) {
                gestureFeedbackView.setVisibility(View.GONE);
            }
        }
    };

    private FrameLayout contentHost;
    private View overlayChrome;
    private View playbackControls;
    private View previewMetaCard;
    private TextView previewIndex;
    private TextView previewFileName;
    private TextView previewMeta;
    private TextView recoverabilityView;
    private TextView gestureFeedbackView;
    private Button previewRecoverButton;
    private ImageButton detailsButton;
    private AudioManager audioManager;

    private MediaPlayer mediaPlayer;
    private Surface mediaSurface;
    private ImageButton playbackButton;
    private ImageButton playbackFullscreenButton;
    private SeekBar playbackSeekBar;
    private boolean mediaPrepared;
    private boolean currentItemIsMedia;
    private boolean immersivePreview;
    private boolean userSeeking;
    private boolean speedBoostActive;
    private boolean videoFullscreen;
    private boolean playbackChromeVisible;
    private int originalRequestedOrientation;
    private RecoveryItem currentItem;
    private File currentFile;
    private boolean singleItemPreview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_preview);
        bindChrome();
        renderCurrentItem();
    }

    @Override
    public void onBackPressed() {
        if (videoFullscreen) {
            setVideoFullscreen(false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onPause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            stopFastPlayback();
            mediaPlayer.pause();
            updatePlaybackState(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        uiHandler.removeCallbacksAndMessages(null);
        if (videoFullscreen) {
            setRequestedOrientation(originalRequestedOrientation);
        }
        releasePlayer();
        super.onDestroy();
    }

    private void bindChrome() {
        contentHost = findViewById(R.id.preview_content_host);
        overlayChrome = findViewById(R.id.preview_overlay);
        previewMetaCard = findViewById(R.id.preview_meta_card);
        previewIndex = findViewById(R.id.preview_index);
        previewFileName = findViewById(R.id.preview_file_name);
        previewMeta = findViewById(R.id.preview_meta);
        recoverabilityView = findViewById(R.id.preview_recoverability);
        gestureFeedbackView = findViewById(R.id.preview_gesture_feedback);
        previewRecoverButton = findViewById(R.id.preview_recover_button);
        detailsButton = findViewById(R.id.preview_details_button);
        playbackControls = findViewById(R.id.playback_controls);
        playbackButton = findViewById(R.id.playback_button);
        playbackFullscreenButton = findViewById(R.id.playback_fullscreen_button);
        playbackSeekBar = findViewById(R.id.playback_seek);
        ImageButton backButton = findViewById(R.id.preview_back_button);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        originalRequestedOrientation = getRequestedOrientation();

        backButton.setOnClickListener(v -> finish());
        previewRecoverButton.setOnClickListener(v -> recoverCurrentItem());
        detailsButton.setOnClickListener(v -> {
            showOverlay(false);
            showFileDetails();
        });
        playbackButton.setOnClickListener(v -> {
            togglePlayback();
            showOverlay(mediaPlayer != null && mediaPlayer.isPlaying());
        });
        playbackFullscreenButton.setOnClickListener(v -> toggleVideoFullscreen());
        playbackSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                userSeeking = true;
                showOverlay(false);
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                if (mediaPrepared && mediaPlayer != null) {
                    int duration = mediaPlayer.getDuration();
                    if (duration > 0) {
                        mediaPlayer.seekTo((int) ((long) duration * seekBar.getProgress() / SEEK_BAR_MAX));
                    }
                }
                userSeeking = false;
                updateProgress();
                scheduleOverlayHide();
            }
        });
    }

    private void renderCurrentItem() {
        uiHandler.removeCallbacks(hideOverlayRunnable);
        uiHandler.removeCallbacks(hideGestureFeedbackRunnable);
        stopProgressUpdates();
        releasePlayer();
        contentHost.removeAllViews();
        contentHost.setOnClickListener(null);
        currentItemIsMedia = false;
        immersivePreview = false;
        setVideoFullscreen(false);
        RecoveryItem intentItem = itemFromIntent();
        singleItemPreview = intentItem != null;
        if (singleItemPreview) {
            PreviewSession.clear();
            currentItem = intentItem;
        } else {
            currentItem = PreviewSession.currentItem();
        }
        if (currentItem == null) {
            finish();
            return;
        }

        bindNavigationState();
        currentFile = previewFileFor(currentItem);
        previewFileName.setText(currentItem.name);
        previewMeta.setText(buildMeta(currentItem, currentFile));
        setRecoverability(R.string.preview_recoverability_readable);
        resetPlaybackControls();

        if (!currentFile.exists() || !currentFile.canRead()) {
            setRecoverability(R.string.preview_recoverability_unreadable);
            setPlaybackChromeVisible(false);
            addMessage(getString(R.string.preview_missing));
            showOverlay(false);
            return;
        }

        if (currentItem.type == RecoveryType.IMAGE) {
            immersivePreview = true;
            setPlaybackChromeVisible(false);
            addImagePreview(currentFile);
            hideOverlay();
        } else if (currentItem.type == RecoveryType.VIDEO) {
            currentItemIsMedia = true;
            immersivePreview = true;
            setPlaybackChromeVisible(true);
            addVideoPreview(currentFile);
            hideOverlay();
        } else if (currentItem.type == RecoveryType.AUDIO) {
            currentItemIsMedia = true;
            immersivePreview = true;
            setPlaybackChromeVisible(true);
            addAudioPreview(currentFile);
            hideOverlay();
        } else {
            setPlaybackChromeVisible(false);
            addDocumentPreview(currentFile);
            showOverlay(false);
        }
    }

    private void bindNavigationState() {
        if (singleItemPreview) {
            previewIndex.setText("1 / 1");
            return;
        }
        int total = PreviewSession.totalCount();
        if (total > 0) {
            previewIndex.setText(getString(R.string.preview_index_format, PreviewSession.currentIndex() + 1, total));
        } else {
            previewIndex.setText("1 / 1");
        }
    }

    private void movePreviewBy(int delta) {
        if (singleItemPreview) {
            return;
        }
        if (PreviewSession.moveBy(delta)) {
            renderCurrentItem();
        }
    }

    private RecoveryItem itemFromIntent() {
        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        String typeName = getIntent().getStringExtra(EXTRA_TYPE);
        boolean suspectedDeleted = getIntent().getBooleanExtra(EXTRA_SUSPECTED_DELETED, false);
        Uri data = getIntent().getData();
        if (path == null) {
            if (data == null) {
                return null;
            }
            path = data.toString();
        }
        if (name == null || name.isEmpty()) {
            name = displayNameFor(path, data);
        }
        RecoveryType type = parseType(typeName, path, getIntent().getType());
        File file = new File(path);
        long size = file.exists() ? file.length() : queryContentSize(data);
        long modified = file.exists() ? file.lastModified() : 0L;
        return new RecoveryItem(type, name, path, size, modified, 0, 0, suspectedDeleted);
    }

    private void recoverCurrentItem() {
        final RecoveryItem item = currentItem != null ? currentItem : itemFromIntent();
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

    private String buildMeta(RecoveryItem item, File file) {
        StringBuilder builder = new StringBuilder();
        if (item != null && item.size > 0) {
            builder.append(formatSize(item.size));
            if (item.modifiedAt > 0) {
                builder.append(" · ").append(DateFormat.getDateTimeInstance().format(new Date(item.modifiedAt)));
            }
        } else if (file != null) {
            long size = file.length();
            if (size > 0) {
                builder.append(formatSize(size));
            }
            if (file.lastModified() > 0) {
                if (builder.length() > 0) {
                    builder.append(" · ");
                }
                builder.append(DateFormat.getDateTimeInstance().format(new Date(file.lastModified())));
            }
        }
        return builder.toString();
    }

    private void showFileDetails() {
        if (currentItem == null || currentFile == null) {
            return;
        }
        String typeLabel = typeLabel(currentItem.type);
        String modified = currentItem.modifiedAt > 0
                ? DateFormat.getDateTimeInstance().format(new Date(currentItem.modifiedAt))
                : "-";
        long size = currentItem.size > 0 ? currentItem.size : currentFile.length();
        String message = getString(R.string.file_browser_detail_name) + ": " + currentItem.name + "\n"
                + getString(R.string.file_browser_detail_type) + ": " + typeLabel + "\n"
                + getString(R.string.file_browser_detail_size) + ": " + formatSize(size) + "\n"
                + getString(R.string.file_browser_detail_modified) + ": " + modified + "\n"
                + getString(R.string.preview_detail_status) + ": "
                + getString(currentItem.suspectedDeleted ? R.string.status_deleted : R.string.status_existing) + "\n"
                + getString(R.string.file_browser_detail_path) + ":\n" + currentItem.path;
        new AlertDialog.Builder(this)
                .setTitle(R.string.preview_details)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String typeLabel(RecoveryType type) {
        if (type == RecoveryType.IMAGE) {
            return getString(R.string.type_images);
        } else if (type == RecoveryType.VIDEO) {
            return getString(R.string.type_videos);
        } else if (type == RecoveryType.AUDIO) {
            return getString(R.string.type_audio);
        }
        return getString(R.string.type_documents);
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
        bindImageGestures(imageView);
    }

    private void bindImageGestures(View view) {
        final GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent event) {
                if (overlayChrome.getVisibility() == View.VISIBLE) {
                    hideOverlay();
                } else {
                    showOverlay(false);
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent start, MotionEvent end, float velocityX, float velocityY) {
                if (start == null || end == null) {
                    return false;
                }
                float dx = end.getX() - start.getX();
                float dy = end.getY() - start.getY();
                if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > dp(72)) {
                    movePreviewBy(dx > 0 ? -1 : 1);
                    return true;
                }
                return false;
            }
        });
        view.setOnTouchListener((touchedView, event) -> detector.onTouchEvent(event));
    }

    private void addVideoPreview(final File file) {
        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(getResources().getColor(R.color.preview_backdrop, getTheme()));
        final ImageView posterView = new ImageView(this);
        posterView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap poster = readVideoFrame(file);
        if (poster != null) {
            posterView.setImageBitmap(poster);
        } else {
            posterView.setImageResource(R.drawable.ic_type_video);
            posterView.setPadding(dp(96), dp(96), dp(96), dp(96));
        }
        previewFrame.addView(posterView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        final TextureView textureView = new TextureView(this);
        textureView.setOpaque(false);
        textureView.setAlpha(0f);
        previewFrame.addView(textureView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        bindMediaGestures(previewFrame);
        contentHost.addView(previewFrame, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                mediaSurface = new Surface(surfaceTexture);
                prepareVideoPlayer(file, textureView, posterView);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                if (mediaPlayer != null) {
                    applyVideoFitTransform(textureView, mediaPlayer.getVideoWidth(), mediaPlayer.getVideoHeight());
                }
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
        FrameLayout audioRoot = new FrameLayout(this);
        audioRoot.setBackgroundColor(getResources().getColor(R.color.preview_backdrop, getTheme()));

        LinearLayout panel = new LinearLayout(this);
        panel.setBackgroundResource(R.drawable.bg_preview_audio_panel);
        panel.setGravity(Gravity.CENTER);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(24), dp(28), dp(24), dp(28));

        ImageView audioIcon = new ImageView(this);
        audioIcon.setImageResource(R.drawable.ic_type_audio);
        audioIcon.setAlpha(0.95f);
        panel.addView(audioIcon, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView title = new TextView(this);
        title.setText(R.string.preview_audio_title);
        title.setTextColor(getResources().getColor(R.color.text_on_primary, getTheme()));
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, dp(16), 0, dp(4));
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView type = new TextView(this);
        type.setText(R.string.type_audio);
        type.setTextColor(getResources().getColor(R.color.brand_accent_soft, getTheme()));
        type.setTextSize(12);
        type.setGravity(Gravity.CENTER);
        panel.addView(type, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout wave = new LinearLayout(this);
        wave.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        wave.setOrientation(LinearLayout.HORIZONTAL);
        wave.setPadding(0, dp(20), 0, 0);
        int[] heights = {18, 34, 24, 44, 28, 38, 22, 32, 18};
        for (int height : heights) {
            View bar = new View(this);
            bar.setBackgroundResource(R.drawable.bg_preview_wave_bar);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(5), dp(height));
            params.setMargins(dp(4), 0, dp(4), 0);
            wave.addView(bar, params);
        }
        panel.addView(wave, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        panelParams.setMargins(dp(32), 0, dp(32), 0);
        audioRoot.addView(panel, panelParams);
        bindMediaGestures(audioRoot);
        contentHost.addView(audioRoot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        prepareAudioPlayer(file);
    }

    private void bindMediaGestures(View view) {
        final MediaGestureTouchListener listener = new MediaGestureTouchListener(view);
        view.setOnTouchListener(listener);
    }

    private final class MediaGestureTouchListener implements View.OnTouchListener {
        private final View targetView;
        private final GestureDetector detector;
        private final int touchSlop;

        private int gestureZone;
        private float gestureStartX;
        private float gestureStartY;
        private float startBrightness;
        private int startVolume;
        private boolean edgeAdjustmentActive;

        MediaGestureTouchListener(View targetView) {
            this.targetView = targetView;
            touchSlop = ViewConfiguration.get(PreviewActivity.this).getScaledTouchSlop();
            detector = new GestureDetector(PreviewActivity.this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent event) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent event) {
                handleMediaSingleTap();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent event) {
                return false;
            }

                @Override
                public void onLongPress(MotionEvent event) {
                    if (gestureZone == GESTURE_ZONE_CENTER) {
                        startFastPlayback();
                    }
                }

                @Override
                public boolean onFling(MotionEvent start, MotionEvent end, float velocityX, float velocityY) {
                    if (start == null || end == null || gestureZone != GESTURE_ZONE_CENTER) {
                        return false;
                    }
                    float dx = end.getX() - start.getX();
                    float dy = end.getY() - start.getY();
                    if (Math.abs(dy) > Math.abs(dx) && Math.abs(dy) > dp(72)) {
                        movePreviewBy(dy < 0 ? 1 : -1);
                        return true;
                    }
                    return false;
                }
            });
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    gestureStartX = event.getX();
                    gestureStartY = event.getY();
                    gestureZone = resolveGestureZone(view, gestureStartX);
                    startBrightness = currentWindowBrightness();
                    startVolume = currentMusicVolume();
                    edgeAdjustmentActive = false;
                    detector.onTouchEvent(event);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (edgeAdjustmentActive) {
                        updateEdgeAdjustment(event.getY());
                        return true;
                    }
                    if (isEdgeAdjustmentGesture(event)) {
                        edgeAdjustmentActive = true;
                        targetView.getParent().requestDisallowInterceptTouchEvent(true);
                        updateEdgeAdjustment(event.getY());
                        return true;
                    }
                    detector.onTouchEvent(event);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopFastPlayback();
                    if (edgeAdjustmentActive) {
                        edgeAdjustmentActive = false;
                        scheduleOverlayHide();
                        return true;
                    }
                    detector.onTouchEvent(event);
                    return true;
                default:
                    return detector.onTouchEvent(event);
            }
        }

        private boolean isEdgeAdjustmentGesture(MotionEvent event) {
            if (gestureZone != GESTURE_ZONE_LEFT && gestureZone != GESTURE_ZONE_RIGHT) {
                return false;
            }
            float dx = event.getX() - gestureStartX;
            float dy = event.getY() - gestureStartY;
            return Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx);
        }

        private void updateEdgeAdjustment(float currentY) {
            float delta = (gestureStartY - currentY) / Math.max(1f, targetView.getHeight());
            if (gestureZone == GESTURE_ZONE_LEFT) {
                float brightness = clamp(startBrightness + delta, 0.05f, 1f);
                setWindowBrightness(brightness);
                showGestureFeedback(getString(R.string.preview_gesture_brightness, Math.round(brightness * 100f)));
            } else if (gestureZone == GESTURE_ZONE_RIGHT) {
                int maxVolume = maxMusicVolume();
                int volume = Math.round(startVolume + delta * maxVolume);
                volume = Math.max(0, Math.min(maxVolume, volume));
                setMusicVolume(volume);
                int percent = maxVolume <= 0 ? 0 : Math.round(volume * 100f / maxVolume);
                showGestureFeedback(getString(R.string.preview_gesture_volume, percent));
            }
        }
    }

    private void prepareVideoPlayer(File file, final TextureView textureView, final ImageView posterView) {
        releasePlayerOnly();
        mediaPrepared = false;
        setPlaybackEnabled(false);

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setSurface(mediaSurface);
        } catch (Exception exception) {
            showPlaybackError();
            return;
        }
        mediaPlayer.setOnVideoSizeChangedListener(new MediaPlayer.OnVideoSizeChangedListener() {
            @Override
            public void onVideoSizeChanged(MediaPlayer mp, int width, int height) {
                applyVideoFitTransform(textureView, width, height);
            }
        });
        bindPlayerCallbacks(new Runnable() {
            @Override
            public void run() {
                applyVideoFitTransform(textureView, mediaPlayer.getVideoWidth(), mediaPlayer.getVideoHeight());
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
        setPlaybackEnabled(false);
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
                setPlaybackEnabled(true);
                setRecoverability(R.string.preview_recoverability_ready);
                if (onPreparedExtra != null) {
                    onPreparedExtra.run();
                }
                mp.start();
                updateProgress();
                updatePlaybackState(true);
            }
        });
        mediaPlayer.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mediaPlayer) {
                updatePlaybackState(false);
                updateProgress();
                showOverlay(false);
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
            showOverlay(false);
            return;
        }
        stopFastPlayback();
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updatePlaybackState(false);
        } else {
            mediaPlayer.start();
            updatePlaybackState(true);
        }
    }

    private void handleMediaSingleTap() {
        if (!mediaPrepared || mediaPlayer == null) {
            showOverlay(false);
            return;
        }
        if (mediaPlayer.isPlaying()) {
            stopFastPlayback();
            mediaPlayer.pause();
            updatePlaybackState(false);
        }
        showOverlay(false);
    }

    private void toggleVideoFullscreen() {
        if (!isVideoPreview()) {
            return;
        }
        setVideoFullscreen(!videoFullscreen);
        showOverlay(false);
    }

    private void setVideoFullscreen(boolean fullscreen) {
        if (videoFullscreen == fullscreen) {
            applyFullscreenChromeState();
            return;
        }
        videoFullscreen = fullscreen;
        setRequestedOrientation(fullscreen
                ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                : originalRequestedOrientation);
        applyFullscreenChromeState();
    }

    private void applyFullscreenChromeState() {
        boolean fullscreenVideo = videoFullscreen && isVideoPreview();
        if (previewMetaCard != null) {
            previewMetaCard.setVisibility(fullscreenVideo ? View.GONE : View.VISIBLE);
        }
        if (previewRecoverButton != null) {
            previewRecoverButton.setVisibility(fullscreenVideo ? View.GONE : View.VISIBLE);
        }
        if (playbackFullscreenButton != null) {
            playbackFullscreenButton.setVisibility(playbackChromeVisible && isVideoPreview()
                    ? View.VISIBLE
                    : View.GONE);
            playbackFullscreenButton.setImageResource(fullscreenVideo
                    ? R.drawable.ic_fullscreen_exit
                    : R.drawable.ic_fullscreen);
            playbackFullscreenButton.setContentDescription(getString(fullscreenVideo
                    ? R.string.preview_exit_fullscreen
                    : R.string.preview_fullscreen));
        }
    }

    private void startFastPlayback() {
        if (!isVideoPreview() || !mediaPrepared || mediaPlayer == null || !mediaPlayer.isPlaying()) {
            return;
        }
        if (setMediaPlaybackSpeed(FAST_PLAYBACK_SPEED)) {
            speedBoostActive = true;
            showGestureFeedback(R.string.preview_gesture_speed);
        }
    }

    private void stopFastPlayback() {
        if (!speedBoostActive) {
            return;
        }
        speedBoostActive = false;
        setMediaPlaybackSpeed(1.0f);
    }

    private boolean setMediaPlaybackSpeed(float speed) {
        if (mediaPlayer == null) {
            return false;
        }
        try {
            PlaybackParams params = mediaPlayer.getPlaybackParams();
            params.setSpeed(speed);
            mediaPlayer.setPlaybackParams(params);
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean isVideoPreview() {
        return currentItem != null && currentItem.type == RecoveryType.VIDEO;
    }

    private void updatePlaybackState(boolean playing) {
        stopProgressUpdates();
        playbackButton.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play_white);
        playbackButton.setContentDescription(getString(playing ? R.string.preview_pause : R.string.preview_play));
        if (playing) {
            progressUpdater.run();
            scheduleOverlayHide();
        } else {
            showOverlay(false);
        }
    }

    private void updateProgress() {
        if (mediaPlayer == null || !mediaPrepared || userSeeking) {
            return;
        }
        int duration = mediaPlayer.getDuration();
        int position = mediaPlayer.getCurrentPosition();
        if (duration <= 0) {
            playbackSeekBar.setProgress(0);
            return;
        }
        playbackSeekBar.setProgress(Math.min(SEEK_BAR_MAX, Math.max(0,
                (int) ((long) position * SEEK_BAR_MAX / duration))));
    }

    private void showPlaybackError() {
        mediaPrepared = false;
        stopProgressUpdates();
        setPlaybackEnabled(false);
        setRecoverability(R.string.preview_recoverability_failed);
        showOverlay(false);
        Toast.makeText(this, R.string.preview_playback_error, Toast.LENGTH_SHORT).show();
    }

    private int resolveGestureZone(View view, float x) {
        int width = Math.max(1, view.getWidth());
        int edgeWidth = width * GESTURE_ZONE_EDGE_PERCENT / 100;
        if (x <= edgeWidth) {
            return GESTURE_ZONE_LEFT;
        }
        if (x >= width - edgeWidth) {
            return GESTURE_ZONE_RIGHT;
        }
        return GESTURE_ZONE_CENTER;
    }

    private float currentWindowBrightness() {
        float brightness = getWindow().getAttributes().screenBrightness;
        if (brightness < 0f) {
            return 0.5f;
        }
        return clamp(brightness, 0.05f, 1f);
    }

    private void setWindowBrightness(float brightness) {
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.screenBrightness = clamp(brightness, 0.05f, 1f);
        getWindow().setAttributes(params);
    }

    private int currentMusicVolume() {
        if (audioManager == null) {
            return 0;
        }
        return audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
    }

    private int maxMusicVolume() {
        if (audioManager == null) {
            return 0;
        }
        return Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private void setMusicVolume(int volume) {
        if (audioManager != null) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
        }
    }

    private void showGestureFeedback(int stringResId) {
        showGestureFeedback(getString(stringResId));
    }

    private void showGestureFeedback(String message) {
        if (gestureFeedbackView == null) {
            return;
        }
        gestureFeedbackView.setText(message);
        gestureFeedbackView.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideGestureFeedbackRunnable);
        uiHandler.postDelayed(hideGestureFeedbackRunnable, 900);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private void showOverlay(boolean autoHide) {
        overlayChrome.setVisibility(View.VISIBLE);
        uiHandler.removeCallbacks(hideOverlayRunnable);
        if (autoHide) {
            scheduleOverlayHide();
        }
    }

    private void hideOverlay() {
        if (immersivePreview) {
            overlayChrome.setVisibility(View.GONE);
        }
    }

    private void scheduleOverlayHide() {
        uiHandler.removeCallbacks(hideOverlayRunnable);
        if (currentItemIsMedia && mediaPlayer != null && mediaPlayer.isPlaying()) {
            uiHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS);
        }
    }

    private void stopProgressUpdates() {
        uiHandler.removeCallbacks(progressUpdater);
    }

    private void setPlaybackChromeVisible(boolean visible) {
        playbackChromeVisible = visible;
        playbackControls.setVisibility(visible ? View.VISIBLE : View.GONE);
        playbackButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        applyFullscreenChromeState();
    }

    private void setPlaybackEnabled(boolean enabled) {
        playbackSeekBar.setEnabled(enabled);
    }

    private void resetPlaybackControls() {
        playbackSeekBar.setProgress(0);
        playbackButton.setImageResource(R.drawable.ic_play_white);
        playbackButton.setContentDescription(getString(R.string.preview_play));
        setPlaybackEnabled(false);
    }

    private void setRecoverability(int stringResId) {
        recoverabilityView.setText(stringResId);
    }

    private void applyVideoFitTransform(TextureView textureView, int videoWidth, int videoHeight) {
        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (videoWidth <= 0 || videoHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            textureView.setTransform(null);
            return;
        }
        float scale = Math.min(viewWidth / (float) videoWidth, viewHeight / (float) videoHeight);
        float displayWidth = videoWidth * scale;
        float displayHeight = videoHeight * scale;
        float scaleX = displayWidth / viewWidth;
        float scaleY = displayHeight / viewHeight;

        Matrix matrix = new Matrix();
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f);
        textureView.setTransform(matrix);
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
        message.setTextColor(getResources().getColor(R.color.text_on_primary, getTheme()));
        message.setPadding(dp(16), dp(16), dp(16), dp(16));
        scrollView.addView(message);
        contentHost.addView(scrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        contentHost.setOnClickListener(v -> showOverlay(false));
    }

    private void addMessage(String message) {
        TextView textView = new TextView(this);
        textView.setText(message);
        textView.setGravity(Gravity.CENTER);
        textView.setTextColor(getResources().getColor(R.color.status_warning, getTheme()));
        textView.setPadding(dp(16), dp(16), dp(16), dp(16));
        contentHost.addView(textView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        contentHost.setOnClickListener(v -> showOverlay(false));
    }

    private File previewFileFor(RecoveryItem item) {
        if (item == null || item.path == null) {
            return new File(getCacheDir(), "missing_preview_source");
        }
        if (item.path.startsWith("content://")) {
            try {
                return cacheContentUri(Uri.parse(item.path), item.name);
            } catch (IOException exception) {
                return new File(getCacheDir(), "missing_preview_source");
            }
        }
        return item.asFile();
    }

    private File cacheContentUri(Uri uri, String name) throws IOException {
        File directory = new File(getCacheDir(), PREVIEW_CACHE_DIR);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create preview cache: " + directory.getAbsolutePath());
        }
        clearDirectory(directory);
        File destination = new File(directory, sanitizeFileName(name));
        try (InputStream input = getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("Cannot open content URI: " + uri);
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
        return destination;
    }

    private void clearDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile()) {
                file.delete();
            }
        }
    }

    private String sanitizeFileName(String name) {
        String fallback = (name == null || name.trim().isEmpty()) ? "preview_source" : name.trim();
        return fallback.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String displayNameFor(String path, Uri data) {
        if (data != null && "content".equals(data.getScheme())) {
            try (Cursor cursor = getContentResolver().query(data,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String displayName = cursor.getString(index);
                        if (displayName != null && !displayName.isEmpty()) {
                            return displayName;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (path != null && !path.isEmpty()) {
            String fileName = new File(path).getName();
            if (fileName != null && !fileName.isEmpty()) {
                return fileName;
            }
        }
        if (data != null && data.getLastPathSegment() != null) {
            return data.getLastPathSegment();
        }
        return "preview_source";
    }

    private long queryContentSize(Uri data) {
        if (data == null || !"content".equals(data.getScheme())) {
            return 0L;
        }
        try (Cursor cursor = getContentResolver().query(data,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return Math.max(0L, cursor.getLong(index));
                }
            }
        } catch (RuntimeException ignored) {
        }
        return 0L;
    }

    private RecoveryType parseType(String typeName, String path, String mimeType) {
        if (typeName != null) {
            try {
                return RecoveryType.valueOf(typeName);
            } catch (IllegalArgumentException ignored) {
            }
        }
        if (mimeType != null) {
            String mime = mimeType.toLowerCase(Locale.US);
            if (mime.startsWith("image/")) {
                return RecoveryType.IMAGE;
            }
            if (mime.startsWith("video/")) {
                return RecoveryType.VIDEO;
            }
            if (mime.startsWith("audio/")) {
                return RecoveryType.AUDIO;
            }
        }
        String lowerPath = path == null ? "" : path.toLowerCase(Locale.US);
        if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg") || lowerPath.endsWith(".png")
                || lowerPath.endsWith(".webp") || lowerPath.endsWith(".gif")
                || lowerPath.endsWith(".bmp") || lowerPath.endsWith(".heic")) {
            return RecoveryType.IMAGE;
        }
        if (lowerPath.endsWith(".mp4") || lowerPath.endsWith(".mkv") || lowerPath.endsWith(".mov")
                || lowerPath.endsWith(".webm") || lowerPath.endsWith(".3gp")
                || lowerPath.endsWith(".avi")) {
            return RecoveryType.VIDEO;
        }
        if (lowerPath.endsWith(".mp3") || lowerPath.endsWith(".m4a") || lowerPath.endsWith(".aac")
                || lowerPath.endsWith(".wav") || lowerPath.endsWith(".flac")
                || lowerPath.endsWith(".ogg")) {
            return RecoveryType.AUDIO;
        }
        return RecoveryType.DOCUMENT;
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
        speedBoostActive = false;
        mediaPrepared = false;
        stopProgressUpdates();
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
