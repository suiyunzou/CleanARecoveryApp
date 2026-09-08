package com.example.cleanrecovery.proxy;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;

public class SubscriptionImportTest {
    @Test
    public void parsesMultipleLinesAndRemovesDuplicates() {
        List<String> urls = SubscriptionImport.parseUrls(
                "https://one.example/sub\n"
                        + "\nhttp://two.example/list\r\n"
                        + "https://one.example/sub\n"
                        + "not-a-url");
        assertEquals(2, urls.size());
        assertEquals("https://one.example/sub", urls.get(0));
        assertEquals("http://two.example/list", urls.get(1));
    }
}
