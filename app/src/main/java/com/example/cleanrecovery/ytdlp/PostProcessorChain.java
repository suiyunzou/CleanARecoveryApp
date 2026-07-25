package com.example.cleanrecovery.ytdlp;

import android.util.Log;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 后处理器链调度器（对应 yt-dlp {@code run_all_pps}）。
 *
 * <p>按 {@link PostProcessWhen} 阶段分组执行 PP，调用方在主流程相应位置触发：</p>
 * <ol>
 *   <li>下载前 → {@link #runAll(DownloadResult, PostProcessWhen) runAll(_, PRE_PROCESS)}</li>
 *   <li>下载完成 → {@code POST_PROCESS}（合并 / remux / 嵌入元数据）</li>
 *   <li>移动到最终路径 → {@code AFTER_MOVE}（清理临时文件、归档写入）</li>
 * </ol>
 *
 * <p>异常处理：默认 {@link PostProcessWhen#FINAL_CLEANUP} PP 始终执行（{@code runOnError=true}），
 * 其他 PP 抛异常时记录并继续（M1 保守策略：不中断后续 PP，避免一个失败导致整个流程卡住）。</p>
 */
public final class PostProcessorChain {

    private static final String TAG = "PPChain";

    private final Map<PostProcessWhen, List<PostProcessor>> registry =
            new EnumMap<>(PostProcessWhen.class);

    /** 注册 PP 到指定阶段。 */
    public synchronized void register(PostProcessor pp) {
        if (pp == null) return;
        PostProcessWhen when = pp.when();
        if (when == null) when = PostProcessWhen.POST_PROCESS;
        List<PostProcessor> processors = registry.get(when);
        if (processors == null) {
            processors = new ArrayList<>();
            registry.put(when, processors);
        }
        processors.add(pp);
        Log.i(TAG, "registered PP " + pp.name() + " @ " + when);
    }

    /** 注销 PP。 */
    public synchronized void unregister(PostProcessor pp) {
        if (pp == null) return;
        for (List<PostProcessor> list : registry.values()) {
            list.remove(pp);
        }
    }

    /** 已注册 PP 数量。 */
    public synchronized int size() {
        int n = 0;
        for (List<PostProcessor> list : registry.values()) n += list.size();
        return n;
    }

    /**
     * 在指定阶段执行所有 PP（按注册顺序）。
     *
     * <p>异常处理：非 {@code runOnError} PP 抛异常时记录并继续；{@code runOnError} PP 始终执行。</p>
     */
    public synchronized void runAll(DownloadResult result, PostProcessWhen when) {
        if (result == null) return;
        List<PostProcessor> list = registry.get(when);
        if (list == null || list.isEmpty()) return;
        Log.d(TAG, "running " + list.size() + " PPs @ " + when);
        for (PostProcessor pp : list) {
            try {
                pp.process(result);
            } catch (PostProcessorException e) {
                if (pp.runOnError()) {
                    // 该 PP 应在异常路径也执行，但自身又失败：仅记录
                    Log.w(TAG, "PP " + pp.name() + " (runOnError) failed @ " + when + ": " + e.getMessage());
                } else {
                    // 普通 PP 失败：记录但不中断（保守策略）
                    Log.w(TAG, "PP " + pp.name() + " failed @ " + when + ": " + e.getMessage());
                    if (result.error == null) {
                        result.error = pp.name() + " failed: " + e.getMessage();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "PP " + pp.name() + " unexpected error @ " + when + ": " + e.getMessage(), e);
                if (result.error == null) {
                    result.error = pp.name() + " unexpected error: " + e.getMessage();
                }
            }
        }
    }
}
