package com.example.cleanrecovery.ytdlp;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.util.Log;

import java.io.File;

/**
 * 媒体扫描通知器（P0 B2）。
 *
 * <p>下载完成后通知系统 {@link MediaStore} 扫描新文件，使其在相册/文件管理器中立即可见。
 * 对应 yt-dlp 的 post-processor 思路：文件落盘后需触发媒体索引刷新。</p>
 *
 * <h3>策略</h3>
 * <ul>
 *   <li>所有 API 级别：调用 {@link MediaScannerConnection#scanFile} 扫描文件路径，
 *       让系统 MediaScanner 重新索引该文件。这是最通用的方式，无需 ContentResolver 插入。</li>
 *   <li>Android 10+（API 29+）：由于应用使用 {@code MANAGE_EXTERNAL_STORAGE} 或
 *       legacy storage 直接写 File 路径到共享存储，{@code scanFile} 已足够让
 *       相册识别。无需额外的 {@code MediaStore.insert()}。</li>
 * </ul>
 *
 * <p><b>注意</b>：下载目录下的 {@code .nomedia} 文件（B1）会阻止 MediaScanner 扫描
 * <b>临时 .part 文件</b>，但最终输出文件需要被扫描。由于 {@code .nomedia} 是目录级的，
 * 它会阻止该目录下所有文件被扫描。因此本方法对最终文件显式调用 {@code scanFile}
 * 来覆盖 {@code .nomedia} 的抑制（{@code scanFile} 是显式单文件扫描，不受 {@code .nomedia} 影响）。</p>
 */
public final class MediaScannerNotifier {

    private static final String TAG = "MediaScannerNotifier";

    private MediaScannerNotifier() {}

    /**
     * 通知系统扫描已下载的文件，使其在相册中可见。
     *
     * @param context  上下文
     * @param file     已下载的文件（最终输出，非 .part）
     * @param mimeType MIME 类型（如 "video/mp4"），未知时传 null 让系统自动推断
     */
    public static void scanFile(Context context, File file, String mimeType) {
        if (context == null || file == null || !file.exists()) {
            Log.w(TAG, "scanFile 跳过：context/file 无效或文件不存在");
            return;
        }
        String path = file.getAbsolutePath();
        String mime = (mimeType != null && !mimeType.isEmpty()) ? mimeType : guessMime(file);
        try {
            // 显式单文件扫描 —— 不受 .nomedia 影响（.nomedia 仅影响目录级自动扫描）
            MediaScannerConnection.scanFile(context, new String[]{path}, new String[]{mime}, (p, uri) -> {
                Log.i(TAG, "MediaScanner 已索引: " + p + " uri=" + uri);
            });
        } catch (Exception e) {
            Log.w(TAG, "MediaScannerConnection.scanFile 失败: " + e.getMessage());
        }
    }

    /**
     * 批量扫描多个文件（双轨下载场景：video + audio 分别下载后合并）。
     */
    public static void scanFiles(Context context, File[] files, String mimeType) {
        if (context == null || files == null) return;
        for (File f : files) {
            scanFile(context, f, mimeType);
        }
    }

    /**
     * 根据文件扩展名猜测 MIME 类型。
     */
    private static String guessMime(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".m4v")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".ts")) return "video/mp2t";
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".aac")) return "audio/aac";
        if (name.endsWith(".flac")) return "audio/flac";
        return "*/*";
    }
}
