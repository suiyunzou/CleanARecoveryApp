package com.example.cleanrecovery.ui.activity;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.recovery.RecoveryOutputPaths;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;


import com.example.cleanrecovery.music.ui.MusicHomeActivity;


/**
 * 设置页（原型 A5）：扫描 / 恢复 / 工具 / 关于 四组。
 * 偏好存储在 SharedPreferences("ui_settings")，供 MainActivity 读取。
 */
public final class AboutActivity extends Activity {
    public static final String PREFS_NAME = "ui_settings";
    public static final String KEY_DEFAULT_DEEP = "default_scan_deep";
    public static final String KEY_DEEP_HINT = "deep_scan_hint";
    public static final String KEY_KEEP_NAMES = "keep_original_names";

    private SharedPreferences prefs;
    private TextView scanModeValue;
    private androidx.appcompat.widget.SwitchCompat deepHintSwitch;
    private androidx.appcompat.widget.SwitchCompat keepNamesSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_about);
        findViewById(R.id.settings_about_row).setOnClickListener(v -> startActivity(new Intent(this, AppAboutActivity.class)));
        com.example.cleanrecovery.ui.widget.AppBottomNavBinder.bind(this,
                com.example.cleanrecovery.ui.widget.AppBottomNavBinder.Tab.SETTINGS);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);


        String version = "0.1.0";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (info.versionName != null) {
                version = info.versionName;
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        ((TextView) findViewById(R.id.about_version)).setText(
                getString(R.string.about_version, version));

        scanModeValue = findViewById(R.id.settings_scan_mode_value);
        deepHintSwitch = findViewById(R.id.settings_deep_hint_switch);
        keepNamesSwitch = findViewById(R.id.settings_keep_names_switch);
        TextView outputDirValue = findViewById(R.id.settings_output_dir_value);
        outputDirValue.setText(RecoveryOutputPaths.primaryDisplayPath());
        refreshValues();

        findViewById(R.id.settings_scan_mode).setOnClickListener(v -> {
            boolean toDeep = !prefs.getBoolean(KEY_DEFAULT_DEEP, false);
            prefs.edit().putBoolean(KEY_DEFAULT_DEEP, toDeep).apply();
            refreshValues();
            toast(toDeep ? R.string.scan_mode_deep : R.string.scan_mode_quick);
        });
        // 拨动开关为状态源：行点击等价于拨开关，持久化统一走监听器
        deepHintSwitch.setChecked(prefs.getBoolean(KEY_DEEP_HINT, true));
        deepHintSwitch.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(KEY_DEEP_HINT, checked).apply());
        findViewById(R.id.settings_deep_hint).setOnClickListener(v -> deepHintSwitch.toggle());
        keepNamesSwitch.setChecked(prefs.getBoolean(KEY_KEEP_NAMES, true));
        keepNamesSwitch.setOnCheckedChangeListener((button, checked) ->
                prefs.edit().putBoolean(KEY_KEEP_NAMES, checked).apply());
        findViewById(R.id.settings_keep_names).setOnClickListener(v -> keepNamesSwitch.toggle());
        findViewById(R.id.settings_output_dir).setOnClickListener(v -> copyOutputPath());

        // 启动后显示：设置开屏内容（音乐/在线观影/全网下载），冷启动生效
        refreshStartupValue();
        findViewById(R.id.settings_startup_row).setOnClickListener(v -> showStartupPicker());

        View openMusic = findViewById(R.id.about_music_card);
        openMusic.setOnClickListener(view ->
                startActivity(new Intent(AboutActivity.this, MusicHomeActivity.class)));
        View openOnlineMovie = findViewById(R.id.about_online_movie_card);
        openOnlineMovie.setOnClickListener(view ->
                startActivity(new Intent(AboutActivity.this, BrowserActivity.class)));
        View openDownload = findViewById(R.id.about_universal_download_card);
        openDownload.setOnClickListener(view ->
                startActivity(new Intent(AboutActivity.this, UniversalDownloadActivity.class)));


    }

    private void refreshValues() {
        boolean deep = prefs.getBoolean(KEY_DEFAULT_DEEP, false);
        scanModeValue.setText(deep ? R.string.scan_mode_deep : R.string.scan_mode_quick);
    }

    private void refreshStartupValue() {
        ((TextView) findViewById(R.id.settings_startup_value))
                .setText(AppStartup.labelOf(this, AppStartup.module(this)));
    }

    /** 启动后显示单选（保存后下次冷启动生效）。 */
    private void showStartupPicker() {
        AppStartup.showPicker(this, false, this::refreshStartupValue);
    }

    private void copyOutputPath() {
        String path = RecoveryOutputPaths.primaryDisplayPath();
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("path", path));
        GlassToast.makeText(this, R.string.settings_path_copied, GlassToast.LENGTH_SHORT).show();
    }

    private void toast(int resId) {
        GlassToast.makeText(this, resId, GlassToast.LENGTH_SHORT).show();
    }

}
