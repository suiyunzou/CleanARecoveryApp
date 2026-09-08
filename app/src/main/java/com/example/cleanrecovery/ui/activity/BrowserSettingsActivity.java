package com.example.cleanrecovery.ui.activity;

import android.app.AlertDialog;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.proxy.ProxyActivity;
import com.example.cleanrecovery.ui.browser.AdBlockRuleLoader;
import com.example.cleanrecovery.ui.browser.AdSubscriptionManager;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.BrowserActions;
import com.example.cleanrecovery.ui.browser.BrowserWebdav;
import com.example.cleanrecovery.ui.browser.BrowserWebdavSynchronizer;
import com.example.cleanrecovery.ui.browser.BrowserBottomMenu;
import com.example.cleanrecovery.ui.browser.BrowserBackupManager;
import com.example.cleanrecovery.ui.browser.BrowserPasswordStore;
import com.example.cleanrecovery.ui.browser.BrowserUserScripts;
import com.example.cleanrecovery.ui.browser.BrowserAiClient;
import com.example.cleanrecovery.ui.browser.BrowserAiPrompt;
import com.example.cleanrecovery.ui.browser.BrowserCloudAccount;
import com.example.cleanrecovery.ui.browser.BrowserCloudPayload;
import com.example.cleanrecovery.ui.browser.SearchEngines;
import com.example.cleanrecovery.ui.browser.ViaUi;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * VIA 设置（1:1 对齐模拟器实测 VIA 7.2.1，见 .task/via-ref/）。
 *
 * <p>结构：设置 → 通用/定制/隐私/高级/脚本/关于；行几何=白底无分割线，
 * 单行 60dp、双行约 88dp、蓝色小节头；弹窗=圆角白卡（单选点选即生效、
 * 复选带取消/确定、滑杆即时生效）。</p>
 *
 * <p>本工程扩展：「高级」底部保留 Mihomo 代理入口。</p>
 */
public final class BrowserSettingsActivity extends Activity {

    public static final String EXTRA_OPEN_CUSTOMIZER = "open_customizer";
    public static final String EXTRA_OPEN_LONGPRESS = "open_longpress";
    public static final String EXTRA_OPEN_AI = "open_ai";
    public static final String EXTRA_OPEN_SCRIPTS = "open_scripts";
    public static final String EXTRA_EDIT_SCRIPT = "edit_script";
    public static final String EXTRA_SELECT_SCRIPT = "select_script";
    public static final String EXTRA_NEW_SCRIPT = "new_script";
    public static final String EXTRA_SCRIPT_URL = "script_url";
    public static final String EXTRA_SCRIPT_SOURCE = "script_source";

    private enum Page {
        ROOT, GENERAL, SYNC, WEBDAV, UA, DESKTOP_UA, ADBLOCK, ADBLOCK_CUSTOM, ADBLOCK_SUBS,
        SITE_CONF, SITE_LIST, PERMISSION, PASSWORD, PASSWORD_SAVED, PASSWORD_IGNORED, NIGHT, READER, READER_CSS,
        TOOLBAR, LONGPRESS, SEARCH, AI, AI_PROVIDERS, AI_PROVIDER_EDIT, AI_PROMPTS, AI_PROMPT_EDIT, ORIENTATION_NONE, GESTURES, GESTURE_ACTION, KEYBOARD, SCRIPTS, SCRIPT_CONFIG, SCRIPT_EDIT,
        PRIVACY, ADVANCED, FONT
    }

    private static final String[] UA_PRESETS = {
            "默认", "Android (手机)", "Android (平板)", "Windows (Chrome)", "Windows (IE 11)",
            "macOS", "iPhone", "iPad", "塞班 (Symbian)"
    };

    public interface BoolConsumer {
        void accept(boolean value);
    }

    public interface SubtitleUpdater {
        void update(String newSubtitle);
    }

    public interface RowActionWithSub {
        void onClick(SubtitleUpdater updater);
    }

    private BrowserPrefs prefs;
    private TextView titleView;
    private LinearLayout actionsContainer;
    private LinearLayout listContainer;
    private ScrollView scroll;
    private final List<Page> stack = new ArrayList<>();
    private final java.util.Map<Page, Integer> scrollPositions = new java.util.EnumMap<>(Page.class);
    private Page current = Page.ROOT;
    private TextView readerPreviewText;
    private BrowserAiPrompt editingAiPrompt;
    private int editingAiPromptType = 1;
    private EditText aiPromptName;
    private EditText aiPromptContent;
    private boolean discardAiPromptChanges;
    private android.app.Dialog aiPromptMenu;
    private Bundle aiProviderDraft;
    private EditText aiProviderNameInput, aiProviderEndpointInput, aiProviderKeyInput;
    private EditText aiProviderModelInput;
    private boolean discardAiProviderChanges;
    private BrowserAiClient.Request aiProviderValidationRequest;
    private android.app.Dialog aiProviderValidationDialog;
    private String editScriptName;
    private EditText scriptSource;
    private String scriptOriginalSource;
    private boolean discardScriptChanges;
    private boolean savingScript;
    private TextView scriptSaveAction;
    private TextView scriptAddAction;
    private View pendingScriptReveal;
    private android.animation.ObjectAnimator scriptRevealAnimator;

