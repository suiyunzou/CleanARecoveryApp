package com.example.cleanrecovery;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Surface;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public final class PreviewActivity extends Activity {
    public static final String EXTRA_PATH = "com.example.cleanrecovery.extra.PATH";
    public static final String EXTRA_NAME = "com.example.cleanrecovery.extra.NAME";
    public static final String EXTRA_TYPE = "com.example.cleanrecovery.extra.TYPE";
    public static final String EXTRA_SUSPECTED_DELETED = "com.example.cleanrecovery.extra.SUSPECTED_DELETED";

    private LinearLayout root;
    private MediaPlayer mediaPlayer;
    private Surface mediaSurface;
    private Button playbackButton;
    private TextView playbackStateView;
    private boolean mediaPrepared;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildBaseUi();

        String path = getIntent().getStringExtra(EXTRA_PATH);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        String typeName = getIntent().getStringExtra(EXTRA_TYPE);
        boolean suspectedDeleted = getIntent().getBooleanExtra(EXTRA_SUSPECTED_DELETED, false);
        File file = path == null ? null : new File(path);

        if (name == null || name.length() == 0) {
            name = file == null ? "" : file.getName();
        }
        RecoveryType type = parseType(typeName);

        addHeader(name, file, suspectedDeleted);
        if (file == null || !file.exists() || !file.canRead()) {
            addMessage(getString(R.string.preview_missing));
            return;
        }

        if (type == RecoveryType.IMAGE) {
            addImagePreview(file);
        } else if (type == RecoveryType.VIDEO) {
            addVideoPreview(file);
        } else if (type == RecoveryType.AUDIO) {
            addAudioPreview(file);
        } else {
            addDocumentPreview(file);
        }
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

    private void buildBaseUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(246, 248, 250));
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        setContentView(root);
    }

    private void addHeader(String name, File file, boolean suspectedDeleted) {
        LinearLayout header = panel();
        root.addView(header, matchWrapWithBottom(10));

        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(18f);
        title.setTextColor(Color.rgb(20, 33, 43));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(false);
        header.addView(title, matchWrap());

        TextView meta = new TextView(this);
        meta.setTextSize(12f);
        meta.setTextColor(Color.rgb(82, 98, 109));
        meta.setPadding(0, dp(6), 0, dp(8));
        meta.setText(buildMeta(file, suspectedDeleted));
        header.addView(meta, matchWrap());

        Button closeButton = secondaryButton(R.string.preview_close);
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        header.addView(closeButton, matchWrap());
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
        imageView.setBackgroundColor(Color.BLACK);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageURI(Uri.fromFile(file));
        root.addView(imageView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
    }

    private void addVideoPreview(final File file) {
        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);

        final ImageView posterView = new ImageView(this);
        posterView.setBackgroundColor(Color.BLACK);
        posterView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap poster = readVideoFrame(file);
        if (poster != null) {
            posterView.setImageBitmap(poster);
        }
        previewFrame.addView(posterView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        final TextureView textureView = new TextureView(this);
        textureView.setAlpha(0f);
        previewFrame.addView(textureView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(previewFrame, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        addPlaybackControls();

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

    private void addAudioPreview(final File file) {
        addMessage(getString(R.string.preview_audio_ready));
        addPlaybackControls();
        prepareAudioPlayer(file);
    }

    private void prepareVideoPlayer(File file, final TextureView textureView, final ImageView posterView) {
        releasePlayerOnly();
        mediaPrepared = false;
        playbackButton.setEnabled(false);
        playbackStateView.setText(R.string.preview_loading);

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setSurface(mediaSurface);
        } catch (Exception exception) {
            showPlaybackError();
            return;
        }

        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mediaPlayer) {
                mediaPrepared = true;
                mediaPlayer.setLooping(false);
                playbackButton.setEnabled(true);
                textureView.setAlpha(1f);
                posterView.setVisibility(View.GONE);
                mediaPlayer.start();
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

        try {
            mediaPlayer.prepareAsync();
        } catch (IllegalStateException exception) {
            showPlaybackError();
        }
    }

    private void prepareAudioPlayer(File file) {
        releasePlayerOnly();
        mediaPrepared = false;
        playbackButton.setEnabled(false);
        playbackStateView.setText(R.string.preview_loading);

        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(file.getAbsolutePath());
        } catch (Exception exception) {
            showPlaybackError();
            return;
        }

        mediaPlayer.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mediaPlayer) {
                mediaPrepared = true;
                mediaPlayer.setLooping(false);
                playbackButton.setEnabled(true);
                mediaPlayer.start();
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

        try {
            mediaPlayer.prepareAsync();
        } catch (IllegalStateException exception) {
            showPlaybackError();
        }
    }

    private void addPlaybackControls() {
        LinearLayout controls = panel();
        controls.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(controls, matchWrapWithTop(10));

        playbackStateView = new TextView(this);
        playbackStateView.setText(R.string.preview_loading);
        playbackStateView.setTextSize(12f);
        playbackStateView.setTextColor(Color.rgb(82, 98, 109));
        playbackStateView.setPadding(0, 0, 0, dp(6));
        controls.addView(playbackStateView, matchWrap());

        playbackButton = secondaryButton(R.string.preview_pause);
        playbackButton.setEnabled(false);
        playbackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                togglePlayback();
            }
        });
        controls.addView(playbackButton, matchWrap());
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
                // Ignore platform retriever cleanup failures.
            }
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
                // Player may not be prepared yet.
            }
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    private void addDocumentPreview(File file) {
        ScrollView scrollView = new ScrollView(this);
        LinearLayout panel = panel();
        scrollView.addView(panel, matchWrap());
        TextView message = new TextView(this);
        message.setText(R.string.preview_document_message);
        message.setTextSize(14f);
        message.setTextColor(Color.rgb(36, 50, 60));
        message.setPadding(0, 0, 0, dp(8));
        panel.addView(message, matchWrap());

        TextView detail = new TextView(this);
        detail.setText(file.getAbsolutePath());
        detail.setTextSize(12f);
        detail.setTextColor(Color.rgb(82, 98, 109));
        detail.setSingleLine(false);
        panel.addView(detail, matchWrap());

        root.addView(scrollView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
    }

    private void addMessage(String message) {
        TextView textView = new TextView(this);
        textView.setText(message);
        textView.setTextSize(15f);
        textView.setTextColor(Color.rgb(174, 89, 0));
        textView.setGravity(Gravity.CENTER);
        root.addView(textView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
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

    private LinearLayout panel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(10), dp(12), dp(10));
        panel.setBackground(panelBackground(Color.WHITE));
        return panel;
    }

    private Button secondaryButton(int stringRes) {
        Button button = new Button(this);
        button.setText(stringRes);
        button.setAllCaps(false);
        button.setMinHeight(dp(42));
        button.setTextSize(13f);
        button.setTextColor(Color.rgb(0, 105, 92));
        button.setBackground(buttonBackground(Color.WHITE, Color.rgb(192, 210, 207)));
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
