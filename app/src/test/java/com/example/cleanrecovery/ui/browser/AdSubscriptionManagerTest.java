package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 订阅更新链加固的纯函数部分：正文校验（大小/特征/行数）与镜像链。
 * 网络部分（ETag/304/条件请求）不在 JVM 单测范围。
 */
public class AdSubscriptionManagerTest {

    @Test
    public void catalogMatchesViaChineseSubscriptionScreen() {
        List<AdSubscriptionManager.CatalogItem> catalog = AdSubscriptionManager.catalog();
        assertEquals(5, catalog.size());
        assertEquals("EasyList", catalog.get(0).title);
        assertEquals("EasyList China", catalog.get(1).title);
        assertEquals("CJX's Annoyance List", catalog.get(2).title);
        assertEquals("EasyPrivacy", catalog.get(3).title);
        assertEquals("Adblock Warning Removal List", catalog.get(4).title);
        assertEquals("https://easylist-downloads.adblockplus.org/easylistchina.txt",
                catalog.get(1).url);
    }

    private static String legitRuleList() {
        StringBuilder sb = new StringBuilder();
        sb.append("! Title: Test List\n");
        sb.append("! Last modified: 01 Sep 2026\n");
        for (int i = 0; i < 30; i++) {
            sb.append("||ad").append(i).append(".example.com^\n");
        }
        for (int i = 0; i < 10; i++) {
            sb.append("example").append(i).append(".com##.ad-slot\n");
        }
        return sb.toString();
    }

    @Test
    public void validateAcceptsLegitRuleList() {
        assertNull(AdSubscriptionManager.validateRuleBody(legitRuleList()));
    }

    @Test
    public void validateRejectsGarbage() {
        assertNotNull(AdSubscriptionManager.validateRuleBody(null));
        assertNotNull(AdSubscriptionManager.validateRuleBody(""));
        assertNotNull(AdSubscriptionManager.validateRuleBody("||a.com^\n")); // 过短

        StringBuilder html = new StringBuilder("<!doctype html><html><body>");
        for (int i = 0; i < 60; i++) html.append("||ad").append(i).append(".example.com^\n");
        html.append("</body></html>");
        assertNotNull("HTML 错误页不得当规则换入", AdSubscriptionManager.validateRuleBody(html.toString()));

        // 无 ABP 规则特征的纯文本
        StringBuilder plain = new StringBuilder("just some text\n");
        for (int i = 0; i < 40; i++) plain.append("random line ").append(i).append("\n");
        assertNotNull(AdSubscriptionManager.validateRuleBody(plain.toString()));

        // 规则行数不足
        assertNotNull(AdSubscriptionManager.validateRuleBody(
                "||only-one.example.com^\n" + repeat('x', 400)));
    }

    @Test
    public void validateAcceptsHostsFormatLists() {
        // hosts 格式订阅（StevenBlack 类）：无 ABP 特征但全是 0.0.0.0 域名行
        StringBuilder hosts = new StringBuilder("# Title: hosts\n");
        for (int i = 0; i < 30; i++) {
            hosts.append("0.0.0.0 ads").append(i).append(".example.com\n");
        }
        assertNull(AdSubscriptionManager.validateRuleBody(hosts.toString()));

        // hosts 行数不足仍拒绝
        StringBuilder few = new StringBuilder("# Title: hosts\n");
        for (int i = 0; i < 5; i++) {
            few.append("0.0.0.0 ads").append(i).append(".example.com\n");
        }
        few.append(repeat('x', 400));
        assertNotNull(AdSubscriptionManager.validateRuleBody(few.toString()));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(c);
        return sb.toString();
    }

    @Test
    public void mirrorChainCoversGithubRaw() {
        List<String> mirrors = AdSubscriptionManager.mirrorsOf(
                "https://raw.githubusercontent.com/easylist/easylistchina/master/easylistchina.txt");
        assertEquals(2, mirrors.size());
        assertEquals("https://fastly.jsdelivr.net/gh/easylist/easylistchina@master/easylistchina.txt",
                mirrors.get(0));
        assertEquals("https://cdn.statically.io/gh/easylist/easylistchina/master/easylistchina.txt",
                mirrors.get(1));
    }

    @Test
    public void mirrorChainForGithubBlobAndUnknown() {
        List<String> blob = AdSubscriptionManager.mirrorsOf(
                "https://github.com/foo/bar/blob/main/list.txt");
        assertEquals(1, blob.size());
        assertEquals("https://fastly.jsdelivr.net/gh/foo/bar@main/list.txt", blob.get(0));

        assertTrue(AdSubscriptionManager.mirrorsOf("https://example.org/list.txt").isEmpty());
        assertTrue(AdSubscriptionManager.mirrorsOf(null).isEmpty());
    }

    @Test
    public void subscriptionPersistsConditionalCredentials() throws Exception {
        AdSubscriptionManager.Subscription s = new AdSubscriptionManager.Subscription();
        s.url = "https://example.org/list.txt";
        s.etag = "\"abc123\"";
        s.lastModified = "Tue, 01 Sep 2026 00:00:00 GMT";
        org.json.JSONObject o = s.toJson();
        AdSubscriptionManager.Subscription back = AdSubscriptionManager.Subscription.fromJson(o);
        assertEquals("\"abc123\"", back.etag);
        assertEquals("Tue, 01 Sep 2026 00:00:00 GMT", back.lastModified);
    }
}
