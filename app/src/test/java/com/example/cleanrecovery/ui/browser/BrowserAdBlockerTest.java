package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import java.util.Collections;
import java.util.HashSet;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BrowserAdBlockerTest {
    @Test
    public void blocksKnownHostAndSubdomainOnly() {
        assertTrue(BrowserAdBlocker.isBlockedHost(
                "pagead2.googlesyndication.com", Collections.emptySet()));
        assertFalse(BrowserAdBlocker.isBlockedHost(
                "notgooglesyndication.com", Collections.emptySet()));
    }

    @Test
    public void blocksCustomHostCaseInsensitively() {
        HashSet<String> custom = new HashSet<>();
        custom.add("Ads.Example.com");
        assertTrue(BrowserAdBlocker.isBlockedHost("cdn.ads.example.com", custom));
        assertFalse(BrowserAdBlocker.isBlockedHost("example.com", custom));
    }
    @Test
    public void blocksKnownUrlPartsAndCustomRules() {
        assertTrue(BrowserAdBlocker.isBlocked(
                "cdn.example.com",
                "https://cdn.example.com/static/ads/banner.js",
                Collections.emptySet(),
                Collections.emptySet()));

        HashSet<String> rules = new HashSet<>();
        rules.add("*promo-slot*");
        assertTrue(BrowserAdBlocker.isBlocked(
                "www.example.com",
                "https://www.example.com/a/promo-slot/index.js",
                Collections.emptySet(),
                rules));
        assertFalse(BrowserAdBlocker.isBlocked(
                "www.example.com",
                "https://www.example.com/content/index.js",
                Collections.emptySet(),
                rules));
    }

    @Test
    public void supportsAdblockStyleDomainRules() {
        HashSet<String> rules = new HashSet<>();
        rules.add("||ads.example.net^");
        assertTrue(BrowserAdBlocker.isBlocked(
                "img.ads.example.net",
                "https://img.ads.example.net/a.js",
                Collections.emptySet(),
                rules));
        assertFalse(BrowserAdBlocker.isBlocked(
                "notads.example.net",
                "https://notads.example.net/a.js",
                Collections.emptySet(),
                rules));
    }

}
