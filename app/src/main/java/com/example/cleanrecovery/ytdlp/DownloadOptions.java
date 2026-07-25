package com.example.cleanrecovery.ytdlp;

import com.example.cleanrecovery.extractor.ExtractorResult;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * 下载选项（对应计划 §3.1 / Q1-Q8 已确认决策）。
 *
 * <p>智能零配置模式下，用户不暴露 yt-dlp 的 CLI 参数（{@code -f} / {@code -o} / {@code --ppa}）。
 * 所有"自动判断"逻辑由 {@code SmartFormatSelector} / {@code SmartProtocolRouter} /
 * {@code SmartPostProcessor} / {@code SmartOutputNamer} 内部决定。</p>
 *
 * <p>本类只承载用户可调的"档位级"选项 + Q1-Q8 默认值，可通过 Builder 构造。</p>
 */
public final class DownloadOptions {

    // ===== 画质与音频 =====

    /** 用户画质偏好，默认 {@link QualityHint#AUTO}。 */
    public final QualityHint quality;
    /** 是否仅提取音频。 */
    public final boolean audioOnly;

    // ===== 输出位置（Q2）=====

    /** 输出目录，默认 {@code DataRecovery/Downloads/}，设置可改为自定义目录（含 SD 卡）。 */
    public final File outDir;

    // ===== 并发与冲突（Q1 / Q3）=====

    /** 同时下载任务数，默认 2（WiFi 下自动升至 3）。 */
    public final int maxConcurrentDownloads;
    /** 文件冲突策略，默认 {@link FileConflictStrategy#ASK}。 */
    public final FileConflictStrategy conflictStrategy;

    // ===== 网络 / 电池（Q4 / Q5）=====

    /** 是否允许移动数据，默认 true。>100MB 文件在流量下需弹窗确认。 */
    public final boolean allowMobileData;
    /** "大文件"阈值（字节），默认 100MB。 */
    public final long largeFileThreshold;
    /** 是否在低电量时提示，默认 true（<15% 提示，不强制阻止）。 */
    public final boolean checkBattery;

    // ===== 通知（Q6）=====

    /** 下载完成时振动一次，默认 true。 */
    public final boolean notifyVibrateOnComplete;
    /** 下载完成时是否发声，默认 false（仅振动）。 */
    public final boolean notifySoundOnComplete;

    // ===== 临时文件 / 重试（Q7 / Q8）=====

    /** 失败后临时文件保留天数，默认 7。App 启动时清理 >7 天孤儿。 */
    public final int tempFileRetainDays;
    /** 自动重试次数，默认 3（{1s,2s,4s} 退避）。 */
    public final int maxRetries;
    /** 失败后是否保留任务并发通知，默认 true。 */
    public final boolean keepTaskOnFailure;

    // ===== Cookie / 归档 =====

    /**
     * 已登录站点列表（仅注入对应站点 cookie 的 URL）。
     * 由 {@code PersistentCookieJar} 维护，Extractor 请求前注入对应站点 cookie。
     */
    public final List<String> authenticatedHosts;
    /** 是否启用归档（幂等跳过已下载），默认 true。 */
    public final boolean enableArchive;

    private DownloadOptions(Builder b) {
        this.quality = b.quality != null ? b.quality : QualityHint.AUTO;
        this.audioOnly = b.audioOnly;
        this.outDir = b.outDir;
        this.maxConcurrentDownloads = b.maxConcurrentDownloads > 0 ? b.maxConcurrentDownloads : 2;
        this.conflictStrategy = b.conflictStrategy != null ? b.conflictStrategy : FileConflictStrategy.ASK;
        this.allowMobileData = b.allowMobileData;
        this.largeFileThreshold = b.largeFileThreshold > 0 ? b.largeFileThreshold : 100L * 1024 * 1024;
        this.checkBattery = b.checkBattery;
        this.notifyVibrateOnComplete = b.notifyVibrateOnComplete;
        this.notifySoundOnComplete = b.notifySoundOnComplete;
        this.tempFileRetainDays = b.tempFileRetainDays > 0 ? b.tempFileRetainDays : 7;
        this.maxRetries = b.maxRetries >= 0 ? b.maxRetries : 3;
        this.keepTaskOnFailure = b.keepTaskOnFailure;
        this.authenticatedHosts = b.authenticatedHosts != null
                ? Collections.unmodifiableList(b.authenticatedHosts) : Collections.emptyList();
        this.enableArchive = b.enableArchive;
    }

    /** 默认选项（智能零配置）。 */
    public static DownloadOptions defaults() {
        return new Builder().build();
    }

    /** 默认输出目录名（{@code DataRecovery/Downloads/}）。 */
    public static final String DEFAULT_OUT_DIR_NAME = "DataRecovery/Downloads";

    public static class Builder {
        private QualityHint quality;
        private boolean audioOnly = false;
        private File outDir;
        private int maxConcurrentDownloads = 2;
        private FileConflictStrategy conflictStrategy = FileConflictStrategy.ASK;
        private boolean allowMobileData = true;
        private long largeFileThreshold = 100L * 1024 * 1024;
        private boolean checkBattery = true;
        private boolean notifyVibrateOnComplete = true;
        private boolean notifySoundOnComplete = false;
        private int tempFileRetainDays = 7;
        private int maxRetries = 3;
        private boolean keepTaskOnFailure = true;
        private List<String> authenticatedHosts;
        private boolean enableArchive = true;

        public Builder quality(QualityHint q) { this.quality = q; return this; }
        public Builder audioOnly(boolean a) { this.audioOnly = a; return this; }
        public Builder outDir(File d) { this.outDir = d; return this; }
        public Builder maxConcurrentDownloads(int n) { this.maxConcurrentDownloads = n; return this; }
        public Builder conflictStrategy(FileConflictStrategy s) { this.conflictStrategy = s; return this; }
        public Builder allowMobileData(boolean a) { this.allowMobileData = a; return this; }
        public Builder largeFileThreshold(long bytes) { this.largeFileThreshold = bytes; return this; }
        public Builder checkBattery(boolean c) { this.checkBattery = c; return this; }
        public Builder notifyVibrateOnComplete(boolean v) { this.notifyVibrateOnComplete = v; return this; }
        public Builder notifySoundOnComplete(boolean s) { this.notifySoundOnComplete = s; return this; }
        public Builder tempFileRetainDays(int d) { this.tempFileRetainDays = d; return this; }
        public Builder maxRetries(int r) { this.maxRetries = r; return this; }
        public Builder keepTaskOnFailure(boolean k) { this.keepTaskOnFailure = k; return this; }
        public Builder authenticatedHosts(List<String> h) { this.authenticatedHosts = h; return this; }
        public Builder enableArchive(boolean e) { this.enableArchive = e; return this; }

        public DownloadOptions build() { return new DownloadOptions(this); }
    }

    /**
     * 根据 {@link ExtractorResult} 决定是否启用归档跳过。
     * <p>M1 简化：只要 enableArchive 且 result 有归档键就启用。</p>
     */
    public boolean shouldArchive(ExtractorResult result) {
        return enableArchive && result != null && result.getArchiveKey() != null;
    }
}
