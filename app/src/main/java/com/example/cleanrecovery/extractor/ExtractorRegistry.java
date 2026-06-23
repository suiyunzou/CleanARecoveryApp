package com.example.cleanrecovery.extractor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 提取器注册表（对应 yt-dlp YoutubeDL._ies 字典）。
 *
 * <p>yt-dlp 在启动时加载所有提取器，根据 URL 的 _VALID_URL 正则匹配。
 * 本类按注册顺序遍历，第一个 {@link Extractor#suitable(String)} 返回 true
 * 的提取器负责处理该 URL；若无匹配，回退到 {@link GenericExtractor}。</p>
 *
 * <p>使用方式：</p>
 * <pre>{@code
 * Extractor ex = ExtractorRegistry.match("https://www.bilibili.com/video/BV1xx");
 * ExtractorResult result = ex.extract(url);
 * }</pre>
 */
public final class ExtractorRegistry {

    private static final List<Extractor> EXTRACTORS = new ArrayList<>();
    private static final GenericExtractor GENERIC = new GenericExtractor();

    static {
        // 注册顺序：专用提取器在前，Generic 兜底在最后
        register(new BilibiliExtractor());
        register(new DouyinExtractor());
        register(new TikTokExtractor());
        register(new YouTubeExtractor());
    }

    private ExtractorRegistry() {}

    /** 注册提取器（对应 yt-dlp add_default_info_extractors）。 */
    public static synchronized void register(Extractor extractor) {
        if (extractor == null) throw new NullPointerException("extractor");
        EXTRACTORS.add(extractor);
    }

    /** 获取所有已注册的专用提取器（不含 Generic）。 */
    public static synchronized List<Extractor> getExtractors() {
        return Collections.unmodifiableList(new ArrayList<>(EXTRACTORS));
    }

    /**
     * 匹配提取器（对应 yt-dlp YoutubeDL.get_info_extractor）。
     *
     * <p>按注册顺序遍历，返回第一个 suitable 的提取器；
     * 若无匹配，返回 {@link GenericExtractor} 兜底。</p>
     */
    public static Extractor match(String url) {
        if (url == null || url.trim().isEmpty()) return GENERIC;
        for (Extractor ex : EXTRACTORS) {
            if (ex.suitable(url)) return ex;
        }
        return GENERIC;
    }

    /** 获取 Generic 兜底提取器。 */
    public static GenericExtractor getGeneric() {
        return GENERIC;
    }
}
