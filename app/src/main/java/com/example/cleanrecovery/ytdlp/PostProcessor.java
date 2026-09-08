package com.example.cleanrecovery.ytdlp;

/**
 * 后处理器接口（对应 yt-dlp {@code PostProcessor}）。
 *
 * <p>实现类示例（M2-M5）：</p>
 * <ul>
 *   <li>{@code FFmpegMergerPP}：双轨合并（bv+ba → mp4）</li>
 *   <li>{@code FFmpegVideoRemuxerPP}：ts → mp4 容器转换</li>
 *   <li>{@code FFmpegExtractAudioPP}：提取音频</li>
 *   <li>{@code FFmpegMetadataPP}：嵌入标题/上传者/描述等元数据</li>
 *   <li>{@code EmbedThumbnailPP}：嵌入封面缩略图</li>
 * </ul>
 *
 * <p>M2 已接入合并、转封装和成功清理 PP。libav JNI 桥不可用时，
 * 合并/转封装走 Android MediaMuxer 后备路径。</p>
 */
public interface PostProcessor {

    /**
     * 执行后处理，返回更新后的 result（可能改写 outputFile、追加文件）。
     *
     * @param result 下载阶段产物
     * @return 后处理后的结果（通常是同一对象，字段已更新）
     * @throws PostProcessorException 后处理失败（链调度器决定是否中断）
     */
    DownloadResult process(DownloadResult result) throws PostProcessorException;

    /** 该 PP 应在哪个阶段执行。 */
    PostProcessWhen when();

    /** PP 名称（用于日志）。 */
    String name();

    /** 是否在异常路径也执行（如清理临时文件的 PP）。 */
    default boolean runOnError() { return false; }
}
