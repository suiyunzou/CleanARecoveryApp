package com.example.cleanrecovery.ui.browser;

/**
 * 搜索引擎解析（VIA 风格：地址栏非 URL 时用所选引擎搜索）。
 *
 * <p>纯 Java，无新依赖。索引与 {@link BrowserPrefs#searchEngine()} 对齐。</p>
 */
public final class SearchEngines {

    /** 搜索引擎索引：0=YouTube,1=Google,2=Bing,3=Baidu,4=DuckDuckGo,5=自定义,6=Yahoo,7=StartPage。 */
    public static final int YOUTUBE = 0;
    public static final int GOOGLE = 1;
    public static final int BING = 2;
    public static final int BAIDU = 3;
    public static final int DUCKDUCKGO = 4;
    public static final int CUSTOM = 5;
    public static final int YAHOO = 6;
    public static final int STARTPAGE = 7;

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
            case YAHOO:
                return "https://search.yahoo.com/search?p=";
            case STARTPAGE:
                return "https://startpage.com/do/search?query=";
            case YOUTUBE:
            default:
                return "https://www.youtube.com/results?search_query=";
        }
    }

    public static final class SearchResult {
        public final int engine;
        public final String query;

        public SearchResult(int engine, String query) {
            this.engine = engine;
            this.query = query;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            SearchResult that = (SearchResult) o;
            return engine == that.engine && (query != null ? query.equals(that.query) : that.query == null);
        }

        @Override
        public int hashCode() {
            return 31 * engine + (query != null ? query.hashCode() : 0);
        }

        @Override
        public String toString() {
            return "SearchResult{engine=" + engine + ", query='" + query + "'}";
        }
    }

    /** 返回搜索引擎的人类可读标签。 */
    public static String label(int engine) {
        switch (engine) {
            case GOOGLE: return "谷歌";
            case BAIDU: return "百度";
            case BING: return "必应";
            case YAHOO: return "雅虎";
            case STARTPAGE: return "StartPage";
            case DUCKDUCKGO: return "DuckDuckGo";
            case YOUTUBE: return "YouTube";
            case CUSTOM: return "自定义";
            default: return "搜索";
        }
    }

    /** 根据指定引擎和关键词组装搜索 URL。 */
    public static String buildSearchUrl(int engine, String query, String customPrefix) {
        if (query == null) query = "";
        String encoded = query;
        try {
            encoded = java.net.URLEncoder.encode(query, "UTF-8");
        } catch (Exception ignored) {
        }
        return prefix(engine, customPrefix) + encoded;
    }

    /**
     * 解析给定 URL 是否为已知搜索引擎的结果页，并提取关键词。
     * 若不是搜索引擎或无关键词则返回 null。纯 Java 实现，无外部依赖。
     */
    public static SearchResult parseSearchResult(String url) {
        if (url == null || url.isEmpty()) return null;
        try {
            int schemeIdx = url.indexOf("://");
            if (schemeIdx < 0) return null;
            int hostStart = schemeIdx + 3;
            int hostEnd = url.indexOf('/', hostStart);
            int queryStart = url.indexOf('?');
            if (hostEnd < 0) hostEnd = queryStart >= 0 ? queryStart : url.length();
            if (hostStart >= hostEnd) return null;

            String host = url.substring(hostStart, hostEnd).toLowerCase(java.util.Locale.ROOT);
            String path = (queryStart >= 0 && hostEnd < queryStart)
                    ? url.substring(hostEnd, queryStart)
                    : (hostEnd < url.length() && queryStart < 0 ? url.substring(hostEnd) : "");

            if (queryStart < 0 || queryStart + 1 >= url.length()) {
                return null;
            }
            String queryString = url.substring(queryStart + 1);
            int hashIdx = queryString.indexOf('#');
            if (hashIdx >= 0) {
                queryString = queryString.substring(0, hashIdx);
            }

            if (host.contains("google.") && (path.startsWith("/search") || path.isEmpty())) {
                String q = getQueryParam(queryString, "q");
                if (q != null && !q.trim().isEmpty()) return new SearchResult(GOOGLE, q.trim());
            } else if (host.contains("baidu.com") && (path.contains("/s") || path.isEmpty())) {
                String wd = getQueryParam(queryString, "wd");
                if (wd == null) wd = getQueryParam(queryString, "word");
                if (wd != null && !wd.trim().isEmpty()) return new SearchResult(BAIDU, wd.trim());
            } else if (host.contains("bing.com") && (path.startsWith("/search") || path.isEmpty())) {
                String q = getQueryParam(queryString, "q");
                if (q != null && !q.trim().isEmpty()) return new SearchResult(BING, q.trim());
            } else if (host.contains("yahoo.com") && (path.startsWith("/search") || path.isEmpty())) {
                String p = getQueryParam(queryString, "p");
                if (p != null && !p.trim().isEmpty()) return new SearchResult(YAHOO, p.trim());
            } else if (host.contains("duckduckgo.com")) {
                String q = getQueryParam(queryString, "q");
                if (q != null && !q.trim().isEmpty()) return new SearchResult(DUCKDUCKGO, q.trim());
            } else if (host.contains("startpage.com") && (path.contains("/search") || path.startsWith("/do/search") || path.startsWith("/sp/search") || path.isEmpty())) {
                String q = getQueryParam(queryString, "query");
                if (q == null) q = getQueryParam(queryString, "q");
                if (q != null && !q.trim().isEmpty()) return new SearchResult(STARTPAGE, q.trim());
            } else if (host.contains("youtube.com") && path.startsWith("/results")) {
                String q = getQueryParam(queryString, "search_query");
                if (q != null && !q.trim().isEmpty()) return new SearchResult(YOUTUBE, q.trim());
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String getQueryParam(String queryString, String param) {
        if (queryString == null || queryString.isEmpty() || param == null || param.isEmpty()) return null;
        for (String pair : queryString.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key = pair.substring(0, eq);
                if (key.equalsIgnoreCase(param)) {
                    String val = pair.substring(eq + 1);
                    try {
                        return java.net.URLDecoder.decode(val.replace("+", " "), "UTF-8");
                    } catch (Exception e) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    /** 便捷重载：从 BrowserPrefs 读取自定义前缀。 */
    public static String prefix(BrowserPrefs prefs, int engine) {
        return prefix(engine, prefs == null ? "" : prefs.searchPrefix());
    }
}
