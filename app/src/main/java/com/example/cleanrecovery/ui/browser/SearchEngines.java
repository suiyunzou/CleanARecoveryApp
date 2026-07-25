package com.example.cleanrecovery.ui.browser;

/**
 * 搜索引擎解析（VIA 风格：地址栏非 URL 时用所选引擎搜索）。
 *
 * <p>纯 Java，无新依赖。索引与 {@link BrowserPrefs#searchEngine()} 对齐。</p>
 */
public final class SearchEngines {

    /** 搜索引擎索引：0=YouTube,1=Google,2=Bing,3=Baidu,4=DuckDuckGo,5=自定义。 */
    public static final int YOUTUBE = 0;
    public static final int GOOGLE = 1;
    public static final int BING = 2;
    public static final int BAIDU = 3;
    public static final int DUCKDUCKGO = 4;
    public static final int CUSTOM = 5;

    private SearchEngines() {
    }

    /** 返回指定引擎的搜索前缀（关键词拼接到末尾，需先 URL 编码）。 */
    public static String prefix(int engine, String customPrefix) {
        switch (engine) {
            case GOOGLE:
                return "https://www.google.com/search?q=";
            case BING:
                return "https://www.bing.com/search?q=";
            case BAIDU:
                return "https://www.baidu.com/s?wd=";
            case DUCKDUCKGO:
                return "https://duckduckgo.com/?q=";
            case CUSTOM:
                return (customPrefix == null || customPrefix.isEmpty())
                        ? "https://www.google.com/search?q=" : customPrefix;
            case YOUTUBE:
            default:
                return "https://www.youtube.com/results?search_query=";
        }
    }

    /** 便捷重载：从 BrowserPrefs 读取自定义前缀。 */
    public static String prefix(BrowserPrefs prefs, int engine) {
        return prefix(engine, prefs == null ? "" : prefs.searchPrefix());
    }
}
