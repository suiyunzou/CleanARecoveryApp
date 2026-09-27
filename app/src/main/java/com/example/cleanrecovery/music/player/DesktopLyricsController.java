package com.example.cleanrecovery.music.player;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.ViaUi;
import com.example.cleanrecovery.music.MusicApp;
import com.example.cleanrecovery.music.data.Lyrics;
import com.example.cleanrecovery.music.data.SongInfo;

/** One small overlay owned by MusicService, never by the player Activity. Main-thread only. */
public final class DesktopLyricsController implements MusicPlayer.Callback,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private final Context context;
    private final MusicPlayer player;
    private final WindowManager windows;
    private final SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable settingsChanged;
    private LinearLayout root;
    private TextView primary;
    private TextView preview;
    private LinearLayout settingsBar;
    private android.widget.ImageView quickPlay;
    private android.app.AlertDialog settingsDialog;
    private final Runnable hideSettings = () -> {
        setControlsVisible(false);
    };
    private WindowManager.LayoutParams params;
    private Lyrics lyrics = Lyrics.empty();
    private SongInfo song;
    private int generation;
    private int orientation;
    private int availableWidth;
    private int availableHeight;
    private boolean closed;
    private boolean dragging;
    private boolean windowDirty = true;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { windowDirty = true; refresh(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() { refresh(); }
    };

    public DesktopLyricsController(Context context, Runnable settingsChanged) {
        this.context = context;
        this.settingsChanged = settingsChanged;
        player = MusicPlayer.get();
        windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        prefs = DesktopLyricsSettings.get(context);
        prefs.registerOnSharedPreferenceChangeListener(this);
        player.addCallback(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(screen, filter);
        refresh();
    }

    private boolean enabled() { return prefs.getBoolean(DesktopLyricsSettings.ENABLED, false); }
    private boolean locked() { return prefs.getBoolean(DesktopLyricsSettings.LOCKED, false); }
    private boolean screenVisible() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        return power.isInteractive() && !keyguard.isKeyguardLocked();
    }

    private void refresh() {
        handler.removeCallbacks(tick);
        if (closed) return;
        if (!enabled() || player.currentSong() == null) {
            removeWindow();
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            prefs.edit().putBoolean(DesktopLyricsSettings.ENABLED, false).apply();
            removeWindow();
            return;
        }
        if (!screenVisible()) {
            removeWindow();
            return;
        }
        SongInfo current = player.currentSong();
        if (song != current) loadSong(current);
        try {
            if (root == null) createWindow();
            updateWindow();
            render(player.getCurrentPosition());
        } catch (RuntimeException error) {
            android.util.Log.w("DesktopLyrics", "Overlay unavailable", error);
            removeWindow();
            prefs.edit().putBoolean(DesktopLyricsSettings.ENABLED, false).apply();
        }
        if (root != null) handler.postDelayed(tick,
                player.getState() == MusicPlayer.State.PLAYING ? 250 : 1000);
    }

    private void loadSong(SongInfo current) {
        song = current;
        lyrics = Lyrics.empty();
        int request = ++generation;
        MusicApp.get().lyrics.load(current, (value, error) -> {
            if (closed || generation != request) return;
            lyrics = value;
            if (root != null) render(player.getCurrentPosition());
        });
    }

    private void createWindow() {
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(6), dp(4), dp(6), dp(4));
        root.setBackgroundColor(Color.TRANSPARENT);
        primary = label(20);
        primary.setSingleLine(false);
        primary.setMaxLines(Integer.MAX_VALUE);
        primary.setHorizontallyScrolling(false);
        primary.setIncludeFontPadding(false);
        primary.setLineSpacing(dp(2), 1f);
        primary.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        primary.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        root.addView(primary);
        settingsBar = new LinearLayout(context);
        settingsBar.setOrientation(LinearLayout.HORIZONTAL);
        settingsBar.setGravity(Gravity.CENTER_VERTICAL);
        settingsBar.setPadding(dp(4), 0, dp(4), 0);
        // Translucent glass treatment; no screen capture or unsupported cross-window blur.
        int surface = ViaUi.surfaceColor(context) & 0x00FFFFFF;
        GradientDrawable card = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xD9000000 | surface, 0xA6000000 | surface});
        card.setCornerRadius(dp(12));
        card.setStroke(dp(1), 0x66FFFFFF);
        settingsBar.setBackground(card);
        settingsBar.setElevation(dp(2));
        addSetting(R.drawable.ic_notif_prev, context.getString(R.string.notif_action_prev), player::previous);
        quickPlay = addSetting(R.drawable.ic_notif_play, context.getString(R.string.notif_action_play), player::toggle);
        addSetting(R.drawable.ic_notif_next, context.getString(R.string.notif_action_next), player::next);
        addSetting(R.drawable.ic_settings_gear, context.getString(R.string.music_desktop_lyrics), () -> {
            hideSettings.run();
            if (settingsDialog == null || !settingsDialog.isShowing())
                settingsDialog = com.example.cleanrecovery.music.ui.DesktopLyricsDialog.showOverlay(context);
        });
        root.addView(settingsBar, new LinearLayout.LayoutParams(-1, -2));
        settingsBar.setVisibility(View.GONE);
        preview = label(14);
        preview.setSingleLine(false);
        preview.setPadding(dp(4), dp(4), dp(4), 0);
        preview.setVisibility(View.GONE);
        root.addView(preview);
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (params == null || dragging || root == null) return;
            int x = params.x, y = params.y;
            clamp();
            if (params.x != x || params.y != y) safeUpdate();
        });
        View.OnTouchListener drag = new View.OnTouchListener() {
            float startX, startY;
            int windowX, windowY;
            final GestureDetector gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }
                @Override public boolean onSingleTapUp(MotionEvent event) {
                    if (settingsBar != null) { setControlsVisible(true); scheduleHide(); }
                    return true;
                }
                @Override public void onLongPress(MotionEvent event) {
                    if (root == null || locked()) return;
                    dragging = true;
                    root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    handler.removeCallbacks(hideSettings);
                }
            }, handler);
            @Override public boolean onTouch(View view, MotionEvent event) {
                if (locked()) return false;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    handler.removeCallbacks(hideSettings);
                    startX = event.getRawX(); startY = event.getRawY();
                    windowX = params.x; windowY = params.y; dragging = false;
                }
                gestures.onTouchEvent(event);
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging) return true;
                        params.x = windowX + Math.round(event.getRawX() - startX);
                        params.y = windowY + Math.round(event.getRawY() - startY);
                        clamp();
                        safeUpdate();
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) { dragging = false; savePosition(); }
                        scheduleHide();
                        view.performClick();
                        return true;
                    default: return true;
                }
            }
        };
        primary.setOnTouchListener(drag);
        preview.setOnTouchListener(drag);
        root.setOnTouchListener(drag);
        params = new WindowManager.LayoutParams(1, WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setTitle("Desktop lyrics");
        orientation = 0;
        configureWindow();
        windows.addView(root, params);
        windowDirty = false;
        root.post(() -> { if (root != null && !closed) { clamp(); safeUpdate(); } });
    }

    private TextView label(int size) {
        TextView view = new TextView(context);
        view.setTextColor(Color.WHITE);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setShadowLayer(dp(2), 0, dp(1), Color.BLACK);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private android.widget.ImageView addSetting(int icon, String description, Runnable action) {
        android.widget.ImageView button = new android.widget.ImageView(context);
        button.setImageResource(icon); button.setImageTintList(android.content.res.ColorStateList.valueOf(ViaUi.ACCENT));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setContentDescription(description); button.setBackgroundResource(R.drawable.bg_via_menu_cell);
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) handler.removeCallbacks(hideSettings);
            if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) scheduleHide();
            return false;
        });
        button.setOnClickListener(v -> { action.run(); scheduleHide(); });
        settingsBar.addView(button, new LinearLayout.LayoutParams(0, dp(44), 1)); return button;
    }
    private void setControlsVisible(boolean visible) {
        if (root == null || settingsBar == null) return;
        // The controls take the original lyric position; only a smaller live preview sits below.
        primary.setVisibility(visible ? View.GONE : View.VISIBLE);
        settingsBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        preview.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible && primary.getText().length() == 0) root.setVisibility(View.INVISIBLE);
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideSettings);
        if (settingsBar != null && settingsBar.getVisibility() == View.VISIBLE) handler.postDelayed(hideSettings, 2000);
    }

    private void configureWindow() {
        Point size = new Point();
        windows.getDefaultDisplay().getSize(size);
        availableWidth = size.x;
        availableHeight = size.y;
        boolean landscape = availableWidth > availableHeight;
        params.width = Math.max(1, landscape
                ? Math.min(dp(600), Math.round(availableWidth * .78f))
                : Math.min(dp(360), availableWidth - dp(32)));
        settingsBar.setLayoutParams(new LinearLayout.LayoutParams(Math.min(dp(200),
                Math.max(1, params.width - root.getPaddingLeft() - root.getPaddingRight())), -2));
        if (locked()) { setControlsVisible(false); handler.removeCallbacks(hideSettings); }
        int nextOrientation = landscape ? android.content.res.Configuration.ORIENTATION_LANDSCAPE
                : android.content.res.Configuration.ORIENTATION_PORTRAIT;
        if (orientation != nextOrientation) {
            orientation = nextOrientation;
            params.x = prefs.getInt("x_" + orientation, (availableWidth - params.width) / 2);
            params.y = prefs.getInt("y_" + orientation, dp(80));
        }
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | (locked() ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
        // Android 12 checks WINDOW alpha, not just the transparent view background.
        params.alpha = 1f;
        if (locked()) {
            params.alpha = .8f;
            if (Build.VERSION.SDK_INT >= 31) {
                InputManager input = context.getSystemService(InputManager.class);
                params.alpha = Math.min(.8f, input.getMaximumObscuringOpacityForTouch());
            }
        }
        clamp();
    }

    private void updateWindow() {
        if (dragging) return;
        int oldX = params.x, oldY = params.y;
        if (windowDirty) configureWindow();
        else clamp();
        if (windowDirty || oldX != params.x || oldY != params.y) windows.updateViewLayout(root, params);
        windowDirty = false;
    }
    private void safeUpdate() {
        if (root == null) return;
        try { windows.updateViewLayout(root, params); }
        catch (RuntimeException error) { removeWindow(); }
    }
    private void clamp() {
        int height = root == null || root.getHeight() == 0 ? dp(60) : root.getHeight();
        params.x = Math.max(0, Math.min(params.x, Math.max(0, availableWidth - params.width)));
        params.y = Math.max(0, Math.min(params.y, Math.max(0, availableHeight - height)));
    }
    private void savePosition() {
        prefs.edit().putInt("x_" + orientation, params.x).putInt("y_" + orientation, params.y).apply();
    }

    private void render(int position) {
        if (root == null || song == null) return;
        boolean playing = player.getState() == MusicPlayer.State.PLAYING;
        quickPlay.setImageResource(playing ? R.drawable.ic_notif_pause : R.drawable.ic_notif_play);
        quickPlay.setContentDescription(context.getString(playing ? R.string.notif_action_pause : R.string.notif_action_play));
        int colorIndex = Math.floorMod(prefs.getInt(DesktopLyricsSettings.COLOR, 0), DesktopLyricsSettings.COLORS.length);
        int color = DesktopLyricsSettings.COLORS[colorIndex];
        int active = lyrics.indexOfActive(position);
        if (active >= 0 && !lyrics.lines().get(active).text.trim().isEmpty()) {
            Lyrics.Line line = lyrics.lines().get(active);
            root.setVisibility(View.VISIBLE);
            fitText(line.text);
            SpannableString text = new SpannableString(line.text);
            int sung = Math.min(text.length(), line.sungTextEnd(position));
            primary.setTextColor(line.words.isEmpty() ? color : Color.WHITE);
            if (sung > 0) text.setSpan(new ForegroundColorSpan(color), 0, sung, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            primary.setText(text);
            preview.setText(text);
            preview.setTextColor(primary.getCurrentTextColor());
        } else {
            // No current lyric yet: do not replace it with the title, next line or status text.
            primary.setText("");
            preview.setText("");
            // Keep transport controls usable while switching tracks or waiting for lyrics.
            if (settingsBar.getVisibility() != View.VISIBLE) root.setVisibility(View.INVISIBLE);
        }
    }

    private void fitText(String text) {
        float sp = Math.max(16, Math.min(32, prefs.getInt(DesktopLyricsSettings.FONT, 20)));
        if (availableWidth > availableHeight) sp = Math.max(16, sp - 2);
        int width = Math.max(1, params.width - root.getPaddingLeft() - root.getPaddingRight());
        int maxHeight = Math.max(dp(40), availableHeight - dp(32));
        android.text.TextPaint measure = new android.text.TextPaint(primary.getPaint());
        float scale = context.getResources().getDisplayMetrics().scaledDensity;
        // Wrap the entire sentence; shrink only exceptionally long text that would leave the screen.
        while (sp > 12) {
            measure.setTextSize(sp * scale);
            android.text.StaticLayout layout = android.text.StaticLayout.Builder.obtain(text, 0, text.length(), measure, width)
                    .setIncludePad(false).setLineSpacing(dp(2), 1f)
                    .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
                    .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE).build();
            if (layout.getHeight() <= maxHeight) break;
            sp -= 1;
        }
        if (Math.abs(primary.getTextSize() - sp * scale) > .1f) primary.setTextSize(sp);
    }

    private void removeWindow() {
        if (settingsDialog != null) { settingsDialog.dismiss(); settingsDialog = null; }
        if (root != null) {
            try { windows.removeViewImmediate(root); }
            catch (RuntimeException ignored) { /* Permission may have been revoked by the system. */ }
            root = null;
            settingsBar = null;
            handler.removeCallbacks(hideSettings);
            dragging = false;
        }
    }
    public void close() {
        closed = true;
        generation++;
        handler.removeCallbacksAndMessages(null);
        prefs.unregisterOnSharedPreferenceChangeListener(this);
        player.removeCallback(this);
        context.unregisterReceiver(screen);
        removeWindow();
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        windowDirty = true;
        if (key == null || DesktopLyricsSettings.ENABLED.equals(key)) song = null;
        refresh();
        if (DesktopLyricsSettings.ENABLED.equals(key) || DesktopLyricsSettings.LOCKED.equals(key)) settingsChanged.run();
    }
    @Override public void onSongChanged(SongInfo value) { refresh(); }
    @Override public void onStateChanged(MusicPlayer.State state) { refresh(); }
    @Override public void onProgressChanged(int position, int duration) { /* Tick also checks permission revocation. */ }
    @Override public void onError(String message) { refresh(); }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
