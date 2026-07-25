package com.example.cleanrecovery.ytdlp;

/**
 * 后处理阶段枚举（对应 yt-dlp {@code POSTPROCESS_WHEN}）。
 *
 * <p>定义后处理在该在主流程的哪个阶段执行。链调度器
 * {@link PostProcessorChain#runAll(DownloadResult, PostProcessWhen)}
 * 按阶段分组执行。</p>
 *
 * <p>M1 仅启用 {@link #POST_PROCESS}（主后处理，含合并/嵌入元数据等），
 * 其他阶段为框架预留，M2-M5 按需启用。</p>
 */
public enum PostProcessWhen {
    /** 下载前预处理（如字幕下载、缩略图下载）。 */
    PRE_PROCESS,
    /** 视频过滤后（如只保留音轨场景）。 */
    AFTER_FILTER,
    /** 下载开始前（罕见）。 */
    BEFORE_DL,
    /** 主后处理：合并、remux、嵌入元数据/缩略图（M1 启用）。 */
    POST_PROCESS,
    /** 文件移动到最终位置后（清理临时文件、归档写入）。 */
    AFTER_MOVE,
    /** 整个视频流程完成后（罕见，多用于统计）。 */
    AFTER_VIDEO,
    /** 整个下载流程（含 playlist）完成后。 */
    AFTER_ALL_VIDEO,
    /** 最终清理（异常路径也触发）。 */
    FINAL_CLEANUP
}
