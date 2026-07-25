package com.example.cleanrecovery.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.background.BackgroundDownloadService;
import com.example.cleanrecovery.background.CompositeWebViewClient;
import com.example.cleanrecovery.background.DownloadQueueManager;
import com.example.cleanrecovery.background.HiddenMediaSniffer;
import com.example.cleanrecovery.download.DownloadProgressCallback;
import com.example.cleanrecovery.download.UniversalDownloadManager;
import com.example.cleanrecovery.extractor.MediaSniffer;
import com.example.cleanrecovery.proxy.ProxyActivity;
import com.example.cleanrecovery.proxy.ProxyRouter;
import com.example.cleanrecovery.ui.browser.BrowserDatabaseHelper;
import com.example.cleanrecovery.ui.browser.BrowserAdBlocker;
import com.example.cleanrecovery.ui.browser.BrowserBottomMenu;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.browser.SearchEngines;
import com.example.cleanrecovery.ui.browser.TabManager;
import com.example.cleanrecovery.ui.browser.TabSnapshotHolder;
import com.example.cleanrecovery.ui.browser.ViaAddressResolver;
import com.example.cleanrecovery.ui.browser.ViaSnifferStateMachine;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;
import com.example.cleanrecovery.util.FaviconFetcher;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * VIA 风格内置浏览器 Activity。
 *
 * <p>核心交互 1:1 复刻 VIA 浏览器：顶部地址栏（后退|前进|地址|主页|标签角标|菜单）、
 * 顶部加载进度条、多标签、书签、历史、设置、资源嗅探（保留原有嗅探链路）。</p>
 *
 * <p>保留并复用 {@link MediaSniffer}/{@link HiddenMediaSniffer} 嗅探与下载链路。</p>
 */
public final class BrowserActivity extends Activity {

    private static final String TAG = "BrowserActivity";
    private static final int REQ_TABS = 1001;
    private static final int REQ_BOOKMARKS = 1002;
    private static final int REQ_HISTORY = 1003;
    private static final int REQ_SETTINGS = 1004;
    private static final int REQ_PROXY = 1005;
    private static final int REQ_OFFLINE = 1006;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private FrameLayout webContainer;
    private LinearLayout toolbar;
    private LinearLayout toolbarPrimary;
    private LinearLayout toolbarAddressRow;
    private LinearLayout bottomNavigation;
    private ImageButton siteInfoButton;
    private ImageButton scanButton;
    private TextView homeTitleView;
    private FrameLayout tabsButtonContainer;
    private EditText urlInput;
    private EditText homeSearch;
    private ImageButton navBackButton;
    private ImageButton navForwardButton;
    private ImageButton homeButton;
    private ImageButton menuButton;
    private TextView tabBadge;
    private ProgressBar progressbar;
    private TextView emptyHint;
    private TextView snifferCount;
    private ImageView proxyDot;
    private ScrollView homeScroll;
    private GridLayout homeGrid;
    private ImageButton homeAddButton;

    private LinearLayout snifferPanel;
    private LinearLayout mediaList;
    private TextView snifferHint;
    private Button downloadButton;
    private ImageButton snifferCloseButton;

    private final TabManager tabs = new TabManager();
    /** VIA 嗅探状态机：角标显隐与不支持站点策略。 */
    private final ViaSnifferStateMachine viaSniffer = new ViaSnifferStateMachine();
    private BrowserPrefs prefs;
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
        final List<MediaSniffer.MediaResource> mediaResources = new ArrayList<>();
        final List<String> networkLog = new ArrayList<>();
        BrowserAdBlocker adBlocker;
        boolean adMarkerArmed;
        boolean incognito;
        int selectedIndex = -1;

        TabState(MediaSniffer sniffer) {
            this.sniffer = sniffer;
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser);

        prefs = new BrowserPrefs(this);
        dbHelper = BrowserDatabaseHelper.getInstance(this);
        WebView.setWebContentsDebuggingEnabled(prefs.webDebug());

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
        emptyHint = findViewById(R.id.browser_empty_hint);
        snifferCount = findViewById(R.id.browser_sniffer_count);

        snifferPanel = findViewById(R.id.browser_sniffer_panel);
        mediaList = findViewById(R.id.browser_media_list);
        snifferHint = findViewById(R.id.browser_sniffer_hint);
        downloadButton = findViewById(R.id.browser_download_all);
        snifferCloseButton = findViewById(R.id.browser_sniffer_close);

        webContainer = findViewById(R.id.browser_web_container);
        proxyDot = findViewById(R.id.browser_proxy_dot);
        homeScroll = findViewById(R.id.browser_home_scroll);
        homeGrid = findViewById(R.id.browser_home_grid);
        homeAddButton = findViewById(R.id.browser_home_add);
        homeSearch = findViewById(R.id.browser_home_search);
        toolbar = findViewById(R.id.browser_toolbar);
        toolbarPrimary = findViewById(R.id.browser_toolbar_primary);
        toolbarAddressRow = findViewById(R.id.browser_toolbar_address_row);
        tabsButtonContainer = (FrameLayout) findViewById(R.id.browser_tabs).getParent();
        createViaToolbarCompanions();

        applyToolbarMode();

        // 启动后台下载服务（保留原行为）
        try {
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
        if (homeSearch != null) homeSearch.setOnEditorActionListener(goAction);

        navBackButton.setOnClickListener(v -> {
            TabManager.Tab cur = tabs.current();
            if (cur != null && cur.webView.canGoBack()) cur.webView.goBack();
        });
        navBackButton.setOnLongClickListener(v -> {
            showNavigationHistory(true);
            return true;
        });
        navForwardButton.setOnClickListener(v -> {
            TabManager.Tab cur = tabs.current();
            if (cur != null && cur.webView.canGoForward()) cur.webView.goForward();
        });
        navForwardButton.setOnLongClickListener(v -> {
            showNavigationHistory(false);
            return true;
        });
        homeButton.setOnClickListener(v -> showHome());
        homeButton.setOnLongClickListener(v -> {
            focusAddressBar();
            return true;
        });
        menuButton.setOnClickListener(v -> showMainMenu());
        View tabsButton = findViewById(R.id.browser_tabs);
        tabsButton.setOnClickListener(v -> openTabs());
        tabsButton.setOnLongClickListener(v -> {
            newTab("");
            showHome();
            return true;
        });
        tabsButtonContainer.setOnClickListener(v -> openTabs());
        tabsButtonContainer.setOnLongClickListener(v -> {
            newTab("");
            showHome();
            return true;
        });
        snifferCloseButton.setOnClickListener(v -> snifferPanel.setVisibility(View.GONE));
        downloadButton.setOnClickListener(v -> onDownloadSelected());
        homeAddButton.setOnClickListener(v -> showAddQuickLinkDialog());

        // 初始标签：无显式 URL 时进入 VIA 九宫格主页。兼容 VIA 默认浏览器入口：
        // ACTION_VIEW(data)、ACTION_SEND(text/plain)、WEB_SEARCH/PROCESS_TEXT(query)。
        String initialUrl = resolveLaunchUrl(getIntent());
        if (initialUrl == null || initialUrl.isEmpty()) {
            newTab("");
            showHome();
        } else {
            newTab(initialUrl);
        }
        updateProxyIndicator();
    }

