package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ViaSnifferStateMachineTest {
    @Test
    public void reusedWebViewMustCommitTheNewPageBeforeExposingResources() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 1, 10, "https://example.com/old");
        sniffer.onPageStarted(1, 10, "https://example.com/new");
        sniffer.onRequest(1, 10, "https://example.com/new.mp4", false, null);
        assertTrue("A previous page commit must not authorize the new page", sniffer.candidates(1).isEmpty());
        assertFalse(sniffer.shouldShowButton(1));
        assertTrue(sniffer.onPageCommitVisible(1, 10));
    }

    @Test
    public void cosmeticCacheBusterMustNotConsumeTheResourceWindow() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 1, 10, "https://example.com/page");
        assertFalse(sniffer.onRequest(1, 10,
                "https://example.com/via_inject_blocker.css?v=123#style", true, null).recorded());
    }

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
    public void newWebViewStartsWithoutExposingThePreviousPagesCandidates() {
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
    public void returningToRetainedPageRestoresItsOwnCommittedResources() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 1, 10, "https://one.example/watch");
        sniffer.onRequest(1, 10, "https://cdn.example/one.mp4", false, null);
        startAndCommit(sniffer, 1, 11, "https://two.example/watch");
        sniffer.onRequest(1, 11, "https://cdn.example/two.mp3", false, null);

        sniffer.activatePage(1, 10);
        assertEquals("Back navigation must recover A's resources without reloading it",
                Collections.singletonList("https://cdn.example/one.mp4"), sniffer.mediaUrls(1));
        assertTrue(sniffer.shouldShowButton(1));
        assertFalse("Requests from retained B cannot leak into active A",
                sniffer.onRequest(1, 11, "https://cdn.example/late-b.mp4", false, null).recorded());

        sniffer.activatePage(1, 11);
        assertEquals("Forward navigation must recover B's independent resources",
                Collections.singletonList("https://cdn.example/two.mp3"), sniffer.mediaUrls(1));
        assertTrue(sniffer.shouldShowButton(1));
    }

    @Test
    public void oldCommitCannotExposeOrHideTheActivePagesResources() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        sniffer.onPageStarted(1, 10, "https://one.example/watch");
        sniffer.onRequest(1, 10, "https://cdn.example/one.mp4", false, null);
        sniffer.onPageStarted(1, 11, "https://two.example/watch");
        sniffer.onRequest(1, 11, "https://cdn.example/two.mp4", false, null);

        assertFalse("A's commit must not authorize uncommitted B", sniffer.onPageCommitVisible(1, 10));
        assertTrue(sniffer.candidates(1).isEmpty());
        assertTrue(sniffer.onPageCommitVisible(1, 11));
        assertTrue("A's late commit must not replace B's active identity", sniffer.onPageCommitVisible(1, 10));
        assertEquals(Collections.singletonList("https://cdn.example/two.mp4"), sniffer.mediaUrls(1));

        sniffer.activatePage(1, 10);
        assertEquals("A's own commit remains attached to A", Collections.singletonList("https://cdn.example/one.mp4"), sniffer.mediaUrls(1));
    }

    @Test
    public void reusingOnePageResetsOnlyThatPagesDocument() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 1, 10, "https://one.example/old");
        sniffer.onRequest(1, 10, "https://cdn.example/old.mp4", false, null);
        startAndCommit(sniffer, 1, 11, "https://two.example/retained");
        sniffer.onRequest(1, 11, "https://cdn.example/retained.mp4", false, null);

        sniffer.onPageStarted(1, 10, "https://one.example/new");
        sniffer.onRequest(1, 10, "https://cdn.example/new.mp4", false, null);
        assertTrue("A new document must wait for its own commit", sniffer.candidates(1).isEmpty());
        sniffer.activatePage(1, 11);
        assertEquals("Reloading A must not erase retained B", Collections.singletonList("https://cdn.example/retained.mp4"), sniffer.mediaUrls(1));
        sniffer.activatePage(1, 10);
        assertTrue(sniffer.candidates(1).isEmpty());
        sniffer.onPageCommitVisible(1, 10);
        assertEquals("The reused page must contain only its new document", Collections.singletonList("https://cdn.example/new.mp4"), sniffer.mediaUrls(1));
    }

    @Test
    public void discardedPageCannotBeResurrectedByLateCallbacks() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 1, 10, "https://one.example/watch");
        sniffer.onRequest(1, 10, "https://cdn.example/discarded.mp4", false, null);
        startAndCommit(sniffer, 1, 11, "https://two.example/watch");
        sniffer.onRequest(1, 11, "https://cdn.example/kept.mp4", false, null);

        sniffer.removePage(1, 10);
        assertFalse(sniffer.onRequest(1, 10, "https://cdn.example/late.mp4", false, null).recorded());
        assertTrue("A discarded page's commit must not disturb active B", sniffer.onPageCommitVisible(1, 10));
        assertEquals(Collections.singletonList("https://cdn.example/kept.mp4"), sniffer.mediaUrls(1));
        sniffer.activatePage(1, 10);
        assertTrue("Removed captures must not reappear through activation", sniffer.candidates(1).isEmpty());
        assertFalse(sniffer.shouldShowButton(1));

        sniffer.activatePage(1, 11);
        assertEquals(Collections.singletonList("https://cdn.example/kept.mp4"), sniffer.mediaUrls(1));
        sniffer.removePage(1, 11);
        assertTrue(sniffer.candidates(1).isEmpty());
        assertFalse(sniffer.onPageCommitVisible(1, 11));
        assertFalse(sniffer.onRequest(1, 11, "https://cdn.example/late-active.mp4", false, null).recorded());
        sniffer.activatePage(1, 11);
        assertTrue(sniffer.candidates(1).isEmpty());
    }

    @Test
    public void clearingActiveResourcesPreservesOtherPagesButClosingTabReleasesAll() {
        ViaSnifferStateMachine sniffer = new ViaSnifferStateMachine();
        startAndCommit(sniffer, 1, 10, "https://one.example/watch");
        sniffer.onRequest(1, 10, "https://cdn.example/one.mp4", false, null);
        startAndCommit(sniffer, 1, 11, "https://two.example/watch");
        sniffer.onRequest(1, 11, "https://cdn.example/two.mp4", false, null);

        sniffer.clear(1);
        assertTrue(sniffer.candidates(1).isEmpty());
        sniffer.activatePage(1, 10);
        assertEquals("The resource-page clear action belongs to its active source page",
                Collections.singletonList("https://cdn.example/one.mp4"), sniffer.mediaUrls(1));

        sniffer.removeTab(1);
        assertFalse(sniffer.onPageCommitVisible(1, 10));
        assertFalse(sniffer.onRequest(1, 10, "https://cdn.example/late.mp4", false, null).recorded());
        sniffer.activatePage(1, 10);
        assertTrue(sniffer.candidates(1).isEmpty());
        sniffer.activatePage(1, 11);
        assertTrue(sniffer.candidates(1).isEmpty());
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
