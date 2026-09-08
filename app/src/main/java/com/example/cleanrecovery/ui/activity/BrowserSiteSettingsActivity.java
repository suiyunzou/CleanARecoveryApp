package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.ViaUi;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

/** VIA 风格：按当前 host 保存的网站设定。 */
public final class BrowserSiteSettingsActivity extends Activity {
    public static final String EXTRA_HOST = "host";
    public static final String EXTRA_INCOGNITO_CHANGED = "incognito_changed";
    public static final String EXTRA_OPEN_FONT = "open_font";

    private static final int MODE_DEFAULT = -1;
    private static final int MODE_OFF = 0;
    private static final int MODE_ON = 1;

    private BrowserPrefs prefs;
    private String host;
    private LinearLayout list;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        SystemUiHelper.apply(this);
        prefs = new BrowserPrefs(this);
        host = getIntent().getStringExtra(EXTRA_HOST);
        if (host == null) host = "";
        build();
        render();
    }

    private void build() {
        boolean night=prefs.nightMode();
        if(night) {
            getWindow().setStatusBarColor(android.graphics.Color.BLACK);
            getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        }
        androidx.core.view.WindowInsetsControllerCompat bars=new androidx.core.view.WindowInsetsControllerCompat(getWindow(),getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(!night);
        bars.setAppearanceLightNavigationBars(!night);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(night?android.graphics.Color.BLACK:android.graphics.Color.WHITE);
        root.setPadding(0, statusBarHeight(), 0, 0);
        setContentView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 8, 0);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(56)));

        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(night?ViaUi.textColor(this,ViaUi.TEXT):getColor(R.color.text_primary));
        back.setBackgroundResource(R.drawable.bg_via_toolbar_button);
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setOnClickListener(v -> finishOk(false));
        top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView title = new TextView(this);
        title.setText("网站设定");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setTextColor(night?ViaUi.textColor(this,ViaUi.TEXT):getColor(R.color.text_primary));
        top.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        TextView reset = new TextView(this);
        reset.setText("重置");
        reset.setTextSize(15);
        reset.setGravity(Gravity.CENTER);
        reset.setTextColor(night?ViaUi.textColor(this,ViaUi.TEXT_SUB):getColor(R.color.text_secondary));
        reset.setOnClickListener(v -> {
            android.webkit.GeolocationPermissions.getInstance().clearAll();
            prefs.resetSiteSettings(host);
            render();
            GlassToast.makeText(this, R.string.via_site_reset_done, GlassToast.LENGTH_SHORT).show();
        });
        top.addView(reset, new LinearLayout.LayoutParams(dp(64), -1));

        View line = new View(this);
        line.setBackgroundColor(night?0xff333333:0xffeeeeee);
        root.addView(line, new LinearLayout.LayoutParams(-1, 1));

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void render() {
        list.removeAllViews();
        boolean enabled = prefs.siteSettingsEnabled(host);

        LinearLayout enable = new LinearLayout(this);
        enable.setGravity(Gravity.CENTER_VERTICAL);
        enable.setPadding(dp(4), dp(12), dp(12), dp(12));
        TextView label = new TextView(this);
        label.setText("启用 \"" + host + "\" 的网站设定");
        label.setTextSize(15);
        label.setTextColor(prefs.nightMode()?ViaUi.textColor(this,ViaUi.TEXT):getColor(R.color.text_primary));
        enable.addView(label, new LinearLayout.LayoutParams(0, dp(44), 1));
        Switch enableSwitch = new Switch(this);
        enableSwitch.setChecked(enabled);
        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.setSiteSettingsEnabled(host, isChecked);
            render();
        });
        enable.addView(enableSwitch);
        list.addView(enable);

        addSection("内容");
        addClickableRow("字体大小", textZoomLabel(enabled), enabled, this::finishForFont);
        addClickableRow("浏览器标识", uaLabel(enabled), enabled, this::showUserAgentDialog);
        addModeRow("电脑模式", prefs.siteDesktopMode(host), prefs.uaMode() == 0, enabled,
                () -> showModeDialog("电脑模式", prefs.siteDesktopMode(host), prefs.uaMode() == 0,
                        mode -> { prefs.setSiteDesktopMode(host, mode); render(); }));
        addModeRow("图像", prefs.siteImagesMode(host), prefs.imagesEnabled(), enabled,
                () -> showModeDialog("图像", prefs.siteImagesMode(host), prefs.imagesEnabled(),
                        mode -> { prefs.setSiteImagesMode(host, mode); render(); }));
        addModeRow("JavaScript", prefs.siteJsMode(host), prefs.jsEnabled(), enabled,
                () -> showModeDialog("JavaScript", prefs.siteJsMode(host), prefs.jsEnabled(),
                        mode -> { prefs.setSiteJsMode(host, mode); render(); }));

        addSection("基本");
        addModeRow("广告拦截", prefs.siteAdBlockMode(host), prefs.adBlockEnabled(), enabled,
                () -> showModeDialog("广告拦截", prefs.siteAdBlockMode(host), prefs.adBlockEnabled(),
                        mode -> { prefs.setSiteAdBlockMode(host, mode); render(); }));
        addModeRow("隐身", prefs.siteIncognitoMode(host), prefs.incognitoMode(), enabled,
                () -> showModeDialog("隐身", prefs.siteIncognitoMode(host), prefs.incognitoMode(),
                        mode -> { prefs.setSiteIncognitoMode(host, mode); render(); if (mode == MODE_ON) finishOk(true); }));

        addSection("权限");
        addPermissionRow("麦克风", "microphone", enabled);
        addPermissionRow("摄像头", "camera", enabled);
        addPermissionRow("剪贴板", "clipboard", enabled);
        addPermissionRow("打开应用", "open_apps", enabled);
        addRedirectRow(enabled);
        addPermissionRow("位置信息", "location", enabled);

        addSection("高级");
        addModeRow("返回不重载", prefs.siteBackNoReloadMode(host), prefs.backNoReload(), enabled,
                () -> showModeDialog("返回不重载", prefs.siteBackNoReloadMode(host), prefs.backNoReload(),
                        mode -> { prefs.setSiteBackNoReloadMode(host, mode); render(); }));
    }

    private void addPermissionRow(String title, String key, boolean enabled) {
        int mode = prefs.sitePermissionMode(key, host);
        addClickableRow(title, permissionLabel(mode, prefs.permission(key)), enabled,
                () -> showPermissionDialog(title, mode,
                        value -> {
                            prefs.setSitePermissionMode(key, host, value);
                            if ("location".equals(key)) android.webkit.GeolocationPermissions.getInstance().clearAll();
                            render();
                        }));
    }

    private void addRedirectRow(boolean enabled) {
        int mode = prefs.siteRedirectMode(host);
        String fallback = prefs.permission("redirect");
        String label = mode < 0 ? permissionLabel(-1, fallback)
                : (mode == 0 ? "允许" : mode == 1 ? "询问" : "禁止");
        addClickableRow("页面重定向", label, enabled,
                () -> showRedirectDialog(mode, value -> { prefs.setSiteRedirectMode(host, value); render(); }));
    }

    private void addSection(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(13);
        v.setTextColor(getColor(R.color.via_accent));
        v.setPadding(dp(4), dp(18), dp(4), dp(8));
        list.addView(v);
    }

    private void addModeRow(String title, int mode, boolean fallbackOn, boolean enabled, Runnable click) {
        addClickableRow(title, modeLabel(title, mode, fallbackOn), enabled, click);
    }

    private void addClickableRow(String title, String sub, boolean enabled, Runnable click) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(8), dp(4), dp(8));
        row.setEnabled(enabled);
        row.setClickable(enabled);
        if (enabled) row.setBackgroundResource(R.drawable.bg_via_toolbar_button);
        row.setOnClickListener(enabled ? v -> click.run() : null);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(enabled ? (prefs.nightMode()?ViaUi.textColor(this,ViaUi.TEXT):getColor(R.color.text_primary)) : (prefs.nightMode()?0xff777777:0xffb0b0b0));
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(13);
        s.setTextColor(prefs.nightMode()?ViaUi.textColor(this,ViaUi.TEXT_SUB):0xff9e9e9e);
        row.addView(t);
        row.addView(s);
        list.addView(row, new LinearLayout.LayoutParams(-1, dp(72)));
    }

    private String modeLabel(int mode, boolean fallbackOn) {
        if (mode == MODE_ON) return "打开";
        if (mode == MODE_OFF) return "关闭";
        return (fallbackOn ? "打开" : "关闭") + "（默认）";
    }

    private String modeLabel(String title, int mode, boolean fallbackOn) {
        String label = modeLabel(mode, fallbackOn);
        return "图像".equals(title) || "JavaScript".equals(title)
                ? label.replace("打开", "允许").replace("关闭", "禁止") : label;
    }

    private String textZoomLabel(boolean enabled) {
        int site = prefs.siteTextZoom(host, -1);
        if (site <= 0) return prefs.textZoom() + "%（默认）";
        return site + "%" + (enabled ? "" : "（默认）");
    }

    private String uaLabel(boolean enabled) {
        long id = prefs.siteUaSelectedId(host);
        if (id == BrowserPrefs.SITE_UA_ID_INHERIT) return globalUaLabel() + "（默认）";
        if (id == BrowserPrefs.SITE_UA_ID_CUSTOM) return prefs.siteUserAgent(host);
        return prefs.getUaName(id);
    }

    private String globalUaLabel() {
        return prefs.getUaName(prefs.uaSelectedId());
    }

    private void finishForFont() {
        Intent data = new Intent();
        data.putExtra(EXTRA_OPEN_FONT, true);
        setResult(RESULT_OK, data);
        finish();
    }

    private String permissionLabel(int mode, String fallback) {
        if (mode == 1) return "允许";
        if (mode == 2) return "禁止";
        if (mode == 3) return "询问";
        return ("allow".equals(fallback) ? "允许" : "block".equals(fallback) ? "禁止" : "询问") + "（默认）";
    }

    private Dialog showPermissionDialog(String title, int mode, ModeConsumer consumer) {
        String[] labels = {"跟随全局（默认）", "允许", "禁止", "询问"};
        int checked = mode >= 1 && mode <= 3 ? mode : 0;
        Dialog dialog = ViaUi.radioDialog(this, title, labels, checked,
                which -> consumer.accept(which == 0 ? MODE_DEFAULT : which));
        dialog.show();
        return dialog;
    }

    private Dialog showRedirectDialog(int mode, ModeConsumer consumer) {
        String[] labels = {"跟随全局（默认）", "允许", "询问", "禁止"};
        int checked = mode < 0 ? 0 : mode == 0 ? 1 : mode == 1 ? 2 : 3;
        Dialog dialog = ViaUi.radioDialog(this, "页面重定向", labels, checked,
                which -> consumer.accept(which == 0 ? MODE_DEFAULT : which == 1 ? 0 : which == 2 ? 1 : 2));
        dialog.show();
        return dialog;
    }

    private void showUserAgentDialog() {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        java.util.List<String> labels = new java.util.ArrayList<>();
        ids.add((long) BrowserPrefs.SITE_UA_ID_INHERIT);
        labels.add(globalUaLabel() + "（默认）");
        for (int id : BrowserPrefs.PRESET_UA_IDS) {
            ids.add((long) id);
            labels.add(prefs.getUaName(id));
        }
        for (BrowserPrefs.CustomUaItem item : prefs.customUaList()) {
            ids.add(item.id);
            labels.add(item.name);
        }
        ids.add((long) BrowserPrefs.SITE_UA_ID_CUSTOM);
        labels.add("自定义");
        long current = prefs.siteUaSelectedId(host);
        int checked = 0;
        for (int i = 0; i < ids.size(); i++) if (ids.get(i) == current) checked = i;
        ViaUi.radioDialog(this, "浏览器标识", labels.toArray(new String[0]), checked, which -> {
            long selected = ids.get(which);
            if (selected == BrowserPrefs.SITE_UA_ID_CUSTOM) showCustomUaDialog(prefs.siteUserAgent(host));
            else { prefs.setSiteUaSelectedId(host, selected); render(); }
        }).show();
    }

    private void showCustomUaDialog(String current) {
        ViaUi.inputDialog(this, "自定义",
                new ViaUi.InputField("浏览器标识", current, true),
                null, false, null, (values, checked) -> {
                    prefs.setSiteUserAgent(host, values[0].trim());
                    render();
                });
    }

    private Dialog showModeDialog(String title, int mode, boolean fallbackOn, ModeConsumer consumer) {
        String[] labels = {modeLabel(title, MODE_DEFAULT, fallbackOn),
                modeLabel(title, MODE_ON, fallbackOn), modeLabel(title, MODE_OFF, fallbackOn)};
        int checked = mode == MODE_ON ? 1 : (mode == MODE_OFF ? 2 : 0);
        Dialog dialog = ViaUi.radioDialog(this, title, labels, checked,
                which -> consumer.accept(which == 1 ? MODE_ON
                        : (which == 2 ? MODE_OFF : MODE_DEFAULT)));
        dialog.show();
        return dialog;
    }

    private void finishOk(boolean incognitoChanged) {
        Intent data = new Intent();
        data.putExtra(EXTRA_INCOGNITO_CHANGED, incognitoChanged);
        setResult(RESULT_OK, data);
        finish();
    }

    @Override public void onBackPressed() { finishOk(false); }
    private int statusBarHeight() { int id = getResources().getIdentifier("status_bar_height", "dimen", "android"); return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24); }
    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
    private interface ModeConsumer { void accept(int mode); }
}
