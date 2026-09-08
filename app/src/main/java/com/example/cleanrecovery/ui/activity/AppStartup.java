package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.ui.MusicHomeActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用开屏内容（启动后显示）：恢复主界面（默认）/ 音乐播放器 / 在线观影 / 全网下载。
 * <ul>
 *   <li>冷启动路由：所选模块非主界面时立即跳转，主界面保留在返回栈底（模块内返回=回主界面）；</li>
 *   <li>首启引导：第一次启动弹一次四选卡片，之后可在 设置→启动后显示 修改。</li>
 * </ul>
 */
public final class AppStartup {
    public static final String MODULE_HOME = "home";
    public static final String MODULE_MUSIC = "music";
    public static final String MODULE_BROWSER = "browser";
    public static final String MODULE_DOWNLOAD = "download";

    private static final String PREFS = "app_startup";
    private static final String KEY_MODULE = "module";
    private static final String KEY_CHOICE_MADE = "choice_made";

    private AppStartup() {
    }

    public static String module(Context c) {
        return prefs(c).getString(KEY_MODULE, MODULE_HOME);
    }

    public static void setModule(Context c, String module) {
        prefs(c).edit().putString(KEY_MODULE, module).apply();
    }

    public static boolean choiceMade(Context c) {
        return prefs(c).getBoolean(KEY_CHOICE_MADE, false);
    }

    public static void markChoiceMade(Context c) {
        prefs(c).edit().putBoolean(KEY_CHOICE_MADE, true).apply();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String labelOf(Context c, String module) {
        if (MODULE_BROWSER.equals(module)) return c.getString(R.string.settings_label_online);
        if (MODULE_DOWNLOAD.equals(module)) return c.getString(R.string.settings_label_download);
        if (MODULE_MUSIC.equals(module)) return c.getString(R.string.settings_label_music);
        return c.getString(R.string.settings_label_home);
    }

    /** 首启一次引导：卡片式四选，选择即记忆并立即进入所选模块。 */
    public static void maybeShowFirstRunChoice(final Activity activity) {
        showPicker(activity, true, null);
    }

    /** 设置页入口：卡片式四选（当前项高亮），选择即记忆。onSelected 供页面刷新文案。 */
    public static void showPicker(final Activity activity, final boolean firstRun,
            final Runnable onSelected) {
        String[] modules = {MODULE_HOME, MODULE_MUSIC, MODULE_BROWSER, MODULE_DOWNLOAD};
        int[] icons = {R.drawable.ic_experimental_recovery, R.drawable.ic_audio_bars,
                R.drawable.ic_play_outline, R.drawable.ic_download};
        int[] descs = {R.string.startup_desc_home, R.string.startup_desc_music,
                R.string.startup_desc_browser, R.string.startup_desc_download};
        String current = module(activity);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(activity, 24);
        root.setPadding(pad, dp(activity, 20), pad, dp(activity, 10));

        TextView title = new TextView(activity);
        title.setText(R.string.startup_first_title);
        title.setTextSize(17);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setTextColor(activity.getColor(R.color.text_primary));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (firstRun) {
            TextView hint = new TextView(activity);
            hint.setText(R.string.startup_first_hint);
            hint.setTextSize(12);
            hint.setTextColor(activity.getColor(R.color.text_hint));
            hint.setPadding(0, dp(activity, 6), 0, 0);
            root.addView(hint, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(activity, 10), 0, 0);
        final List<android.widget.CheckBox> checks = new ArrayList<>();
        final AlertDialog[] holder = new AlertDialog[1];

        for (int i = 0; i < modules.length; i++) {
            final String module = modules[i];
            final boolean selected = module.equals(current);

            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setFocusable(true);
            TypedValue ripple = new TypedValue();
            activity.getTheme().resolveAttribute(
                    android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            int vPad = dp(activity, 12);
            row.setPadding(0, vPad, 0, vPad);

            ImageView icon = new ImageView(activity);
            icon.setImageResource(icons[i]);
            icon.setColorFilter(activity.getColor(
                    selected ? R.color.brand_primary : R.color.text_secondary));
            row.addView(icon, new LinearLayout.LayoutParams(
                    dp(activity, 22), dp(activity, 22)));

            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.setPadding(dp(activity, 14), 0, 0, 0);
            TextView label = new TextView(activity);
            label.setText(labelOf(activity, module));
            label.setTextSize(15);
            label.setTextColor(activity.getColor(R.color.text_primary));
            label.setTypeface(label.getTypeface(),
                    selected ? Typeface.BOLD : Typeface.NORMAL);
            texts.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView desc = new TextView(activity);
            desc.setText(descs[i]);
            desc.setTextSize(11.5f);
            desc.setTextColor(activity.getColor(R.color.text_hint));
            desc.setPadding(0, dp(activity, 2), 0, 0);
            texts.addView(desc, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            row.addView(texts, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            android.widget.CheckBox check = new android.widget.CheckBox(activity);
            check.setButtonDrawable(R.drawable.sel_check_circle);
            check.setClickable(false);
            check.setFocusable(false);
            check.setChecked(selected);
            checks.add(check);
            row.addView(check, new LinearLayout.LayoutParams(
                    dp(activity, 24), dp(activity, 24)));

            row.setOnClickListener(v -> {
                AppStartup.setModule(activity, module);
                AppStartup.markChoiceMade(activity);
                for (android.widget.CheckBox other : checks) other.setChecked(false);
                check.setChecked(true);
                GlassToast.makeText(activity, activity.getString(R.string.settings_startup_row)
                        + ": " + labelOf(activity, module), GlassToast.LENGTH_SHORT).show();
                if (holder[0] != null) holder[0].dismiss();
                if (onSelected != null) onSelected.run();
                if (firstRun) routeNow(activity, module);
            });
            rows.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        root.addView(rows, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton(firstRun
                        ? R.string.startup_pick_later
                        : android.R.string.cancel, null);
        if (firstRun) builder.setCancelable(false);
        holder[0] = builder.show();
    }

    /** 冷启动路由：恢复主界面为默认（不跳转），其余模块直达。主界面保留在返回栈底。 */
    public static void routeIfConfigured(Activity activity) {
        routeNow(activity, module(activity));
    }

    private static void routeNow(Activity activity, String module) {
        if (MODULE_BROWSER.equals(module)) {
            activity.startActivity(new Intent(activity, BrowserActivity.class));
            activity.overridePendingTransition(0, 0);
        } else if (MODULE_DOWNLOAD.equals(module)) {
            activity.startActivity(new Intent(activity, UniversalDownloadActivity.class));
            activity.overridePendingTransition(0, 0);
        } else if (MODULE_MUSIC.equals(module)) {
            activity.startActivity(new Intent(activity, MusicHomeActivity.class));
            activity.overridePendingTransition(0, 0);
        }
        // MODULE_HOME：留在恢复主界面，无需跳转
    }

    private static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
