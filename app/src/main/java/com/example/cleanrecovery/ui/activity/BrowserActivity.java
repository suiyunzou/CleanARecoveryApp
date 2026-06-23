package com.example.cleanrecovery.ui.activity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 内置浏览器 Activity（借鉴 Via 浏览器的资源嗅探设计）。
 *
 * <p><b>核心功能</b>：通过 WebView 加载视频网站（如 YouTube），利用
 * {@link MediaSniffer} 拦截网络请求，嗅探视频/音频流直链，然后复用
 * {@link UniversalDownloadManager} 下载。</p>
 *
 * <p><b>解决 YouTube PO Token 问题</b>：WebView 是真实浏览器环境，
 * YouTube 的 JavaScript 会自动执行并计算 PO Token，服务端不会触发
 * "Sign in to confirm you're not a bot" 反机器人检测。</p>
 *
 * <p><b>工作流程</b>：</p>
 * <ol>
 *   <li>用户在地址栏输入 URL（或搜索关键词）</li>
 *   <li>WebView 加载页面，{@link MediaSniffer} 拦截所有网络请求</li>
 *   <li>识别到媒体流（googlevideo.com / .m3u8 / .mp4 等）时，加入嗅探列表</li>
 *   <li>用户播放视频以触发更多流请求（不同码率）</li>
 *   <li>用户选择媒体项，点击下载，调用 {@link UniversalDownloadManager#downloadSmart}</li>
 * </ol>
 */
public final class BrowserActivity extends Activity {

    private static final String TAG = "BrowserActivity";

    /** 默认首页（YouTube 搜索）。 */
    private static final String DEFAULT_HOME = "https://www.youtube.com";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private WebView webView;
    private EditText urlInput;
    private ImageButton goButton;
    private ImageButton navBackButton;
    private ImageButton navForwardButton;
    private ImageButton refreshButton;
    private ProgressBar progressbar;
    private TextView emptyHint;
    private TextView snifferCount;

    private LinearLayout snifferPanel;
    private LinearLayout mediaList;
    private TextView snifferHint;
    private Button downloadButton;
    private ImageButton snifferCloseButton;

    /** 媒体嗅探器。 */
    private MediaSniffer sniffer;
    /** 隐藏嗅探器（后台下载模块，无 UI 回调）。 */
    private HiddenMediaSniffer hiddenSniffer;
    /** 已嗅探到的媒体资源列表。 */
    private final List<MediaSniffer.MediaResource> mediaResources = new ArrayList<>();
    /** 当前选中的媒体索引。 */
    private int selectedIndex = -1;
    /** 下载管理器。 */
    private UniversalDownloadManager downloadManager;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_browser);

        // 返回按钮
        ImageButton backButton = findViewById(R.id.browser_back_button);
        backButton.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
        });

        urlInput = findViewById(R.id.browser_url_input);
        goButton = findViewById(R.id.browser_go_button);
        navBackButton = findViewById(R.id.browser_nav_back);
        navForwardButton = findViewById(R.id.browser_nav_forward);
        refreshButton = findViewById(R.id.browser_refresh);
        progressbar = findViewById(R.id.browser_progress);
        emptyHint = findViewById(R.id.browser_empty_hint);
        snifferCount = findViewById(R.id.browser_sniffer_count);

        snifferPanel = findViewById(R.id.browser_sniffer_panel);
        mediaList = findViewById(R.id.browser_media_list);
        snifferHint = findViewById(R.id.browser_sniffer_hint);
        downloadButton = findViewById(R.id.browser_download_all);
        snifferCloseButton = findViewById(R.id.browser_sniffer_close);

        webView = findViewById(R.id.browser_webview);

        // 配置 WebView（启用 JS、DOM Storage、媒体播放）
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(false);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // 使用桌面 UA 以获取完整版 YouTube 页面（含更多格式，且使用 HLS 而非 MSE）
        settings.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        // 初始化媒体嗅探器
        sniffer = new MediaSniffer(new MediaSniffer.SnifferCallback() {
            @Override
            public void onMediaFound(MediaSniffer.MediaResource resource) {
                mainHandler.post(() -> addMediaItem(resource));
            }

            @Override
            public void onPageStarted(String url) {
                mainHandler.post(() -> {
                    emptyHint.setVisibility(View.GONE);
                    progressbar.setVisibility(View.VISIBLE);
                    progressbar.setProgress(0);
                    // 切换页面时清空旧嗅探结果
                    mediaResources.clear();
                    mediaList.removeAllViews();
                    selectedIndex = -1;
                    updateSnifferBadge();
                });
            }

            @Override
            public void onPageFinished(String url) {
                mainHandler.post(() -> {
                    progressbar.setVisibility(View.GONE);
                    urlInput.setText(url);
                    updateNavButtons();
                });
            }
        });

        // 初始化隐藏嗅探器（后台下载模块，无 UI 回调，直接入队下载）
        hiddenSniffer = new HiddenMediaSniffer((url, mime, pageUrl, pageTitle) -> {
            // 静默入队后台下载（不更新 UI）
            DownloadQueueManager.getInstance().enqueue(url, mime, pageUrl, pageTitle);
        });

        // 启动后台下载服务
        try {
            startService(new Intent(this, BackgroundDownloadService.class));
        } catch (Exception e) {
            Log.w(TAG, "后台下载服务启动失败: " + e.getMessage());
        }

        // 组合两个嗅探器（可见嗅探器 + 隐藏嗅探器）
        webView.setWebViewClient(new CompositeWebViewClient(sniffer, hiddenSniffer));

        // 进度条
        webView.setWebChromeClient(new WebChromeClient() {
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

            @Override
            public void onReceivedTitle(WebView view, String title) {
                // 可用于自动命名下载文件
            }
        });

        // 地址栏回车
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                loadUrlFromInput();
                return true;
            }
            return false;
        });

        goButton.setOnClickListener(v -> loadUrlFromInput());
        navBackButton.setOnClickListener(v -> {
            if (webView.canGoBack()) webView.goBack();
        });
        navForwardButton.setOnClickListener(v -> {
            if (webView.canGoForward()) webView.goForward();
        });
        refreshButton.setOnClickListener(v -> webView.reload());

        snifferCloseButton.setOnClickListener(v -> {
            snifferPanel.setVisibility(View.GONE);
        });

        downloadButton.setOnClickListener(v -> onDownloadSelected());

        // 加载默认首页
        String initialUrl = getIntent().getStringExtra("url");
        if (initialUrl == null || initialUrl.isEmpty()) {
            initialUrl = DEFAULT_HOME;
        }
        urlInput.setText(initialUrl);
        webView.loadUrl(initialUrl);
    }

    /** 从地址栏加载 URL（支持搜索关键词）。 */
    private void loadUrlFromInput() {
        String input = urlInput.getText().toString().trim();
        if (input.isEmpty()) {
            Toast.makeText(this, R.string.browser_url_hint, Toast.LENGTH_SHORT).show();
            return;
        }

        String url;
        if (input.startsWith("http://") || input.startsWith("https://")) {
            url = input;
        } else if (input.contains(".") && !input.contains(" ")) {
            // 看起来像域名
            url = "https://" + input;
        } else {
            // 当作搜索关键词，用 YouTube 搜索
            url = "https://www.youtube.com/results?search_query="
                    + java.net.URLEncoder.encode(input, java.nio.charset.StandardCharsets.UTF_8);
        }

        urlInput.setText(url);
        webView.loadUrl(url);
    }

    /** 添加一个嗅探到的媒体项到列表。 */
    private void addMediaItem(MediaSniffer.MediaResource resource) {
        // 去重
        for (MediaSniffer.MediaResource existing : mediaResources) {
            if (existing.url.equals(resource.url)) return;
        }
        mediaResources.add(resource);

        // 创建列表项视图
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.HORIZONTAL);
        item.setGravity(android.view.Gravity.CENTER_VERTICAL);
        item.setPadding(
                getResources().getDimensionPixelSize(R.dimen.space_sm),
                getResources().getDimensionPixelSize(R.dimen.space_sm),
                getResources().getDimensionPixelSize(R.dimen.space_sm),
                getResources().getDimensionPixelSize(R.dimen.space_sm));

        CheckBox checkBox = new CheckBox(this);
        checkBox.setTag(mediaResources.size() - 1);
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            int idx = (Integer) button.getTag();
            if (checked) {
                // 单选模式：取消其他选中
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
                selectedIndex = idx;
            } else if (selectedIndex == idx) {
                selectedIndex = -1;
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

        // 首次发现媒体时自动展开面板
        if (mediaResources.size() == 1) {
            snifferPanel.setVisibility(View.VISIBLE);
        }

        updateSnifferBadge();
    }

    /** 更新嗅探计数徽章。 */
    private void updateSnifferBadge() {
        int count = mediaResources.size();
        if (count == 0) {
            snifferCount.setVisibility(View.GONE);
        } else {
            snifferCount.setVisibility(View.VISIBLE);
            snifferCount.setText(getString(R.string.browser_sniffer_count_format, count));
        }
    }

    /** 更新导航按钮状态。 */
    private void updateNavButtons() {
        navBackButton.setEnabled(webView.canGoBack());
        navForwardButton.setEnabled(webView.canGoForward());
        navBackButton.setAlpha(webView.canGoBack() ? 1.0f : 0.4f);
        navForwardButton.setAlpha(webView.canGoForward() ? 1.0f : 0.4f);
    }

    /** 下载选中的媒体。 */
    private void onDownloadSelected() {
        if (selectedIndex < 0 || selectedIndex >= mediaResources.size()) {
            Toast.makeText(this, R.string.browser_no_media_selected, Toast.LENGTH_SHORT).show();
            return;
        }

        MediaSniffer.MediaResource resource = mediaResources.get(selectedIndex);
        Log.i(TAG, "开始下载嗅探到的媒体: " + resource.getDisplayTitle());

        // 生成文件名
        String title = webView.getTitle();
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
                            if (percent >= 0) {
                                downloadButton.setText(percent + "%");
                            }
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

    /** 获取下载目录。 */
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
    public void onBackPressed() {
        if (snifferPanel.getVisibility() == View.VISIBLE) {
            snifferPanel.setVisibility(View.GONE);
            return;
        }
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadManager != null) {
            downloadManager.cancel();
        }
        executor.shutdownNow();
        // 清理 WebView
        if (webView != null) {
            webView.stopLoading();
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
        }
    }
}
