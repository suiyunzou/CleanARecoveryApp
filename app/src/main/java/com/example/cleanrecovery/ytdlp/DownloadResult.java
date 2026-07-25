package com.example.cleanrecovery.ytdlp;

import com.example.cleanrecovery.extractor.ExtractorResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 下载结果（对应 yt-dlp download 阶段产物，传给 PostProcessor）。
 *
 * <p>承载：已下载文件列表（双轨场景有 2 个临时文件）、原始 info_dict、
 * 选定的 RequestedFormats、归档键。后处理链据此决定合并/嵌入元数据等动作。</p>
 */
public final class DownloadResult {

    /** 已下载的文件列表（双轨合并前为 2 个，单轨为 1 个）。 */
    public final List<File> downloadedFiles;
    /** 最终输出文件（合并后或单轨直出，PostProcessor 可改写）。 */
    public File outputFile;
    /** 原始 info_dict。 */
    public final ExtractorResult info;
    /** 选定的 format 集合。 */
    public final RequestedFormats requestedFormats;
    /** 归档键（extractor_key+id），用于 {@link DownloadArchive}。 */
    public final String archiveKey;
    /** 是否需要合并（双轨）。 */
    public final boolean needsMerge;
    /** 错误信息（null 表示成功）。 */
    public String error;

    public DownloadResult(ExtractorResult info, RequestedFormats requestedFormats,
                          List<File> downloadedFiles, File outputFile, String archiveKey) {
        this.info = info;
        this.requestedFormats = requestedFormats;
        this.downloadedFiles = downloadedFiles != null
                ? new ArrayList<>(downloadedFiles) : new ArrayList<>();
        this.outputFile = outputFile;
        this.archiveKey = archiveKey;
        this.needsMerge = requestedFormats != null && requestedFormats.needsMerge;
        this.error = null;
    }

    /** 是否成功（无 error 且至少有一个文件）。 */
    public boolean isSuccess() {
        return error == null && outputFile != null && outputFile.exists();
    }

    /** 下载阶段是否已拿到源文件；双轨合并前最终 outputFile 可能还不存在。 */
    public boolean hasDownloadedSources() {
        return error == null && !downloadedFiles.isEmpty();
    }

    /** 主下载文件（合并前 = videoOnly 或唯一 format 的输出）。 */
    public File primaryFile() {
        return downloadedFiles.isEmpty() ? null : downloadedFiles.get(0);
    }

    /** 双轨场景的音频文件。 */
    public File audioFile() {
        return downloadedFiles.size() >= 2 ? downloadedFiles.get(1) : null;
    }

    /** 不可变文件列表快照。 */
    public List<File> getDownloadedFiles() {
        return Collections.unmodifiableList(downloadedFiles);
    }
}
