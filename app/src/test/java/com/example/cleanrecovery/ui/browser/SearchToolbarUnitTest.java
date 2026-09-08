package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * VIA 搜索工具栏与切擎纯逻辑单测：
 * 验证搜索结果页特征提取、关键词反解、跨引擎即时换擎 URL 构造以及标签文本对齐。
 */
public class SearchToolbarUnitTest {

    @Test
    public void defaultToolbarEngines_coverAllViaEnginesInExactOrder() {
        int[] engines = BrowserPrefs.DEFAULT_TOOLBAR_ENGINES;
        assertEquals(6, engines.length);
        assertEquals(SearchEngines.GOOGLE, engines[0]);
        assertEquals(SearchEngines.BAIDU, engines[1]);
        assertEquals(SearchEngines.BING, engines[2]);
        assertEquals(SearchEngines.YAHOO, engines[3]);
        assertEquals(SearchEngines.STARTPAGE, engines[4]);
        assertEquals(SearchEngines.DUCKDUCKGO, engines[5]);
    }

    @Test
    public void engineLabels_strictlyMatchViaChineseLocalization() {
        assertEquals("谷歌", SearchEngines.label(SearchEngines.GOOGLE));
        assertEquals("百度", SearchEngines.label(SearchEngines.BAIDU));
        assertEquals("必应", SearchEngines.label(SearchEngines.BING));
        assertEquals("雅虎", SearchEngines.label(SearchEngines.YAHOO));
        assertEquals("StartPage", SearchEngines.label(SearchEngines.STARTPAGE));
        assertEquals("DuckDuckGo", SearchEngines.label(SearchEngines.DUCKDUCKGO));
    }

    @Test
    public void parseSearchResult_extractsQueryAndEngineAccurately() {
        // 谷歌测试
        SearchEngines.SearchResult googleRes = SearchEngines.parseSearchResult(
                "https://www.google.com/search?q=android+webview+cookie&client=ms-android");
        assertNotNull(googleRes);
        assertEquals(SearchEngines.GOOGLE, googleRes.engine);
        assertEquals("android webview cookie", googleRes.query);

        // 百度测试 (wd 与 word)
        SearchEngines.SearchResult baiduRes1 = SearchEngines.parseSearchResult(
                "https://www.baidu.com/s?wd=%E7%99%BE%E5%BA%A6%E6%90%9C%E7%B4%A2&ie=utf-8");
        assertNotNull(baiduRes1);
        assertEquals(SearchEngines.BAIDU, baiduRes1.engine);
        assertEquals("百度搜索", baiduRes1.query);

        SearchEngines.SearchResult baiduRes2 = SearchEngines.parseSearchResult(
                "https://m.baidu.com/from=844b/s?word=kotlin+coroutines");
        assertNotNull(baiduRes2);
        assertEquals(SearchEngines.BAIDU, baiduRes2.engine);
        assertEquals("kotlin coroutines", baiduRes2.query);

        // 必应测试
        SearchEngines.SearchResult bingRes = SearchEngines.parseSearchResult(
                "https://cn.bing.com/search?q=clean+recovery&form=QBRE");
        assertNotNull(bingRes);
        assertEquals(SearchEngines.BING, bingRes.engine);
        assertEquals("clean recovery", bingRes.query);

        // 雅虎测试
        SearchEngines.SearchResult yahooRes = SearchEngines.parseSearchResult(
                "https://search.yahoo.com/search?p=jetpack+compose");
        assertNotNull(yahooRes);
        assertEquals(SearchEngines.YAHOO, yahooRes.engine);
        assertEquals("jetpack compose", yahooRes.query);

        // DuckDuckGo 测试
        SearchEngines.SearchResult ddgRes = SearchEngines.parseSearchResult(
                "https://duckduckgo.com/?q=privacy+protection&ia=web");
        assertNotNull(ddgRes);
        assertEquals(SearchEngines.DUCKDUCKGO, ddgRes.engine);
        assertEquals("privacy protection", ddgRes.query);

        // StartPage 测试
        SearchEngines.SearchResult spRes = SearchEngines.parseSearchResult(
                "https://www.startpage.com/sp/search?query=via+browser+parity");
        assertNotNull(spRes);
        assertEquals(SearchEngines.STARTPAGE, spRes.engine);
        assertEquals("via browser parity", spRes.query);
    }

    @Test
    public void parseSearchResult_returnsNullForNonSearchPages() {
        assertNull(SearchEngines.parseSearchResult(null));
        assertNull(SearchEngines.parseSearchResult(""));
        assertNull(SearchEngines.parseSearchResult("about:blank"));
        assertNull(SearchEngines.parseSearchResult("https://www.google.com/"));
        assertNull(SearchEngines.parseSearchResult("https://www.baidu.com/"));
        assertNull(SearchEngines.parseSearchResult("https://cn.bing.com/"));
        assertNull(SearchEngines.parseSearchResult("https://github.com/trending"));
        assertNull(SearchEngines.parseSearchResult("https://news.ycombinator.com/item?id=12345"));
        assertNull(SearchEngines.parseSearchResult("https://www.google.com/maps/@37.77,-122.41,14z"));
    }

    @Test
    public void switchEngine_constructsCorrectTargetUrl() {
        String query = "开源 浏览器 架构";
        // 从百度切到谷歌
        String googleUrl = SearchEngines.buildSearchUrl(SearchEngines.GOOGLE, query, "");
        SearchEngines.SearchResult googleParsed = SearchEngines.parseSearchResult(googleUrl);
        assertNotNull(googleParsed);
        assertEquals(SearchEngines.GOOGLE, googleParsed.engine);
        assertEquals(query, googleParsed.query);

        // 从谷歌切到必应
        String bingUrl = SearchEngines.buildSearchUrl(SearchEngines.BING, query, "");
        SearchEngines.SearchResult bingParsed = SearchEngines.parseSearchResult(bingUrl);
        assertNotNull(bingParsed);
        assertEquals(SearchEngines.BING, bingParsed.engine);
        assertEquals(query, bingParsed.query);

        // 从必应切到 DuckDuckGo
        String ddgUrl = SearchEngines.buildSearchUrl(SearchEngines.DUCKDUCKGO, query, "");
        SearchEngines.SearchResult ddgParsed = SearchEngines.parseSearchResult(ddgUrl);
        assertNotNull(ddgParsed);
        assertEquals(SearchEngines.DUCKDUCKGO, ddgParsed.engine);
        assertEquals(query, ddgParsed.query);
    }
}
