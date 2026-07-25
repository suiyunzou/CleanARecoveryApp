package com.example.cleanrecovery.ytdlp;

import android.content.Context;
import android.util.Log;

import com.example.cleanrecovery.background.IntegrityVerifier;
import com.example.cleanrecovery.download.DownloadProgressCallback;
import com.example.cleanrecovery.download.DownloaderRegistry;
import com.example.cleanrecovery.download.FileDownloader;
import com.example.cleanrecovery.extractor.Extractor;
import com.example.cleanrecovery.extractor.ExtractorException;
import com.example.cleanrecovery.extractor.ExtractorRegistry;
import com.example.cleanrecovery.extractor.ExtractorResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 中心编排器（对应 yt-dlp {@code YoutubeDL}）。
 *
 * <p>统一编排视频下载的完整流程，<b>用户只提供 URL</b>，自动识别站点、选格式、
 * 路由下载器、跑后处理链、归档。所有 CLI 参数（-f/-o/--ppa）由智能策略层内部决定。</p>
 *
 * <h3>六阶段流程（对应计划 §3.1）</h3>
 * <ol>
 *   <li>{@link #stageMatchExtractor match extractor}：从 {@link ExtractorRegistry} 按 URL 匹配</li>
 *   <li>{@link #stageExtract extract}：调用 {@code extractor.extract(url)} 得到 {@link ExtractorResult}（info_dict）</li>
 *   <li>{@link #stageArchiveCheck archive check}：{@link DownloadArchive#isDownloaded} 幂等跳过</li>
 *   <li>{@link #stageSelectFormats format select}：{@code SmartFormatSelector}
 *       按 D5 策略选择合一轨或 bv+ba → {@link RequestedFormats}</li>
 *   <li>{@link #stageDownload download}：{@link DownloaderRegistry#route} 按 protocol 路由
 *       {@link FileDownloader}，下载到 outFile</li>
 *   <li>{@link #stagePostProcess post-process}：{@link PostProcessorChain#runAll} 执行后处理链
 *       （M2 注册合并、转封装和临时文件清理）</li>
 * </ol>
 *
 * <p><b>M1 范围</b>：跑通 A 流水线（用户直接粘贴 URL，非 WebView 嗅探）。
 * 嗅探路径接入在 M3 完成（D2 流水线收敛）。</p>
 */
public final class YoutubeDL {

    private static final String TAG = "YoutubeDL";

    private final Context context;
    private final DownloadArchive archive;
    private final PostProcessorChain ppChain;
    private final SmartFormatSelector formatSelector;

    public YoutubeDL(Context context) {
        this.context = context.getApplicationContext();
        this.archive = DownloadArchive.getInstance(this.context);
        this.ppChain = new PostProcessorChain();
        this.formatSelector = new SmartFormatSelector();
        new SmartPostProcessor(this.context).registerDefaults(this.ppChain);
    }

    /**
     * 智能零配置入口：用户只给 URL，自动完成全流程。
     *
     * @param url  用户输入的 URL（支持 Bilibili/YouTube/抖音/TikTok + 通用兜底）
     * @param opts 下载选项（{@link DownloadOptions#defaults()} 即可）
     * @param callback 进度回调（可为 null）
     * @return 下载结果（成功时含最终文件路径；失败时 error 字段填错误信息）
     */
    public DownloadResult extractAndDownload(String url, DownloadOptions opts,
                                              DownloadProgressCallback callback) {
        if (url == null || url.trim().isEmpty()) {
            return failed("URL is empty", null, null);
        }
        if (opts == null) opts = DownloadOptions.defaults();

        notifyStatus(callback, "resolving", "正在识别链接…");

        // ① 匹配 Extractor
        Extractor extractor = stageMatchExtractor(url);
        if (extractor == null) {
            return failed("no extractor matched: " + url, null, null);
        }
        Log.i(TAG, "① matched extractor: " + extractor.name());

        // ② 提取 info_dict
        ExtractorResult info;
        try {
            info = stageExtract(extractor, url);
        } catch (ExtractorException e) {
            Log.w(TAG, "② extract failed: " + e.getKind() + " " + e.getMessage());
            return failed("extract failed: " + e.getMessage(), null, null);
        } catch (Exception e) {
            Log.e(TAG, "② extract error: " + e.getMessage(), e);
            return failed("extract error: " + e.getMessage(), null, null);
        }
        if (info == null || info.getFormats().isEmpty()) {
            return failed("no formats extracted", info, null);
        }
        Log.i(TAG, "② extracted: " + info.getFormats().size() + " formats, title="
                + info.getTitle());

        // ③ 归档检查（幂等跳过）
        String archiveKey = info.getArchiveKey();
        if (archiveKey != null && opts.shouldArchive(info) && archive.isDownloaded(archiveKey)) {
            Log.i(TAG, "③ archive skip: " + archiveKey);
            DownloadResult r = new DownloadResult(info, null,
                    Collections.<File>emptyList(), null, archiveKey);
            r.error = "已在归档中跳过（已下载过）";
            return r;
        }

        // ④ 选 format
        RequestedFormats requested = stageSelectFormats(info, opts);
        if (requested == null || requested.isEmpty()) {
            return failed("no suitable format selected", info, null);
        }
        Log.i(TAG, "④ selected " + requested.size() + " format(s), needsMerge=" + requested.needsMerge);

        // ⑤ 下载（双轨时下载两条到 .video.part / .audio.part）
        notifyStatus(callback, "downloading", "下载中…");
        DownloadResult result = stageDownload(info, requested, opts, callback);
        if (!result.hasDownloadedSources()) {
            return result;
        }
        Log.i(TAG, "⑤ downloaded " + result.downloadedFiles.size() + " file(s)");

        // ⑥ 后处理
        stagePostProcess(result);
        if (!result.isSuccess()) {
            return result;
        }

        // ⑥.5 归档写入
        if (archiveKey != null && opts.shouldArchive(info)) {
            archive.markDownloaded(archiveKey);
            Log.i(TAG, "⑥.5 archived: " + archiveKey);
        }

        // ⑥.6 完整性校验（M1 复用现有 IntegrityVerifier，与 BackgroundDownloadService 一致）
        if (result.outputFile != null) {
            IntegrityVerifier.VerifyResult vr = IntegrityVerifier.verify(result.outputFile);
            if (!vr.valid) {
                Log.w(TAG, "integrity check failed: " + vr.errorMessage);
                result.error = "完整性校验失败: " + vr.errorMessage;
            }
        }

        // ⑥.7 P0 B2：通知 MediaScanner 扫描已下载文件，使其在相册中可见
        if (result.outputFile != null) {
            try {
                MediaScannerNotifier.scanFile(context, result.outputFile, null);
                // 双轨场景：audio 临时文件也扫描（M2 合并后由合并产物替代）
                for (File f : result.getDownloadedFiles()) {
                    if (f != null && f.exists() && f != result.outputFile) {
                        MediaScannerNotifier.scanFile(context, f, null);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "MediaScanner 通知失败: " + e.getMessage());
            }
        }

        notifyComplete(callback, result.outputFile != null
                ? result.outputFile.getAbsolutePath() : null);
        return result;
    }

    // ===== 阶段实现 =====

    /** ① 匹配 Extractor。 */
    protected Extractor stageMatchExtractor(String url) {
        return ExtractorRegistry.match(url);
    }

    /** ② 提取 info_dict。 */
    protected ExtractorResult stageExtract(Extractor extractor, String url)
            throws ExtractorException, java.io.IOException {
        return extractor.extract(url);
    }

    /** ③ 归档检查（在主流程内联实现，无需独立方法，保留为 hook 点）。 */
    protected boolean stageArchiveCheck(ExtractorResult info, DownloadOptions opts) {
        String key = info.getArchiveKey();
        return key != null && opts.shouldArchive(info) && archive.isDownloaded(key);
    }

    /** ④ 选 format。 */
    protected RequestedFormats stageSelectFormats(ExtractorResult info, DownloadOptions opts) {
        return formatSelector.select(info, opts);
    }

    /**
     * ⑤ 下载。
     *
     * <p>双轨：分别下载 video/audio 临时轨道，后处理链合并为最终 outFile。</p>
     *
     * <p>单轨：直接下载到 outFile。</p>
     */
    protected DownloadResult stageDownload(ExtractorResult info, RequestedFormats requested,
                                              DownloadOptions opts, DownloadProgressCallback callback) {
        File outDir = ensureOutDir(opts);
        if (outDir == null) {
            return failed("无法创建下载目录", info, requested);
        }
        String baseName = info.getBaseFilename() != null
                ? info.getBaseFilename() : (info.getId() != null ? info.getId() : "media");
        baseName = sanitizeFileName(baseName);

        List<File> downloaded = new ArrayList<>();
        ExtractorResult.Format primary = requested.primary();
        try {
            String primaryExt = primary.ext != null && !primary.ext.isEmpty() ? primary.ext : "mp4";
            File finalOut = requested.needsMerge
                    ? resolveOutputFile(outDir, baseName, "mp4", opts.conflictStrategy)
                    : resolveOutputFile(outDir, baseName, primaryExt, opts.conflictStrategy);
            if (finalOut == null) {
                return failed("输出文件已存在，且冲突策略为跳过", info, requested);
            }

            File primaryOut = requested.needsMerge
                    ? new File(finalOut.getParentFile(), stripExtension(finalOut.getName())
                    + ".video." + primaryExt)
                    : finalOut;
            FileDownloader downloader = DownloaderRegistry.route(primary);
            downloader.download(primary, primaryOut, callback);
            downloaded.add(primaryOut);

            if (requested.needsMerge && requested.audio() != null) {
                ExtractorResult.Format audioFmt = requested.audio();
                String audioExt = audioFmt.ext != null && !audioFmt.ext.isEmpty() ? audioFmt.ext : "m4a";
                File audioOut = new File(primaryOut.getParentFile(),
                        stripExtension(finalOut.getName()) + ".audio." + audioExt);
                FileDownloader audioDownloader = DownloaderRegistry.route(audioFmt);
                audioDownloader.download(audioFmt, audioOut, callback);
                downloaded.add(audioOut);
                Log.i(TAG, "双轨下载完成，进入 FFmpegMergerPP: " + finalOut.getName());
            }

            return new DownloadResult(info, requested, downloaded, finalOut, info.getArchiveKey());
        } catch (Exception e) {
            Log.e(TAG, "stageDownload failed: " + e.getMessage(), e);
            DownloadResult r = new DownloadResult(info, requested, downloaded, null, info.getArchiveKey());
            r.error = "下载失败: " + e.getMessage();
            return r;
        }
    }

    /** ⑥ 后处理（M1 仅框架）。 */
    protected void stagePostProcess(DownloadResult result) {
        ppChain.runAll(result, PostProcessWhen.POST_PROCESS);
        ppChain.runAll(result, PostProcessWhen.AFTER_MOVE);
    }

    // ===== 工具 =====

    /** 获取后处理链（用于注册 PP）。 */
    public PostProcessorChain postProcessorChain() { return ppChain; }

    /** 获取归档实例。 */
    public DownloadArchive archive() { return archive; }

    private File ensureOutDir(DownloadOptions opts) {
        File dir = opts.outDir;
        if (dir == null) {
            // 默认 /storage/emulated/0/DataRecovery/Downloads
            File external = android.os.Environment.getExternalStorageDirectory();
            if (external != null && "mounted".equals(android.os.Environment.getExternalStorageState())) {
                dir = new File(external, DownloadOptions.DEFAULT_OUT_DIR_NAME);
            } else if (context != null) {
                File base = context.getExternalFilesDir(null);
                if (base == null) base = context.getFilesDir();
                dir = new File(base, DownloadOptions.DEFAULT_OUT_DIR_NAME);
            }
        }
        if (dir == null) return null;
        if (!dir.exists() && !dir.mkdirs()) return null;

        // P0 B1: 写入 .nomedia 避免临时文件污染相册
        ensureNomedia(dir);
        return dir;
    }

    /** P0 B1: 在目录下写入 .nomedia（已存在则跳过）。 */
    private static void ensureNomedia(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return;
        try {
            File f = new File(dir, ".nomedia");
            if (!f.exists()) f.createNewFile();
        } catch (Exception ignored) {}
    }

    /**
     * 解析输出文件路径，处理冲突（Q3 ASK/SKIP/RENAME）。
     *
     * <p>M1 实现：ASK 在 YoutubeDL 内退化为 RENAME（弹窗由 UI 层处理，编排器不弹 UI）；
     * SKIP 直接返回 null；RENAME 追加 _1/_2。</p>
     */
    private File resolveOutputFile(File dir, String baseName, String ext,
                                     FileConflictStrategy strategy) {
        File candidate = new File(dir, baseName + "." + ext);
        if (!candidate.exists()) return candidate;

        switch (strategy) {
            case SKIP:
                return null;
            case RENAME:
            case ASK:
            default:
                // 编排器不弹 UI，ASK 退化为 RENAME
                int counter = 1;
                while (true) {
                    File f = new File(dir, baseName + "_" + counter + "." + ext);
                    if (!f.exists()) return f;
                    counter++;
                    if (counter > 9999) return null; // 防御性
                }
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "media";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (cleaned.length() > 200) cleaned = cleaned.substring(0, 200);
        return cleaned;
    }

    private static String stripExtension(String name) {
        if (name == null) return "media";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void notifyStatus(DownloadProgressCallback cb, String status, String message) {
        if (cb != null) cb.onStatusChanged(status, message);
    }

    private void notifyComplete(DownloadProgressCallback cb, String path) {
        if (cb != null && path != null) cb.onComplete(path);
    }

    private DownloadResult failed(String error, ExtractorResult info, RequestedFormats requested) {
        DownloadResult r = new DownloadResult(info, requested,
                Collections.<File>emptyList(), null,
                info != null ? info.getArchiveKey() : null);
        r.error = error;
        Log.w(TAG, "failed: " + error);
        return r;
    }
}
