package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 搜索引擎前缀解析单测（Phase1）。
 *
 * <p>验证地址栏非 URL 输入时，按所选引擎拼出正确搜索 URL（纯逻辑，无 Context 依赖）。</p>
 */
public class SearchEnginesTest {

    @Test
    public void youtubeDefault() {
        assertEquals("https://www.youtube.com/results?search_query=",
                SearchEngines.prefix(SearchEngines.YOUTUBE, ""));
    }

    @Test
    public void google() {
        assertEquals("https://www.google.com/search?udm=14&q=",
                SearchEngines.prefix(SearchEngines.GOOGLE, ""));
    }

    @Test
    public void bing() {
        assertEquals("https://www.bing.com/search?q=",
                SearchEngines.prefix(SearchEngines.BING, ""));
    }

    @Test
    public void baidu() {
        assertEquals("https://www.baidu.com/s?wd=",
                SearchEngines.prefix(SearchEngines.BAIDU, ""));
    }

    @Test
    public void duckduckgo() {
        assertEquals("https://duckduckgo.com/?q=",
                SearchEngines.prefix(SearchEngines.DUCKDUCKGO, ""));
    }

    @Test
    public void customEmptyFallback() {
        // 自定义为空时回退 Google
        assertEquals("https://www.google.com/search?udm=14&q=",
                SearchEngines.prefix(SearchEngines.CUSTOM, ""));
    }

    @Test
    public void customNullFallback() {
        assertEquals("https://www.google.com/search?udm=14&q=",
                SearchEngines.prefix(SearchEngines.CUSTOM, null));
    }

    @Test
    public void customPrefix() {
        assertEquals("https://my.engine/?q=",
                SearchEngines.prefix(SearchEngines.CUSTOM, "https://my.engine/?q="));
    }

    @Test
    public void fullSearchUrl() {
        String url = SearchEngines.prefix(SearchEngines.GOOGLE, "") + "hello";
        assertTrue(url.startsWith("https://www.google.com/search?udm=14&q=hello"));
    }

    @Test
    public void unknownIndexFallsBackToYoutube() {
        assertEquals("https://www.youtube.com/results?search_query=",
                SearchEngines.prefix(999, ""));
    }

    @Test
    public void parseGoogleSearchResult() {
        SearchEngines.SearchResult res = SearchEngines.parseSearchResult("https://www.google.com/search?udm=14&q=City+University+Hong+Kong&oq=city");
        org.junit.Assert.assertNotNull(res);
        assertEquals(SearchEngines.GOOGLE, res.engine);
        assertEquals("City University Hong Kong", res.query);
    }

    @Test
    public void parseBaiduSearchResult() {
        SearchEngines.SearchResult res = SearchEngines.parseSearchResult("https://www.baidu.com/s?wd=%E6%B8%85%E7%90%86&rsv_spt=1");
        org.junit.Assert.assertNotNull(res);
        assertEquals(SearchEngines.BAIDU, res.engine);
        assertEquals("清理", res.query);

        SearchEngines.SearchResult resWord = SearchEngines.parseSearchResult("https://m.baidu.com/s?word=android+studio");
        org.junit.Assert.assertNotNull(resWord);
        assertEquals(SearchEngines.BAIDU, resWord.engine);
        assertEquals("android studio", resWord.query);
    }

    @Test
    public void parseBingSearchResult() {
        SearchEngines.SearchResult res = SearchEngines.parseSearchResult("https://cn.bing.com/search?q=open+source&form=QBLH");
        org.junit.Assert.assertNotNull(res);
        assertEquals(SearchEngines.BING, res.engine);
        assertEquals("open source", res.query);
    }

    @Test
    public void parseDuckDuckGoSearchResult() {
        SearchEngines.SearchResult res = SearchEngines.parseSearchResult("https://duckduckgo.com/?q=privacy+browser&t=h_");
        org.junit.Assert.assertNotNull(res);
        assertEquals(SearchEngines.DUCKDUCKGO, res.engine);
        assertEquals("privacy browser", res.query);
    }

    @Test
    public void parseYahooSearchResult() {
        SearchEngines.SearchResult res = SearchEngines.parseSearchResult("https://search.yahoo.com/search?p=weather+today");
        org.junit.Assert.assertNotNull(res);
        assertEquals(SearchEngines.YAHOO, res.engine);
        assertEquals("weather today", res.query);
    }

    @Test
    public void parseStartPageSearchResult() {
        SearchEngines.SearchResult res = SearchEngines.parseSearchResult("https://startpage.com/do/search?query=via+browser");
        org.junit.Assert.assertNotNull(res);
        assertEquals(SearchEngines.STARTPAGE, res.engine);
        assertEquals("via browser", res.query);
    }

    @Test
    public void parseNonSearchUrlsReturnNull() {
        org.junit.Assert.assertNull(SearchEngines.parseSearchResult(null));
        org.junit.Assert.assertNull(SearchEngines.parseSearchResult(""));
        org.junit.Assert.assertNull(SearchEngines.parseSearchResult("https://example.com/"));
        org.junit.Assert.assertNull(SearchEngines.parseSearchResult("https://github.com/google/guava"));
        org.junit.Assert.assertNull(SearchEngines.parseSearchResult("https://www.google.com/"));
        org.junit.Assert.assertNull(SearchEngines.parseSearchResult("https://www.baidu.com/"));
    }

    @Test
    public void buildSearchUrlWorks() {
        String url = SearchEngines.buildSearchUrl(SearchEngines.BING, "hello world", "");
        assertEquals("https://www.bing.com/search?q=hello+world", url);

        String baidu = SearchEngines.buildSearchUrl(SearchEngines.BAIDU, "谷歌", "");
        assertEquals("https://www.baidu.com/s?wd=%E8%B0%B7%E6%AD%8C", baidu);
    }

    @Test
    public void labelsMatchVia() {
        assertEquals("谷歌", SearchEngines.label(SearchEngines.GOOGLE));
        assertEquals("百度", SearchEngines.label(SearchEngines.BAIDU));
        assertEquals("必应", SearchEngines.label(SearchEngines.BING));
        assertEquals("雅虎", SearchEngines.label(SearchEngines.YAHOO));
        assertEquals("StartPage", SearchEngines.label(SearchEngines.STARTPAGE));
        assertEquals("DuckDuckGo", SearchEngines.label(SearchEngines.DUCKDUCKGO));
    }
}
