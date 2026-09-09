package com.example.cleanrecovery.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.net.http.SslCertificate;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebResourceRequest;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.background.BackgroundDownloadService;
import com.example.cleanrecovery.background.DownloadQueueManager;
import com.example.cleanrecovery.download.DownloadProgressCallback;
import com.example.cleanrecovery.download.UniversalDownloadManager;
import com.example.cleanrecovery.extractor.MediaSniffer;
import com.example.cleanrecovery.proxy.ProxyActivity;
import com.example.cleanrecovery.proxy.ProxyRouter;
import com.example.cleanrecovery.ui.feed.VideoFeedActivity;
import com.example.cleanrecovery.ui.browser.AdBlockRuleLoader;
import com.example.cleanrecovery.ui.browser.AdFilterEngine;
import com.example.cleanrecovery.ui.browser.AddressSuggestPopup;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import com.example.cleanrecovery.ui.browser.BrowserAdBlocker;
import com.example.cleanrecovery.ui.browser.BrowserBottomMenu;
import com.example.cleanrecovery.ui.browser.NavigationGuard;
import com.example.cleanrecovery.ui.browser.PageClickCollector;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.BrowserWebView;
import com.example.cleanrecovery.ui.browser.BrowserReader;
import com.example.cleanrecovery.ui.browser.BrowserPasswordStore;
import com.example.cleanrecovery.ui.browser.BrowserUserScripts;
import com.example.cleanrecovery.ui.browser.BrowserAiClient;
import com.example.cleanrecovery.ui.browser.BrowserAiStore;
import com.example.cleanrecovery.ui.browser.BrowserAiMarkdown;
import com.example.cleanrecovery.ui.browser.BrowserAiPrompt;
import com.example.cleanrecovery.ui.browser.SearchEngines;
import com.example.cleanrecovery.ui.browser.SnifferLog;
import com.example.cleanrecovery.ui.browser.TabManager;
import com.example.cleanrecovery.ui.browser.BrowserResourcePage;
import com.example.cleanrecovery.ui.browser.ViaUi;
import com.example.cleanrecovery.ui.browser.ViaAddressResolver;
import com.example.cleanrecovery.ui.browser.ViaSnifferStateMachine;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;
import com.example.cleanrecovery.util.FaviconFetcher;
import com.google.android.material.snackbar.Snackbar;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VIA 风格内置浏览器 Activity。
 *
 * <p>核心交互 1:1 复刻 VIA 浏览器：顶部地址栏（后退|前进|地址|主页|标签角标|菜单）、
 * 顶部加载进度条、多标签、书签、历史、设置、资源嗅探（保留原有嗅探链路）。</p>
 *
 * <p>媒体嗅探保存候选，用户确认下载后才加入后台队列。</p>
 */
public final class BrowserActivity extends Activity {

    private static final String TAG = "BrowserActivity";
    private static final int REQ_BOOKMARKS = 1002;
    private static final int REQ_HISTORY = 1003;
    private static final int REQ_SETTINGS = 1004;
    private static final int REQ_PROXY = 1005;
    private static final int REQ_OFFLINE = 1006;
    private static final int REQ_SNIFFER = 1007;
    private static final int REQ_SITE_SETTINGS = 1008;
    private static final int REQ_AI_EXPORT = 1009;
    private static final int REQ_SCRIPT_SETTINGS = 1010;
    private String scriptsBeforeEditing;
    private String pendingAiExport;
    private android.app.Dialog aiChatDialog;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private FrameLayout webContainer;
    private FrameLayout toolbar;
    private com.example.cleanrecovery.ui.browser.BrowserToolbarLayout toolbarLayout;
    private boolean toolbarHidden;
    private ImageButton toolbarReveal;
    private LinearLayout barHome;
    private LinearLayout barPage;
    private LinearLayout barEdit;
    private LinearLayout siteCard;
    private TextView siteCardTitle;
    private TextView siteCardUrl;
    private LinearLayout findBar;
    private View nightMask;
    /** 隐身模式主页小提醒（kb：浏览将不会被记录）。 */
    private TextView incognitoHint;
    private android.app.Dialog readAloudBar;
    /** VIA 标签底部面板。 */
    private android.app.Dialog tabSheet;
    private View engineAvatar;
    private EditText findInput;
    /** 盾牌拦截数徽标。 */
    private TextView adBadge;
    /** HTML5 全屏视频（onShowCustomView）。 */
    private View fullscreenVideoView;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private float fullscreenBrightness = -1f;
    private int savedOrientation;
    private TextView pageBarTitle;
    private ImageView reloadIcon;
    private long lastBackPressAt;
    private LinearLayout bottomNavigation;
    private ImageButton siteInfoButton;
    private ImageButton scanButton;
    private TextView homeTitleView;
    private FrameLayout tabsButtonContainer;
    private EditText urlInput;
    private AddressSuggestPopup suggestPopup;
    private EditText homeSearch;
    private LinearLayout homeSearchEngineRow;
    private LinearLayout bottomSearchEngineRow;
    private View searchToolbarContainer;
    private HorizontalScrollView searchToolbarScroll;
    private LinearLayout searchToolbarChips;
    private int activeSearchEngine = -1;
    private boolean showSearchEngineSwitcher;
    private String lastSearchQuery = "";
    private boolean addressBarEditing;
    private boolean searchImeWasVisible;
    private boolean endingSearchInput;
    /** 由第三方 Custom Tabs 会话发起的临时页面。 */
    private boolean customTabMode;
    private ImageButton navBackButton;
    private ImageButton navForwardButton;
    private ImageButton homeButton;
    private ImageButton menuButton;
    private TextView tabBadge;
    private ProgressBar progressbar;
    private ScrollView homeScroll;
    private WebView homeCustomWeb;
    private String homeCustomHtml = "";
    private ImageView homeLogo;
    private GridLayout homeGrid;
    private ImageButton homeAddButton;

    private final TabManager tabs = new TabManager();
    /** VIA 嗅探状态机：角标显隐与不支持站点策略。 */
    private final ViaSnifferStateMachine viaSniffer = new ViaSnifferStateMachine();
    private ImageButton snifferButton;
    private BrowserPrefs prefs;
    private BrowserReader reader;
    private boolean restorePromptPending;
    private boolean hasRestorableClosedTab;
    private com.example.cleanrecovery.ui.browser.BrowserPermissionController permissions;
    private BrowserDatabaseHelper dbHelper;
    private UniversalDownloadManager downloadManager;

    /** 全屏状态。 */
    private boolean fullscreen = false;
    private boolean gameMode = false;
    private TextToSpeech textToSpeech;
    /** 页面内查找：匹配总数（异步回调更新）。 */
    private int findCount = 0;
    private int findIndex = 0;

    /** 每个标签的嗅探状态。 */
    private static final class TabState {
        final MediaSniffer sniffer;
        final List<String> networkLog = new ArrayList<>();
        BrowserAdBlocker adBlocker;
        PageClickCollector clickCollector;
        boolean adMarkerArmed;
        boolean incognito;
        /** 当前页累计拦截数（盾牌徽标）。 */
        int pageBlockedCount;
        int resourceSourceId = -1;
        long lastTouchTimeMs = 0;
        int lastHitType = 0;
        int toolbarColor = Color.WHITE;
        int readerStatus;
        long readerHintUntil;
        int documentGeneration;

        TabState(MediaSniffer sniffer) {
            this.sniffer = sniffer;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) pendingAiExport = savedInstanceState.getString("ai_export_topic");
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser);

        prefs = new BrowserPrefs(this);
        customTabMode = isCustomTabIntent(getIntent()) && !prefs.disableCustomTabs();
        if (prefs.webdavAutoSync() && !prefs.webdavUrl().trim().isEmpty()
                && prefs.webdavSyncData() != 0) {
            new Thread(() -> com.example.cleanrecovery.ui.browser.BrowserWebdavSynchronizer.sync(this),
                    "webdav-auto-sync").start();
        }
        permissions = new com.example.cleanrecovery.ui.browser.BrowserPermissionController(this, prefs);
        dbHelper = BrowserDatabaseHelper.getInstance(this);
        WebView.setWebContentsDebuggingEnabled(prefs.webDebug());
        // Service Worker 请求过滤（App 级一次；绕过 shouldInterceptRequest 盲区的 SW 流量）
        BrowserAdBlocker.installServiceWorkerFilter();
        // 广告规则装载（内置+自定义+订阅缓存，后台；过期订阅按需联网更新）
        AdBlockRuleLoader.reloadAsync(this);

        navBackButton = findViewById(R.id.browser_nav_back);
        navForwardButton = findViewById(R.id.browser_nav_forward);
        urlInput = findViewById(R.id.browser_url_input);
        homeButton = findViewById(R.id.browser_home);
        menuButton = findViewById(R.id.browser_menu);
        tabBadge = findViewById(R.id.browser_tab_badge);
        navBackButton.setContentDescription(getString(R.string.via_web_back));
        navForwardButton.setContentDescription(getString(R.string.via_web_forward));
        homeButton.setContentDescription(getString(R.string.via_home_title));
        progressbar = findViewById(R.id.browser_progress);

