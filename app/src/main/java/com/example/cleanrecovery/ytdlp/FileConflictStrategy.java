package com.example.cleanrecovery.ytdlp;

/**
 * 已存在文件冲突处理策略（对应计划 Q3 已确认决策）。
 *
 * <p>同一视频重复下载时：</p>
 * <ul>
 *   <li>{@link #ASK} 默认弹窗询问（最安全，不丢数据也不浪费）</li>
 *   <li>{@link #SKIP} 始终跳过（配合 {@link DownloadArchive} 实现幂等）</li>
 *   <li>{@link #RENAME} 始终重命名（追加 {@code _1} / {@code _2}）</li>
 * </ul>
 */
public enum FileConflictStrategy {
    /** 弹窗询问（默认）。 */
    ASK,
    /** 始终跳过。 */
    SKIP,
    /** 始终重命名。 */
    RENAME
}