    private String permissionKey;
    private String permissionTitle;
    private String gestureKey;
    private int pendingExportMask;
    private String pendingExportPassword;
    private static final int REQ_EXPORT_BOOKMARKS = 7101;
    private static final int REQ_IMPORT_BOOKMARKS = 7102;
    private static final int REQ_IMPORT_DATA = 7103;
    private static final int REQ_EXPORT_DATA = 7104;
    private static final int REQ_IMPORT_PASSWORDS = 7105;
    private static final int REQ_EXPORT_PASSWORDS = 7106;
    private static final int REQ_IMPORT_SCRIPT = 7107;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new BrowserPrefs(this);
        applyAppLocale(this, prefs.language());
        applyOrientation();
        SystemUiHelper.apply(this);
        if (prefs.nightMode()) {
            getWindow().setStatusBarColor(Color.BLACK);
            getWindow().setNavigationBarColor(Color.BLACK);
            androidx.core.view.WindowInsetsControllerCompat controller = new androidx.core.view.WindowInsetsControllerCompat(getWindow(), getWindow().getDecorView());
            controller.setAppearanceLightStatusBars(false); controller.setAppearanceLightNavigationBars(false);
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(prefs.nightMode() ? Color.BLACK : Color.WHITE);
        root.setFitsSystemWindows(true);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶栏 48dp：返回箭头 + 标题（17sp #212121）+ 右侧动作（无分割虚线/底线，1:1 对齐 Via）
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(1), 0, dp(1), 0);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_chevron_left);
        back.setColorFilter(ViaUi.textColor(this, ViaUi.TEXT));
        back.setPadding(dp(14), dp(14), dp(14), dp(14));
        back.setBackgroundResource(R.drawable.bg_via_menu_cell);
        back.setContentDescription("返回");
        back.setOnClickListener(v -> onBackPressed());
        bar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

        titleView = new TextView(this);
        titleView.setTextColor(ViaUi.textColor(this, ViaUi.TEXT));
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleParams.leftMargin = dp(1);
        bar.addView(titleView, titleParams);

        actionsContainer = new LinearLayout(this);
        actionsContainer.setOrientation(LinearLayout.HORIZONTAL);
        actionsContainer.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(actionsContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        column.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54) - 1));
        View divider = new View(this);
        divider.setBackgroundColor(prefs.nightMode() ? 0xFF333333 : 0xFFE5E5E5);
        column.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        scroll.setFocusable(false);
        scroll.setFocusableInTouchMode(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        listContainer.setFocusableInTouchMode(true);
        listContainer.requestFocus();
        listContainer.setPadding(0, 0, 0, dp(24));
        scroll.addView(listContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);

        if (savedInstanceState != null && savedInstanceState.containsKey("settings_page")) {
            aiProviderDraft = savedInstanceState.getBundle("ai_provider_draft");
            editingAiPromptType = savedInstanceState.getInt("ai_prompt_type", 1);
            String promptId = savedInstanceState.getString("ai_prompt_id");
            for (BrowserAiPrompt prompt : prefs.aiStore().prompts()) if (prompt.id.equals(promptId)) editingAiPrompt = prompt;
            editScriptName = savedInstanceState.getString("script_name");
            permissionKey = savedInstanceState.getString("permission_key");
            permissionTitle = savedInstanceState.getString("permission_title");
            gestureKey = savedInstanceState.getString("gesture_key");
            for (String page : savedInstanceState.getStringArrayList("settings_stack")) stack.add(Page.valueOf(page));
            Bundle positions = savedInstanceState.getBundle("scroll_positions");
            if (positions != null) for (String page : positions.keySet()) scrollPositions.put(Page.valueOf(page), positions.getInt(page));
            render(Page.valueOf(savedInstanceState.getString("settings_page")));
            List<EditText> inputs = new ArrayList<>();
            collectInputs(listContainer, inputs);
            ArrayList<String> drafts = savedInstanceState.getStringArrayList("drafts");
            if (drafts != null) for (int i = 0; i < Math.min(drafts.size(), inputs.size()); i++) inputs.get(i).setText(drafts.get(i));
            int focused = savedInstanceState.getInt("focused_input", -1);
            if (focused >= 0 && focused < inputs.size()) inputs.get(focused).requestFocus();
            scroll.post(() -> scroll.scrollTo(0, savedInstanceState.getInt("settings_scroll")));
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_SCRIPTS, false)) {
            open(Page.SCRIPTS);
            String script = getIntent().getStringExtra(EXTRA_EDIT_SCRIPT);
            if (script != null && prefs.scriptNames().contains(script)) {
                editScriptName = script;
                open(Page.SCRIPT_CONFIG);
            } else if (getIntent().getBooleanExtra(EXTRA_NEW_SCRIPT, false)) {
                editScriptName = null;
                open(Page.SCRIPT_EDIT);
            }
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_AI, false)) {
            open(Page.AI);
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_LONGPRESS, false)) {
            open(Page.LONGPRESS);
        } else if (getIntent().getBooleanExtra(EXTRA_OPEN_CUSTOMIZER, false)) {
            open(Page.ROOT);
            openCustomizer();
        } else {
            open(Page.ROOT);
        }
    }

    private int dp(float v) {
        return ViaUi.dp(this, v);
    }

    private void open(Page page) {
        if (!stack.isEmpty()) scrollPositions.put(current, scroll.getScrollY());
        stack.add(page);
        render(page);
    }

    @Override
    public void onBackPressed() {
        if (current == Page.SCRIPT_EDIT && savingScript) return;
        if (current == Page.SCRIPT_EDIT && !discardScriptChanges && scriptSource != null
                && !scriptSource.getText().toString().equals(scriptOriginalSource)) {
            new AlertDialog.Builder(scriptDialogContext()).setTitle("提示")
                    .setMessage("修改的内容未保存，是否保存？")
                    .setPositiveButton("保存", (dialog, which) -> saveScriptSource(false))
                    .setNegativeButton("放弃修改", (dialog, which) -> {
                        discardScriptChanges = true; onBackPressed(); discardScriptChanges = false;
                    }).setNeutralButton("继续编辑", null).show();
            return;
        }
        if (current == Page.AI_PROVIDER_EDIT && !discardAiProviderChanges && aiProviderChanged()) {
            new AlertDialog.Builder(this).setTitle("提示").setMessage("修改的内容未保存，是否保存？")
                    .setPositiveButton("保存", (dialog, which) -> saveAiProviderDraft())
                    .setNegativeButton(R.string.via_cancel, (dialog, which) -> {
                        discardAiProviderChanges = true; onBackPressed(); discardAiProviderChanges = false;
                    }).show();
            return;
        }
        if (current == Page.AI_PROMPT_EDIT && !discardAiPromptChanges && aiPromptChanged()) {
            new AlertDialog.Builder(this).setTitle("提示").setMessage("修改的内容未保存，是否保存？")
                    .setPositiveButton("保存", (dialog, which) -> saveAiPrompt())
                    .setNegativeButton(R.string.via_cancel, (dialog, which) -> {
                        discardAiPromptChanges = true; onBackPressed(); discardAiPromptChanges = false;
                    }).show();
            return;
        }
        if (stack.size() > 1) {
            if (current == Page.SCRIPT_EDIT) {
                getIntent().removeExtra(EXTRA_SCRIPT_SOURCE);
                getIntent().removeExtra(EXTRA_SCRIPT_URL);
                getIntent().removeExtra(EXTRA_NEW_SCRIPT);
            }
            if (current == Page.AI_PROVIDER_EDIT) aiProviderDraft = null;
            stack.remove(stack.size() - 1);
            render(stack.get(stack.size() - 1));
            int position = scrollPositions.containsKey(current) ? scrollPositions.get(current) : 0;
            scroll.post(() -> scroll.scrollTo(0, position));
            return;
        }
        setResult(RESULT_OK);
        finish();
    }

    private void clearActions() {
        if (actionsContainer != null) actionsContainer.removeAllViews();
    }

    private TextView addRightAction(String text, Runnable onClick) {
        TextView action = new TextView(this);
        action.setTextColor(ViaUi.textColor(this, ViaUi.TEXT));
        action.setTextSize(15);
        action.setText(text);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(12), 0, dp(12), 0);
        action.setBackgroundResource(R.drawable.bg_via_menu_cell);
        action.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        actionsContainer.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return action;
    }

    // ================= 行构建 =================

    private final List<View> rowViews = new ArrayList<>();

    private View navRow(String title, String subtitle, Runnable onClick) {
        LinearLayout row = baseRow();
        attachTexts(row, title, subtitle, null);
        row.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        return row;
    }

    private View navRowWithSub(String title, String subtitle, RowActionWithSub onClick) {
        LinearLayout row = baseRow();
        TextView[] subRef = new TextView[1];
        attachTexts(row, title, subtitle, subRef);
        row.setOnClickListener(v -> {
            if (onClick != null) {
                onClick.onClick(newSub -> {
                    if (subRef[0] != null) {
                        subRef[0].setText(newSub);
                    }
                });
            }
        });
        return row;
    }

    private View toggleRow(String title, String subtitle, boolean initialOn, BoolConsumer onFlip) {
        LinearLayout row = baseRow();
        attachTexts(row, title, subtitle, null);
        final boolean[] state = new boolean[]{initialOn};
        ImageView sw = ViaUi.switchView(this, state[0]);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(16), dp(16));
        p.leftMargin = dp(12);
        row.addView(sw, p);
        row.setOnClickListener(v -> {
            state[0] = !state[0];
            ViaUi.renderSwitch(sw, state[0]);
            if (onFlip != null) onFlip.accept(state[0]);
        });
        return row;
    }

    private LinearLayout baseRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(20), dp(16), dp(20));
        row.setClickable(true);
        row.setFocusable(false);
        row.setFocusableInTouchMode(false);
        row.setBackgroundResource(R.drawable.bg_via_menu_cell);
        return row;
    }

    private void attachTexts(LinearLayout row, String title, String subtitle, TextView[] subRef) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14);
        t.setTextColor(ViaUi.textColor(this, ViaUi.TEXT));
        box.addView(t);
        if (subtitle != null && subtitle.length() > 0) {
            TextView s = new TextView(this);
            s.setText(subtitle);
            s.setTextSize(12);
            s.setTextColor(ViaUi.textColor(this, ViaUi.TEXT_SUB));
            box.addView(s);
            if (subRef != null) subRef[0] = s;
        }
        row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private View section(String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(0xFF6F8DE1);
        t.setPadding(dp(16), dp(18), dp(16), dp(6));
        return t;
    }

    private void addRow(View v) {
        rowViews.add(v);
        listContainer.addView(v, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void render(Page page) {
        if (scriptRevealAnimator != null) {
            scriptRevealAnimator.end();
            scriptRevealAnimator = null;
        }
        pendingScriptReveal = null;
        current = page;
        rowViews.clear();
        listContainer.removeAllViews();
        listContainer.setPadding(0, 0, 0, dp(24));
        clearActions();
        scroll.scrollTo(0, 0);
        switch (page) {
            case ROOT: renderRoot(); break;
            case GENERAL: renderGeneral(); break;
            case SYNC: renderSync(); break;
            case WEBDAV: renderWebdav(); break;
            case UA: renderUa(); break;
            case DESKTOP_UA: renderDesktopUa(); break;
            case ADBLOCK: renderAdblock(); break;
            case ADBLOCK_CUSTOM: renderAdblockCustom(); break;
            case ADBLOCK_SUBS: renderAdblockSubs(); break;
            case SITE_CONF: renderSiteConf(); break;
            case SITE_LIST: renderSiteList(); break;
            case PERMISSION: renderPermission(); break;
            case PASSWORD: renderPassword(); break;
            case PASSWORD_SAVED: renderSavedPasswords(); break;
            case PASSWORD_IGNORED: renderIgnoredPasswordSites(); break;
            case NIGHT: renderNight(); break;
            case READER: renderReader(); break;
            case READER_CSS: renderReaderCss(); break;
            case TOOLBAR: renderToolbar(); break;
            case LONGPRESS: renderLongPress(); break;
            case SEARCH: renderSearch(); break;
            case AI: renderAi(); break;
            case AI_PROVIDERS: renderAiProviders(); break;
            case AI_PROVIDER_EDIT: renderAiProviderEdit(); break;
            case AI_PROMPTS: renderAiPrompts(); break;
            case AI_PROMPT_EDIT: renderAiPromptEditor(); break;
            case GESTURES: renderGestures(); break;
            case GESTURE_ACTION: renderGestureAction(); break;
            case KEYBOARD: renderKeyboard(); break;
            case SCRIPTS: renderScripts(); break;
            case SCRIPT_EDIT: renderScriptEdit(); break;
            case SCRIPT_CONFIG: renderScriptConfig(); break;
            case PRIVACY: renderPrivacy(); break;
            case ADVANCED: renderAdvanced(); break;
            case FONT: renderFont(); break;
            default: renderRoot(); break;
        }
    }

    // ================= 各页 =================

    private void renderRoot() {
        titleView.setText("设置");
        addRow(navRow("通用", null, () -> open(Page.GENERAL)));
        addRow(navRow("定制", null, () -> openHomeCustomize()));
        addRow(navRow("隐私", null, () -> open(Page.PRIVACY)));
        addRow(navRow("高级", null, () -> open(Page.ADVANCED)));
        addRow(navRow("脚本", null, () -> open(Page.SCRIPTS)));
        addRow(navRow("检查更新", null, () -> startActivity(new Intent(this, AppUpdateActivity.class))));
    }

    private void renderGeneral() {
        titleView.setText("通用");
        addRow(navRow("同步", null, () -> open(Page.SYNC)));
        addRow(navRowWithSub("浏览器标识", prefs.getUaName(prefs.uaSelectedId()), updater -> open(Page.UA)));
        addRow(navRow("清除数据", null, this::showClearDataDialog));
        addRow(navRow("广告拦截", null, () -> open(Page.ADBLOCK)));
        addRow(navRow("网站设定", null, () -> open(Page.SITE_CONF)));
        addRow(navRow("密码管理器", null, () -> open(Page.PASSWORD)));
        addRow(navRow("夜间模式", null, () -> open(Page.NIGHT)));
        addRow(navRow("阅读模式", null, () -> open(Page.READER)));
        addRow(navRow("工具栏设置", null, () -> open(Page.TOOLBAR)));
        addRow(navRow("定制菜单", null, this::openCustomizer));
        addRow(navRow("定制长按菜单", null, () -> open(Page.LONGPRESS)));
        addRow(navRowWithSub("语言", languageLabel(), this::showLanguageDialog));
        addRow(navRowWithSub("主页", homeModeLabel(), this::showHomeDialog));
        addRow(navRow("搜索设置", null, () -> startActivity(new Intent(this, BrowserSearchSettingsActivity.class))));
        addRow(navRow("AI 设置", null, () -> open(Page.AI)));
        addRow(navRowWithSub("屏幕方向", orientationLabel(), this::showOrientationDialog));
        addRow(navRowWithSub("下载目录", prefs.downloadDir(), this::showDownloadDirDialog));
        addRow(navRowWithSub("下载管理", prefs.downloadManager() == 0 ? "内建下载器" : "系统下载管理器",
                this::showDownloadManagerDialog));
        addRow(navRowWithSub("外置视频播放器", prefs.externalPlayer() == 0 ? "系统分享" : "询问每次",
                this::showPlayerDialog));
        addRow(navRow("退出时清除数据", null, this::showExitClearDialog));
        addRow(navRow("字体", null, () -> open(Page.FONT)));
        addRow(navRow("操作设定", "手势，快捷方式，功能键", () -> open(Page.GESTURES)));
        addRow(navRow("导入/备份书签", null, this::showBookmarkBackupDialog));
        addRow(navRow("导入数据", null, this::showImportDataDialog));
        addRow(navRow("导出数据", "导出书签、设置、脚本和广告规则", this::exportAllData));
        addRow(navRowWithSub("启动时恢复未关闭标签", restoreLabel(), this::showRestoreDialog));
        addRow(toggleRow("显示撤销关闭标签的提示", "如果开启了隐身模式，则不会显示提示",
                prefs.undoCloseToast(), on -> prefs.setUndoCloseToast(on)));
        addRow(navRow("设置默认浏览器", null, this::openDefaultBrowserSettings));
    }

    private void renderSync() {
        titleView.setText("同步");
        addRightAction("WEBDAV", () -> open(Page.WEBDAV));
        if (prefs.cloudLoggedIn()) {
            addRow(navRow(prefs.cloudUsername(), null, this::showCloudLogout));
            addRow(navRow("从云端同步", null, this::cloudDownload));
            addRow(navRow("上传到云端", null, this::cloudUpload));
            addRow(navRow("注销账号", "从服务器删除账号", this::showCloudDelete));
        } else {
            addRow(navRow("登录/注册", null, this::showCloudLogin));
        }
        addRow(navRow("云同步服务器",
                prefs.syncServer().equals("global") ? "全球" :
                        prefs.syncServer().equals("cn") ? "中国" : "自定义",
                this::showSyncServerDialog));
    }

    private void showCloudLogin() {
        String server = prefs.syncServer();
        ViaUi.loginDialog(this, prefs.cloudUsername(),
                () -> openWeb(BrowserCloudAccount.terms(server)),
                () -> openWeb(BrowserCloudAccount.privacy(server)),
                (values, agreed) -> {
                    GlassToast.makeText(this, "登录中…", GlassToast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String result;
                        try {
                            result = BrowserCloudAccount.login(BrowserCloudAccount.endpoint(server),
                                    values[0].trim(), values[1]);
                        } catch (Exception e) {
                            result = "error:" + (e.getMessage() == null ? "服务器异常" : e.getMessage());
                        }
                        String response = result;
                        runOnUiThread(() -> {
                            if ("0".equals(response) || "2".equals(response)) {
                                try {
                                    prefs.setCloudUsername(values[0].trim());
                                    prefs.setCloudPasswordHash(BrowserCloudAccount.md5(values[1]));
                                    prefs.setCloudLoggedIn(true);
                                    GlassToast.makeText(this, "2".equals(response) ? "注册成功" : "登录成功",
                                            GlassToast.LENGTH_SHORT).show();
                                    render(Page.SYNC);
                                } catch (Exception e) {
                                    GlassToast.makeText(this, "服务器异常", GlassToast.LENGTH_SHORT).show();
                                }
                            } else {
                                GlassToast.makeText(this, "1".equals(response) ? "密码错误" : "服务器异常",
                                        GlassToast.LENGTH_SHORT).show();
                            }
                        });
                    }, "via-account-login").start();
                });
    }

    private void showCloudLogout() {
        ViaUi.listDialog(this, "退出登录？", new String[]{"确定"}, index -> {
            prefs.clearCloudAccount();
            render(Page.SYNC);
        }).show();
    }

    private void showCloudDelete() {
        ViaUi.inputDialog(this, "注销账号",
                new ViaUi.InputField("密码", ""), null, false, null, (values, checked) -> {
                    try {
                        if (!BrowserCloudAccount.md5(values[0]).equals(prefs.cloudPasswordHash())) {
                            GlassToast.makeText(this, "密码错误", GlassToast.LENGTH_SHORT).show();
                            return;
                        }
                    } catch (Exception e) {
                        GlassToast.makeText(this, "服务器异常", GlassToast.LENGTH_SHORT).show();
                        return;
                    }
                    cloudDelete();
                });
    }

    private void cloudUpload() {
        GlassToast.makeText(this, "云同步数据准备中…", GlassToast.LENGTH_SHORT).show();
        // The payload protocol is shared with import/export so all selected browser data round-trips.
        cloudTransfer(true);
    }

    private void cloudDownload() {
        GlassToast.makeText(this, "正在从云端同步…", GlassToast.LENGTH_SHORT).show();
        cloudTransfer(false);
    }

    private void cloudTransfer(boolean upload) {
        new Thread(() -> {
            try {
                java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
                data.put("name", prefs.cloudUsername());
                data.put("psw", prefs.cloudPasswordHash());
                if (upload) {
                    data.putAll(BrowserCloudPayload.create(this));
                    BrowserCloudAccount.form(BrowserCloudAccount.updateEndpoint(prefs.syncServer()), data);
                } else {
                    String response = BrowserCloudAccount.request(
                            BrowserCloudAccount.syncEndpoint(prefs.syncServer()),
                            prefs.cloudUsername(), prefs.cloudPasswordHash());
                    BrowserCloudPayload.restore(this, response);
                }
                runOnUiThread(() -> GlassToast.makeText(this, "同步成功", GlassToast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> GlassToast.makeText(this,
                        e.getMessage() == null ? "服务器异常" : e.getMessage(), GlassToast.LENGTH_LONG).show());
            }
        }, "via-cloud-sync").start();
    }

    private void cloudDelete() {
        new Thread(() -> {
            try {
                java.util.Map<String, String> data = new java.util.LinkedHashMap<>();
                data.put("name", prefs.cloudUsername());
                data.put("psw", prefs.cloudPasswordHash());
                data.put("op", "delete");
                BrowserCloudAccount.form(BrowserCloudAccount.updateEndpoint(prefs.syncServer()), data);
                prefs.clearCloudAccount();
                runOnUiThread(() -> {
                    GlassToast.makeText(this, "账号注销申请已提交。", GlassToast.LENGTH_LONG).show();
                    render(Page.SYNC);
                });
            } catch (Exception e) {
                runOnUiThread(() -> GlassToast.makeText(this, "服务器异常", GlassToast.LENGTH_LONG).show());
            }
        }, "via-account-delete").start();
    }

    private void openWeb(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private void renderWebdav() {
        titleView.setText("同步");
        addRightAction("云同步", () -> { onBackPressed(); });
        addRow(navRow("WebDAV", prefs.webdavUrl().isEmpty() ? "无配置" : prefs.webdavUrl(), this::showWebdavDialog));
        addRow(section("自动同步"));
        addRow(toggleRow("自动同步", null, prefs.webdavAutoSync(), prefs::setWebdavAutoSync));
        addRow(navRow("同步的数据", webdavDataLabel(), this::showWebdavDataDialog));
        addRow(navRow("手动同步", null, this::webdavSync));
    }

    private void showWebdavDialog() {
        ViaUi.inputDialog(this, "WebDAV 配置",
                new ViaUi.InputField[]{
                        new ViaUi.InputField("地址", prefs.webdavUrl()),
                        new ViaUi.InputField("用户名", prefs.webdavUser()),
                        new ViaUi.InputField("密码", prefs.webdavPass())
                }, null, false, null, (values, check) -> {
                    prefs.setWebdavUrl(values[0].trim());
                    prefs.setWebdavUser(values[1].trim());
                    prefs.setWebdavPass(values[2].trim());
                    render(Page.WEBDAV);
                });
    }

    private String webdavDataLabel() {
        int mask = prefs.webdavSyncData();
        if (mask == 0) return "无";
        List<String> names = new ArrayList<>();
        if ((mask & BrowserBackupManager.BOOKMARKS) != 0) names.add("书签");
        if ((mask & BrowserBackupManager.FAVORITES) != 0) names.add("主页收藏");
        if ((mask & BrowserBackupManager.SETTINGS) != 0) names.add("设置");
        return android.text.TextUtils.join("、", names);
    }

    private void showWebdavDataDialog() {
        String[] names = {"书签", "主页收藏", "设置"};
        int mask = prefs.webdavSyncData();
        boolean[] checked = {
                (mask & BrowserBackupManager.BOOKMARKS) != 0,
                (mask & BrowserBackupManager.FAVORITES) != 0,
                (mask & BrowserBackupManager.SETTINGS) != 0
        };
        ViaUi.checkboxDialog(this, "同步的数据", names, checked, values -> {
            int selected = (values[0] ? BrowserBackupManager.BOOKMARKS : 0)
                    | (values[1] ? BrowserBackupManager.FAVORITES : 0)
                    | (values[2] ? BrowserBackupManager.SETTINGS : 0);
            prefs.setWebdavSyncData(selected);
            render(Page.WEBDAV);
        });
    }

    private void webdavSync() {
        if (!webdavReady()) return;
        int mask = prefs.webdavSyncData();
        if (mask == 0) {
            GlassToast.makeText(this, "请选择同步的数据", GlassToast.LENGTH_SHORT).show();
            return;
        }
        GlassToast.makeText(this, "同步中…", GlassToast.LENGTH_SHORT).show();
        new Thread(() -> {
            BrowserWebdav.Result result = BrowserWebdavSynchronizer.sync(this);
            BrowserWebdav.Result finalResult = result;
            runOnUiThread(() -> GlassToast.makeText(this, finalResult.ok ? "同步完成" : "同步失败：" + finalResult.body,
                    GlassToast.LENGTH_LONG).show());
        }, "webdav-sync").start();
    }

    private boolean webdavReady() {
        if (prefs.webdavUrl().trim().isEmpty()) {
            GlassToast.makeText(this, "请先配置 WEBDAV 账号", GlassToast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private void renderUa() {
        titleView.setText("浏览器标识");
        addRightAction("+", () -> showAddOrEditCustomUa(null));

        long currentSelected = prefs.uaSelectedId();

        // 1. 系统预设
        for (int i = 0; i < BrowserPrefs.PRESET_UA_IDS.length; i++) {
            final int presetId = BrowserPrefs.PRESET_UA_IDS[i];
            String name = BrowserPrefs.PRESET_UA_NAMES[i];
            View row = radioRow(name, currentSelected == presetId, () -> {
                prefs.setUaSelectedId(presetId);
                render(Page.UA);
            });
            addRow(row);
        }

        // 2. 自定义 UA 列表
        List<BrowserPrefs.CustomUaItem> customs = prefs.customUaList();
        for (BrowserPrefs.CustomUaItem item : customs) {
            View row = radioRow(item.name, currentSelected == item.id, () -> {
                prefs.setUaSelectedId(item.id);
                render(Page.UA);
            });
            row.setOnLongClickListener(v -> {
                showCustomUaOptions(item);
                return true;
            });
            addRow(row);
        }

        // 3. 高级分类
        addRow(section("高级"));
        addRow(navRow("电脑模式下的浏览器标识",
                prefs.getUaName(prefs.desktopUaSelectedId()), this::showDesktopUaDialog));
        addRow(toggleRow("简化浏览器标识", "从浏览器标识中移除设备信息及次要版本信息",
                prefs.simpleUa(), on -> {
                    prefs.setSimpleUa(on);
                }));
    }

    private void renderDesktopUa() {
        showDesktopUaDialog();
    }

    private void showDesktopUaDialog() {
        List<Long> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();

        ids.add((long) BrowserPrefs.UA_ID_DEFAULT);
        names.add("默认");

        ids.add((long) BrowserPrefs.UA_ID_WINDOWS_CHROME);
        names.add("Windows (Chrome)");

        ids.add((long) BrowserPrefs.UA_ID_WINDOWS_IE);
        names.add("Windows (IE 11)");

        ids.add((long) BrowserPrefs.UA_ID_MACOS);
        names.add("macOS");

        for (BrowserPrefs.CustomUaItem c : prefs.customUaList()) {
            ids.add(c.id);
            names.add(c.name);
        }

        long currentId = prefs.desktopUaSelectedId();
        int selectedIndex = 0;
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i) == currentId) {
                selectedIndex = i;
                break;
            }
        }

        String[] nameArr = names.toArray(new String[0]);
        ViaUi.radioDialog(this, "电脑模式下的浏览器标识", nameArr, selectedIndex, idx -> {
            prefs.setDesktopUaSelectedId(ids.get(idx));
            render(Page.UA);
        }).show();
    }

    /** 单选行（设置页内嵌式，与弹窗样式一致）。 */
    private View radioRow(String label, boolean selected, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(16), dp(16), dp(16));
        row.setClickable(true);
        row.setBackgroundResource(R.drawable.bg_via_menu_cell);
        ImageView dot = new ImageView(this);
        dot.setImageResource(selected ? R.drawable.bg_via_radio_on : R.drawable.bg_via_radio);
        row.addView(dot, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(16);
        t.setTextColor(0xFF212121);
        t.setPadding(dp(18), 0, 0, 0);
        row.addView(t);
        row.setOnClickListener(v -> {
            if (onClick != null) onClick.run();
        });
        return row;
    }

    private void renderAdblock() {
        titleView.setText("广告拦截");
        String sub = "已拦截 " + prefs.adBlockBlockedCount() + " 个广告，共节省 "
                + formatBytes(prefs.adBlockSavedBytes());
        addRow(toggleRow("广告拦截", sub, prefs.adBlockEnabled(),
                on -> {
                    prefs.setAdBlockEnabled(on);
                    AdBlockRuleLoader.reloadAsync(this);
                }));
        addRow(toggleRow("启用内建规则", "内建规则仅拦截常见广告",
                prefs.adBlockBuiltin(), on -> {
                    prefs.setAdBlockBuiltin(on);
                    AdBlockRuleLoader.reloadAsync(this);
                }));
        addRow(navRow("自定义规则", "包括拦截的规则、标记的广告", () -> open(Page.ADBLOCK_CUSTOM)));
        addRow(navRow("规则订阅", "订阅 Adblock Plus 规则列表", () -> open(Page.ADBLOCK_SUBS)));
        addRow(toggleRow("自动展开网页全文", "打开网页时自动展开网页中折叠的内容",
                prefs.adBlockExpand(), on -> prefs.setAdBlockExpand(on)));
    }

    private void renderAdblockCustom() {
        titleView.setText("自定义规则");
        List<String> hosts = new ArrayList<>(prefs.blockedHosts());
        List<String> rules = new ArrayList<>(prefs.blockedUrlRules());
        List<String> cosmetic = new ArrayList<>(prefs.cosmeticRuleEntries());
        int total = hosts.size() + rules.size() + cosmetic.size();
        addRow(navRow("添加拦截规则", "域名、URL 片段或 host##selector", () ->
                ViaUi.inputDialog(this, "添加规则",
                        new ViaUi.InputField("例：example.com 或 ||ads.example.com^", ""), null, false,
                        null, (values, check) -> {
                            String rule = values[0].trim();
                            if (rule.isEmpty()) return;
                            if (rule.contains("##")) {
                                int split = rule.indexOf("##");
                                prefs.addCosmeticRule(rule.substring(0, split), rule.substring(split + 2));
                            } else if (rule.startsWith("||") && rule.endsWith("^")) {
                                prefs.addBlockedHost(rule.substring(2, rule.length() - 1));
                            } else {
                                prefs.addBlockedUrlRule(rule);
                            }
                            AdBlockRuleLoader.reloadAsync(BrowserSettingsActivity.this);
                            render(Page.ADBLOCK_CUSTOM);
                        })));
        addRow(section("已保存规则（" + total + "）"));
        for (String host : hosts) {
            addRow(ruleRow(host, "域名", () -> {
                java.util.Set<String> set = new java.util.HashSet<>(prefs.blockedHosts());
                set.remove(host);
                prefs.setBlockedHosts(set);
                AdBlockRuleLoader.reloadAsync(this);
                render(Page.ADBLOCK_CUSTOM);
            }));
        }
        for (String rule : rules) {
            addRow(ruleRow(rule, "URL 规则", () -> {
                java.util.Set<String> set = new java.util.HashSet<>(prefs.blockedUrlRules());
                set.remove(rule);
                prefs.setBlockedUrlRules(set);
                AdBlockRuleLoader.reloadAsync(this);
                render(Page.ADBLOCK_CUSTOM);
            }));
        }
        for (String entry : cosmetic) {
            int split = entry.indexOf('\t');
            final String host = split > 0 ? entry.substring(0, split) : entry;
            final String selector = split > 0 ? entry.substring(split + 1) : "";
            addRow(ruleRow(host + "  ##  " + selector, "标记的广告", () -> {
                java.util.Set<String> set = new java.util.HashSet<>(prefs.cosmeticRuleEntries());
                set.remove(entry);
                prefs.setCosmeticRuleEntries(set);
                AdBlockRuleLoader.reloadAsync(this);
                render(Page.ADBLOCK_CUSTOM);
            }));
        }
        if (total == 0) {
            addRow(kaomoji());
        }
    }

    private View ruleRow(String text, String kind, Runnable onDelete) {
        LinearLayout row = baseRow();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(15);
        t.setTextColor(0xFF212121);
        t.setSingleLine(true);
        box.addView(t);
        TextView k = new TextView(this);
        k.setText(kind);
        k.setTextSize(12);
        k.setTextColor(0xFF9E9E9E);
        k.setPadding(0, dp(2), 0, 0);
        box.addView(k);
        row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView del = new TextView(this);
        del.setText("删除");
        del.setTextColor(0xFF6F8DE1);
        del.setTextSize(14);
        del.setPadding(dp(12), dp(10), 0, dp(10));
        row.addView(del);
        row.setOnClickListener(v -> onDelete.run());
        return row;
    }

    private void renderAdblockSubs() {
        titleView.setText("规则订阅");
        TextView add = addRightAction("＋", this::showAddSubscriptionChooser);
        add.setTextSize(24);
        add.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        addRightAction("更新", this::updateAllSubscriptions);

        List<AdSubscriptionManager.Subscription> subs = AdSubscriptionManager.load(this);
        if (!subs.isEmpty()) {
            addRow(navRow("自动更新", adBlockUpdateIntervalLabel(), this::showAdBlockUpdateIntervalDialog));
            addRow(section("规则订阅"));
            for (AdSubscriptionManager.Subscription s : orderedSubscriptions(subs)) {
                addRow(subRow(s.title, s.url, s));
            }
        } else {
            addRow(kaomoji());
        }
    }

    /** Via 将内建目录固定按推荐顺序排列，用户自定义订阅随后显示。 */
    private List<AdSubscriptionManager.Subscription> orderedSubscriptions(
            List<AdSubscriptionManager.Subscription> subscriptions) {
        List<AdSubscriptionManager.Subscription> ordered = new ArrayList<>();
        for (AdSubscriptionManager.CatalogItem item : AdSubscriptionManager.catalog()) {
            AdSubscriptionManager.Subscription sub = AdSubscriptionManager.find(subscriptions, item.url);
            if (sub != null) ordered.add(sub);
        }
        for (AdSubscriptionManager.Subscription sub : subscriptions) {
            if (!ordered.contains(sub)) ordered.add(sub);
        }
        return ordered;
    }

    private View subRow(String displayTitle, String url, AdSubscriptionManager.Subscription s) {
        LinearLayout row = baseRow();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(displayTitle);
        t.setTextSize(14);
        t.setTextColor(0xFF212121);
        t.setSingleLine(true);
        box.addView(t);
        TextView k = new TextView(this);
        k.setText(subscriptionStatus(s));
        k.setTextSize(12);
        k.setTextColor(0xFF757575);
        k.setPadding(0, dp(2), 0, 0);
        box.addView(k);
        row.addView(box, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ImageView sw = ViaUi.switchView(this, s != null && s.enabled);
        LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(dp(16), dp(16));
        switchParams.leftMargin = dp(12);
        row.addView(sw, switchParams);
        row.setOnClickListener(v -> {
            if (s == null) {
                addSubscription(url);
            } else {
                AdSubscriptionManager.setEnabled(this, s.url, !s.enabled);
                AdBlockRuleLoader.reloadAsync(this);
                render(Page.ADBLOCK_SUBS);
            }
        });
        if (s != null) row.setOnLongClickListener(v -> {
            showSubscriptionActions(s);
            return true;
        });
        return row;
    }

    private void showSubscriptionActions(AdSubscriptionManager.Subscription s) {
        ViaUi.listDialog(this, null,
                new String[]{"预览", "更新", "编辑", "复制链接", "删除"}, index -> {
                    if (index == 0) {
                        Intent intent = new Intent(this, AdRulesPreviewActivity.class);
                        intent.putExtra(AdRulesPreviewActivity.EXTRA_URL, s.url);
                        intent.putExtra(AdRulesPreviewActivity.EXTRA_TITLE,
                                s.title == null || s.title.isEmpty() ? s.url : s.title);
                        startActivity(intent);
                    } else if (index == 1) {
                        updateSubscription(s);
                    } else if (index == 2) {
                        showEditSubscriptionDialog(s);
                    } else if (index == 3) {
                        android.content.ClipboardManager clipboard =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clipboard != null) clipboard.setPrimaryClip(
                                android.content.ClipData.newPlainText("规则订阅", s.url));
                        GlassToast.makeText(this, "链接已复制到剪贴板", GlassToast.LENGTH_SHORT).show();
                    } else {
                        new android.app.AlertDialog.Builder(this)
                                .setTitle("删除")
                                .setMessage("确定删除 “" + (s.title == null ? s.url : s.title) + "” 吗？")
                                .setNegativeButton("取消", null)
                                .setPositiveButton("确定", (dialog, which) -> {
                                    AdSubscriptionManager.remove(this, s.url);
                                    AdBlockRuleLoader.reloadAsync(this);
                                    render(Page.ADBLOCK_SUBS);
                                }).show();
                    }
                }).show();
    }

    private void showEditSubscriptionDialog(AdSubscriptionManager.Subscription s) {
        ViaUi.inputDialog(this, "编辑规则订阅",
                new ViaUi.InputField("https://", s.url), null, false, null,
                (values, check) -> {
                    if (AdSubscriptionManager.replaceUrl(this, s.url, values[0])) {
                        AdBlockRuleLoader.reloadAsync(this);
                        render(Page.ADBLOCK_SUBS);
                    } else {
                        GlassToast.makeText(this, "订阅地址无效或已存在", GlassToast.LENGTH_SHORT).show();
                    }
                });
    }

    private String subscriptionStatus(AdSubscriptionManager.Subscription s) {
        if (s == null || s.lastUpdateMs <= 0) return "0 条规则，从未更新";
        long age = Math.max(0, System.currentTimeMillis() - s.lastUpdateMs);
        String when = age < DateUtils.MINUTE_IN_MILLIS ? "刚刚" :
                DateUtils.getRelativeTimeSpanString(s.lastUpdateMs, System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS).toString();
        return s.lineCount + " 条规则，" + when + " 已更新";
    }

    private String adBlockUpdateIntervalLabel() {
        long days = prefs.adBlockSubUpdateInterval() / DateUtils.DAY_IN_MILLIS;
        if (days <= 0) return "从不";
        return days == 1 ? "每天" : "每 " + days + " 天";
    }

    private android.app.Dialog showAdBlockUpdateIntervalDialog() {
        String[] options = {"从不", "每天", "每 3 天", "每 7 天", "每 15 天"};
        long[] values = {0, 1, 3, 7, 15};
        long currentDays = prefs.adBlockSubUpdateInterval() / DateUtils.DAY_IN_MILLIS;
        int selected = 0;
        for (int i = 0; i < values.length; i++) if (values[i] == currentDays) selected = i;
        android.app.Dialog dialog = ViaUi.radioDialog(this, "自动更新", options, selected, index -> {
            prefs.setAdBlockSubUpdateInterval(values[index] * DateUtils.DAY_IN_MILLIS);
            AdBlockRuleLoader.reloadAsync(this);
            render(Page.ADBLOCK_SUBS);
        });
        dialog.show();
        return dialog;
    }

    private void showAddSubscriptionChooser() {
        List<AdSubscriptionManager.Subscription> current = AdSubscriptionManager.load(this);
        List<AdSubscriptionManager.CatalogItem> missing = new ArrayList<>();
        for (AdSubscriptionManager.CatalogItem item : AdSubscriptionManager.catalog()) {
            if (AdSubscriptionManager.find(current, item.url) == null) missing.add(item);
        }
        String[] options = new String[missing.size() + 1];
        for (int i = 0; i < missing.size(); i++) options[i] = missing.get(i).title;
        options[missing.size()] = "添加自定义订阅";
        ViaUi.listDialog(this, "添加规则订阅", options, index -> {
            if (index < missing.size()) addSubscription(missing.get(index).url);
            else showAddSubscriptionDialog();
        }).show();
    }

    private void showAddSubscriptionDialog() {
        ViaUi.inputDialog(this, "添加规则订阅",
                new ViaUi.InputField("https://", ""), null, false,
                null, (values, check) -> {
                    String url = values[0].trim();
                    if (!url.isEmpty()) addSubscription(url);
                });
    }

    private void updateAllSubscriptions() {
        List<AdSubscriptionManager.Subscription> enabled = new ArrayList<>();
        for (AdSubscriptionManager.Subscription s : AdSubscriptionManager.load(this)) {
            if (s.enabled) enabled.add(s);
        }
        if (enabled.isEmpty()) {
            GlassToast.makeText(this, "没有启用的规则订阅", GlassToast.LENGTH_SHORT).show();
            return;
        }
        GlassToast.makeText(this, "开始更新…", GlassToast.LENGTH_SHORT).show();
        new Thread(() -> {
            int failed = 0;
            for (AdSubscriptionManager.Subscription s : enabled) {
                AdSubscriptionManager.DownloadResult result = AdSubscriptionManager.download(this, s.url, s);
                if (!result.ok) failed++;
            }
            final int failures = failed;
            runOnUiThread(() -> {
                AdBlockRuleLoader.reloadAsync(this);
                GlassToast.makeText(this, failures == 0 ? "订阅规则更新完成。" :
                        failures + " 个订阅更新失败", GlassToast.LENGTH_LONG).show();
                render(Page.ADBLOCK_SUBS);
            });
        }, "ad-sub-update-all").start();
    }

    private void addSubscription(String url) {
        GlassToast.makeText(this, "正在下载订阅…", GlassToast.LENGTH_SHORT).show();
        new Thread(() -> {
            AdSubscriptionManager.DownloadResult res = AdSubscriptionManager.download(this, url, null);
            runOnUiThread(() -> {
                if (res.ok) {
                    GlassToast.makeText(this, getString(R.string.via_sub_added, res.lineCount),
                            GlassToast.LENGTH_SHORT).show();
                    AdBlockRuleLoader.reloadAsync(this);
                } else {
                    GlassToast.makeText(this, getString(R.string.via_sub_add_failed, res.error),
                            GlassToast.LENGTH_LONG).show();
                }
                render(Page.ADBLOCK_SUBS);
            });
        }, "ad-sub-download").start();
    }

    private void updateSubscription(AdSubscriptionManager.Subscription s) {
        new Thread(() -> {
            AdSubscriptionManager.DownloadResult res = AdSubscriptionManager.download(this, s.url, s);
            runOnUiThread(() -> {
                if (res.ok) {
                    GlassToast.makeText(this, R.string.via_sub_updated, GlassToast.LENGTH_SHORT).show();
                    AdBlockRuleLoader.reloadAsync(this);
                } else {
                    GlassToast.makeText(this, getString(R.string.via_sub_update_failed, res.error),
                            GlassToast.LENGTH_LONG).show();
                }
                render(Page.ADBLOCK_SUBS);
            });
        }, "ad-sub-update").start();
    }

    private void renderSiteConf() {
        titleView.setText("网站设定");
        addRow(navRow("所有网站", null, () -> open(Page.SITE_LIST)));
        addRow(section("内容"));
        addRow(navRow("字体大小", prefs.textZoom() + "%", () -> open(Page.FONT)));
        addRow(navRow("浏览器标识", prefs.getUaName(prefs.uaSelectedId()), () -> open(Page.UA)));
        addRow(toggleRow("电脑模式", null, prefs.desktopMode(), on -> prefs.setDesktopMode(on)));
        addRow(navRowWithSub("图像", prefs.imagesEnabled() ? "允许" : "阻止", this::showImagesDialog));
        addRow(navRowWithSub("JavaScript", prefs.jsEnabled() ? "允许" : "阻止", this::showJsDialog));
        addRow(navRowWithSub("Cookies", prefs.cookiesEnabled() ? "允许" : "阻止", this::showCookiesDialog));
        addRow(navRowWithSub("弹出式窗口", prefs.popupsEnabled() ? "允许" : "阻止", this::showPopupsDialog));
        addRow(section("基本"));
        addRow(navRow("广告拦截", prefs.adBlockEnabled() ? "打开" : "关闭",
                () -> open(Page.ADBLOCK)));
        addRow(section("权限"));
        permissionRow("剪贴板", "clipboard");
        permissionRow("打开应用", "open_apps");
        permissionRow("位置信息", "location");
        permissionRow("摄像头", "camera");
        permissionRow("麦克风", "microphone");
        permissionRow("页面重定向", "redirect");
        permissionRow("振动", "vibration");
        addRow(section("高级"));
        addRow(toggleRow("返回不重载", "返回上一页时不重新加载网页",
                prefs.backNoReload(), on -> prefs.setBackNoReload(on)));
    }

    private void permissionRow(String label, String key) {
        addRow(navRow(label, permissionLabel(prefs.permission(key)), () -> {
            permissionKey = key;
            permissionTitle = label;
            if ("location".equals(key)) android.webkit.GeolocationPermissions.getInstance().clearAll();
            open(Page.PERMISSION);
        }));
    }

    private static String permissionLabel(String mode) {
        return "allow".equals(mode) ? "允许" : "ask".equals(mode) ? "优先询问" : "已禁止";
    }

    private void renderPermission() {
        titleView.setText(permissionTitle);
        String mode = prefs.permission(permissionKey);
        boolean mediaCapture = "camera".equals(permissionKey) || "microphone".equals(permissionKey);
        boolean location = "location".equals(permissionKey);
        boolean clipboard = "clipboard".equals(permissionKey);
        if ("open_apps".equals(permissionKey) || clipboard) {
            String subtitle = clipboard ? ("ask".equals(mode) ? "网站需先询问并得到许可才能使用剪贴板"
                    : "block".equals(mode) ? "禁止网站使用剪贴板" : "允许网站使用剪贴板")
                    : "ask".equals(mode) ? "网站需先询问并得到许可才能打开应用" : permissionLabel(mode);
            addRow(navRow(permissionTitle, subtitle, () -> {
                String[] modes = {"allow", "block", "ask"};
                ViaUi.radioDialog(this, permissionTitle, new String[]{"允许", "禁止", "优先询问"},
                        Arrays.asList(modes).indexOf(mode), idx -> {
                            prefs.setPermission(permissionKey, modes[idx]);
                            render(Page.PERMISSION);
                        }).show();
            }));
        } else {
            boolean redirect = "redirect".equals(permissionKey);
            boolean on = redirect ? "allow".equals(mode) : !"block".equals(mode);
            String subtitle = redirect ? (on ? "允许页面重定向到任何页面" : "当页面重定向到非同源页面前先询问")
                    : !on ? (mediaCapture ? "已禁用" : "已禁止")
                    : location ? "网站需先询问并得到许可才能获取你的位置信息" : "网站需先询问并得到许可才能使用" + permissionTitle;
            addRow(toggleRow(permissionTitle, subtitle, on, enabled -> {
                String next = redirect ? (enabled ? "allow" : "ask")
                        : !enabled ? "block" : ("clipboard".equals(permissionKey) || "vibration".equals(permissionKey)) ? "allow" : "ask";
                prefs.setPermission(permissionKey, next);
                if (redirect) prefs.setRedirectAskMode(enabled ? 0 : 1);
                render(Page.PERMISSION);
            }));
        }
        boolean promptedPermission = mediaCapture || location || clipboard;
        boolean blockedPermission = promptedPermission && "block".equals(mode);
        String exceptionMessage = promptedPermission ? (blockedPermission ? "允许" : "禁止") + "特定网站使用" + permissionTitle + "。" : null;
        addRow(navRow("＋  添加例外网站", null, () -> ViaUi.inputDialog(this, "添加例外网站", exceptionMessage,
                new ViaUi.InputField[]{new ViaUi.InputField("www.example.com", "")}, blockedPermission ? "优先询问" : null, false, null, (values, check) -> {
                    String value = values[0].trim();
                    Uri uri = Uri.parse(value.contains("://") ? value : "https://" + value);
                    String host = uri.getHost();
                    if (host == null || host.isEmpty()) {
                        GlassToast.makeText(this, "请输入有效网站", GlassToast.LENGTH_SHORT).show();
                        return;
                    }
                    String exception = "redirect".equals(permissionKey) ? ("allow".equals(mode) ? "ask" : "allow")
                            : blockedPermission && check ? "ask"
                            : "block".equals(mode) ? "allow" : "block";
                    prefs.setPermissionException(permissionKey, host, exception);
                    render(Page.PERMISSION);
                })));
        String[] groups = {"allow", "block", "ask"};
        for (String group : groups) {
            boolean heading = false;
            for (String host : prefs.permissionExceptionHosts(permissionKey)) {
                if (!group.equals(prefs.permission(permissionKey, host))) continue;
                if (!heading) { addRow(section("block".equals(group) ? "已禁止" : permissionLabel(group))); heading = true; }
                addRow(navRow(host, null, () -> showPermissionException(host)));
            }
        }
    }

    private AlertDialog showPermissionException(String host) {
        String key = permissionKey;
        String[] modes = {"allow", "block", "ask"};
        int[] selected = {Arrays.asList(modes).indexOf(prefs.permission(key, host))};
        return new AlertDialog.Builder(this).setTitle(host)
                .setSingleChoiceItems(new String[]{"允许", "禁止", "优先询问"}, selected[0], (dialog, which) -> selected[0] = which)
                .setNegativeButton("删除", (dialog, which) -> {
                    prefs.setPermissionException(key, host, null); render(Page.PERMISSION);
                })
                .setPositiveButton("确定", (dialog, which) -> {
                    if (selected[0] >= 0) prefs.setPermissionException(key, host, modes[selected[0]]);
                    render(Page.PERMISSION);
                }).show();
    }

    private void renderSiteList() {
        titleView.setText("所有网站");
        List<String> hosts = new ArrayList<>(prefs.siteEnabledHosts());
        for (String host : hosts) {
            addRow(navRow(host, null, () -> showSiteDetailDialog(host)));
        }
        if (hosts.isEmpty()) addRow(kaomoji());
    }

    private void renderPassword() {
        titleView.setText("密码管理器");
        addRow(toggleRow("提示保存密码", null, prefs.passwordSaveHint(),
                on -> prefs.setPasswordSaveHint(on)));
        addRow(navRow("保存的密码", null, () -> open(Page.PASSWORD_SAVED)));
        addRow(navRow("不保存密码的网站", null, () -> open(Page.PASSWORD_IGNORED)));
        addRow(navRow("导入密码", "从 CSV 文件中导入密码",
                this::choosePasswordImport));
        addRow(navRow("导出密码", "使用完密码文件后，请将其删除以避免可能的数据泄露",
                this::choosePasswordExport));
    }

    private void renderSavedPasswords() {
        titleView.setText("保存的密码");
        List<BrowserPasswordStore.Entry> entries = new BrowserPasswordStore(this).list();
        for (BrowserPasswordStore.Entry entry : entries) {
            addRow(navRow(entry.origin, entry.username, () -> showPasswordEntry(entry)));
        }
        if (entries.isEmpty()) addRow(kaomoji());
    }

    private void renderIgnoredPasswordSites() {
        titleView.setText("不保存密码的网站");
        for (String host : new BrowserPasswordStore(this).ignoredHosts()) {
            addRow(navRow(host, null, () -> new android.app.AlertDialog.Builder(this)
                    .setMessage("要从列表中移除这个网站吗？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("移除", (d, w) -> {
                        new BrowserPasswordStore(this).unignore(host);
                        render(Page.PASSWORD_IGNORED);
                    }).show()));
        }
        if (new BrowserPasswordStore(this).ignoredHosts().isEmpty()) addRow(kaomoji());
    }

    private void showPasswordEntry(BrowserPasswordStore.Entry entry) {
        ViaUi.listDialog(this, entry.origin, new String[]{"复制用户名", "复制密码", "删除"}, index -> {
            if (index < 2) {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("password", index == 0 ? entry.username : entry.password));
            } else {
                new BrowserPasswordStore(this).remove(entry);
                render(Page.PASSWORD_SAVED);
            }
        }).show();
    }

    private void renderNight() {
        titleView.setText("夜间模式");
        addRow(navRow("网页蒙版滤镜", null, () -> {
            ViaUi.sliderDialog(this, prefs.nightMaskStrength(), 0, 90, "%",
                    val -> prefs.setNightMaskStrength(val)).show();
        }));
        addRow(toggleRow("强制网页暗色", "为网页启用强制暗色，这可能会导致部分页面表现异常",
                prefs.forceDarkPages(), prefs::setForceDarkPages));
    }

    private void renderReader() {
        titleView.setText("阅读模式");
        addRow(toggleRow("开启阅读模式需要二次确认", prefs.readerConfirm()
                ? "当点击地址栏阅读模式图标时，先展示菜单" : "当点击地址栏阅读模式图标时，开启阅读模式",
                prefs.readerConfirm(), on -> {
                    prefs.setReaderConfirm(on);
                    render(Page.READER);
                }));
        TextView appearance = (TextView) section("外观");
        appearance.setTextSize(12);
        appearance.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        appearance.setPadding(dp(16), dp(12), dp(16), dp(4));
        addRow(appearance);
        addRow(themeSwatches());
        addRow(readerFontControl());
        addRow(navRow("自定义阅读模式 CSS", null, () -> open(Page.READER_CSS)));
    }

    private View themeSwatches() {
        int[] colors = com.example.cleanrecovery.ui.browser.BrowserReader.themeColors();
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(8), dp(12), dp(8), dp(12));
        int selected = prefs.readerTheme();
        final View[] views = new View[colors.length];
        for (int i = 0; i < colors.length; i++) {
            final int idx = i;
            View sw = new View(this);
            views[i] = sw;
            updateSwatch(sw, colors[i], idx == selected);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(42), 1);
            p.setMargins(dp(8), 0, dp(8), 0);
            sw.setLayoutParams(p);
            sw.setClickable(true);
            sw.setOnClickListener(v -> {
                prefs.setReaderTheme(idx);
                for (int j = 0; j < colors.length; j++) {
                    updateSwatch(views[j], colors[j], j == idx);
                }
            });
            row.addView(sw);
        }
        return row;
    }

    private void updateSwatch(View sw, int color, boolean isSelected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(20));
        bg.setStroke(dp(2), isSelected ? 0xFF6F8DE1 : 0x1F000000);
        sw.setBackground(bg);
    }

    private View readerFontControl() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(20), dp(16), dp(20));
        TextView label = new TextView(this);
        label.setText("字体大小");
        label.setTextSize(14);
        label.setTextColor(0xFF212121);
        box.addView(label);
        LinearLayout slider = new LinearLayout(this);
        slider.setGravity(Gravity.CENTER_VERTICAL);
        TextView value = new TextView(this);
        value.setText(prefs.readerFont() + "px");
        value.setTextSize(14);
        value.setTextColor(0xFF757575);
        value.setMinWidth(dp(32));
        slider.addView(value);
        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(20);
        seek.setProgress(prefs.readerFont() - 10);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(5));
        track.setColor(0xFFDCDCDC);
        track.setSize(0, dp(2));
        GradientDrawable progress = new GradientDrawable();
        progress.setCornerRadius(dp(5));
        progress.setColor(0xFF6F8DE1);
        progress.setSize(0, dp(2));
        android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{track,
                        new android.graphics.drawable.ClipDrawable(progress, Gravity.LEFT, 1)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        seek.setProgressDrawable(layers);
        seek.setMinHeight(dp(2));
        seek.setMaxHeight(dp(2));
        seek.setSplitTrack(false);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(0xFF6F8DE1);
        thumb.setCornerRadius(dp(6));
        thumb.setSize(dp(12), dp(12));
        seek.setThumb(thumb);
        slider.addView(seek, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(-1, -2);
        sliderParams.topMargin = dp(2);
        box.addView(slider, sliderParams);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFE7E7E7);
        bg.setCornerRadius(dp(8));
        readerPreviewText = new TextView(this);
        readerPreviewText.setBackground(bg);
        readerPreviewText.setPadding(dp(16), dp(16), dp(16), dp(16));
        readerPreviewText.setText("Less is more.");
        readerPreviewText.setTextSize(14f * prefs.readerFont() / 17f);
        readerPreviewText.setTextColor(0xFF212121);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, -2);
        previewParams.topMargin = dp(12);
        box.addView(readerPreviewText, previewParams);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                value.setText((progress + 10) + "px");
                readerPreviewText.setTextSize(14f * (progress + 10) / 17f);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                prefs.setReaderFont(bar.getProgress() + 10);
            }
        });
        return box;
    }

    private void renderReaderCss() {
        titleView.setText("自定义阅读模式 CSS");
        EditText input = new EditText(this);
        input.setHint(".via-reader-body { font-family: … }");
        input.setText(prefs.readerCss());
        input.setTextSize(14);
        input.setTextColor(0xFF212121);
        input.setMinLines(6);
        input.setGravity(Gravity.TOP);
        input.setBackground(null);
        addRow(input);
        addRightAction("保存", () -> {
            prefs.setReaderCss(input.getText().toString());
            GlassToast.makeText(this, "已保存", GlassToast.LENGTH_SHORT).show();
        });
    }

    private void renderToolbar() {
        titleView.setText("工具栏设置");
        addRow(toolbarPreview());
        addRow(navRowWithSub("应用布局", toolbarModeLabel(), this::showLayoutDialog));
        addRow(navRowWithSub("自动隐藏操作栏", autoHideLabel(), this::showAutoHideDialog));
        addRow(toggleRow("启用标签栏", null,
                prefs.tabBarEnabled(), on -> {
                    if (on && (prefs.toolbarMode() == 1 || prefs.toolbarMode() == 3)) {
                        render(Page.TOOLBAR);
                        ViaUi.radioDialog(this, "应用布局", "请选择另一种应用布局以启用标签栏",
                                new String[]{"工具栏在上", "工具栏在下"}, -1, index -> {
                                    prefs.setToolbarMode(index == 0 ? 0 : 2);
                                    prefs.setTabBarEnabled(true);
                                    render(Page.TOOLBAR);
                                }).show();
                    } else {
                        prefs.setTabBarEnabled(on);
                        render(Page.TOOLBAR);
                    }
                }));
        addRow(navRowWithSub("地址栏内容", new String[]{"标题", "网址", "域名"}[prefs.addressContent()],
                this::showAddressDialog));
        addRow(toggleRow("色彩模式", "浏览网页时操作栏自适应色彩",
                prefs.adaptiveColor(), on -> prefs.setAdaptiveColor(on)));
    }

    private View toolbarPreview() {
        FrameLayout box = new FrameLayout(this) {
            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) { return true; }
        };
        box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        LinearLayout preview = (LinearLayout) getLayoutInflater().inflate(R.layout.activity_browser, box, false);
        preview.setFitsSystemWindows(false);
        preview.findViewById(R.id.browser_home_scroll).setVisibility(View.GONE);
        preview.findViewById(R.id.browser_bar_home).setVisibility(View.GONE);
        preview.findViewById(R.id.browser_bar_page).setVisibility(View.VISIBLE);
        preview.findViewById(R.id.browser_progress).setVisibility(View.GONE);
        preview.findViewById(R.id.browser_web_container).setBackgroundColor(0xfff3f3f3);
        ((TextView) preview.findViewById(R.id.browser_page_title)).setText(
                prefs.addressContent() == 0 ? "网页" : prefs.addressContent() == 1 ? "https://example.com/" : "example.com");
        ((TextView) preview.findViewById(R.id.browser_page_title)).setTextSize(16);
        ((ImageView) preview.findViewById(R.id.browser_site_info)).setImageResource(R.drawable.via_top_search);
        com.example.cleanrecovery.ui.browser.BrowserToolbarLayout layout =
                new com.example.cleanrecovery.ui.browser.BrowserToolbarLayout(preview, true);
        layout.apply(prefs.toolbarMode(), prefs.tabBarEnabled(), false);
        layout.updateTabs(java.util.Collections.singletonList("网页"), java.util.Collections.singletonList(null),
                0, index -> { }, index -> { }, () -> { });
        GradientDrawable border = new GradientDrawable();
        border.setColor(0xfff3f3f3);
        border.setCornerRadius(dp(14));
        border.setStroke(dp(2), 0xffe5e5e5);
        preview.setBackground(border);
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(Color.TRANSPARENT);
        outline.setCornerRadius(dp(14));
        outline.setStroke(dp(2), 0xffe5e5e5);
        preview.setForeground(outline);
        preview.setClipToOutline(true);
        preview.setScaleX(.8f);
        preview.setScaleY(.8f);
        box.addView(preview, new FrameLayout.LayoutParams(-1, dp(300)));
        return box;
    }

    private void renderLongPress() {
        titleView.setText("定制长按菜单");
        addRightAction("重置", () -> {
            prefs.setLongPressFlags(new java.util.HashSet<>(Arrays.asList(
                    BrowserPrefs.LONG_PRESS_FLAGS)));
            render(Page.LONGPRESS);
            GlassToast.makeText(this, "已重置", GlassToast.LENGTH_SHORT).show();
        });
        String[] labels = longPressLabels();
        java.util.Set<String> flags = prefs.longPressFlags();
        for (int i = 0; i < BrowserPrefs.LONG_PRESS_FLAGS.length; i++) {
            final String flag = BrowserPrefs.LONG_PRESS_FLAGS[i];
            addRow(toggleRow(labels[i], null, flags.contains(flag), on -> {
                java.util.Set<String> set = new java.util.HashSet<>(prefs.longPressFlags());
                if (on) set.add(flag);
                else set.remove(flag);
                prefs.setLongPressFlags(set);
            }));
        }
    }

    private static String[] longPressLabels() {
        return new String[]{
                "后台打开", "新标签打开", "查看图片", "保存图片", "下载图片", "分享图片",
                "以图搜图", "看图模式", "页面信息", "标记广告", "复制链接文本",
                "扫描二维码", "复制链接", "分享"
        };
    }

    private void renderSearch() {
        titleView.setText("搜索设置");
        String[] engines = {"YouTube", "Google", "Bing", "百度", "DuckDuckGo", "自定义", "Yahoo", "Startpage"};
        addRow(navRowWithSub("搜索引擎", engineLabel(), updater ->
                ViaUi.radioDialog(this, "搜索引擎", engines, prefs.searchEngine(), idx -> {
                    prefs.setSearchEngine(idx);
                    if (updater != null) updater.update(engineLabel());
                }).show()));
        addRow(navRowWithSub("自定义搜索前缀", prefs.searchPrefix().isEmpty() ? "未设置" : prefs.searchPrefix(),
                updater -> ViaUi.inputDialog(this, "自定义搜索前缀",
                        new ViaUi.InputField("https://…/search?q=", prefs.searchPrefix()), null, false,
                        null, (values, check) -> {
                            prefs.setSearchPrefix(values[0].trim());
                            if (updater != null) updater.update(prefs.searchPrefix().isEmpty() ? "未设置" : prefs.searchPrefix());
                        })));
        addRow(toggleRow("搜索框获得焦点时显示建议", null,
                (prefs.searchSuggestions() & 1) != 0, on ->
                prefs.setSearchSuggestions(on ? (prefs.searchSuggestions() | 1) : (prefs.searchSuggestions() & ~1))));
        addRow(toggleRow("地址栏搜索引擎切换", "在地址栏左侧显示搜索引擎切换按钮",
                prefs.searchToolbarEnabled(), on ->
                prefs.setSearchToolbarEnabled(on)));
    }

    private void renderAi() {
        titleView.setText("AI 设置");
        addRow(navRow("AI 服务提供商", prefs.aiProviderName().isEmpty() ? "无" : prefs.aiProviderName(),
                () -> open(Page.AI_PROVIDERS)));
        addRow(navRow("AI 提示词", null, () -> open(Page.AI_PROMPTS)));
    }

    private void renderAiProviders() {
        titleView.setText("AI 服务提供商");
        addRightAction("新建", this::showNewAiProvider);
        List<com.example.cleanrecovery.ui.browser.BrowserAiStore.Provider> providers = prefs.aiProviders();
        addRow(radioRow("无", prefs.aiProviderId().isEmpty(), () -> {
            prefs.selectAiProvider(null);
            render(Page.AI_PROVIDERS);
        }));
        for (com.example.cleanrecovery.ui.browser.BrowserAiStore.Provider provider : providers) {
            View row = radioRow(provider.name, provider.id.equals(prefs.aiProviderId()), () -> {
                prefs.selectAiProvider(provider); render(Page.AI_PROVIDERS);
            });
            row.setOnLongClickListener(v -> {
                showAiPromptMenu(row, new String[]{"编辑", "删除"}, index -> {
                    if (index == 0) {
                        aiProviderDraft = new Bundle();
                        aiProviderDraft.putString("id", provider.id); aiProviderDraft.putString("name", provider.name);
                        aiProviderDraft.putString("endpoint", provider.endpoint); aiProviderDraft.putString("key", provider.key);
                        aiProviderDraft.putString("models", provider.models); aiProviderDraft.putString("model", provider.model);
                        open(Page.AI_PROVIDER_EDIT);
                    } else {
                        prefs.aiStore().deleteProvider(provider.id);
                        if (provider.id.equals(prefs.aiProviderId())) prefs.selectAiProvider(null);
                        render(Page.AI_PROVIDERS);
                    }
                });
                return true;
            });
            addRow(row);
        }
    }

    private void showNewAiProvider() {
        com.example.cleanrecovery.ui.browser.BrowserAiProviderPresets.Preset[] presets =
                com.example.cleanrecovery.ui.browser.BrowserAiProviderPresets.ALL;
        String[] providers = new String[presets.length];
        for (int i = 0; i < providers.length; i++) providers[i] = presets[i].name;
        ViaUi.listDialog(this, "新建", providers, index -> startAiProviderDraft(presets[index].name,
                presets[index].endpoint, presets[index].models)).show();
    }

    private void startAiProviderDraft(String name, String endpoint, String models) {
        prefs.aiProviders();
        aiProviderDraft = new Bundle();
        aiProviderDraft.putString("name", name); aiProviderDraft.putString("endpoint", endpoint);
        aiProviderDraft.putString("key", ""); aiProviderDraft.putString("models", models);
        aiProviderDraft.putString("model", models.isEmpty() ? "" : models.split("\n")[0]);
        open(Page.AI_PROVIDER_EDIT);
    }

    private void renderAiProviderEdit() {
        if (aiProviderDraft == null) {
            prefs.aiProviders();
            aiProviderDraft = new Bundle();
            aiProviderDraft.putString("id", prefs.aiProviderId().isEmpty() ? null : prefs.aiProviderId());
            aiProviderDraft.putString("name", prefs.aiProviderName());
            aiProviderDraft.putString("endpoint", prefs.aiEndpoint());
            aiProviderDraft.putString("key", prefs.aiApiKey());
            aiProviderDraft.putString("models", prefs.aiModels());
            aiProviderDraft.putString("model", prefs.aiModel());
        }
        if (aiProviderDraft.getBundle("original") == null) aiProviderDraft.putBundle("original", new Bundle(aiProviderDraft));
        titleView.setText(aiProviderDraft.getString("id") == null ? "新建" : "编辑");
        EditText provider = aiProviderNameInput = plainInput(aiProviderDraft.getString("name", "自定义"), "名称");
        EditText endpoint = aiProviderEndpointInput = plainInput(aiProviderDraft.getString("endpoint", ""), "服务地址");
        EditText key = aiProviderKeyInput = plainInput(aiProviderDraft.getString("key", ""), "API 密钥");
        key.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addRightAction("验证", this::validateAiProvider);
        addRightAction("保存", this::saveAiProviderDraft);
        addRow(provider);
        addRow(endpoint);
        addRow(key);
        View keyPage = navRow("打开 API 密钥生成和管理页面", null, () -> {
            String url = com.example.cleanrecovery.ui.browser.BrowserAiProviderPresets.keyPage(endpoint.getText().toString());
            if (url != null) startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });
        keyPage.setVisibility(com.example.cleanrecovery.ui.browser.BrowserAiProviderPresets.keyPage(endpoint.getText().toString()) == null ? View.GONE : View.VISIBLE);
        addRow(keyPage);
        addRow(section("模型 ID"));
        LinearLayout modelEntry = new LinearLayout(this);
        modelEntry.setGravity(Gravity.CENTER_VERTICAL);
        aiProviderModelInput = plainInput(aiProviderDraft.getString("pending_model", ""), "模型 ID");
        modelEntry.addView(aiProviderModelInput, new LinearLayout.LayoutParams(0, -2, 1));
        TextView addModel = new TextView(this);
        addModel.setText("＋"); addModel.setTextSize(20); addModel.setTextColor(ViaUi.ACCENT);
        addModel.setPadding(dp(16), dp(12), dp(16), dp(12)); addModel.setContentDescription("添加模型");
        Runnable add = () -> {
            String model = aiProviderModelInput.getText().toString().trim();
            if (model.isEmpty()) return;
            captureAiProviderDraft();
            List<String> models = new ArrayList<>(Arrays.asList(aiProviderDraft.getString("models", "").split("\n")));
            models.remove("");
            if (!models.contains(model)) { models.add(model); aiProviderDraft.putBoolean("models_changed", true); }
            aiProviderDraft.putString("models", android.text.TextUtils.join("\n", models));
            if (aiProviderDraft.getString("model", "").isEmpty()) aiProviderDraft.putString("model", model);
            aiProviderDraft.putString("pending_model", "");
            render(Page.AI_PROVIDER_EDIT);
        };
        addModel.setOnClickListener(v -> add.run());
        aiProviderModelInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        aiProviderModelInput.setOnEditorActionListener((view, action, event) -> {
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { add.run(); return true; }
            return false;
        });
        modelEntry.addView(addModel); addRow(modelEntry);
        for (String model : aiProviderDraft.getString("models", "").split("\\n")) {
            if (model.trim().isEmpty()) continue;
            LinearLayout row = baseRow();
            row.setClickable(false);
            attachTexts(row, model, null, null);
            TextView remove = new TextView(this);
            remove.setText("删除"); remove.setTextColor(ViaUi.ACCENT); remove.setPadding(dp(14), dp(8), dp(4), dp(8));
            remove.setOnClickListener(v -> {
                captureAiProviderDraft();
                List<String> models = new ArrayList<>(Arrays.asList(aiProviderDraft.getString("models", "").split("\\n")));
                models.remove(model); aiProviderDraft.putBoolean("models_changed", true);
                aiProviderDraft.putString("models", android.text.TextUtils.join("\n", models));
                if (model.equals(aiProviderDraft.getString("model", ""))) aiProviderDraft.putString("model", models.isEmpty() ? "" : models.get(0));
                render(Page.AI_PROVIDER_EDIT);
            });
            row.addView(remove);
            addRow(row);
        }
    }

    private EditText plainInput(String value, String hint) {
        EditText input = new EditText(this);
        input.setHint(hint); input.setText(value); input.setTextSize(14); input.setTextColor(ViaUi.TEXT);
        input.setSingleLine(true); input.setBackgroundResource(R.drawable.bg_via_input);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        return input;
    }

    private void captureAiProviderDraft() {
        if (aiProviderDraft == null || aiProviderNameInput == null) return;
        aiProviderDraft.putString("name", aiProviderNameInput.getText().toString());
        aiProviderDraft.putString("endpoint", aiProviderEndpointInput.getText().toString());
        aiProviderDraft.putString("key", aiProviderKeyInput.getText().toString());
        aiProviderDraft.putString("pending_model", aiProviderModelInput.getText().toString());
    }

    private void saveAiProviderDraft() {
        EditText[] required = {aiProviderNameInput, aiProviderEndpointInput, aiProviderKeyInput};
        for (EditText input : required) if (input.getText().toString().trim().isEmpty()) {
            input.setError("不能为空"); input.requestFocus(); return;
        }
        captureAiProviderDraft();
        List<String> models = new ArrayList<>(Arrays.asList(aiProviderDraft.getString("models", "").split("\n")));
        models.remove("");
        String pending = aiProviderModelInput.getText().toString().trim();
        if (!pending.isEmpty() && !models.contains(pending)) models.add(pending);
        if (models.isEmpty()) { aiProviderModelInput.setError("请输入模型 ID"); aiProviderModelInput.requestFocus(); return; }
        String endpoint = aiProviderEndpointInput.getText().toString().trim();
        if (endpoint.endsWith("/chat/completions")) endpoint = endpoint.substring(0, endpoint.length() - "/chat/completions".length());
        String model = aiProviderDraft.getString("model", "");
        if (!models.contains(model)) model = models.get(0);
        com.example.cleanrecovery.ui.browser.BrowserAiStore.Provider saved = prefs.aiStore().saveProvider(aiProviderDraft.getString("id"),
                aiProviderNameInput.getText().toString().trim(), endpoint, aiProviderKeyInput.getText().toString().trim(),
                android.text.TextUtils.join("\n", models), model);
        if (saved.id.equals(prefs.aiProviderId())) prefs.selectAiProvider(saved);
        discardAiProviderChanges = true; onBackPressed(); discardAiProviderChanges = false;
    }

    private boolean aiProviderChanged() {
        captureAiProviderDraft();
        if (aiProviderDraft.getBoolean("models_changed", false)) return true;
        Bundle original = aiProviderDraft.getBundle("original");
        for (String field : new String[]{"name", "endpoint", "key", "pending_model"}) {
            if (!aiProviderDraft.getString(field, "").trim().equals(original.getString(field, "").trim())) return true;
        }
        return false;
    }

    private void validateAiProvider() {
        for (EditText input : new EditText[]{aiProviderNameInput, aiProviderEndpointInput, aiProviderKeyInput}) {
            if (input.getText().toString().trim().isEmpty()) { input.setError("不能为空"); input.requestFocus(); return; }
        }
        String models = aiProviderDraft.getString("models", "").trim();
        if (models.isEmpty()) { aiProviderModelInput.setError("请先添加模型"); aiProviderModelInput.requestFocus(); return; }
        String model = models.split("\n")[0];
        String endpoint = aiProviderEndpointInput.getText().toString().trim(), key = aiProviderKeyInput.getText().toString().trim();
        Bundle draft = aiProviderDraft;
        if (aiProviderValidationRequest != null) aiProviderValidationRequest.cancel();
        BrowserAiClient.Request request = new BrowserAiClient.Request(); aiProviderValidationRequest = request;
        GlassToast.makeText(this, "稍等片刻…", GlassToast.LENGTH_SHORT).show();
        new Thread(() -> {
            String failure = null;
            boolean[] received = {false};
            try {
                org.json.JSONArray messages = new org.json.JSONArray().put(new org.json.JSONObject().put("role", "user").put("content", "Say 'test'"));
                request.run(endpoint, key, model, messages, (content, reasoning) -> { received[0] = true; request.cancel(); });
            } catch (Exception e) {
                if (!received[0]) failure = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            final String error = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || (request.isCancelled() && !received[0]) || aiProviderValidationRequest != request
                        || current != Page.AI_PROVIDER_EDIT || aiProviderDraft != draft) return;
                aiProviderValidationRequest = null;
                if (aiProviderValidationDialog != null) aiProviderValidationDialog.dismiss();
                aiProviderValidationDialog = new AlertDialog.Builder(this).setTitle(error == null ? "验证" : "验证失败")
                        .setMessage(error == null ? "验证成功" : error).setPositiveButton(android.R.string.ok, null).show();
            });
        }, "ai-validate").start();
    }

    private void renderAiPrompts() {
        titleView.setText("AI 提示词");
        TextView create = addRightAction("＋", () -> {});
        create.setContentDescription("新建");
        create.setOnClickListener(v -> showAiPromptMenu(create, new String[]{"系统提示词", "消息模板"},
                index -> openAiPromptEditor(null, index + 1)));
        List<BrowserAiPrompt> prompts = prefs.aiStore().prompts();
        if (prompts.isEmpty()) {
            View empty = kaomoji(); empty.setPadding(0, 0, 0, 0); empty.setContentDescription("空空如也…");
            listContainer.setPadding(0, 0, 0, 0);
            listContainer.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
            rowViews.add(empty);
            return;
        }
        int type = 0;
        for (BrowserAiPrompt prompt : prompts) {
            if (prompt.type != type) { type = prompt.type; addRow(section(type == 1 ? "系统提示词" : "消息模板")); }
            View row = navRow(prompt.name, null, () -> openAiPromptEditor(prompt, prompt.type));
            row.setOnLongClickListener(v -> {
                showAiPromptMenu(row, new String[]{"编辑", "删除"}, index -> {
                    if (index == 0) openAiPromptEditor(prompt, prompt.type);
                    else {
                        prefs.aiStore().deletePrompt(prompt.id);
                        if (prompt.id.equals(prefs.aiDefaultPromptId())) prefs.setAiDefaultPromptId("");
                        render(Page.AI_PROMPTS);
                    }
                });
                return true;
            });
            addRow(row);
        }
    }

    private void showAiPromptMenu(View anchor, String[] labels, java.util.function.IntConsumer action) {
        if (aiPromptMenu != null) aiPromptMenu.dismiss();
        android.app.Dialog menu = new android.app.Dialog(this);
        aiPromptMenu = menu;
        menu.setOnDismissListener(dialog -> { if (aiPromptMenu == menu) aiPromptMenu = null; });
        menu.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL); rows.setPadding(1, dp(12), 1, dp(12));
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(android.graphics.Color.WHITE); background.setCornerRadius(dp(4));
        background.setStroke(1, 0xFFE0E0E0); rows.setBackground(background);
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView item = new TextView(this);
            item.setText(labels[i]); item.setTextSize(14); item.setTextColor(ViaUi.TEXT);
            item.setPadding(dp(16), dp(12), dp(16), dp(12));
            item.setOnClickListener(v -> { menu.dismiss(); action.accept(index); });
            rows.addView(item, new LinearLayout.LayoutParams(-1, -2));
        }
        menu.setContentView(rows); menu.setCanceledOnTouchOutside(true);
        android.view.Window window = menu.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        android.graphics.Rect screen;
        if (android.os.Build.VERSION.SDK_INT >= 30) screen = getWindowManager().getCurrentWindowMetrics().getBounds();
        else {
            android.graphics.Point size = new android.graphics.Point(); getWindowManager().getDefaultDisplay().getSize(size);
            screen = new android.graphics.Rect(0, 0, size.x, size.y);
        }
        android.graphics.Rect frame = new android.graphics.Rect(); anchor.getWindowVisibleDisplayFrame(frame);
        int width = Math.min(dp(240), Math.min(screen.width(), screen.height()) / 7 * 4);
        rows.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.AT_MOST));
        int[] location = new int[2]; anchor.getLocationOnScreen(location);
        int x = location[0] + anchor.getWidth();
        if (x > screen.width() / 2) x -= width;
        x = Math.max(dp(12), Math.min(x, screen.width() - width - dp(12)));
        int y = location[1] + anchor.getHeight() / 2;
        if (y > screen.height() / 2) y -= rows.getMeasuredHeight();
        y = Math.max(frame.top, Math.min(y, frame.bottom - rows.getMeasuredHeight()));
        android.view.WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = Gravity.TOP | Gravity.LEFT;
        attributes.x = x; attributes.y = y - frame.top; attributes.width = width; attributes.height = -2;
        window.setAttributes(attributes);
        menu.show();
    }

    @Override protected void onDestroy() {
        if (scriptRevealAnimator != null) scriptRevealAnimator.cancel();
        if (aiProviderValidationRequest != null) aiProviderValidationRequest.cancel();
        if (aiProviderValidationDialog != null) aiProviderValidationDialog.dismiss();
        if (aiPromptMenu != null) aiPromptMenu.dismiss();
        super.onDestroy();
    }

    private void openAiPromptEditor(BrowserAiPrompt prompt, int type) {
        editingAiPrompt = prompt; editingAiPromptType = type;
        open(Page.AI_PROMPT_EDIT);
    }

    private void renderAiPromptEditor() {
        titleView.setText(editingAiPrompt == null ? "新建" : "编辑");
        addRightAction("保存", this::saveAiPrompt);
        aiPromptName = plainInput(editingAiPrompt == null ? "" : editingAiPrompt.name, "标题");
        aiPromptContent = plainInput(editingAiPrompt == null ? "" : editingAiPrompt.content, "内容");
        aiPromptContent.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        aiPromptName.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        aiPromptName.setPadding(0, dp(8), 0, dp(8));
        aiPromptContent.setPadding(0, dp(8), 0, dp(8));
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            aiPromptName.setLocalePreferredLineHeightForMinimumUsed(false);
            aiPromptContent.setLocalePreferredLineHeightForMinimumUsed(false);
        }
        aiPromptContent.setSingleLine(false); aiPromptContent.setLines(7); aiPromptContent.setMaxLines(7); aiPromptContent.setGravity(Gravity.TOP);
        LinearLayout inputs = new LinearLayout(this);
        inputs.setOrientation(LinearLayout.VERTICAL); inputs.setPadding(dp(16), dp(12), dp(16), 0);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, -2);
        nameParams.bottomMargin = dp(12);
        inputs.addView(aiPromptName, nameParams);
        inputs.addView(aiPromptContent, new LinearLayout.LayoutParams(-1, -2));
        addRow(inputs); addRow(space(18));
        TextView variablesHeading = (TextView) section("可用变量");
        variablesHeading.setTextSize(12); variablesHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        variablesHeading.setPadding(dp(16), dp(12), dp(16), dp(4));
        addRow(variablesHeading);
        String[][] variables = editingAiPromptType == 1 ? new String[][]{
                {"{{webpage_url}}", "当前网页的链接。"}, {"{{webpage_title}}", "当前网页的标题。"},
                {"{{webpage_content}}", "当前网页的纯文本内容。"}, {"{{time}}", "当前时间 yyyy-MM-dd HH:mm:ss EEEE (ZZZZ)。"}
        } : new String[][]{{"{{input}}", "输入的消息。"}, {"{{time}}", "当前时间 yyyy-MM-dd HH:mm:ss EEEE (ZZZZ)。"}};
        for (String[] variable : variables) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(16), dp(12), dp(16), dp(12));
            TextView name = new TextView(this);
            name.setText(variable[0]); name.setTextSize(12); name.setTextColor(ViaUi.TEXT); name.setTypeface(Typeface.MONOSPACE);
            row.addView(name, new LinearLayout.LayoutParams(-2, -2));
            TextView description = new TextView(this);
            description.setText(variable[1]); description.setTextSize(14); description.setTextColor(ViaUi.TEXT_SUB);
            description.setGravity(Gravity.END); description.setPadding(dp(16), 0, 0, 0);
            row.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
            row.setOnClickListener(v -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(null, variable[0]));
                GlassToast.makeText(this, "已复制", GlassToast.LENGTH_SHORT).show();
            });
            addRow(row);
        }
    }

    private boolean aiPromptChanged() {
        return !aiPromptName.getText().toString().trim().equals(editingAiPrompt == null ? "" : editingAiPrompt.name)
                || !BrowserAiPrompt.editorContent(aiPromptContent.getText().toString()).equals(editingAiPrompt == null ? "" : editingAiPrompt.content);
    }

    private void saveAiPrompt() {
        if (aiPromptName.getText().toString().trim().isEmpty()) { aiPromptName.setError("请输入标题"); aiPromptName.requestFocus(); return; }
        if (aiPromptContent.getText().toString().trim().isEmpty()) { aiPromptContent.setError("请输入内容"); aiPromptContent.requestFocus(); return; }
        editingAiPrompt = prefs.aiStore().savePrompt(editingAiPrompt == null ? null : editingAiPrompt.id,
                aiPromptName.getText().toString(), BrowserAiPrompt.editorContent(aiPromptContent.getText().toString()), editingAiPromptType);
        onBackPressed();
    }

    private void renderGestures() {
        titleView.setText("操作设定");
        addRow(toggleRow("滑动屏幕前进后退", null, prefs.swipeNavigation(), prefs::setSwipeNavigation));
        addRow(toggleRow("使用音量键翻页", null, prefs.volumeKeyScroll(),
                on -> prefs.setVolumeKeyScroll(on)));
        addRow(toggleRow("启用视频播放器手势", "如果可能，在全屏播放视频时允许滑动屏幕以调整视频进度、屏幕亮度、设备音量",
                prefs.videoGestures(), prefs::setVideoGestures));
        addRow(navRow("键盘快捷键", null, () -> open(Page.KEYBOARD)));
        TextView help = new TextView(this);
        help.setText("点击下列按钮来设置它的长按事件，长按查看它的事件。你也可以滑动工具栏来设置滑动事件。");
        help.setTextSize(14);
        help.setTextColor(ViaUi.TEXT_SUB);
        addRow(help);
        View shell = getLayoutInflater().inflate(R.layout.activity_browser, listContainer, false);
        LinearLayout bar = shell.findViewById(R.id.browser_bottom_bar);
        ((ViewGroup) bar.getParent()).removeView(bar);
        int[] ids = {R.id.browser_nav_back, R.id.browser_nav_forward, R.id.browser_home, R.id.browser_tabs, R.id.browser_menu};
        String[] keys = {"back", "forward", "home", "tab", "menu"};
        for (int i = 0; i < ids.length; i++) {
            String key = keys[i];
            View button = bar.findViewById(ids[i]);
            button.setOnClickListener(v -> editGesture(key));
            button.setOnLongClickListener(v -> {
                GlassToast.makeText(this, BrowserActions.LABELS[prefs.gestureAction(key)], GlassToast.LENGTH_SHORT).show();
                return true;
            });
        }
        com.example.cleanrecovery.ui.browser.BrowserToolbarGestures.bind(bar, direction -> editGesture(direction < 0 ? "left" : "right"));
        listContainer.addView(bar, new LinearLayout.LayoutParams(-1, dp(48)));
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (current == Page.AI_PROVIDER_EDIT) captureAiProviderDraft();
        out.putBundle("ai_provider_draft", aiProviderDraft);
        out.putString("ai_prompt_id", editingAiPrompt == null ? null : editingAiPrompt.id);
        out.putInt("ai_prompt_type", editingAiPromptType);
        out.putString("settings_page", current.name());
        ArrayList<String> pages = new ArrayList<>();
        for (Page page : stack) pages.add(page.name());
        out.putStringArrayList("settings_stack", pages);
        out.putString("script_name", editScriptName);
        out.putString("permission_key", permissionKey);
        out.putString("permission_title", permissionTitle);
        out.putString("gesture_key", gestureKey);
        out.putInt("settings_scroll", scroll.getScrollY());
        Bundle positions = new Bundle();
        for (Page page : scrollPositions.keySet()) positions.putInt(page.name(), scrollPositions.get(page));
        out.putBundle("scroll_positions", positions);
        List<EditText> inputs = new ArrayList<>();
        collectInputs(listContainer, inputs);
        ArrayList<String> drafts = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            drafts.add(inputs.get(i).getText().toString());
            if (inputs.get(i).hasFocus()) out.putInt("focused_input", i);
        }
        out.putStringArrayList("drafts", drafts);
    }

    private static void collectInputs(View view, List<EditText> inputs) {
        if (view instanceof EditText) inputs.add((EditText) view);
        else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectInputs(group.getChildAt(i), inputs);
        }
    }

    private void editGesture(String key) {
        gestureKey = key;
        open(Page.GESTURE_ACTION);
    }

    private void renderGestureAction() {
        titleView.setText("选择行为");
        for (int group = 0; group < BrowserActions.GROUPS.length; group++) {
            if (group > 0) {
                TextView heading = new TextView(this);
                heading.setText(BrowserActions.SECTIONS[group]);
                heading.setTextSize(12);
                heading.setTextColor(ViaUi.TEXT_SUB);
                heading.setPadding(dp(16), dp(8), dp(16), dp(8));
                heading.setBackgroundColor(0xfff5f5f5);
                addRow(heading);
            }
            for (int action : BrowserActions.GROUPS[group]) {
                LinearLayout row = baseRow();
                ImageView dot = new ImageView(this);
                dot.setImageResource(prefs.gestureAction(gestureKey) == action ? R.drawable.bg_via_radio_on : R.drawable.bg_via_radio);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(16), dp(16));
                params.rightMargin = dp(18);
                row.addView(dot, params);
                attachTexts(row, BrowserActions.LABELS[action], null, null);
                row.setOnClickListener(v -> { prefs.setGestureAction(gestureKey, action); onBackPressed(); });
                addRow(row);
                if (prefs.gestureAction(gestureKey) == action) row.post(() -> scroll.scrollTo(0, row.getTop()));
            }
        }
    }

    private void renderKeyboard() {
        titleView.setText("键盘快捷键");
        String[] keys = {"Ctrl + t", "Ctrl + Tab", "Ctrl + Shift + Tab", "Alt + ←", "Alt + →", "Ctrl + w", "Alt + f",
                "Ctrl + Shift + b", "Ctrl + h", "Ctrl + f", "Ctrl + l", "F5 / Ctrl + r", "Ctrl + u", "Ctrl + d"};
        String[] labels = {"新建标签", "下一个标签", "上一个标签", "网页后退", "网页前进", "关闭标签", "菜单", "书签", "历史", "页内查找", "输入网址", "刷新网页", "源码", "添加书签"};
        for (int i = 0; i < keys.length; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            TextView key = new TextView(this);
            key.setText(keys[i]); key.setTextSize(13); key.setTextColor(ViaUi.TEXT_SUB);
            row.addView(key);
            TextView label = new TextView(this);
            label.setText(labels[i]); label.setTextSize(14); label.setTextColor(ViaUi.TEXT);
            row.addView(label);
            addRow(row);
        }
    }

    private void renderScripts() {
        titleView.setText("脚本");
        scriptAddAction = addRightAction("＋", this::showAddScriptMenu);
        scriptAddAction.setContentDescription("添加脚本");
        addRightAction("更新", this::updateScripts);
        List<String> names = prefs.scriptNames();
        if (!names.isEmpty()) {
            addRow(toggleRow("启用脚本", null, prefs.scriptsEnabled(), prefs::setScriptsEnabled));
            addRow(section("脚本"));
        }
        for (final String name : names) {
            LinearLayout row = baseRow();
            attachTexts(row, name, BrowserUserScripts.parse(prefs.scriptCode(name)).version, null);
            final boolean[] state = new boolean[]{prefs.isScriptEnabled(name)};
            ImageView sw = ViaUi.switchView(this, state[0]);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(20), dp(20));
            p.leftMargin = dp(12);
            row.addView(sw, p);
            row.setOnClickListener(v -> { editScriptName = name; open(Page.SCRIPT_CONFIG); });
            row.setOnLongClickListener(v -> { showScriptActions(name); return true; });
            sw.setOnClickListener(v -> {
                state[0] = !state[0];
                ViaUi.renderSwitch(sw, state[0]);
                prefs.setScriptEnabled(name, state[0]);
            });
            addRow(row);
            if (name.equals(getIntent().getStringExtra(EXTRA_SELECT_SCRIPT))) {
                pendingScriptReveal = row;
                row.post(this::revealSelectedScriptRow);
            }
        }
        if (names.isEmpty()) addRow(kaomoji());
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) revealSelectedScriptRow();
    }

    private void revealSelectedScriptRow() {
        View row = pendingScriptReveal;
        if (row == null || current != Page.SCRIPTS || !hasWindowFocus()) return;
        pendingScriptReveal = null;
        // Start after the activity entrance, once the target row can actually be seen.
        row.postDelayed(() -> {
            if (isFinishing() || isDestroyed() || current != Page.SCRIPTS || row.getParent() != listContainer) return;
            scroll.scrollTo(0, Math.max(0, row.getTop() - 20));
            row.postOnAnimation(() -> {
                if (current != Page.SCRIPTS || row.getParent() != listContainer) return;
                getIntent().removeExtra(EXTRA_SELECT_SCRIPT);
                // Via h6.y.W -> X: 24dp horizontal excursion, 280ms, three repetitions.
                scriptRevealAnimator = android.animation.ObjectAnimator.ofFloat(row, View.TRANSLATION_X, 0f, dp(24), 0f);
                scriptRevealAnimator.setDuration(280);
                scriptRevealAnimator.setRepeatCount(2);
                scriptRevealAnimator.setRepeatMode(android.animation.ValueAnimator.RESTART);
                scriptRevealAnimator.setInterpolator(new android.view.animation.PathInterpolator(.2f, .2f, .8f, .8f));
                scriptRevealAnimator.start();
                row.announceForAccessibility("已定位脚本");
            });
        }, 300);
    }

    private void showAddScriptMenu() {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(0, dp(8), 0, dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(ViaUi.surfaceColor(this));
        background.setCornerRadius(dp(8));
        android.widget.PopupWindow popup = new android.widget.PopupWindow(menu,
                Math.min(dp(232), getResources().getDisplayMetrics().widthPixels - dp(32)), -2, true);
        popup.setBackgroundDrawable(background);
        popup.setElevation(dp(8));
        popup.setOutsideTouchable(true);
        String[] labels = {"添加脚本", "下载脚本", "导入脚本"};
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView item = new TextView(this);
            item.setText(labels[i]); item.setTextSize(16);
            item.setTextColor(ViaUi.textColor(this, ViaUi.TEXT));
            item.setPadding(dp(20), 0, dp(20), 0);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackgroundResource(R.drawable.bg_via_menu_cell);
            menu.addView(item, new LinearLayout.LayoutParams(-1, dp(44)));
            item.setOnClickListener(v -> {
                popup.dismiss();
                if (index == 0) {
                    editScriptName = null;
                    open(Page.SCRIPT_EDIT);
                } else if (index == 1) {
                    startActivity(new Intent(this, BrowserActivity.class)
                            .putExtra("url", "https://greasyfork.org/zh-CN/scripts"));
                } else {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .addCategory(Intent.CATEGORY_OPENABLE).setType("text/*"), REQ_IMPORT_SCRIPT);
                }
            });
        }
        popup.showAsDropDown(scriptAddAction, 0, -dp(12), Gravity.END);
    }

    private void updateScripts() {
        GlassToast.makeText(this, "正在检查脚本更新…", GlassToast.LENGTH_SHORT).show();
        new Thread(() -> {
            int updated = 0;
            int failed = 0;
            for (String name : prefs.scriptNames()) {
                try {
                    BrowserUserScripts.Metadata metadata = BrowserUserScripts.parse(prefs.scriptCode(name));
                    if (metadata.updateUrl.isEmpty()) continue;
                    String code = BrowserUserScripts.download(metadata.updateUrl);
                    BrowserUserScripts.Metadata fresh = BrowserUserScripts.parse(code);
                    code = BrowserUserScripts.resolveRequires(code);
                    String updatedName = fresh.name.isEmpty() ? name : fresh.name;
                    boolean enabled = prefs.isScriptEnabled(name);
                    prefs.saveScript(updatedName, fresh.matchText(), code);
                    prefs.setScriptEnabled(updatedName, enabled);
                    prefs.preserveScriptIdentity(name, updatedName);
                    if (!fresh.name.isEmpty() && !fresh.name.equals(name)) prefs.removeScript(name);
                    updated++;
                } catch (Exception error) { failed++; }
            }
            int count = updated;
            int failures = failed;
            runOnUiThread(() -> {
                GlassToast.makeText(this, failures > 0 ? "已更新 " + count + " 个脚本，" + failures + " 个更新失败"
                                : count == 0 ? "没有可更新的脚本" : "已更新 " + count + " 个脚本",
                        GlassToast.LENGTH_SHORT).show();
                render(Page.SCRIPTS);
            });
        }, "userscript-update").start();
    }

    private void importUserScript(String code) {
        BrowserUserScripts.Metadata metadata = BrowserUserScripts.parse(code);
        String name = metadata.name.isEmpty() ? "导入的脚本" : metadata.name;
        new Thread(() -> {
            try {
                String resolved = BrowserUserScripts.resolveRequires(code);
                prefs.saveScript(name, metadata.matchText(), resolved);
                runOnUiThread(() -> {
                    GlassToast.makeText(this, "已导入 " + name, GlassToast.LENGTH_SHORT).show();
                    render(Page.SCRIPTS);
                });
            } catch (Exception e) {
                runOnUiThread(() -> GlassToast.makeText(this, "导入失败：" + e.getMessage(), GlassToast.LENGTH_LONG).show());
            }
        }, "userscript-import").start();
    }

    private android.content.Context scriptDialogContext() {
        return prefs.nightMode() ? new android.view.ContextThemeWrapper(this, R.style.ViaDarkPanel) : this;
    }

    private void renderScriptConfig() {
        titleView.setText("编辑");
        String name = editScriptName;
        addRow(navRow(name, BrowserUserScripts.parse(prefs.scriptCode(name)).version, () -> showScriptInfo(name)));
        String inherited = BrowserUserScripts.runAt(prefs.scriptCode(name));
        String[] values = {"", "document-start", "document-end", "document-idle"};
        String[] labels = {inherited + "（默认）", "document-start", "document-end", "document-idle"};
        int selected = java.util.Arrays.asList(values).indexOf(prefs.scriptRunAtOverride(name));
        addRow(navRow("运行时机", labels[Math.max(0, selected)], () ->
                ViaUi.radioDialog(this, "运行时机", labels, selected, index -> {
                    prefs.setScriptRunAtOverride(name, values[index]); render(Page.SCRIPT_CONFIG);
                }).show()));
        addScriptRuleRows(name, false);
        addScriptRuleRows(name, true);
        addRow(section("高级"));
        addRow(navRow("编辑源代码", null, () -> open(Page.SCRIPT_EDIT)));
        addRow(navRow("重置", null, () -> new AlertDialog.Builder(scriptDialogContext())
                .setMessage("恢复脚本默认配置？")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("重置", (dialog, which) -> { prefs.resetScriptOverrides(name); render(Page.SCRIPT_CONFIG); }).show()));
    }

    private void showScriptInfo(String name) {
        String code=prefs.scriptCode(name);
        BrowserUserScripts.Metadata metadata=BrowserUserScripts.parse(code);
        int bytes=code.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        String size=bytes<1024?String.format(java.util.Locale.ROOT,"%.1f B",(double)bytes)
                :android.text.format.Formatter.formatShortFileSize(this,bytes);
        new AlertDialog.Builder(scriptDialogContext()).setTitle("脚本信息")
                .setMessage("名称\n"+name+"\n版本\n"+(metadata.version.isEmpty()?"未知":metadata.version)
                        +"\n更新于\n"+scriptTime(prefs.scriptUpdatedAt(name))+"\n创建于\n"+scriptTime(prefs.scriptCreatedAt(name))+"\n大小\n"+size)
                .setPositiveButton(android.R.string.ok,null).show();
    }

    private CharSequence scriptTime(long time) {
        return time<=0?"未知":android.text.format.DateUtils.getRelativeTimeSpanString(time,System.currentTimeMillis(),android.text.format.DateUtils.MINUTE_IN_MILLIS);
    }

    private void addScriptRuleRows(String name, boolean exclude) {
        addRow(section(exclude ? "排除" : "匹配"));
        String text = exclude ? prefs.scriptExcludes(name) : prefs.scriptMatch(name);
        List<String> rules = new ArrayList<>();
        for (String rule : text.split("[\\n,]+")) if (!rule.trim().isEmpty()) rules.add(rule.trim());
        for (int i = 0; i < rules.size(); i++) {
            final int index = i;
            addRow(navRow(rules.get(i), null, () -> editScriptRule(name, exclude, rules, index)));
        }
        LinearLayout add = baseRow();
        ImageView plus = new ImageView(this);
        plus.setImageResource(R.drawable.via_script_add);
        plus.setColorFilter(ViaUi.textColor(this, ViaUi.TEXT_SUB));
        LinearLayout.LayoutParams icon = new LinearLayout.LayoutParams(dp(20), dp(20));
        icon.rightMargin = dp(16);
        add.addView(plus, icon);
        attachTexts(add, exclude ? "添加排除" : "添加匹配", null, null);
        add.setOnClickListener(v -> editScriptRule(name, exclude, rules, -1));
        addRow(add);
    }

    private void editScriptRule(String name, boolean exclude, List<String> rules, int index) {
        EditText input = new EditText(scriptDialogContext());
        input.setText(index < 0 ? "https://*/*" : rules.get(index)); input.setSingleLine(true);
        input.setHint(exclude ? "排除" : "匹配");
        LinearLayout field = new LinearLayout(this);
        field.setPadding(dp(20), dp(8), dp(20), 0);
        field.addView(input, new LinearLayout.LayoutParams(-1, -2));
        AlertDialog.Builder dialog = new AlertDialog.Builder(scriptDialogContext())
                .setTitle(index < 0 ? exclude ? "添加排除" : "添加匹配" : "编辑").setView(field)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton("保存", null);
        if (index >= 0) dialog.setNeutralButton("删除", (d, w) -> { rules.remove(index); saveScriptRules(name, exclude, rules); });
        AlertDialog shown = dialog.create();
        shown.setOnShowListener(d -> shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            if (value.isEmpty()) { input.setError("请输入规则"); return; }
            if (index < 0) rules.add(value); else rules.set(index, value);
            saveScriptRules(name, exclude, rules);
            shown.dismiss();
        }));
        shown.show();
    }

    private void saveScriptRules(String name, boolean exclude, List<String> rules) {
        String value = android.text.TextUtils.join("\n", rules);
        if (exclude) prefs.setScriptExcludes(name, value); else prefs.setScriptMatchOverride(name, value);
        render(Page.SCRIPT_CONFIG);
    }

    private void renderScriptEdit() {
        titleView.setText(editScriptName == null ? "添加脚本" : "编辑脚本");
        addRightAction("帮助", () -> new AlertDialog.Builder(scriptDialogContext())
                .setTitle("脚本帮助")
                .setMessage("在脚本头中填写 @name 名称、@match 匹配网址和 @run-at 运行时机。\n\n长按代码可选择、复制、剪切和粘贴。修改后点击右上角保存。")
                .setPositiveButton(android.R.string.ok, null).show());
        scriptSaveAction = addRightAction("保存", () -> saveScriptSource(false));
        scriptOriginalSource = editScriptName == null
                ? "// ==UserScript==\n// @name         New Userscript\n// @namespace    https://github.com/suiyunzou/CleanARecoveryApp\n// @version      0.1\n// @description  Describe what this script does\n// @author       You\n// @run-at       document-end\n// @match        https://*/*\n// @grant        none\n// ==/UserScript==\n\n(function() {\n    'use strict';\n\n    // Your code here...\n})();"
                : prefs.scriptCode(editScriptName);
        scriptSource = new EditText(scriptDialogContext());
        if (editScriptName == null) {
            String downloaded = getIntent().getStringExtra(EXTRA_SCRIPT_SOURCE);
            String pageUrl = getIntent().getStringExtra(EXTRA_SCRIPT_URL);
            if (downloaded != null) scriptOriginalSource = downloaded;
            else if (pageUrl != null && Uri.parse(pageUrl).getHost() != null) {
                Uri page = Uri.parse(pageUrl);
                scriptOriginalSource = BrowserUserScripts.withMatchRules(scriptOriginalSource,
                        page.getScheme() + "://" + page.getAuthority() + "/*");
            }
        }
        scriptSource.setContentDescription("脚本源代码");
        scriptSource.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        scriptSource.setTypeface(Typeface.MONOSPACE);
        scriptSource.setTextSize(14);
        scriptSource.setTextColor(ViaUi.textColor(this, ViaUi.TEXT));
        scriptSource.setGravity(Gravity.TOP | Gravity.START);
        scriptSource.setBackground(null);
        scriptSource.setPadding(dp(12), dp(12), dp(12), dp(24));
        scriptSource.setHorizontallyScrolling(true);
        scriptSource.setText(scriptOriginalSource);
        scriptSource.setSelection(0);
        listContainer.setPadding(0, 0, 0, 0);
        addRow(scriptSource);
        scroll.post(() -> { if (current == Page.SCRIPT_EDIT) scriptSource.setMinHeight(scroll.getHeight()); });
    }

    private void saveScriptSource(boolean replace) {
        if (savingScript || scriptSource == null) return;
        String source = scriptSource.getText().toString();
        BrowserUserScripts.Metadata metadata = BrowserUserScripts.parse(source);
        String name = metadata.name.isEmpty() && editScriptName != null ? editScriptName : metadata.name;
        if (source.trim().isEmpty() || name.isEmpty()) {
            scriptSource.setError(source.trim().isEmpty() ? "请输入脚本代码" : "请在脚本头填写 @name 名称");
            return;
        }
        if (!replace && !name.equals(editScriptName) && prefs.scriptNames().contains(name)) {
            new AlertDialog.Builder(scriptDialogContext()).setTitle("脚本已存在")
                    .setMessage("是否替换“" + name + "”？")
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton("替换", (dialog, which) -> saveScriptSource(true)).show();
            return;
        }
        final String oldName = editScriptName;
        final String match = oldName != null && !source.contains("// ==UserScript==")
                && metadata.matches.isEmpty() ? prefs.scriptMatch(oldName) : metadata.matchText();
        savingScript = true;
        scriptSaveAction.setEnabled(false);
        scriptSaveAction.setText("保存中…");
        scriptSource.setEnabled(false);
        new Thread(() -> {
            try {
                String resolved = BrowserUserScripts.resolveRequires(source);
                runOnUiThread(() -> {
                    // Only commit while this editor owns the draft; destroyed editors never save stale work.
                    if (isFinishing() || isDestroyed()) return;
                    boolean enabled = prefs.isScriptEnabled(oldName == null ? name : oldName);
                    prefs.saveScript(name, match, resolved);
                    prefs.setScriptEnabled(name, enabled);
                    prefs.preserveScriptIdentity(oldName, name);
                    if (oldName != null && !oldName.equals(name)) prefs.removeScript(oldName);
                    savingScript = false;
                    scriptOriginalSource = source;
                    ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                            .hideSoftInputFromWindow(scriptSource.getWindowToken(), 0);
                    editScriptName = name;
                    GlassToast.makeText(this, "已保存", GlassToast.LENGTH_SHORT).show();
                    onBackPressed();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    savingScript = false;
                    scriptSaveAction.setEnabled(true);
                    scriptSaveAction.setText("保存");
                    scriptSource.setEnabled(true);
                    GlassToast.makeText(this, "保存失败：" + error.getMessage(), GlassToast.LENGTH_LONG).show();
                });
            }
        }, "userscript-save").start();
    }

    private void renderPrivacy() {
        titleView.setText("隐私");
        addRow(toggleRow("不跟踪", null, prefs.doNotTrack(),
                on -> prefs.setDoNotTrack(on)));
        addRow(toggleRow("禁用 WebRTC", null, prefs.disableWebRtc(),
                on -> prefs.setDisableWebRtc(on)));
        addRow(toggleRow("不出售或分享数据", "请求网站不出售或分享我的数据以保护隐私 (实验性)",
                prefs.doNotSell(), on -> prefs.setDoNotSell(on)));
    }

    private void renderAdvanced() {
        titleView.setText("高级");
        addRow(toggleRow("流量节省", "请求网站节省流量", prefs.dataSaver(),
                on -> prefs.setDataSaver(on)));
        addRow(toggleRow("自动展示嗅探按钮", null, prefs.autoSnifferButton(),
                on -> prefs.setAutoSnifferButton(on)));
        addRow(toggleRow("允许调试网页", null, prefs.webDebug(),
                on -> prefs.setWebDebug(on)));
        addRow(toggleRow("禁用 Custom Tabs", "跳过第三方应用通过 Custom Tabs 打开链接时的中转页",
                prefs.disableCustomTabs(), on -> prefs.setDisableCustomTabs(on)));
        addRow(toggleRow("禁用安全浏览", "安全浏览允许 WebView 通过验证链接来防止恶意软件和网络钓鱼攻击",
                prefs.disableSafeBrowsing(), on -> prefs.setDisableSafeBrowsing(on)));
        addRow(toggleRow("忽略 SSL 证书警告", "请小心，忽略 SSL 证书警告可能会使网站的连接不安全",
                prefs.ignoreSslWarnings(), on -> prefs.setIgnoreSslWarnings(on)));
    }

    private void renderFont() {
        titleView.setText("字体");
        addRow(sliderBlock("字体大小", prefs.textZoom(), 50, 200, "%", value -> {
            prefs.setTextZoom(value);
        }));
        addRow(space(8));
        TextView hint = new TextView(this);
        hint.setText("调整网页文字缩放，对已打开的页面立即生效。");
        hint.setTextSize(13);
        hint.setTextColor(0xFF757575);
        hint.setPadding(dp(16), 0, dp(16), 0);
        addRow(hint);
    }

    // ================= 通用块 =================

    private View sliderBlock(String label, int initial, int min, int max,
                             String suffix, ViaUi.OnSlider change) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(16), dp(16), dp(4));
        TextView title = new TextView(this);
        title.setText(label + "：" + initial + suffix);
        title.setTextSize(15);
        title.setTextColor(0xFF212121);
        box.addView(title);
        android.widget.SeekBar seek = new android.widget.SeekBar(this);
        seek.setMax(max - min);
        seek.setProgress(initial - min);
        seek.getProgressDrawable().setTint(0xFF6F8DE1);
        seek.getThumb().setTint(0xFF6F8DE1);
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) return;
                int v = progress + min;
                title.setText(label + "：" + v + suffix);
                if (change != null) change.onChange(v);
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) { }
        });
        box.addView(seek, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private View kaomoji() {
        TextView t = new TextView(this);
        t.setText("¯\\_(ツ)_/¯");
        t.setTextSize(20);
        t.setTextColor(0xFF9E9E9E);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(80), 0, dp(80));
        return t;
    }

    private View space(int heightDp) {
        Space s = new Space(this);
        s.setMinimumHeight(dp(heightDp));
        return s;
    }

    private View bigButton(String label, Runnable onClick) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextColor(0xFF212121);
        btn.setTextSize(15);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(0, dp(12), 0, dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF1F1F3);
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        btn.setClickable(true);
        btn.setOnClickListener(v -> onClick.run());
        return btn;
    }

    // ================= 弹窗与动作 =================

    private void showClearDataDialog() {
        String[] items = {"缓存", "表单数据", "历史", "关闭的标签页", "网页存储", "Cookies (登录状态)", "应用缓存"};
        boolean[] checked = {true, true, true, false, false, false, false};
        ViaUi.checkboxDialog(this, "清除数据", items, checked, flags -> {
            clearSelectedData(flags);
        });
    }

    private void clearSelectedData(boolean[] flags) {
        String[] keys = {"cache", "form", "history", "closed_tabs", "web_storage", "cookies", "app_cache"};
        java.util.Set<String> selected = new java.util.HashSet<>();
        for (int i = 0; i < keys.length; i++) if (flags[i]) selected.add(keys[i]);
        try {
            com.example.cleanrecovery.ui.browser.BrowserDataCleaner.clear(this, selected,
                    java.util.Collections.emptyList(), () -> GlassToast.makeText(this, "已清除", GlassToast.LENGTH_SHORT).show());
        } catch (java.io.IOException e) {
            GlassToast.makeText(this, "清除未完成：" + e.getMessage(), GlassToast.LENGTH_LONG).show();
        }
    }

    private void showExitClearDialog() {
        String[] items = {"缓存", "表单数据", "历史", "关闭的标签页", "网页存储", "Cookies (登录状态)", "应用缓存"};
        String[] keys = {"cache", "form", "history", "closed_tabs", "web_storage", "cookies", "app_cache"};
        java.util.Set<String> saved = prefs.exitClearFlags();
        boolean[] checked = new boolean[items.length];
        for (int i = 0; i < keys.length; i++) checked[i] = saved.contains(keys[i]);
        ViaUi.checkboxDialog(this, "退出时清除数据", items, checked, flags -> {
            java.util.Set<String> set = new java.util.HashSet<>();
            for (int i = 0; i < keys.length; i++) {
                if (flags[i]) set.add(keys[i]);
            }
            prefs.setExitClearFlags(set);
        });
    }

    public static void applyAppLocale(Context ctx, int langCode) {
        Locale target;
        if (langCode == 1) {
            target = Locale.ENGLISH;
        } else if (langCode == 2) {
            target = Locale.SIMPLIFIED_CHINESE;
        } else if (langCode == 3) {
            target = Locale.TRADITIONAL_CHINESE;
        } else {
            android.content.res.Configuration system = android.content.res.Resources.getSystem().getConfiguration();
            target = Build.VERSION.SDK_INT >= 24 ? system.getLocales().get(0) : system.locale;
        }
        Locale.setDefault(target);
        android.content.res.Resources res = ctx.getResources();
        android.content.res.Configuration config = new android.content.res.Configuration(res.getConfiguration());
        config.setLocale(target);
        res.updateConfiguration(config, res.getDisplayMetrics());
        Context app = ctx.getApplicationContext();
        if (app != null && app != ctx) {
            app.getResources().updateConfiguration(config, app.getResources().getDisplayMetrics());
        }
    }

    private void showLanguageDialog(SubtitleUpdater updater) {
        String[] langs = {"跟随系统", "English", "中文 (简体)", "中文 (繁體)"};
        ViaUi.radioDialog(this, "语言", langs, prefs.language(), idx -> {
            prefs.setLanguage(idx);
            applyAppLocale(this, idx);
            if (updater != null) updater.update(languageLabel());
            recreate();
        }).show();
    }

    private void showHomeDialog(SubtitleUpdater updater) {
        String[] modes = {"默认", "空白页", "书签", "网页"};
        ViaUi.radioDialog(this, "主页", modes, prefs.homeMode(), idx -> {
            if (idx == 3) {
                ViaUi.inputDialog(this, "自定义主页",
                        new ViaUi.InputField("https://…", prefs.homeCustomUrl()), null, false,
                        null, (values, check) -> {
                            String url = values[0].trim();
                            if (!url.isEmpty()) {
                                prefs.setHomeCustomUrl(url);
                                prefs.setHomeMode(3);
                            }
                            if (updater != null) updater.update(homeModeLabel());
                        });
            } else {
                prefs.setHomeMode(idx);
                if (updater != null) updater.update(homeModeLabel());
            }
        }).show();
    }

    private void showOrientationDialog(SubtitleUpdater updater) {
        String[] modes = {"跟随系统", "竖屏", "横屏"};
        ViaUi.radioDialog(this, "屏幕方向", modes, prefs.orientationMode(), idx -> {
            prefs.setOrientationMode(idx);
            applyOrientation();
            if (updater != null) updater.update(orientationLabel());
        }).show();
    }

    private void applyOrientation() {
        if (prefs == null) return;
        int mode = prefs.orientationMode();
        if (mode == 1) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else if (mode == 2) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
    }

    private void showDownloadDirDialog(SubtitleUpdater updater) {
        ViaUi.inputDialog(this, "下载目录",
                new ViaUi.InputField("/sdcard/Download", prefs.downloadDir()), null, false,
                null, (values, check) -> {
                    String dir = values[0].trim();
                    if (!dir.isEmpty()) prefs.setDownloadDir(dir);
                    if (updater != null) updater.update(prefs.downloadDir());
                });
    }

    private void showDownloadManagerDialog(SubtitleUpdater updater) {
        String[] modes = {"内建下载器", "系统下载管理器"};
        ViaUi.radioDialog(this, "下载管理", modes, prefs.downloadManager(), idx -> {
            prefs.setDownloadManager(idx);
            if (updater != null) updater.update(modes[idx]);
        }).show();
    }

    private void showPlayerDialog(SubtitleUpdater updater) {
        String[] modes = {"系统分享", "询问每次"};
        ViaUi.radioDialog(this, "外置视频播放器", modes, prefs.externalPlayer(), idx -> {
            prefs.setExternalPlayer(idx);
            if (updater != null) updater.update(modes[idx]);
        }).show();
    }

    private void showRestoreDialog(SubtitleUpdater updater) {
        String[] modes = {"禁用恢复", "总是恢复", "优先询问"};
        ViaUi.radioDialog(this, "启动时恢复未关闭标签", modes, prefs.restoreTabs(), idx -> {
            prefs.setRestoreTabs(idx);
            if (updater != null) updater.update(modes[idx]);
        }).show();
    }

    private void showBookmarkBackupDialog() {
        String[] options = {"备份书签", "导入书签"};
        ViaUi.listDialog(this, "导入/备份书签", options, idx -> {
            if (idx == 0) {
                exportBookmarks();
            } else {
                importBookmarks();
            }
        }).show();
    }

    private void exportBookmarks() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/html")
                .putExtra(Intent.EXTRA_TITLE, "bookmarks.html");
        startActivityForResult(intent, REQ_EXPORT_BOOKMARKS);
    }

    private void importBookmarks() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "text/plain"});
        startActivityForResult(intent, REQ_IMPORT_BOOKMARKS);
    }

    private void showImportDataDialog() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "text/plain", "text/html"});
        startActivityForResult(intent, REQ_IMPORT_DATA);
    }

    private void exportAllData() {
        String[] names = {"书签", "主页收藏", "历史", "关闭的标签页", "设置", "保存的密码"};
        boolean[] checked = {true, true, true, true, true, false};
        ViaUi.checkboxDialog(this, "导出数据", names, checked, values -> {
            pendingExportMask = 0;
            int[] bits = {BrowserBackupManager.BOOKMARKS, BrowserBackupManager.FAVORITES,
                    BrowserBackupManager.HISTORY, BrowserBackupManager.CLOSED_TABS,
                    BrowserBackupManager.SETTINGS, BrowserBackupManager.PASSWORDS};
            for (int i = 0; i < values.length; i++) if (values[i]) pendingExportMask |= bits[i];
            if (pendingExportMask == 0) return;
            if ((pendingExportMask & BrowserBackupManager.PASSWORDS) != 0) {
                ViaUi.passwordDialog(this, "设置备份密码", "恢复保存的密码时需要输入此密码", password -> {
                    pendingExportPassword = password;
                    beginDataExport();
                });
            } else {
                pendingExportPassword = null;
                beginDataExport();
            }
        });
    }

    private void beginDataExport() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "via-backup.zip");
        startActivityForResult(intent, REQ_EXPORT_DATA);
    }

    private void choosePasswordImport() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/csv", "text/plain"}),
                REQ_IMPORT_PASSWORDS);
    }

    private void choosePasswordExport() {
        if (new BrowserPasswordStore(this).list().isEmpty()) {
            GlassToast.makeText(this, "未保存任何密码", GlassToast.LENGTH_SHORT).show();
            return;
        }
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv").putExtra(Intent.EXTRA_TITLE, "passwords.csv"), REQ_EXPORT_PASSWORDS);
    }

    private void openDefaultBrowserSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
        } catch (Exception e) {
            GlassToast.makeText(this, "无法打开系统设置", GlassToast.LENGTH_SHORT).show();
        }
    }

    private void showSyncServerDialog() {
        String[] options = {"全球", "中国", "自定义"};
        int selected = prefs.syncServer().equals("global") ? 0
                : prefs.syncServer().equals("cn") ? 1 : 2;
        ViaUi.radioDialog(this, "云同步服务器", options, selected, idx -> {
            String next = idx == 0 ? "global" : idx == 1 ? "cn" : "custom";
            if (!next.equals(prefs.syncServer())) prefs.clearCloudAccount();
            prefs.setSyncServer(next);
            render(Page.SYNC);
        }).show();
    }

    private void showAddOrEditCustomUa(BrowserPrefs.CustomUaItem editItem) {
        boolean isEdit = editItem != null;
        String title = isEdit ? "编辑" : "新建";
        ViaUi.InputField[] fields = new ViaUi.InputField[]{
                new ViaUi.InputField("标题", isEdit ? editItem.name : ""),
                new ViaUi.InputField("浏览器标识", isEdit ? editItem.ua : "", true)
        };
        ViaUi.inputDialog(this, title, fields, null, false, null, (values, check) -> {
            String name = values.length > 0 && values[0] != null ? values[0].trim() : "";
            String ua = values.length > 1 && values[1] != null ? values[1].trim() : "";
            if (name.isEmpty()) {
                GlassToast.makeText(this, "标题不能为空", GlassToast.LENGTH_SHORT).show();
                return;
            }
            if (ua.isEmpty()) {
                GlassToast.makeText(this, "浏览器标识不能为空", GlassToast.LENGTH_SHORT).show();
                return;
            }
            if (isEdit) {
                prefs.updateCustomUa(editItem.id, name, ua);
                GlassToast.makeText(this, "已保存", GlassToast.LENGTH_SHORT).show();
            } else {
                long newId = prefs.addCustomUa(name, ua);
                prefs.setUaSelectedId(newId);
                GlassToast.makeText(this, "已保存", GlassToast.LENGTH_SHORT).show();
            }
            render(Page.UA);
        });
    }

    private void showCustomUaOptions(BrowserPrefs.CustomUaItem item) {
        String[] options = {"编辑", "删除"};
        new android.app.AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        showAddOrEditCustomUa(item);
                    } else if (which == 1) {
                        prefs.deleteCustomUa(item.id);
                        GlassToast.makeText(this, "已删除", GlassToast.LENGTH_SHORT).show();
                        render(Page.UA);
                    }
                })
                .show();
    }

    private void showImagesDialog(SubtitleUpdater updater) {
        String[] options = {"允许", "阻止"};
        ViaUi.radioDialog(this, "图像", options, prefs.imagesEnabled() ? 0 : 1, idx -> {
            prefs.setImagesEnabled(idx == 0);
            if (updater != null) updater.update(idx == 0 ? "允许" : "阻止");
        }).show();
    }

    private void showJsDialog(SubtitleUpdater updater) {
        String[] options = {"允许", "阻止"};
        ViaUi.radioDialog(this, "JavaScript", options, prefs.jsEnabled() ? 0 : 1, idx -> {
            prefs.setJsEnabled(idx == 0);
            if (updater != null) updater.update(idx == 0 ? "允许" : "阻止");
        }).show();
    }

    private void showCookiesDialog(SubtitleUpdater updater) {
        String[] options = {"允许", "阻止"};
        ViaUi.radioDialog(this, "Cookies", options, prefs.cookiesEnabled() ? 0 : 1, idx -> {
            prefs.setCookiesEnabled(idx == 0);
            if (updater != null) updater.update(idx == 0 ? "允许" : "阻止");
        }).show();
    }

    private void showPopupsDialog(SubtitleUpdater updater) {
        String[] options = {"允许", "阻止"};
        ViaUi.radioDialog(this, "弹出式窗口", options, prefs.popupsEnabled() ? 0 : 1, idx -> {
            prefs.setPopupsEnabled(idx == 0);
            if (updater != null) updater.update(idx == 0 ? "允许" : "阻止");
        }).show();
    }

    private void showSiteDetailDialog(String host) {
        String[] options = {"删除网站数据", "删除该网站设定"};
        ViaUi.radioDialog(this, host + " 的网站设定", options, -1, idx -> {
            if (idx == 0) {
                android.webkit.CookieManager.getInstance().removeAllCookies(null);
                GlassToast.makeText(this, "已删除", GlassToast.LENGTH_SHORT).show();
            } else {
                prefs.resetSiteSettings(host);
                render(Page.SITE_LIST);
            }
        }).show();
    }

    private void showLayoutDialog(SubtitleUpdater updater) {
        String[] options = {"传统", "工具栏在上", "工具栏在下", "双行工具栏"};
        int[] modes = {1, 0, 2, 3};
        int selected = prefs.toolbarMode() == 1 ? 0 : prefs.toolbarMode() == 0 ? 1 : prefs.toolbarMode();
        ViaUi.radioDialog(this, "应用布局", options, selected, index -> {
            prefs.setToolbarMode(modes[index]);
            if (index == 0 || index == 3) prefs.setTabBarEnabled(false);
            render(Page.TOOLBAR);
        }).show();
    }

    private void showAutoHideDialog(SubtitleUpdater updater) {
        String[] options = {"总是显示", "滑动来显示/隐藏", "点击来显示/隐藏"};
        ViaUi.radioDialog(this, "自动隐藏操作栏", options, prefs.toolbarAutoHide(), index -> {
            prefs.setToolbarAutoHide(index);
            if (updater != null) updater.update(options[index]);
        }).show();
    }

    private void showAddressDialog(SubtitleUpdater updater) {
        String[] options = {"标题", "网址", "域名"};
        ViaUi.radioDialog(this, "地址栏内容", options, prefs.addressContent(), idx -> {
            prefs.setAddressContent(idx);
            render(Page.TOOLBAR);
        }).show();
    }

    private void showScriptActions(String name) {
        String[] options = {"编辑", "复制脚本代码", "删除"};
        ViaUi.radioDialog(this, name, options, -1, idx -> {
            if (idx == 0) {
                editScriptName = name;
                open(Page.SCRIPT_CONFIG);
            } else if (idx == 1) {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("script", prefs.scriptCode(name)));
                }
                GlassToast.makeText(this, "脚本代码已复制到剪贴板", GlassToast.LENGTH_SHORT).show();
            } else {
                prefs.removeScript(name);
                render(Page.SCRIPTS);
            }
        }).show();
    }

    private void openCustomizer() {
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_OPEN_CUSTOMIZER, true));
        finish();
    }

    private void openHomeCustomize() {
        startActivity(new Intent(this, BrowserHomeCustomizeActivity.class));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT_BOOKMARKS) {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("无法写入文件");
                    BrowserBackupManager.exportBookmarks(this, output);
                }
                GlassToast.makeText(this, "书签已备份", GlassToast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_IMPORT_BOOKMARKS || requestCode == REQ_IMPORT_DATA) {
                int count;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new java.io.IOException("无法读取文件");
                    count = BrowserBackupManager.importBackup(this, input);
                } catch (IllegalArgumentException passwordRequired) {
                    if (requestCode != REQ_IMPORT_DATA) throw passwordRequired;
                    ViaUi.passwordDialog(this, "输入备份密码", "该备份包含保存的密码", password ->
                            importProtectedBackup(uri, password));
                    return;
                }
                AdBlockRuleLoader.reloadAsync(this);
                GlassToast.makeText(this, "导入完成" + (count > 0 ? "（" + count + " 条）" : ""), GlassToast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_EXPORT_DATA) {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("无法写入文件");
                    BrowserBackupManager.exportBackup(this, output, pendingExportMask, pendingExportPassword);
                }
                pendingExportPassword = null;
                GlassToast.makeText(this, "数据已导出", GlassToast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_IMPORT_SCRIPT) {
                String code;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new java.io.IOException("无法读取文件");
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                    code = out.toString("UTF-8");
                }
                importUserScript(code);
            } else if (requestCode == REQ_IMPORT_PASSWORDS) {
                String csv;
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new java.io.IOException("无法读取文件");
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int n;
                    while ((n = input.read(buffer)) >= 0) if (n > 0) out.write(buffer, 0, n);
                    csv = out.toString("UTF-8");
                }
                int count = new BrowserPasswordStore(this).importCsv(csv);
                GlassToast.makeText(this, "已导入 " + count + " 个密码", GlassToast.LENGTH_SHORT).show();
            } else if (requestCode == REQ_EXPORT_PASSWORDS) {
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("无法写入文件");
                    output.write(new BrowserPasswordStore(this).csv().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                GlassToast.makeText(this, "密码已导出", GlassToast.LENGTH_SHORT).show();
            }
        } catch (Exception error) {
            GlassToast.makeText(this, "操作失败：" + error.getMessage(), GlassToast.LENGTH_LONG).show();
        }
    }

    private void importProtectedBackup(Uri uri, String password) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new java.io.IOException("无法读取文件");
            int count = BrowserBackupManager.importBackup(this, input, password);
            AdBlockRuleLoader.reloadAsync(this);
            GlassToast.makeText(this, "导入完成" + (count > 0 ? "（" + count + " 条）" : ""), GlassToast.LENGTH_SHORT).show();
        } catch (Exception error) {
            GlassToast.makeText(this, "操作失败：密码错误或备份已损坏", GlassToast.LENGTH_LONG).show();
        }
    }

    // ================= 标签辅助 =================

    private String uaPresetLabel(int preset) {
        return preset >= 0 && preset < UA_PRESETS.length ? UA_PRESETS[preset] : UA_PRESETS[0];
    }

    private String toolbarModeLabel() {
        switch (prefs.toolbarMode()) {
            case 0: return "工具栏在上";
            case 2: return "工具栏在下";
            case 3: return "双行工具栏";
            default: return "传统";
        }
    }

    private String autoHideLabel() {
        switch (prefs.toolbarAutoHide()) {
            case 1: return "滑动来显示/隐藏";
            case 2: return "点击来显示/隐藏";
            default: return "总是显示";
        }
    }

    private String languageLabel() {
        switch (prefs.language()) {
            case 1: return "English";
            case 2: return "中文 (简体)";
            case 3: return "中文 (繁體)";
            default: return "跟随系统";
        }
    }

    private String homeModeLabel() {
        switch (prefs.homeMode()) {
            case 1: return "空白页";
            case 2: return "书签";
            case 3: return prefs.homeCustomUrl();
            default: return "默认";
        }
    }

    private String orientationLabel() {
        switch (prefs.orientationMode()) {
            case 1: return "竖屏";
            case 2: return "横屏";
            default: return "跟随系统";
        }
    }

    private String restoreLabel() {
        switch (prefs.restoreTabs()) {
            case 1: return "总是恢复";
            case 2: return "优先询问";
            default: return "禁用恢复";
        }
    }

    private String engineLabel() {
        String[] engines = {"YouTube", "Google", "Bing", "百度", "DuckDuckGo", "自定义", "Yahoo", "Startpage"};
        int idx = prefs.searchEngine();
        return idx >= 0 && idx < engines.length ? engines[idx] : engines[0];
    }

    private String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f);
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.0";
        }
    }
}