    @Override
    protected void onResume() {
        super.onResume();
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

    /** 显示 VIA 风格主页（Logo + 胶囊搜索 + 快捷链接）。 */
    private void showHome() {
        renderHomeGrid();
        homeScroll.setVisibility(View.VISIBLE);
        emptyHint.setVisibility(View.GONE);
        urlInput.setText("");
        if (homeSearch != null) homeSearch.setText("");
        TabManager.Tab cur = tabs.current();
        if (cur != null) {
            cur.url = "";
            updateNavButtons(cur);
        }
        applyToolbarMode();
    }

    /** 渲染主页九宫格快捷链接。 */
    private void renderHomeGrid() {
        homeGrid.removeAllViews();
        List<BrowserDatabaseHelper.Entry> links = dbHelper.listQuickLinks();
        homeGrid.setVisibility(links.isEmpty() ? View.GONE : View.VISIBLE);
        for (BrowserDatabaseHelper.Entry e : links) {
            homeGrid.addView(buildQuickLinkItem(e));
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
    private View buildQuickLinkItem(BrowserDatabaseHelper.Entry e) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = 0;
        lp.height = GridLayout.LayoutParams.WRAP_CONTENT;
        lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
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
        int side = getResources().getDimensionPixelSize(R.dimen.space_xl)
                + getResources().getDimensionPixelSize(R.dimen.space_xl);
        LinearLayout.LayoutParams iconLl = new LinearLayout.LayoutParams(side, side);
        iconLl.gravity = Gravity.CENTER_HORIZONTAL;
        iconLp.setLayoutParams(iconLl);
        iconLp.setGravity(Gravity.CENTER);
        iconLp.setBackgroundResource(R.drawable.bg_quicklink_item);
        iconLp.addView(letter);

        // favicon：优先 google s2/favicons，异步加载到 ImageView，失败回退首字母
        final ImageView favicon = new ImageView(this);
        LinearLayout.LayoutParams favLp = new LinearLayout.LayoutParams(side, side);
        favLp.gravity = Gravity.CENTER;
        favicon.setLayoutParams(favLp);
        favicon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = getResources().getDimensionPixelSize(R.dimen.space_sm);
        favicon.setPadding(pad, pad, pad, pad);
        favicon.setVisibility(View.GONE);
        iconLp.addView(favicon);
        loadFavicon(favicon, letter, e.url);

        TextView title = new TextView(this);
        title.setText(e.title);
        title.setTextColor(getResources().getColor(R.color.text_secondary));
        title.setTextSize(12);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.setMargins(0, getResources().getDimensionPixelSize(R.dimen.space_xs), 0, 0);
        title.setLayoutParams(titleLp);

        item.addView(iconLp);
        item.addView(title);

        item.setOnClickListener(v -> loadUrlInCurrent(e.url));
        item.setOnLongClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.via_home_remove)
                    .setMessage(e.title + "\n" + e.url)
                    .setPositiveButton(android.R.string.ok, (d, w) -> {
                        dbHelper.removeQuickLink(e.id);
                        renderHomeGrid();
                        Toast.makeText(this, R.string.via_home_removed, Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(this, R.string.via_home_add_url, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (!u.startsWith("http://") && !u.startsWith("https://")) {
                        u = "https://" + u;
                    }
                    dbHelper.addQuickLink(t.isEmpty() ? u : t, u);
                    renderHomeGrid();
                    Toast.makeText(this, R.string.via_home_added, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 更新代理状态指示点。 */
    private void updateProxyIndicator() {
        boolean on = ProxyRouter.isProxyActive();
        proxyDot.setBackgroundColor(getResources().getColor(
                on ? R.color.via_proxy_on : R.color.via_proxy_off));
        proxyDot.setContentDescription(getString(on
                ? R.string.via_proxy_on_short : R.string.via_proxy_off_short));
    }

    /** URL 编码（兼容 minSdk 23：URLEncoder.encode(String, Charset) 需 API 33）。 */
    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    /** 在当前标签加载 URL 并隐藏主页。 */
    private void loadUrlInCurrent(String url) {
        if (url == null || url.isEmpty()) return;
        homeScroll.setVisibility(View.GONE);
        applyToolbarMode();
        TabManager.Tab cur = tabs.current();
        if (cur == null) {
            newTab(url);
        } else {
            urlInput.setText(url);
            cur.webView.loadUrl(url);
        }
    }

    /** 显示 VIA 风格底部 2×5 分页菜单。 */
    private void showMainMenu() {
        new BrowserBottomMenu(
                this, prefs, buildMenuEntries(), this::onMenuAction).show();
    }

    private void focusAddressBar() {
        urlInput.requestFocus();
        urlInput.selectAll();
        urlInput.post(() -> {
            android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
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
            Toast.makeText(this, backwards
                    ? R.string.via_no_back_history : R.string.via_no_forward_history,
                    Toast.LENGTH_SHORT).show();
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
            // VIA 菜单格文案固定「夜间模式」，状态用 Toast 反馈
            nightItem.setTitle(R.string.via_menu_night);
        }
        MenuItem uaItem = source.getMenu().findItem(R.id.menu_ua);
        if (uaItem != null) {
            // VIA 首屏文案固定为「电脑模式」，点击后 Toast 提示开/关
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
            // VIA 工具箱文案固定「有图模式」，状态用 Toast/设置生效反馈。
            imgItem.setTitle(R.string.via_menu_image_mode);
        }
        MenuItem fsItem = source.getMenu().findItem(R.id.menu_fullscreen);
        if (fsItem != null) {
            // VIA 工具箱文案固定「全屏」。
            fsItem.setTitle(R.string.via_menu_fullscreen);
        }
        List<BrowserBottomMenu.Entry> entries = new ArrayList<>();
        for (int i = 0; i < source.getMenu().size(); i++) {
            MenuItem item = source.getMenu().getItem(i);
            String name;
            try {
                name = getResources().getResourceEntryName(item.getItemId());
            } catch (Exception ignored) {
                continue;
            }
            entries.add(new BrowserBottomMenu.Entry(
                    item.getItemId(), name, item.getTitle(), item.getIcon()));
        }
        return entries;
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
            startActivityForResult(new Intent(this, HistoryActivity.class), REQ_HISTORY);
            return true;
        } else if (id == R.id.menu_share) {
            shareCurrentUrl();
            return true;
        } else if (id == R.id.menu_sniff) {
            snifferPanel.setVisibility(View.VISIBLE);
            return true;
        } else if (id == R.id.menu_download) {
            openDownloadActivity();
            return true;
        } else if (id == R.id.menu_translate) {
            TabManager.Tab cur = tabs.current();
            if (cur != null && cur.url != null && !cur.url.isEmpty()) {
                String t = "https://translate.google.com/translate?sl=auto&tl=zh-CN&u="
                        + urlEncode(cur.url);
                cur.webView.loadUrl(t);
            } else {
                Toast.makeText(this, R.string.browser_empty_hint, Toast.LENGTH_SHORT).show();
            }
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
            boolean now = !prefs.imagesEnabled();
            prefs.setImagesEnabled(now);
            for (TabManager.Tab t : tabs.all()) applySettings(t.webView);
            Toast.makeText(this, now ? R.string.via_images_on : R.string.via_images_off,
                    Toast.LENGTH_SHORT).show();
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
            for (TabManager.Tab t : tabs.all()) applyDarkMode(t.webView, now);
            Toast.makeText(this, now ? R.string.via_night_on : R.string.via_night_off,
                    Toast.LENGTH_SHORT).show();
            return true;
        } else if (id == R.id.menu_ua) {
            // VIA：电脑模式切换；开=桌面 UA，关=移动 UA
            boolean enableDesktop = prefs.uaMode() != 0;
            int mode = enableDesktop ? 0 : 1;
            prefs.setUaMode(mode);
            prefs.setCustomUserAgent("");
            for (TabManager.Tab t : tabs.all()) {
                applySettings(t.webView);
                if (!TextUtils.isEmpty(t.url)) t.webView.reload();
            }
            Toast.makeText(this,
                    enableDesktop ? R.string.via_computer_mode_on : R.string.via_computer_mode_off,
                    Toast.LENGTH_SHORT).show();
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
            addCurrentBookmark();
            return true;
        } else if (id == R.id.menu_incognito) {
            newIncognitoTab();
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
            sharePageToAi();
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

    /** VIA「添加到桌面」：创建当前页快捷方式。 */
    private void addDesktopShortcut() {
        TabManager.Tab cur = tabs.current();
        String url = cur != null ? cur.url : null;
        if (url == null || url.isEmpty()) {
            Toast.makeText(this, R.string.browser_empty_hint, Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, R.string.via_desktop_shortcut_hint, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, R.string.via_report_sent, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, url.isEmpty()
                    ? getString(R.string.browser_empty_hint) : url, Toast.LENGTH_SHORT).show();
        }
    }

    private void clearBrowserData() {
        for (TabManager.Tab tab : tabs.all()) {
            if (tab.webView != null) {
                tab.webView.clearCache(true);
                tab.webView.clearFormData();
                tab.webView.clearHistory();
            }
        }
        android.webkit.CookieManager.getInstance().removeAllCookies(null);
        android.webkit.CookieManager.getInstance().flush();
        Toast.makeText(this, R.string.via_data_cleared, Toast.LENGTH_SHORT).show();
    }

    private void launchQrScanner() {
        new IntentIntegrator(this)
                .setDesiredBarcodeFormats(java.util.Collections.singleton(
                        com.google.zxing.BarcodeFormat.QR_CODE.name()))
                .setPrompt(getString(R.string.via_scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(true)
                .setCaptureActivity(com.journeyapps.barcodescanner.CaptureActivity.class)
                .initiateScan();
    }

    private void enterReaderMode() {
        TabManager.Tab tab = tabs.current();
        if (tab == null || tab.webView == null) return;
        tab.webView.evaluateJavascript(
                "(function(){try{var a=document.querySelector('article,main,[role=main]')||document.body;"
                        + "var c=a.cloneNode(true);c.querySelectorAll('script,style,nav,aside,footer,"
                        + "iframe,form,button').forEach(function(e){e.remove();});"
                        + "document.head.innerHTML='<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">';"
                        + "document.body.innerHTML='';document.body.appendChild(c);"
                        + "document.body.style.cssText='max-width:760px;margin:auto;padding:24px;"
                        + "font-size:18px;line-height:1.75;background:#fafafa;color:#222';"
                        + "document.querySelectorAll('img').forEach(function(i){i.style.maxWidth='100%';"
                        + "i.style.height='auto';});return true;}catch(e){return false;}})();",
                value -> Toast.makeText(this,
                        "true".equals(value) ? R.string.via_reader_enabled
                                : R.string.via_reader_failed,
                        Toast.LENGTH_SHORT).show());
    }

    private void readPageAloud() {
        TabManager.Tab tab = tabs.current();
        if (tab == null || tab.webView == null) return;
        tab.webView.evaluateJavascript(
                "(function(){return (document.body&&document.body.innerText)||'';})()",
                value -> {
                    String text = decodeJsString(value).trim();
                    if (text.isEmpty()) {
                        Toast.makeText(this, R.string.via_read_aloud_empty,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (text.length() > 3900) text = text.substring(0, 3900);
                    final String spoken = text;
                    if (textToSpeech != null) {
                        textToSpeech.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "via-page");
                        return;
                    }
                    textToSpeech = new TextToSpeech(this, status -> {
                        if (status == TextToSpeech.SUCCESS) {
                            textToSpeech.setLanguage(Locale.getDefault());
                            textToSpeech.speak(spoken, TextToSpeech.QUEUE_FLUSH,
                                    null, "via-page");
                        } else {
                            Toast.makeText(this, R.string.via_read_aloud_failed,
                                    Toast.LENGTH_SHORT).show();
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

    private void showFontSizeDialog() {
        final int[] zooms = {75, 90, 100, 115, 130, 150, 175, 200};
        String[] labels = new String[zooms.length];
        int checked = 2;
        for (int i = 0; i < zooms.length; i++) {
            labels[i] = zooms[i] + "%";
            if (zooms[i] == prefs.textZoom()) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_menu_font_size)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.setTextZoom(zooms[which]);
                    for (TabManager.Tab tab : tabs.all()) {
                        tab.webView.getSettings().setTextZoom(zooms[which]);
                    }
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
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
                                Toast.makeText(this,
                                        getString(R.string.via_script_result,
                                                decodeJsString(result)),
                                        Toast.LENGTH_LONG).show());
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void markCurrentHostAsAd() {
        TabManager.Tab tab = tabs.current();
        if (tab == null || TextUtils.isEmpty(tab.url)) return;
        String host = hostFromUrl(tab.url);
        if (TextUtils.isEmpty(host)) return;
        prefs.setAdBlockEnabled(true);
        TabState state = getState(tab);
        if (state != null) state.adMarkerArmed = true;
        injectAdMarker(tab.webView);
        Toast.makeText(this, R.string.via_ad_marker_ready, Toast.LENGTH_LONG).show();
    }

    private void injectAdMarker(WebView webView) {
        if (webView == null) return;
        String js = "(function(){"
                + "if(window.__viaAdMarkerActive)return;window.__viaAdMarkerActive=true;"
                + "var old=document.getElementById('__via_ad_marker_tip');if(old)old.remove();"
                + "var tip=document.createElement('div');tip.id='__via_ad_marker_tip';"
                + "tip.textContent='点击页面上的广告区域';"
                + "tip.style.cssText='position:fixed;left:12px;right:12px;top:12px;z-index:2147483647;background:#111827;color:#fff;border-radius:20px;padding:10px 14px;text-align:center;font-size:14px;box-shadow:0 4px 16px rgba(0,0,0,.25)';"
                + "document.documentElement.appendChild(tip);"
                + "function sel(el){if(!el||el===document.documentElement||el===document.body)return 'body';"
                + "if(el.id)return '#'+CSS.escape(el.id);var a=[],n=el;while(n&&n.nodeType===1&&n!==document.body){var s=n.tagName.toLowerCase();"
                + "if(n.classList&&n.classList.length){s+='.'+Array.prototype.slice.call(n.classList,0,3).map(function(c){return CSS.escape(c)}).join('.');}"
                + "var p=n.parentElement;if(p){var same=Array.prototype.filter.call(p.children,function(x){return x.tagName===n.tagName});if(same.length>1)s+=':nth-of-type('+(same.indexOf(n)+1)+')';}"
                + "a.unshift(s);n=p;if(a.length>=5)break;}return a.join('>');}"
                + "function done(e){try{e.preventDefault();e.stopPropagation();var t=e.target;if(t&&t.id==='__via_ad_marker_tip')return;var r=sel(t);"
                + "t.style.setProperty('display','none','important');window.ViaAdMarker.report(location.href,r);}catch(x){}"
                + "document.removeEventListener('click',done,true);window.__viaAdMarkerActive=false;setTimeout(function(){var tip=document.getElementById('__via_ad_marker_tip');if(tip)tip.remove();},80);}"
                + "document.addEventListener('click',done,true);"
                + "})();";
        webView.evaluateJavascript(js, null);
    }

    private final class AdMarkerBridge {
        @JavascriptInterface
        public void report(String pageUrl, String selector) {
            mainHandler.post(() -> onAdMarked(pageUrl, selector));
        }
    }

    private void onAdMarked(String pageUrl, String selector) {
        String host = hostFromUrl(pageUrl);
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(selector)) return;
        prefs.addCosmeticRule(host, selector);
        prefs.setAdBlockEnabled(true);
        TabManager.Tab cur = tabs.current();
        if (cur != null) {
            TabState st = getState(cur);
            if (st != null) st.adMarkerArmed = false;
            applyAdBlockCss(cur.webView, cur.url);
        }
        Toast.makeText(this, R.string.via_ad_marker_saved, Toast.LENGTH_SHORT).show();
    }

    private void applyAdBlockCss(WebView webView, String pageUrl) {
        if (webView == null || !prefs.adBlockEnabled()) return;
        String css = prefs.cosmeticCssForHost(hostFromUrl(pageUrl));
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
        tab.webView.evaluateJavascript(
                "(function(){try{return '<!doctype html><html><head><base href=\"'"
                        + "+location.href.replace(/\"/g,'&quot;')+'\">'+"
                        + "document.documentElement.innerHTML+'</html>';"
                        + "}catch(e){return document.documentElement.outerHTML;}})();",
                value -> writeOfflineSnapshot(title, sourceUrl, decodeJsString(value)));
    }

    private void writeOfflineSnapshot(String title, String sourceUrl, String html) {
        if (TextUtils.isEmpty(html)) {
            Toast.makeText(this, R.string.via_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        File root = new File(getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
                "OfflinePages");
        if (!root.exists() && !root.mkdirs()) {
            Toast.makeText(this, R.string.via_save_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        String safeTitle = TextUtils.isEmpty(title) ? "page" : title;
        safeTitle = safeTitle.replaceAll("[\\/:*?\"<>|]", "_");
        if (safeTitle.length() > 48) safeTitle = safeTitle.substring(0, 48);
        File target = new File(root, safeTitle + "-" + System.currentTimeMillis() + ".html");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(target)) {
            fos.write(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            dbHelper.addOfflinePage(title, sourceUrl, target.getAbsolutePath());
            Toast.makeText(this, getString(R.string.via_offline_saved_via),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "save offline html failed", e);
            Toast.makeText(this, R.string.via_save_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private void openOfflinePages() {
        startActivityForResult(new Intent(this, BrowserOfflinePagesActivity.class), REQ_OFFLINE);
    }

    private void newIncognitoTab() {
        TabManager.Tab tab = newTab("");
        TabState state = getState(tab);
        if (state != null) state.incognito = true;
        WebSettings settings = tab.webView.getSettings();
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setSaveFormData(false);
        tab.webView.clearHistory();
        Toast.makeText(this, R.string.via_incognito_started, Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, gameMode ? R.string.via_game_on : R.string.via_game_off,
                Toast.LENGTH_SHORT).show();
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
                R.id.menu_site_conf, R.id.menu_customize_menu
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
            else if (id == R.id.menu_site_conf) title = getString(R.string.via_toolbox_report_site);
            tools.add(new BrowserBottomMenu.Entry(
                    source.id, source.resourceName, title, source.icon));
        }
        new BrowserBottomMenu(this, tools, this::onMenuAction).show();
    }

    private void showSiteConfiguration() {
        String host = currentHost();
        if (TextUtils.isEmpty(host)) {
            Toast.makeText(this, R.string.browser_empty_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        String cookieText = getString(R.string.via_site_cookies) + "："
                + getString(prefs.siteCookiesOff(host)
                ? R.string.via_site_disabled : R.string.via_site_enabled);
        String adText = getString(R.string.via_menu_adblock) + "："
                + getString(prefs.siteAdBlockOff(host)
                ? R.string.via_site_disabled : R.string.via_site_follow_global);
        String uaText = getString(R.string.via_menu_useragent);
        String zoomText = getString(R.string.via_menu_font_size) + "："
                + prefs.siteTextZoom(host, prefs.textZoom()) + "%";
        String[] items = {
                cookieText, uaText, adText, zoomText, getString(R.string.via_site_reset)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.via_site_settings_title_format, host))
                .setItems(items, (dialog, which) -> {
                    if (which == 0) toggleSiteCookies(host);
                    else if (which == 1) showSiteUserAgentDialog(host);
                    else if (which == 2) toggleSiteAdBlock(host);
                    else if (which == 3) showSiteFontSizeDialog(host);
                    else resetSiteSettings(host);
                })
                .show();
    }

    private void toggleSiteCookies(String host) {
        boolean off = !prefs.siteCookiesOff(host);
        prefs.setSiteCookiesOff(host, off);
        if (off) clearCookiesForCurrentUrl();
        Toast.makeText(this, off ? R.string.via_site_cookies_off : R.string.via_site_cookies_on,
                Toast.LENGTH_SHORT).show();
    }

    private void clearCookiesForCurrentUrl() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || TextUtils.isEmpty(cur.url)) return;
        try {
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            String cookies = cm.getCookie(cur.url);
            if (cookies != null) {
                for (String c : cookies.split(";")) {
                    String name = c.trim();
                    int eq = name.indexOf('=');
                    if (eq > 0) name = name.substring(0, eq);
                    if (!name.isEmpty()) {
                        cm.setCookie(cur.url, name + "=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Max-Age=0");
                    }
                }
                cm.flush();
            }
        } catch (Exception ignored) {
        }
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
                        Toast.makeText(this, R.string.via_site_settings_saved,
                                Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, off ? R.string.via_site_adblock_off : R.string.via_site_adblock_follow,
                Toast.LENGTH_SHORT).show();
    }

    private void showSiteFontSizeDialog(String host) {
        final int[] zooms = {75, 90, 100, 110, 125, 150, 175, 200};
        String[] labels = new String[zooms.length];
        int checked = 2;
        int current = prefs.siteTextZoom(host, prefs.textZoom());
        for (int i = 0; i < zooms.length; i++) {
            labels[i] = zooms[i] + "%";
            if (zooms[i] == current) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.via_menu_font_size)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    prefs.setSiteTextZoom(host, zooms[which]);
                    reloadCurrentWithSettings();
                    dialog.dismiss();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void resetSiteSettings(String host) {
        prefs.resetSiteSettings(host);
        reloadCurrentWithSettings();
        Toast.makeText(this, R.string.via_site_reset_done, Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, enabled ? R.string.via_adblock_on : R.string.via_adblock_off,
                Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, R.string.browser_no_media_selected, Toast.LENGTH_SHORT).show();
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
                        Toast.makeText(this, R.string.via_share_link_copied, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, R.string.browser_empty_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(cur.url));
            startActivity(Intent.createChooser(it, getString(R.string.via_open_with_title)));
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private TabManager.Tab closeAndDestroyTab(int index) {
        if (index < 0 || index >= tabs.size()) return tabs.current();
        TabManager.Tab removed = tabs.all().get(index);
        webContainer.removeView(removed.webView);
        removed.webView.stopLoading();
        removed.webView.setWebChromeClient(null);
        removed.webView.setWebViewClient(new WebViewClient());
        removed.webView.destroy();
        return tabs.close(index);
    }

    /** 切换屏幕方向（横屏/竖屏）。 */
    private void toggleRotation() {
        int cur = getRequestedOrientation();
        if (cur == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            Toast.makeText(this, R.string.via_rotation_portrait, Toast.LENGTH_SHORT).show();
        } else {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            Toast.makeText(this, R.string.via_rotation_landscape, Toast.LENGTH_SHORT).show();
        }
    }

    /** 切换全屏（隐藏系统 UI 与工具栏，再按退出）。 */
    private void toggleFullscreen() {
        fullscreen = !fullscreen;
        if (fullscreen) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            toolbar.setVisibility(View.GONE);
            Toast.makeText(this, R.string.via_fullscreen_on, Toast.LENGTH_SHORT).show();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            toolbar.setVisibility(View.VISIBLE);
            Toast.makeText(this, R.string.via_fullscreen_off, Toast.LENGTH_SHORT).show();
        }
    }

    /** 截图：把当前 WebView 绘制到 Bitmap 并保存到下载目录。 */
    private void captureScreenshot() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.webView == null) {
            Toast.makeText(this, R.string.via_screenshot_failed, Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, getString(R.string.via_screenshot_saved, out.getAbsolutePath()),
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e(TAG, "screenshot failed", e);
            Toast.makeText(this, R.string.via_screenshot_failed, Toast.LENGTH_SHORT).show();
        }
    }

    /** 查看源码：JS 取 outerHTML，AlertDialog 展示。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void showPageSource() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.url == null || cur.url.isEmpty()) {
            Toast.makeText(this, R.string.browser_empty_hint, Toast.LENGTH_SHORT).show();
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
        TabManager.Tab cur = tabs.current();
        if (cur == null) {
            Toast.makeText(this, R.string.browser_empty_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        final WebView wv = cur.webView;
        final EditText input = new EditText(this);
        input.setHint(R.string.via_find_hint);
        input.setSingleLine(true);
        TextView count = new TextView(this);
        count.setText(R.string.via_find_no_result);
        count.setTextSize(13);
        count.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_muted));
        count.setPadding(0, dp(8), 0, 0);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(12), dp(24), 0);
        box.addView(input);
        box.addView(count);
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.via_menu_find)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.via_menu_find, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dlg.setOnShowListener(d -> {
            final TextView[] countView = new TextView[]{count};
            wv.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
                findCount = numberOfMatches;
                findIndex = activeMatchOrdinal;
                if (countView[0] != null) {
                    countView[0].setText(numberOfMatches > 0
                            ? getString(R.string.via_find_result_format, activeMatchOrdinal + 1, numberOfMatches)
                            : getString(R.string.via_find_no_result));
                }
            });
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String q = input.getText().toString();
                if (!TextUtils.isEmpty(q)) wv.findAllAsync(q);
            });
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                if (findCount > 0) wv.findNext(true);
            });
        });
        dlg.show();
    }

    /** 把当前页加入书签。 */
    private void addCurrentBookmark() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.url == null || cur.url.isEmpty()) {
            Toast.makeText(this, R.string.browser_empty_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        dbHelper.addBookmark(cur.title != null && !cur.title.isEmpty() ? cur.title : cur.url, cur.url);
        Toast.makeText(this, R.string.via_bookmark_added_current, Toast.LENGTH_SHORT).show();
    }

    /** 把当前页添加为主页九宫格快捷链接。 */
    private void addCurrentQuickLink() {
        TabManager.Tab cur = tabs.current();
        if (cur == null || cur.url == null || cur.url.isEmpty()) {
            Toast.makeText(this, R.string.browser_empty_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        dbHelper.addQuickLink(cur.title != null && !cur.title.isEmpty() ? cur.title : cur.url, cur.url);
        Toast.makeText(this, R.string.via_quicklink_added_current, Toast.LENGTH_SHORT).show();
    }

    private void createViaToolbarCompanions() {
        siteInfoButton = createToolbarButton(
                R.drawable.ic_search, R.string.via_site_information);
        siteInfoButton.setOnClickListener(v -> showSiteConfiguration());
        scanButton = createToolbarButton(R.drawable.ic_scan, R.string.via_menu_scan);
        scanButton.setOnClickListener(v -> launchQrScanner());
        homeTitleView = new TextView(this);
        homeTitleView.setText(R.string.via_home_title);
        homeTitleView.setTextSize(18);
        homeTitleView.setTextColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.text_primary));
        homeTitleView.setGravity(Gravity.CENTER_VERTICAL);
        homeTitleView.setPadding(0, 0, 0, 0);

        bottomNavigation = new LinearLayout(this);
        bottomNavigation.setOrientation(LinearLayout.HORIZONTAL);
        bottomNavigation.setGravity(Gravity.CENTER_VERTICAL);
        bottomNavigation.setBackgroundColor(
                androidx.core.content.ContextCompat.getColor(this, R.color.surface_card));
        bottomNavigation.setElevation(dp(2));
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

    /** 应用 VIA 的顶部/底部/三明治/双行工具栏结构。默认底部五键。 */
    private void applyToolbarMode() {
        if (toolbar == null || toolbarPrimary == null || toolbarAddressRow == null) return;
        android.view.ViewParent vp = toolbar.getParent();
        if (!(vp instanceof LinearLayout)) return;
        LinearLayout root = (LinearLayout) vp;
        int mode = prefs.toolbarMode();
        boolean atHome = homeScroll != null && homeScroll.getVisibility() == View.VISIBLE;
        toolbarPrimary.removeAllViews();
        toolbarAddressRow.removeAllViews();
        bottomNavigation.removeAllViews();
        root.removeView(toolbar);
        root.removeView(bottomNavigation);
        root.addView(toolbar, 0);

        // VIA 默认：顶栏 = 网站信息 + 主页标题/地址 + 扫码；底栏 = 后/前/主页/标签/菜单
        if (mode == 1 || mode == 2 || mode == 3) {
            addToolbarView(toolbarPrimary, siteInfoButton, dp(48), dp(48), 0);
            if (atHome) {
                urlInput.setVisibility(View.GONE);
                homeTitleView.setVisibility(View.VISIBLE);
                addToolbarView(toolbarPrimary, homeTitleView, 0, dp(48), 1);
            } else {
                homeTitleView.setVisibility(View.GONE);
                urlInput.setVisibility(View.VISIBLE);
                addToolbarView(toolbarPrimary, urlInput, 0, dp(40), 1);
            }
            addToolbarView(toolbarPrimary, scanButton, dp(48), dp(48), 0);
            toolbarAddressRow.setVisibility(View.GONE);

            navBackButton.setVisibility(View.VISIBLE);
            navForwardButton.setVisibility(View.VISIBLE);
            homeButton.setVisibility(View.VISIBLE);
            menuButton.setVisibility(View.VISIBLE);
            tabsButtonContainer.setVisibility(View.VISIBLE);
            addToolbarView(bottomNavigation, navBackButton, 0, dp(48), 1);
            addToolbarView(bottomNavigation, navForwardButton, 0, dp(48), 1);
            addToolbarView(bottomNavigation, homeButton, 0, dp(48), 1);
            addToolbarView(bottomNavigation, tabsButtonContainer, 0, dp(48), 1);
            addToolbarView(bottomNavigation, menuButton, 0, dp(48), 1);
            if (mode != 3) {
                root.addView(bottomNavigation, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
            } else {
                // 双行：底栏按钮放回顶栏第二行
                toolbarAddressRow.setVisibility(View.VISIBLE);
                addToolbarView(toolbarAddressRow, navBackButton, 0, dp(48), 1);
                addToolbarView(toolbarAddressRow, navForwardButton, 0, dp(48), 1);
                addToolbarView(toolbarAddressRow, homeButton, 0, dp(48), 1);
                addToolbarView(toolbarAddressRow, tabsButtonContainer, 0, dp(48), 1);
                addToolbarView(toolbarAddressRow, menuButton, 0, dp(48), 1);
            }
        } else {
            urlInput.setVisibility(View.VISIBLE);
            addToolbarView(toolbarPrimary, navBackButton, dp(40), dp(40), 0);
            addToolbarView(toolbarPrimary, navForwardButton, dp(40), dp(40), 0);
            addToolbarView(toolbarPrimary, urlInput, 0, dp(40), 1);
            addToolbarView(toolbarPrimary, homeButton, dp(40), dp(40), 0);
            addToolbarView(toolbarPrimary, tabsButtonContainer, dp(44), dp(44), 0);
            addToolbarView(toolbarPrimary, menuButton, dp(40), dp(40), 0);
            addToolbarView(toolbarPrimary, scanButton, dp(40), dp(40), 0);
            toolbarAddressRow.setVisibility(View.GONE);
        }
        toolbarPrimary.getLayoutParams().height = dp(48);
        toolbarPrimary.requestLayout();
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
                FrameLayout.LayoutParams tabParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                tabParams.gravity = Gravity.CENTER;
                tabs.setLayoutParams(tabParams);
            }
            if (tabBadge != null) {
                FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(22), dp(22));
                badgeParams.gravity = Gravity.CENTER;
                tabBadge.setLayoutParams(badgeParams);
            }
        }
        parent.addView(view, params);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 打开标签管理页。 */
    private void openTabs() {
        TabSnapshotHolder.Snapshot s = new TabSnapshotHolder.Snapshot();
        for (TabManager.Tab t : tabs.all()) {
            s.titles.add(t.title == null ? "" : t.title);
            s.urls.add(t.url == null ? "" : t.url);
        }
        s.currentIndex = tabs.currentIndex();
        TabSnapshotHolder.set(s);
        startActivityForResult(new Intent(this, TabsActivity.class), REQ_TABS);
    }

    /** 新建标签并加载 URL。 */
    private TabManager.Tab newTab(String url) {
        WebView webView = createWebView();
        TabManager.Tab tab = tabs.newTab(webView);
        TabState state = new TabState(createSniffer(tab));
        tab.tag = state;
        applySettings(webView);
        attachClients(tab, state);
        // WebView 创建后立即按当前代理状态路由
        try {
            ProxyRouter.applyProxy(webView);
        } catch (Exception e) {
            Log.w(TAG, "applyProxy on newTab failed: " + e.getMessage());
        }
        showTab(tab);
        updateTabBadge();
        if (url != null && !url.isEmpty()) {
            webView.loadUrl(url);
            urlInput.setText(url);
        } else {
            showHome();
        }
        return tab;
    }

    /** 创建并基础配置 WebView。 */
    @SuppressLint("SetJavaScriptEnabled")
    private WebView createWebView() {
        WebView webView = new WebView(this);
        webView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        webView.addJavascriptInterface(new AdMarkerBridge(), "ViaAdMarker");
        return webView;
    }

    /** 应用设置（UA/JS/图片/夜间）。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void applySettings(WebView webView) {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(prefs.jsEnabled());
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadsImagesAutomatically(prefs.imagesEnabled());
        s.setBlockNetworkImage(!prefs.imagesEnabled());
        String host = currentHostFor(webView);
        s.setTextZoom(prefs.siteTextZoom(host, prefs.textZoom()));
        String siteUa = prefs.siteUserAgent(host);
        String customUa = !TextUtils.isEmpty(siteUa) ? siteUa : prefs.customUserAgent();
        if (!TextUtils.isEmpty(customUa)) {
            s.setUserAgentString(customUa);
        } else if (prefs.uaMode() == 0) {
            s.setUserAgentString(desktopUserAgent());
        } else {
            s.setUserAgentString(mobileUserAgent());
        }
        applyDarkMode(webView, prefs.nightMode());
    }

    private String currentHostFor(WebView webView) {
        String url = webView == null ? null : webView.getUrl();
        if (TextUtils.isEmpty(url)) {
            TabManager.Tab cur = tabs == null ? null : tabs.current();
            if (cur != null && cur.webView == webView) url = cur.url;
        }
        return hostFromUrl(url);
    }

    private String currentHost() {
        TabManager.Tab cur = tabs == null ? null : tabs.current();
        return hostFromUrl(cur == null ? null : cur.url);
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

    /** 夜间模式：优先 androidx.webkit WebSettingsCompat（API 35 上 setForceDark 已 no-op），回退反射 setForceDark，并始终注入 CSS 兜底。 */
    @SuppressLint("SetJavaScriptEnabled")
    private void applyDarkMode(WebView webView, boolean on) {
        WebSettings s = webView.getSettings();
        boolean applied = false;
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(
                    androidx.webkit.WebViewFeature.FORCE_DARK)) {
                androidx.webkit.WebSettingsCompat.setForceDark(s,
                        on ? androidx.webkit.WebSettingsCompat.FORCE_DARK_ON
                                : androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF);
                applied = true;
            }
        } catch (Exception ignored) {
        }
        if (!applied) {
            try {
                Method m = WebSettings.class.getMethod("setForceDark", int.class);
                m.invoke(s, on ? 2 /*FORCE_DARK_ON*/ : 0);
            } catch (Exception ignored) {
            }
        }
        webView.evaluateJavascript(
                "(function(){try{document.querySelectorAll('style[data-via-night]').forEach("
                        + "function(x){x.remove();});"
                        + (on ? "var css='html{background-color:#1a1a1a !important;"
                            + "color:#e0e0e0 !important;}body{background-color:#1a1a1a !important;"
                            + "color:#e0e0e0 !important;}a{color:#8ab4f8 !important;}"
                            + "img{opacity:0.85;}';var st=document.createElement('style');"
                            + "st.setAttribute('data-via-night','1');st.appendChild(document.createTextNode(css));"
                            + "document.head.appendChild(st);" : "")
                        + "}catch(e){}})();", null);
    }

    /** 为标签创建嗅探器（回调更新该标签状态与 UI）。 */
    private MediaSniffer createSniffer(TabManager.Tab tab) {
        return new MediaSniffer(new MediaSniffer.SnifferCallback() {
            @Override
            public void onMediaFound(MediaSniffer.MediaResource resource) {
                mainHandler.post(() -> {
                    TabManager.Tab cur = tabs.current();
                    if (cur == null || cur != tab) return;
                    addMediaItem(getState(cur), resource);
                });
            }

            @Override
            public void onPageStarted(String url) {
                mainHandler.post(() -> {
                    emptyHint.setVisibility(View.GONE);
                    homeScroll.setVisibility(View.GONE);
                    applyToolbarMode();
                    progressbar.setVisibility(View.VISIBLE);
                    progressbar.setProgress(0);
                    TabManager.Tab cur = tabs.current();
                    if (cur == tab) {
                        cur.url = url;
                        applySettings(cur.webView);
                        if (prefs.siteCookiesOff(hostFromUrl(url))) clearCookiesForCurrentUrl();
                        TabState st = getState(cur);
                        st.mediaResources.clear();
                        mediaList.removeAllViews();
                        st.selectedIndex = -1;
                        updateSnifferBadge(st);
                    }
                });
            }

            @Override
            public void onPageFinished(String url) {
                mainHandler.post(() -> {
                    progressbar.setVisibility(View.GONE);
                    TabManager.Tab cur = tabs.current();
                    if (cur == tab) {
                        cur.url = url;
                        urlInput.setText(url);
                        updateNavButtons(cur);
                        String title = cur.webView.getTitle();
                        if (title != null) cur.title = title;
                        TabState state = getState(cur);
                        if (state == null || !state.incognito) {
                            dbHelper.recordHistory(title, url);
                        }
                        applyDarkMode(cur.webView, prefs.nightMode());
                        applyAdBlockCss(cur.webView, url);
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
        HiddenMediaSniffer hidden = new HiddenMediaSniffer((url, mime, pageUrl, pageTitle) ->
                DownloadQueueManager.getInstance().enqueue(url, mime, pageUrl, pageTitle));
        state.adBlocker = new BrowserAdBlocker(prefs);
        WebViewClient networkLogger = new WebViewClient() {
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    synchronized (state.networkLog) {
                        if (state.networkLog.size() >= 120) state.networkLog.remove(0);
                        state.networkLog.add(request.getMethod() + " " + request.getUrl());
                    }
                }
                return null;
            }
        };
        WebViewClient viaSnifferClient = new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                viaSniffer.onPageStarted(tab.id, System.identityHashCode(view), url);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                viaSniffer.onPageCommitVisible(tab.id, System.identityHashCode(view));
                mainHandler.post(() -> {
                    syncViaSnifferMedia(tab, state);
                    if (tabs.current() == tab) updateSnifferBadge(state);
                });
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return null;
                ViaSnifferStateMachine.CaptureResult result = viaSniffer.onRequest(
                        tab.id,
                        System.identityHashCode(view),
                        request.getUrl().toString(),
                        false,
                        request.getRequestHeaders());
                if (result.showSnifferButton()) {
                    mainHandler.post(() -> {
                        syncViaSnifferMedia(tab, state);
                        if (tabs.current() == tab) updateSnifferBadge(state);
                    });
                }
                return null;
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
        tab.webView.setWebViewClient(new CompositeWebViewClient(
                state.sniffer, hidden, state.adBlocker, networkLogger, viaSnifferClient));
        tab.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mainHandler.post(() -> {
                    if (newProgress < 100) {
                        progressbar.setVisibility(View.VISIBLE);
                        progressbar.setProgress(newProgress);
                    } else {
                        progressbar.setVisibility(View.GONE);
                    }
                });
            }
        });
    }

    /** 切换显示的标签 WebView。 */
    private void showTab(TabManager.Tab tab) {
        webContainer.removeAllViews();
        webContainer.addView(tab.webView);
        // 重新挂载主页容器（removeAllViews 会移除它）
        webContainer.addView(homeScroll);
        TabState st = getState(tab);
        urlInput.setText(tab.url != null ? tab.url : "");
        refreshSnifferPanel(st);
        updateNavButtons(tab);
        boolean atHome = tab.url == null || tab.url.isEmpty();
        homeScroll.setVisibility(atHome ? View.VISIBLE : View.GONE);
        emptyHint.setVisibility(View.GONE);
        if (atHome) renderHomeGrid();
    }

    private void updateTabBadge() {
        int n = tabs.size();
        tabBadge.setText(n > 99 ? "99+" : String.valueOf(n));
        String desc = n == 1 ? "1" : getString(R.string.via_tab_count_format, n);
        tabBadge.setContentDescription(desc);
        tabsButtonContainer.setContentDescription(desc);
    }

    private void updateNavButtons(TabManager.Tab tab) {
        boolean canBack = tab != null && tab.webView.canGoBack();
        boolean canFwd = tab != null && tab.webView.canGoForward();
        // VIA 的后退/前进区域始终保持 5 等分完整点击区；无历史时只降低透明度，点击 no-op。
        navBackButton.setEnabled(true);
        navForwardButton.setEnabled(true);
        navBackButton.setAlpha(canBack ? 1.0f : 0.4f);
        navForwardButton.setAlpha(canFwd ? 1.0f : 0.4f);
    }

    /** 从地址栏加载 URL（智能识别）。 */
    private void loadUrlFromInput() {
        String input = urlInput.getText().toString().trim();
        ViaAddressResolver.Resolution resolution = ViaAddressResolver.resolve(
                input,
                SearchEngines.prefix(prefs, prefs.searchEngine()),
                false);
        if (resolution.getKind() == ViaAddressResolver.Kind.EMPTY
                || resolution.getOutput() == null
                || resolution.getOutput().isEmpty()) {
            Toast.makeText(this, R.string.browser_url_hint, Toast.LENGTH_SHORT).show();
            return;
        }
        String url = resolution.getOutput();
        TabManager.Tab cur = tabs.current();
        if (cur == null) {
            newTab(url);
        } else {
            urlInput.setText(url);
            cur.url = url;
            homeScroll.setVisibility(View.GONE);
            cur.webView.loadUrl(url);
        }
    }

    /** 添加媒体项到当前标签列表。 */
    private void addMediaItem(TabState st, MediaSniffer.MediaResource resource) {
        for (MediaSniffer.MediaResource e : st.mediaResources) {
            if (e.url.equals(resource.url)) return;
        }
        st.mediaResources.add(resource);

        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(android.view.Gravity.CENTER_VERTICAL);
        item.setPadding(
                getResources().getDimensionPixelSize(R.dimen.space_sm),
                getResources().getDimensionPixelSize(R.dimen.space_sm),
                getResources().getDimensionPixelSize(R.dimen.space_sm),
                getResources().getDimensionPixelSize(R.dimen.space_sm));

        CheckBox checkBox = new CheckBox(this);
        checkBox.setTag(st.mediaResources.size() - 1);
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            int idx = (Integer) button.getTag();
            if (checked) {
                for (int i = 0; i < mediaList.getChildCount(); i++) {
                    View child = mediaList.getChildAt(i);
                    if (child instanceof LinearLayout) {
                        CheckBox cb = (CheckBox) ((LinearLayout) child).getChildAt(0);
                        if (cb != null && cb.getTag() != null
                                && (Integer) cb.getTag() != idx && cb.isChecked()) {
                            cb.setChecked(false);
                        }
                    }
                }
                st.selectedIndex = idx;
            } else if (st.selectedIndex == idx) {
                st.selectedIndex = -1;
            }
        });

        TextView label = new TextView(this);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        label.setText(resource.getDisplayTitle());
        label.setTextColor(getResources().getColor(R.color.text_secondary));
        label.setTextSize(13);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(getResources().getDimensionPixelSize(R.dimen.space_sm), 0, 0, 0);

        item.addView(checkBox);
        item.addView(label);
        mediaList.addView(item);

        if (st.mediaResources.size() == 1) {
            snifferPanel.setVisibility(View.VISIBLE);
        }
        updateSnifferBadge(st);
    }

    private void syncViaSnifferMedia(TabManager.Tab tab, TabState st) {
        if (tab == null || st == null) return;
        List<String> urls = viaSniffer.mediaUrls(tab.id);
        if (urls.isEmpty()) return;
        for (String url : urls) {
            boolean exists = false;
            for (MediaSniffer.MediaResource resource : st.mediaResources) {
                if (resource.url.equals(url)) { exists = true; break; }
            }
            if (!exists) {
                addMediaItem(st, MediaSniffer.MediaResource.fromSniffedUrl(url, tab.url));
            }
        }
    }

    private void updateSnifferBadge(TabState st) {
        TabManager.Tab cur = tabs.current();
        // VIA：仅在页面 commit 且存在 mediaLike 候选、且非不支持站点时显示角标
        if (cur == null || st == null
                || !prefs.autoSnifferButton()
                || !viaSniffer.shouldShowButton(cur.id)) {
            snifferCount.setVisibility(View.GONE);
            return;
        }
        int count = Math.max(st.mediaResources.size(), viaSniffer.mediaUrls(cur.id).size());
        if (count == 0) {
            snifferCount.setVisibility(View.GONE);
        } else {
            snifferCount.setVisibility(View.VISIBLE);
            snifferCount.setText(getString(R.string.browser_sniffer_count_format, count));
        }
    }

    /** 切换标签时刷新嗅探面板。 */
    private void refreshSnifferPanel(TabState st) {
        mediaList.removeAllViews();
        for (int i = 0; i < st.mediaResources.size(); i++) {
            addMediaItemSilent(st, st.mediaResources.get(i), i);
        }
        updateSnifferBadge(st);
    }

    private void addMediaItemSilent(TabState st, MediaSniffer.MediaResource resource, int idx) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(android.view.Gravity.CENTER_VERTICAL);
        item.setPadding(
                getResources().getDimensionPixelSize(R.dimen.space_sm),
                getResources().getDimensionPixelSize(R.dimen.space_sm),
                getResources().getDimensionPixelSize(R.dimen.space_sm),
                getResources().getDimensionPixelSize(R.dimen.space_sm));
        CheckBox checkBox = new CheckBox(this);
        checkBox.setTag(idx);
        checkBox.setChecked(st.selectedIndex == idx);
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            int i = (Integer) button.getTag();
            if (checked) {
                for (int j = 0; j < mediaList.getChildCount(); j++) {
                    View child = mediaList.getChildAt(j);
                    if (child instanceof LinearLayout) {
                        CheckBox cb = (CheckBox) ((LinearLayout) child).getChildAt(0);
                        if (cb != null && cb.getTag() != null
                                && (Integer) cb.getTag() != i && cb.isChecked()) {
                            cb.setChecked(false);
                        }
                    }
                }
                st.selectedIndex = i;
            } else if (st.selectedIndex == i) {
                st.selectedIndex = -1;
            }
        });
        TextView label = new TextView(this);
        label.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        label.setText(resource.getDisplayTitle());
        label.setTextColor(getResources().getColor(R.color.text_secondary));
        label.setTextSize(13);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(getResources().getDimensionPixelSize(R.dimen.space_sm), 0, 0, 0);
        item.addView(checkBox);
        item.addView(label);
        mediaList.addView(item);
    }

    /** 下载选中的媒体。 */
    private void onDownloadSelected() {
        TabManager.Tab cur = tabs.current();
        if (cur == null) return;
        TabState st = getState(cur);
        if (st.selectedIndex < 0 || st.selectedIndex >= st.mediaResources.size()) {
            Toast.makeText(this, R.string.browser_no_media_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        MediaSniffer.MediaResource resource = st.mediaResources.get(st.selectedIndex);
        String title = cur.webView.getTitle();
        String baseName = (title != null && !title.isEmpty())
                ? title : "sniffed_" + System.currentTimeMillis();
        String fileName = UniversalDownloadManager.sanitizeFileName(baseName + "." + resource.ext);
        File outFile = new File(getDownloadDir(), fileName);

        downloadButton.setEnabled(false);
        downloadButton.setText(R.string.browser_downloading_sniffed);

        final String mediaUrl = resource.url;
        executor.execute(() -> {
            try {
                downloadManager = new UniversalDownloadManager();
                downloadManager.downloadSmart(mediaUrl, outFile, new DownloadProgressCallback() {
                    @Override
                    public void onProgress(long downloadedBytes, long totalBytes,
                                           long speedBps, int percent) {
                        mainHandler.post(() -> {
                            if (percent >= 0) downloadButton.setText(percent + "%");
                        });
                    }

                    @Override
                    public void onStatusChanged(String status, String message) {
                    }

                    @Override
                    public void onComplete(String path) {
                        mainHandler.post(() -> {
                            downloadButton.setEnabled(true);
                            downloadButton.setText(R.string.browser_download_selected);
                            Toast.makeText(BrowserActivity.this,
                                    getString(R.string.browser_download_done, path),
                                    Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onError(String errorCode, String message) {
                        mainHandler.post(() -> {
                            downloadButton.setEnabled(true);
                            downloadButton.setText(R.string.browser_download_selected);
                            Toast.makeText(BrowserActivity.this,
                                    getString(R.string.browser_download_failed, message),
                                    Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } catch (IOException e) {
                Log.e(TAG, "下载失败", e);
                mainHandler.post(() -> {
                    downloadButton.setEnabled(true);
                    downloadButton.setText(R.string.browser_download_selected);
                    Toast.makeText(BrowserActivity.this,
                            getString(R.string.browser_download_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

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
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        IntentResult scan = IntentIntegrator.parseActivityResult(
                requestCode, resultCode, data);
        if (scan != null) {
            if (scan.getContents() == null) {
                Toast.makeText(this, R.string.via_scan_cancelled,
                        Toast.LENGTH_SHORT).show();
            } else {
                urlInput.setText(scan.getContents().trim());
                loadUrlFromInput();
            }
            return;
        }
        if (requestCode == REQ_TABS && resultCode == RESULT_OK && data != null) {
            int action = data.getIntExtra(TabSnapshotHolder.EXTRA_ACTION, -1);
            int index = data.getIntExtra(TabSnapshotHolder.EXTRA_INDEX, -1);
            handleTabAction(action, index);
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQ_SETTINGS) {
                TabManager.Tab cur = tabs.current();
                if (cur != null) applySettings(cur.webView);
            }
            return;
        }
        if (requestCode == REQ_BOOKMARKS || requestCode == REQ_HISTORY || requestCode == REQ_OFFLINE) {
            String url = data.getStringExtra("url");
            if (url != null && !url.isEmpty()) {
                TabManager.Tab cur = tabs.current();
                if (cur == null) newTab(url);
                else {
                    cur.url = url;
                    homeScroll.setVisibility(View.GONE);
                    cur.webView.loadUrl(url);
                }
            }
        } else if (requestCode == REQ_SETTINGS) {
            if (data != null
                    && data.getBooleanExtra(BrowserSettingsActivity.EXTRA_OPEN_CUSTOMIZER, false)) {
                BrowserBottomMenu.showCustomizer(this, prefs, buildMenuEntries());
            }
            TabManager.Tab cur = tabs.current();
            if (cur != null) applySettings(cur.webView);
            for (TabManager.Tab tab : tabs.all()) {
                applySettings(tab.webView);
            }
            WebView.setWebContentsDebuggingEnabled(prefs.webDebug());
        }
    }

    private void handleTabAction(int action, int index) {
        if (action == TabSnapshotHolder.ACTION_NEW) {
            newTab("");
            showHome();
        } else if (action == TabSnapshotHolder.ACTION_SWITCH) {
            tabs.select(index);
            TabManager.Tab cur = tabs.current();
            if (cur != null) showTab(cur);
            updateTabBadge();
        } else if (action == TabSnapshotHolder.ACTION_CLOSE) {
            TabManager.Tab closed = closeAndDestroyTab(index);
            if (closed == null) {
                newTab(prefs.homeUrl());
            } else {
                showTab(closed);
            }
            updateTabBadge();
        }
    }

    @Override
    public void onBackPressed() {
        if (snifferPanel.getVisibility() == View.VISIBLE) {
            snifferPanel.setVisibility(View.GONE);
            return;
        }
        TabManager.Tab cur = tabs.current();
        if (cur != null && cur.webView.canGoBack()) {
            cur.webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadManager != null) downloadManager.cancel();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        executor.shutdownNow();
        for (TabManager.Tab t : tabs.all()) {
            try {
                t.webView.stopLoading();
                t.webView.setWebViewClient(null);
                t.webView.setWebChromeClient(null);
                t.webView.destroy();
            } catch (Exception ignored) {
            }
        }
    }
}

