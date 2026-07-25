package com.example.cleanrecovery.ytdlp;

import java.io.File;

/** 成功后清理双轨/转封装临时文件。 */
public final class TemporaryFilesCleanupPP implements PostProcessor {

    @Override
    public DownloadResult process(DownloadResult result) {
        if (result == null || result.error != null || result.outputFile == null) {
            return result;
        }
        File output = result.outputFile;
        for (File f : result.getDownloadedFiles()) {
            if (f != null && !sameFile(f, output) && f.exists()) {
                // 成功路径清理临时文件；失败路径由 Q7 保留 7 天供续传。
                f.delete();
            }
        }
        return result;
    }

    @Override
    public PostProcessWhen when() {
        return PostProcessWhen.AFTER_MOVE;
    }

    @Override
    public String name() {
        return "TemporaryFilesCleanupPP";
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (Exception e) {
            return a.equals(b);
        }
    }
}
