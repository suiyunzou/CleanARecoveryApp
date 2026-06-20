package com.example.cleanrecovery.ui.activity;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.download.DownloadProgressCallback;
import com.example.cleanrecovery.download.UniversalDownloadManager;
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
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.example.cleanrecovery.extractor.Extractor;
import com.example.cleanrecovery.extractor.ExtractorException;
import com.example.cleanrecovery.extractor.ExtractorRegistry;
import com.example.cleanrecovery.extractor.ExtractorResult;
import com.example.cleanrecovery.extractor.GenericExtractor;
import com.example.cleanrecovery.extractor.JsRendererExtractor;

import java.io.File;
import java.io.IOException;
import java.util.List;
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

    /** 画质选项：label → 高度（像素）。 */
    private static final String[][] VIDEO_QUALITIES = {
            {"2160p（4K 超清）", "2160"},
            {"1440p（2K 高清）", "1440"},
            {"1080p（全高清）", "1080"},
            {"720p（高清）",     "720"},
            {"480p（标清）",     "480"},
            {"360p（流畅）",     "360"},
    };
    /** 纯音频选项：label → 扩展名。 */
    private static final String[][] AUDIO_FORMATS = {
            {"MP3（兼容性最好）", "mp3"},
            {"M4A（原声质量）",  "m4a"},
            {"FLAC（无损）",      "flac"},
    };

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
        // 请求通知权限（Android 13+ 需要）
        requestNotificationPermission();

        // 注册 HLS 与 JS 渲染钩子（让 GenericExtractor 能处理 m3u8 与 SPA 页面）
        registerExtractorHooks();

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
            Toast.makeText(this, R.string.universal_download_url_empty,
                    Toast.LENGTH_SHORT).show();
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
                Toast.makeText(this, R.string.universal_download_select_quality,
                        Toast.LENGTH_SHORT).show();
            });

        } catch (ExtractorException e) {
            Log.e(TAG, "extract failed: " + e.getKind(), e);
            final String msg = mapExtractorError(e);
            mainHandler.post(() -> {
                showStatus(getString(R.string.universal_download_resolve_failed, msg), 0, false);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
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

    /** 根据提取结果动态构建画质选项。 */
    private void buildQualityOptions(ExtractorResult result) {
        List<ExtractorResult.Format> formats = result.getFormats();

        // 视频画质选项：根据提取到的格式筛选可用画质
        boolean hasVideo = false;
        for (ExtractorResult.Format f : formats) {
            if (!f.isAudioOnly()) { hasVideo = true; break; }
        }

        if (hasVideo) {
            for (int i = 0; i < VIDEO_QUALITIES.length; i++) {
                RadioButton rb = new RadioButton(this);
                rb.setId(10_000 + i);
                rb.setText(VIDEO_QUALITIES[i][0]);
                rb.setTextColor(getResources().getColor(R.color.text_secondary));
                rb.setTextSize(14);
                rb.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_xs), 0, 0);
                videoGroup.addView(rb);
            }
            // 默认选中 1080p
            videoGroup.check(videoGroup.getChildAt(3).getId());
        } else {
            // 仅音频，隐藏视频组
            TextView label = new TextView(this);
            label.setText("该链接仅提供音频");
            label.setTextColor(getResources().getColor(R.color.text_muted));
            label.setTextSize(14);
            videoGroup.addView(label);
        }

        // 音频选项
        boolean hasAudio = false;
        for (ExtractorResult.Format f : formats) {
            if (f.isAudioOnly()) { hasAudio = true; break; }
        }
        if (hasAudio) {
            for (int i = 0; i < AUDIO_FORMATS.length; i++) {
                RadioButton rb = new RadioButton(this);
                rb.setId(20_000 + i);
                rb.setText(AUDIO_FORMATS[i][0]);
                rb.setTextColor(getResources().getColor(R.color.text_secondary));
                rb.setTextSize(14);
                rb.setPadding(0, getResources().getDimensionPixelSize(R.dimen.space_xs), 0, 0);
                audioGroup.addView(rb);
            }
        }
    }

    /** 第二步：用户选定画质后，下载对应格式。 */
    private void onQualitySelected() {
        if (pendingUrl == null || pendingResult == null) return;

        int videoId = videoGroup.getCheckedRadioButtonId();
        int audioId = audioGroup.getCheckedRadioButtonId();

        if (videoId == -1 && audioId == -1) {
            Toast.makeText(this, R.string.universal_download_select_quality,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        // 选择目标 Format（对应 yt-dlp format selection）
        ExtractorResult.Format targetFormat = null;
        boolean audioOnly = false;

        if (audioId != -1) {
            // 纯音频模式：选择最佳音频
            audioOnly = true;
            targetFormat = pendingResult.getBestAudioOnlyFormat();
        } else {
            // 视频模式：按选定高度选择最接近的格式
            int targetHeight = Integer.parseInt(VIDEO_QUALITIES[videoId - 10_000][1]);
            targetFormat = selectBestVideoFormat(pendingResult, targetHeight);
        }

        if (targetFormat == null) {
            Toast.makeText(this, R.string.universal_download_select_quality,
                    Toast.LENGTH_SHORT).show();
            return;
        }

        qualityCard.setVisibility(View.GONE);
        statusCard.setVisibility(View.VISIBLE);
        showStatus(getString(R.string.universal_download_downloading), 0, true);

        final ExtractorResult.Format finalFormat = targetFormat;
        final boolean finalAudioOnly = audioOnly;
        executor.execute(() -> downloadFormat(finalFormat, finalAudioOnly));
    }

    /**
     * 选择最接近目标高度的视频格式（对应 yt-dlp format sorting）。
     *
     * <p>策略：优先选择 ≤ 目标高度的最大格式；若无，选择最小的超过目标的格式。</p>
     */
    private ExtractorResult.Format selectBestVideoFormat(ExtractorResult result, int targetHeight) {
        // 优先选择合并格式（含音视频）
        ExtractorResult.Format best = result.getBestCombinedFormat();
        if (best != null && best.height <= targetHeight) return best;

        // 从所有非纯音频格式中筛选
        ExtractorResult.Format below = null;  // ≤ 目标的最大
        ExtractorResult.Format above = null;  // > 目标的最小
        for (ExtractorResult.Format f : result.getFormats()) {
            if (f.isAudioOnly()) continue;
            if (f.height <= targetHeight) {
                if (below == null || f.height > below.height) below = f;
            } else {
                if (above == null || f.height < above.height) above = f;
            }
        }
        return below != null ? below : above;
    }

    /** 下载指定格式到本地（对应 yt-dlp process_info → downloader.download）。 */
    private void downloadFormat(ExtractorResult.Format format, boolean audioOnly) {
        try {
            // 生成文件名
            String baseName = pendingResult.getBaseFilename() != null
                    ? pendingResult.getBaseFilename() : "media_" + System.currentTimeMillis();
            String ext = audioOnly ? format.ext : (format.ext != null ? format.ext : "mp4");
            String fileName = UniversalDownloadManager.sanitizeFileName(baseName + "." + ext);
            File outFile = new File(getDownloadDir(), fileName);

            mainHandler.post(() -> {
                showStatus(getString(R.string.universal_download_downloading), 0, true);
                actionButtons.setVisibility(View.VISIBLE);
                pauseButton.setText(R.string.universal_download_pause);
            });

            // 使用下载管理器（集成断点续传/重试/动态块大小）
            // downloadSmart 会自动识别 m3u8 并路由到 HLS 下载器
            downloadManager = new UniversalDownloadManager();
            downloadInProgress = true;
            downloadManager.downloadSmart(format.url, outFile, new DownloadProgressCallback() {
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
                    mainHandler.post(() -> {
                        downloadInProgress = false;
                        actionButtons.setVisibility(View.GONE);
                        speedText.setVisibility(View.GONE);
                        // 状态卡片显示完整路径（应用内反馈）
                        showStatus(getString(R.string.universal_download_done_with_path, path),
                                100, false);
                        // 系统通知（不干扰用户，可滑动清除）
                        String fileName = new File(path).getName();
                        showDownloadNotification(true, fileName, path);
                    });
                }

                @Override
                public void onError(String errorCode, String message) {
                    mainHandler.post(() -> {
                        downloadInProgress = false;
                        actionButtons.setVisibility(View.GONE);
                        speedText.setVisibility(View.GONE);
                        showStatus(getString(R.string.universal_download_failed, message), 0, false);
                        // 失败也通过系统通知提醒（避免 Toast 打断用户）
                        String title = pendingResult != null && pendingResult.getTitle() != null
                                ? pendingResult.getTitle() : "媒体";
                        showDownloadNotification(false, title, message);
                    });
                }
            });

        } catch (IOException e) {
            Log.e(TAG, "download failed", e);
            mainHandler.post(() -> {
                downloadInProgress = false;
                actionButtons.setVisibility(View.GONE);
                speedText.setVisibility(View.GONE);
                showStatus(getString(R.string.universal_download_failed, e.getMessage()), 0, false);
            });
        }
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

    /** 获取下载目录：/storage/emulated/0/DataRecovery/Downloads/。 */
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

    /** 请求通知权限（Android 13+ 需要运行时申请）。 */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    /**
     * 显示下载结果系统通知（替代 Toast，避免过度打扰用户）。
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
