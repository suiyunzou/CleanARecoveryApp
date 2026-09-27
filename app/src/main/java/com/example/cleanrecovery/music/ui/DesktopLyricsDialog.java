package com.example.cleanrecovery.music.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.player.DesktopLyricsSettings;
import com.example.cleanrecovery.music.player.MusicPlayer;
import com.example.cleanrecovery.music.player.MusicService;
import com.example.cleanrecovery.ui.browser.ViaDialogBuilder;
import com.example.cleanrecovery.ui.browser.ViaUi;
import com.example.cleanrecovery.ui.widget.GlassToast;

/** Uses the same Via cards, circular toggles and palette as browser settings. */
public final class DesktopLyricsDialog {
    public static final int PERMISSION_REQUEST = 1804;
    private DesktopLyricsDialog() {}

    public static void show(Activity activity) { show(activity, false); }
    public static AlertDialog showOverlay(android.content.Context context) { return show(context, true); }
    private static AlertDialog show(android.content.Context activity, boolean overlay) {
        SharedPreferences prefs = DesktopLyricsSettings.get(activity);
        if (!Settings.canDrawOverlays(activity)) prefs.edit().putBoolean(DesktopLyricsSettings.ENABLED, false).apply();
        int padding = ViaUi.dp(activity, 16);
        LinearLayout content = new LinearLayout(activity); content.setOrientation(1);
        content.setPadding(padding, 0, padding, ViaUi.dp(activity, 8));
        LinearLayout enable = row(activity, activity.getString(R.string.desktop_lyrics_enable));
        ImageView enableSwitch = ViaUi.switchView(activity, prefs.getBoolean(DesktopLyricsSettings.ENABLED, false));
        enable.addView(enableSwitch, new LinearLayout.LayoutParams(ViaUi.dp(activity, 32), ViaUi.dp(activity, 32)));
        content.addView(enable);
        TextView hint = label(activity, activity.getString(R.string.desktop_lyrics_hint), 13, ViaUi.TEXT_SUB);
        hint.setPadding(0, ViaUi.dp(activity, 8), 0, ViaUi.dp(activity, 12)); content.addView(hint);
        LinearLayout lock = row(activity, activity.getString(R.string.desktop_lyrics_game_mode));
        ImageView lockSwitch = ViaUi.switchView(activity, prefs.getBoolean(DesktopLyricsSettings.LOCKED, false));
        lock.addView(lockSwitch, new LinearLayout.LayoutParams(ViaUi.dp(activity, 32), ViaUi.dp(activity, 32)));
        lock.setOnClickListener(v -> prefs.edit().putBoolean(DesktopLyricsSettings.LOCKED,
                !prefs.getBoolean(DesktopLyricsSettings.LOCKED, false)).apply()); content.addView(lock);
        TextView font = label(activity, activity.getString(R.string.music_lyrics_font_size), 14, ViaUi.TEXT_SUB);
        content.addView(font);
        SeekBar size = new SeekBar(activity); size.setMax(16);
        size.setContentDescription(activity.getString(R.string.music_lyrics_font_size));
        size.setProgress(Math.max(0, Math.min(16, prefs.getInt(DesktopLyricsSettings.FONT, 20) - 16)));
        size.setProgressTintList(ColorStateList.valueOf(ViaUi.ACCENT));
        size.setThumbTintList(ColorStateList.valueOf(ViaUi.ACCENT));
        content.addView(size, new LinearLayout.LayoutParams(-1, ViaUi.dp(activity, 44)));
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                font.setText(activity.getString(R.string.music_lyrics_font_size) + " · " + (value + 16));
                if (user) prefs.edit().putInt(DesktopLyricsSettings.FONT, value + 16).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        content.addView(label(activity, activity.getString(R.string.music_lyrics_color), 14, ViaUi.TEXT_SUB));
        LinearLayout colors = new LinearLayout(activity);
        String[] names = {activity.getString(R.string.music_lyrics_theme_white),
                activity.getString(R.string.music_lyrics_theme_amber), activity.getString(R.string.music_lyrics_theme_teal)};
        ImageView[] dots = new ImageView[3];
        for (int i = 0; i < names.length; i++) {
            int color = i;
            LinearLayout choice = row(activity, names[i]);
            choice.setGravity(Gravity.CENTER);
            choice.setPadding(ViaUi.dp(activity, 4), 0, ViaUi.dp(activity, 4), 0);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2);
            nameParams.setMarginEnd(ViaUi.dp(activity, 8));
            choice.getChildAt(0).setLayoutParams(nameParams);
            dots[i] = ViaUi.switchView(activity, prefs.getInt(DesktopLyricsSettings.COLOR, 0) == i);
            choice.addView(dots[i], new LinearLayout.LayoutParams(ViaUi.dp(activity, 24), ViaUi.dp(activity, 24)));
            choice.setOnClickListener(v -> prefs.edit().putInt(DesktopLyricsSettings.COLOR, color).apply());
            colors.addView(choice, new LinearLayout.LayoutParams(0, ViaUi.dp(activity, 48), 1));
        }
        content.addView(colors);
        ScrollView scroll = new ScrollView(activity); scroll.addView(content);
        AlertDialog dialog = new ViaDialogBuilder(activity).setTitle(R.string.music_desktop_lyrics).setView(scroll)
                .setNeutralButton(R.string.desktop_lyrics_permissions, (d, w) -> activity.startActivity(
                        new Intent(activity, com.example.cleanrecovery.ui.activity.OnboardingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)))
                .setPositiveButton(android.R.string.ok, null).create();
        SharedPreferences.OnSharedPreferenceChangeListener listener = (p, key) -> {
            ViaUi.renderSwitch(enableSwitch, p.getBoolean(DesktopLyricsSettings.ENABLED, false));
            ViaUi.renderSwitch(lockSwitch, p.getBoolean(DesktopLyricsSettings.LOCKED, false));
            for (int i = 0; i < dots.length; i++) ViaUi.renderSwitch(dots[i], p.getInt(DesktopLyricsSettings.COLOR, 0) == i);
        };
        prefs.registerOnSharedPreferenceChangeListener(listener);
        dialog.setOnDismissListener(d -> prefs.unregisterOnSharedPreferenceChangeListener(listener));
        enable.setOnClickListener(v -> {
            if (prefs.getBoolean(DesktopLyricsSettings.ENABLED, false)) {
                prefs.edit().putBoolean(DesktopLyricsSettings.ENABLED, false).apply();
            } else if (Settings.canDrawOverlays(activity)) enable(activity);
            else if (!overlay) {
                dialog.dismiss();
                new ViaDialogBuilder(activity).setTitle(R.string.music_desktop_lyrics).setMessage(R.string.desktop_lyrics_permission)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.desktop_lyrics_authorize, (d, which) -> {
                            try {
                                ((Activity) activity).startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + activity.getPackageName())), PERMISSION_REQUEST);
                            } catch (android.content.ActivityNotFoundException error) {
                                GlassToast.makeText(activity, R.string.desktop_lyrics_unavailable, GlassToast.LENGTH_LONG).show();
                            }
                        }).show();
            }
        });
        if (overlay) {
            dialog.getWindow().setType(android.os.Build.VERSION.SDK_INT >= 26
                    ? android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : android.view.WindowManager.LayoutParams.TYPE_PHONE);
        }
        dialog.show();
        return dialog;
    }
    private static TextView label(android.content.Context a, String text, int sp, int color) {
        TextView view = new TextView(a); view.setText(text); view.setTextSize(sp);
        view.setTextColor(ViaUi.textColor(a, color)); return view;
    }
    private static LinearLayout row(android.content.Context a, String text) {
        LinearLayout row = new LinearLayout(a); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ViaUi.dp(a, 48)); row.setBackgroundResource(R.drawable.bg_via_menu_cell);
        row.addView(label(a, text, 15, ViaUi.TEXT), new LinearLayout.LayoutParams(0, -2, 1)); return row;
    }
    public static void permissionReturned(Activity activity) {
        if (Settings.canDrawOverlays(activity)) enable(activity);
        else GlassToast.makeText(activity, R.string.desktop_lyrics_denied, GlassToast.LENGTH_LONG).show();
        show(activity);
    }
    private static void enable(android.content.Context activity) {
        SharedPreferences prefs = DesktopLyricsSettings.get(activity);
        prefs.edit().putBoolean(DesktopLyricsSettings.ENABLED, true).apply();
        if (MusicPlayer.get().currentSong() == null) return;
        try { activity.startService(new Intent(activity, MusicService.class)); }
        catch (RuntimeException error) {
            prefs.edit().putBoolean(DesktopLyricsSettings.ENABLED, false).apply();
            GlassToast.makeText(activity, R.string.desktop_lyrics_unavailable, GlassToast.LENGTH_LONG).show();
        }
    }
}
