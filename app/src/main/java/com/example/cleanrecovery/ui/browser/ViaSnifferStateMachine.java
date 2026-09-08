package com.example.cleanrecovery.ui.browser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java state machine for VIA-compatible resource sniffing.
 *
 * <p>Instances retain independent state for each page in a browser tab. Only the active page's
 * candidates are exposed, after that WebView commits; restoring a page preserves its captures.
 */
public final class ViaSnifferStateMachine {
    private static final String INJECTED_BLOCKER_CSS = "via_inject_blocker.css";
    private static final String[] UNSUPPORTED_SITES = {
            "v.qq.com",
            "youku.com",
            "iqiyi.com",
            "mgtv.com",
            "bilibili.com",
            "ximalaya.com",
            "film.qq.com"
    };

    /** One request retained by the sniffer. */
    public static final class Candidate {
        private final String url;
        private final String extension;
        private final long capturedAtMillis;
        private final boolean blockedResponse;
        private final boolean mediaLike;

        private Candidate(
                String url,
                String extension,
                long capturedAtMillis,
                boolean blockedResponse,
                boolean mediaLike) {
            this.url = url;
            this.extension = extension;
            this.capturedAtMillis = capturedAtMillis;
            this.blockedResponse = blockedResponse;
            this.mediaLike = mediaLike;
        }

        public String url() {
            return url;
        }

        public String extension() {
            return extension;
        }

        public long capturedAtMillis() {
            return capturedAtMillis;
        }

        public boolean blockedResponse() {
            return blockedResponse;
        }

        public boolean mediaLike() {
            return mediaLike;
        }
    }

    /** Result returned after processing a request. */
    public static final class CaptureResult {
        private final boolean recorded;
        private final boolean showSnifferButton;

        private CaptureResult(boolean recorded, boolean showSnifferButton) {
            this.recorded = recorded;
            this.showSnifferButton = showSnifferButton;
        }

        public boolean recorded() {
            return recorded;
        }

        public boolean showSnifferButton() {
            return showSnifferButton;
        }
    }

    private static final class PageState {
        boolean committed;
        final List<Candidate> candidates = new ArrayList<>();
        boolean hasMediaLikeCandidate;
        final boolean unsupportedSite;

        PageState(boolean unsupported) {
            unsupportedSite = unsupported;
        }

        boolean hasCurrentRequests() {
            return committed && !candidates.isEmpty();
        }

        boolean shouldShowButton() {
            return hasCurrentRequests() && !unsupportedSite && hasMediaLikeCandidate;
        }
    }

    private static final class TabState {
        int activeWebViewId = -1;
        final Map<Integer, PageState> pages = new HashMap<>();

        PageState activePage() {
            return pages.get(activeWebViewId);
        }

        boolean shouldShowButton() {
            PageState page = activePage();
            return page != null && page.shouldShowButton();
        }
    }

    private final Map<Integer, TabState> tabs = new HashMap<>();

    /** A new document resets only its WebView's captures and makes that page active. */
    public synchronized void onPageStarted(int tabId, int webViewId, String pageUrl) {
        TabState state = state(tabId);
        state.pages.put(webViewId, new PageState(isUnsupportedSite(pageUrl)));
        state.activeWebViewId = webViewId;
    }

    /** Restores a retained page without clearing its captured requests or commit state. */
    public synchronized void activatePage(int tabId, int webViewId) {
        state(tabId).activeWebViewId = webViewId;
    }

    /** A discarded page must release its captured URLs; late requests cannot recreate it. */
    public synchronized void removePage(int tabId, int webViewId) {
        TabState state = tabs.get(tabId);
        if (state == null) return;
        state.pages.remove(webViewId);
        if (state.activeWebViewId == webViewId) state.activeWebViewId = -1;
        if (state.pages.isEmpty()) tabs.remove(tabId);
    }

    /**
     * Mirrors the current-WebView commit callback.
     *
     * @return whether the sniffer button should be visible after this commit
     */
    public synchronized boolean onPageCommitVisible(int tabId, int webViewId) {
        TabState state = tabs.get(tabId);
        if (state == null) return false;
        PageState page = state.pages.get(webViewId);
        if (page != null) page.committed = true;
        return state.shouldShowButton();
    }

    /**
     * Records a request after the content-blocking callback has produced its response.
     *
     * @param blockedResponse true when interception returned a non-null blocked response
     * @param requestHeaders request headers, or null; VIA recognizes an exact {@code Range} key
     */
    public synchronized CaptureResult onRequest(
            int tabId,
            int webViewId,
            String url,
            boolean blockedResponse,
            Map<String, String> requestHeaders) {
        TabState state = tabs.get(tabId);
        PageState page = state == null ? null : state.activePage();
        if (page == null || url == null
                || isUnsupportedSite(url)
                || url.split("[?#]", 2)[0].endsWith("/" + INJECTED_BLOCKER_CSS)
                || webViewId != state.activeWebViewId) {
            return result(false, state);
        }

        String range = requestHeaders == null ? null : requestHeaders.get("Range");
        boolean initialRange = range != null && range.startsWith("bytes=0-");
        String extension = extractExtension(url);
        boolean mediaLike = initialRange || ViaMediaHashMembership.contains(extension);

        // VIA does not retain media candidates while the active page is unsupported.
        if (mediaLike && page.unsupportedSite) {
            return result(false, state);
        }

        page.candidates.add(new Candidate(
                url,
                extension,
                System.currentTimeMillis(),
                blockedResponse,
                mediaLike));
        if (mediaLike) {
            page.hasMediaLikeCandidate = true;
        }
        return result(true, state);
    }

