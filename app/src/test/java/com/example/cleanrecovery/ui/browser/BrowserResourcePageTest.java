package com.example.cleanrecovery.ui.browser;

import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class BrowserResourcePageTest {
    private final ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();

    private void start() {
        sniffer.onPageStarted(1, 1, "https://page.test/");
        sniffer.onPageCommitVisible(1, 1);
    }

    private String render() {
        return BrowserResourcePage.render(sniffer.candidates(1), "Resources", "Loaded resources", "No resources", false, false);
    }

    @Test public void latest64RequestsAreWindowedBeforeMediaFiltering() {
        start();
        sniffer.onRequest(1, 1, "https://media.test/expired.mp4", false, null);
        for (int i = 0; i < 63; i++) sniffer.onRequest(1, 1, "https://page.test/" + i + ".js", false, null);
        assertTrue(render().contains("expired.mp4"));
        sniffer.onRequest(1, 1, "https://media.test/current.mp4", false, null);
        assertFalse("Old media must not survive by filtering before windowing", render().contains("expired.mp4"));
        assertTrue(render().contains("current.mp4"));
        assertFalse(render().contains(".js"));
    }

    @Test public void rowsKeepDuplicatesNewestFirstAndActualBlockedState() {
        start();
        sniffer.onRequest(1, 1, "https://cdn.test/first.mp4", false, null);
        sniffer.onRequest(1, 1, "https://cdn.test/blocked.mp3", true, null);
        sniffer.onRequest(1, 1, "https://cdn.test/first.mp4", false, null);
        String html = render();
        assertTrue(html.indexOf("first.mp4") < html.indexOf("blocked.mp3"));
        assertTrue(html.lastIndexOf("first.mp4") > html.indexOf("blocked.mp3"));
        assertTrue(html.contains("class=\"box block\""));
        assertTrue(html.contains(">block</span>"));
        assertTrue(html.contains("https://<b>cdn.test</b>/first.mp4"));
        assertFalse("Row navigation is same-tab", html.contains("target="));
    }

    @Test public void requestTextCannotInjectMarkupOrChangeTheHref() {
        start();
        sniffer.onRequest(1, 1, "https://cdn.test/a.mp4?q=\"<script>&x=1", false, null);
        String html = render();
        assertTrue(html.contains("?q=&quot;&lt;script&gt;&amp;x=1"));
        assertFalse(html.contains("<script>"));
        assertTrue("Internal page must not execute captured site content", html.contains("default-src 'none'"));
    }

    @Test public void initialRangeWithoutExtensionIsVisibleWithoutFakeTypeBadge() {
        start();
        sniffer.onRequest(1, 1, "https://cdn.test/stream", false, Collections.singletonMap("Range", "bytes=0-"));
        assertTrue(render().contains("/stream"));
        assertFalse(render().contains("class=\"res tag\""));
    }

    @Test public void nonMediaRequestsProduceHelpfulEmptyStateNotNetworkLogFilters() {
        start();
        sniffer.onRequest(1, 1, "https://cdn.test/file.js", false, null);
        assertTrue(render().contains("No resources"));
        assertFalse(render().contains("<select"));
        assertFalse(render().contains("file.js"));
    }

    @Test public void closedTabCannotExposeOldPrivateResources() {
        start();
        sniffer.onRequest(1, 1, "https://private.test/private.mp4", false, null);
        sniffer.removeTab(1);
        assertTrue(sniffer.candidates(1).isEmpty());
        assertFalse(sniffer.shouldShowButton(1));
    }

    @Test public void nightAndRtlUseViaUrlContrastAndRightAlignedRows() {
        String html = BrowserResourcePage.render(Collections.emptyList(), "Resources", "Note", "Empty", true, true);
        assertTrue(html.contains("direction:rtl"));
        assertTrue(html.contains("text-align:right"));
        assertTrue(html.contains(".url{color:#fafafa"));
        assertTrue(html.contains("color:#d5d5d5"));
        assertTrue(render().contains(".url{color:#1b1b1b"));
    }
}
