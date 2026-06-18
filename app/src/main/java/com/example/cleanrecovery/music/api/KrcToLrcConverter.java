package com.example.cleanrecovery.music.api;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * KRC 逐字歌词到 LRC 行级歌词的格式转换器。
 *
 * <p>KRC 解码后的明文格式示例：</p>
 * <pre>
 * [id:0]
 * [ti:歌曲名]
 * [ar:歌手]
 * [al:专辑]
 * [by:酷狗歌词]
 * [offset:0]
 * [0,1000]歌(0,500)词(500,500)
 * [1000,2000]下(0,500)一(500,500)行(1000,500)歌(1500,500)词(2000,0)
 * </pre>
 *
 * <p>其中：</p>
 * <ul>
 *   <li>{@code [ti:...], [ar:...], [al:...], [by:...], [offset:...]} 为元数据行</li>
 *   <li>{@code [startMs,durationMs]} 为行时间戳（毫秒）</li>
 *   <li>{@code 字(offsetMs,durationMs)} 为逐字时间戳</li>
 * </ul>
 *
 * <p>转换后的 LRC 格式示例：</p>
 * <pre>
 * [ti:歌曲名]
 * [ar:歌手]
 * [al:专辑]
 * [00:00.00]歌词
 * [00:01.00]下一行歌词
 * </pre>
 *
 * <p>转换规则：</p>
 * <ol>
 *   <li>保留元数据行（[ti:], [ar:], [al:], [by:], [offset:]）</li>
 *   <li>将行时间戳 [startMs,durationMs] 转换为 LRC 时间戳 [mm:ss.xx]</li>
 *   <li>提取行内所有逐字歌词文本，拼接为完整行文本</li>
 *   <li>丢弃逐字时间戳信息（LRC 不支持逐字精度）</li>
 * </ol>
 */
public final class KrcToLrcConverter {

    private KrcToLrcConverter() {
        // 工具类，禁止实例化
    }

    /** KRC 行时间戳正则：[startMs,durationMs] */
    private static final Pattern LINE_TIMESTAMP =
            Pattern.compile("^\\[(\\d+),(\\d+)]");

    /** KRC 逐字时间戳正则：字(offsetMs,durationMs) */
    private static final Pattern WORD_TIMESTAMP =
            Pattern.compile("([^()\\[\\]]+)\\((\\d+),(\\d+)\\)");

    /** KRC 元数据行正则：[key:value] */
    private static final Pattern METADATA =
            Pattern.compile("^\\[(\\w+):(.*)]$");

    /**
     * 将 KRC 明文转换为 LRC 格式。
     *
     * @param krcText KRC 解码后的明文
     * @return LRC 格式文本，输入为空返回空字符串
     */
    public static String convert(String krcText) {
        if (krcText == null || krcText.isEmpty()) {
            return "";
        }

        StringBuilder lrc = new StringBuilder();
        String[] lines = krcText.split("\n");

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            // 1. 元数据行 [ti:...], [ar:...], [al:...], [by:...], [offset:...]
            Matcher metaMatcher = METADATA.matcher(trimmed);
            if (metaMatcher.matches()) {
                String key = metaMatcher.group(1);
                String value = metaMatcher.group(2);
                // 保留 LRC 兼容的元数据
                if ("ti".equals(key) || "ar".equals(key) || "al".equals(key)
                        || "by".equals(key) || "offset".equals(key)) {
                    lrc.append("[").append(key).append(":").append(value).append("]\n");
                }
                continue;
            }

            // 2. 歌词行 [startMs,durationMs]字(offset,dur)字(offset,dur)...
            Matcher lineMatcher = LINE_TIMESTAMP.matcher(trimmed);
            if (!lineMatcher.find()) {
                continue;
            }

            try {
                long startMs = Long.parseLong(lineMatcher.group(1));
                String lrcTimestamp = msToLrcTimestamp(startMs);

                // 提取行内所有歌词文本（去除逐字时间戳）
                String lyricText = extractLyricText(trimmed.substring(lineMatcher.end()));

                if (!lyricText.isEmpty()) {
                    lrc.append("[").append(lrcTimestamp).append("]")
                            .append(lyricText).append("\n");
                }
            } catch (NumberFormatException e) {
                // 时间戳解析失败，跳过该行
                continue;
            }
        }

        return lrc.toString();
    }

    /**
     * 从 KRC 行内容中提取歌词文本，去除逐字时间戳。
     *
     * <p>例如输入 {@code "歌(0,500)词(500,500)"}，输出 {@code "歌词"}。</p>
     *
     * @param content KRC 行内容（去除行时间戳后）
     * @return 纯歌词文本
     */
    private static String extractLyricText(String content) {
        StringBuilder text = new StringBuilder();
        Matcher wordMatcher = WORD_TIMESTAMP.matcher(content);
        int lastEnd = 0;
        while (wordMatcher.find()) {
            // 添加匹配前的非时间戳文本（如空格）
            if (wordMatcher.start() > lastEnd) {
                text.append(content, lastEnd, wordMatcher.start());
            }
            text.append(wordMatcher.group(1));
            lastEnd = wordMatcher.end();
        }
        // 添加末尾的非时间戳文本
        if (lastEnd < content.length()) {
            text.append(content.substring(lastEnd));
        }
        return text.toString().trim();
    }

    /**
     * 将毫秒时间戳转换为 LRC 格式时间戳 [mm:ss.xx]。
     *
     * <p>例如：1234ms → "00:01.23"，65000ms → "01:05.00"</p>
     *
     * @param ms 毫秒时间戳
     * @return LRC 格式时间戳字符串
     */
    private static String msToLrcTimestamp(long ms) {
        if (ms < 0) ms = 0;
        long totalSeconds = ms / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        long centiseconds = (ms % 1000) / 10;
        return String.format("%02d:%02d.%02d", minutes, seconds, centiseconds);
    }
}