    /** Returns a defensive snapshot of all current requests for a tab. */
    public synchronized List<Candidate> candidates(int tabId) {
        TabState state = tabs.get(tabId);
        PageState page = state == null ? null : state.activePage();
        if (page == null || !page.hasCurrentRequests()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(page.candidates));
    }

    /** Returns the VIA sniffer UI export: current candidates classified as media only. */
    public synchronized List<String> mediaUrls(int tabId) {
        List<Candidate> candidates = candidates(tabId);
        List<String> urls = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.mediaLike()) {
                urls.add(candidate.url());
            }
        }
        return Collections.unmodifiableList(urls);
    }

    public synchronized boolean shouldShowButton(int tabId) {
        TabState state = tabs.get(tabId);
        return state != null && state.shouldShowButton();
    }

    /** A destroyed tab must not retain captured private URLs. */
    public synchronized void removeTab(int tabId) {
        tabs.remove(tabId);
    }

    /** Clears the active page's captures without changing its identity or commit state. */
    public synchronized void clear(int tabId) {
        TabState state = tabs.get(tabId);
        PageState page = state == null ? null : state.activePage();
        if (page == null) return;
        page.candidates.clear();
        page.hasMediaLikeCandidate = false;
    }

    public static String extractExtension(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }

        int end = url.length();
        int extensionStart = -1;
        for (int index = url.length() - 1; index >= 0; index--) {
            char next = url.charAt(index);
            if (next == '#' || next == '?') {
                end = index;
            } else if (next == '.') {
                if (extensionStart < 0 || extensionStart > end) {
                    extensionStart = index + 1;
                }
            } else if (next == '/') {
                if (extensionStart < 0 || extensionStart > end) {
                    return "";
                }
                if (index >= 2
                        && url.charAt(index - 1) == '/'
                        && url.charAt(index - 2) == ':') {
                    return "";
                }
                break;
            }
        }

        if (extensionStart < 0 || extensionStart >= end) {
            return "";
        }
        return url.substring(extensionStart, end).toLowerCase(Locale.ROOT);
    }

    public static boolean isUnsupportedSite(String urlOrHost) {
        String host = extractHostLikeVia(urlOrHost);
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (String unsupported : UNSUPPORTED_SITES) {
            int match = host.indexOf(unsupported);
            if (match < 0) {
                continue;
            }
            boolean leftBoundary = match == 0 || host.charAt(match - 1) == '.';
            int right = match + unsupported.length();
            boolean rightBoundary = right >= host.length() || host.charAt(right) == '.';
            if (leftBoundary && rightBoundary) {
                return true;
            }
        }
        return false;
    }

    private static String extractHostLikeVia(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.indexOf('/') < 0) {
            return value.toLowerCase(Locale.ROOT);
        }
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            return null;
        }
        String remainder = value.substring(scheme + 3);
        int slash = remainder.indexOf('/');
        String host = slash < 0 ? remainder : remainder.substring(0, slash);
        return host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
    }

    private CaptureResult result(boolean recorded, TabState state) {
        return new CaptureResult(recorded, state != null && state.shouldShowButton());
    }

    private TabState state(int tabId) {
        TabState state = tabs.get(tabId);
        if (state == null) {
            state = new TabState();
            tabs.put(tabId, state);
        }
        return state;
    }

    /**
     * Exact j6.i0 media-extension membership. Kept nested to avoid colliding with the address
     * resolver's independently generated hash helper.
     */
    private static final class ViaMediaHashMembership {
        private static final int MULTIPLIER = 1540483477;
        private static final int[] HASHES = {
                3847354, 9568862, 81264632, 81930561, 107123353, 126179678, 140994747,
                172819050, 190100404, 313906034, 324664358, 349563905, 394057630,
                401942050, 414455207, 415430052, 428139183, 479113676, 512218191,
                521295666, 560628411, 608526472, 622106444, 687482999, 718140131,
                733429268, 807282548, 831243175, 838714884, 843086363, 846958475,
                851561760, 855761738, 912646323, 917017690, 930611925, 937672157,
                957034884, 985168431, 994678331, 1000779705, 1014739318, 1103121146,
                1108586424, 1123614135, 1155674324, 1161994020, 1215239210, 1234200878,
                1319876930, 1321096783, 1387028997, 1413036331, 1458270773, 1558932844,
                1583569252, 1625637477, 1734170793, 1739555145, 1740189627, 1768878331,
                1778226052, 1792002250, 1809528708, 1810343100, 1876522621, 1944662444,
                1990761014, 1999275047, 2015764437, 2022745637, 2061805411, 2072937858,
                2075848807, 2093577775
        };

        static boolean contains(String value) {
            if (value == null || value.length() < 2 || value.length() > 5) {
                return false;
            }
            return Arrays.binarySearch(HASHES, viaHash(value.toLowerCase(Locale.ROOT))) >= 0;
        }

        private static int viaHash(String value) {
            int state = 0;
            for (byte next : value.getBytes(StandardCharsets.UTF_8)) {
                int product = (state ^ (next & 0xff)) * MULTIPLIER;
                int positive = product & Integer.MAX_VALUE;
                state = positive ^ (positive >>> 15);
            }
            return state;
        }
    }
}
