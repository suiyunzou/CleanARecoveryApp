package com.example.cleanrecovery.ui.browser;

import com.example.cleanrecovery.ui.activity.BrowserActivity;
import org.junit.Test;
import java.util.Locale;
import java.util.Set;
import static org.junit.Assert.*;

public class BrowserP1UnitTest {
    @Test public void cookieExpiryCoversMatchingAncestorsAndPreservesUnrelatedSites() {
        Set<String> values = BrowserActivity.cookieExpirations(
                "https://a.b.example.com/account/deep/page?q=1", "session=token==; other=value");
        assertTrue(values.contains("session=;Max-Age=0;Path=/account;Domain=example.com;Secure"));
        assertTrue(values.contains("session=;Max-Age=0;Path=/account/deep/;Secure"));
        assertTrue(values.contains("other=;Max-Age=0;Path=/;Secure"));
        assertTrue(values.stream().noneMatch(v -> v.contains("token") || v.contains("?q=")));
        assertTrue(values.stream().noneMatch(v -> v.contains("Domain=com;") || v.contains("unrelated")));
    }

    @Test public void securePrefixCookiesCanBeExpiredWithoutDomain() {
        assertTrue(BrowserActivity.cookieExpirations("https://example.com/", "__Host-login=x")
                .contains("__Host-login=;Max-Age=0;Path=/;Secure"));
    }

    @Test public void ipAndNonWebUrlsDoNotInventParentDomains() {
        Set<String> values = BrowserActivity.cookieExpirations("http://127.0.0.1/a", "a=b");
        assertTrue(values.stream().noneMatch(v -> v.contains("Domain=0.0.1") || v.contains(";Secure")));
        assertTrue(BrowserActivity.cookieExpirations("file:///page.mht", "a=b").isEmpty());
        assertTrue(BrowserActivity.cookieExpirations("https://example.com/", null).isEmpty());
    }

    @Test public void translationTargetsSystemLocaleWithoutNavigatingAway() {
        String french = BrowserActivity.translationScript(Locale.FRENCH);
        assertTrue(french.contains("var lang='fr',edge='fr'"));
        assertFalse(french.contains("location.href="));
        assertFalse(french.contains("translate?sl="));
        assertTrue(BrowserActivity.translationScript(Locale.SIMPLIFIED_CHINESE)
                .contains("var lang='zh-CN',edge='zh-CHS'"));
        assertTrue(BrowserActivity.translationScript(Locale.TRADITIONAL_CHINESE)
                .contains("var lang='zh-TW',edge='zh-CHT'"));
    }
}
