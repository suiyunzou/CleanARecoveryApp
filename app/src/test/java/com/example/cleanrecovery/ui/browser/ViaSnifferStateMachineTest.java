package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViaSnifferStateMachineTest {
    @Test
    public void keepsIndependentStateAcrossTabs() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 1, 101, "https://one.example/page");
        startAndCommit(sniffer, 2, 202, "https://two.example/page");

        sniffer.onRequest(1, 101, "https://cdn.example/one.mp4", false, null);
        sniffer.onRequest(2, 202, "https://cdn.example/two.js", false, null);

        assertEquals(
                Collections.singletonList("https://cdn.example/one.mp4"),
                sniffer.mediaUrls(1));
        assertTrue(sniffer.shouldShowButton(1));
        assertTrue(sniffer.mediaUrls(2).isEmpty());
        assertFalse(sniffer.shouldShowButton(2));
    }

    @Test
    public void ignoresRequestsFromOldWebView() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 7, 70, "https://example.com/old");
        sniffer.onPageStarted(7, 71, "https://example.com/new");

        ViaSnifferStateMachine.CaptureResult result = sniffer.onRequest(
                7, 70, "https://cdn.example/stale.mp4", false, null);

        assertFalse(result.recorded());
        assertFalse(result.showSnifferButton());
        assertTrue(sniffer.candidates(7).isEmpty());
    }

    @Test
    public void pageStartClearsPreviousPageCandidates() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 3, 30, "https://example.com/first");
        sniffer.onRequest(3, 30, "https://cdn.example/first.mp4", false, null);
        assertTrue(sniffer.shouldShowButton(3));

        sniffer.onPageStarted(3, 31, "https://example.com/second");

        assertTrue(sniffer.candidates(3).isEmpty());
        assertTrue(sniffer.mediaUrls(3).isEmpty());
        assertFalse(sniffer.shouldShowButton(3));
    }

    @Test
    public void unsupportedPageSuppressesMediaAndButton() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 4, 40, "https://m.youku.com/video");

        ViaSnifferStateMachine.CaptureResult media = sniffer.onRequest(
                4, 40, "https://cdn.example/movie.mp4", false, null);
        ViaSnifferStateMachine.CaptureResult script = sniffer.onRequest(
                4, 40, "https://cdn.example/player.js", false, null);

        assertFalse(media.recorded());
        assertTrue(script.recorded());
        assertEquals(1, sniffer.candidates(4).size());
        assertTrue(sniffer.mediaUrls(4).isEmpty());
        assertFalse(sniffer.shouldShowButton(4));
    }

    @Test
    public void initialRangeMarksExtensionlessRequestAsMedia() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        sniffer.onPageStarted(5, 50, "https://example.com/watch");

        ViaSnifferStateMachine.CaptureResult beforeCommit = sniffer.onRequest(
                5,
                50,
                "https://media.example/stream",
                false,
                Collections.singletonMap("Range", "bytes=0-"));

        assertTrue(beforeCommit.recorded());
        assertFalse(beforeCommit.showSnifferButton());
        assertTrue(sniffer.onPageCommitVisible(5, 50));
        assertEquals(
                Collections.singletonList("https://media.example/stream"),
                sniffer.mediaUrls(5));
    }

    @Test
    public void blockedResponseIsRetainedAndExportedWhenMedia() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 6, 60, "https://example.com/watch");

        ViaSnifferStateMachine.CaptureResult result = sniffer.onRequest(
                6, 60, "https://cdn.example/blocked.mp3", true, null);

        assertTrue(result.recorded());
        assertTrue(result.showSnifferButton());
        List<ViaSnifferStateMachine.Candidate> candidates = sniffer.candidates(6);
        assertEquals(1, candidates.size());
        assertTrue(candidates.get(0).blockedResponse());
        assertTrue(candidates.get(0).mediaLike());
        assertEquals(
                Collections.singletonList("https://cdn.example/blocked.mp3"),
                sniffer.mediaUrls(6));
    }

    @Test
    public void excludesInjectedBlockerCss() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 8, 80, "https://example.com/page");

        ViaSnifferStateMachine.CaptureResult result = sniffer.onRequest(
                8,
                80,
                "https://example.com/assets/via_inject_blocker.css",
                true,
                null);

        assertFalse(result.recorded());
        assertTrue(sniffer.candidates(8).isEmpty());
        assertFalse(sniffer.shouldShowButton(8));
    }

    @Test
    public void exportContainsOnlyMediaCandidates() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 9, 90, "https://example.com/page");
        sniffer.onRequest(9, 90, "https://cdn.example/app.js", false, null);
        sniffer.onRequest(9, 90, "https://cdn.example/video.mp4?token=1", false, null);

        assertEquals(2, sniffer.candidates(9).size());
        assertEquals(
                Collections.singletonList("https://cdn.example/video.mp4?token=1"),
                sniffer.mediaUrls(9));
    }

    private static void startAndCommit(
            ViaSnifferStateMachine sniffer,
            int tabId,
            int webViewId,
            String pageUrl) {
        sniffer.onPageStarted(tabId, webViewId, pageUrl);
        sniffer.onPageCommitVisible(tabId, webViewId);
    }
}