        webContainer = findViewById(R.id.browser_web_container);
        homeScroll = findViewById(R.id.browser_home_scroll);
        nightMask = findViewById(R.id.browser_night_mask);
        incognitoHint = findViewById(R.id.browser_incognito_hint);
        homeLogo = findViewById(R.id.browser_home_logo);
        homeGrid = findViewById(R.id.browser_home_grid);
        homeScroll.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (r - l != oldR - oldL) layoutHomeBookmarks();
        });
        homeSearch = findViewById(R.id.browser_home_search);
        toolbar = findViewById(R.id.browser_toolbar);
        barHome = findViewById(R.id.browser_bar_home);
        barPage = findViewById(R.id.browser_bar_page);
        barEdit = findViewById(R.id.browser_bar_edit);
        pageBarTitle = findViewById(R.id.browser_page_title);
        siteCard = findViewById(R.id.browser_site_card);
        siteCardTitle = findViewById(R.id.browser_site_card_title);
        siteCardUrl = findViewById(R.id.browser_site_card_url);
        findBar = findViewById(R.id.browser_find_bar);
        findInput = findViewById(R.id.browser_find_input);
        reloadIcon = findViewById(R.id.browser_reload);
        ImageView searchToggle = findViewById(R.id.browser_search_toggle);
        ImageView hideBarIcon = findViewById(R.id.browser_hide_bar);
        TextView homeBarTitle = findViewById(R.id.browser_home_title);
        ImageView siteInfoIcon = findViewById(R.id.browser_site_info);
        adBadge = findViewById(R.id.browser_ad_badge);
        ImageView urlGoButton = findViewById(R.id.browser_url_go);
        tabsButtonContainer = (FrameLayout) findViewById(R.id.browser_tabs).getParent();
        createViaToolbarCompanions();
        toolbarLayout = new com.example.cleanrecovery.ui.browser.BrowserToolbarLayout((LinearLayout) toolbar.getParent(), false);
        searchToolbarContainer = findViewById(R.id.browser_search_toolbar_container);
        searchToolbarScroll = findViewById(R.id.browser_search_toolbar_scroll);
        searchToolbarChips = findViewById(R.id.browser_search_toolbar_chips);
        searchToggle.setOnClickListener(v -> {
            if (isHomeVisible()) {
                focusAddressBar();
            } else {
                toggleSiteCard();
            }
        });
        siteInfoIcon.setOnClickListener(v -> onReaderIconClick());
        urlGoButton.setOnClickListener(v -> loadUrlFromInput());
        findViewById(R.id.browser_app_home).setOnClickListener(v -> returnToAppHome());
        findViewById(R.id.browser_page_home).setOnClickListener(v -> returnToAppHome());
        findViewById(R.id.browser_edit_clear).setOnClickListener(v -> { urlInput.setText(""); urlInput.requestFocus(); });
        homeBarTitle.setOnClickListener(v -> focusAddressBar());
        hideBarIcon.setOnClickListener(v -> launchQrScanner());
        findViewById(R.id.browser_edit_scan).setOnClickListener(v -> launchQrScanner());
        findViewById(R.id.browser_edit_search).setOnClickListener(v -> showEnginePicker());
        pageBarTitle.setOnClickListener(v -> focusAddressBar());
        pageBarTitle.setHighlightColor(0x00000000);
        reloadIcon.setOnClickListener(v -> {
            TabManager.Tab tab = tabs.current();
            if (tab != null) tab.webView.reload();
        });
        engineAvatar = findViewById(R.id.browser_engine_avatar);
        engineAvatar.setOnClickListener(v -> showEnginePicker());
        findViewById(R.id.browser_site_card_history).setOnClickListener(v -> {
            hideSiteCard();
            Intent it = new Intent(this, HistoryActivity.class);
            it.putExtra("initial_query", hostFromUrl(currentUrl()));
            startActivityForResult(it, REQ_HISTORY);
        });
        findViewById(R.id.browser_site_card_qr).setOnClickListener(v -> {
            hideSiteCard();
            showPageQrDialog();
        });
        findViewById(R.id.browser_site_card_cert).setOnClickListener(v -> {
            hideSiteCard();
            showCertificateDialog(tabs.current());
        });
        findViewById(R.id.browser_site_card_cookies).setOnClickListener(v -> {
            hideSiteCard();
            showCookiesDialog(currentUrl());
        });
        findViewById(R.id.browser_site_card_settings).setOnClickListener(v -> {
            hideSiteCard();
            showSiteConfiguration();
        });
        findViewById(R.id.browser_site_card_scripts).setOnClickListener(v -> {
            hideSiteCard();
            showPageScripts();
        });
        findViewById(R.id.browser_find_prev).setOnClickListener(v -> findNextInPage(false));
        findViewById(R.id.browser_find_next).setOnClickListener(v -> findNextInPage(true));
        findViewById(R.id.browser_find_close).setOnClickListener(v -> hideFindBar());
        findInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) {
                runFindInPage();
                return true;
            }
            return false;
        });

        applyToolbarMode();

        // 只恢复已有任务，空闲浏览不启动下载服务。
        try {
            if (!com.example.cleanrecovery.background.DownloadTaskDbHelper.getInstance(this).getRestorableTasks().isEmpty())
                startService(new Intent(this, BackgroundDownloadService.class));
        } catch (Exception e) {
            Log.w(TAG, "后台下载服务启动失败: " + e.getMessage());
        }

        // 地址栏 / 主页胶囊搜索回车
        android.widget.TextView.OnEditorActionListener goAction = (v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN)) {
                if (v == homeSearch) urlInput.setText(homeSearch.getText());
                loadUrlFromInput();
                return true;
            }
            return false;
        };
        urlInput.setOnEditorActionListener(goAction);
        urlInput.setOnClickListener(v -> focusAddressBar());
        urlInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) addressBarEditing = true;
            else if (!endingSearchInput && addressBarEditing) v.post(() -> {
                if (!addressBarEditing || urlInput.hasFocus()) return;
                if (homeSearch != null && homeSearch.hasFocus()) {
                    addressBarEditing = false;
                    if (suggestPopup != null) suggestPopup.dismiss();
                    applyToolbarMode();
                } else endSearchInput();
            });
        });
        // P0② 地址栏建议下拉：文本变化即刷新（本地源同步 + 引擎联想异步）
        suggestPopup = new AddressSuggestPopup(this, suggestHost());
        suggestPopup.setAnchor(findViewById(R.id.browser_toolbar));
        urlInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suggestPopup == null) return;
                if (!addressBarEditing) {
                    suggestPopup.dismiss();
                    return;
                }
                suggestPopup.onTextChanged(s.toString());
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });
        if (homeSearch != null) {
            homeSearch.setOnEditorActionListener(goAction);
        }
        getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(getWindow().getDecorView());
            if (insets == null) return;
            boolean visible = insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime());
            boolean wasVisible = searchImeWasVisible; searchImeWasVisible = visible;
            if (wasVisible && !visible && (addressBarEditing || homeSearch.hasFocus()
                    || (homeCustomWeb != null && homeCustomWeb.getVisibility() == View.VISIBLE && homeCustomWeb.hasFocus()))) endSearchInput();
        });
        if (homeLogo != null) {
            homeLogo.setOnClickListener(v -> {
                Intent it = new Intent(this, BrowserHomeCustomizeActivity.class);
                it.putExtra(BrowserHomeCustomizeActivity.EXTRA_PAGE, "logo");
                startActivityForResult(it, REQ_SETTINGS);
            });
        }
        updateSearchHints();
        applyHomeCustomization();

        navBackButton.setOnClickListener(v -> {
            TabManager.Tab cur = tabs.current();
            if (cur != null && cur.canGoBack()) navigateHistory(cur.webView, -1);
            else if (cur != null && isResourcePage(cur, cur.url)) closeResourcePage(cur);
        });
        navForwardButton.setOnClickListener(v -> {
            TabManager.Tab cur = tabs.current();
            if (cur != null && cur.canGoForward()) navigateHistory(cur.webView, 1);
        });
        homeButton.setOnClickListener(v -> showHome());
        menuButton.setOnClickListener(v -> showMainMenu());
        View tabsButton = findViewById(R.id.browser_tabs);
        tabsButton.setOnClickListener(v -> openTabs());
        tabsButtonContainer.setOnClickListener(v -> openTabs());
        int[] gestureIds = {R.id.browser_nav_back, R.id.browser_nav_forward, R.id.browser_home, R.id.browser_tabs, R.id.browser_menu};
        String[] gestureKeys = {"back", "forward", "home", "tab", "menu"};
        for (int i = 0; i < gestureIds.length; i++) {
            String key = gestureKeys[i];
            findViewById(gestureIds[i]).setOnLongClickListener(v -> {
                performBrowserAction(prefs.gestureAction(key));
                return true;
            });
        }
        tabsButtonContainer.setOnLongClickListener(v -> {
            performBrowserAction(prefs.gestureAction("tab"));
            return true;
        });
        configureCustomTabUi();
        java.util.function.IntConsumer toolbarSwipe = direction -> {
            if (!addressBarEditing) performBrowserAction(prefs.gestureAction(direction < 0 ? "left" : "right"));
        };
        com.example.cleanrecovery.ui.browser.BrowserToolbarGestures.bind(findViewById(R.id.browser_bottom_bar), toolbarSwipe);
        com.example.cleanrecovery.ui.browser.BrowserToolbarGestures.bind(toolbar, toolbarSwipe);
        homeAddButton = new ImageButton(this);
        homeAddButton.setImageResource(R.drawable.ic_add);
        homeAddButton.setBackgroundResource(R.drawable.bg_via_menu_cell);
        homeAddButton.setContentDescription(getString(R.string.via_home_add));
        homeAddButton.setOnClickListener(v -> showAddQuickLinkDialog());

        // 初始标签：无显式 URL 时进入 VIA 九宫格主页。兼容 VIA 默认浏览器入口：
        // ACTION_VIEW(data)、ACTION_SEND(text/plain)、WEB_SEARCH/PROCESS_TEXT(query)。
        String initialUrl = resolveLaunchUrl(getIntent());
        if (initialUrl == null || initialUrl.isEmpty()) {
            String session = prefs.sessionTabs();
            if (prefs.restoreTabs() == 1 && restoreSession(session)) {
                // Restored tabs supply the initial selection.
            } else {
                newTab("");
                if (prefs.restoreTabs() == 2 && !session.isEmpty()) {
                    restorePromptPending = true;
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("恢复未关闭标签")
                            .setMessage("是否恢复上次未关闭的标签？")
                            .setNegativeButton("取消", (dialog, which) -> {
                                restorePromptPending = false;
                                prefs.setSessionTabs("");
                            })
                            .setOnCancelListener(dialog -> restorePromptPending = false)
                            .setPositiveButton("恢复", (dialog, which) -> {
                                restorePromptPending = false;
                                if (restoreSession(session)) closeAndDestroyTab(0);
                            }).show();
                }
            }
        } else {
            if (prefs.restoreTabs() == 1) restoreSession(prefs.sessionTabs());
            newTab(initialUrl);
        }
        updateProxyIndicator();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (fullscreenVideoView == null) {
            setRequestedOrientation(prefs.orientationMode() == 1
                    ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    : prefs.orientationMode() == 2 ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        applyToolbarMode();
        // 从 ProxyActivity 返回后，按当前代理状态重新 apply/clear
        TabManager.Tab cur = tabs.current();
        if (cur != null && cur.webView != null) {
            if (ProxyRouter.isProxyActive()) {
                ProxyRouter.applyProxy(cur.webView);
            } else {
                ProxyRouter.clearProxy();
            }
        }
        updateProxyIndicator();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        customTabMode = isCustomTabIntent(intent) && !prefs.disableCustomTabs();
        configureCustomTabUi();
        String target = resolveLaunchUrl(intent);
        if (target != null && !target.trim().isEmpty()) {
            ViaAddressResolver.Resolution resolution = ViaAddressResolver.resolve(
                    target,
                    SearchEngines.prefix(prefs, prefs.searchEngine()),
                    false);
            if (resolution.getOutput() != null && !resolution.getOutput().isEmpty()) {
                loadUrlInCurrent(resolution.getOutput());
            }
        }
    }

    /**
     * 解析所有 VIA 类入口传入的导航目标。
     *
     * <p>优先级：显式 extra(url) → ACTION_VIEW data → ACTION_SEND/PROCESS_TEXT 文本 →
     * WEB_SEARCH query。返回原始用户输入，后续统一走 VIA 地址栏解析器。</p>
     */
    private static String resolveLaunchUrl(Intent intent) {
        if (intent == null) return null;
        String explicit = intent.getStringExtra("url");
        if (explicit != null && !explicit.trim().isEmpty()) return explicit.trim();
        Uri data = intent.getData();
        if (data != null) return data.toString();
        CharSequence processText = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        if (processText != null && processText.length() > 0) {
            return processText.toString().trim();
        }
        String webQuery = intent.getStringExtra(android.app.SearchManager.QUERY);
        if (webQuery != null && !webQuery.trim().isEmpty()) return webQuery.trim();
        CharSequence sent = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (sent != null && sent.length() > 0) return sent.toString().trim();
        return null;
    }

    private static boolean isCustomTabIntent(Intent intent) {
        return intent != null && (intent.hasExtra("android.support.customtabs.extra.SESSION")
                || intent.hasExtra("androidx.browser.customtabs.extra.SESSION"));
    }

    /** 外部 Custom Tab 是临时页面：关闭按钮结束会话，标签管理不暴露给调用方。 */
    private void configureCustomTabUi() {
        if (homeButton == null || tabsButtonContainer == null) return;
        tabsButtonContainer.setVisibility(customTabMode ? View.GONE : View.VISIBLE);
        if (customTabMode) {
            homeButton.setImageResource(R.drawable.ic_close);
            homeButton.setContentDescription("关闭");
            homeButton.setOnClickListener(v -> finish());
        } else {
            homeButton.setImageResource(R.drawable.via_nav_home);
            homeButton.setContentDescription(getString(R.string.via_home_title));
            homeButton.setOnClickListener(v -> showHome());
        }
        configureCustomTabTopAction();
    }

    /** 更新页面顶栏右侧控件：站点信息面板优先占用关闭按钮。 */
    private void configureCustomTabTopAction() {
        if (reloadIcon == null) return;
        if (siteCard != null && siteCard.getVisibility() == View.VISIBLE) {
            reloadIcon.setImageResource(R.drawable.via_toolbar_stop);
            reloadIcon.setContentDescription("关闭");
            reloadIcon.setOnClickListener(v -> hideSiteCard());
        } else if (customTabMode) {
            reloadIcon.setImageResource(R.drawable.via_toolbar_stop);
            reloadIcon.setContentDescription("关闭");
            reloadIcon.setOnClickListener(v -> finish());
        } else {
            TabManager.Tab current = tabs.current();
            boolean loading = current != null && !isHomeVisible() && current.webView.getProgress() < 100;
            reloadIcon.setImageResource(loading ? R.drawable.via_toolbar_stop : R.drawable.via_toolbar_refresh);
            reloadIcon.setContentDescription(loading ? "停止加载" : getString(R.string.via_menu_reload));
            reloadIcon.setOnClickListener(v -> {
                TabManager.Tab tab = tabs.current();
                if (tab == null) return;
                if (tab.webView.getProgress() < 100) tab.webView.stopLoading(); else tab.webView.reload();
                configureCustomTabTopAction();
            });
        }
    }

    /** 显示 VIA 风格主页（Logo + 胶囊搜索 + 快捷链接）。 */
    private void returnToAppHome() {
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    private void showHome() {
        endSearchInput();
        if ((prefs.homeMode() == 1 || prefs.homeMode() == 3)
                && !com.example.cleanrecovery.ui.browser.BrowserInternalUrls.isHome(prefs.homeUrl())) {
            TabManager.Tab tab = tabs.current();
            if (tab == null) {
                newTab(prefs.homeUrl());
            } else {
                tab.url = prefs.homeUrl();
                hideHome();
                addressBarEditing = false;
                tab.webView.loadUrl(tab.url);
                urlInput.setText(tab.url);
                applyToolbarMode();
            }
            return;
        }
        renderHomeGrid();
        addressBarEditing = false;
        homeScroll.setVisibility(!usesHtmlHome() ? View.VISIBLE : View.GONE);
        if (homeCustomWeb != null) homeCustomWeb.setVisibility(
                !usesHtmlHome() ? View.GONE : View.VISIBLE);
        applyToolbarMode();
        urlInput.setText("");
        if (homeSearch != null) homeSearch.setText("");
        showSearchEngineSwitcher = false;
        lastSearchQuery = "";
        rebuildSearchEngineRow();
        setSearchEngineRowVisible(false);
        TabManager.Tab cur = tabs.current();
        if (cur != null) {
            cur.url = "";
            updateNavButtons(cur);
        }
        applyToolbarMode();
        updateSnifferButton();
    }

    private void hideHome() {
        endSearchInput();
        homeScroll.setVisibility(View.GONE);
        if (homeCustomWeb != null) homeCustomWeb.setVisibility(View.GONE);
    }

    private boolean usesHtmlHome() {
        return !TextUtils.isEmpty(prefs.homeCustomCss()) || prefs.homeLogoMode() == 2
            || prefs.homeLogoMode() != 0 || prefs.homeSearchEffect() == 1 || !prefs.homeBackgroundUri().isEmpty()
            || prefs.homeLogoWidth() != 0 || prefs.homeLogoSize() != 72 || prefs.homeLogoRadius() != 50
            || prefs.homeSearchRadius() != 100 || prefs.homeSearchAlpha() != 0 || prefs.homeSearchStroke() != 1
            || prefs.homeSearchStrokeAlpha() != 24 || prefs.homeSearchStyle() != 0 || prefs.homeSearchLine()
            || !prefs.homeSearchVisible() || prefs.homeBackgroundColor() != Color.WHITE
            || prefs.homeFavoriteWidth() != 46 || prefs.homeFavoriteHeight() != 46 || prefs.homeFavoriteRadius() != 100
            || prefs.homeFavoriteIconStyle() != 0 || prefs.homeFavoriteIconColor() != 0
            || prefs.homeFavoriteIconDisabled() || prefs.homeFavoriteTitleDisabled();
    }

    private boolean isHomeVisible() {
        return (homeScroll != null && homeScroll.getVisibility() == View.VISIBLE)
            || (homeCustomWeb != null && homeCustomWeb.getVisibility() == View.VISIBLE);
    }

    private void applyHomeCustomization() {
        if (homeScroll == null || homeSearch == null || homeLogo == null) return;
        applyCustomHomeCss();
        homeScroll.setBackgroundColor(prefs.nightMode() ? Color.BLACK : prefs.homeBackgroundColor());
        String bgUri = prefs.homeBackgroundUri();
        if (!TextUtils.isEmpty(bgUri)) {
            try {
                android.graphics.drawable.Drawable bg = android.graphics.drawable.Drawable.createFromStream(
                        getContentResolver().openInputStream(Uri.parse(bgUri)), null);
                if (bg != null) {
                    bg.setAlpha((int) (255 * (prefs.homeBackgroundOpacity() / 100f)));
                    homeScroll.setBackground(bg);
                }
            } catch (Exception ignored) { homeScroll.setBackgroundColor(prefs.nightMode() ? Color.BLACK : prefs.homeBackgroundColor()); }
        }
        int mode = prefs.homeLogoMode();
        int height = dp(prefs.homeLogoSize());
        int width = prefs.homeLogoWidth() == 0 ? Math.round(height * 124f / 90f) : dp(prefs.homeLogoWidth());
        ViewGroup.LayoutParams lp = homeLogo.getLayoutParams();
        lp.width = width; lp.height = height; homeLogo.setLayoutParams(lp);
        homeLogo.setVisibility(mode == 4 ? View.GONE : View.VISIBLE);
        homeLogo.setPadding(0, 0, 0, 0);
        int logoRadius = (int) ((Math.min(width, height) / 2f) * (prefs.homeLogoRadius() / 100f));
        GradientDrawable logoClip = new GradientDrawable();
        logoClip.setColor(0x00ffffff);
        logoClip.setCornerRadius(logoRadius);
        homeLogo.setBackground(logoClip);
        homeLogo.setClipToOutline(prefs.homeLogoRadius() > 0);
        if (mode == 1 && !TextUtils.isEmpty(prefs.homeLogoUri())) {
            try { homeLogo.setImageURI(Uri.parse(prefs.homeLogoUri())); } catch (Exception e) { homeLogo.setImageResource(R.drawable.ic_via_logo); }
            homeLogo.setScaleType(ImageView.ScaleType.CENTER_CROP);
        } else if (mode == 2 || mode == 3) {
            android.graphics.Bitmap bm = textLogoBitmap(prefs.homeLogoText(), Math.max(width, height), prefs.homeLogoBold(), prefs.homeLogoItalic());
            homeLogo.setImageBitmap(bm);
            homeLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        } else {
            homeLogo.setImageResource(R.drawable.ic_via_logo);
            homeLogo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        }

        homeSearch.setVisibility(prefs.homeSearchVisible() ? View.VISIBLE : View.GONE);
        GradientDrawable searchBg = new GradientDrawable();
        searchBg.setShape(GradientDrawable.RECTANGLE);
        searchBg.setCornerRadius(dp((int)((23 + prefs.homeSearchStroke()) * prefs.homeSearchRadius() / 100f)));
        int alpha = (int) (255 * (prefs.homeSearchAlpha() / 100f));
        searchBg.setColor((alpha << 24) | (prefs.nightMode() ? 0x001c1c1e : 0x00ffffff));
        homeSearch.setTextColor(prefs.nightMode() ? 0xffcccccc : 0xff212121);
        homeSearch.setHintTextColor(prefs.nightMode() ? 0xff999999 : 0xff757575);
        int strokeAlpha = Math.round(255 * prefs.homeSearchStrokeAlpha() / 100f);
        int strokeColor = (strokeAlpha << 24);
        if (prefs.homeSearchStyle() == 1 || prefs.homeSearchLine()) searchBg.setStroke(Math.max(1, dp(1)), strokeColor);
        else if (prefs.homeSearchStroke() > 0) searchBg.setStroke(dp(prefs.homeSearchStroke()), strokeColor);
        if (prefs.homeSearchStyle() == 1 || prefs.homeSearchLine()) {
            searchBg.setStroke(0, Color.TRANSPARENT); searchBg.setCornerRadius(0);
            android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
            line.setColor(strokeColor);
            android.graphics.drawable.LayerDrawable layers = new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{searchBg, line});
            layers.setLayerHeight(1, Math.max(dp(1), dp(prefs.homeSearchStroke())));
            layers.setLayerGravity(1, Gravity.BOTTOM);
            homeSearch.setBackground(layers);
        } else homeSearch.setBackground(searchBg);
        homeSearch.setElevation(prefs.homeSearchEffect() == 1 ? dp(5) : 0);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applyCustomHomeCss() {
        String customCss = prefs.homeCustomCss();
        boolean atHome = tabs.current() == null || TextUtils.isEmpty(tabs.current().url);
        if (!usesHtmlHome()) {
            if (homeCustomWeb != null) homeCustomWeb.setVisibility(View.GONE);
            return;
        }
        if (homeCustomWeb == null) {
            homeCustomWeb = new WebView(this) {
                @Override public android.view.inputmethod.InputConnection onCreateInputConnection(android.view.inputmethod.EditorInfo info) {
                    android.view.inputmethod.InputConnection connection = super.onCreateInputConnection(info);
                    info.imeOptions |= android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                        | android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN;
                    return connection;
                }
            };
            homeCustomWeb.setOnLongClickListener(v -> {
                WebView.HitTestResult hit = homeCustomWeb.getHitTestResult();
                String url = hit == null ? null : hit.getExtra();
                if (url == null) return false;
                boolean bookmarks = prefs.homeMode() == 2;
                java.util.List<BrowserDatabaseHelper.Entry> entries = bookmarks ? dbHelper.listBookmarks() : dbHelper.listQuickLinks();
                for (BrowserDatabaseHelper.Entry entry : entries) if (url.equals(entry.url)) {
                    new AlertDialog.Builder(this).setTitle(R.string.via_home_remove).setMessage(entry.title + "\n" + entry.url)
                        .setPositiveButton(android.R.string.ok, (d, w) -> {
                            if (bookmarks) dbHelper.removeBookmark(entry.id); else dbHelper.removeQuickLink(entry.id);
                            renderHomeGrid();
                        }).setNegativeButton(android.R.string.cancel, null).show();
                    return true;
                }
                return false;
            });
            homeCustomWeb.setBackgroundColor(Color.TRANSPARENT);
            homeCustomWeb.getSettings().setJavaScriptEnabled(true);
            homeCustomWeb.getSettings().setDomStorageEnabled(true);
            homeCustomWeb.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return openCustomHomeUrl(request == null ? null : request.getUrl().toString());
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return openCustomHomeUrl(url);
                }
            });
        }
        if (homeCustomWeb.getParent() == null) {
            webContainer.addView(homeCustomWeb, new FrameLayout.LayoutParams(-1, -1));
        }
        String html = customHomeHtml(customCss);
        if (!html.equals(homeCustomHtml)) {
            homeCustomHtml = html;
            homeCustomWeb.loadDataWithBaseURL("https://local.via/", html, "text/html", "UTF-8", null);
        }
        homeCustomWeb.setVisibility(atHome ? View.VISIBLE : View.GONE);
        if (atHome) homeScroll.setVisibility(View.GONE);
    }

    private boolean openCustomHomeUrl(String url) {
        if (TextUtils.isEmpty(url) || "https://local.via/".equals(url)) return true;
        if (url.startsWith("via://search")) {
            String query = Uri.parse(url).getQueryParameter("q");
            if (query != null) {
                urlInput.setText(query);
                loadUrlFromInput();
            }
            return true;
        }
        loadUrlInCurrent(url);
        return true;
    }

    private String customHomeHtml(String customCss) {
        return new com.example.cleanrecovery.ui.browser.HomePageRenderer(this).render(customCss);
    }

    private Bitmap textLogoBitmap(String text, int size, boolean bold, boolean italic) {
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, size), Math.max(1, size), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xff3b82f6);
        int style = bold && italic
                ? Typeface.BOLD_ITALIC
                : bold ? Typeface.BOLD : italic ? Typeface.ITALIC : Typeface.NORMAL;
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, style));
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        String display = TextUtils.isEmpty(text) ? "枢" : text.replaceAll("<[^>]+>", "").trim();
        if (display.length() > 8) display = display.substring(0, 8);
        paint.setTextSize(size * (display.length() <= 3 ? 0.36f : 0.24f));
        android.graphics.Paint.FontMetrics fm = paint.getFontMetrics();
        canvas.drawText(display, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint);
        return bitmap;
    }

    /** 渲染主页九宫格快捷链接。 */
    private void renderHomeGrid() {
        applyHomeCustomization();
        homeGrid.removeAllViews();
        boolean bookmarkHome = prefs.homeMode() == 2;
        List<BrowserDatabaseHelper.Entry> links = bookmarkHome
                ? dbHelper.listBookmarks() : dbHelper.listQuickLinks();
        homeGrid.setVisibility(links.isEmpty() ? View.GONE : View.VISIBLE);
        for (BrowserDatabaseHelper.Entry e : links) {
            homeGrid.addView(buildQuickLinkItem(e, bookmarkHome));
        }
        layoutHomeBookmarks();
    }

    /** Fixed cells inside a centered grid; an incomplete row still starts in column one. */
    private void layoutHomeBookmarks() {
        if (homeGrid == null || prefs == null) return;
        int viewport = homeScroll.getWidth();
        if (viewport <= 0) viewport = getResources().getDisplayMetrics().widthPixels;
        int iconWidth = prefs.homeFavoriteWidth();
        int pitch = iconWidth + 18;
        int columns = Math.max(1, Math.min(540 / pitch,
            (int) Math.floor(viewport / getResources().getDisplayMetrics().density / pitch) - 1));
        for (int i = 0; i < homeGrid.getChildCount(); i++) {
            GridLayout.LayoutParams cell = (GridLayout.LayoutParams) homeGrid.getChildAt(i).getLayoutParams();
            cell.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
            cell.rowSpec = GridLayout.spec(GridLayout.UNDEFINED);
            homeGrid.getChildAt(i).setLayoutParams(cell);
        }
        homeGrid.setColumnCount(columns);
        ViewGroup.LayoutParams grid = homeGrid.getLayoutParams();
        int gridWidth = columns * (dp(iconWidth) + 2 * dp(9));
        if (grid.width != gridWidth) { grid.width = gridWidth; homeGrid.setLayoutParams(grid); }
        for (int i = 0; i < homeGrid.getChildCount(); i++) {
            GridLayout.LayoutParams cell = (GridLayout.LayoutParams) homeGrid.getChildAt(i).getLayoutParams();
            cell.columnSpec = GridLayout.spec(i % columns, GridLayout.START);
            cell.rowSpec = GridLayout.spec(i / columns, GridLayout.TOP);
            homeGrid.getChildAt(i).setLayoutParams(cell);
        }
    }

    /** 异步加载站点 favicon 到 ImageView；失败回退显示首字母。 */
    private void loadFavicon(ImageView favicon, TextView letter, String url) {
        String host = extractHost(url);
        if (host.isEmpty()) {
            return; // 保留首字母回退
        }
        executor.execute(() -> {
            Bitmap bitmap = FaviconFetcher.fetch(host);
            if (bitmap != null) {
                mainHandler.post(() -> {
                    favicon.setImageBitmap(bitmap);
                    favicon.clearColorFilter();
                    favicon.setVisibility(View.VISIBLE);
                    letter.setVisibility(View.GONE);
                });
            }
            // 失败：保持首字母可见（默认即首字母）
        });
    }

    /** 从 URL 提取 host，失败返回空串。 */
    private static String extractHost(String url) {
        if (url == null) return "";
        try {
            return Uri.parse(url).getHost();
        } catch (Exception e) {
            return "";
        }
    }

    /** 构建一个九宫格快捷链接项。 */
    private View buildQuickLinkItem(BrowserDatabaseHelper.Entry e, boolean bookmarkHome) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = dp(prefs.homeFavoriteWidth());
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.setMargins(dp(9), dp(4), dp(9), dp(4));
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.START);
        item.setLayoutParams(lp);

        final TextView letter = new TextView(this);
        letter.setGravity(Gravity.CENTER);
        String letterStr = (e.title != null && !e.title.isEmpty())
                ? e.title.substring(0, 1).toUpperCase(Locale.ROOT) : "?";
        letter.setText(letterStr);
        letter.setTextColor(Color.WHITE);
        letter.setTextSize(18);
        letter.setTypeface(letter.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout iconLp = new LinearLayout(this);
        iconLp.setOrientation(LinearLayout.VERTICAL);
        int sideW = dp(prefs.homeFavoriteWidth());
        int sideH = dp(prefs.homeFavoriteHeight());
        LinearLayout.LayoutParams iconLl = new LinearLayout.LayoutParams(sideW, sideH);
        iconLl.gravity = Gravity.CENTER_HORIZONTAL;
        iconLp.setLayoutParams(iconLl);
        iconLp.setGravity(Gravity.CENTER);
        GradientDrawable favBg = new GradientDrawable();
        favBg.setShape(GradientDrawable.RECTANGLE);
        favBg.setColor(com.example.cleanrecovery.ui.browser.HomePageRenderer.siteColor(e.url));
        favBg.setCornerRadius((Math.min(sideW, sideH) / 2f) * (prefs.homeFavoriteRadius() / 100f));
        iconLp.setBackground(favBg);
        iconLp.setClipToOutline(true);
        iconLp.setVisibility(prefs.homeFavoriteIconDisabled() ? View.GONE : View.VISIBLE);
        iconLp.addView(letter);

        // favicon：优先 google s2/favicons，异步加载到 ImageView，失败回退首字母
        final ImageView favicon = new ImageView(this);
        LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(sideW, sideH);
        favLp.gravity = Gravity.CENTER;
        favicon.setLayoutParams(favLp);
        favicon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_sm);
        favicon.setPadding(0, 0, 0, 0);
        favicon.setVisibility(View.GONE);
        iconLp.addView(favicon);
        if (prefs.homeFavoriteIconStyle() == 0) loadFavicon(favicon, letter, e.url);

        TextView title = new TextView(this);
        title.setText(e.title);
        title.setTextColor(prefs.nightMode() ? 0xffcccccc : getResources().getColor(R.color.text_secondary));
        title.setTextSize(10);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setMaxWidth(sideW);
        title.setGravity(Gravity.CENTER);
        title.setVisibility(prefs.homeFavoriteTitleDisabled() ? View.GONE : View.VISIBLE);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                sideW, dp(20));
        titleLp.gravity = Gravity.CENTER_HORIZONTAL;
        titleLp.setMargins(0, dp(2), 0, 0);
        title.setLayoutParams(titleLp);

        item.addView(iconLp);
        item.addView(title);

        item.setOnClickListener(v -> loadUrlInCurrent(e.url));
        item.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.via_home_remove)
                    .setMessage(e.title + "\n" + e.url)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        if (bookmarkHome) dbHelper.removeBookmark(e.id);
                        else dbHelper.removeQuickLink(e.id);
                        renderHomeGrid();
                        GlassToast.makeText(this, R.string.via_home_removed, GlassToast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        });
        return item;
    }

    /** 弹出添加快捷链接对话框。 */
    private void showAddQuickLinkDialog() {
        final EditText titleInput = new EditText(this);
        titleInput.setHint(R.string.via_home_add_hint);
        final EditText urlInput2 = new EditText(this);
        urlInput2.setHint(R.string.via_home_add_url);
        urlInput2.setInputType(android.text.InputType.TYPE_TEXT_VARIATION_URI);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);
        box.addView(titleInput);
        box.addView(urlInput2);
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_home_add)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String t = titleInput.getText().toString().trim();
                    String u = urlInput2.getText().toString().trim();
                    if (u.isEmpty()) {
                        GlassToast.makeText(this, R.string.via_home_add_url, GlassToast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        u = "https://" + u;
                    }
                    dbHelper.addQuickLink(t.isEmpty() ? u : t, u);
                    renderHomeGrid();
                    GlassToast.makeText(this, R.string.via_home_added, GlassToast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 更新代理状态指示点。 */
    private void updateProxyIndicator() {
        // VIA 壳无状态点；代理状态由菜单「代理」条目承担
    }

    /** URL 编码（兼容 minSdk 23：URLEncoder.encode(String, Charset) 需 API 33）。 */
    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private void loadUrlInCurrentKeepingSearchSwitcher(String url) {
        if (url == null || url.isEmpty()) return;
        hasRestorableClosedTab = false;
        hideHome();
        addressBarEditing = false;
        applyToolbarMode();
        TabManager.Tab cur = tabs.current();
        if (cur == null) newTab(url);
        else {
            urlInput.setText(url);
            navigateToPage(cur, url, false);
        }
    }

    /** 在当前标签加载 URL 并隐藏主页。 */
    private void loadUrlInCurrent(String url) {
        if (url == null || url.isEmpty()) return;
        hasRestorableClosedTab = false;
        SearchEngines.SearchResult res = SearchEngines.parseSearchResult(url);
        if (res != null) {
            showSearchEngineSwitcher = true;
            lastSearchQuery = res.query;
            activeSearchEngine = res.engine;
        } else {
            showSearchEngineSwitcher = false;
        }
        hideHome();
        addressBarEditing = false;
        TabManager.Tab cur = tabs.current();
        if (cur == null) {
            newTab(url);
            applyToolbarMode();
        } else {
            urlInput.setText(url);
            navigateToPage(cur, url, false);
            applyToolbarMode();
        }
    }

    /** 显示 VIA 风格底部 2×5 分页菜单。 */
    private void showMainMenu() {
        new BrowserBottomMenu(
                this, prefs, buildMenuEntries(), this::onMenuAction).show();
    }

    private void endSearchInput() {
        if (endingSearchInput) return;
        endingSearchInput = true;
        try {
            addressBarEditing = false;
            if (suggestPopup != null) suggestPopup.dismiss();
            if (urlInput != null) urlInput.clearFocus();
            if (homeSearch != null) homeSearch.clearFocus();
            if (homeCustomWeb != null && homeCustomWeb.getVisibility() == View.VISIBLE) {
                homeCustomWeb.evaluateJavascript("if(document.activeElement)document.activeElement.blur();", null);
                homeCustomWeb.clearFocus();
            }
            View content = findViewById(android.R.id.content);
            content.setFocusableInTouchMode(true); content.requestFocus();
            android.view.inputmethod.InputMethodManager keyboard =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(content.getWindowToken(), 0);
            applyToolbarMode();
        } finally { endingSearchInput = false; }
    }

    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN && webContainer != null
                && (addressBarEditing || (homeSearch != null && homeSearch.hasFocus()))) {
            android.graphics.Rect page = new android.graphics.Rect(), input = new android.graphics.Rect();
            webContainer.getGlobalVisibleRect(page);
            boolean insideInput = homeSearch != null && homeSearch.getGlobalVisibleRect(input)
                && input.contains((int) event.getRawX(), (int) event.getRawY());
            if (!insideInput && page.contains((int) event.getRawX(), (int) event.getRawY())) endSearchInput();
        }
        return super.dispatchTouchEvent(event);
    }

    private void focusAddressBar() {
        addressBarEditing = true;
        setSearchEngineRowVisible(false);
        applyToolbarMode();
        urlInput.setVisibility(View.VISIBLE);
        urlInput.requestFocus();
        urlInput.selectAll();
        urlInput.post(() -> {
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (!addressBarEditing || !urlInput.hasFocus()) return;
            if (keyboard != null) keyboard.showSoftInput(
                    urlInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void showNavigationHistory(boolean backwards) {
        TabManager.Tab tab = tabs.current();
        if (tab == null) return;
        android.webkit.WebBackForwardList history = tab.webView.copyBackForwardList();
        int current = history.getCurrentIndex();
        List<String> labels = new ArrayList<>();
        List<Integer> offsets = new ArrayList<>();
        if (backwards) {
            for (int index = current - 1; index >= 0; index--) {
                android.webkit.WebHistoryItem item = history.getItemAtIndex(index);
                labels.add(historyLabel(item));
                offsets.add(index - current);
            }
        } else {
            for (int index = current + 1; index < history.getSize(); index++) {
                android.webkit.WebHistoryItem item = history.getItemAtIndex(index);
                labels.add(historyLabel(item));
                offsets.add(index - current);
            }
        }
        if (labels.isEmpty()) {
            GlassToast.makeText(this, backwards
                    ? R.string.via_no_back_history : R.string.via_no_forward_history,
                    GlassToast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(backwards
                        ? R.string.via_back_history : R.string.via_forward_history)
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        tab.webView.goBackOrForward(offsets.get(which)))
                .show();
    }

    private static String historyLabel(android.webkit.WebHistoryItem item) {
        if (item == null) return "";
        return TextUtils.isEmpty(item.getTitle()) ? item.getUrl() : item.getTitle();
    }

    private List<BrowserBottomMenu.Entry> buildMenuEntries() {
        PopupMenu source = new PopupMenu(this, menuButton);
        source.inflate(R.menu.browser_menu);
        MenuItem nightItem = source.getMenu().findItem(R.id.menu_night);
        if (nightItem != null) {
            // VIA 菜单格文案固定「夜间模式」，状态用 GlassToast 反馈
            nightItem.setTitle(R.string.via_menu_night);
            nightItem.setIcon(R.drawable.via_action_night);
        }
        MenuItem uaItem = source.getMenu().findItem(R.id.menu_ua);
        if (uaItem != null) {
            // VIA 首屏文案固定为「电脑模式」，点击后 GlassToast 提示开/关
            uaItem.setTitle(R.string.via_menu_computer_mode);
        }
        MenuItem proxyItem = source.getMenu().findItem(R.id.menu_proxy);
        if (proxyItem != null) {
            proxyItem.setTitle(ProxyRouter.isProxyActive()
                    ? getString(R.string.via_proxy_on_short)
                    : getString(R.string.via_proxy_off_short));
        }
        MenuItem imgItem = source.getMenu().findItem(R.id.menu_image_mode);
        if (imgItem != null) {
            // VIA 工具箱文案固定「有图模式」，状态用 GlassToast/设置生效反馈。
            imgItem.setTitle(R.string.via_menu_image_mode);
        }
        MenuItem fsItem = source.getMenu().findItem(R.id.menu_fullscreen);
        if (fsItem != null) {
            // VIA 工具箱文案固定「全屏」。
            fsItem.setTitle(R.string.via_menu_fullscreen);
        }
        List<BrowserBottomMenu.Entry> entries = new ArrayList<>();
        boolean pageOpen = !isHomeVisible();
        for (int i = 0; i < source.getMenu().size(); i++) {
            MenuItem item = source.getMenu().getItem(i);
            String name;
            try {
                name = getResources().getResourceEntryName(item.getItemId());
            } catch (Exception ignored) {
                continue;
            }
            BrowserBottomMenu.Entry entry = new BrowserBottomMenu.Entry(
                    item.getItemId(), name, item.getTitle(), item.getIcon());
            applyMenuState(entry, pageOpen);
            entries.add(entry);
        }
        return entries;
    }

    /** VIA 状态化菜单：开启态仅蓝色高亮（真机实测无「已开启」后缀），配合置灰。 */
    private void applyMenuState(BrowserBottomMenu.Entry entry, boolean pageOpen) {
        switch (entry.resourceName) {
            case "menu_adblock":
                if (prefs.adBlockEnabled()) {
                    entry.highlighted = true;
                }
                break;
            case "menu_night":
                if (prefs.nightMode()) {
                    entry.highlighted = true;
                }
                break;
            case "menu_ua":
                // VIA：电脑模式开启时整格变蓝（图标+文字），文案不变
                if (effectiveDesktopMode()) {
                    entry.highlighted = true;
                }
                break;
            case "menu_image_mode":
                // VIA：无图状态下「有图模式」高亮，提示可恢复图片
                if (!effectiveImagesEnabled()) {
                    entry.highlighted = true;
                }
                break;
            case "menu_incognito": {
                if (prefs.incognitoMode()) {
                    entry.highlighted = true;
                }
                break;
            }
            case "menu_font_size":
                if (prefs.textZoom() != 100) {
                    entry.highlighted = true;
                }
                break;
            case "menu_reader": {
                TabState state = tabs.current() == null ? null : getState(tabs.current());
                entry.disabled = !pageOpen;
                entry.title = getString(state != null && state.readerStatus == 3
                        ? R.string.via_reader_hide : R.string.via_reader_show);
                break;
            }
            case "menu_read_aloud":
            case "menu_mark_ad":
            case "menu_save":
            case "menu_translate":
                // 仅网页态可用（真机实测：主页态保存/翻译/朗读/标记置灰）
                if (!pageOpen) entry.disabled = true;
                break;
            default:
                break;
        }
    }

    private boolean effectiveDesktopMode() {
        String host = BrowserPrefs.siteKey(currentUrl());
        int override = prefs.siteSettingsEnabled(host) ? prefs.siteDesktopMode(host) : -1;
        return override < 0 ? prefs.desktopMode() : override == 1;
    }
    private boolean effectiveImagesEnabled() {
        String host = BrowserPrefs.siteKey(currentUrl());
        int override = prefs.siteSettingsEnabled(host) ? prefs.siteImagesMode(host) : -1;
        return override < 0 ? prefs.imagesEnabled() : override == 1;
    }

    private boolean onMenuAction(int id) {
        if (id == R.id.menu_new_tab) {
            newTab("");
            showHome();
            return true;
        } else if (id == R.id.menu_close_tab) {
            closeCurrentTab();
            return true;
        } else if (id == R.id.menu_reload) {
            TabManager.Tab cur = tabs.current();
            if (cur != null) cur.webView.reload();
            return true;
        } else if (id == R.id.menu_bookmarks) {
            Intent it = new Intent(this, BookmarksActivity.class);
            TabManager.Tab cur = tabs.current();
            if (cur != null && cur.url != null) it.putExtra("current_url", cur.url);
            startActivityForResult(it, REQ_BOOKMARKS);
            return true;
        } else if (id == R.id.menu_history) {
            Intent it = new Intent(this, HistoryActivity.class);
            TabManager.Tab cur = tabs.current();
            if (cur != null && cur.url != null) it.putExtra("current_url", cur.url);
            startActivityForResult(it, REQ_HISTORY);
            return true;
        } else if (id == R.id.menu_share) {
            shareCurrentUrl();
            return true;
        } else if (id == R.id.menu_sniff) {
            openResourcePage();
            return true;
        } else if (id == R.id.menu_download) {
            openDownloadActivity();
            return true;
        } else if (id == R.id.menu_translate) {
            translatePageInPlace();
            return true;
        } else if (id == R.id.menu_find) {
            showFindInPageDialog();
            return true;
        } else if (id == R.id.menu_fullscreen) {
            toggleFullscreen();
            return true;
        } else if (id == R.id.menu_screenshot) {
            captureScreenshot();
            return true;
        } else if (id == R.id.menu_view_source) {
            showPageSource();
            return true;
        } else if (id == R.id.menu_open_with) {
            openWith();
            return true;
        } else if (id == R.id.menu_rotation) {
            toggleRotation();
            return true;
        } else if (id == R.id.menu_image_mode) {
            boolean now = !effectiveImagesEnabled();
            String host = BrowserPrefs.siteKey(currentUrl());
            if (prefs.siteSettingsEnabled(host) && prefs.siteImagesMode(host) >= 0) prefs.setSiteImagesMode(host, now ? 1 : 0);
            else prefs.setImagesEnabled(now);
            for (TabManager.Tab t : tabs.all()) applySettings(t.webView);
            GlassToast.makeText(this, now ? R.string.via_images_on : R.string.via_images_off,
                    GlassToast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.menu_add_bookmark) {
            addCurrentBookmark();
            return true;
        } else if (id == R.id.menu_add_to_home) {
            addDesktopShortcut();
            return true;
        } else if (id == R.id.menu_night) {
            boolean now = !prefs.nightMode();
            prefs.setNightMode(now);
            renderHomeGrid();
            for (TabManager.Tab t : tabs.all()) applyDarkMode(t.webView, now && prefs.forceDarkPages());
            applyToolbarMode();
            GlassToast.makeText(this, now ? R.string.via_night_on : R.string.via_night_off,
                    GlassToast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.menu_ua) {
            boolean enableDesktop = !effectiveDesktopMode();
            String host = BrowserPrefs.siteKey(currentUrl());
            if (prefs.siteSettingsEnabled(host) && prefs.siteDesktopMode(host) >= 0) {
                prefs.setSiteDesktopMode(host, enableDesktop ? 1 : 0);
            } else {
                prefs.setUaMode(enableDesktop ? 0 : 1);
            }
            for (TabManager.Tab t : tabs.all()) applySettings(t.webView);
            TabManager.Tab cur = tabs.current();
            if (cur != null && !isHomeVisible()) {
                String url = currentUrl();
                if (!TextUtils.isEmpty(url)) cur.webView.loadUrl(url);
            }
            GlassToast.makeText(this,
                    enableDesktop ? R.string.via_computer_mode_on : R.string.via_computer_mode_off,
                    GlassToast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.menu_proxy) {
            startActivityForResult(new Intent(this, ProxyActivity.class), REQ_PROXY);
            return true;
        } else if (id == R.id.menu_reader) {
            enterReaderMode();
            return true;
        } else if (id == R.id.menu_read_aloud) {
            readPageAloud();
            return true;
        } else if (id == R.id.menu_print) {
            printCurrentPage();
            return true;
        } else if (id == R.id.menu_font_size) {
            showFontSizeDialog();
            return true;
        } else if (id == R.id.menu_scripts) {
            showScriptDialog();
            return true;
        } else if (id == R.id.menu_mark_ad) {
            markCurrentHostAsAd();
            return true;
        } else if (id == R.id.menu_douyin_mode) {
            openDouyinMode();
            return true;
        } else if (id == R.id.menu_network_log) {
            showNetworkLog();
            return true;
        } else if (id == R.id.menu_offline) {
            openOfflinePages();
            return true;
        } else if (id == R.id.menu_save) {
            saveOfflinePage();
            return true;
        } else if (id == R.id.menu_add_favorite) {
            addCurrentQuickLink();
            return true;
        } else if (id == R.id.menu_incognito) {
            toggleIncognito();
            return true;
        } else if (id == R.id.menu_game_mode) {
            toggleGameMode();
            return true;
        } else if (id == R.id.menu_site_conf) {
            showSiteConfiguration();
            return true;
        } else if (id == R.id.menu_tools) {
            showSiteTools();
            return true;
        } else if (id == R.id.menu_useragent) {
            showUserAgentDialog();
            return true;
        } else if (id == R.id.menu_ai) {
            openPageAi();
            return true;
        } else if (id == R.id.menu_adblock) {
            toggleAdBlock();
            return true;
        } else if (id == R.id.menu_scan) {
            launchQrScanner();
            return true;
        } else if (id == R.id.menu_customize_menu) {
            BrowserBottomMenu.showCustomizer(
                    this, prefs, buildMenuEntries());
            return true;
        } else if (id == R.id.menu_report) {
            reportCurrentSite();
            return true;
        } else if (id == R.id.menu_clear_data) {
            clearBrowserData();
            return true;
        } else if (id == R.id.menu_settings) {
            startActivityForResult(new Intent(this, BrowserSettingsActivity.class), REQ_SETTINGS);
            return true;
        } else if (id == R.id.menu_exit) {
            finish();
            return true;
        }
        return false;
    }

    private void showSiteInformationPanel() {
        toggleSiteCard();
    }

    private TextView siteInfoSmallButton(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(22);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary));
        v.setBackgroundResource(R.drawable.bg_via_toolbar_button);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(40)));
        return v;
    }

    private void addSiteInfoAction(LinearLayout box, String text, View.OnClickListener listener) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(15);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.via_accent));
        row.setOnClickListener(listener);
        box.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private android.content.Context sitePanelContext() {
        return prefs.nightMode() ? new android.view.ContextThemeWrapper(this, R.style.ViaDarkPanel) : this;
    }

    private AlertDialog showCertificateDialog(TabManager.Tab tab) {
        if (tab == null || tab.webView == null) return null;
        return showCertificateDetails(tab.webView.getCertificate());
    }

    private AlertDialog showCertificateDetails(SslCertificate cert) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(6));
        if (cert == null) {
            addCertValue(box, null, getString(R.string.via_certificate_empty));
        } else {
            SslCertificate.DName to = cert.getIssuedTo();
            SslCertificate.DName by = cert.getIssuedBy();
            addCertSection(box, "颁发对象");
            addCertValue(box, "公用名 (CN)", to == null ? "-" : emptyDash(to.getCName()));
            addCertValue(box, "组织 (O)", to == null ? "-" : emptyDash(to.getOName()));
            addCertValue(box, "组织单位 (OU)", to == null ? "-" : emptyDash(to.getUName()));
            addCertSection(box, "颁发者");
            addCertValue(box, "公用名 (CN)", by == null ? "-" : emptyDash(by.getCName()));
            addCertValue(box, "组织 (O)", by == null ? "-" : emptyDash(by.getOName()));
            addCertSection(box, "有效期");
            addCertValue(box, "颁发日期", formatCertDate(cert.getValidNotBeforeDate()));
            addCertValue(box, "截止日期", formatCertDate(cert.getValidNotAfterDate()));
            if (Build.VERSION.SDK_INT >= 29 && cert.getX509Certificate() != null) {
                try {
                    java.security.cert.X509Certificate x509 = cert.getX509Certificate();
                    addCertSection(box, "SHA-256 指纹");
                    addCertValue(box, "证书", certificateFingerprint(x509.getEncoded()));
                    addCertValue(box, "公钥", certificateFingerprint(x509.getPublicKey().getEncoded()));
                } catch (java.security.cert.CertificateEncodingException error) {
                    Log.w(TAG, "Cannot encode certificate fingerprint", error);
                }
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        return new AlertDialog.Builder(sitePanelContext())
                .setTitle("证书信息")
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private String certificateFingerprint(byte[] encoded) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(encoded);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02X", value & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void addCertSection(LinearLayout box, String title) {
        TextView v = new TextView(sitePanelContext());
        v.setText(title);
        v.setTextSize(15);
        v.setTypeface(v.getTypeface(), android.graphics.Typeface.BOLD);
        v.setTextColor(prefs.nightMode() ? 0xffcccccc : getResources().getColor(R.color.text_primary));
        v.setPadding(0, dp(12), 0, dp(4));
        box.addView(v);
    }

    private void addCertValue(LinearLayout box, String label, String value) {
        if (!TextUtils.isEmpty(label)) {
            TextView l = new TextView(sitePanelContext());
            l.setText(label);
            l.setTextSize(14);
            l.setTypeface(l.getTypeface(), android.graphics.Typeface.BOLD);
            l.setTextColor(prefs.nightMode() ? 0xffcccccc : getResources().getColor(R.color.text_primary));
            box.addView(l);
        }
        TextView v = new TextView(sitePanelContext());
        v.setText(value);
        v.setTextSize(14);
        v.setTextColor(prefs.nightMode() ? 0xff999999 : getResources().getColor(R.color.text_secondary));
        v.setPadding(0, 0, 0, dp(6));
        box.addView(v);
    }

    private String emptyDash(String v) { return TextUtils.isEmpty(v) ? "-" : v; }

    private String formatCertDate(Date date) {
        if (date == null) return "-";
        return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.LONG, java.text.DateFormat.SHORT).format(date);
    }

    private AlertDialog showCookiesDialog(String url) {
        String cookies = null;
        if (!TextUtils.isEmpty(url)) {
            try { cookies = android.webkit.CookieManager.getInstance().getCookie(url); }
            catch (Exception ignored) { }
        }
        final String rawCookies = cookies;
        if (TextUtils.isEmpty(cookies)) cookies = getString(R.string.via_cookie_empty);
        TextView view = new TextView(sitePanelContext());
        view.setText(cookies);
        view.setTextIsSelectable(true);
        view.setPadding(dp(20), dp(12), dp(20), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        AlertDialog.Builder builder = new AlertDialog.Builder(sitePanelContext())
                .setTitle(hostFromUrl(url) + " 的 Cookies").setView(scroll);
        if (TextUtils.isEmpty(rawCookies)) builder.setPositiveButton(android.R.string.ok, null);
        else builder.setPositiveButton(android.R.string.copy, (d, w) -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Cookies", rawCookies));
        }).setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton("删除", (d, w) -> clearCookiesForUrl(url));
        return builder.show();
    }

    /** VIA「添加到桌面」：创建当前页快捷方式。 */
    private void addDesktopShortcut() {
        TabManager.Tab cur = tabs.current();
        String url = cur != null ? cur.url : null;
        if (url == null || url.isEmpty()) {
            GlassToast.makeText(this, R.string.browser_empty_hint, GlassToast.LENGTH_SHORT).show();
            return;
        }
        String title = cur.title == null || cur.title.isEmpty() ? url : cur.title;
        Intent launch = new Intent(this, BrowserActivity.class);
        launch.setAction(Intent.ACTION_VIEW);
        launch.putExtra("url", url);
        launch.setData(Uri.parse(url));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.content.pm.ShortcutManager sm =
                    getSystemService(android.content.pm.ShortcutManager.class);
            if (sm != null && sm.isRequestPinShortcutSupported()) {
                android.content.pm.ShortcutInfo shortcut =
                        new android.content.pm.ShortcutInfo.Builder(this, "via_" + url.hashCode())
                                .setShortLabel(title.length() > 20 ? title.substring(0, 20) : title)
                                .setLongLabel(title)
                                .setIcon(android.graphics.drawable.Icon.createWithResource(
                                        this, R.drawable.ic_via_logo))
                                .setIntent(launch)
                                .build();
                sm.requestPinShortcut(shortcut, null);
            }
        } else {
            Intent add = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            add.putExtra(Intent.EXTRA_SHORTCUT_INTENT, launch);
            add.putExtra(Intent.EXTRA_SHORTCUT_NAME, title);
            add.putExtra(Intent.EXTRA_SHORTCUT_ICON_RESOURCE,
                    Intent.ShortcutIconResource.fromContext(this, R.drawable.ic_via_logo));
            sendBroadcast(add);
        }
        GlassToast.makeText(this, R.string.via_desktop_shortcut_hint, GlassToast.LENGTH_SHORT).show();
    }

    private void reportCurrentSite() {
        TabManager.Tab cur = tabs.current();
        String url = cur != null && cur.url != null ? cur.url : "";
        Intent mail = new Intent(Intent.ACTION_SENDTO);
        mail.setData(Uri.parse("mailto:"));
        mail.putExtra(Intent.EXTRA_SUBJECT, "Report website");
        mail.putExtra(Intent.EXTRA_TEXT, url);
        try {
            startActivity(Intent.createChooser(mail, getString(R.string.via_menu_report)));
        } catch (Exception e) {
            GlassToast.makeText(this, url.isEmpty()
                    ? getString(R.string.browser_empty_hint) : url, GlassToast.LENGTH_SHORT).show();
        }
    }

    private void clearBrowserData() {
        clearData(new HashSet<>(java.util.Arrays.asList("cache", "form", "history", "web_storage", "cookies")),
                () -> GlassToast.makeText(this, R.string.via_data_cleared, GlassToast.LENGTH_SHORT).show());
    }

    private void clearData(Set<String> flags, Runnable complete) {
        List<WebView> views = new ArrayList<>();
        for (TabManager.Tab tab : tabs.all()) {
            if (flags.contains("history")) {
                for (TabManager.Page page : tab.discardOtherPages()) destroyPage(page);
            }
            for (TabManager.Page page : tab.pages()) if (page.webView != null) views.add(page.webView);
        }
        try {
            com.example.cleanrecovery.ui.browser.BrowserDataCleaner.clear(this, flags, views, complete);
        } catch (java.io.IOException e) {
            Log.e(TAG, "Browser data cleanup incomplete", e);
            GlassToast.makeText(this, "清除未完成：" + e.getMessage(), GlassToast.LENGTH_LONG).show();
        }
    }

    private Dialog showPageQrDialog() {
        String url = currentUrl();
        if (TextUtils.isEmpty(url)) return null;
        try {
            Bitmap bitmap = com.example.cleanrecovery.ui.browser.BrowserPageQr.encode(url, dp(280));
            Dialog dialog = new Dialog(this);
            dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16), dp(16), dp(16), dp(8));
            content.setBackgroundColor(Color.WHITE);
            ImageView image = new ImageView(this);
            image.setImageBitmap(bitmap);
            image.setContentDescription("网页二维码");
            content.addView(image, new LinearLayout.LayoutParams(dp(280), dp(280)));
            TextView save = new TextView(this);
            save.setText("保存图片");
            save.setGravity(Gravity.CENTER);
            save.setTextSize(16);
            save.setTextColor(com.example.cleanrecovery.ui.browser.ViaUi.ACCENT);
            content.addView(save, new LinearLayout.LayoutParams(-1, dp(48)));
            save.setOnClickListener(v -> {
                try {
                    com.example.cleanrecovery.ui.browser.BrowserPageQr.save(this, bitmap);
                    GlassToast.makeText(this, "图片已保存", GlassToast.LENGTH_SHORT).show();
                } catch (Exception error) {
                    Log.e(TAG, "Cannot save page QR image", error);
                    GlassToast.makeText(this, "图片保存失败", GlassToast.LENGTH_LONG).show();
                }
            });
            dialog.setContentView(content);
            dialog.show();
            return dialog;
        } catch (com.google.zxing.WriterException error) {
            GlassToast.makeText(this, "二维码生成失败", GlassToast.LENGTH_LONG).show();
            return null;
        }
    }

    private void launchQrScanner() {
        endSearchInput();
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(java.util.Collections.singleton(
                        com.google.zxing.BarcodeFormat.QR_CODE.name()))
                .setPrompt(getString(R.string.via_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(BrowserQrScannerActivity.class)
                .initiateScan();
    }

    private void enterReaderMode() {
        TabManager.Tab tab = tabs.current();
        if (tab == null) return;
        TabState state = getState(tab);
        runReader(tab, state != null && state.readerStatus == 3 ? "hide" : "show");
    }

    private void onReaderIconClick() {
        if (siteCard.getVisibility() == View.VISIBLE) {
            hideSiteCard();
            return;
        }
        TabManager.Tab tab = tabs.current();
        TabState state = tab == null ? null : getState(tab);
        if (state != null && state.readerStatus == 1
                && android.os.SystemClock.uptimeMillis() < state.readerHintUntil && !prefs.readerConfirm()) {
            runReader(tab, "show");
        } else {
            toggleSiteCard();
        }
    }

    private BrowserReader reader() {
        if (reader == null) reader = new BrowserReader(this);
        return reader;
    }

    private void runReader(TabManager.Tab tab, String action) {
        TabState state = getState(tab);
        if (state == null) return;
        int generation = state.documentGeneration;
        reader().run(tab.webView, action, prefs, value -> {
            if (generation != state.documentGeneration || tabs.indexOf(tab) < 0 || tab.tag != state) return;
            if ("true".equals(value)) {
                state.readerStatus = "hide".equals(action) ? 1 : 3;
                state.readerHintUntil = 0;
                if (tabs.current() == tab) updateReaderIcon();
            } else if (tabs.current() == tab) {
                GlassToast.makeText(this, R.string.via_reader_failed, GlassToast.LENGTH_SHORT).show();
            }
        });
    }

    private void detectReader(TabManager.Tab tab) {
        TabState state = getState(tab);
        if (state == null) return;
        int generation = state.documentGeneration;
        reader().run(tab.webView, "detect", prefs, value -> {
            if (generation != state.documentGeneration || tabs.indexOf(tab) < 0 || tab.tag != state) return;
            state.readerStatus = "3".equals(value) ? 3 : "1".equals(value) ? 1 : 0;
            state.readerHintUntil = state.readerStatus == 1 ? android.os.SystemClock.uptimeMillis() + 3000 : 0;
            if (state.readerStatus == 1) {
                tab.webView.postDelayed(() -> {
                    if (generation == state.documentGeneration && tabs.current() == tab && tab.tag == state) updateReaderIcon();
                }, 3000);
            }
            if ("5".equals(value)) runReader(tab, "show");
            if (tabs.current() == tab) updateReaderIcon();
        });
    }

    private void updateReaderIcon() {
        TabManager.Tab tab = tabs.current();
        TabState state = tab == null ? null : getState(tab);
        ImageView icon = findViewById(R.id.browser_site_info);
        boolean available = state != null && state.readerStatus == 1
                && android.os.SystemClock.uptimeMillis() < state.readerHintUntil;
        icon.setImageResource(available ? R.drawable.ic_via_book
                : tab != null && tab.url != null && tab.url.startsWith("https://")
                ? state != null && state.incognito ? R.drawable.via_site_private : R.drawable.via_site_secure
                : R.drawable.via_site_info);
        icon.setContentDescription(getString(available ? R.string.via_menu_reader : R.string.via_site_information));
    }

    private void renderReaderControls(TabManager.Tab tab) {
        LinearLayout controls = findViewById(R.id.browser_reader_controls);
        controls.removeAllViews();
        TabState state = getState(tab);
        boolean active = state != null && state.readerStatus == 3;
        controls.setVisibility(state != null && state.readerStatus != 0 ? View.VISIBLE : View.GONE);
        for (int id : new int[]{R.id.browser_site_card_cert, R.id.browser_site_card_cookies,
                R.id.browser_site_card_settings, R.id.browser_site_card_scripts}) {
            findViewById(id).setVisibility(active ? View.GONE : View.VISIBLE);
        }
        if (active) {
            LinearLayout swatches = new LinearLayout(this);
            int[] colors = BrowserReader.themeColors();
            for (int i = 0; i < colors.length; i++) {
                final int theme = i;
                View swatch = new View(this);
                GradientDrawable background = new GradientDrawable();
                background.setColor(colors[i]);
                background.setCornerRadius(dp(8));
                background.setStroke(dp(2), prefs.readerTheme() == i ? ViaUi.ACCENT : 0xFFDCDCDC);
                swatch.setBackground(background);
                swatch.setContentDescription("阅读主题 " + (i + 1));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(42), 1);
                params.setMargins(dp(4), dp(8), dp(4), dp(8));
                swatches.addView(swatch, params);
                swatch.setOnClickListener(v -> {
                    prefs.setReaderTheme(theme);
                    reader().run(tab.webView, "appearance", prefs, null);
                    renderReaderControls(tab);
                });
            }
            controls.addView(swatches);
            LinearLayout font = new LinearLayout(this);
            font.setGravity(Gravity.CENTER_VERTICAL);
            android.widget.SeekBar seek = new android.widget.SeekBar(this);
            seek.setMax(20);
            seek.setProgress(Math.max(0, Math.min(20, prefs.readerFont() - 10)));
            for (int delta : new int[]{-1, 1}) {
                TextView button = readerControl(delta < 0 ? "A−" : "A+");
                button.setOnClickListener(v -> seek.setProgress(seek.getProgress() + delta));
                font.addView(button);
                if (delta < 0) font.addView(seek, new LinearLayout.LayoutParams(0, dp(48), 1));
            }
            seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                    prefs.setReaderFont(progress + 10);
                    reader().run(tab.webView, "appearance", prefs, null);
                }
                @Override public void onStartTrackingTouch(android.widget.SeekBar bar) { }
                @Override public void onStopTrackingTouch(android.widget.SeekBar bar) { }
            });
            controls.addView(font);
        }
        TextView toggle = readerControl(getString(active ? R.string.via_reader_hide : R.string.via_reader_show));
        toggle.setOnClickListener(v -> {
            hideSiteCard();
            runReader(tab, active ? "hide" : "show");
        });
        controls.addView(toggle);
        if (active) {
            TextView aloud = readerControl(getString(R.string.via_menu_read_aloud));
            aloud.setOnClickListener(v -> { hideSiteCard(); readPageAloud(); });
            controls.addView(aloud);
        }
    }

    private TextView readerControl(String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(15);
        view.setTextColor(ViaUi.TEXT);
        view.setPadding(dp(8), dp(12), dp(8), dp(12));
        view.setBackgroundResource(R.drawable.bg_via_menu_cell);
        return view;
    }

    private void readPageAloud() {
        TabManager.Tab tab = tabs.current();
        if (tab == null || tab.webView == null) return;
        tab.webView.evaluateJavascript(
                "(function(){var body=document.querySelector('.via-reader-body')||document.body;return (body&&body.innerText)||'';})()",
                value -> {
                    String text = decodeJsString(value).trim();
                    if (text.isEmpty()) {
                        GlassToast.makeText(this, R.string.via_read_aloud_empty,
                                GlassToast.LENGTH_SHORT).show();
                        return;
                    }
                    if (text.length() > 3900) text = text.substring(0, 3900);
                    final String spoken = text;
                    if (textToSpeech != null) {
                        textToSpeech.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "via-page");
                        showReadAloudBar();
                        return;
                    }
                    textToSpeech = new TextToSpeech(this, status -> {
                        if (status == TextToSpeech.SUCCESS) {
                            textToSpeech.setLanguage(Locale.getDefault());
                            textToSpeech.speak(spoken, TextToSpeech.QUEUE_FLUSH,
                                    null, "via-page");
                            showReadAloudBar();
                        } else {
                            GlassToast.makeText(this, R.string.via_read_aloud_failed,
                                    GlassToast.LENGTH_SHORT).show();
                        }
                    });
                });
    }

    private void printCurrentPage() {
        TabManager.Tab tab = tabs.current();
        if (tab == null || tab.webView == null) return;
        PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
        if (manager == null) return;
        String title = TextUtils.isEmpty(tab.title) ? "Web page" : tab.title;
        PrintDocumentAdapter adapter = tab.webView.createPrintDocumentAdapter(title);
        manager.print(title, adapter, null);
    }

    private Dialog showFontSizeDialog() {
        String host = currentHost();
        boolean siteOnly = !TextUtils.isEmpty(host);
        int initial = siteOnly ? prefs.siteTextZoom(host, prefs.textZoom()) : prefs.textZoom();
        Dialog dialog = ViaUi.textZoomDialog(this, initial, siteOnly, zoom -> {
            if (siteOnly) {
                prefs.setSiteSettingsEnabled(host, true);
                prefs.setSiteTextZoom(host, zoom);
                TabManager.Tab current = tabs.current();
                if (current != null) applyTextZoom(current.webView, zoom);
            } else {
                prefs.setTextZoom(zoom);
                for (TabManager.Tab tab : tabs.all()) applyTextZoom(tab.webView, zoom);
            }
        });
        dialog.show();
        return dialog;
    }

    private void showScriptDialog() {
        EditText input = new EditText(this);
        input.setHint("document.title");
        input.setMinLines(4);
        input.setGravity(Gravity.TOP);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_menu_scripts)
                .setMessage(R.string.via_script_warning)
                .setView(input)
                .setPositiveButton(R.string.via_run, (dialog, which) -> {
                    TabManager.Tab tab = tabs.current();
                    String script = input.getText().toString();
                    if (tab != null && !script.trim().isEmpty()) {
                        tab.webView.evaluateJavascript(script, result ->
                                GlassToast.makeText(this,
                                        getString(R.string.via_script_result,
                                                decodeJsString(result)),
                                        GlassToast.LENGTH_LONG).show());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private Dialog showPageScripts() {
        // Via passes no URL on its internal homepage, so this is an installed-script list there.
        String url = isHomeVisible() ? "" : currentUrl();
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "";
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(ViaUi.surfaceColor(this)); surface.setCornerRadius(dp(14));
        card.setBackground(surface);
        card.setPadding(0, dp(4), 0, dp(12));
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(card);
        renderPageScripts(dialog, card, url, null);
        dialog.show();
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(Math.min(dp(384), getResources().getDisplayMetrics().widthPixels - dp(72)), -2);
            android.view.WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.35f; window.setAttributes(attributes);
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        return dialog;
    }

    private TextView scriptPanelText(String text, int size) {
        TextView view = new TextView(sitePanelContext());
        view.setText(text); view.setTextSize(size);
        view.setTextColor(ViaUi.textColor(this, ViaUi.TEXT));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(16), dp(12), dp(16), dp(12));
        return view;
    }

    private void scriptPanelAction(LinearLayout content, String text, Runnable action) {
        TextView row = scriptPanelText(text, 16);
        row.setMinHeight(dp(48));
        row.setBackgroundResource(R.drawable.bg_via_menu_cell);
        row.setOnClickListener(v -> action.run());
        content.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void setPageScriptEnabled(String name, boolean enabled) {
        prefs.setScriptEnabled(name, enabled);
        for (TabManager.Tab tab : tabs.all()) applySettings(tab.webView);
        reloadCurrentWithSettings();
    }

    private void renderPageScripts(Dialog dialog, LinearLayout card, String url, String selected) {
        card.removeAllViews();
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        String host = hostFromUrl(url);
        TextView heading = scriptPanelText(selected != null ? selected
                : host.isEmpty() ? "脚本" : "作用于 " + host + " 的脚本", 16);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setMaxLines(2);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ImageView nav = new ImageView(this);
        nav.setImageResource(selected == null ? R.drawable.via_script_add : R.drawable.via_script_back);
        nav.setColorFilter(ViaUi.textColor(this, ViaUi.TEXT));
        nav.setPadding(dp(13), dp(13), dp(13), dp(13));
        nav.setBackgroundResource(R.drawable.bg_via_menu_cell);
        nav.setContentDescription(selected == null ? "添加脚本" : "返回脚本列表");
        if (selected != null) header.addView(nav, new LinearLayout.LayoutParams(dp(48), dp(48)));
        header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (selected == null) header.addView(nav, new LinearLayout.LayoutParams(dp(48), dp(48)));
        nav.setOnClickListener(v -> {
            if (selected != null) renderPageScripts(dialog, card, url, null);
            else {
                dialog.dismiss();
                scriptsBeforeEditing = scriptConfiguration();
                startActivityForResult(new Intent(this, BrowserSettingsActivity.class)
                        .putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS, true)
                        .putExtra(BrowserSettingsActivity.EXTRA_NEW_SCRIPT, true)
                        .putExtra(BrowserSettingsActivity.EXTRA_SCRIPT_URL, url), REQ_SCRIPT_SETTINGS);
            }
        });
        card.addView(header);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        card.addView(scroll);
        dialog.setOnKeyListener((d, key, event) -> {
            if (key == android.view.KeyEvent.KEYCODE_BACK && selected != null) {
                if (event.getAction() == android.view.KeyEvent.ACTION_UP) renderPageScripts(dialog, card, url, null);
                return true;
            }
            return false;
        });
        if (selected == null) {
            int matched = 0;
            for (String name : prefs.scriptNames()) {
                if (!TextUtils.isEmpty(url)
                        && !BrowserUserScripts.applies(prefs.scriptExecutionCode(name), prefs.scriptMatch(name), url)) continue;
                matched++;
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackgroundResource(R.drawable.bg_via_menu_cell);
                TextView label = scriptPanelText(name, 16);
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                android.widget.CheckBox enabled = new android.widget.CheckBox(sitePanelContext());
                enabled.setButtonDrawable((android.graphics.drawable.Drawable) null);
                enabled.setPadding(dp(12), dp(12), dp(16), dp(12));
                enabled.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                        prefs.isScriptEnabled(name) ? R.drawable.bg_via_toggle_on : R.drawable.bg_via_toggle, 0);
                enabled.setContentDescription(name + " 启用");
                enabled.setChecked(prefs.isScriptEnabled(name));
                row.addView(enabled, new LinearLayout.LayoutParams(dp(48), dp(48)));
                enabled.setOnCheckedChangeListener((button, checked) -> {
                    enabled.setCompoundDrawablesWithIntrinsicBounds(0, 0,
                            checked ? R.drawable.bg_via_toggle_on : R.drawable.bg_via_toggle, 0);
                    setPageScriptEnabled(name, checked);
                });
                row.setOnClickListener(v -> renderPageScripts(dialog, card, url, name));
                content.addView(row);
            }
            if (matched == 0) {
                TextView empty = scriptPanelText(TextUtils.isEmpty(url) ? "没有已安装的脚本" : "当前网页没有匹配的脚本", 14);
                empty.setTextColor(ViaUi.textColor(this, ViaUi.TEXT_SUB));
                empty.setGravity(Gravity.CENTER); empty.setMinHeight(dp(100)); content.addView(empty);
            }
        } else {
            scriptPanelAction(content, prefs.isScriptEnabled(selected) ? "已启用" : "已禁用", () -> {
                setPageScriptEnabled(selected, !prefs.isScriptEnabled(selected));
                renderPageScripts(dialog, card, url, selected);
            });
            scriptPanelAction(content, "编辑", () -> { dialog.dismiss(); openScriptSettings(selected); });
            if (prefs.isScriptEnabled(selected) && !host.isEmpty()) {
                scriptPanelAction(content, "排除 " + host, () -> {
                    String rule = Uri.parse(url).getScheme() + "://" + Uri.parse(url).getAuthority() + "/*";
                    java.util.List<String> rules = new java.util.ArrayList<>();
                    for (String old : prefs.scriptExcludes(selected).split("\\n")) if (!old.trim().isEmpty()) rules.add(old.trim());
                    if (!rules.contains(rule)) rules.add(rule);
                    prefs.setScriptExcludes(selected, android.text.TextUtils.join("\n", rules));
                    for (TabManager.Tab tab : tabs.all()) applySettings(tab.webView);
                    reloadCurrentWithSettings(); dialog.dismiss();
                    GlassToast.makeText(this, "已排除 " + host, GlassToast.LENGTH_SHORT).show();
                });
            }
            scriptPanelAction(content, "在设置中查看", () -> {
                dialog.dismiss(); scriptsBeforeEditing = scriptConfiguration();
                startActivityForResult(new Intent(this, BrowserSettingsActivity.class)
                        .putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS, true)
                        .putExtra(BrowserSettingsActivity.EXTRA_SELECT_SCRIPT, selected), REQ_SCRIPT_SETTINGS);
            });
            TabManager.Tab current = tabs.current();
            if (prefs.scriptsEnabled() && prefs.isScriptEnabled(selected) && current != null && current.webView instanceof BrowserWebView) {
                BrowserWebView page = (BrowserWebView) current.webView;
                page.evaluateJavascript(page.scriptMenus().list(selected), result -> {
                    if (!dialog.isShowing() || content.getParent() != scroll || scroll.getParent() != card
                            || !prefs.isScriptEnabled(selected)) return;
                    try {
                        org.json.JSONObject snapshot = new org.json.JSONObject(result);
                        org.json.JSONArray names = snapshot.getJSONArray("names");
                        String epoch = snapshot.getString("epoch");
                        for (int i = 0; i < names.length(); i++) {
                            String command = names.getString(i);
                            scriptPanelAction(content, command, () -> {
                                dialog.dismiss();
                                if (tabs.current() != current || !prefs.scriptsEnabled() || !prefs.isScriptEnabled(selected)) return;
                                page.evaluateJavascript(page.scriptMenus().invoke(selected, command, epoch), null);
                            });
                        }
                    } catch (org.json.JSONException error) { android.util.Log.w("ViaScriptMenus", "Invalid menu snapshot", error); }
                });
            }
        }
        scroll.post(() -> {
            int limit = Math.min(dp(600), getResources().getDisplayMetrics().heightPixels / 2);
            if (scroll.getHeight() > limit) { scroll.getLayoutParams().height = limit; scroll.requestLayout(); }
        });
    }

    private String scriptConfiguration() {
        java.util.List<String> names = prefs.scriptNames();
        java.util.Collections.sort(names);
        StringBuilder result = new StringBuilder(Boolean.toString(prefs.scriptsEnabled()));
        for (String name : names) result.append(name).append('\u0000').append(prefs.isScriptEnabled(name))
                .append('\u0000').append(prefs.scriptMatch(name)).append('\u0000').append(prefs.scriptExecutionCode(name)).append('\u0000');
        return result.toString();
    }

    private void openScriptSettings(String name) {
        scriptsBeforeEditing = scriptConfiguration();
        Intent intent = new Intent(this, BrowserSettingsActivity.class)
                .putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS, true);
        if (name != null) intent.putExtra(BrowserSettingsActivity.EXTRA_EDIT_SCRIPT, name);
        startActivityForResult(intent, REQ_SCRIPT_SETTINGS);
    }

    private boolean offerUserScript(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || path == null || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".user.js")) return false;
        AlertDialog loading = new AlertDialog.Builder(sitePanelContext()).setTitle("下载脚本")
                .setMessage("正在读取脚本…").setNegativeButton(android.R.string.cancel, null).create();
        loading.show();
        new Thread(() -> {
            try {
                String code = BrowserUserScripts.download(url);
                if (!code.contains("// ==UserScript==") || BrowserUserScripts.parse(code).name.isEmpty())
                    throw new java.io.IOException("文件不是有效的 Userscript 脚本");
                runOnUiThread(() -> {
                    if (!loading.isShowing() || isFinishing() || isDestroyed()) return;
                    loading.dismiss();
                    scriptsBeforeEditing = scriptConfiguration();
                    startActivityForResult(new Intent(this, BrowserSettingsActivity.class)
                            .putExtra(BrowserSettingsActivity.EXTRA_OPEN_SCRIPTS, true)
                            .putExtra(BrowserSettingsActivity.EXTRA_NEW_SCRIPT, true)
                            .putExtra(BrowserSettingsActivity.EXTRA_SCRIPT_SOURCE, code), REQ_SCRIPT_SETTINGS);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!loading.isShowing() || isFinishing() || isDestroyed()) return;
                    loading.dismiss();
                    new AlertDialog.Builder(sitePanelContext()).setTitle("下载脚本失败")
                            .setMessage(error.getMessage()).setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton("重试", (dialog, which) -> offerUserScript(url)).show();
                });
            }
        }, "userscript-download").start();
        return true;
    }

    /** 抖音模式：以当前页 URL 进入垂直滑动视频流（B站 → 相关推荐 feed；其它 → 单条播放）。 */
    private void openDouyinMode() {
        TabManager.Tab cur = tabs.current();
        String url = cur == null || cur.url == null ? "" : cur.url;
        if (TextUtils.isEmpty(url) || !url.startsWith("http")) {
            GlassToast.makeText(this, R.string.feed_no_url, GlassToast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, VideoFeedActivity.class);
        it.putExtra(VideoFeedActivity.EXTRA_URL, url);
        startActivity(it);
    }

    private void markCurrentHostAsAd() {
        TabManager.Tab tab = tabs.current();
        if (tab == null) return;
        String pageUrl = !TextUtils.isEmpty(tab.url) ? tab.url : tab.webView.getUrl();
        if (TextUtils.isEmpty(pageUrl)) return;
        String host = hostFromUrl(pageUrl);
        if (TextUtils.isEmpty(host)) return;
        prefs.setAdBlockEnabled(true);
        TabState state = getState(tab);
        if (state != null) state.adMarkerArmed = true;
        injectAdMarker(tab.webView);
        GlassToast.makeText(this, R.string.via_ad_marker_ready, GlassToast.LENGTH_LONG).show();
    }

    private void injectAdMarker(WebView webView) {
        if (webView == null) return;
        webView.evaluateJavascript(adMarkerScript(), null);
    }

    static String adMarkerScript() {
        return "(function(){"
                + "if(window.__viaAdMarkerActive)return;window.__viaAdMarkerActive=true;"
                + "var selected=null,trail=[],box=document.createElement('div'),bar=document.createElement('div');"
                + "box.id='__via_ad_marker_box';box.style.cssText='position:fixed;display:none;pointer-events:none;border:3px solid #e53935;background:rgba(229,57,53,.10);z-index:2147483646;box-sizing:border-box';"
                + "bar.id='__via_ad_marker_bar';bar.style.cssText='position:fixed;left:10px;right:10px;top:10px;z-index:2147483647;background:#202124;color:white;padding:8px;border-radius:10px;display:flex;gap:6px;justify-content:center;box-shadow:0 3px 14px #0008';"
                + "['扩大','缩小','保存','取消','上移','下移'].forEach(function(x){var b=document.createElement('button');b.textContent=x;b.dataset.a=x;b.style.cssText='border:0;border-radius:7px;padding:7px 10px;background:#fff;color:#202124;font-size:13px';bar.appendChild(b)});"
                + "document.documentElement.appendChild(box);document.documentElement.appendChild(bar);"
                + "function esc(x){return window.CSS&&CSS.escape?CSS.escape(x):x.replace(/[^a-zA-Z0-9_-]/g,'\\\\$&')}"
                + "function sel(el){if(!el||el===document.documentElement||el===document.body)return 'body';if(el.id)return '#'+esc(el.id);var a=[],n=el;while(n&&n.nodeType===1&&n!==document.body){var s=n.tagName.toLowerCase(),p=n.parentElement;if(n.classList&&n.classList.length)s+='.'+Array.prototype.slice.call(n.classList,0,2).map(esc).join('.');if(p){var same=Array.prototype.filter.call(p.children,function(v){return v.tagName===n.tagName});if(same.length>1)s+=':nth-of-type('+(same.indexOf(n)+1)+')'}a.unshift(s);n=p;if(a.length===5)break}return a.join('>')}"
                + "function draw(){if(!selected)return;var r=selected.getBoundingClientRect();box.style.display='block';box.style.left=r.left+'px';box.style.top=r.top+'px';box.style.width=r.width+'px';box.style.height=r.height+'px'}"
                + "function choose(t){if(!t||bar.contains(t))return;selected=t;trail=[t];draw()}"
                + "window.__viaAdMarkerPickAt=function(x,y){choose(document.elementFromPoint(x,y))};"
                + "function clean(){document.removeEventListener('click',pick,true);window.removeEventListener('scroll',draw,true);window.removeEventListener('resize',draw);delete window.__viaAdMarkerPickAt;box.remove();bar.remove();window.__viaAdMarkerActive=false}"
                + "function pick(e){if(bar.contains(e.target))return;e.preventDefault();e.stopPropagation();choose(e.target)}"
                + "bar.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();var a=e.target.dataset.a;if(!a)return;if(a==='扩大'&&selected&&selected.parentElement&&selected.parentElement!==document.documentElement){selected=selected.parentElement;trail.push(selected);draw()}else if(a==='缩小'&&trail.length>1){trail.pop();selected=trail[trail.length-1];draw()}else if(a==='取消'){window.ViaAdMarker.cancel();clean()}else if(a==='保存'&&selected){var t=selected,u=(t.currentSrc||t.src||t.href||t.getAttribute('data-src')||t.getAttribute('data-adurl')||'');window.ViaAdMarker.report(location.href,sel(t),u);clean()}else if(a==='上移'){bar.style.top='10px';bar.style.bottom='auto'}else if(a==='下移'){bar.style.top='auto';bar.style.bottom='10px'}});"
                + "document.addEventListener('click',pick,true);window.addEventListener('scroll',draw,true);window.addEventListener('resize',draw);"
                + "})();";
    }

    private final class AdMarkerBridge {
        @JavascriptInterface
        public void cancel() {
            mainHandler.post(() -> {
                TabManager.Tab cur = tabs.current();
                TabState state = cur == null ? null : getState(cur);
                if (state != null) state.adMarkerArmed = false;
            });
        }

        @JavascriptInterface
        public void report(String pageUrl, String selector) {
            mainHandler.post(() -> onAdMarked(pageUrl, selector, null));
        }

        @JavascriptInterface
        public void report(String pageUrl, String selector, String targetUrl) {
            mainHandler.post(() -> onAdMarked(pageUrl, selector, targetUrl));
        }
    }

    private void injectPasswordManager(WebView webView, String pageUrl, boolean incognito) {
        if (webView == null || incognito || !prefs.scriptsEnabled() || TextUtils.isEmpty(pageUrl)) return;
        String origin = originFromUrl(pageUrl);
        if (origin.isEmpty()) return;
        BrowserPasswordStore.Entry saved = new BrowserPasswordStore(this).find(origin);
        String username = saved == null ? "" : saved.username;
        String password = saved == null ? "" : saved.password;
        String script = "(function(){try{if(location.origin!==" + jsString(origin)
                + "||window.__viaPasswordManager)return;window.__viaPasswordManager=1;"
                + "var pick=function(form){var p=form.querySelector('input[type=password]');if(!p)return null;"
                + "var all=Array.prototype.slice.call(form.querySelectorAll('input'));var at=all.indexOf(p),u=null;"
                + "for(var i=at-1;i>=0;i--){var t=(all[i].type||'text').toLowerCase();if(t==='text'||t==='email'||t==='tel'){u=all[i];break;}}return {u:u,p:p};};"
                + "Array.prototype.forEach.call(document.forms,function(f){var x=pick(f);if(!x)return;"
                + "if(x.u&&!x.u.value&&" + jsString(username) + ")x.u.value=" + jsString(username) + ";"
                + "if(!x.p.value&&" + jsString(password) + ")x.p.value=" + jsString(password) + ";"
                + "f.addEventListener('submit',function(){var y=pick(f);if(y&&y.p.value)ViaPasswords.submit(location.origin,y.u?y.u.value:'',y.p.value);},true);});"
                + "}catch(e){}})();";
        webView.evaluateJavascript(script, null);
    }

    private void injectUserScripts(WebView webView, String pageUrl, String phase) {
        if (webView == null || !prefs.scriptsEnabled() || TextUtils.isEmpty(pageUrl)) return;
        if (webView instanceof com.example.cleanrecovery.ui.browser.BrowserWebView
                && androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        for (String name : prefs.scriptNames()) {
            String code = prefs.scriptExecutionCode(name);
            if (!prefs.isScriptEnabled(name) || !BrowserUserScripts.applies(code, prefs.scriptMatch(name), pageUrl)) continue;
            if (TextUtils.isEmpty(code)) continue;
            if (!phase.equalsIgnoreCase(BrowserUserScripts.runAt(code))) continue;
            webView.evaluateJavascript(webView instanceof BrowserWebView
                    ? ((BrowserWebView) webView).wrapUserScript(name, code, prefs.scriptMatch(name))
                    : BrowserUserScripts.wrap(code, prefs.scriptMatch(name)), null);
        }
    }

    private final class PasswordBridge {
        private final WebView source;
        PasswordBridge(WebView source) { this.source = source; }

        @JavascriptInterface
        public void submit(String origin, String username, String password) {
            mainHandler.post(() -> {
                String actual = originFromUrl(source.getUrl());
                if (actual.isEmpty() || !actual.equals(origin) || TextUtils.isEmpty(password)) return;
                BrowserPasswordStore store = new BrowserPasswordStore(BrowserActivity.this);
                String host = hostFromUrl(actual);
                if (!prefs.passwordSaveHint() || store.ignoredHosts().contains(host)) return;
                BrowserPasswordStore.Entry old = store.find(actual);
                if (old != null && old.username.equals(username) && old.password.equals(password)) return;
                new AlertDialog.Builder(BrowserActivity.this)
                        .setTitle("保存密码？")
                        .setMessage(TextUtils.isEmpty(username) ? actual : username + "\n" + actual)
                        .setNeutralButton("永不", (dialog, which) -> store.ignore(host))
                        .setNegativeButton("取消", null)
                        .setPositiveButton("保存", (dialog, which) -> store.save(actual, username, password))
                        .show();
            });
        }
    }

    private static String originFromUrl(String url) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (TextUtils.isEmpty(scheme) || TextUtils.isEmpty(host)) return "";
            int port = uri.getPort();
            return scheme + "://" + host + (port > 0 ? ":" + port : "");
        } catch (Exception ignored) {
            return "";
        }
    }

    private void onAdMarked(String pageUrl, String selector, String targetUrl) {
        String host = hostFromUrl(pageUrl);
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(selector)) return;
        prefs.addCosmeticRule(host, selector);
        String targetHost = hostFromUrl(targetUrl);
        if (!TextUtils.isEmpty(targetHost) && !targetHost.equals(host)) {
            prefs.addBlockedHost(targetHost);
        } else if (!TextUtils.isEmpty(targetUrl)) {
            prefs.addBlockedUrlRule(targetUrl);
        }
        prefs.setAdBlockEnabled(true);
        AdBlockRuleLoader.reloadAsync(this); // 自定义规则变更 → 重建共享索引
        TabManager.Tab cur = tabs.current();
        if (cur != null) {
            TabState st = getState(cur);
            if (st != null) st.adMarkerArmed = false;
            applyAdBlockCss(cur.webView, cur.url);
        }
        GlassToast.makeText(this, R.string.via_ad_marker_saved, GlassToast.LENGTH_SHORT).show();
    }

    private void applyAdBlockCss(WebView webView, String pageUrl) {
        if (webView == null || !prefs.adBlockEnabled()) return;
        String host = hostFromUrl(pageUrl);
        String css = prefs.cosmeticCssForHost(host);
        TabManager.Tab cur = tabs.current();
        TabState state = cur != null ? getState(cur) : null;
        if (state != null && state.adBlocker != null) {
            String engineCss = state.adBlocker.cosmeticCssFor(pageUrl);
            if (!TextUtils.isEmpty(engineCss)) css = engineCss + css;
        }
        if (TextUtils.isEmpty(css)) return;
        String js = "(function(){try{var id='__via_cosmetic_blocker';var st=document.getElementById(id);"
                + "if(!st){st=document.createElement('style');st.id=id;document.documentElement.appendChild(st);}"
                + "st.textContent=" + jsString(css) + ";}catch(e){}})();";
        webView.evaluateJavascript(js, null);
    }

    private static String jsString(String value) {
        if (value == null) return "''";
        StringBuilder out = new StringBuilder("'");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\') out.append("\\\\");
            else if (c == '\'') out.append("\\'");
            else if (c == '\n') out.append("\\n");
            else if (c == '\r') out.append("\\r");
            else out.append(c);
        }
        return out.append("'").toString();
    }

    private void showNetworkLog() {
        TabState state = getState(tabs.current());
        if (state == null) return;
        StringBuilder text = new StringBuilder();
        synchronized (state.networkLog) {
            for (String url : state.networkLog) text.append(url).append('\n');
        }
        if (text.length() == 0) text.append(getString(R.string.via_network_log_empty));
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11);
        view.setTextIsSelectable(true);
        view.setPadding(32, 24, 32, 24);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(view);
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_menu_network_log)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void saveOfflinePage() {
        TabManager.Tab tab = tabs.current();
        if (tab == null || tab.webView == null) return;
        String sourceUrl = tab.url == null ? "" : tab.url;
        String title = tab.title == null || tab.title.isEmpty() ? sourceUrl : tab.title;
        File root = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "OfflinePages");
        if (!root.exists() && !root.mkdirs()) {
            GlassToast.makeText(this, R.string.via_save_failed, GlassToast.LENGTH_SHORT).show();
            return;
        }
        String safeTitle = TextUtils.isEmpty(title) ? "page" : title;
        safeTitle = safeTitle.replaceAll("[\\/:*?\"<>|]", "_");
        if (safeTitle.length() > 48) safeTitle = safeTitle.substring(0, 48);
        File target = new File(root, safeTitle + "-" + System.currentTimeMillis() + ".mht");
        tab.webView.saveWebArchive(target.getAbsolutePath(), false, value -> {
            if (value != null) {
                dbHelper.addOfflinePage(title, sourceUrl, value);
                GlassToast.makeText(this, getString(R.string.via_offline_saved_via), GlassToast.LENGTH_LONG).show();
            } else {
                GlassToast.makeText(this, R.string.via_save_failed, GlassToast.LENGTH_SHORT).show();
            }
        });
    }

    private void openOfflinePages() {
        Intent it = new Intent(this, BrowserOfflinePagesActivity.class);
        TabManager.Tab cur = tabs.current();
        if (cur != null && cur.url != null) it.putExtra("current_url", cur.url);
        startActivityForResult(it, REQ_OFFLINE);
    }

    /** Via uses a global incognito baseline with per-site overrides. */
    private void toggleIncognito() {
        if (!prefs.incognitoMode()) {
            applyIncognitoMode(true);
            return;
        }
        TabManager.Tab current = tabs.current();
        String pageUrl = current == null ? null : (TextUtils.isEmpty(current.url) ? current.webView.getUrl() : current.url);
        boolean hasPage = current != null && (!TextUtils.isEmpty(pageUrl)
                && !"about:blank".equals(pageUrl) && !pageUrl.equals(prefs.homeUrl())
                || current.canGoBack() || current.canGoForward());
        boolean hasClosedTabs = hasRestorableClosedTab && !TextUtils.isEmpty(prefs.closedTabs()) && !"[]".equals(prefs.closedTabs());
        if (tabs.size() > 1 || hasPage || hasClosedTabs) {
            new AlertDialog.Builder(sitePanelContext())
                    .setTitle(R.string.via_incognito_close_title)
                    .setMessage(R.string.via_incognito_close_message)
                    .setNegativeButton(R.string.via_incognito_keep, (dialog, which) -> applyIncognitoMode(false))
                    .setPositiveButton(R.string.via_incognito_close, (dialog, which) -> {
                        // Close while each tab still has its original effective privacy state.
                        int oldCount = tabs.size();
                        newTab("");
                        for (int index = oldCount - 1; index >= 0; index--) closeAndDestroyTab(index);
                        updateTabBadge();
                        applyIncognitoMode(false);
                    }).show();
        } else {
            applyIncognitoMode(false);
        }
    }

    private void applyIncognitoMode(boolean enabled) {
        prefs.setIncognitoMode(enabled);
        for(TabManager.Tab tab:tabs.all())applySettings(tab.webView);
        GlassToast.makeText(this,prefs.incognitoMode()?R.string.via_incognito_started:R.string.via_incognito_exit,GlassToast.LENGTH_SHORT).show();
        applyToolbarMode();
    }

    public static Set<String> cookieExpirations(String urlString, String rawCookie) {
        if (urlString == null || rawCookie == null || rawCookie.trim().isEmpty()) {
            return Collections.emptySet();
        }
        java.net.URI uri;
        try {
            uri = new java.net.URI(urlString);
        } catch (Exception e) {
            return Collections.emptySet();
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
            return Collections.emptySet();
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return Collections.emptySet();
        }
        boolean isSecure = "https".equalsIgnoreCase(scheme);

        // Candidate domains
        List<String> domains = new ArrayList<>();
        domains.add(null); // Host-only cookie (no domain attribute)

        // Check if host is IP address
        boolean isIp = host.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$");
        if (!isIp) {
            String[] parts = host.split("\\.");
            if (parts.length >= 2) {
                // e.g. a.b.example.com -> a.b.example.com, b.example.com, example.com
                for (int i = 0; i <= parts.length - 2; i++) {
                    StringBuilder d = new StringBuilder();
                    for (int j = i; j < parts.length; j++) {
                        if (d.length() > 0) d.append('.');
                        d.append(parts[j]);
                    }
                    domains.add(d.toString());
                }
            }
        }

        // Candidate paths
        Set<String> paths = new LinkedHashSet<>();
        paths.add("/");
        String rawPath = uri.getPath();
        if (rawPath != null && !rawPath.isEmpty()) {
            String[] segments = rawPath.split("/");
            StringBuilder curr = new StringBuilder();
            for (String seg : segments) {
                if (seg.isEmpty()) continue;
                curr.append("/").append(seg);
                paths.add(curr.toString());
                paths.add(curr.toString() + "/");
            }
        }

        Set<String> result = new LinkedHashSet<>();
        String[] cookiePairs = rawCookie.split(";");
        for (String pair : cookiePairs) {
            String trimmed = pair.trim();
            if (trimmed.isEmpty()) continue;
            int eq = trimmed.indexOf('=');
            String name = eq > 0 ? trimmed.substring(0, eq).trim() : trimmed;
            if (name.isEmpty()) continue;

            if (name.startsWith("__Host-")) {
                // __Host- cookies must NOT have Domain attribute, must have Path=/, must be Secure
                result.add(name + "=;Max-Age=0;Path=/;Secure");
                continue;
            }

            for (String path : paths) {
                for (String domain : domains) {
                    StringBuilder sb = new StringBuilder();
                    sb.append(name).append("=;Max-Age=0;Path=").append(path);
                    if (domain != null) {
                        sb.append(";Domain=").append(domain);
                    }
                    if (isSecure) {
                        sb.append(";Secure");
                    }
                    result.add(sb.toString());
                }
            }
        }
        return result;
    }

    private void translatePageInPlace() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.url == null || cur.url.isEmpty() || isResourcePage(cur, cur.url)) {
            GlassToast.makeText(this, R.string.browser_empty_hint, GlassToast.LENGTH_SHORT).show();
            return;
        }
        String script = translationScript(Locale.getDefault());
        cur.webView.evaluateJavascript(script, null);
    }

    public static String translationScript(Locale locale) {
        if (locale == null) locale = Locale.getDefault();
        String lang;
        String edge;
        String langTag = locale.toLanguageTag();
        if (Locale.SIMPLIFIED_CHINESE.equals(locale) || "zh-CN".equalsIgnoreCase(langTag) || "zh-Hans".equalsIgnoreCase(locale.getScript())) {
            lang = "zh-CN";
            edge = "zh-CHS";
        } else if (Locale.TRADITIONAL_CHINESE.equals(locale) || "zh-TW".equalsIgnoreCase(langTag) || "zh-HK".equalsIgnoreCase(langTag) || "zh-Hant".equalsIgnoreCase(locale.getScript())) {
            lang = "zh-TW";
            edge = "zh-CHT";
        } else {
            String l = locale.getLanguage().toLowerCase();
            lang = l.isEmpty() ? "en" : l;
            edge = lang;
        }

        return "(function(){\n"
                + "var lang='" + lang + "',edge='" + edge + "';\n"
                + "window.__via_translation_pending__ = true;\n"
                + "function fallbackToEdge(){\n"
                + "  window.__via_edge_translation__ = true;\n"
                + "  window.__via_translation_pending__ = false;\n"
                + "  function applyEdge(){\n"
                + "    if(window.translate && window.translate.service){\n"
                + "      window.translate.service.use('client.edge');\n"
                + "      window.translate.selectLanguageTag.show = false;\n"
                + "      var targetLang = edge;\n"
                + "      if(window.translate.service.edge && window.translate.service.edge.language && window.translate.service.edge.language.json){\n"
                + "        var list = window.translate.service.edge.language.json;\n"
                + "        for(var i=0; i<list.length; i++){\n"
                + "          if(list[i].serviceId === edge){ targetLang = list[i].id; break; }\n"
                + "        }\n"
                + "      }\n"
                + "      window.translate.changeLanguage(targetLang);\n"
                + "    }\n"
                + "  }\n"
                + "  if(window.translate){ applyEdge(); } else {\n"
                + "    var s = document.createElement('script');\n"
                + "    s.src = 'https://fastly.jsdelivr.net/npm/translate@3.14.0/translate.js';\n"
                + "    s.onload = applyEdge;\n"
                + "    s.onerror = function(){ window.__via_translation_pending__ = false; };\n"
                + "    document.head.appendChild(s);\n"
                + "  }\n"
                + "}\n"
                + "if(!window.__via_csp_listener__){\n"
                + "  window.__via_csp_listener__ = true;\n"
                + "  document.addEventListener('securitypolicyviolation', function(e){\n"
                + "    if(e.blockedURI && (e.blockedURI.indexOf('translate.google.com') !== -1 || e.blockedURI.indexOf('translate-pa.googleapis.com') !== -1)){\n"
                + "      fallbackToEdge();\n"
                + "    }\n"
                + "  });\n"
                + "}\n"
                + "if(window.__via_edge_translation__){\n"
                + "  fallbackToEdge();\n"
                + "  return;\n"
                + "}\n"
                + "if(window.google && window.google.translate){\n"
                + "  window.__via_translator__ = true;\n"
                + "  window.__via_translation_pending__ = false;\n"
                + "  if(window.__via_google_element__){\n"
                + "    window.__via_google_element__.showBanner();\n"
                + "  } else {\n"
                + "    window.__via_google_element__ = new window.google.translate.TranslateElement({includedLanguages: lang});\n"
                + "    window.__via_google_element__.showBanner();\n"
                + "  }\n"
                + "  return;\n"
                + "}\n"
                + "window.googleTranslateElementInit = function(){\n"
                + "  window.__via_translator__ = true;\n"
                + "  window.__via_translation_pending__ = false;\n"
                + "  if(window.google && window.google.translate){\n"
                + "    window.__via_google_element__ = new window.google.translate.TranslateElement({includedLanguages: lang});\n"
                + "    window.__via_google_element__.showBanner();\n"
                + "  }\n"
                + "};\n"
                + "var timer = setTimeout(function(){\n"
                + "  if(!window.__via_translator__) fallbackToEdge();\n"
                + "}, 2000);\n"
                + "var script = document.createElement('script');\n"
                + "script.src = 'https://translate.google.com/translate_a/element.js?cb=googleTranslateElementInit';\n"
                + "script.onerror = function(){\n"
                + "  clearTimeout(timer);\n"
                + "  fallbackToEdge();\n"
                + "};\n"
                + "document.head.appendChild(script);\n"
                + "})();";
    }

    private boolean isResourcePage(TabManager.Tab tab, String url) {
        if (url == null) return false;
        if (tab != null && BrowserResourcePage.url(tab.id).equals(url)) return true;
        return url.startsWith("https://appassets.androidplatform.net/via-res/");
    }

    private void resourcePageLoaded(TabManager.Tab tab, String url) {
        tab.url = url;
        tab.title = getString(R.string.via_menu_sniff);
        if (tabs.current() == tab) {
            hideHome();
            applyToolbarMode();
            progressbar.setVisibility(View.GONE);
            updateNavButtons(tab);
            updateSnifferButton();
        }
    }

    private void openResourcePage() {
        TabManager.Tab cur = tabs.current();
        if (cur == null) return;
        if (isResourcePage(cur, cur.url)) return;
        TabState state = getState(cur);
        if (state == null) return;
        if (ViaSnifferStateMachine.isUnsupportedSite(cur.url) || viaSniffer.mediaUrls(cur.id).isEmpty()) {
            GlassToast.makeText(this, ViaSnifferStateMachine.isUnsupportedSite(cur.url)
                    ? R.string.via_sniffer_unsupported : R.string.via_page_resource_none, GlassToast.LENGTH_SHORT).show();
            return;
        }
        String title = getString(R.string.via_menu_sniff);

        TabManager.Tab resTab = null;
        for (TabManager.Tab t : tabs.all()) {
            TabState st = getState(t);
            if (st != null && st.resourceSourceId == cur.id) {
                resTab = t;
                break;
            }
        }
        if (resTab == null) {
            // Bind the source before loading: interception and lifecycle callbacks need it immediately.
            resTab = newTab(null, false);
            TabState resState = getState(resTab);
            if (resState != null) {
                resState.resourceSourceId = cur.id;
                resState.incognito = state.incognito;
            }
            resTab.title = title;
            resTab.url = BrowserResourcePage.url(resTab.id);
            applySettings(resTab.webView);
            tabs.select(tabs.indexOf(resTab));
            showTab(resTab);
            resTab.webView.loadUrl(resTab.url);
        } else {
            resTab.title = title;
            resTab.url = BrowserResourcePage.url(resTab.id);
            tabs.select(tabs.indexOf(resTab));
            showTab(resTab);
            resTab.webView.loadUrl(resTab.url);
        }
    }

    private void closeResourcePage(TabManager.Tab cur) {
        if (cur == null) return;
        TabState state = getState(cur);
        int sourceId = state != null ? state.resourceSourceId : -1;
        int idx = tabs.indexOf(cur);
        TabManager.Tab nextTab = idx >= 0 ? closeAndDestroyTab(idx) : null;
        if (sourceId >= 0) {
            for (TabManager.Tab t : tabs.all()) {
                if (t.id == sourceId) {
                    tabs.select(tabs.indexOf(t));
                    showTab(t);
                    return;
                }
            }
        }
        if (nextTab != null) {
            showTab(nextTab);
        } else if (tabs.size() == 0) {
            newTab("");
        }
    }

    private void showResourceMenu(TabManager.Tab log, String url) {
        showResourceMenu(log, url, false);
    }

    private void showResourceMenu(TabManager.Tab log, String url, boolean more) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        java.util.function.BiConsumer<Integer, Runnable> add = (label, action) -> {
            labels.add(getString(label));
            actions.add(action);
        };
        Set<String> flags = prefs.longPressFlags();
        if (more || flags.contains("bg_open")) add.accept(R.string.via_bk_open_bg, () -> openResourceTab(log, url, false));
        if (more || flags.contains("new_tab")) add.accept(R.string.via_bk_open_newtab, () -> openResourceTab(log, url, true));
        if (more || flags.contains("copy_link")) add.accept(R.string.via_bk_copy_link,
                () -> runLongPressAction("copy_link", url, null, null));
        if (more || flags.contains("share")) add.accept(R.string.via_menu_share,
                () -> runLongPressAction("share", url, null, null));
        if (!more) {
            add.accept(R.string.via_sniffer_play, () -> {
                try {
                    Intent play = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*");
                    startActivity(prefs.externalPlayer() == 1 ? Intent.createChooser(play, getString(R.string.via_sniffer_play)) : play);
                } catch (android.content.ActivityNotFoundException error) {
                    GlassToast.makeText(this, R.string.via_sniffer_no_player, GlassToast.LENGTH_SHORT).show();
                }
            });
            add.accept(R.string.via_menu_download, () -> confirmResourceDownload(log, url));
            add.accept(R.string.via_sniffer_clear, () -> new AlertDialog.Builder(this)
                    .setMessage(R.string.via_sniffer_clear_confirm)
                    .setNegativeButton(R.string.via_cancel, null)
                    .setPositiveButton(R.string.via_sniffer_clear, (dialog, which) -> {
                        TabState state = getState(log);
                        if (state == null || state.resourceSourceId < 0) return;
                        viaSniffer.clear(state.resourceSourceId);
                        log.webView.reload();
                    }).show());
            if (!flags.containsAll(java.util.Arrays.asList("bg_open", "new_tab", "copy_link", "share"))) {
                add.accept(R.string.via_sniffer_more, () -> showResourceMenu(log, url, true));
            }
            add.accept(R.string.via_bk_edit, () -> startActivityForResult(new Intent(this, BrowserSettingsActivity.class)
                    .putExtra(BrowserSettingsActivity.EXTRA_OPEN_LONGPRESS, true), REQ_SETTINGS));
        }
        ViaUi.listDialog(this, null, labels.toArray(new String[0]), index -> actions.get(index).run()).show();
    }

    private void openResourceTab(TabManager.Tab log, String url, boolean foreground) {
        TabManager.Tab opened = newTab(null, false);
        getState(opened).incognito = getState(log).incognito;
        opened.url = url;
        applySettings(opened.webView);
        if (foreground) {
            tabs.select(tabs.indexOf(opened));
            showTab(opened);
        }
        opened.webView.loadUrl(url);
    }

    private void confirmResourceDownload(TabManager.Tab log, String url) {
        confirmFileDownload(url, globalDownloadUserAgent(), null, null, null, -1, log.title);
    }

    private String globalDownloadUserAgent() {
        return prefs.getEffectiveUserAgent(false, "", android.webkit.WebSettings.getDefaultUserAgent(this));
    }

    private void confirmFileDownload(String url, String userAgent, String referer,
                                     String contentDisposition, String mimeType, long contentLength, String pageTitle) {
        com.example.cleanrecovery.ui.browser.BrowserDownloadDialog.show(this, url, contentDisposition, mimeType, contentLength,
                (filename, selectedMimeType) -> {
                    String name = new File(filename).getName();
                    if (name.isEmpty()) return;
                    java.util.Map<String, String> headers = new java.util.LinkedHashMap<>();
                    if (!TextUtils.isEmpty(userAgent)) headers.put("User-Agent", userAgent);
                    if (!TextUtils.isEmpty(referer)) headers.put("Referer", referer);
                    if (prefs.downloadManager() == 1) {
                        try {
                            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(Uri.parse(url));
                            request.setTitle(name);
                            request.setDestinationUri(Uri.fromFile(new File(getDownloadDir(), name)));
                            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                            if (!TextUtils.isEmpty(selectedMimeType)) request.setMimeType(selectedMimeType);
                            for (java.util.Map.Entry<String, String> header : headers.entrySet())
                                request.addRequestHeader(header.getKey(), header.getValue());
                            String cookie = android.webkit.CookieManager.getInstance().getCookie(url);
                            if (!TextUtils.isEmpty(cookie)) request.addRequestHeader("Cookie", cookie);
                            ((android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                            GlassToast.makeText(this, R.string.via_sniffer_download_queued, GlassToast.LENGTH_SHORT).show();
                        } catch (Exception error) {
                            GlassToast.makeText(this, error.getMessage(), GlassToast.LENGTH_SHORT).show();
                        }
                    } else {
                        DownloadQueueManager queue = DownloadQueueManager.getInstance();
                        queue.init(this);
                        int id = queue.enqueue(url, selectedMimeType, referer, pageTitle, name, headers);
                        if (id >= 0) startService(new Intent(this, BackgroundDownloadService.class));
                        GlassToast.makeText(this, id < 0 ? R.string.via_sniffer_already_queued
                                : R.string.via_sniffer_download_queued, GlassToast.LENGTH_SHORT).show();
                    }
                });
    }

    private void updateSnifferButton() {
        if (snifferButton == null) return;
        if (!prefs.autoSnifferButton()) {
            snifferButton.setVisibility(View.GONE);
            return;
        }
        TabManager.Tab cur = tabs.current();
        if (cur == null || (isHomeVisible())
                || isResourcePage(cur, cur.url)) {
            snifferButton.setVisibility(View.GONE);
            return;
        }
        boolean show = viaSniffer.shouldShowButton(cur.id);
        snifferButton.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void toggleGameMode() {
        gameMode = !gameMode;
        if (gameMode) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            toolbar.setVisibility(View.GONE);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (!fullscreen) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            toolbar.setVisibility(View.VISIBLE);
        }
        GlassToast.makeText(this, gameMode ? R.string.via_game_on : R.string.via_game_off,
                GlassToast.LENGTH_SHORT).show();
    }

    private void showSiteTools() {
        int[] order = {
                R.id.menu_find, R.id.menu_save, R.id.menu_offline, R.id.menu_translate,
                R.id.menu_view_source,
                R.id.menu_fullscreen, R.id.menu_image_mode, R.id.menu_sniff,
                R.id.menu_useragent, R.id.menu_network_log,
                R.id.menu_scan, R.id.menu_add_to_home, R.id.menu_read_aloud,
                R.id.menu_ai, R.id.menu_rotation,
                R.id.menu_adblock, R.id.menu_mark_ad, R.id.menu_font_size,
                R.id.menu_report, R.id.menu_customize_menu
        };
        Map<Integer, BrowserBottomMenu.Entry> byId = new HashMap<>();
        for (BrowserBottomMenu.Entry entry : buildMenuEntries()) byId.put(entry.id, entry);
        List<BrowserBottomMenu.Entry> tools = new ArrayList<>();
        for (int id : order) {
            BrowserBottomMenu.Entry source = byId.get(id);
            if (source == null) continue;
            CharSequence title = source.title;
            if (id == R.id.menu_useragent) title = getString(R.string.via_toolbox_browser_id);
            else if (id == R.id.menu_rotation) title = getString(R.string.via_toolbox_orientation);
            else if (id == R.id.menu_report) title = getString(R.string.via_toolbox_report_site);
            tools.add(new BrowserBottomMenu.Entry(
                    source.id, source.resourceName, title, source.icon));
        }
        new BrowserBottomMenu(this, tools, this::onMenuAction).show();
    }

    private void showSiteConfiguration() {
        String host = currentHost();
        if (TextUtils.isEmpty(host)) {
            GlassToast.makeText(this, R.string.browser_empty_hint, GlassToast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(this, BrowserSiteSettingsActivity.class);
        it.putExtra(BrowserSiteSettingsActivity.EXTRA_HOST, host);
        startActivityForResult(it, REQ_SITE_SETTINGS);
    }

    private void toggleSiteCookies(String host) {
        boolean off = !prefs.siteCookiesOff(host);
        prefs.setSiteCookiesOff(host, off);
        if (off) clearCookiesForCurrentUrl();
        GlassToast.makeText(this, off ? R.string.via_site_cookies_off : R.string.via_site_cookies_on,
                GlassToast.LENGTH_SHORT).show();
    }

    private void clearCookiesForCurrentUrl() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || TextUtils.isEmpty(cur.url)) return;
        clearCookiesForUrl(cur.url);
    }

    private void clearCookiesForUrl(String url) {
        com.example.cleanrecovery.ui.browser.BrowserCookies.clear(this, url, success ->
                GlassToast.makeText(this, success ? "已置空 " + hostFromUrl(url) + " 的 Cookies"
                        : "Cookies 未完全清除", GlassToast.LENGTH_SHORT).show());
    }

    private void showSiteUserAgentDialog(String host) {
        String[] names = {
                getString(R.string.via_settings_ua_default),
                getString(R.string.via_settings_ua_chrome),
                getString(R.string.via_settings_ua_android_phone),
                getString(R.string.via_site_custom_ua)
        };
        String[] values = {"", desktopUserAgent(), mobileUserAgent(), null};
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_menu_useragent)
                .setItems(names, (dialog, which) -> {
                    if (which == values.length - 1) {
                        showSiteCustomUserAgentDialog(host);
                    } else {
                        prefs.setSiteUserAgent(host, values[which]);
                        reloadCurrentWithSettings();
                        GlassToast.makeText(this, R.string.via_site_settings_saved,
                                GlassToast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void showSiteCustomUserAgentDialog(String host) {
        EditText input = new EditText(this);
        input.setSingleLine(false);
        input.setText(prefs.siteUserAgent(host));
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_site_custom_ua)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    prefs.setSiteUserAgent(host, input.getText().toString().trim());
                    reloadCurrentWithSettings();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void toggleSiteAdBlock(String host) {
        boolean off = !prefs.siteAdBlockOff(host);
        prefs.setSiteAdBlockOff(host, off);
        reloadCurrentWithSettings();
        GlassToast.makeText(this, off ? R.string.via_site_adblock_off : R.string.via_site_adblock_follow,
                GlassToast.LENGTH_SHORT).show();
    }

    private Dialog showSiteFontSizeDialog(String host) {
        int current = prefs.siteTextZoom(host, prefs.textZoom());
        Dialog dialog = ViaUi.textZoomDialog(this, current, true, zoom -> {
            prefs.setSiteSettingsEnabled(host, true);
            prefs.setSiteTextZoom(host, zoom);
            TabManager.Tab tab = tabs.current();
            if (tab != null) tab.webView.getSettings().setTextZoom(zoom);
        });
        dialog.show();
        return dialog;
    }

    private void resetSiteSettings(String host) {
        prefs.resetSiteSettings(host);
        reloadCurrentWithSettings();
        GlassToast.makeText(this, R.string.via_site_reset_done, GlassToast.LENGTH_SHORT).show();
    }

    private void reloadCurrentWithSettings() {
        TabManager.Tab cur = tabs.current();
        if (cur == null) return;
        applySettings(cur.webView);
        if (!TextUtils.isEmpty(cur.url)) cur.webView.reload();
    }

    private void showUserAgentDialog() {
        String[] names = {"Android Chrome", "Windows Chrome", "macOS Safari",
                "iPhone Safari", "iPad Safari", "Firefox", "Edge", "Googlebot", "自定义"};
        String[] values = {
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 "
                        + "(KHTML, like Gecko) Version/17.0 Safari/605.1.15",
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                        + "AppleWebKit/605.1.15 Version/17.0 Mobile/15E148 Safari/604.1",
                "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 "
                        + "Version/17.0 Mobile/15E148 Safari/604.1",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:121.0) Gecko/20100101 Firefox/121.0",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.0.0",
                "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
                ""
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_menu_useragent)
                .setItems(names, (dialog, which) -> {
                    if (which == values.length - 1) {
                        showCustomUserAgentDialog();
                    } else {
                        prefs.setCustomUserAgent(values[which]);
                        for (TabManager.Tab tab : tabs.all()) applySettings(tab.webView);
                    }
                })
                .show();
    }

    private void showCustomUserAgentDialog() {
        EditText input = new EditText(this);
        input.setText(prefs.customUserAgent());
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_menu_useragent)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    prefs.setCustomUserAgent(input.getText().toString().trim());
                    for (TabManager.Tab tab : tabs.all()) applySettings(tab.webView);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** VIA AI：底部「所有主题」抽屉（主题列表 + 设置 + 新建，空态 ¯\_(ツ)_/¯）。 */
    private android.app.Dialog showAiSheet() {
        android.app.Dialog sheet = new android.app.Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        float r = dp(14);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        root.setBackground(bg);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        java.util.Set<String> selected = new java.util.LinkedHashSet<>();
        ImageView cancel = new ImageView(this);
        cancel.setImageResource(R.drawable.ic_close);
        cancel.setContentDescription("取消选择");
        cancel.setPadding(dp(8), dp(8), dp(8), dp(8));
        head.addView(cancel, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("所有主题");
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(0xFF212121);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        ImageView gear = new ImageView(this);
        gear.setImageResource(R.drawable.via_menu_settings);
        gear.setPadding(dp(8), dp(8), dp(8), dp(8));
        gear.setOnClickListener(v -> {
            sheet.dismiss();
            startActivityForResult(new Intent(this, BrowserSettingsActivity.class)
                    .putExtra(BrowserSettingsActivity.EXTRA_OPEN_AI, true), REQ_SETTINGS);
        });
        head.addView(gear, new LinearLayout.LayoutParams(dp(40), dp(40)));
        ImageView add = new ImageView(this);
        add.setImageResource(R.drawable.ic_add);
        add.setPadding(dp(8), dp(8), dp(8), dp(8));
        head.addView(add, new LinearLayout.LayoutParams(dp(40), dp(40)));
        root.addView(head, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ViewGroup.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        Runnable[] rerender = new Runnable[1];
        rerender[0] = () -> {
            boolean selecting = !selected.isEmpty();
            title.setText(selecting ? String.valueOf(selected.size()) : "所有主题");
            cancel.setVisibility(selecting ? View.VISIBLE : View.GONE);
            gear.setVisibility(selecting ? View.GONE : View.VISIBLE);
            add.setImageResource(selecting ? R.drawable.ic_delete : R.drawable.ic_add);
            add.setContentDescription(selecting ? "删除" : "新主题");
            list.removeAllViews();
            List<BrowserAiStore.Topic> themes = prefs.aiStore().topics();
            if (themes.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("\u00af\\_(\u30c4)_/\u00af");
                empty.setTextSize(22);
                empty.setTextColor(0xFF9E9E9E);
                empty.setGravity(Gravity.CENTER);
                list.addView(empty, new LinearLayout.LayoutParams(-1, dp(260)));
                return;
            }
            for (BrowserAiStore.Topic topic : themes) {
                TextView row = new TextView(this);
                row.setText(topic.name);
                row.setTextSize(15);
                row.setTextColor(0xFF212121);
                row.setPadding(dp(4), dp(14), dp(4), dp(14));
                row.setClickable(true);
                row.setBackgroundResource(R.drawable.bg_via_menu_cell);
                if (selected.contains(topic.id)) row.setBackgroundColor(0x226F8DE1);
                row.setOnClickListener(v -> {
                    if (!selected.isEmpty()) {
                        if (!selected.remove(topic.id)) selected.add(topic.id);
                        rerender[0].run();
                        return;
                    }
                    sheet.dismiss();
                    openAiChat(topic);
                });
                row.setOnLongClickListener(v -> {
                    selected.add(topic.id);
                    rerender[0].run();
                    return true;
                });
                list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            }
        };
        rerender[0].run();
        cancel.setOnClickListener(v -> { selected.clear(); rerender[0].run(); });

        add.setContentDescription("新主题");
        add.setOnClickListener(v -> {
            if (!selected.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("确认删除").setMessage("删除选中的 " + selected.size() + " 个主题？")
                        .setNegativeButton(R.string.via_cancel, null)
                        .setPositiveButton("删除", (confirm, button) -> {
                            for (String id : selected) prefs.aiStore().delete(id);
                            selected.clear(); rerender[0].run();
                        }).show();
                return;
            }
            sheet.dismiss();
            openAiChat(null);
        });

        sheet.setContentView(root);
        sheet.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        sizeAiSheet(sheet);
        sheet.show();
        return sheet;
    }

    /** AI 对话页：使用设置中选择的 OpenAI 兼容服务发送真实请求。 */
    private android.app.Dialog openAiChat(BrowserAiStore.Topic topic) {
        return openAiChat(topic, null);
    }

    private void openPageAi() {
        TabManager.Tab tab = tabs.current();
        if (tab == null || tab.url == null || !(tab.url.startsWith("https://") || tab.url.startsWith("http://"))
                || !tab.webView.getSettings().getJavaScriptEnabled()) { showAiSheet(); return; }
        String url = tab.url, title = tab.webView.getTitle();
        int generation = getState(tab).documentGeneration;
        WebView sourceView = tab.webView;
        reader().run(tab.webView, "text", prefs, value -> {
            if (tabs.current() != tab || tab.webView != sourceView || getState(tab).documentGeneration != generation) return;
            String text;
            try { Object decoded = new org.json.JSONTokener(value).nextValue(); text = decoded instanceof String ? (String) decoded : ""; }
            catch (org.json.JSONException error) { text = ""; }
            openAiChat(prefs.aiStore().topicForPage(url), new BrowserAiPrompt.Page(url, title, text));
        });
    }

    private android.app.Dialog openAiChat(BrowserAiStore.Topic topic, BrowserAiPrompt.Page page) {
        BrowserAiStore store = prefs.aiStore();
        String[] topicId = {topic == null ? null : topic.id};
        BrowserAiPrompt[] selectedPrompt = {null};
        if (topic == null) for (BrowserAiPrompt prompt : store.prompts()) {
            if (prompt.id.equals(prefs.aiDefaultPromptId()) && prompt.needsPage() == (page != null)) selectedPrompt[0] = prompt;
        }
        android.app.Dialog chat = new android.app.Dialog(this);
        if (aiChatDialog != null) aiChatDialog.dismiss();
        aiChatDialog = chat;
        io.noties.markwon.Markwon markdown = BrowserAiMarkdown.create(this, link -> {
            chat.dismiss();
            newTab(link);
        });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this);
        back.setText("‹"); back.setTextSize(28); back.setContentDescription("所有主题");
        back.setOnClickListener(v -> { chat.dismiss(); showAiSheet(); });
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(48)));
        TextView title = new TextView(this);
        title.setText(topic == null ? "新主题" : topic.name);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(0xFF212121);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView more = new TextView(this);
        more.setText("⋮"); more.setTextSize(24); more.setContentDescription("更多");
        more.setGravity(Gravity.CENTER);
        header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));
        ScrollView history = new ScrollView(this);
        LinearLayout messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        history.addView(messages, new ScrollView.LayoutParams(-1, -2));
        root.addView(history, new LinearLayout.LayoutParams(-1, 0, 1f));
        Runnable[] render = new Runnable[1];
        render[0] = () -> {
            messages.removeAllViews();
            for (BrowserAiStore.Message message : store.messages(topicId[0])) {
                if (!"system".equals(message.role)) {
                    TextView view = addAiMessage(messages, message.role, message.content, message.reasoning, markdown);
                    bindAiMessageActions(view, topicId[0], message, markdown, () -> render[0].run());
                }
            }
            if (messages.getChildCount() == 0) {
                TextView empty = addAiMessage(messages, "", "¯\\_(ツ)_/¯", "", markdown);
                empty.setGravity(Gravity.CENTER); empty.setPadding(0, dp(120), 0, dp(120));
            }
        };
        render[0].run();
        android.widget.HorizontalScrollView promptScroll = new android.widget.HorizontalScrollView(this);
        promptScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout promptChoices = new LinearLayout(this);
        promptScroll.addView(promptChoices);
        root.addView(promptScroll, new LinearLayout.LayoutParams(-1, -2));
        Runnable[] renderPrompts = new Runnable[1];
        renderPrompts[0] = () -> {
            promptChoices.removeAllViews();
            List<BrowserAiPrompt> choices = new ArrayList<>();
            for (BrowserAiPrompt prompt : store.prompts()) {
                if (prompt.type == (topicId[0] == null ? 1 : 2)
                        && (prompt.type == 2 || prompt.needsPage() == (page != null))) choices.add(prompt);
            }
            promptScroll.setVisibility(choices.isEmpty() ? View.GONE : View.VISIBLE);
            if (choices.isEmpty()) { selectedPrompt[0] = null; return; }
            choices.add(0, new BrowserAiPrompt("default", "随便聊聊", "", 0));
            for (BrowserAiPrompt prompt : choices) {
                TextView chip = new TextView(this);
                chip.setText(prompt.name); chip.setTextSize(12); chip.setPadding(dp(12), dp(8), dp(12), dp(8));
                boolean selected = selectedPrompt[0] == null ? prompt.type == 0 : selectedPrompt[0].id.equals(prompt.id);
                chip.setTextColor(selected ? ViaUi.ACCENT : 0xFF757575);
                chip.setOnClickListener(v -> { selectedPrompt[0] = prompt.type == 0 ? null : prompt; renderPrompts[0].run(); });
                promptChoices.addView(chip);
            }
        };
        renderPrompts[0].run();
        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(this);
        input.setHint("消息");
        input.setMaxLines(5);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setTextSize(15);
        input.setBackgroundResource(R.drawable.bg_via_input);
        input.setPadding(dp(2), dp(10), dp(2), dp(12));
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView send = new TextView(this);
        send.setText("发送"); send.setTextSize(15); send.setTextColor(ViaUi.ACCENT);
        send.setGravity(Gravity.CENTER); send.setPadding(dp(14), dp(12), dp(4), dp(12));
        composer.addView(send, new LinearLayout.LayoutParams(-2, -2));
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        final BrowserAiClient.Request[] active = new BrowserAiClient.Request[1];
        final Runnable[] finish = new Runnable[1];
        Runnable submit = () -> {
            if (active[0] != null) {
                active[0].cancel();
                finish[0].run();
                return;
            }
            String message = input.getText().toString().trim();
            if (message.isEmpty()) return;
            if (prefs.aiProviderName().isEmpty() || prefs.aiEndpoint().isEmpty() || prefs.aiModel().isEmpty()) {
                GlassToast.makeText(this, "请先在 AI 设置中配置服务提供商和模型", GlassToast.LENGTH_SHORT).show();
                return;
            }
            final org.json.JSONArray requestMessages;
            String sentMessage = topicId[0] != null && selectedPrompt[0] != null
                    ? selectedPrompt[0].message(message) : message;
            if (topicId[0] == null) {
                String prompt = selectedPrompt[0] == null ? BrowserAiPrompt.defaultSystem(page) : selectedPrompt[0].system(page);
                String name = message.substring(0, Math.min(message.length(), 200)).replace('\n', ' ');
                BrowserAiStore.Topic created = store.create(name, prompt);
                topicId[0] = created.id; title.setText(created.name);
                if (page != null) store.bindPage(created.id, page.url);
                selectedPrompt[0] = null;
                renderPrompts[0].run();
                String endpoint = prefs.aiEndpoint(), apiKey = prefs.aiApiKey(), model = prefs.aiModel();
                executor.execute(() -> {
                    String generated;
                    try {
                        generated = BrowserAiClient.chat(endpoint, apiKey, model,
                                "Act as an Expert Title Summarizer. Generate a concise summary title (max 10 words, one sentence) "
                                        + "in the same language as the input text. If the input contains proper terms (e.g., 'Java/Python'), "
                                        + "prioritize the user's language context over term language. Avoid markdown formatting.", message).trim();
                    } catch (Exception error) { generated = ""; }
                    String nameResult = generated.isEmpty() ? message.substring(0, Math.min(message.length(), 20)) : generated;
                    store.rename(created.id, nameResult);
                    mainHandler.post(() -> { if (chat.isShowing()) title.setText(nameResult); });
                });
            }
            store.append(topicId[0], "user", sentMessage, "");
            try { requestMessages = store.requestMessages(topicId[0]); }
            catch (org.json.JSONException error) { throw new IllegalStateException(error); }
            input.setText(""); send.setText("停止");
            render[0].run();
            TextView replyView = addAiMessage(messages, "assistant", "正在回复…", "", markdown);
            BrowserAiClient.Request request = new BrowserAiClient.Request();
            active[0] = request;
            String[] reply = {"", ""};
            int[] replyFlags = {0};
            Runnable complete = () -> {
                if (active[0] != request) return;
                if (!reply[0].isEmpty() || !reply[1].isEmpty()) store.append(topicId[0], "assistant", reply[0], reply[1], replyFlags[0]);
                active[0] = null;
                send.setText("发送");
                render[0].run();
                history.post(() -> history.fullScroll(View.FOCUS_DOWN));
            };
            finish[0] = complete;
            String endpoint = prefs.aiEndpoint(), apiKey = prefs.aiApiKey(), model = prefs.aiModel();
            executor.execute(() -> {
                try {
                    request.run(endpoint, apiKey, model, requestMessages, (content, reasoning) -> mainHandler.post(() -> {
                        if (active[0] != request) return;
                        reply[0] = content; reply[1] = reasoning;
                        boolean follow = !history.canScrollVertically(1);
                        markdown.setMarkdown(replyView, aiMessageText(content, reasoning));
                        if (follow) history.post(() -> history.fullScroll(View.FOCUS_DOWN));
                    }));
                    mainHandler.post(complete);
                } catch (Exception e) {
                    mainHandler.post(() -> {
                        if (active[0] != request) return;
                        if (!request.isCancelled() && reply[0].isEmpty() && reply[1].isEmpty()) {
                            reply[0] = "❗" + e.getMessage();
                            replyFlags[0] = 1;
                        }
                        complete.run();
                    });
                }
            });
        };
        send.setOnClickListener(v -> submit.run());
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != android.view.inputmethod.EditorInfo.IME_ACTION_SEND) return false;
            if (active[0] == null) submit.run();
            return true;
        });
        more.setOnClickListener(v -> {
            boolean hasMessages = false;
            for (BrowserAiStore.Message message : store.messages(topicId[0])) {
                if (!"system".equals(message.role)) { hasMessages = true; break; }
            }
            if (!hasMessages) { selectAiModel(); return; }
            new AlertDialog.Builder(this)
                .setItems(new String[]{prefs.aiModel().isEmpty() ? "选择模型" : prefs.aiModel(), "导出聊天", "清空消息"}, (dialog, which) -> {
                    if (which == 0) selectAiModel();
                    else if (which == 1) {
                        pendingAiExport = topicId[0];
                        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                                .setType("text/plain").putExtra(Intent.EXTRA_TITLE,
                                        title.getText().toString().replaceAll("[\\\\/:*?\"<>|]", "_") + ".txt"), REQ_AI_EXPORT);
                    }
                    else new AlertDialog.Builder(this).setTitle("确认清空")
                            .setNegativeButton(R.string.via_cancel, null).setPositiveButton("清空", (confirm, button) -> {
                                if (active[0] != null) { active[0].cancel(); finish[0].run(); }
                                store.clear(topicId[0]); render[0].run();
                            }).show();
                }).show();
        });
        chat.setOnDismissListener(dialog -> {
            if (active[0] != null) { active[0].cancel(); finish[0].run(); }
            if (aiChatDialog == chat) aiChatDialog = null;
        });
        chat.setContentView(root);
        chat.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        sizeAiSheet(chat);
        chat.show();
        return chat;
    }

    private String aiMessageText(String content, String reasoning) {
        return reasoning.isEmpty() ? content : ">" + reasoning.replace("\n", "\n>")
                + (content.isEmpty() ? "" : "\n\n" + content);
    }

    private void sizeAiSheet(android.app.Dialog dialog) {
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        int longest = Math.max(metrics.widthPixels, metrics.heightPixels);
        boolean landscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        dialog.getWindow().setLayout(landscape ? Math.min(dp(440), longest / 2) : -1,
                landscape ? -1 : longest / 9 * 5);
        dialog.getWindow().setGravity(Gravity.BOTTOM);
        dialog.getWindow().setDimAmount(0.2f);
    }

    private void selectAiModel() {
        List<String> models = new ArrayList<>();
        for (String model : prefs.aiModels().split("\n")) if (!model.trim().isEmpty()) models.add(model.trim());
        if (models.isEmpty()) {
            startActivityForResult(new Intent(this, BrowserSettingsActivity.class)
                    .putExtra(BrowserSettingsActivity.EXTRA_OPEN_AI, true), REQ_SETTINGS);
        } else if (models.size() > 1) {
            new AlertDialog.Builder(this).setTitle("选择模型")
                    .setSingleChoiceItems(models.toArray(new String[0]), models.indexOf(prefs.aiModel()), (dialog, index) -> {
                        prefs.setAiModel(models.get(index)); dialog.dismiss();
                    }).show();
        }
    }

    private TextView addAiMessage(LinearLayout messages, String role, String content, String reasoning,
                                  io.noties.markwon.Markwon markdown) {
        TextView view = new TextView(this);
        view.setText(aiMessageText(content, reasoning));
        view.setTextSize(14);
        view.setTextColor(0xFF212121);
        view.setTextIsSelectable(true);
        if (!role.isEmpty()) markdown.setMarkdown(view, aiMessageText(content, reasoning));
        view.setPadding(dp(8), dp(12), dp(8), dp(12));
        if ("user".equals(role)) view.setBackgroundResource(R.drawable.bg_via_input);
        messages.addView(view, new LinearLayout.LayoutParams(-1, -2));
        return view;
    }

    private void bindAiMessageActions(TextView view, String topicId, BrowserAiStore.Message message,
                                      io.noties.markwon.Markwon markdown, Runnable refresh) {
        view.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
            @Override public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                menu.add(0, 1, 0, "复制全文");
                menu.add(0, 2, 0, "预览");
                menu.add(0, 3, 0, "删除");
                return true;
            }
            @Override public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                menu.removeItem(android.R.id.selectAll); menu.removeItem(android.R.id.cut); menu.removeItem(android.R.id.shareText);
                return true;
            }
            @Override public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                if (item.getItemId() == 1) {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("消息", aiMessageText(message.content, message.reasoning)));
                }
                else if (item.getItemId() == 2) showAiMessagePreview(message, markdown);
                else if (item.getItemId() == 3) new AlertDialog.Builder(BrowserActivity.this).setTitle("删除")
                        .setMessage("确认删除这条消息？").setNegativeButton(R.string.via_cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            prefs.aiStore().deleteMessage(topicId, message.id); refresh.run();
                        }).show();
                else return false;
                mode.finish();
                return true;
            }
            @Override public void onDestroyActionMode(android.view.ActionMode mode) { }
        });
    }

    private android.app.Dialog showAiMessagePreview(BrowserAiStore.Message message, io.noties.markwon.Markwon markdown) {
        android.app.Dialog preview = new android.app.Dialog(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Color.WHITE);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = new TextView(this); back.setText("‹"); back.setTextSize(28);
        back.setGravity(Gravity.CENTER); back.setContentDescription("返回"); back.setOnClickListener(v -> preview.dismiss());
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = new TextView(this); title.setText("消息"); title.setTextSize(17); title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView toggle = new TextView(this); toggle.setText("纯文本"); toggle.setPadding(dp(16), dp(12), dp(16), dp(12));
        header.addView(toggle); root.addView(header);
        ScrollView scroll = new ScrollView(this); TextView body = new TextView(this);
        body.setTextSize(14); body.setTextColor(0xFF212121); body.setTextIsSelectable(true);
        body.setPadding(dp(16), dp(16), dp(16), dp(16)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        String raw = aiMessageText(message.content.trim(), message.reasoning.trim());
        markdown.setMarkdown(body, raw);
        toggle.setOnClickListener(v -> {
            if ("纯文本".contentEquals(toggle.getText())) { body.setText(raw); toggle.setText("Markdown"); }
            else { markdown.setMarkdown(body, raw); toggle.setText("纯文本"); }
        });
        preview.setContentView(root); sizeAiSheet(preview); preview.getWindow().setDimAmount(0);
        preview.show();
        return preview;
    }

    private void sharePageToAi() {
        TabManager.Tab tab = tabs.current();
        if (tab == null || TextUtils.isEmpty(tab.url)) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, tab.title + "\n" + tab.url);
        startActivity(Intent.createChooser(send, getString(R.string.via_ai_chooser)));
    }

    private void toggleAdBlock() {
        boolean enabled = !prefs.adBlockEnabled();
        prefs.setAdBlockEnabled(enabled);
        TabManager.Tab tab = tabs.current();
        if (tab != null) tab.webView.reload();
        GlassToast.makeText(this, enabled ? R.string.via_adblock_on : R.string.via_adblock_off,
                GlassToast.LENGTH_SHORT).show();
    }

    /** 关闭当前标签：若空则新建空白标签（显示主页）。 */
    private void closeCurrentTab() {
        TabManager.Tab closed = closeAndDestroyTab(tabs.currentIndex());
        if (closed == null) {
            newTab("");
            showHome();
        } else {
            showTab(closed);
        }
        updateTabBadge();
    }

    private void shareCurrentUrl() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.url == null || cur.url.isEmpty()) {
            GlassToast.makeText(this, R.string.browser_no_media_selected, GlassToast.LENGTH_SHORT).show();
            return;
        }
        String[] items = {
                getString(R.string.via_share_link),
                getString(R.string.via_share_title_link),
                getString(R.string.via_share_copy_link),
                getString(R.string.via_settings_system_share)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_menu_share)
                .setItems(items, (dialog, which) -> {
                    if (which == 2) {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("url", cur.url));
                        GlassToast.makeText(this, R.string.via_share_link_copied, GlassToast.LENGTH_SHORT).show();
                        return;
                    }
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_SUBJECT, cur.title);
                    String text = which == 1 && !TextUtils.isEmpty(cur.title)
                            ? cur.title + "\n" + cur.url : cur.url;
                    send.putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(send, getString(R.string.via_share_link)));
                })
                .show();
    }

    private void openDownloadActivity() {
        startActivity(new Intent(this, BrowserDownloadsActivity.class));
    }

    /** 用其它应用打开当前 URL。 */
    private void openWith() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.url == null || cur.url.isEmpty()) {
            GlassToast.makeText(this, R.string.browser_empty_hint, GlassToast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(cur.url));
            startActivity(Intent.createChooser(it, getString(R.string.via_open_with_title)));
        } catch (Exception e) {
            GlassToast.makeText(this, e.getMessage(), GlassToast.LENGTH_SHORT).show();
        }
    }

    private TabManager.Tab closeAndDestroyTab(int index) {
        if (index < 0 || index >= tabs.size()) return tabs.current();
        TabManager.Tab removed = tabs.all().get(index);
        TabState removedState = getState(removed);
        if (removedState != null && !removedState.incognito && removedState.resourceSourceId < 0
                && removed.url != null && !removed.url.isEmpty()) {
            String closedUrl = removed.url;
            try {
                org.json.JSONArray old = new org.json.JSONArray(prefs.closedTabs().isEmpty() ? "[]" : prefs.closedTabs());
                org.json.JSONArray recent = new org.json.JSONArray();
                recent.put(new org.json.JSONObject().put("u", closedUrl).put("t", removed.title));
                for (int i = 0; i < Math.min(19, old.length()); i++) recent.put(old.get(i));
                prefs.setClosedTabs(recent.toString());
                hasRestorableClosedTab = true;
            } catch (org.json.JSONException e) { Log.w(TAG, "Cannot record closed tab", e); }
        }
        for (TabManager.Page page : removed.clearPages()) destroyPage(page);
        viaSniffer.removeTab(removed.id);
        return tabs.close(index);
    }

    /** 切换屏幕方向（横屏/竖屏）。 */
    private void toggleRotation() {
        int cur = getRequestedOrientation();
        if (cur == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            GlassToast.makeText(this, R.string.via_rotation_portrait, GlassToast.LENGTH_SHORT).show();
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            GlassToast.makeText(this, R.string.via_rotation_landscape, GlassToast.LENGTH_SHORT).show();
        }
    }

    /** 切换全屏（隐藏系统 UI 与工具栏，再按退出）。 */
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        if (fullscreen) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            GlassToast.makeText(this, R.string.via_fullscreen_on, GlassToast.LENGTH_SHORT).show();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            GlassToast.makeText(this, R.string.via_fullscreen_off, GlassToast.LENGTH_SHORT).show();
        }
        applyToolbarMode();
    }

    /** 截图：把当前 WebView 绘制到 Bitmap 并保存到下载目录。 */
    private void captureScreenshot() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.webView == null) {
            GlassToast.makeText(this, R.string.via_screenshot_failed, GlassToast.LENGTH_SHORT).show();
            return;
        }
        WebView wv = cur.webView;
        try {
            Bitmap bmp = Bitmap.createBitmap(wv.getWidth(), wv.getHeight(), Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bmp);
            wv.draw(c);
            File dir = getDownloadDir();
            String name = "screenshot_" + System.currentTimeMillis() + ".png";
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            GlassToast.makeText(this, getString(R.string.via_screenshot_saved, out.getAbsolutePath()),
                    GlassToast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "screenshot failed", e);
            GlassToast.makeText(this, R.string.via_screenshot_failed, GlassToast.LENGTH_SHORT).show();
        }
    }

    /** 查看源码：JS 取 outerHTML，AlertDialog 展示。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void showPageSource() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.url == null || cur.url.isEmpty()) {
            GlassToast.makeText(this, R.string.browser_empty_hint, GlassToast.LENGTH_SHORT).show();
            return;
        }
        final WebView wv = cur.webView;
        wv.evaluateJavascript(
                "(function(){try{return '<!doctype html>'+document.documentElement.outerHTML;"
                        + "}catch(e){return String(e);}})();",
                value -> {
                    String src = decodeJsString(value);
                    TextView tv = new TextView(this);
                    tv.setText(src);
                    tv.setTextSize(11);
                    tv.setTypeface(android.graphics.Typeface.MONOSPACE);
                    tv.setTextIsSelectable(true);
                    ScrollView sc = new ScrollView(this);
                    int pad = getResources().getDimensionPixelSize(R.dimen.space_md);
                    sc.setPadding(pad, pad, pad, pad);
                    sc.addView(tv);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.via_view_source_title)
                            .setView(sc)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                });
    }

    /** 把 JS 回调返回的带引号字符串解码为原文（极简处理）。 */
    private static String decodeJsString(String v) {
        if (v == null) return "";
        if (v.equals("null")) return "";
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            String inner = v.substring(1, v.length() - 1);
            return unescapeJsString(inner.replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\"));
        }
        return unescapeJsString(v);
    }

    private static String unescapeJsString(String s) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                String hex = s.substring(i + 2, i + 6);
                try {
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                } catch (Exception ignored) {
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 页面内查找对话框：findAllAsync + 上一个/下一个。 */
    private void showFindInPageDialog() {
        showFindBar();
    }

    /** 把当前页加入书签（VIA 弹窗：名称/地址/目录 + 添加到主页收藏 + 取消/确定）。 */
    private void addCurrentBookmark() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.url == null || cur.url.isEmpty()) {
            GlassToast.makeText(this, R.string.browser_empty_hint, GlassToast.LENGTH_SHORT).show();
            return;
        }
        String name = cur.title != null && !cur.title.isEmpty() ? cur.title : cur.url;
        ViaUi.inputDialog(this, getString(R.string.via_menu_add_bookmark),
                new ViaUi.InputField[]{
                        new ViaUi.InputField("名称", name),
                        new ViaUi.InputField("地址", cur.url),
                        newBookmarkFolderField()
                },
                getString(R.string.via_add_to_home_fav), false,
                null,
                (values, toHome) -> {
                    String title = values[0].trim();
                    String url = values[1].trim();
                    if (title.isEmpty()) title = url;
                    if (!url.isEmpty()) {
                        dbHelper.addBookmark(title, url, values[2].trim());
                        if (toHome) prefs.dbHelper().addQuickLink(title, url);
                        GlassToast.makeText(this, R.string.via_bookmark_added_current,
                                GlassToast.LENGTH_SHORT).show();
                    }
                });
    }

    /** 添加书签弹窗的目录行：弹出文件夹选择器（根目录/已有文件夹/新建文件夹…）。 */
    private ViaUi.InputField newBookmarkFolderField() {
        ViaUi.InputField field = new ViaUi.InputField("目录", "根目录");
        field.onFieldClick = fieldView -> showFolderPicker(fieldView);
        return field;
    }

    private void showFolderPicker(android.widget.EditText target) {
        List<String> folders = new ArrayList<>();
        folders.add("根目录");
        folders.addAll(dbHelper.listFolders());
        String[] options = new String[folders.size() + 1];
        for (int i = 0; i < folders.size(); i++) options[i] = folders.get(i);
        options[folders.size()] = "新建文件夹…";
        new android.app.AlertDialog.Builder(this)
                .setTitle("选择目录")
                .setItems(options, (dialog, which) -> {
                    if (which == folders.size()) {
                        ViaUi.inputDialog(this, "新建文件夹",
                                new ViaUi.InputField("文件夹名称", ""), null, false, null,
                                (values, check) -> {
                                    String n = values[0].trim();
                                    if (!n.isEmpty()) {
                                        dbHelper.addFolder(n);
                                        target.setText(n);
                                    }
                                });
                    } else {
                        target.setText(options[which]);
                    }
                })
                .show();
    }

    /** 把当前页添加为主页九宫格快捷链接。 */
    private void addCurrentQuickLink() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.url == null || cur.url.isEmpty()) {
            GlassToast.makeText(this, R.string.browser_empty_hint, GlassToast.LENGTH_SHORT).show();
            return;
        }
        dbHelper.addQuickLink(cur.title != null && !cur.title.isEmpty() ? cur.title : cur.url, cur.url);
        GlassToast.makeText(this, R.string.via_quicklink_added_current, GlassToast.LENGTH_SHORT).show();
    }

    private void createViaToolbarCompanions() {
        bottomSearchEngineRow = new LinearLayout(this);
        bottomSearchEngineRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomSearchEngineRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomSearchEngineRow.setPadding(dp(12), dp(6), dp(12), dp(6));
        bottomSearchEngineRow.setBackgroundColor(Color.WHITE);
    }

    private ImageButton createToolbarButton(int icon, int description) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(getString(description));
        button.setBackgroundResource(R.drawable.bg_via_toolbar_button);
        button.setPadding(dp(12), dp(12), dp(12), dp(12));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        return button;
    }

    /** VIA 壳三态切换：主页态 / 浏览态 / 地址编辑态；底栏常驻。 */
    private void applyToolbarMode() {
        if (barHome == null || barPage == null || barEdit == null) return;
        if (!addressBarEditing && suggestPopup != null) suggestPopup.dismiss();
        boolean atHome = isHomeVisible();
        if (nightMask != null) {
            nightMask.setBackgroundColor((Math.round(255 * prefs.nightMaskStrength() / 100f) << 24));
            nightMask.setVisibility(prefs != null && prefs.nightMode() && prefs.nightMask()
                    ? View.VISIBLE : View.GONE);
        }
        // 隐身小提醒：仅当前标签隐身且在主页时显示
        if (incognitoHint != null) {
            TabManager.Tab cur = tabs.current();
            TabState st = cur == null ? null : getState(cur);
            incognitoHint.setVisibility(atHome && st != null && st.incognito
                    ? View.VISIBLE : View.GONE);
        }
        // 三态：编辑态优先（主页/浏览态都可进入地址编辑）；主页态；浏览态
        boolean editing = addressBarEditing;
        if (toolbarLayout != null) {
            int mode = prefs.toolbarMode();
            if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                if (mode == 1) mode = 0;
                else if (mode == 3) mode = 2;
            }
            toolbarLayout.apply(customTabMode ? 0 : mode,
                    customTabMode ? false : prefs.tabBarEnabled(), editing);
            if (atHome || editing || prefs.toolbarAutoHide() == 0) toolbarHidden = false;
            toolbarLayout.setHidden(fullscreen || toolbarHidden);
            updateToolbarReveal();
            updateToolbarTabs();
        }
        barHome.setVisibility(!editing && atHome ? View.VISIBLE : View.GONE);
        barEdit.setVisibility(editing ? View.VISIBLE : View.GONE);
        findViewById(R.id.browser_edit_scan).setVisibility(View.GONE);
        barPage.setVisibility(!editing && !atHome ? View.VISIBLE : View.GONE);
        if (engineAvatar != null) {
            engineAvatar.setVisibility(
                    View.GONE);
            renderEngineAvatar();
        }
        if (siteCard != null && (atHome || addressBarEditing)) hideSiteCard();
        TabManager.Tab cur = tabs.current();
        if (cur != null && pageBarTitle != null) {
            String show = prefs.addressContent() == 0 ? cur.title
                    : prefs.addressContent() == 2 ? Uri.parse(cur.url == null ? "" : cur.url).getHost() : cur.url;
            if (show == null || show.trim().isEmpty()) show = cur.url;
            pageBarTitle.setText(show == null ? "" : show);
        }
        updateSearchToolbar(cur != null ? cur.url : null);
        updateSnifferButton();
        configureCustomTabTopAction();
        applyToolbarColors();
        if (Build.VERSION.SDK_INT >= 31 && webContainer != null) {
            webContainer.setRenderEffect(addressBarEditing
                    ? android.graphics.RenderEffect.createBlurEffect(dp(10), dp(10), android.graphics.Shader.TileMode.CLAMP) : null);
        }
    }

    private void applyToolbarColors() {
        if (toolbarLayout == null) return;
        TabManager.Tab tab = tabs.current();
        int color = prefs.nightMode() ? Color.BLACK : Color.WHITE;
        if (!prefs.nightMode() && prefs.adaptiveColor() && tab != null && !isHomeVisible()) {
            color = getState(tab).toolbarColor;
        }
        boolean light = androidx.core.graphics.ColorUtils.calculateLuminance(color) > .5;
        toolbarLayout.setColors(color, light ? 0xff333333 : 0xffeeeeee);
        ViewGroup activityContent = findViewById(android.R.id.content);
        if (activityContent != null && activityContent.getChildCount() > 0)
            activityContent.getChildAt(0).setBackgroundColor(color);
        getWindow().setStatusBarColor(color);
        getWindow().setNavigationBarColor(color);
        int flags = getWindow().getDecorView().getSystemUiVisibility();
        int lightFlags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26) lightFlags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(light ? flags | lightFlags : flags & ~lightFlags);
    }

    private void sampleToolbarColor(TabManager.Tab tab, String url) {
        WebView source = tab.webView;
        source.postDelayed(() -> {
            if (!tabs.all().contains(tab) || tab.webView != source || !TextUtils.equals(url, tab.url)) return;
            Bitmap sample = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            try {
                tab.webView.draw(new android.graphics.Canvas(sample));
                int color = sample.getPixel(0, 0);
                getState(tab).toolbarColor = Color.alpha(color) == 0 ? Color.WHITE : color | 0xff000000;
            } finally { sample.recycle(); }
            if (tab == tabs.current()) applyToolbarColors();
        }, 200);
    }

    private void updateToolbarReveal() {
        boolean visible = toolbarHidden && prefs.toolbarAutoHide() == 2 && !fullscreen;
        if (!visible && toolbarReveal == null) return;
        if (toolbarReveal == null) {
            toolbarReveal = new ImageButton(this);
            toolbarReveal.setImageResource(R.drawable.via_toolbar_reveal);
            toolbarReveal.setColorFilter(Color.WHITE);
            toolbarReveal.setContentDescription("退出全屏模式");
            toolbarReveal.setPadding(dp(10), dp(10), dp(10), dp(10));
            GradientDrawable background = new GradientDrawable();
            background.setShape(GradientDrawable.OVAL);
            background.setColor(0x99333333);
            toolbarReveal.setBackground(background);
            toolbarReveal.setElevation(dp(12));
            toolbarReveal.setOnClickListener(v -> {
                toolbarHidden = false;
                applyToolbarMode();
            });
        }
        if (toolbarReveal.getParent() == null) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(42), dp(42), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            params.bottomMargin = dp(36);
            webContainer.addView(toolbarReveal, params);
        }
        toolbarReveal.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) toolbarReveal.bringToFront();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        applyToolbarMode();
    }

    private String engineLabel() {
        switch (prefs.searchEngine()) {
            case SearchEngines.GOOGLE: return "Google";
            case SearchEngines.BING: return "Bing";
            case SearchEngines.DUCKDUCKGO: return "DuckDuckGo";
            case SearchEngines.YAHOO: return "Yahoo";
            case SearchEngines.YOUTUBE: return "YouTube";
            case com.example.cleanrecovery.ui.browser.SearchEngines.STARTPAGE: return "Startpage";
            case SearchEngines.CUSTOM: return "自定义";
            default: return "百度";
        }
    }

    /** 编辑态左侧引擎头像：圆形底色 + 引擎首字，点击弹出引擎切换。 */
    private void renderEngineAvatar() {
        if (!(engineAvatar instanceof android.widget.TextView)) {
            engineAvatar = engineAvatar != null ? engineAvatar : findViewById(R.id.browser_engine_avatar);
        }
        android.widget.TextView tv = (android.widget.TextView) engineAvatar;
        String engine = engineLabel();
        tv.setText(engine.substring(0, 1));
        tv.setTextSize(16);
        tv.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(engineColor());
        bg.setShape(GradientDrawable.OVAL);
        tv.setBackground(bg);
    }

    private int engineColor() {
        switch (prefs.searchEngine()) {
            case SearchEngines.GOOGLE: return 0xFF4285F4;
            case SearchEngines.BING: return 0xFF008373;
            case SearchEngines.DUCKDUCKGO: return 0xFFDE5833;
            case SearchEngines.YAHOO: return 0xFF6001D2;
            case SearchEngines.YOUTUBE: return 0xFFFF0000;
            default: return 0xFF2932E1;
        }
    }

    private void showEnginePicker() {
        String[] engines = {"YouTube", "Google", "Bing", "百度", "DuckDuckGo", "自定义",
                "Yahoo", "Startpage"};
        ViaUi.radioDialog(this, getString(R.string.via_search_engine), engines,
                prefs.searchEngine(), idx -> {
                    prefs.setSearchEngine(idx);
                    applyToolbarMode();
                }).show();
    }

    private void toggleSiteCard() {
        if (siteCard == null) return;
        if (siteCard.getVisibility() == View.VISIBLE) {
            siteCard.setVisibility(View.GONE);
            configureCustomTabTopAction();
            return;
        }
        TabManager.Tab cur = tabs.current();
        String url = cur == null ? "" : (cur.url == null ? "" : cur.url);
        String title = cur == null || cur.title == null || cur.title.trim().isEmpty()
                ? url : cur.title;
        siteCardTitle.setText(title);
        ImageView siteInfoIcon = findViewById(R.id.browser_site_info);
        if (siteInfoIcon != null) {
            siteInfoIcon.setImageResource(url.startsWith("https://")
                    ? R.drawable.ic_via_shield : R.drawable.ic_via_info);
        }
        if (TextUtils.isEmpty(url)) {
            siteCardUrl.setText(getString(R.string.browser_empty_hint));
        } else if (url.startsWith("https://")) {
            android.text.SpannableString span = new android.text.SpannableString(url);
            span.setSpan(new android.text.style.ForegroundColorSpan(0xFF4C8D2E),
                    0, 5, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            siteCardUrl.setText(span);
        } else {
            siteCardUrl.setText(url);
        }
        siteCard.setBackgroundResource(R.drawable.bg_via_sitecard);
        siteCard.getBackground().mutate().setTint(prefs.nightMode() ? 0xff1c1c1e : Color.WHITE);
        siteCardTitle.setTextColor(prefs.nightMode() ? 0xffcccccc : getResources().getColor(R.color.via_text));
        siteCardUrl.setTextColor(prefs.nightMode() ? 0xff999999 : getResources().getColor(R.color.via_text_sub));
        for (int iconId : new int[]{R.id.browser_site_card_history, R.id.browser_site_card_qr}) {
            ImageView icon = findViewById(iconId);
            if (prefs.nightMode()) icon.setColorFilter(0xffbbbbbb); else icon.clearColorFilter();
        }
        renderReaderControls(cur);
        updateReaderIcon();
        siteCard.setVisibility(View.VISIBLE);
        configureCustomTabTopAction();
    }

    private void hideSiteCard() {
        if (siteCard == null) return;
        siteCard.setVisibility(View.GONE);
        configureCustomTabTopAction();
    }

    private void showFindBar() {
        TabManager.Tab cur = tabs.current();
        if (cur == null) {
            GlassToast.makeText(this, R.string.browser_empty_hint, GlassToast.LENGTH_SHORT).show();
            return;
        }
        findBar.setVisibility(View.VISIBLE);
        cur.webView.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
            findCount = numberOfMatches;
            findIndex = activeMatchOrdinal;
        });
        findInput.requestFocus();
        findInput.post(() -> {
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(
                    findInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void hideFindBar() {
        findBar.setVisibility(View.GONE);
        TabManager.Tab cur = tabs.current();
        if (cur != null) cur.webView.clearMatches();
    }

    private void runFindInPage() {
        TabManager.Tab cur = tabs.current();
        if (cur == null) return;
        String q = findInput.getText().toString();
        if (!TextUtils.isEmpty(q)) cur.webView.findAllAsync(q);
    }

    private void findNextInPage(boolean forward) {
        TabManager.Tab cur = tabs.current();
        if (cur == null || findCount <= 0) return;
        cur.webView.findNext(forward);
    }

    private static void detach(View view) {
        android.view.ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) ((ViewGroup) parent).removeView(view);
    }

    private void addToolbarView(
            LinearLayout parent,
            View view,
            int width,
            int height,
            float weight) {
        if (view == null) return;
        detach(view);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height, weight);
        if (view instanceof ImageButton) {
            view.setMinimumWidth(0);
            view.setMinimumHeight(0);
        }
        if (view == tabsButtonContainer) {
            View tabs = tabsButtonContainer.findViewById(R.id.browser_tabs);
            if (tabs != null) {
                tabs.setVisibility(View.GONE);
            }
            if (tabBadge != null) {
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(28), dp(28));
                badgeParams.gravity = Gravity.CENTER;
                tabBadge.setLayoutParams(badgeParams);
            }
        }
        parent.addView(view, params);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setSearchEngineRowVisible(boolean visible) {
        if (!visible) {
            if (searchToolbarContainer != null) searchToolbarContainer.setVisibility(View.GONE);
            if (bottomSearchEngineRow != null) bottomSearchEngineRow.setVisibility(View.GONE);
        } else {
            TabManager.Tab cur = tabs.current();
            updateSearchToolbar(cur != null ? cur.url : null);
        }
    }

    private void updateSearchToolbar(String url) {
        if (searchToolbarContainer == null) return;
        boolean atHome = isHomeVisible();
        if (atHome || prefs == null || !prefs.searchToolbarEnabled() || addressBarEditing) {
            searchToolbarContainer.setVisibility(View.GONE);
            return;
        }

        SearchEngines.SearchResult res = SearchEngines.parseSearchResult(url);
        if (res != null) {
            showSearchEngineSwitcher = true;
            lastSearchQuery = res.query;
            activeSearchEngine = res.engine;
        } else if (showSearchEngineSwitcher && !TextUtils.isEmpty(lastSearchQuery)) {
            if (activeSearchEngine < 0) {
                activeSearchEngine = prefs.searchEngine();
            }
        } else {
            showSearchEngineSwitcher = false;
            searchToolbarContainer.setVisibility(View.GONE);
            return;
        }

        searchToolbarContainer.setVisibility(View.VISIBLE);
        rebuildSearchEngineRow();
    }

    private void maybeAddBottomSearchEngineRow(LinearLayout root) {
        // 保留方法名以兼容旧调用
    }

    private void rebuildSearchEngineRow() {
        updateSearchHints();
        if (searchToolbarChips == null || prefs == null) return;
        searchToolbarChips.removeAllViews();
        List<Integer> engines = prefs.searchToolbarEngines();
        if (engines == null || engines.isEmpty()) {
            engines = java.util.Arrays.asList(
                    SearchEngines.GOOGLE, SearchEngines.BAIDU, SearchEngines.BING,
                    SearchEngines.YAHOO, SearchEngines.STARTPAGE, SearchEngines.DUCKDUCKGO);
        }

        boolean night = prefs.nightMode();
        View divider = findViewById(R.id.browser_search_toolbar_divider);
        if (divider != null) divider.setBackgroundColor(night ? 0xFF2B2B2B : 0xFFE0E0E0);
        if (searchToolbarContainer != null) searchToolbarContainer.setBackgroundColor(night ? 0xFF1E1E1E : Color.WHITE);

        int accentColor = androidx.core.content.ContextCompat.getColor(this, R.color.via_accent);

        for (int engine : engines) {
            TextView chip = addSearchToolbarChip(SearchEngines.label(engine),
                    engine == activeSearchEngine, night, accentColor);

            final int targetEngine = engine;
            chip.setOnClickListener(v -> {
                if (targetEngine == activeSearchEngine) return;
                activeSearchEngine = targetEngine;
                prefs.setSearchEngine(targetEngine);
                updateSearchHints();
                rebuildSearchEngineRow();
                if (!TextUtils.isEmpty(lastSearchQuery)) {
                    String searchUrl = SearchEngines.buildSearchUrl(
                            targetEngine, lastSearchQuery, prefs.searchPrefix());
                    showSearchEngineSwitcher = true;
                    loadUrlInCurrentKeepingSearchSwitcher(searchUrl);
                }
            });
        }
        TextView settings = addSearchToolbarChip(getString(R.string.via_menu_settings),
                false, night, accentColor);
        settings.setOnClickListener(v -> startActivityForResult(
                new Intent(this, BrowserSearchSettingsActivity.class).putExtra("page", "toolbar"), REQ_SETTINGS));
    }

    private TextView addSearchToolbarChip(String title, boolean selected, boolean night, int accentColor) {
        TextView chip = new TextView(this);
        chip.setText(title);
        chip.setTextSize(13);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setTextColor(selected ? accentColor : (night ? 0xFFCCCCCC : 0xFF3C4043));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(night ? 0xFF2B2B2B : Color.WHITE);
        bg.setStroke(dp(1), selected ? accentColor : (night ? 0xFF444444 : 0xFFDADCE0));
        chip.setBackground(bg);
        chip.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(28));
        lp.setMargins(0, 0, dp(6), 0);
        searchToolbarChips.addView(chip, lp);
        return chip;
    }

    private String searchEngineLabel(int engine) {
        return SearchEngines.label(engine);
    }

    private void updateSearchHints() {
        String hint = getString(R.string.via_search_hint_simple);
        if (urlInput != null) urlInput.setHint(hint);
        if (homeSearch != null) homeSearch.setHint(hint);
    }

    /** VIA 标签底部面板（基准 81/82：白底圆角、行=地球图标+标题(选中蓝)+×、下方居中+）。 */
    private void openTabs() {
        if (tabSheet != null && tabSheet.isShowing()) return;
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setCancelable(true);
        dialog.setOnCancelListener(d -> tabSheet = null);

        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x33000000);
        scrim.setOnClickListener(v -> dialog.dismiss());
        root.addView(scrim, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadii(new float[]{dp(16), dp(16), dp(16), dp(16), 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setPadding(0, dp(6), 0, dp(4));
        android.widget.FrameLayout.LayoutParams sp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.BOTTOM;
        root.addView(sheet, sp);

        LinearLayout listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(listBox, new LinearLayout.LayoutParams(-1, -2));
        final android.app.Dialog dlg = dialog;
        rebuildTabSheet(listBox, dlg);

        TextView add = new TextView(this);
        add.setText("+");
        add.setTextSize(22);
        add.setTextColor(0xff333333);
        add.setGravity(Gravity.CENTER);
        add.setPadding(0, dp(6), 0, dp(10));
        add.setOnClickListener(v -> {
            dialog.dismiss();
            newTab("");
            showHome();
            updateTabBadge();
        });
        sheet.addView(add, new LinearLayout.LayoutParams(-1, dp(52)));

        dialog.setContentView(root, new ViewGroup.LayoutParams(-1, -2));
        android.view.Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        tabSheet = dialog;
        dialog.show();
    }

    /** 面板行区重建（× 关标签后原地刷新，面板保持打开）。 */
    private void rebuildTabSheet(LinearLayout listBox, android.app.Dialog dlg) {
        listBox.removeAllViews();
        int current = tabs.currentIndex();
        List<TabManager.Tab> all = tabs.all();
        for (int i = 0; i < all.size(); i++) {
            final int index = i;
            TabManager.Tab t = all.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(8), 0);
            row.setMinimumHeight(dp(64));
            row.setClickable(true);
            row.setBackgroundResource(R.drawable.bg_via_menu_cell);

            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_via_globe);
            icon.setColorFilter(0xff3c3c3c);
            row.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));

            TextView title = new TextView(this);
            String show = t.title != null && !t.title.trim().isEmpty()
                    ? t.title : t.url;
            if (show == null || show.trim().isEmpty()) show = getString(R.string.via_home_title);
            title.setText(show);
            title.setTextSize(15);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(14), 0, 0, 0);
            title.setTextColor(i == current ? 0xff5b7fd6 : getColor(R.color.text_primary));
            row.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

            ImageButton close = new ImageButton(this);
            close.setImageResource(R.drawable.ic_close);
            close.setColorFilter(0xff3c3c3c);
            close.setBackgroundResource(R.drawable.bg_via_toolbar_button);
            close.setPadding(dp(10), dp(10), dp(10), dp(10));
            close.setOnClickListener(v -> {
                TabManager.Tab closed = closeAndDestroyTab(index);
                if (closed == null) {
                    dlg.dismiss();
                    tabSheet = null;
                    newTab(prefs.homeUrl());
                    showHome();
                } else {
                    updateTabBadge();
                    if (index == current) showTab(closed);
                    rebuildTabSheet(listBox, dlg);
                }
            });
            row.addView(close, new LinearLayout.LayoutParams(dp(44), dp(44)));

            row.setOnClickListener(v -> {
                dlg.dismiss();
                tabSheet = null;
                tabs.select(index);
                showTab(tabs.current());
                updateTabBadge();
            });
            listBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }

    /** 新建标签并加载 URL。 */
    private TabManager.Tab newTab(String url) {
        return newTab(url, true);
    }

    /** 新建标签；switchTo=false 为后台打开（不切前台、不改地址栏）。 */
    private TabManager.Tab newTab(String url, boolean switchTo) {
        if (com.example.cleanrecovery.ui.browser.BrowserInternalUrls.isHome(url)) url = "";
        if (!TextUtils.isEmpty(url)) hasRestorableClosedTab = false;
        WebView webView = createWebView();
        // TabManager.newTab 会把 currentIndex 指向新标签；后台打开需还原本位
        int prevIndex = tabs.currentIndex();
        TabManager.Tab tab = tabs.newTab(webView);
        TabState state = new TabState(createSniffer(tab, webView));
        tab.tag = state;
        tab.url=url==null?"":url;
        applySettings(webView,tab.url);
        attachClients(tab, state);
        // WebView 创建后立即按当前代理状态路由
        try {
            ProxyRouter.applyProxy(webView);
        } catch (Exception e) {
            Log.w(TAG, "applyProxy on newTab failed: " + e.getMessage());
        }
        if (url != null && !url.isEmpty()) {
            webView.loadUrl(url);
        }
        if (switchTo) {
            showTab(tab);
            updateTabBadge();
            if (url != null && !url.isEmpty()) {
                urlInput.setText(url);
            } else {
                showHome();
            }
        } else {
            tabs.select(prevIndex);
            updateTabBadge();
        }
        return tab;
    }

    /** 创建并基础配置 WebView。 */
    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        BrowserWebView webView = new BrowserWebView(this);
        webView.setBeforeNavigation(url -> applySettings(webView, url));
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        webView.addJavascriptInterface(new AdMarkerBridge(), "ViaAdMarker");
        webView.addJavascriptInterface(new PasswordBridge(webView), "ViaPasswords");
        webView.setDownloadListener((url, userAgent, disposition, mime, length) ->
                handleWebDownload(webView, url, userAgent, disposition, mime, length));
        // 长按菜单对齐 Via：GestureDetector 只观察不消费触摸，WebView 自身的长按文本选择不受影响
        final android.view.GestureDetector longPressDetector = new android.view.GestureDetector(
                this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(android.view.MotionEvent e) {
                showWebLongPressMenu(webView);
            }
            @Override public boolean onFling(android.view.MotionEvent first, android.view.MotionEvent last, float vx, float vy) {
                if (first == null || !prefs.swipeNavigation() || addressBarEditing) return false;
                float dx = last.getX() - first.getX(), dy = last.getY() - first.getY();
                if (Math.abs(dx) > webView.getWidth() * .25f && Math.abs(dx) > Math.abs(dy) * 2
                        && !webView.canScrollHorizontally(dx > 0 ? -1 : 1)) {
                    if (dx > 0 && canNavigateHistory(webView, -1)) navigateHistory(webView, -1);
                    else if (dx < 0 && canNavigateHistory(webView, 1)) navigateHistory(webView, 1);
                }
                return false;
            }
        });
        final int[] scrollTravel = {0};
        webView.setOnScrollChangeListener((view, x, y, oldX, oldY) -> {
            int delta = y - oldY;
            if (Integer.signum(delta) != Integer.signum(scrollTravel[0])) scrollTravel[0] = 0;
            scrollTravel[0] += delta;
            if (prefs.toolbarAutoHide() > 0 && tabs.current() != null && tabs.current().webView == webView
                    && Math.abs(scrollTravel[0]) > dp(8)) {
                if (y > oldY || prefs.toolbarAutoHide() == 1) {
                    toolbarHidden = y > oldY;
                    applyToolbarMode();
                }
                scrollTravel[0] = 0;
            }
        });
        webView.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_UP) {
                TabState s = null;
                for (TabManager.Tab tab : tabs.all()) {
                    if (tab.webView == webView) {
                        s = getState(tab);
                        break;
                    }
                }
                if (s != null) {
                    s.lastTouchTimeMs = android.os.SystemClock.elapsedRealtime();
                    WebView.HitTestResult hr = webView.getHitTestResult();
                    if (hr != null) s.lastHitType = hr.getType();
                    if (action == android.view.MotionEvent.ACTION_UP && s.adMarkerArmed) {
                        webView.evaluateJavascript("window.__viaAdMarkerPickAt&&window.__viaAdMarkerPickAt("
                                + event.getX() + "," + event.getY() + ")", null);
                    }
                }
            }
            longPressDetector.onTouchEvent(event);
            return false;
        });
        return webView;
    }

    private TabManager.Tab tabForPage(WebView webView) {
        for (TabManager.Tab tab : tabs.all()) if (tab.webView == webView) return tab;
        return null;
    }

    private boolean canNavigateHistory(WebView webView, int direction) {
        TabManager.Tab tab = tabForPage(webView);
        return tab != null && (direction < 0 ? tab.canGoBack() : tab.canGoForward());
    }

    private void navigateHistory(WebView webView, int direction) {
        TabManager.Tab tab = tabForPage(webView);
        if (tab == null || !canNavigateHistory(webView, direction)) return;
        if (direction > 0) hasRestorableClosedTab = false;
        if (direction < 0 && webView.canGoBack()) webView.goBack();
        else if (direction > 0 && webView.canGoForward()) webView.goForward();
        else {
            pausePage(webView);
            if (tab.selectPage(direction)) {
                restoreRetainedPage(tab);
                applyDocumentSettings(tab.webView, tab.webView.getUrl());
                tab.webView.onResume();
                tab.webView.evaluateJavascript("document.querySelectorAll('[via-data-playing]').forEach(function(e){e.removeAttribute('via-data-playing');e.play()})", null);
                viaSniffer.activatePage(tab.id, System.identityHashCode(tab.webView));
                trimRetainedPages(tab);
                if (tabs.current() == tab) {
                    showTab(tab);
                    progressbar.setVisibility(View.GONE);
                }
            }
        }
    }

    private void pausePage(WebView webView) {
        TabManager.Tab tab = tabForPage(webView);
        if (tab != null) tab.pageFor(webView).lastActiveMs = android.os.SystemClock.elapsedRealtime();
        if (webView.getProgress() < 100) webView.stopLoading();
        webView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(e){if(!e.paused){e.pause();e.setAttribute('via-data-playing','true')}})", null);
        webView.onPause();
    }

    private void destroyPage(TabManager.Page page) {
        if (page.webView == null) return;
        for (TabManager.Tab tab : tabs.all()) viaSniffer.removePage(tab.id, System.identityHashCode(page.webView));
        webContainer.removeView(page.webView);
        page.webView.stopLoading();
        page.webView.setWebChromeClient(null);
        page.webView.setWebViewClient(null);
        page.webView.destroy();
    }

    /** Via releases distant/old renderers while retaining their navigable state. */
    private void trimRetainedPages(TabManager.Tab tab) {
        long now = android.os.SystemClock.elapsedRealtime();
        List<TabManager.Page> pages = tab.pages();
        int current = tab.pageIndex();
        for (int index = 0; index < pages.size(); index++) {
            TabManager.Page page = pages.get(index);
            if (index == current || page.webView == null) continue;
            if (index >= current - 3 && index <= current + 2 && now - page.lastActiveMs <= 300000) continue;
            page.savedState = new Bundle();
            page.webView.saveState(page.savedState);
            page.scrollX = page.webView.getScrollX();
            page.scrollY = page.webView.getScrollY();
            destroyPage(page);
            page.webView = null;
            page.tag = null;
        }
    }

    private void restoreRetainedPage(TabManager.Tab tab) {
        if (tab.webView != null) return;
        TabManager.Page page = tab.pages().get(tab.pageIndex());
        WebView view = createWebView();
        tab.restoreCurrentView(view);
        tab.tag = new TabState(createSniffer(tab, view));
        attachClients(tab, getState(tab));
        ProxyRouter.applyProxy(view);
        applySettings(view, page.url);
        android.webkit.WebBackForwardList restored = page.savedState == null ? null : view.restoreState(page.savedState);
        boolean fallback = restored == null || restored.getCurrentItem() == null
                || TextUtils.isEmpty(restored.getCurrentItem().getUrl());
        if (fallback && !TextUtils.isEmpty(page.url)) view.loadUrl(page.url);
        if (page.scrollX != 0 || page.scrollY != 0) view.postDelayed(() -> {
            if (tab.webView == view && view.getScrollY() <= 1000) view.scrollTo(page.scrollX, page.scrollY);
        }, fallback ? 500 : 100);
        page.savedState = null;
    }

    private boolean retainPageForNavigation(TabManager.Tab tab, String target, boolean nonGestureOrRedirect) {
        return com.example.cleanrecovery.ui.browser.BrowserPageNavigationPolicy.shouldRetainPage(
                tab.webView.getUrl(), target, nonGestureOrRedirect, prefs);
    }

    private void navigateToPage(TabManager.Tab tab, String target, boolean fromLink) {
        if (com.example.cleanrecovery.ui.browser.BrowserInternalUrls.isHome(target)) {
            tab.url = "";
            if (tabs.current() == tab) showHome();
            return;
        }
        WebView previous = tab.webView;
        if (target.regionMatches(true, 0, "javascript:", 0, 11)) { previous.loadUrl(target); return; }
        if (!fromLink && target.equals(previous.getUrl())) { previous.reload(); return; }
        if (!TextUtils.isEmpty(previous.getUrl()) && retainPageForNavigation(tab, target, false)) {
            String referer = fromLink ? previous.getUrl() : null;
            if (previous instanceof BrowserWebView) ((BrowserWebView) previous).truncateForwardHistory();
            pausePage(previous);
            WebView next = createWebView();
            for (TabManager.Page discarded : tab.appendPage(next)) destroyPage(discarded);
            TabState state = new TabState(createSniffer(tab, next));
            tab.tag = state;
            tab.url = target;
            attachClients(tab, state);
            ProxyRouter.applyProxy(next);
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            if (referer != null && !target.contains(".aliyundrive.net/")) headers.put("Referer", referer);
            next.loadUrl(target, headers);
            trimRetainedPages(tab);
            if (tabs.current() == tab) showTab(tab);
        } else {
            for (TabManager.Page discarded : tab.discardForwardPages()) destroyPage(discarded);
            tab.url = target;
            previous.loadUrl(target);
            if (previous instanceof BrowserWebView) ((BrowserWebView) previous).truncateForwardHistory();
        }
    }

    // Expand semantic disclosure content only; never synthesize clicks on page controls.
    private void expandCollapsedContent(WebView webView) {
        webView.evaluateJavascript(
                "(function(){try{document.querySelectorAll('details:not([open])').forEach(function(e){e.open=true});"
                        + "return true}catch(e){return false}})()", null);
    }

    private void handleWebDownload(WebView source, String url, String userAgent, String contentDisposition,
                                   String mimeType, long contentLength) {
        if (TextUtils.isEmpty(url)) return;
        if (offerUserScript(url)) return;
        String referer = source.getUrl();
        if (TextUtils.isEmpty(referer) || url.equals(referer)) {
            referer = source instanceof BrowserWebView ? ((BrowserWebView)source).getNavigationReferer() : null;
        }
        confirmFileDownload(url, userAgent, referer, contentDisposition, mimeType, contentLength, source.getTitle());
    }

    /**
     * 网页长按菜单。基准＝真 Via 定制长按菜单 14 项
     * （via-reverse-src readable-source-final BrowserFragment$o.onLongPress），显隐由
     * 设置→定制长按菜单 flags 控制，14 项均接入对应的浏览器运行时动作。
     *
     * <p>与 Via 一致不信任 WebView.getHitTestResult()（部分页面缓存为 UNKNOWN），
     * 长按一律 requestFocusNodeHref 异步取命中节点的 url(链接)/src(图片) 再组菜单。</p>
     */
    private void showWebLongPressMenu(WebView wv) {
        if (isHomeVisible()) return;
        java.util.Set<String> flags = prefs.longPressFlags();
        android.os.Message hrefMsg = android.os.Message.obtain(new Handler(m -> {
            android.os.Bundle b = m.getData();
            String url = b != null ? b.getString("url") : null;
            String src = b != null ? b.getString("src") : null;
            String title = b != null ? b.getString("title") : null;
            for (TabManager.Tab tab : tabs.all()) {
                if (tab.webView == wv && isResourcePage(tab, wv.getUrl()) && !TextUtils.isEmpty(url)) {
                    showResourceMenu(tab, url);
                    return true;
                }
            }
            if (TextUtils.isEmpty(url) && TextUtils.isEmpty(src)) return true;
            showLongPressItems(url, src, title, flags);
            return true;
        }));
        wv.requestFocusNodeHref(hrefMsg);
    }

    private void showLongPressItems(String linkUrl, String imageUrl, String linkText,
                                    java.util.Set<String> flags) {
        final java.util.List<String> labels = new ArrayList<>();
        final java.util.List<String> actions = new ArrayList<>();
        if (!TextUtils.isEmpty(linkUrl)) {
            if (flags.contains("bg_open")) { labels.add(getString(R.string.via_bk_open_bg)); actions.add("bg_open"); }
            if (flags.contains("new_tab")) { labels.add(getString(R.string.via_bk_open_newtab)); actions.add("new_tab"); }
            if (flags.contains("copy_link")) { labels.add(getString(R.string.via_bk_copy_link)); actions.add("copy_link"); }
            if (flags.contains("share")) { labels.add(getString(R.string.via_menu_share)); actions.add("share"); }
            if (flags.contains("copy_link_text")) { labels.add("复制链接文本"); actions.add("copy_link_text"); }
        }
        if (!TextUtils.isEmpty(imageUrl)) {
            if (flags.contains("view_image")) { labels.add(getString(R.string.via_lp_view_image)); actions.add("view_image"); }
            if (flags.contains("save_image")) { labels.add(getString(R.string.via_lp_save_image)); actions.add("save_image"); }
            if (flags.contains("download_image")) { labels.add(getString(R.string.via_lp_download_image)); actions.add("download_image"); }
            if (flags.contains("share_image")) { labels.add(getString(R.string.via_lp_share_image)); actions.add("share_image"); }
            if (flags.contains("search_image")) { labels.add(getString(R.string.via_lp_search_image)); actions.add("search_image"); }
            if (flags.contains("image_mode")) { labels.add("看图模式"); actions.add("image_mode"); }
            if (flags.contains("scan_qr")) { labels.add("扫描二维码"); actions.add("scan_qr"); }
        }
        if (flags.contains("page_info")) { labels.add("页面信息"); actions.add("page_info"); }
        if (flags.contains("mark_ad")) { labels.add("标记广告"); actions.add("mark_ad"); }
        if (actions.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[0]), (d, w) ->
                        runLongPressAction(actions.get(w), linkUrl, imageUrl, linkText))
                .show();
    }

    private void runLongPressAction(String act, String linkUrl, String imageUrl, String linkText) {
        switch (act) {
            case "bg_open":
                if (TextUtils.isEmpty(linkUrl)) return;
                newTab(linkUrl, false);
                GlassToast.makeText(this, R.string.via_opened_bg, GlassToast.LENGTH_SHORT).show();
                break;
            case "new_tab":
                if (TextUtils.isEmpty(linkUrl)) return;
                newTab(linkUrl, true);
                break;
            case "copy_link":
                if (TextUtils.isEmpty(linkUrl)) return;
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("link", linkUrl));
                GlassToast.makeText(this, R.string.via_copied, GlassToast.LENGTH_SHORT).show();
                break;
            case "copy_link_text":
                if (TextUtils.isEmpty(linkText)) linkText = linkUrl;
                ClipboardManager textClipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (textClipboard != null) textClipboard.setPrimaryClip(
                        ClipData.newPlainText("link text", linkText));
                GlassToast.makeText(this, R.string.via_copied, GlassToast.LENGTH_SHORT).show();
                break;
            case "share":
                if (TextUtils.isEmpty(linkUrl)) return;
                startActivity(android.content.Intent.createChooser(
                        new android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, linkUrl),
                        getString(R.string.via_menu_share)));
                break;
            case "view_image":
                if (TextUtils.isEmpty(imageUrl)) return;
                newTab(imageUrl, true);
                break;
            case "save_image":
                if (TextUtils.isEmpty(imageUrl)) return;
                saveImageToGallery(imageUrl);
                break;
            case "download_image":
                if (TextUtils.isEmpty(imageUrl)) return;
                TabManager.Tab cur = tabs.current();
                confirmFileDownload(imageUrl, globalDownloadUserAgent(), null, null, null, -1,
                        cur != null ? cur.title : null);
                break;
            case "share_image":
                if (TextUtils.isEmpty(imageUrl)) return;
                startActivity(android.content.Intent.createChooser(
                        new android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, imageUrl),
                        getString(R.string.via_lp_share_image)));
                break;
            case "search_image":
                if (TextUtils.isEmpty(imageUrl)) return;
                try {
                    newTab("https://lens.google.com/uploadbyurl?url="
                            + java.net.URLEncoder.encode(imageUrl, "UTF-8"), true);
                } catch (Exception e) {
                    newTab("https://lens.google.com/uploadbyurl?url=" + imageUrl, true);
                }
                break;
            case "image_mode": {
                TabManager.Tab current = tabs.current();
                if (current != null) openImageMode(current.webView);
                break;
            }
            case "page_info":
                showSiteInformationPanel();
                break;
            case "mark_ad":
                markCurrentHostAsAd();
                break;
            case "scan_qr":
                decodeQrImage(imageUrl);
                break;
            default:
                break;
        }
    }

    private void openImageMode(WebView webView) {
        webView.evaluateJavascript(
                "JSON.stringify(Array.from(document.images).map(function(i){return i.currentSrc||i.src}).filter(Boolean))",
                value -> {
                    String decoded = decodeJsString(value);
                    try {
                        org.json.JSONArray images = new org.json.JSONArray(decoded);
                        StringBuilder html = new StringBuilder("<!doctype html><meta name='viewport' content='width=device-width'><style>body{margin:0;background:#111}img{display:block;width:100%;height:auto;margin:0 0 8px}</style>");
                        for (int i = 0; i < images.length(); i++) {
                            html.append("<img src=\"").append(android.text.TextUtils.htmlEncode(images.getString(i))).append("\">");
                        }
                        String data = android.util.Base64.encodeToString(
                                html.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                android.util.Base64.NO_WRAP);
                        newTab("data:text/html;base64," + data, true);
                    } catch (Exception e) {
                        GlassToast.makeText(this, "未找到图片", GlassToast.LENGTH_SHORT).show();
                    }
                });
    }

    private void decodeQrImage(String imageUrl) {
        if (TextUtils.isEmpty(imageUrl)) return;
        executor.execute(() -> {
            try {
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(
                        new java.net.URL(imageUrl).openStream());
                if (bitmap == null) throw new java.io.IOException("invalid image");
                int width = bitmap.getWidth(), height = bitmap.getHeight();
                int[] pixels = new int[width * height];
                bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
                com.google.zxing.BinaryBitmap source = new com.google.zxing.BinaryBitmap(
                        new com.google.zxing.common.HybridBinarizer(
                                new com.google.zxing.RGBLuminanceSource(width, height, pixels)));
                String text = new com.google.zxing.MultiFormatReader().decode(source).getText();
                mainHandler.post(() -> {
                    urlInput.setText(text);
                    loadUrlFromInput();
                });
            } catch (Exception e) {
                mainHandler.post(() -> GlassToast.makeText(this, "未识别到二维码", GlassToast.LENGTH_SHORT).show());
            }
        });
    }

    /** 保存图片：系统 DownloadManager 落入公共 Pictures 目录。 */
    private void saveImageToGallery(String imageUrl) {
        try {
            String name = imageUrl.substring(imageUrl.lastIndexOf('/') + 1);
            int q = name.indexOf('?');
            if (q > 0) name = name.substring(0, q);
            if (name.isEmpty()) name = "image_" + System.currentTimeMillis() + ".jpg";
            android.app.DownloadManager.Request req = new android.app.DownloadManager.Request(
                    Uri.parse(imageUrl));
            req.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_PICTURES, name);
            req.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(req);
                GlassToast.makeText(this, R.string.via_sniffer_download_started, GlassToast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.w(TAG, "saveImage failed: " + e.getMessage());
            GlassToast.makeText(this, R.string.via_sniffer_download_started, GlassToast.LENGTH_SHORT).show();
        }
    }

    /** 应用设置（UA/JS/图片/夜间）。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void applySettings(WebView webView) {
        String url=webView.getUrl();
        if(TextUtils.isEmpty(url))for(TabManager.Tab tab:tabs.all())if(tab.webView==webView){url=tab.url;break;}
        applySettings(webView,url);
    }

    private void applyDocumentSettings(WebView webView, String url) {
        boolean incognito=prefs.effectiveIncognito(url);
        for(TabManager.Tab candidate:tabs.all())if(candidate.webView==webView) {
            TabState state=getState(candidate);
            if(state!=null) {
                if(state.resourceSourceId>=0 && isResourcePage(candidate,url))for(TabManager.Tab source:tabs.all())if(source.id==state.resourceSourceId) {
                    incognito=prefs.effectiveIncognito(source.url);break;
                }
                state.incognito=incognito;
            }
            break;
        }
        if (webView instanceof com.example.cleanrecovery.ui.browser.BrowserWebView) {
            com.example.cleanrecovery.ui.browser.BrowserWebView browserView =
                    (com.example.cleanrecovery.ui.browser.BrowserWebView) webView;
            browserView.applyPrivacySettings();
            browserView.applyUserScripts();
        }
    }

    private void applySettings(WebView webView, String url) {
        applySettings(webView, url, url);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applySettings(WebView webView, String documentUrl, String settingsUrl) {
        applyDocumentSettings(webView, documentUrl);
        WebSettings s = webView.getSettings();
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // 多窗口开启后 target=_blank / window.open 走 onCreateWindow → 应用内新标签（弹窗管控见 attachClients）
        s.setSupportMultipleWindows(true);
        String host = BrowserPrefs.siteKey(settingsUrl);
        boolean siteEnabled = prefs.siteSettingsEnabled(host);
        int desktopOverride = siteEnabled ? prefs.siteDesktopMode(host) : -1;
        int jsOverride = siteEnabled ? prefs.siteJsMode(host) : -1;
        int imagesOverride = siteEnabled ? prefs.siteImagesMode(host) : -1;
        boolean desktopMode = desktopOverride == 1 || (desktopOverride < 0 && prefs.desktopMode());
        boolean jsOn = jsOverride == 1 || (jsOverride < 0 && prefs.jsEnabled());
        boolean imagesOn = imagesOverride == 1 || (imagesOverride < 0 && prefs.imagesEnabled());
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setSaveFormData(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        }
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setUserAgentString(prefs.getEffectiveUserAgent(desktopMode, host,
                WebSettings.getDefaultUserAgent(this)));
        com.example.cleanrecovery.ui.browser.BrowserUserAgentMetadata.apply(s, s.getUserAgentString());
        s.setJavaScriptEnabled(jsOn);
        s.setLoadsImagesAutomatically(imagesOn);
        s.setBlockNetworkImage(!imagesOn);
        int textZoom = siteEnabled ? prefs.siteTextZoom(host, prefs.textZoom()) : prefs.textZoom();
        applyTextZoom(webView, textZoom);
        android.webkit.CookieManager.getInstance().setAcceptCookie(prefs.cookiesEnabled());
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, prefs.cookiesEnabled());
        if (Build.VERSION.SDK_INT >= 26) s.setSafeBrowsingEnabled(!prefs.disableSafeBrowsing());
        applyDarkMode(webView, prefs.nightMode() && prefs.forceDarkPages());
    }

    private String currentHostFor(WebView webView) {
        String url = webView == null ? null : webView.getUrl();
        if (TextUtils.isEmpty(url)) {
            TabManager.Tab cur = tabs == null ? null : tabs.current();
            if (cur != null && cur.webView == webView) url = cur.url;
        }
        return hostFromUrl(url);
    }

    private void applyTextZoom(WebView webView, int zoom) {
        webView.getSettings().setTextZoom(zoom);
    }

    private String currentHost() {
        return BrowserPrefs.siteKey(currentUrl());
    }

    private String currentUrl() {
        TabManager.Tab cur = tabs == null ? null : tabs.current();
        return cur == null ? "" : (cur.url == null ? "" : cur.url);
    }

    private static String hostFromUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            return BrowserPrefs.normalizeHost(Uri.parse(url).getHost());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String desktopUserAgent() {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    }

    private static String mobileUserAgent() {
        return "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";
    }

    /** Apply the supported WebView darkening API and Via page stylesheet. */
    @SuppressLint("SetJavaScriptEnabled")
    private void applyDarkMode(WebView webView, boolean on) {
        com.example.cleanrecovery.ui.browser.BrowserNightMode.apply(webView, on);
    }

    /** 为标签创建嗅探器（回调更新该标签状态与 UI）。 */
    private MediaSniffer createSniffer(TabManager.Tab tab, WebView pageView) {
        return new MediaSniffer(new MediaSniffer.SnifferCallback() {
            @Override
            public void onMediaFound(MediaSniffer.MediaResource resource) {
                // 资源嗅探展示已改为整页日志（BrowserSnifferActivity）；媒体下载走自动嗅探入队
            }

            @Override
            public void onPageStarted(String url) {
                mainHandler.post(() -> {
                    if (tab.webView != pageView || !TextUtils.equals(tab.url, url)) return;
                    // 后台标签也在加载：tab.url 必须同步，否则切回时 showTab 会误判主页态
                    tab.url = url;
                    String startedHost = BrowserPrefs.siteKey(url);
                    if (prefs.siteSettingsEnabled(startedHost) && prefs.siteCookiesOff(startedHost)) clearCookiesForCurrentUrl();
                    if (tabs.current() == tab) {
                        hideHome();
                        applyToolbarMode();
                        progressbar.setVisibility(View.VISIBLE);
                        progressbar.setProgress(0);
                    }
                });
            }

            @Override
            public void onPageFinished(String url) {
                mainHandler.post(() -> {
                    if (tab.webView != pageView || !TextUtils.equals(tab.url, url)) return;
                    tab.url = url;
                    String title = tab.webView.getTitle();
                    if (title != null) tab.title = title;
                    sampleToolbarColor(tab, url);
                    if (!prefs.effectiveIncognito(url)) {
                        dbHelper.recordHistory(title, url);
                    }
                    applyDarkMode(tab.webView, prefs.nightMode() && prefs.forceDarkPages());
                    applyAdBlockCss(tab.webView, url);
                    if (tabs.current() == tab) {
                        urlInput.setText(url);
                        updateNavButtons(tab);
                        progressbar.setVisibility(View.GONE);
                        applyToolbarMode();
                    }
                });
            }
        });
    }

    private TabState getState(TabManager.Tab tab) {
        return (TabState) tab.tag;
    }

    /** 组合嗅探器并设置到 WebView。 */
    private void attachClients(TabManager.Tab tab, TabState state) {
                state.adBlocker = new BrowserAdBlocker(prefs);
                // 点击来源采集（document-start + WebMessage；守卫的证据源）
                state.clickCollector = new PageClickCollector(null);
                state.clickCollector.install(tab.webView);
        WebViewClient viaClient = new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (tab.webView != view) return;
                state.documentGeneration++;
                state.readerStatus = 0;
                if (tabs.current() == tab && tab.webView == view) updateReaderIcon();
                if (isResourcePage(tab, url)) {
                    resourcePageLoaded(tab, url);
                    return;
                }
                tab.url=url;
                applyDocumentSettings(view, url);
                state.sniffer.onPageStarted(view, url, favicon);
                viaSniffer.onPageStarted(tab.id, System.identityHashCode(view), url);
                // shouldInterceptRequest 运行在 WebView IO 线程，页 host 只能在此（UI 线程）缓存
                if (state.adBlocker != null) state.adBlocker.setPageUrl(url);
                // 新页面：重置拦截徽标，并注入元素隐藏虚拟 CSS（head 解析即生效，无闪烁）
                state.pageBlockedCount = 0;
                mainHandler.post(() -> {
                    if (tabs.current() == tab && tab.webView == view) {
                        updateAdBadge(0);
                        updateSnifferButton();
                    }
                });
                injectCosmeticLink(view, url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (tab.webView != view || !TextUtils.equals(tab.url, url)) return;
                if (isResourcePage(tab, url)) {
                    resourcePageLoaded(tab, url);
                    return;
                }
                state.sniffer.onPageFinished(view, url);
                injectPasswordManager(view, url, state.incognito);
                if (prefs.adBlockExpand()) expandCollapsedContent(view);
                detectReader(tab);
                injectUserScripts(view, url, "document-end");
                mainHandler.postDelayed(() -> {
                    if (url != null && url.equals(view.getUrl()))
                        injectUserScripts(view, url, "document-idle");
                }, 200);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                if (tab.webView != view) return;
                if (isResourcePage(tab, url)) return;
                viaSniffer.onPageCommitVisible(tab.id, System.identityHashCode(view));
                if (tabs.current() == tab && tab.webView == view) updateSnifferButton();
            }

            // ===== 防劫持：跨域主框架门禁 + scheme 路由（对齐 Via）=====
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request != null && isResourcePage(tab, request.getUrl().toString())) return false;
                boolean fromResourcePage = isResourcePage(tab, view.getUrl()) && request != null
                        && request.isForMainFrame() && ("https".equals(request.getUrl().getScheme())
                        || "http".equals(request.getUrl().getScheme()));
                boolean hitTestActive = false;
                if (view != null) {
                    WebView.HitTestResult hr = view.getHitTestResult();
                    if (hr != null && hr.getType() != 0) {
                        hitTestActive = true;
                        if (state != null) state.lastHitType = hr.getType();
                    }
                }
                long now = android.os.SystemClock.elapsedRealtime();
                boolean recentTouch = state != null && (now - state.lastTouchTimeMs) < 3500;
                boolean hasGesture = (request != null && request.hasGesture()) || hitTestActive || recentTouch;
                boolean isRedirect = request != null && request.isRedirect();
                boolean nonGesture = isRedirect && !hasGesture;
                boolean handled = !fromResourcePage && handleUrlNavigation(state, view,
                        request == null ? null : request.getUrl(), nonGesture, hasGesture, isRedirect);
                if (!handled && request != null && request.isForMainFrame() && request.hasGesture()
                        && offerUserScript(request.getUrl().toString())) return true;
                if (!handled && request != null && request.isForMainFrame()
                        && ("http".equals(request.getUrl().getScheme()) || "https".equals(request.getUrl().getScheme()))) {
                    if (tab.webView != view) return true;
                    String target = request.getUrl().toString();
                    if (retainPageForNavigation(tab, target, request.isRedirect() || !request.hasGesture())) {
                        navigateToPage(tab, target, true);
                        return true;
                    }
                    for (TabManager.Page discarded : tab.discardForwardPages()) destroyPage(discarded);
                    if (view instanceof BrowserWebView) ((BrowserWebView) view).allowNativeForwardHistory();
                    applySettings(view, target,
                            com.example.cleanrecovery.ui.browser.BrowserPageNavigationPolicy.settingsUrl(
                                    view.getUrl(), target, request.isRedirect() || !request.hasGesture(), prefs));
                }
                return handled;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (isResourcePage(tab, url)) return false;
                boolean webUrl = url != null && (url.startsWith("https://") || url.startsWith("http://"));
                boolean fromResourcePage = isResourcePage(tab, view.getUrl()) && webUrl;
                WebView.HitTestResult hit = view.getHitTestResult();
                boolean nonGestureOrRedirect = hit == null || hit.getType() == WebView.HitTestResult.UNKNOWN_TYPE;
                boolean handled = !fromResourcePage && handleUrlNavigation(state, view,
                        url == null ? null : Uri.parse(url), false, true, false);
                if (!handled && !nonGestureOrRedirect && offerUserScript(url)) return true;
                if (!handled && webUrl) {
                    if (tab.webView != view) return true;
                    if (retainPageForNavigation(tab, url, nonGestureOrRedirect)) {
                        navigateToPage(tab, url, true);
                        return true;
                    }
                    for (TabManager.Page discarded : tab.discardForwardPages()) destroyPage(discarded);
                    if (view instanceof BrowserWebView) ((BrowserWebView) view).allowNativeForwardHistory();
                    applySettings(view, url,
                            com.example.cleanrecovery.ui.browser.BrowserPageNavigationPolicy.settingsUrl(
                                    view.getUrl(), url, nonGestureOrRedirect, prefs));
                }
                return handled;
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                if (request != null && BrowserResourcePage.url(tab.id).equals(request.getUrl().toString())) {
                    String resourceHtml = BrowserResourcePage.render(viaSniffer.candidates(state.resourceSourceId),
                            getString(R.string.via_menu_sniff), getString(R.string.via_sniffer_page_note),
                            getString(R.string.via_page_resource_none), prefs.nightMode(),
                            getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL);
                    return new android.webkit.WebResourceResponse("text/html", "UTF-8",
                            new java.io.ByteArrayInputStream(resourceHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
                android.webkit.WebResourceResponse response = null;
                android.webkit.WebResourceResponse visible = state.sniffer.shouldInterceptRequest(view, request);
                if (visible != null) response = visible;
                android.webkit.WebResourceResponse adResponse = state.adBlocker.shouldInterceptRequest(view, request);
                if (adResponse != null) {
                    response = adResponse;
                    // 更新本页拦截徽标（主框架拦截不计入页内徽标也不打断体验）
                    if (!request.isForMainFrame()) {
                        state.pageBlockedCount = state.adBlocker.sessionBlockedCount();
                        if (tabs.current() == tab && tab.webView == view) {
                            mainHandler.post(() -> {
                                if (tabs.current() == tab && tab.webView == view) updateAdBadge(state.pageBlockedCount);
                            });
                        }
                    }
                }

                if (request != null && request.getUrl() != null) {
                    synchronized (state.networkLog) {
                        if (state.networkLog.size() >= 120) state.networkLog.remove(0);
                        state.networkLog.add((response != null ? "BLOCK " : "ALLOW ")
                                + request.getMethod() + " " + request.getUrl());
                    }
                    ViaSnifferStateMachine.CaptureResult capture = viaSniffer.onRequest(
                            tab.id,
                            System.identityHashCode(view),
                            request.getUrl().toString(),
                            response != null,
                            request.getRequestHeaders());
                    if (capture.showSnifferButton()) mainHandler.post(() -> {
                        if (tabs.current() == tab && tab.webView == view) updateSnifferButton();
                    });
                }
                return response;
            }

            @Override
            public void onReceivedSslError(
                    WebView view,
                    android.webkit.SslErrorHandler handler,
                    android.net.http.SslError error) {
                if (prefs.ignoreSslWarnings() && handler != null) {
                    handler.proceed();
                } else if (handler != null) {
                    handler.cancel();
                }
            }
        };
        tab.webView.setWebViewClient(viaClient);
        tab.webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onReceivedIcon(WebView view, android.graphics.Bitmap icon) {
                TabState iconState = getState(tab);
                if (iconState != null && !iconState.incognito) {
                    com.example.cleanrecovery.ui.browser.BrowserFavicons.put(view.getUrl(), icon);
                }
            }
            @Override public void onReceivedTitle(WebView view, String title) {
                if (tab.webView == view && !TextUtils.isEmpty(view.getUrl())) hasRestorableClosedTab = false;
            }

            @Override public boolean onJsConfirm(WebView view, String url, String message, android.webkit.JsResult result) {
                return com.example.cleanrecovery.ui.browser.BrowserJsDialogs.show(BrowserActivity.this, view, url, message, true, result);
            }

            @Override public boolean onJsAlert(WebView view, String url, String message, android.webkit.JsResult result) {
                return com.example.cleanrecovery.ui.browser.BrowserJsDialogs.show(BrowserActivity.this, view, url, message, false, result);
            }

            @Override public boolean onJsPrompt(WebView view, String url, String message, String initial, android.webkit.JsPromptResult result) {
                return com.example.cleanrecovery.ui.browser.BrowserJsDialogs.prompt(BrowserActivity.this, view, url, message, initial, result);
            }

            @Override public boolean onJsBeforeUnload(WebView view, String url, String message, android.webkit.JsResult result) {
                return com.example.cleanrecovery.ui.browser.BrowserJsDialogs.beforeUnload(BrowserActivity.this, view, message, result);
            }

            @Override public void onPermissionRequest(android.webkit.PermissionRequest request) {
                permissions.request(request);
            }

            @Override public void onPermissionRequestCanceled(android.webkit.PermissionRequest request) {
                permissions.cancel(request);
            }

            @Override public void onGeolocationPermissionsShowPrompt(String origin,
                    android.webkit.GeolocationPermissions.Callback callback) {
                permissions.requestLocation(origin, callback);
            }

            @Override public void onGeolocationPermissionsHidePrompt() {
                permissions.cancelLocation();
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mainHandler.post(() -> {
                    if (tabs.current() != tab || tab.webView != view) return;
                    configureCustomTabTopAction();
                    if (newProgress < 100) {
                        progressbar.setVisibility(View.VISIBLE);
                        progressbar.setProgress(newProgress);
                    } else {
                        progressbar.setVisibility(View.GONE);
                    }
                });
            }

            // ===== 弹窗管控：target=_blank / window.open → 应用内新标签承接 =====
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                if (resultMsg == null || resultMsg.obj == null) return false;
                if (!isUserGesture && !prefs.popupsEnabled()) {
                    Snackbar.make(webContainer, R.string.via_popup_blocked, Snackbar.LENGTH_LONG)
                            .setAction(R.string.via_allow_once, v -> openPopupTab(resultMsg))
                            .show();
                    return true;
                }
                openPopupTab(resultMsg);
                return true;
            }

            // ===== HTML5 全屏视频（影视站网页播放器全屏）=====
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                enterFullscreenVideo(view, callback);
            }

            @Override
            public void onShowCustomView(View view, int requestedOrientation,
                                         CustomViewCallback callback) {
                enterFullscreenVideo(view, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreenVideo();
            }

            @Override
            public Bitmap getDefaultVideoPoster() {
                // 空海报避免灰图标占位
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });
    }

    /** 进入网页视频全屏：decor 叠加全屏层 + 横屏锁定 + 沉浸式（对齐 Via）。 */
    private void enterFullscreenVideo(View view, WebChromeClient.CustomViewCallback callback) {
        if (fullscreenVideoView != null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        com.example.cleanrecovery.ui.browser.BrowserVideoGestureLayer layer =
                new com.example.cleanrecovery.ui.browser.BrowserVideoGestureLayer(this, view, prefs.videoGestures(),
                        new com.example.cleanrecovery.ui.browser.BrowserVideoGestureLayer.Callbacks() {
                            @Override public void seek(int seconds) {
                                TabManager.Tab tab = tabs.current();
                                if (tab != null) tab.webView.evaluateJavascript(
                                        "(function(){function first(d){try{return d.querySelector('video')||Array.from(d.querySelectorAll('iframe')).map(function(f){return first(f.contentDocument)}).find(Boolean)}catch(e){return null}}var v=first(document);if(v)v.currentTime=Math.max(0,Math.min(v.duration,v.currentTime+" + seconds + "))})()", null);
                            }
                            @Override public int changeBrightness(float delta) {
                                WindowManager.LayoutParams params = getWindow().getAttributes();
                                if (fullscreenBrightness < 0) fullscreenBrightness = params.screenBrightness >= 0
                                        ? params.screenBrightness : android.provider.Settings.System.getInt(getContentResolver(),
                                        android.provider.Settings.System.SCREEN_BRIGHTNESS, 128) / 255f;
                                fullscreenBrightness = Math.max(.01f, Math.min(1f, fullscreenBrightness + delta));
                                params.screenBrightness = fullscreenBrightness;
                                getWindow().setAttributes(params);
                                return Math.round(fullscreenBrightness * 100);
                            }
                            @Override public int changeVolume(float delta) {
                                android.media.AudioManager audio = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
                                int max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                                int next = Math.max(0, Math.min(max, audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
                                        + Math.round(delta * max)));
                                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, next, 0);
                                return Math.round(next * 100f / max);
                            }
                        });
        fullscreenVideoView = layer;
        fullscreenCallback = callback;
        savedOrientation = getRequestedOrientation();
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        view.setKeepScreenOn(true);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (decor instanceof FrameLayout) {
            ((FrameLayout) decor).addView(layer, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }
    }

    private void exitFullscreenVideo() {
        if (fullscreenVideoView == null) return;
        ViewGroup decor = (ViewGroup) getWindow().getDecorView();
        if (fullscreenVideoView.getParent() == decor) {
            decor.removeView(fullscreenVideoView);
        }
        fullscreenVideoView.setKeepScreenOn(false);
        if (fullscreenVideoView instanceof ViewGroup && ((ViewGroup) fullscreenVideoView).getChildCount() > 0) {
            ((ViewGroup) fullscreenVideoView).getChildAt(0).setKeepScreenOn(false);
        }
        fullscreenVideoView = null;
        if (fullscreenBrightness >= 0) {
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = -1f;
            getWindow().setAttributes(params);
            fullscreenBrightness = -1f;
        }
        setRequestedOrientation(savedOrientation);
        SystemUiHelper.apply(this);
        if (fullscreenCallback != null) {
            fullscreenCallback.onCustomViewHidden();
            fullscreenCallback = null;
        }
    }

    // ===== 导航决策（防劫持 / scheme 路由 / 弹窗）=====

    /**
     * 导航决策：viacontinue 回调放行并记录 bypass；
     * http(s) 默认丝滑放行（对齐 Via：页面重定向默认允许）；
     * 仅在用户显式开启「重定向询问」或站点策略开启时，对跨站无手势重定向进行询问；
     * 其它 scheme 交由外部路由（部分常见劫持 scheme 静默阻断）。
     */
    private boolean handleUrlNavigation(TabState state, WebView view, Uri uri,
                                        boolean nonGesture, boolean hasGesture, boolean isRedirect) {
        if (uri == null) return false;
        String url = uri.toString();
        if (com.example.cleanrecovery.ui.browser.BrowserInternalUrls.isHome(url)) {
            if (tabs.current() != null && tabs.current().webView == view) showHome();
            return true;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("viacontinue".equals(scheme)) {
            String target = Uri.decode(url.substring("viacontinue://".length()));
            Log.i("ViaGuard", "viacontinue: " + target + " host=" + hostFromUrl(target));
            if (!TextUtils.isEmpty(target)) {
                if (state != null && state.adBlocker != null) {
                    state.adBlocker.allowHostOnce(hostFromUrl(target));
                }
                view.loadUrl(target);
            }
            return true;
        }
        if ("http".equals(scheme) || "https".equals(scheme)) {
            String cur = view == null ? null : view.getUrl();
            if (!TextUtils.isEmpty(cur) && cur.startsWith("http")) {
                String curHost = hostFromUrl(cur);
                String targetHost = hostFromUrl(url);
                String site = BrowserPrefs.siteKey(cur);

                // 1. 同站或同根域名放行（对齐 Via 判定）
                if (AdFilterEngine.sameSite(curHost, targetHost)) return false;
                if (nonGesture && ("block".equals(prefs.permission("redirect", site))
                        || (prefs.siteSettingsEnabled(site) && prefs.siteRedirectMode(site) == 2))) return true;

                // 2. 会话放行名单（用户点过「继续访问」或「允许一次」）放行
                if (state != null && state.adBlocker != null && state.adBlocker.isBypassListed(targetHost)) {
                    return false;
                }

                // 3. 用户最近有明确手势/点击（比如点了搜索结果），绝非恶意暗刷劫持，放行
                if (hasGesture && !isRedirect) {
                    return false;
                }

                // 4. Phase 2：NavigationGuard 统一决策（透明覆盖层拦截与恶意劫持防护）
                if (state != null && state.adBlocker != null
                        && state.adBlocker.navigationGuardActive()) {
                    NavigationGuard.ClickSignal sig = state.clickCollector == null
                            ? null : state.clickCollector.freshSignal();
                    int decision = NavigationGuard.decide(new NavigationGuard.Context(
                            curHost, url, targetHost, hasGesture, isRedirect, sig,
                            state.adBlocker.isBypassListed(targetHost),
                            shouldAskRedirect(site), PageClickCollector.SIGNAL_MAX_AGE_MS));
                    if (decision == NavigationGuard.BLOCK_HIJACK) {
                        Log.i("ViaGuard", "block hijack nav: " + curHost + " -> " + targetHost
                                + " gesture=" + hasGesture);
                        showHijackBlock(state, view, url);
                        return true;
                    }
                    if (decision == NavigationGuard.ASK_REDIRECT) {
                        showRedirectConfirm(state, view, url);
                        return true;
                    }
                    return false;
                }

                // 5. 仅当用户主动在设置中开启「页面重定向询问」且当前为非手势跨站重定向时才提示询问
                if (nonGesture && shouldAskRedirect(site)) {
                    showRedirectConfirm(state, view, url);
                    return true;
                }
            }
            return false;
        }
        return routeExternalScheme(url, scheme, view);
    }

    /** 透明覆盖层/播放器遮挡跳转拦截提示（允许一次 = 会话内放行该 host）。 */
    private void showHijackBlock(TabState state, WebView view, String url) {
        if (isFinishing() || isDestroyed()) return;
        Snackbar.make(webContainer, getString(R.string.via_hijack_blocked, hostFromUrl(url)),
                        Snackbar.LENGTH_LONG)
                .setAction(R.string.via_allow_once, v -> {
                    if (state != null && state.adBlocker != null) {
                        state.adBlocker.allowHostOnce(hostFromUrl(url));
                    }
                    view.loadUrl(url);
                })
                .show();
    }

    /** 页面重定向询问开关（按站覆盖 + 全局，对齐 Via 默认允许/不询问）。 */
    private boolean shouldAskRedirect(String pageHost) {
        if (!TextUtils.isEmpty(pageHost) && prefs.siteSettingsEnabled(pageHost)) {
            int mode = prefs.siteRedirectMode(pageHost);
            if (mode == 0) return false; // 允许
            if (mode == 1) return true;  // 询问
        }
        String perm = prefs.permission("redirect", pageHost);
        if ("ask".equals(perm)) return true;
        if ("block".equals(perm)) return true;
        if ("allow".equals(perm)) return false;
        return prefs.redirectAskMode() == 1;
    }

    private void showRedirectConfirm(TabState state, WebView view, String url) {
        if (isFinishing() || isDestroyed()) return;
        Snackbar.make(webContainer, getString(R.string.via_redirect_ask, hostFromUrl(url)),
                        Snackbar.LENGTH_LONG)
                .setAction(R.string.via_allow_once, v -> {
                    if (state != null && state.adBlocker != null) {
                        state.adBlocker.allowHostOnce(hostFromUrl(url));
                    }
                    view.loadUrl(url);
                })
                .show();
    }

    /** 常见劫持 scheme：静默阻断（对齐 Via 对 baidubox 等的处理）。 */
    private static final java.util.Set<String> SILENT_BLOCK_SCHEMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "baiduboxapp", "baiduboxlite", "bdapp", "baiduhaokan", "baidubrowser",
            "qqbrowser", "mqqbrowser", "bdwidget", "snssdk1128", "snssdk1112"));

    private boolean routeExternalScheme(String url, String scheme, WebView source) {
        if (scheme.isEmpty()) return false;
        switch (scheme) {
            case "about": case "data": case "javascript": case "blob":
            case "file": case "content": case "view-source": case "ws": case "wss":
                return false; // WebView 自行处理
            default:
                if (SILENT_BLOCK_SCHEMES.contains(scheme)) return true;
                String mode = prefs.permission("open_apps", BrowserPrefs.siteKey(source == null ? null : source.getUrl()));
                if ("block".equals(mode)) return true;
                if ("allow".equals(mode)) {
                    startExternal(url, externalIntent(url));
                    return true;
                }
                showExternalConfirm(url);
                return true;
        }
    }

    private void showExternalConfirm(String url) {
        if (isFinishing() || isDestroyed()) return;
        Intent target = externalIntent(url);
        Snackbar.make(webContainer, R.string.via_open_external_confirm, Snackbar.LENGTH_LONG)
                .setAction(R.string.via_allow_once, v -> startExternal(url, target))
                .show();
    }

    private Intent externalIntent(String url) {
        Intent parsed = null;
        if (url.startsWith("intent://")) {
            try {
                parsed = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (parsed != null) {
                    parsed.setComponent(null);
                    parsed.setSelector(null);
                }
            } catch (Exception ignored) {
            }
        }
        return parsed != null ? parsed : new Intent(Intent.ACTION_VIEW, Uri.parse(url));
    }

    private void startExternal(String fallbackUrl, Intent parsed) {
        try {
            Intent it = parsed != null ? parsed : new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl));
            startActivity(it);
        } catch (Exception e) {
            GlassToast.makeText(this, R.string.via_no_app_open, GlassToast.LENGTH_SHORT).show();
        }
    }

    /** 弹窗（target=_blank / window.open）→ 新建应用内标签承接，后续导航过同一套拦截链。 */
    private void openPopupTab(Message resultMsg) {
        TabManager.Tab tab = newTab(null);
        if (tab == null) return;
        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(tab.webView);
        resultMsg.sendToTarget();
    }

    /** head 解析时注入 <link> 拉取虚拟 CSS（元素隐藏即时生效，无闪烁）。 */
    private void injectCosmeticLink(WebView view, String url) {
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        if (view == null || TextUtils.isEmpty(url) || !url.startsWith("http")) return;
        if (!prefs.adBlockEnabled()) return;
        if (TextUtils.isEmpty(hostFromUrl(url))) return;
        view.evaluateJavascript(BrowserAdBlocker.cosmeticScript(), null);
    }

    /** 盾牌徽标：显示当前页已拦截广告数。 */
    private void updateAdBadge(int count) {
        if (adBadge == null) return;
        adBadge.setVisibility(View.GONE);
    }

    /** 切换显示的标签 WebView。 */
    private void showTab(TabManager.Tab tab) {
        if (com.example.cleanrecovery.ui.browser.BrowserInternalUrls.isHome(tab.url)) tab.url = "";
        View displayed = webContainer.getChildCount() == 0 ? null : webContainer.getChildAt(0);
        if (displayed instanceof WebView && displayed != tab.webView) {
            TabManager.Tab previous = tabForPage((WebView) displayed);
            if (previous != null) {
                pausePage(previous.webView);
                trimRetainedPages(previous);
            }
        }
        restoreRetainedPage(tab);
        applyDocumentSettings(tab.webView, tab.webView.getUrl());
        tab.webView.onResume();
        viaSniffer.activatePage(tab.id, System.identityHashCode(tab.webView));
        webContainer.removeAllViews();
        webContainer.addView(tab.webView);
        // 重新挂载主页容器与夜间蒙层（removeAllViews 会移除它们）
        webContainer.addView(homeScroll);
        if (nightMask != null && nightMask.getParent() == null) {
            webContainer.addView(nightMask);
        }
        if (snifferButton == null) {
            snifferButton = new ImageButton(this);
            snifferButton.setImageResource(R.drawable.ic_sniffer);
            snifferButton.setContentDescription(getString(R.string.via_menu_sniff));
            snifferButton.setBackgroundResource(R.drawable.bg_via_toolbar_button);
            snifferButton.setPadding(dp(12), dp(12), dp(12), dp(12));
            snifferButton.setOnClickListener(v -> openResourcePage());
        }
        FrameLayout.LayoutParams sniffLayout = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END | Gravity.BOTTOM);
        sniffLayout.setMargins(dp(12), dp(12), dp(12), dp(12));
        webContainer.addView(snifferButton, sniffLayout);
        TabState st = getState(tab);
        urlInput.setText(tab.url != null ? tab.url : "");
        updateNavButtons(tab);
        boolean atHome = tab.url == null || tab.url.isEmpty();
        homeScroll.setVisibility(atHome ? View.VISIBLE : View.GONE);
        if (atHome) renderHomeGrid();
        applyHomeCustomization();
        // 切换标签：为已加载页面补元素隐藏 CSS 与拦截徽标
        if (!atHome && !isResourcePage(tab, tab.url) && st != null && st.adBlocker != null) {
            applyAdBlockCss(tab.webView, tab.url);
            updateAdBadge(st.pageBlockedCount > 0 ? st.adBlocker.sessionBlockedCount() : 0);
        } else {
            updateAdBadge(0);
        }
        // 顶栏标题/地址栏三态随标签切换刷新（pageBarTitle 只在此路径更新）
        applyToolbarMode();
        updateReaderIcon();
        updateSnifferButton();
    }

    private void updateTabBadge() {
        int n = tabs.size();
        tabBadge.setText(n > 99 ? "99+" : String.valueOf(n));
        String desc = n == 1 ? "1" : getString(R.string.via_tab_count_format, n);
        tabBadge.setContentDescription(desc);
        tabsButtonContainer.setContentDescription(desc);
        updateToolbarTabs();
    }

    private void updateToolbarTabs() {
        if (toolbarLayout == null || !prefs.tabBarEnabled()) return;
        List<String> titles = new ArrayList<>();
        List<Bitmap> icons = new ArrayList<>();
        for (TabManager.Tab tab : tabs.all()) titles.add(TextUtils.isEmpty(tab.title) ? "主页" : tab.title);
        for (TabManager.Tab tab : tabs.all()) icons.add(tab.webView.getFavicon());
        toolbarLayout.updateTabs(titles, icons, tabs.currentIndex(), index -> {
            tabs.select(index);
            showTab(tabs.current());
        }, index -> {
            closeAndDestroyTab(index);
            if (tabs.current() == null) newTab("");
            else showTab(tabs.current());
            updateTabBadge();
        }, () -> newTab(""));
    }

    private void updateNavButtons(TabManager.Tab tab) {
        boolean canBack = tab != null && tab.canGoBack();
        canBack |= isResourcePage(tab, tab == null ? null : tab.url) && tabs.size() > 1;
        boolean canFwd = tab != null && tab.canGoForward();
        // VIA 的后退/前进区域始终保持 5 等分完整点击区；无历史时只降低透明度，点击 no-op。
        navBackButton.setEnabled(true);
        navForwardButton.setEnabled(true);
        navBackButton.setAlpha(canBack ? 1.0f : 0.4f);
        navForwardButton.setAlpha(canFwd ? 1.0f : 0.4f);
    }

    /** 建议下拉宿主：本地六源组装 + 引擎联想数据源 + 点击分发。 */
    private AddressSuggestPopup.Host suggestHost() {
        return new AddressSuggestPopup.Host() {
            @Override
            public void buildLocalRows(String query, List<AddressSuggestPopup.Row> out) {
                buildSuggestionRows(query, out);
            }

            @Override
            public String engineEndpoint(String query) {
                return (prefs.searchSuggestions() & 16) != 0
                        ? AddressSuggestPopup.defaultEndpoint(prefs.searchEngine(), query) : null;
            }

            @Override
            public List<String> parseEngineResponse(String endpoint, String body) {
                return AddressSuggestPopup.defaultParse(body);
            }

            @Override
            public void onPick(AddressSuggestPopup.Row row) {
                onSuggestionPicked(row);
            }
            @Override public void onFill(AddressSuggestPopup.Row row) {
                String value = row.kind == AddressSuggestPopup.KIND_URL || row.kind == AddressSuggestPopup.KIND_HISTORY
                        || row.kind == AddressSuggestPopup.KIND_TAB ? row.subtitle : row.title;
                urlInput.setText(value); urlInput.setSelection(urlInput.length()); urlInput.requestFocus();
            }
        };
    }

    /**
     * 组装本地建议行（按 设置→搜索建议 位标志过滤：
     * 1&lt;&lt;0 收藏 1&lt;&lt;1 书签 1&lt;&lt;2 标签页 1&lt;&lt;3 历史 1&lt;&lt;4 搜索引擎 1&lt;&lt;5 搜索历史）。
     */
    private void buildSuggestionRows(String query, List<AddressSuggestPopup.Row> out) {
        int flags = prefs.searchSuggestions();
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            if ((flags & 32) != 0) {
                int n = 0;
                for (String recent : prefs.searchHistory()) {
                    if (n++ >= 6) break;
                    out.add(new AddressSuggestPopup.Row(AddressSuggestPopup.KIND_SEARCH,
                            recent, "搜索历史"));
                }
            }
            return;
        }
        String needle = q.toLowerCase(java.util.Locale.ROOT);
        // 收藏（主页快捷链接）
        if ((flags & 1) != 0) {
            int n = 0;
            for (BrowserDatabaseHelper.Entry e : dbHelper.listQuickLinks()) {
                if (n >= 3) break;
                if (e == null || !matchesSuggestion(e.title, e.url, needle)) continue;
                out.add(urlSuggestion(e.title, e.url));
                n++;
            }
        }
        // 书签
        if ((flags & 2) != 0) {
            int n = 0;
            for (BrowserDatabaseHelper.Entry e : dbHelper.listBookmarks()) {
                if (n >= 5) break;
                if (matchesSuggestion(e.title, e.url, needle)) {
                    out.add(urlSuggestion(e.title, e.url));
                    n++;
                }
            }
        }
        // 打开的标签页（跳过当前标签）
        if ((flags & 4) != 0) {
            TabManager.Tab cur = tabs.current();
            int n = 0;
            for (TabManager.Tab t : tabs.all()) {
                if (n >= 3) break;
                if (t == cur || t.url == null || t.url.isEmpty()) continue;
                if (matchesSuggestion(t.title, t.url, needle)) {
                    out.add(new AddressSuggestPopup.Row(
                            AddressSuggestPopup.KIND_TAB,
                            t.title == null || t.title.trim().isEmpty() ? t.url : t.title,
                            t.url));
                    n++;
                }
            }
        }
        // 历史（按 URL 去重，最近优先）
        if ((flags & 8) != 0) {
            java.util.HashSet<String> seen = new java.util.HashSet<>();
            int n = 0;
            for (BrowserDatabaseHelper.Entry e : dbHelper.listHistory()) {
                if (n >= 6) break;
                if (e.url == null || !seen.add(e.url)) continue;
                if (matchesSuggestion(e.title, e.url, needle)) {
                    out.add(new AddressSuggestPopup.Row(AddressSuggestPopup.KIND_HISTORY, TextUtils.isEmpty(e.title) ? e.url : e.title, e.url));
                    n++;
                }
            }
        }
        if ((flags & 32) != 0) {
            int n = 0;
            for (String recent : prefs.searchHistory()) {
                if (n >= 6) break;
                if (recent.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
                    out.add(new AddressSuggestPopup.Row(AddressSuggestPopup.KIND_SEARCH,
                            recent, "搜索历史"));
                    n++;
                }
            }
        }
    }

    private static boolean matchesSuggestion(String title, String url, String needle) {
        String t = title == null ? "" : title.toLowerCase(java.util.Locale.ROOT);
        String u = url == null ? "" : url.toLowerCase(java.util.Locale.ROOT);
        return t.contains(needle) || u.contains(needle);
    }

    private static AddressSuggestPopup.Row urlSuggestion(String title, String url) {
        boolean noTitle = title == null || title.trim().isEmpty();
        return new AddressSuggestPopup.Row(AddressSuggestPopup.KIND_URL,
                noTitle ? url : title, url);
    }

    /** 建议行点击分发。 */
    private void onSuggestionPicked(AddressSuggestPopup.Row row) {
        switch (row.kind) {
            case AddressSuggestPopup.KIND_CLIP:
                urlInput.setText(row.title);
                urlInput.setSelection(row.title.length());
                break;
            case AddressSuggestPopup.KIND_TAB:
                for (int i = 0; i < tabs.all().size(); i++) {
                    TabManager.Tab t = tabs.all().get(i);
                    if (row.subtitle != null && row.subtitle.equals(t.url)) {
                        tabs.select(i);
                        showTab(tabs.current());
                        updateTabBadge();
                        break;
                    }
                }
                break;
            case AddressSuggestPopup.KIND_HISTORY:
            case AddressSuggestPopup.KIND_URL:
                loadUrlInCurrent(row.subtitle);
                break;
            case AddressSuggestPopup.KIND_SEARCH:
            case AddressSuggestPopup.KIND_SUGGEST:
            default:
                prefs.rememberSearch(row.title);
                ViaAddressResolver.Resolution resolution = ViaAddressResolver.resolve(
                        row.title,
                        SearchEngines.prefix(prefs, prefs.searchEngine()),
                        false);
                if (resolution.getOutput() != null && !resolution.getOutput().isEmpty()) {
                    loadUrlInCurrent(resolution.getOutput());
                }
                break;
        }
    }

    /** 从地址栏加载 URL（智能识别）。 */
    private void loadUrlFromInput() {
        loadAddress(urlInput.getText().toString().trim());
    }

    private void loadAddress(String input) {
        if (input != null && !input.trim().isEmpty()
                && com.example.cleanrecovery.ui.browser.BrowserInternalUrls.isHome(input)) {
            showHome();
            return;
        }
        if (suggestPopup != null) suggestPopup.dismiss();
        ViaAddressResolver.Resolution resolution = ViaAddressResolver.resolve(
                input,
                SearchEngines.prefix(prefs, prefs.searchEngine()),
                false);
        if (resolution.getKind() == ViaAddressResolver.Kind.EMPTY
                || resolution.getOutput() == null
                || resolution.getOutput().isEmpty()) {
            GlassToast.makeText(this, R.string.browser_url_hint, GlassToast.LENGTH_SHORT).show();
            return;
        }
        endSearchInput();
        String url = resolution.getOutput();
        showSearchEngineSwitcher = resolution.getKind() == ViaAddressResolver.Kind.SEARCH;
        lastSearchQuery = showSearchEngineSwitcher ? resolution.getInput() : "";
        if (showSearchEngineSwitcher) {
            activeSearchEngine = prefs.searchEngine();
            prefs.rememberSearch(lastSearchQuery);
        }
        TabManager.Tab cur = tabs.current();
        if (cur == null) {
            newTab(url);
            applyToolbarMode();
        } else {
            addressBarEditing = false;
            cur.webView.stopLoading();
            urlInput.setText(url);
            cur.url = url;
            hideHome();
            applyToolbarMode();
            cur.webView.loadUrl(url);
        }
    }

    /** 添加媒体项到当前标签列表。 */
    private File getDownloadDir() {
        File externalRoot = Environment.getExternalStorageDirectory();
        File dir;
        if (externalRoot != null && "mounted".equals(Environment.getExternalStorageState())) {
            dir = new File(new File(externalRoot, "DataRecovery"), "Downloads");
        } else {
            File base = getExternalFilesDir(null);
            if (base == null) base = getFilesDir();
            dir = new File(base, "DataRecovery/Downloads");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "download dir mkdirs failed: " + dir.getAbsolutePath());
        }
        return dir;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("ai_export_topic", pendingAiExport);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_AI_EXPORT) {
            String topicId = pendingAiExport;
            pendingAiExport = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null && topicId != null) {
                android.net.Uri destination = data.getData();
                executor.execute(() -> {
                    try (java.io.OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                        if (output == null) throw new java.io.IOException("无法写入文件");
                        output.write(prefs.aiStore().exportText(topicId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        mainHandler.post(() -> GlassToast.makeText(this, "导出成功", GlassToast.LENGTH_SHORT).show());
                    } catch (Exception error) {
                        mainHandler.post(() -> GlassToast.makeText(this, "导出失败：" + error.getMessage(), GlassToast.LENGTH_LONG).show());
                    }
                });
            }
            return;
        }
        IntentResult scan = IntentIntegrator.parseActivityResult(
                requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() == null) {
                GlassToast.makeText(this, R.string.via_scan_cancelled,
                        GlassToast.LENGTH_SHORT).show();
            } else {
                loadAddress(scan.getContents().trim());
            }
            return;
        }
        if (requestCode == REQ_SCRIPT_SETTINGS) {
            applyReturnedSettings(data);
            if (!scriptConfiguration().equals(scriptsBeforeEditing)) reloadCurrentWithSettings();
            scriptsBeforeEditing = null;
            return;
        }
        if (requestCode == REQ_SETTINGS) {
            applyReturnedSettings(data);
            return;
        }
        if (requestCode == REQ_SITE_SETTINGS) {
            if (resultCode == RESULT_OK) {
                applyReturnedSettings(data);
                reloadCurrentWithSettings();
                if (data != null && data.getBooleanExtra(BrowserSiteSettingsActivity.EXTRA_OPEN_FONT, false)) {
                    String host = currentHost();
                    mainHandler.post(() -> showSiteFontSizeDialog(host));
                }
            }
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_BOOKMARKS || requestCode == REQ_HISTORY
                || requestCode == REQ_OFFLINE || requestCode == REQ_SNIFFER) {
            String openUrl = data.getStringExtra(BrowserSnifferActivity.EXTRA_OPEN_URL);
            if (openUrl != null && !openUrl.isEmpty()) {
                TabManager.Tab t = newTab(openUrl);
                showTab(t);
                updateTabBadge();
                return;
            }
            String libraryAction = data.getStringExtra(BrowserLibraryBaseActivity.EXTRA_LIBRARY_ACTION);
            if (BrowserLibraryBaseActivity.ACTION_OPEN_TABS.equals(libraryAction)) {
                openTabs();
                return;
            }
            if (BrowserLibraryBaseActivity.ACTION_FOLDER_BG.equals(libraryAction)
                    || BrowserLibraryBaseActivity.ACTION_FOLDER_NEWTAB.equals(libraryAction)) {
                String folder = data.getStringExtra("folder");
                boolean switchFirst = BrowserLibraryBaseActivity.ACTION_FOLDER_NEWTAB.equals(libraryAction);
                int opened = 0;
                for (BrowserDatabaseHelper.Entry e : dbHelper.listBookmarks()) {
                    if (!folder.equals(e.folder)) continue;
                    TabManager.Tab t = newTab(e.url);
                    if (switchFirst && opened == 0) showTab(t);
                    opened++;
                }
                if (opened > 0) {
                    updateTabBadge();
                    if (!switchFirst) {
                        GlassToast.makeText(this, R.string.via_opened_bg, GlassToast.LENGTH_SHORT).show();
                    }
                } else {
                    showHome();
                }
                return;
            }
            String url = data.getStringExtra("url");
            if (url != null && !url.isEmpty()) {
                if (com.example.cleanrecovery.ui.browser.BrowserInternalUrls.isHome(url)) {
                    showHome();
                    return;
                }
                boolean openBg = data.getBooleanExtra("open_bg", false);
                boolean openNewTab = data.getBooleanExtra("open_newtab", false);
                if (openBg || openNewTab) {
                    TabManager.Tab t = newTab(url);
                    if (openNewTab) showTab(t);
                    else {
                        updateTabBadge();
                        GlassToast.makeText(this, R.string.via_opened_bg, GlassToast.LENGTH_SHORT).show();
                    }
                } else {
                    TabManager.Tab cur = tabs.current();
                    if (cur == null) newTab(url);
                    else {
                        cur.url = url;
                        hideHome();
                        cur.webView.loadUrl(url);
                    }
                }
            }
        }
    }

    private void applyReturnedSettings(Intent data) {
            if (data != null
                    && data.getBooleanExtra(BrowserSettingsActivity.EXTRA_OPEN_CUSTOMIZER, false)) {
                BrowserBottomMenu.showCustomizer(this, prefs, buildMenuEntries());
            }
            for (TabManager.Tab tab : tabs.all()) {
                applySettings(tab.webView);
                if (getState(tab).readerStatus == 3) reader().run(tab.webView, "appearance", prefs, null);
            }
            WebView.setWebContentsDebuggingEnabled(prefs.webDebug());
            applyHomeCustomization();
            customTabMode = isCustomTabIntent(getIntent()) && !prefs.disableCustomTabs();
            configureCustomTabUi();
        applyToolbarMode();
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, String[] requested, int[] results) {
        super.onRequestPermissionsResult(requestCode, requested, results);
        if (requestCode == com.example.cleanrecovery.ui.browser.BrowserPermissionController.REQUEST_CODE) {
            permissions.onRuntimeResult();
        }
    }

    private void performBrowserAction(int action) {
        TabManager.Tab current = tabs.current();
        WebView page = current == null ? null : current.webView;
        switch (action) {
            case 0: return;
            case 1: if (page != null) page.reload(); break;
            case 2: if (page != null) page.pageUp(true); break;
            case 3: if (page != null) page.pageDown(true); break;
            case 4: focusAddressBar(); break;
            case 5: newTab(""); break;
            case 6: addCurrentBookmark(); break;
            case 7: startActivityForResult(new Intent(this, BookmarksActivity.class), REQ_BOOKMARKS); break;
            case 8: startActivityForResult(new Intent(this, HistoryActivity.class), REQ_HISTORY); break;
            case 9: closeCurrentTab(); break;
            case 10:
            case 11:
                if (tabs.size() > 0) {
                    tabs.select((tabs.currentIndex() + tabs.size() + (action == 10 ? -1 : 1)) % tabs.size());
                    showTab(tabs.current());
                }
                break;
            case 12: if (page != null && canNavigateHistory(page, -1)) navigateHistory(page, -1); break;
            case 13: if (page != null && canNavigateHistory(page, 1)) navigateHistory(page, 1); break;
            case 14: showFindInPageDialog(); break;
            case 15: translatePageInPlace(); break;
            case 16:
                for (int i = tabs.size() - 1; i >= 0; i--) closeAndDestroyTab(i);
                newTab("");
                break;
            case 17: if (page != null) page.pageUp(false); break;
            case 18: if (page != null) page.pageDown(false); break;
            case 19: saveOfflinePage(); break;
            case 20: showPageSource(); break;
            case 24: readPageAloud(); break;
            case 25: enterReaderMode(); break;
            case 26: startActivityForResult(new Intent(this, BrowserSettingsActivity.class), REQ_SETTINGS); break;
            case 27: if (current != null) newTab(current.url); break;
            case 28: openDownloadActivity(); break;
            case 29: openPageAi(); break;
        }
    }

    @Override public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (event.isCtrlPressed()) {
            int action = -1;
            switch (keyCode) {
                case android.view.KeyEvent.KEYCODE_T: action = 5; break;
                case android.view.KeyEvent.KEYCODE_TAB: action = event.isShiftPressed() ? 10 : 11; break;
                case android.view.KeyEvent.KEYCODE_W: action = 9; break;
                case android.view.KeyEvent.KEYCODE_B: if (event.isShiftPressed()) action = 7; break;
                case android.view.KeyEvent.KEYCODE_H: action = 8; break;
                case android.view.KeyEvent.KEYCODE_F: action = 14; break;
                case android.view.KeyEvent.KEYCODE_L: action = 4; break;
                case android.view.KeyEvent.KEYCODE_R: action = 1; break;
                case android.view.KeyEvent.KEYCODE_U: action = 20; break;
                case android.view.KeyEvent.KEYCODE_D: action = 6; break;
            }
            if (action >= 0) { if (event.getRepeatCount() == 0) performBrowserAction(action); return true; }
        }
        if (event.isAltPressed()) {
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT) { performBrowserAction(12); return true; }
            if (keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) { performBrowserAction(13); return true; }
            if (keyCode == android.view.KeyEvent.KEYCODE_F) { showMainMenu(); return true; }
        }
        if (keyCode == android.view.KeyEvent.KEYCODE_F5) { performBrowserAction(1); return true; }
        if (volumeScrollKey(keyCode)) { performBrowserAction(keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ? 17 : 18); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override public boolean onKeyShortcut(int keyCode, android.view.KeyEvent event) {
        return onKeyDown(keyCode, event);
    }

    private boolean volumeScrollKey(int keyCode) {
        return prefs.volumeKeyScroll() && fullscreenVideoView == null && !(getCurrentFocus() instanceof EditText)
                && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN);
    }

    @Override public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        return volumeScrollKey(keyCode) || super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (fullscreenVideoView != null) {
            exitFullscreenVideo();
            return;
        }
        if (fullscreen) {
            // 菜单「全屏」模式：底栏已隐藏无菜单入口，返回键是唯一退出路径
            toggleFullscreen();
            return;
        }
        if (siteCard != null && siteCard.getVisibility() == View.VISIBLE) {
            hideSiteCard();
            return;
        }
        if (findBar != null && findBar.getVisibility() == View.VISIBLE) {
            hideFindBar();
            return;
        }
        if (addressBarEditing || (homeSearch != null && homeSearch.hasFocus())) {
            endSearchInput();
            return;
        }
        TabManager.Tab cur = tabs.current();
        if (customTabMode) {
            if (cur != null && cur.canGoBack()) navigateHistory(cur.webView, -1);
            else finish();
            return;
        }
        boolean atHome = isHomeVisible();
        if (!atHome && isResourcePage(cur, cur == null ? null : cur.url)) {
            closeResourcePage(cur);
            return;
        }
        if (!atHome && cur != null && cur.canGoBack()) {
            navigateHistory(cur.webView, -1);
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastBackPressAt < 2000L) {
            super.onBackPressed();
        } else {
            lastBackPressAt = now;
            GlassToast.makeText(this, R.string.via_exit_toast, GlassToast.LENGTH_SHORT).show();
        }
    }

    /** 朗读浮动控制条：暂停/继续 + 停止。 */
    private void showReadAloudBar() {
        if (readAloudBar != null) {
            readAloudBar.show();
            return;
        }
        android.app.Dialog bar = new android.app.Dialog(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE2B2B2B);
        bg.setCornerRadius(dp(24));
        row.setBackground(bg);
        row.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView play = new TextView(this);
        play.setText("⏸");
        play.setTextSize(18);
        play.setTextColor(Color.WHITE);
        play.setPadding(dp(10), dp(6), dp(14), dp(6));
        row.addView(play);
        TextView stop = new TextView(this);
        stop.setText("⏹");
        stop.setTextSize(18);
        stop.setTextColor(Color.WHITE);
        stop.setPadding(dp(10), dp(6), dp(10), dp(6));
        row.addView(stop);
        play.setOnClickListener(v -> {
            if (textToSpeech == null) return;
            if (textToSpeech.isSpeaking()) {
                textToSpeech.stop();
                play.setText("▶");
            } else {
                play.setText("⏸");
            }
        });
        stop.setOnClickListener(v -> {
            if (textToSpeech != null) textToSpeech.stop();
            bar.dismiss();
        });
        bar.setContentView(row);
        android.view.Window w = bar.getWindow();
        if (w != null) {
            w.setBackgroundDrawableResource(android.R.color.transparent);
            w.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            WindowManager.LayoutParams params = w.getAttributes();
            params.y = dp(72);
            w.setAttributes(params);
        }
        bar.show();
        readAloudBar = bar;
    }

    private boolean restoreSession(String json) {
        if (json == null || json.isEmpty()) return false;
        try {
            org.json.JSONArray entries = new org.json.JSONArray(json);
            java.util.List<String[]> valid = new java.util.ArrayList<>();
            for (int i = 0; i < entries.length(); i++) {
                org.json.JSONObject entry = entries.getJSONObject(i);
                String url = entry.optString("u", "");
                if (!(url.isEmpty() || url.startsWith("https://") || url.startsWith("http://") || "about:blank".equals(url))) continue;
                valid.add(new String[]{url, entry.optString("t", "")});
            }
            if (valid.isEmpty()) return false;
            int firstIndex = tabs.size();
            int restored = 0;
            for (String[] entry : valid) {
                TabManager.Tab tab = newTab(entry[0], restored == 0);
                tab.title = entry[1];
                restored++;
            }
            if (restored > 0) {
                tabs.select(firstIndex + Math.max(0, Math.min(restored - 1, prefs.sessionSelectedTab())));
                showTab(tabs.current());
            }
            return restored > 0;
        } catch (org.json.JSONException e) {
            Log.w(TAG, "Cannot restore browser session", e);
            return false;
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (restorePromptPending) return;
        org.json.JSONArray entries = new org.json.JSONArray();
        int selected = 0;
        if (prefs.restoreTabs() != 0) {
            for (TabManager.Tab tab : tabs.all()) {
                TabState state = getState(tab);
                if (state != null && (state.incognito || state.resourceSourceId >= 0)) continue;
                if (tabs.current() == tab) selected = entries.length();
                org.json.JSONObject entry = new org.json.JSONObject();
                try {
                    entry.put("u", tab.url == null ? "" : tab.url);
                    entry.put("t", tab.title == null ? "" : tab.title);
                    entries.put(entry);
                } catch (org.json.JSONException e) {
                    Log.w(TAG, "Cannot save browser tab", e);
                }
            }
        }
        prefs.setSessionTabs(entries.length() == 0 ? "" : entries.toString());
        prefs.setSessionSelectedTab(selected);
    }

    @Override
    protected void onDestroy() {
        if (aiChatDialog != null) aiChatDialog.dismiss();
        super.onDestroy();
        if (permissions != null) permissions.destroy();
        if (isFinishing() && !prefs.exitClearFlags().isEmpty()) clearData(prefs.exitClearFlags(), () -> { });
        if (suggestPopup != null) suggestPopup.destroy();
        if (downloadManager != null) downloadManager.cancel();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        executor.shutdownNow();
        for (TabManager.Tab t : tabs.all()) {
            for (TabManager.Page page : t.clearPages()) destroyPage(page);
        }
    }
}
