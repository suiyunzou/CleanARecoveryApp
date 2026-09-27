package com.example.cleanrecovery.ui.activity;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.example.cleanrecovery.R;
import com.example.cleanrecovery.scan.ScanHistoryStore;
import com.example.cleanrecovery.ui.browser.ViaUi;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;
import java.util.ArrayList;
import java.util.List;

/** First-run opt-in permission tour. Rechecks grants and never retries a denied step automatically. */
public abstract class PermissionSetupActivity extends Activity {
    private static final int RUNTIME = 4201, SPECIAL = 4202;
    private final boolean[] selected = {true, true, true, false, false, false, false};
    private final String[] titles = {"通知", "文件访问", "桌面歌词", "相机", "麦克风", "位置", "安装更新"};
    private final String[] reasons = {"音乐控制、下载进度与提醒", "扫描、恢复和保存文件", "在其他应用上方显示歌词",
            "扫码、自拍与挥手快门", "声波实验、挥手快门与网页录音", "网页定位", "从应用内安装下载的更新"};
    private ImageView permissionIcon;
    private TextView action, status, skip;
    private int stage;
    private boolean busy, completed, allReady;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state); SystemUiHelper.apply(this);
        if (state != null) {
            boolean[] saved = state.getBooleanArray("selected");
            if (saved != null && saved.length == selected.length) System.arraycopy(saved, 0, selected, 0, selected.length);
            stage = state.getInt("stage"); busy = state.getBoolean("busy"); completed = state.getBoolean("completed");
        }
        LinearLayout page = com.example.cleanrecovery.ui.browser.ViaBottomSheet.content(this);
        page.setPadding(ViaUi.dp(this, 24), ViaUi.dp(this, 16), ViaUi.dp(this, 24), ViaUi.dp(this, 20));
        LinearLayout.LayoutParams handleParams = (LinearLayout.LayoutParams) page.getChildAt(0).getLayoutParams();
        handleParams.bottomMargin = ViaUi.dp(this, 16);
        page.getChildAt(0).setLayoutParams(handleParams);
        LinearLayout titleRow = new LinearLayout(this); titleRow.setGravity(Gravity.CENTER_VERTICAL);
        permissionIcon = new ImageView(this); permissionIcon.setColorFilter(ViaUi.ACCENT);
        permissionIcon.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ViaUi.dp(this, 24), ViaUi.dp(this, 24));
        iconParams.setMarginEnd(ViaUi.dp(this, 12)); titleRow.addView(permissionIcon, iconParams);
        heading = text("权限设置", 20, ViaUi.TEXT);
        heading.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        titleRow.addView(heading, new LinearLayout.LayoutParams(0, -2, 1)); page.addView(titleRow);
        intro = text("", 14, ViaUi.TEXT_SUB); intro.setTextColor(0xFF888888); intro.setPadding(0, ViaUi.dp(this, 8), 0, ViaUi.dp(this, 4)); page.addView(intro);
        status = text("", 12, ViaUi.TEXT_SUB); status.setTextColor(0xFF888888); status.setPadding(0, ViaUi.dp(this, 8), 0, ViaUi.dp(this, 12)); page.addView(status);
        action = com.example.cleanrecovery.ui.browser.ViaBottomSheet.button(this, "开始授权", true);
        action.setOnClickListener(v -> {
            if (busy) return;
            if (completed || allReady) { finishSetup(); return; }
            stage = 0; busy = true; advance();
        }); page.addView(action, new LinearLayout.LayoutParams(-1, -2));
        skip = com.example.cleanrecovery.ui.browser.ViaBottomSheet.outlineButton(this, "暂不开启");
        skip.setOnClickListener(v -> { if (!busy) finishSetup(); });
        LinearLayout.LayoutParams skipParams = new LinearLayout.LayoutParams(-1, -2);
        skipParams.topMargin = ViaUi.dp(this, 8); page.addView(skip, skipParams);
        setContentView(com.example.cleanrecovery.ui.browser.ViaBottomSheet.scroll(this, page, .5f));
        com.example.cleanrecovery.ui.browser.ViaBottomSheet.configure(this, getWindow()); render();
    }

    private TextView heading, intro;
    private TextView text(String value, int size, int color) {
        return com.example.cleanrecovery.ui.browser.ViaBottomSheet.text(this, value, size, color);
    }
    private void render() {
        allReady = granted(0) && granted(1) && granted(2);
        int option = stage <= 1 ? 0 : stage == 2 ? 1 : stage == 3 ? 2 : 6;
        heading.setText(busy ? titles[option] : completed ? "权限设置完成" : "权限设置");
        intro.setText(busy ? reasons[option] : completed ? "已开启的权限会保留。暂未开启的功能，可以在使用时再设置。"
                : "允许通知、文件访问与桌面歌词。点击一次开始，返回后自动继续下一项。");
        if (!busy && !completed) {
            int first = -1;
            for (int i = 0; i < 3; i++) if (selected[i] && !granted(i)) { first = i; break; }
            option = first;
            heading.setText(first < 0 ? "权限已就绪" : "开启" + titles[first]);
            intro.setText(first < 0 ? "常用功能所需的权限已经开启，可以直接继续。" : reasons[first]);
        }
        permissionIcon.setImageResource(allReady ? R.drawable.ic_check_small
                : option == 0 ? R.drawable.ic_more_bell_filled
                : option == 1 ? R.drawable.ic_via_library_folder
                : option == 2 ? R.drawable.ic_more_desktop_lyrics : R.drawable.ic_settings_gear);
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            if (i > 0) summary.append("  ·  ");
            summary.append(titles[i]).append(granted(i) ? " ✓" : " 待授权");
        }
        status.setText(busy ? "请在系统页面确认，返回后继续" : summary.toString());
        action.setText(completed || allReady ? "完成" : busy ? "等待系统授权" : "开始授权");
        action.setEnabled(!busy); action.setAlpha(busy ? .55f : 1f); skip.setEnabled(!busy);
        skip.setVisibility(completed || allReady ? android.view.View.GONE : android.view.View.VISIBLE);
    }
    private boolean allowed(String permission) { return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED; }
    private boolean granted(int option) {
        switch (option) {
            case 0: return Build.VERSION.SDK_INT < 33 || allowed(Manifest.permission.POST_NOTIFICATIONS);
            case 1: return Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager() : allowed(Manifest.permission.READ_EXTERNAL_STORAGE);
            case 2: return Settings.canDrawOverlays(this);
            case 3: return allowed(Manifest.permission.CAMERA);
            case 4: return allowed(Manifest.permission.RECORD_AUDIO);
            case 5: return allowed(Manifest.permission.ACCESS_COARSE_LOCATION);
            case 6: return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
            default: return false;
        }
    }
    private void addMissing(List<String> permissions, String permission) { if (!allowed(permission)) permissions.add(permission); }
    private void advance() {
        // Save the next stage before opening a system page; rotation must not repeat the request.
        while (stage < 4) {
            int current = stage++;
            render();
            if (current == 0) {
                List<String> permissions = new ArrayList<>();
                if (selected[0] && Build.VERSION.SDK_INT >= 33) addMissing(permissions, Manifest.permission.POST_NOTIFICATIONS);
                if (selected[1] && Build.VERSION.SDK_INT < 30) {
                    addMissing(permissions, Manifest.permission.READ_EXTERNAL_STORAGE);
                    addMissing(permissions, Manifest.permission.WRITE_EXTERNAL_STORAGE);
                }
                if (selected[3]) addMissing(permissions, Manifest.permission.CAMERA);
                if (selected[4]) addMissing(permissions, Manifest.permission.RECORD_AUDIO);
                if (selected[5] && !granted(5)) {
                    addMissing(permissions, Manifest.permission.ACCESS_COARSE_LOCATION);
                    addMissing(permissions, Manifest.permission.ACCESS_FINE_LOCATION);
                }
                if (!permissions.isEmpty()) { requestPermissions(permissions.toArray(new String[0]), RUNTIME); return; }
            } else {
                int option = current == 1 ? 1 : current == 2 ? 2 : 6;
                if (!selected[option] || granted(option)) continue;
                String setting = option == 1 ? Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                        : option == 2 ? Settings.ACTION_MANAGE_OVERLAY_PERMISSION : Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES;
                try {
                    startActivityForResult(new Intent(setting, Uri.parse("package:" + getPackageName())), SPECIAL); return;
                } catch (android.content.ActivityNotFoundException ignored) {
                    try {
                        startActivityForResult(new Intent(option == 1 ? Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION : setting), SPECIAL); return;
                    } catch (android.content.ActivityNotFoundException unavailable) { /* Continue remaining choices. */ }
                }
            }
        }
        busy = false; completed = true; render();
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == RUNTIME && busy) { render(); advance(); }
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == SPECIAL && busy) { render(); advance(); }
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putBooleanArray("selected", selected); out.putInt("stage", stage);
        out.putBoolean("busy", busy); out.putBoolean("completed", completed); super.onSaveInstanceState(out);
    }
    private void finishSetup() { ScanHistoryStore.setOnboardingComplete(this); setResult(RESULT_OK); finish(); }
    @Override public void onBackPressed() { if (!busy) finishSetup(); }
}
