package com.example.cleanrecovery.ytdlp;

import android.content.Context;

/**
 * 智能后处理规划器（替代 yt-dlp 的 --ppa）。
 *
 * <p>当前 M2 注册合并、转封装和成功清理三个处理器。每个处理器自己判断是否适用，
 * 因此链可以固定注册，运行时按 {@link DownloadResult} 自动 no-op。</p>
 */
public final class SmartPostProcessor {

    private final Context context;

    public SmartPostProcessor(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    public void registerDefaults(PostProcessorChain chain) {
        if (chain == null) {
            return;
        }
        chain.register(new FFmpegMergerPP(context));
        chain.register(new FFmpegVideoRemuxerPP(context));
        chain.register(new TemporaryFilesCleanupPP());
    }
}
