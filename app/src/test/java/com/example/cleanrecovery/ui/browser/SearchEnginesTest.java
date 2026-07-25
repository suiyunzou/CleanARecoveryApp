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
        assertEquals("https://www.google.com/search?q=",
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
        assertEquals("https://www.google.com/search?q=",
                SearchEngines.prefix(SearchEngines.CUSTOM, ""));
    }

    @Test
    public void customNullFallback() {
        assertEquals("https://www.google.com/search?q=",
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
        assertTrue(url.startsWith("https://www.google.com/search?q=hello"));
    }

    @Test
    public void unknownIndexFallsBackToYoutube() {
        assertEquals("https://www.youtube.com/results?search_query=",
                SearchEngines.prefix(999, ""));
    }
}
