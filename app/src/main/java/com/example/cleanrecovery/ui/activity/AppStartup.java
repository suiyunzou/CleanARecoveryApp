package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.music.ui.MusicHomeActivity;
import com.example.cleanrecovery.ui.browser.ViaBottomSheet;
import com.example.cleanrecovery.ui.browser.ViaUi;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Default launch destination and explicit switching share one stable module catalog. */
public final class AppStartup {
    public static final String MODULE_HOME = "home", MODULE_MUSIC = "music", MODULE_BROWSER = "browser", MODULE_DOWNLOAD = "download";
    private static final String[] MODULES = {MODULE_HOME, MODULE_MUSIC, MODULE_BROWSER, MODULE_DOWNLOAD};
    private static final int[] ICONS = {R.drawable.ic_experimental_recovery, R.drawable.ic_audio_bars, R.drawable.ic_via_globe, R.drawable.ic_download};
    private static final String[] NAMES = {"文件恢复", "音乐", "浏览器", "全网下载"};
    private static final String[] DESCRIPTIONS = {"扫描文件、查看结果与恢复记录", "听音乐、管理歌单与桌面歌词", "浏览网页与在线观看视频", "解析链接、保存音视频"};
    private AppStartup() {}
    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("app_startup", Context.MODE_PRIVATE); }
    public static String module(Context c) { String value = prefs(c).getString("module", MODULE_HOME); return Arrays.asList(MODULES).contains(value) ? value : MODULE_HOME; }
    public static void setModule(Context c, String module) { prefs(c).edit().putString("module", module).apply(); }
    public static boolean choiceMade(Context c) { return prefs(c).getBoolean("choice_made", false); }
    public static void markChoiceMade(Context c) { prefs(c).edit().putBoolean("choice_made", true).apply(); }
    public static String labelOf(Context c, String module) { int index = Arrays.asList(MODULES).indexOf(module); return NAMES[Math.max(0, index)]; }
    public static void maybeShowFirstRunChoice(Activity a) { if (!choiceMade(a)) showPicker(a, true, null); }
    public static void showPicker(Activity a, boolean firstRun, Runnable selected) { show(a, firstRun ? 0 : 1, selected, false); }
    public static void showSwitcher(Activity a) { show(a, 2, null, false); }
    private static String current(Activity a) {
        if (a instanceof MusicHomeActivity) return MODULE_MUSIC;
        if (a instanceof BrowserActivity) return MODULE_BROWSER;
        if (a instanceof UniversalDownloadActivity) return MODULE_DOWNLOAD;
        return MODULE_HOME;
    }
    private static List<String> order(Context c) {
        List<String> values = new ArrayList<>();
        for (String item : prefs(c).getString("module_order", "").split(","))
            if (Arrays.asList(MODULES).contains(item) && !values.contains(item)) values.add(item);
        for (String item : MODULES) if (!values.contains(item)) values.add(item);
        return values;
    }
    // mode: first launch, change default, switch without changing default.
    private static void show(Activity a, int mode, Runnable selected, boolean editing) {
        LinearLayout card = ViaBottomSheet.content(a);
        card.setPadding(ViaUi.dp(a, 24), ViaUi.dp(a, 16), ViaUi.dp(a, 24), ViaUi.dp(a, 20));
        LinearLayout.LayoutParams handle = (LinearLayout.LayoutParams) card.getChildAt(0).getLayoutParams();
        handle.bottomMargin = ViaUi.dp(a, 12); card.getChildAt(0).setLayoutParams(handle);
        TextView title = ViaBottomSheet.text(a, mode == 0 ? "从哪里开始" : mode == 1 ? "启动后打开" : editing ? "整理常用功能" : "切换功能", 20, ViaUi.TEXT);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); if (android.os.Build.VERSION.SDK_INT >= 28) title.setAccessibilityHeading(true); card.addView(title);
        TextView hint = ViaBottomSheet.text(a, mode == 0 ? "选择一个入口，之后也能随时切换。" : mode == 1 ? "下次打开应用时直接进入。" : editing ? "星标置顶，箭头调整组内顺序。" : "切换功能时保留当前页面。", 13, ViaUi.TEXT_SUB);
        hint.setTextColor(0xFF888888); hint.setPadding(0, ViaUi.dp(a, 4), 0, ViaUi.dp(a, 8)); card.addView(hint);
        final Dialog[] holder = new Dialog[1];
        boolean[] remember = {true};
        List<String> order = order(a);
        Set<String> favorites = new HashSet<>(prefs(a).getStringSet("favorites", new HashSet<>()));
        order.sort((x, y) -> Boolean.compare(favorites.contains(y), favorites.contains(x)));
        for (String value : order) {
            int i = Arrays.asList(MODULES).indexOf(value);
            LinearLayout row = new LinearLayout(a); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(ViaUi.dp(a, 48)); row.setPadding(0, ViaUi.dp(a, 2), 0, ViaUi.dp(a, 2));
            row.setBackgroundResource(R.drawable.bg_via_menu_cell);
            ImageView icon = new ImageView(a); icon.setImageResource(ICONS[i]); icon.setColorFilter(ViaUi.ACCENT);
            row.addView(icon, new LinearLayout.LayoutParams(ViaUi.dp(a, 24), ViaUi.dp(a, 24)));
            LinearLayout labels = new LinearLayout(a); labels.setOrientation(1); labels.setPadding(ViaUi.dp(a, 16), 0, 0, 0);
            TextView name = ViaBottomSheet.text(a, NAMES[i], 16, ViaUi.TEXT); name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); labels.addView(name);
            String detail = editing ? (favorites.contains(value) ? "已收藏" : "未收藏") : mode == 2 && value.equals(current(a)) ? "正在使用" : DESCRIPTIONS[i];
            TextView description = ViaBottomSheet.text(a, detail, 12, ViaUi.TEXT_SUB); description.setPadding(0, ViaUi.dp(a, 4), 0, 0); if (editing) labels.addView(description);
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            if (editing) {
                TextView star = smallAction(a, favorites.contains(value) ? "★" : "☆", "收藏" + NAMES[i]);
                star.setOnClickListener(v -> { if (!favorites.remove(value)) favorites.add(value); prefs(a).edit().putStringSet("favorites", favorites).apply(); holder[0].dismiss(); show(a, mode, selected, true); }); row.addView(star);
                TextView up = smallAction(a, "↑", "上移" + NAMES[i]); int index = order.indexOf(value);
                up.setEnabled(index > 0 && favorites.contains(value) == favorites.contains(order.get(Math.max(0, index - 1)))); up.setAlpha(up.isEnabled() ? 1f : .3f);
                up.setOnClickListener(v -> { java.util.Collections.swap(order, index, index - 1); prefs(a).edit().putString("module_order", android.text.TextUtils.join(",", order)).apply(); holder[0].dismiss(); show(a, mode, selected, true); }); row.addView(up);
            } else {
                boolean active = mode == 2 ? value.equals(current(a)) : value.equals(module(a));
                TextView mark = smallAction(a, active ? "✓" : "›", active ? "已选择" + NAMES[i] : NAMES[i]);
                if (mode == 2 && active) {
                    TextView state = ViaBottomSheet.text(a, "当前", 12, ViaUi.TEXT_SUB); state.setTextColor(0xFF888888); row.addView(state);
                } mark.setClickable(false); mark.setFocusable(false); row.addView(mark);
                row.setOnClickListener(v -> {
                    if (mode != 2) { if (mode == 1 || remember[0]) setModule(a, value); markChoiceMade(a); }
                    holder[0].dismiss(); if (selected != null) selected.run(); if (mode != 1) routeNow(a, value);
                });
            }
            card.addView(row);
            if (!value.equals(order.get(order.size() - 1))) {
                View divider = new View(a); divider.setBackgroundColor(0x18888888);
                card.addView(divider, new LinearLayout.LayoutParams(-1, 1));
            }
        }
        if (mode == 0) {
            LinearLayout choice = new LinearLayout(a); choice.setGravity(Gravity.CENTER_VERTICAL); choice.setMinimumHeight(ViaUi.dp(a, 48));
            TextView toggle = ViaBottomSheet.text(a, "✓", 15, ViaUi.ACCENT); toggle.setGravity(Gravity.CENTER);
            android.graphics.drawable.GradientDrawable check = new android.graphics.drawable.GradientDrawable();
            check.setColor(android.graphics.Color.TRANSPARENT); check.setCornerRadius(ViaUi.dp(a, 5)); check.setStroke(ViaUi.dp(a, 1), ViaUi.ACCENT); toggle.setBackground(check);
            choice.addView(toggle, new LinearLayout.LayoutParams(ViaUi.dp(a, 22), ViaUi.dp(a, 22)));
            TextView label = ViaBottomSheet.text(a, "下次直接进入所选功能", 14, ViaUi.TEXT_SUB); label.setPadding(ViaUi.dp(a, 8), 0, 0, 0); choice.addView(label);
            choice.setOnClickListener(v -> { remember[0] = !remember[0]; toggle.setText(remember[0] ? "✓" : ""); }); card.addView(choice);
        }
        if (mode == 2) {
            LinearLayout actions = new LinearLayout(a); actions.setGravity(Gravity.CENTER_VERTICAL);
            TextView edit = ViaBottomSheet.button(a, editing ? "完成整理" : "整理常用功能", false);
            edit.setOnClickListener(v -> { holder[0].dismiss(); show(a, mode, selected, !editing); }); actions.addView(edit, new LinearLayout.LayoutParams(0, -2, 1));
            if (!editing) { TextView launch = ViaBottomSheet.button(a, "启动 · " + labelOf(a, module(a)), false);
                launch.setOnClickListener(v -> { holder[0].dismiss(); showPicker(a, false, null); }); actions.addView(launch, new LinearLayout.LayoutParams(0, -2, 1)); }
            card.addView(actions);
        }
        TextView close = ViaBottomSheet.outlineButton(a, mode == 0 ? "先使用文件恢复" : "关闭");
        close.setOnClickListener(v -> holder[0].dismiss()); card.addView(close);
        holder[0] = ViaBottomSheet.show(a, card, .5f);
        if (mode == 0) holder[0].setOnDismissListener(d -> { if (!a.isChangingConfigurations()) markChoiceMade(a); });
    }
    private static TextView smallAction(Activity a, String value, String description) {
        TextView t = ViaBottomSheet.button(a, value, false); t.setContentDescription(description);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViaUi.dp(a, 48), ViaUi.dp(a, 48))); return t;
    }
    public static void routeIfConfigured(Activity a) { routeNow(a, module(a)); }
    private static void routeNow(Activity a, String module) {
        Class<?> target = MODULE_MUSIC.equals(module) ? MusicHomeActivity.class : MODULE_BROWSER.equals(module) ? BrowserActivity.class : MODULE_DOWNLOAD.equals(module) ? UniversalDownloadActivity.class : MainActivity.class;
        if (a.getClass() == target) return;
        // Reuse live Activities so scan results, folder position and browser tabs survive switches.
        a.startActivity(new Intent(a, target).putExtra("module_switch", true).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }
    private static void addButton(Activity a, LinearLayout row) {
        ImageView button = new ImageView(a); button.setImageResource(R.drawable.ic_module_switch);
        button.setColorFilter(ViaUi.textColor(a, ViaUi.TEXT)); button.setContentDescription("切换功能");
        button.setPadding(ViaUi.dp(a, 13), ViaUi.dp(a, 13), ViaUi.dp(a, 13), ViaUi.dp(a, 13));
        button.setBackgroundResource(R.drawable.bg_via_menu_cell); button.setFocusable(true);
        button.setOnClickListener(v -> showSwitcher(a)); row.addView(button, new LinearLayout.LayoutParams(ViaUi.dp(a, 48), ViaUi.dp(a, 48)));
    }
    public static void initialize(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private final java.util.Set<Activity> attached = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
            @Override public void onActivityStarted(Activity a) {
                if (!attached.add(a)) return;
                if (a instanceof MainActivity) {
                    LinearLayout root = (LinearLayout) ((ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
                    LinearLayout bar = new LinearLayout(a); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(ViaUi.dp(a, 20), 0, ViaUi.dp(a, 8), 0);
                    TextView title = ViaBottomSheet.text(a, "文件恢复", 18, ViaUi.TEXT); title.setTypeface(null, android.graphics.Typeface.BOLD);
                    bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); addButton(a, bar); root.addView(bar, 0);
                } else if (a instanceof MusicHomeActivity) addButton(a, (LinearLayout) a.findViewById(R.id.music_title).getParent());
                else if (a instanceof UniversalDownloadActivity) addButton(a, (LinearLayout) a.findViewById(R.id.universal_back_button).getParent());
                else if (a instanceof BrowserActivity) {
                    addButton(a, (LinearLayout) a.findViewById(R.id.browser_home_title).getParent());
                    addButton(a, (LinearLayout) a.findViewById(R.id.browser_page_title).getParent());
                }
            }
            @Override public void onActivityCreated(Activity a, Bundle state) {}
            @Override public void onActivityResumed(Activity a) {}
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle state) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
}
