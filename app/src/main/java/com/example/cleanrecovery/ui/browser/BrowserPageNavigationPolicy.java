package com.example.cleanrecovery.ui.browser;

/** Installed Via g8.i.N/K: retain a live page for ordinary HTTP(S) navigation. */
public final class BrowserPageNavigationPolicy {
    private static final String[] LOGIN_WORDS = {
            "login", "signup", "signin", "register", "sign-up", "create-account", "createaccount", "auth"
    };

    private BrowserPageNavigationPolicy() { }

    /** Via g8.i.E re-evaluates the current source URL at each redirect or script navigation. */
    public static String settingsUrl(String source, String target,
                                     boolean nonGestureOrRedirect, BrowserPrefs prefs) {
        String sourceHost = BrowserPrefs.siteKey(source);
        String targetHost = BrowserPrefs.siteKey(target);
        if (nonGestureOrRedirect && prefs.siteSettingsEnabled(sourceHost)
                && !prefs.siteSettingsEnabled(targetHost)
                && (prefs.siteUaSelectedId(sourceHost) != BrowserPrefs.UA_ID_ANDROID_PHONE
                || prefs.siteDesktopMode(sourceHost) == 1)) return source;
        return target;
    }

    public static boolean shouldRetainPage(String source, String target,
                                           boolean nonGestureOrRedirect, BrowserPrefs prefs) {
        if (!isHttpUrl(source) || !isHttpUrl(target) || nonGestureOrRedirect) return false;
        int sourceFragment = source.lastIndexOf('#');
        int targetFragment = target.lastIndexOf('#');
        if (sourceFragment >= 0 || targetFragment >= 0) {
            if (sourceFragment < 0) sourceFragment = source.length();
            if (targetFragment < 0) targetFragment = target.length();
            if (sourceFragment == targetFragment && source.regionMatches(0, target, 0, sourceFragment)) {
                return false;
            }
        }
        return (retentionEnabled(target, prefs) || retentionEnabled(source, prefs))
                && !isLoginUrl(source);
    }

    private static boolean retentionEnabled(String url, BrowserPrefs prefs) {
        // Site settings use the same authority key as the configuration screen.
        String host = BrowserPrefs.siteKey(url);
        if (prefs.siteSettingsEnabled(host)) {
            int mode = prefs.siteBackNoReloadMode(host);
            if (mode >= 0) return mode == 1;
        }
        return prefs.backNoReload() && permitsDefaultRetention(authority(url));
    }

    private static boolean permitsDefaultRetention(String host) {
        // Keep Via b9.b0.J's exact equals/startsWith/contains distinctions.
        return !(host.startsWith("192.168.") || "x.com".equals(host) || "metaso.cn".equals(host)
                || host.contains("forum.softpedia.com") || host.contains("3g.163.com")
                || host.contains("bbs.binmt.cc") || host.contains("www.giant.com.cn")
                || host.contains("www.10099.com.cn") || host.contains(".10086.cn")
                || host.contains(".10010.com") || host.contains("myaccount.google.com")
                || host.contains("accounts.google.com"));
    }

    private static String authority(String url) {
        int start = url.indexOf("://") + 3;
        int end = url.indexOf('/', start);
        return url.substring(start, end < 0 ? url.length() : end);
    }

    private static boolean isLoginUrl(String url) {
        int index = -1;
        int length = 0;
        // Via checks only the first occurrence of the first matching word in this order.
        for (String word : LOGIN_WORDS) {
            length = word.length();
            index = indexOfIgnoreCase(url, word);
            if (index > 0) break;
        }
        if (index <= 0) return false;
        char before = url.charAt(index - 1);
        if (isAsciiLetter(before) || before == '=') return false;
        int end = index + length;
        if (end >= url.length()) return true;
        char after = url.charAt(end);
        if (before == '.' && after == '.') return false;
        return !isAsciiLetter(after);
    }

    private static int indexOfIgnoreCase(String value, String word) {
        for (int start = 0; start + word.length() <= value.length(); start++) {
            int offset = 0;
            while (offset < word.length()
                    && Character.toLowerCase(value.charAt(start + offset))
                    == Character.toLowerCase(word.charAt(offset))) offset++;
            if (offset == word.length()) return start;
        }
        return -1;
    }

    private static boolean isAsciiLetter(char value) {
        return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z';
    }

    private static boolean isHttpUrl(String url) {
        return url != null && (url.regionMatches(true, 0, "http://", 0, 7)
                || url.regionMatches(true, 0, "https://", 0, 8));
    }
}
