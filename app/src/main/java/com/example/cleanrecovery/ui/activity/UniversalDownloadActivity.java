package com.example.cleanrecovery.ui.activity;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.download.DownloadProgressCallback;
import com.example.cleanrecovery.download.UniversalDownloadManager;
import com.example.cleanrecovery.ui.browser.BrowserPrefs;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import com.example.cleanrecovery.ui.widget.GlassToast;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.cleanrecovery.extractor.Extractor;
import com.example.cleanrecovery.extractor.ExtractorException;
import com.example.cleanrecovery.extractor.ExtractorHttp;
import com.example.cleanrecovery.extractor.ExtractorRegistry;
import com.example.cleanrecovery.extractor.ExtractorResult;
import com.example.cleanrecovery.extractor.DouyinExtractor;
import com.example.cleanrecovery.extractor.GenericExtractor;
import com.example.cleanrecovery.extractor.JsRendererExtractor;
import com.example.cleanrecovery.ytdlp.MediaMuxerUtil;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 全网下载页面：用户输入网址 → 选择画质 → 下载到 DataRecovery/Downloads/。
 *
 * <p>采用 yt-dlp 风格的 Extractor 架构，直接调用目标平台的官方 API 获取直链，
 * 不依赖任何第三方中转服务器（如 cobalt）。</p>
 *
 * <p>核心流程（对应 yt-dlp 三阶段架构）：</p>
 * <ol>
 *   <li><b>Extractor 提取直链</b>：{@link ExtractorRegistry#match(String)} 根据 URL
 *       匹配专用提取器（Bilibili/抖音/TikTok），无匹配时回退到 GenericExtractor</li>
 *   <li><b>选择画质</b>：从提取结果中按用户选择的画质筛选 Format</li>
 *   <li><b>Downloader 下载</b>：{@link UniversalDownloadManager} 执行断点续传下载</li>
 * </ol>
 *
 * <p>提取器实现参照 yt-dlp 源码：</p>
 * <ul>
 *   <li>{@code extractor/bilibili.py} → {@link com.example.cleanrecovery.extractor.BilibiliExtractor}</li>
 *   <li>{@code extractor/tiktok.py} → {@link com.example.cleanrecovery.extractor.DouyinExtractor}
 *       / {@link com.example.cleanrecovery.extractor.TikTokExtractor}</li>
 *   <li>{@code extractor/generic.py} → {@link com.example.cleanrecovery.extractor.GenericExtractor}</li>
 * </ul>
 */
public final class UniversalDownloadActivity extends Activity {

    private static final String TAG = "UniversalDownload";

    /** Intent extra：直接传入待下载 URL，便于外部调用与测试。 */
    public static final String EXTRA_URL = "extra_url";

    /** 下载完成通知的通道 ID。 */
    private static final String CHANNEL_ID_DOWNLOAD = "download_status";
    private static final int NOTIFICATION_ID_DOWNLOAD = 1001;

    /** 动态画质/音频候选（按解析结果渲染，对应 yt-dlp --list-formats；替代写死档位）。 */
    private final List<ExtractorResult.Format> videoChoices = new ArrayList<>();
    private final List<ExtractorResult.Format> audioChoices = new ArrayList<>();

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EditText urlInput;
    private Button downloadButton;
    private LinearLayout qualityCard;
    private RadioGroup videoGroup;
    private RadioGroup audioGroup;
    private Button startDownloadButton;
    private LinearLayout statusCard;
    private TextView statusMessage;
    private ProgressBar progressBar;
    private TextView progressText;
    private TextView speedText;
    private LinearLayout actionButtons;
    private Button pauseButton;
    private Button cancelButton;

    /** 下载管理器（集成断点续传/重试/动态块大小，借鉴 yt-dlp HttpFD）。 */
    private UniversalDownloadManager downloadManager;
    private volatile boolean downloadInProgress = false;

    /** 用户输入的待下载 URL。 */
    private String pendingUrl;
    /** 上次提取的结果（用于画质选择后定位 Format）。 */
    private ExtractorResult pendingResult;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_universal_download);

        // 创建下载通知通道（Android 8.0+ 需要）
        createDownloadNotificationChannel();
        // P0 A5：请求运行时权限（通知 + 媒体读取 + 全文件访问）
        requestRuntimePermissions();
        // P0 A5：检查并引导用户授予"所有文件访问"权限（Android 11+，写共享存储必需）
        requestAllFilesAccessIfNeeded();

        // 注册 HLS 与 JS 渲染钩子（让 GenericExtractor 能处理 m3u8 与 SPA 页面）
        registerExtractorHooks();
        // 抖音 Cookie 预热钩子：无会话 Cookie 时用隐藏 WebView 完成 JS 质询后自动重试
        DouyinExtractor.setCookieBootstrapper(this::bootstrapDouyinCookies);

        ImageButton backButton = findViewById(R.id.universal_back_button);
        backButton.setOnClickListener(v -> finish());

        urlInput = findViewById(R.id.universal_url_input);
        downloadButton = findViewById(R.id.universal_download_button);
        qualityCard = findViewById(R.id.universal_quality_card);
        videoGroup = findViewById(R.id.universal_quality_video_group);
        audioGroup = findViewById(R.id.universal_quality_audio_group);
        startDownloadButton = findViewById(R.id.universal_start_download_button);
        statusCard = findViewById(R.id.universal_status_card);
        statusMessage = findViewById(R.id.universal_status_message);
        progressBar = findViewById(R.id.universal_progress_bar);
        progressText = findViewById(R.id.universal_progress_text);
        speedText = findViewById(R.id.universal_speed_text);
        actionButtons = findViewById(R.id.universal_action_buttons);
        pauseButton = findViewById(R.id.universal_pause_button);
        cancelButton = findViewById(R.id.universal_cancel_button);

        // 点击下载按钮：校验 URL 并提取直链
        downloadButton.setOnClickListener(v -> onDownloadClicked());
        // 选择画质后开始下载
        startDownloadButton.setOnClickListener(v -> onQualitySelected());
        // 暂停/继续/取消
        pauseButton.setOnClickListener(v -> onPauseClicked());
        cancelButton.setOnClickListener(v -> onCancelClicked());

        // 支持通过 Intent extra 传入 URL（便于测试与外部调用）
        String extraUrl = getIntent().getStringExtra(EXTRA_URL);
        if (extraUrl != null && !extraUrl.isEmpty()) {
            urlInput.setText(extraUrl);
            onDownloadClicked();
        }
    }

    /**
     * 注册 HLS 与 JS 渲染钩子到 GenericExtractor。
     *
     * <p>HLS 钩子：将 m3u8 链接包装为 {@link ExtractorResult}，下载阶段由
     * {@link UniversalDownloadManager#downloadSmart} 自动路由到 HLS 下载器。</p>
     *
     * <p>JS 渲染钩子：使用 {@link JsRendererExtractor}（基于 WebView）渲染
     * SPA 页面并提取媒体链接。需在主线程创建 WebView，故延迟到首次使用时初始化。</p>
     */
    private void registerExtractorHooks() {
        // HLS 钩子：返回包含 m3u8 URL 的 ExtractorResult，下载时由 downloadSmart 路由
        GenericExtractor.setHlsHook((m3u8Url, title) -> {
            String safeTitle = title != null ? title : "hls_stream";
            ExtractorResult.Format fmt = new ExtractorResult.Format(
                    m3u8Url, "ts", 0, "hls", "aac", 0, 0, 0, 0, "HLS");
            return new ExtractorResult(safeTitle, safeTitle, null,
                    java.util.Collections.singletonList(fmt),
                    UniversalDownloadManager.sanitizeFileName(safeTitle), "ts");
        });

        // JS 渲染钩子：懒加载 JsRendererExtractor
        GenericExtractor.setJsHook(pageUrl -> {
            JsRendererExtractor renderer = new JsRendererExtractor(this);
            JsRendererExtractor.RenderResult rr = renderer.renderAndExtract(pageUrl);
            return rr.mediaUrls;
        });
    }

    /** 第一步：校验 URL，后台调用 Extractor 提取直链。 */
    private void onDownloadClicked() {
        String url = urlInput.getText().toString().trim();
        if (url.isEmpty()) {
            GlassToast.makeText(this, R.string.universal_download_url_empty,
                    GlassToast.LENGTH_SHORT).show();
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
            urlInput.setText(url);
        }
        pendingUrl = url;

        // 隐藏画质卡片，展示状态卡片
        qualityCard.setVisibility(View.GONE);
        statusCard.setVisibility(View.VISIBLE);
        showStatus(getString(R.string.universal_download_resolving), 0, true);

        // 后台调用 Extractor 提取直链（对应 yt-dlp extract_info）
        final String finalUrl = url;
        executor.execute(() -> extractAndShowQuality(finalUrl));
    }

    /**
     * 调用 Extractor 提取直链，成功后展示画质选择。
     *
     * <p>对应 yt-dlp {@code YoutubeDL.extract_info(url)}：
     * 根据 URL 匹配提取器，执行 {@code _real_extract}，返回 info_dict。</p>
     */
    private void extractAndShowQuality(String url) {
        try {
            Extractor extractor = ExtractorRegistry.match(url);
            Log.d(TAG, "matched extractor: " + extractor.name() + " for " + url);

            final ExtractorResult result = extractor.extract(url);
            pendingResult = result;

            mainHandler.post(() -> {
                // 展示画质选择卡片
                statusCard.setVisibility(View.GONE);
                qualityCard.setVisibility(View.VISIBLE);
                videoGroup.removeAllViews();
                audioGroup.removeAllViews();
                buildQualityOptions(result);
                GlassToast.makeText(this, R.string.universal_download_select_quality,
                        GlassToast.LENGTH_SHORT).show();
            });

        } catch (ExtractorException e) {
            Log.e(TAG, "extract failed: " + e.getKind(), e);
            final String msg = mapExtractorError(e);
            mainHandler.post(() -> {
                showStatus(getString(R.string.universal_download_resolve_failed, msg), 0, false);
                GlassToast.makeText(this, msg, GlassToast.LENGTH_LONG).show();
            });
        } catch (IOException e) {
            Log.e(TAG, "network error", e);
            mainHandler.post(() -> showStatus(
                    getString(R.string.universal_download_failed, e.getMessage()), 0, false));
        } catch (Exception e) {
            Log.e(TAG, "unexpected error", e);
            mainHandler.post(() -> showStatus(
                    getString(R.string.universal_download_failed, e.getMessage()), 0, false));
        }
    }

    /** 根据提取结果动态构建画质选项（对应 yt-dlp --list-formats，只列真实存在的格式）。 */
    private void buildQualityOptions(ExtractorResult result) {
        videoChoices.clear();
        audioChoices.clear();
        videoGroup.removeAllViews();
        audioGroup.removeAllViews();

        // 视频候选：非纯音频格式按高度去重（同高度取码率最高），高度降序
        Map<Integer, ExtractorResult.Format> byHeight = new LinkedHashMap<>();
        for (ExtractorResult.Format f : result.getFormats()) {
            if (f.isAudioOnly()) continue;
            int key = f.height > 0 ? f.height : f.quality;
            ExtractorResult.Format prev = byHeight.get(key);
            if (prev == null || f.tbr > prev.tbr) byHeight.put(key, f);
        }
        videoChoices.addAll(byHeight.values());
        Collections.sort(videoChoices, (a, b) -> Integer.compare(heightKey(b), heightKey(a)));

        // 音频候选：码率降序
        for (ExtractorResult.Format f : result.getFormats()) {
            if (f.isAudioOnly()) audioChoices.add(f);
        }
        Collections.sort(audioChoices, (a, b) -> Integer.compare(b.tbr, a.tbr));

        if (!videoChoices.isEmpty()) {
            for (int i = 0; i < videoChoices.size(); i++) {
                ExtractorResult.Format f = videoChoices.get(i);
                RadioButton rb = new RadioButton(this);
                rb.setId(10_000 + i);
                rb.setText(f.description != null && !f.description.isEmpty()
                        ? f.description : (f.height > 0 ? f.height + "p" : "视频"));
                rb.setTextColor(getResources().getColor(R.color.text_secondary));
                rb.setTextSize(14);
                rb.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_xs), 0, 0);
                videoGroup.addView(rb);
            }
            // 默认选 H.264/MP4（MediaMuxer 合并与机型兼容性最稳），无则选最高
            int defaultIdx = 0;
            for (int i = 0; i < videoChoices.size(); i++) {
                ExtractorResult.Format f = videoChoices.get(i);
                if ("mp4".equals(f.ext) || (f.acodec != null && f.vcodec != null
                        && f.vcodec.startsWith("avc1"))) {
                    defaultIdx = i;
                    break;
                }
            }
            videoGroup.check(videoGroup.getChildAt(defaultIdx).getId());
        } else {
            TextView label = new TextView(this);
            label.setText("该链接仅提供音频");
            label.setTextColor(getResources().getColor(R.color.text_muted));
            label.setTextSize(14);
            videoGroup.addView(label);
        }

        if (!audioChoices.isEmpty()) {
            for (int i = 0; i < audioChoices.size(); i++) {
                ExtractorResult.Format f = audioChoices.get(i);
                RadioButton rb = new RadioButton(this);
                rb.setId(20_000 + i);
                rb.setText(f.description != null && !f.description.isEmpty() ? f.description : "音频");
                rb.setTextColor(getResources().getColor(R.color.text_secondary));
                rb.setTextSize(14);
                rb.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_xs), 0, 0);
                audioGroup.addView(rb);
            }
        }
    }

    private static int heightKey(ExtractorResult.Format f) {
        return f.height > 0 ? f.height : f.quality;
    }

    /** 第二步：用户选定画质后，下载对应格式。 */
    private void onQualitySelected() {
        if (pendingUrl == null || pendingResult == null) return;

        int videoId = videoGroup.getCheckedRadioButtonId();
        int audioId = audioGroup.getCheckedRadioButtonId();

        if (videoId == -1 && audioId == -1) {
            GlassToast.makeText(this, R.string.universal_download_select_quality,
                    GlassToast.LENGTH_SHORT).show();
            return;
        }

        // 选择目标 Format（对应 yt-dlp format selection；选项即真实格式，无需就近换算）
        ExtractorResult.Format targetFormat = null;
        ExtractorResult.Format mergeAudio = null;
        boolean audioOnly = false;

        if (audioId != -1) {
            // 纯音频模式：用户点选了具体音轨
            audioOnly = true;
            targetFormat = audioChoices.get(audioId - 20_000);
        } else {
            // 视频模式：直接使用所选画质对应的真实格式
            targetFormat = videoChoices.get(videoId - 10_000);
            // DASH 分离流：所选为纯视频轨 → 附带可合并的音轨（对应 yt-dlp bv*+ba）
            if (targetFormat.isVideoOnly()) {
                mergeAudio = pickMergeableAudio();
            }
        }

        if (targetFormat == null) {
            GlassToast.makeText(this, R.string.universal_download_select_quality,
                    GlassToast.LENGTH_SHORT).show();
            return;
        }

        qualityCard.setVisibility(View.GONE);
        statusCard.setVisibility(View.VISIBLE);
        showStatus(getString(R.string.universal_download_downloading), 0, true);

        final ExtractorResult.Format finalFormat = targetFormat;
        final ExtractorResult.Format finalAudio = mergeAudio;
        final boolean finalAudioOnly = audioOnly;
        executor.execute(() -> downloadFormat(finalFormat, finalAudioOnly, finalAudio));
    }

    /** 挑选可用于 MP4 合并的音轨（MediaMuxer 的 MPEG_4 容器只稳收 AAC；按编解码器判断，不依赖 ext）。 */
    private ExtractorResult.Format pickMergeableAudio() {
        for (ExtractorResult.Format f : audioChoices) {
            String ac = f.acodec == null ? "" : f.acodec.toLowerCase();
            if ("m4a".equals(f.ext) || "aac".equals(f.ext)
                    || ac.startsWith("mp4a") || ac.startsWith("aac")) {
                return f;
            }
        }
        return null;
    }

    /**
     * 下载指定格式到本地（对应 yt-dlp process_info → downloader.download）。
     *
     * <p>若 {@code audioStream} 非空（DASH 分离流），先下视频、再下音轨，
     * 最后用 MediaMuxer 合并（对应 yt-dlp 的合并后处理，替代未实现的 FFmpeg JNI 链路），
     * 避免产出无声视频文件。</p>
     */
    private void downloadFormat(ExtractorResult.Format format, boolean audioOnly,
                                 ExtractorResult.Format audioStream) {
        File videoTmp = null;
        File audioTmp = null;
        try {
            // 生成文件名
            String baseName = pendingResult.getBaseFilename() != null
                    ? pendingResult.getBaseFilename() : "media_" + System.currentTimeMillis();
            String ext = audioOnly ? format.ext : (format.ext != null ? format.ext : "mp4");
            String fileName = UniversalDownloadManager.sanitizeFileName(baseName + "." + ext);
            File outFile = new File(getDownloadDir(), fileName);

            boolean needMerge = !audioOnly && audioStream != null;
            if (needMerge) {
                videoTmp = new File(getDownloadDir(),
                        UniversalDownloadManager.sanitizeFileName(baseName + "._vpart." + ext));
                audioTmp = new File(getDownloadDir(),
                        UniversalDownloadManager.sanitizeFileName(baseName + "._apart." + audioStream.ext));
            }

            mainHandler.post(() -> {
                showStatus(getString(R.string.universal_download_downloading), 0, true);
                actionButtons.setVisibility(View.VISIBLE);
                pauseButton.setText(R.string.universal_download_pause);
            });

            downloadInProgress = true;
            // downloadSmart 会自动识别 m3u8 并路由到 HLS 下载器
            downloadFile(format.url, videoTmp != null ? videoTmp : outFile, format);

            if (needMerge) {
                mainHandler.post(() -> statusMessage.setText("视频完成，正在下载音轨…"));
                downloadFile(audioStream.url, audioTmp, audioStream);

                mainHandler.post(() -> statusMessage.setText("正在合并音轨…"));
                try {
                    MediaMuxerUtil.merge(videoTmp, audioTmp, outFile);
                } catch (Exception mergeEx) {
                    // 合并失败（编码容器不收、采样异常等）→ 退回无声视频而非整体失败/崩溃
                    Log.w(TAG, "merge failed, fallback to video-only file", mergeEx);
                    if (!videoTmp.renameTo(outFile)) {
                        if (mergeEx instanceof IOException) throw (IOException) mergeEx;
                        throw new IOException("音轨合并失败: " + mergeEx);
                    }
                } finally {
                    videoTmp.delete();
                    audioTmp.delete();
                }
            }

            final String donePath = outFile.getAbsolutePath();
            final String doneName = outFile.getName();
            mainHandler.post(() -> {
                downloadInProgress = false;
                actionButtons.setVisibility(View.GONE);
                speedText.setVisibility(View.GONE);
                // 状态卡片显示完整路径（应用内反馈）
                showStatus(getString(R.string.universal_download_done_with_path, donePath),
                        100, false);
                // 系统通知（不干扰用户，可滑动清除）
                showDownloadNotification(true, doneName, donePath);
            });
        } catch (IOException e) {
            Log.e(TAG, "download failed", e);
            mainHandler.post(() -> {
                downloadInProgress = false;
                actionButtons.setVisibility(View.GONE);
                speedText.setVisibility(View.GONE);
                showStatus(getString(R.string.universal_download_failed, e.getMessage()), 0, false);
                // 失败也通过系统通知提醒（避免 GlassToast 打断用户）
                String title = pendingResult != null && pendingResult.getTitle() != null
                        ? pendingResult.getTitle() : "媒体";
                showDownloadNotification(false, title,
                        e.getMessage() != null ? e.getMessage() : "下载失败");
            });
            if (videoTmp != null) videoTmp.delete();
            if (audioTmp != null) audioTmp.delete();
        }
    }

    /** 顺序下载单个文件（阻塞式；进度/速度/重试状态上屏，失败抛 IOException 由调用方收尾）。
     *  fmt.httpHeaders 非空时随请求下发（googlevideo 直链校验下载端 UA 等）。 */
    private void downloadFile(String url, File target, ExtractorResult.Format fmt) throws IOException {
        UniversalDownloadManager mgr = new UniversalDownloadManager();
        downloadManager = mgr;
        final java.util.concurrent.atomic.AtomicReference<String> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        DownloadProgressCallback cb = new DownloadProgressCallback() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes, long speedBps, int percent) {
                mainHandler.post(() -> {
                    progressBar.setIndeterminate(false);
                    if (percent >= 0) {
                        progressBar.setProgress(percent);
                        progressText.setText(percent + "%");
                    } else {
                        progressText.setText(formatBytes(downloadedBytes));
                    }
                    speedText.setVisibility(View.VISIBLE);
                    speedText.setText(getString(R.string.universal_download_speed_format,
                            formatSpeed(speedBps)));
                });
            }

            @Override
            public void onStatusChanged(String status, String message) {
                mainHandler.post(() -> {
                    if ("downloading".equals(status) && message != null && message.contains("重试")) {
                        statusMessage.setText(message);
                    }
                });
            }

            @Override
            public void onComplete(String path) {
                // 完成收尾由调用方统一处理（可能还有音轨下载/合并阶段）
            }

            @Override
            public void onError(String errorCode, String message) {
                failure.set(message);
            }
        };
        Map<String, String> headers = fmt != null ? fmt.httpHeaders : null;
        if (headers != null && !headers.isEmpty()) {
            // 站点专属头（如 googlevideo 校验 UA）随请求下发
            mgr.download(url, target, headers, cb);
        } else {
            // downloadSmart 会自动识别 m3u8 并路由到 HLS 下载器
            mgr.downloadSmart(url, target, cb);
        }
        String err = failure.get();
        if (err != null) throw new IOException(err);
    }

    /**
     * 抖音 Cookie 预热（{@link DouyinExtractor.CookieBootstrapper} 实现）。
     *
     * <p>抖音 web detail API 需要页面 JS 质询生成的会话 Cookie（ttwid/s_v_web_id/
     * msToken，yt-dlp 官方实现同样依赖真实浏览器 Cookie）。预热分两步：</p>
     * <ol>
     *   <li>调 ByteDance 官方 ttwid 注册接口直接播种 ttwid（无需 WebView，毫秒级）</li>
     *   <li>隐藏 WebView 加载目标视频页，让 acrawler JS 质询跑完生成
     *       s_v_web_id/msToken，轮询回填全局 CookieJar</li>
     * </ol>
     */
    private void bootstrapDouyinCookies(String pageUrl) throws Exception {
        // 1. 播种 ttwid（实测该端点对纯 HTTP 开放，返回 Set-Cookie: ttwid=...）
        String seeded = seedTtwid();
        if (seeded != null) {
            Log.i(TAG, "seedTtwid OK: " + seeded.substring(0, Math.min(24, seeded.length())) + "...");
            importDouyinCookies(seeded);
            CookieManager.getInstance().setCookie("https://www.douyin.com/", seeded);
        } else {
            Log.w(TAG, "seedTtwid: 未获得 ttwid（端点无 Set-Cookie 或网络异常）");
        }

        // 2. WebView 加载视频页，等 JS 质询补齐 s_v_web_id/msToken
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] gotCookie = {false};
        final WebView[] holder = new WebView[1];
        mainHandler.post(() -> {
            WebView wv = new WebView(this);
            holder[0] = wv;
            WebSettings s = wv.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setUserAgentString(ExtractorHttp.DEFAULT_UA);
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            cm.setAcceptThirdPartyCookies(wv, true);
            wv.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    // 轮询至多 25 秒（50 × 500ms）：ttwid + (s_v_web_id 或 msToken)
                    view.postDelayed(new Runnable() {
                        int tries = 0;
                        @Override
                        public void run() {
                            String header = cm.getCookie("https://www.douyin.com/");
                            if (header != null && header.contains("ttwid=")
                                    && (header.contains("s_v_web_id=")
                                        || header.contains("msToken="))) {
                                importDouyinCookies(header);
                                gotCookie[0] = true;
                                latch.countDown();
                            } else if (++tries < 50) {
                                view.postDelayed(this, 500);
                            } else {
                                // 兜底：哪怕只有 ttwid 也回填（部分视频 detail 只需要它）
                                if (header != null && header.contains("ttwid=")) {
                                    importDouyinCookies(header);
                                    gotCookie[0] = true;
                                }
                                latch.countDown();
                            }
                        }
                    }, 500);
                }
            });
            wv.loadUrl(pageUrl);
        });
        try {
            latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            mainHandler.post(() -> {
                if (holder[0] != null) holder[0].destroy();
            });
        }
        // 播种成功即视为预熟（CookieJar 已有 ttwid），WebView 轮询只是补充 s_v_web_id/msToken
        if (!gotCookie[0] && seeded == null) {
            throw new RuntimeException("预热超时：未取到 ttwid，请先在应用内浏览器打开一次 douyin.com");
        }
        Log.i(TAG, "bootstrapDouyinCookies done: seeded=" + (seeded != null)
                + " webviewCookie=" + gotCookie[0]);
    }

    /** 调 ByteDance ttwid 注册接口，返回 "ttwid=xxx" 形式的 Cookie 对（失败返回 null）。 */
    private String seedTtwid() {
        java.net.HttpURLConnection conn = null;
        try {
            java.net.URL url = new java.net.URL("https://ttwid.bytedance.com/ttwid/union/register/");
            conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", ExtractorHttp.DEFAULT_UA);
            String body = "{\"region\":\"cn\",\"aid\":1768,\"needFid\":false,"
                    + "\"service\":\"www.ixigua.com\",\"migrate_info\":{\"ticket\":\"\",\"source\":\"node\"},"
                    + "\"cbUrlProtocol\":\"https\",\"union\":true}";
            java.io.OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            os.flush();
            os.close();
            int code = conn.getResponseCode();
            if (code >= 400) {
                Log.w(TAG, "seedTtwid HTTP " + code);
                return null;
            }
            Map<String, List<String>> headers = conn.getHeaderFields();
            List<String> setCookies = headers.get("Set-Cookie");
            if (setCookies == null) setCookies = headers.get("set-cookie");
            if (setCookies != null) {
                for (String sc : setCookies) {
                    if (sc.startsWith("ttwid=")) {
                        int semi = sc.indexOf(';');
                        return semi > 0 ? sc.substring(0, semi) : sc;
                    }
                }
            }
            Log.w(TAG, "seedTtwid HTTP " + code + " 无 Set-Cookie，头: " + headers.keySet());
        } catch (IOException e) {
            Log.w(TAG, "seedTtwid failed: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    /** 把 CookieManager 的 Cookie 头解析进提取器全局 CookieJar（作用于 .douyin.com）。 */
    private void importDouyinCookies(String header) {
        com.example.cleanrecovery.ytdlp.CookieJar jar = ExtractorHttp.getGlobalCookieJar();
        if (jar == null || header == null || header.isEmpty()) return;
        List<com.example.cleanrecovery.ytdlp.CookieJar.Cookie> list = new ArrayList<>();
        for (String pair : header.split(";")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String name = pair.substring(0, eq).trim();
            String value = pair.substring(eq + 1).trim();
            if (name.isEmpty()) continue;
            list.add(new com.example.cleanrecovery.ytdlp.CookieJar.Cookie(
                    name, value, ".douyin.com", "/", true, false));
        }
        if (!list.isEmpty()) jar.putCookies(list);
    }

    /** 暂停/继续按钮点击。 */
    private void onPauseClicked() {
        if (downloadManager == null) return;
        if (downloadManager.isPaused()) {
            downloadManager.resume();
            pauseButton.setText(R.string.universal_download_pause);
            statusMessage.setText(R.string.universal_download_downloading);
        } else {
            downloadManager.pause();
            pauseButton.setText(R.string.universal_download_resume);
            statusMessage.setText(R.string.universal_download_paused);
        }
    }

    /** 取消按钮点击。 */
    private void onCancelClicked() {
        if (downloadManager != null) {
            downloadManager.cancel();
        }
        downloadInProgress = false;
        actionButtons.setVisibility(View.GONE);
        speedText.setVisibility(View.GONE);
        showStatus(getString(R.string.universal_download_failed, "cancelled"), 0, false);
    }

    /** 将 ExtractorException 映射为用户可读的提示。 */
    private String mapExtractorError(ExtractorException e) {
        switch (e.getKind()) {
            case NOT_FOUND:
                return "视频不存在或已删除";
            case LOGIN_REQUIRED:
                // YouTube 反机器人检测（Sign in to confirm you're not a bot）
                if (e.getMessage() != null && e.getMessage().contains("bot")) {
                    return "YouTube 反机器人检测触发（需要 PO Token 认证）。\n"
                            + "这是 YouTube 2025 年后的服务端限制，yt-dlp 同样受影响。\n"
                            + "建议：1) 稍后重试 2) 更换代理节点 3) 使用其他平台视频";
                }
                return "需要登录才能观看：" + e.getMessage();
            case GEO_RESTRICTED:
                return "地区限制：" + e.getMessage();
            case PREMIUM_ONLY:
                return "仅会员可看：" + e.getMessage();
            case RATE_LIMITED:
                return "请求过于频繁，请稍后重试";
            case UNSUPPORTED:
                return "不支持的链接：" + e.getMessage();
            case PARSE_FAILED:
                return "解析失败：" + e.getMessage();
            default:
                return "提取失败：" + e.getMessage();
        }
    }

    /** 格式化速度为人类可读字符串。 */
    private static String formatSpeed(long bps) {
        if (bps < 1024) return bps + " B";
        if (bps < 1024 * 1024) return String.format("%.1f KB", bps / 1024.0);
        return String.format("%.1f MB", bps / (1024.0 * 1024.0));
    }

    /** 格式化字节数为人类可读字符串。 */
    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /** 获取下载目录：P0③ 设置→下载目录 优先，回退 /storage/emulated/0/DataRecovery/Downloads/。 */
    private File getDownloadDir() {
        File configured = new BrowserPrefs(this).downloadDirFile();
        if (configured != null) return configured;
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

    /** 更新状态卡片。 */
    private void showStatus(String message, int progress, boolean indeterminate) {
        statusMessage.setText(message);
        progressBar.setProgress(progress);
        progressBar.setIndeterminate(indeterminate);
        if (indeterminate || progress == 0) {
            progressText.setText("");
        } else {
            progressText.setText(progress + "%");
        }
    }

    /** 创建下载通知通道（Android 8.0+ 必需）。 */
    private void createDownloadNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID_DOWNLOAD,
                getString(R.string.notification_channel_download),
                NotificationManager.IMPORTANCE_LOW);  // LOW：不发出声音，避免打扰
        channel.setDescription(getString(R.string.notification_channel_download_desc));
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);
    }

    /**
     * P0 A5：请求运行时权限（通知 + 媒体读取）。
     *
     * <p>批量请求以下权限（仅在对应 API 级别需要时）：</p>
     * <ul>
     *   <li>{@link Manifest.permission#POST_NOTIFICATIONS} —— Android 13+（API 33+），
     *       下载完成通知必需</li>
     *   <li>{@link Manifest.permission#READ_MEDIA_VIDEO} —— Android 13+（API 33+），
     *       扫描已下载视频/相册刷新必需</li>
     *   <li>{@link Manifest.permission#WRITE_EXTERNAL_STORAGE} —— Android 10 及以下，
     *       写 {@code DataRecovery/Downloads} 共享目录必需</li>
     * </ul>
     * <p>使用单一 requestCode 批量请求，减少弹窗次数。</p>
     */
    private void requestRuntimePermissions() {
        java.util.List<String> needed = new java.util.ArrayList<>();
        // Android 13+：通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        // Android 13+：媒体读取权限（用于 MediaScanner 刷新相册）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.READ_MEDIA_VIDEO);
        }
        // Android 10 及以下：写外部存储（manifest 已声明 maxSdkVersion=29，需运行时申请）
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), 1001);
        }
    }

    /**
     * P0 A5：检查并引导用户授予"所有文件访问"权限（MANAGE_EXTERNAL_STORAGE）。
     *
     * <p>Android 11+（API 30+）无法通过 {@code requestPermissions} 申请此权限，
     * 必须跳转系统设置页让用户手动授予。本应用作为"数据恢复"工具，写共享存储
     * （{@code /storage/emulated/0/DataRecovery/Downloads}）是核心功能，故需此权限。</p>
     *
     * <p><b>保守策略</b>：仅在未授予时弹 GlassToast 引导，不强制跳转（避免打断用户首次使用流程）。
     * 用户点击下载按钮时若仍未授予，再次提示。真正写文件失败时由下载逻辑兜底处理。</p>
     */
    private void requestAllFilesAccessIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return; // Android 11 以下不需要
        if (android.os.Environment.isExternalStorageManager()) return; // 已授予
        // 未授予：首次进入仅 GlassToast 提示，不强制跳转（保守，避免打断 UX）
        GlassToast.makeText(this,
                "需要\"所有文件访问\"权限才能保存到 DataRecovery/Downloads，"
                        + "请在设置中授予",
                GlassToast.LENGTH_LONG).show();
    }

    /**
     * 跳转到系统"所有文件访问"设置页（由用户在 UI 上主动触发）。
     */
    private void launchAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                // 部分设备不支持直接跳转，回退到通用所有文件访问设置
                Intent fallback = new Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                try {
                    startActivity(fallback);
                } catch (Exception ex) {
                    Log.w(TAG, "无法跳转所有文件访问设置: " + ex.getMessage());
                }
            }
        }
    }

    /**
     * 显示下载结果系统通知（替代 GlassToast，避免过度打扰用户）。
     *
     * @param success true=下载完成，false=下载失败
     * @param title   文件名或视频标题
     * @param detail  完成时为完整路径，失败时为错误信息
     */
    private void showDownloadNotification(boolean success, String title, String detail) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID_DOWNLOAD)
                .setSmallIcon(R.drawable.ic_download)
                .setContentTitle(success
                        ? getString(R.string.notification_download_complete_title)
                        : getString(R.string.notification_download_failed_title))
                .setContentText(success
                        ? getString(R.string.notification_download_complete_text, title)
                        : getString(R.string.notification_download_failed_text, title))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(
                        success ? (title + "\n" + detail) : (title + "\n" + detail)))
                .setPriority(NotificationCompat.PRIORITY_LOW)  // 低优先级，不弹出横幅
                .setAutoCancel(true);

        // 完成时点击通知打开文件所在目录
        if (success) {
            Intent openIntent = createOpenFileIntent(detail);
            if (openIntent != null) {
                PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                builder.setContentIntent(pi);
            }
        }

        try {
            nm.notify(NOTIFICATION_ID_DOWNLOAD, builder.build());
        } catch (SecurityException e) {
            // 用户拒绝通知权限时静默降级（状态卡片仍提供反馈）
            Log.w(TAG, "通知权限被拒绝，无法显示系统通知: " + e.getMessage());
        }
    }

    /** 创建打开文件/目录的 Intent。 */
    private Intent createOpenFileIntent(String filePath) {
        if (filePath == null) return null;
        File file = new File(filePath);
        if (!file.exists()) return null;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "video/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            return intent;
        } catch (Exception e) {
            Log.w(TAG, "无法创建打开文件的 Intent: " + e.getMessage());
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (downloadManager != null) {
            downloadManager.cancel();
        }
        executor.shutdownNow();
    }
}
