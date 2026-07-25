package com.example.cleanrecovery.ui.browser;

import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Resolves browser address-bar input using VIA's permissive URL rules.
 *
 * <p>This is intentionally not an RFC 3986 validator. It preserves VIA's
 * address-bar decisions, including its known-TLD hash membership check.</p>
 */
public final class ViaAddressResolver {
    private static final String[] HTTPS_HINTS = {
            "baidu.", "google.", "youtube.", "facebook.", "sina.", "163.",
            "bilibili.", "douban.", "viayoo.", "twitter.", "bing.",
            "duckduckgo.", "sogou."
    };

    public enum Kind {
        EMPTY,
        DIRECT,
        SEARCH
    }

    public static final class Resolution {
        private final Kind kind;
        private final String input;
        private final String output;

        Resolution(Kind kind, String input, String output) {
            this.kind = kind;
            this.input = input;
            this.output = output;
        }

        public Kind getKind() {
            return kind;
        }

        public String getInput() {
            return input;
        }

        public String getOutput() {
            return output;
        }
    }

    private ViaAddressResolver() {
    }

    /**
     * Resolves user-entered text to either a direct URL or a search URL.
     *
     * @param rawInput user-entered address-bar text
     * @param searchTemplate engine URL, optionally containing %@, %s, or %S
     * @param blockJavascript whether javascript: input must be converted to search
     */
    public static Resolution resolve(
            String rawInput,
            String searchTemplate,
            boolean blockJavascript) {
        if (rawInput == null || rawInput.trim().isEmpty()) {
            return new Resolution(Kind.EMPTY, rawInput, null);
        }

        String input = rawInput.trim();
        ViaUrlParser parser = new ViaUrlParser(input);
        boolean javascript = startsWithIgnoreCase(input, "javascript:");
        if (parser.isValid() && !(blockJavascript && javascript)) {
            return new Resolution(Kind.DIRECT, input, normalize(input, searchTemplate));
        }
        return new Resolution(Kind.SEARCH, input, buildSearchUrl(searchTemplate, input));
    }

    public static String normalize(String input, String searchTemplate) {
        ViaUrlParser parser = new ViaUrlParser(input);
        if (input == null || input.isEmpty() || !parser.isValid()) {
            return buildSearchUrl(searchTemplate, input);
        }

        String trimmed = input.trim();
        if (!parser.hasScheme()) {
            String prefix = shouldPreferHttps(parser.host()) ? "https://" : "http://";
            return prefix + trimmed;
        }

        String scheme = parser.scheme();
        if (("http".equals(scheme) || "https".equals(scheme))
                && parser.host() != null
                && containsNonAscii(parser.host())) {
            return replaceAuthorityHostWithAscii(trimmed);
        }
        return trimmed;
    }

    public static String buildSearchUrl(String template, String query) {
        if (template == null || template.isEmpty()
                || query == null || query.trim().isEmpty()) {
            return query;
        }

        String encoded = percentEncode(query.trim());
        int marker = template.indexOf("%@");
        if (marker < 0) {
            marker = template.indexOf("%s");
        }
        if (marker < 0) {
            marker = template.indexOf("%S");
        }
        if (marker < 0) {
            return template + encoded;
        }
        return template.substring(0, marker) + encoded + template.substring(marker + 2);
    }

    private static boolean shouldPreferHttps(String host) {
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        for (String hint : HTTPS_HINTS) {
            int at = lower.indexOf(hint);
            if (at == 0 || (at > 0 && lower.charAt(at - 1) == '.')) {
                return true;
            }
        }
        return false;
    }

    private static String replaceAuthorityHostWithAscii(String value) {
        int authorityStart = value.indexOf("://") + 3;
        if (authorityStart < 3) {
            return value;
        }
        int authorityEnd = value.length();
        char[] separators = {'/', '?', '#'};
        for (char separator : separators) {
            int at = value.indexOf(separator, authorityStart);
            if (at >= 0) {
                authorityEnd = Math.min(authorityEnd, at);
            }
        }

        String authority = value.substring(authorityStart, authorityEnd);
        int userInfoEnd = authority.lastIndexOf('@');
        int hostStart = userInfoEnd + 1;
        int hostEnd = authority.length();
        if (hostStart < authority.length() && authority.charAt(hostStart) == '[') {
            int bracket = authority.indexOf(']', hostStart);
            if (bracket >= 0) {
                hostEnd = bracket + 1;
            }
        } else {
            int portAt = authority.lastIndexOf(':');
            if (portAt > hostStart) {
                hostEnd = portAt;
            }
        }

        String host = authority.substring(hostStart, hostEnd);
        String ascii;
        try {
            ascii = IDN.toASCII(host);
        } catch (IllegalArgumentException ignored) {
            return value;
        }
        String rewritten = authority.substring(0, hostStart)
                + ascii
                + authority.substring(hostEnd);
        return value.substring(0, authorityStart)
                + rewritten
                + value.substring(authorityEnd);
    }

    private static String percentEncode(String value) {
        StringBuilder output = new StringBuilder();
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        for (byte next : bytes) {
            int unsigned = next & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '_'
                    || unsigned == '.' || unsigned == '~') {
                output.append((char) unsigned);
            } else {
                output.append('%');
                output.append(Character.toUpperCase(
                        Character.forDigit(unsigned >>> 4, 16)));
                output.append(Character.toUpperCase(
                        Character.forDigit(unsigned & 0xf, 16)));
            }
        }
        return output.toString();
    }

    private static boolean containsNonAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }
}

final class ViaUrlParser {
    private static final String STRICT_HOST_FORBIDDEN =
            "!#$%&\"'()*+,/;<=>?@\\^_`{|}~";
    private static final String NETWORK_HOST_FORBIDDEN =
            "!#$&\"'()*+,/;<=>?@\\^`{|}~";

    private final String url;
    private String scheme;
    private String userInfo;
    private String host;
    private String port;
    private String path;
    private String query;
    private String fragment;
    private boolean valid;

    ViaUrlParser(String input) {
        url = input == null ? null : input.trim();
        parse();
    }

    String url() {
        return url;
    }

    String scheme() {
        return scheme;
    }

    String userInfo() {
        return userInfo;
    }

    String host() {
        return host == null ? null : normalizeDots(host).toLowerCase(Locale.ROOT);
    }

    String port() {
        return port;
    }

    String path() {
        return path;
    }

    String query() {
        return query;
    }

    String fragment() {
        return fragment;
    }

    boolean hasScheme() {
        return scheme != null;
    }

    boolean isValid() {
        return valid;
    }

    private void parse() {
        if (url == null || url.isEmpty()) {
            return;
        }

        int length = url.length();
        int colon = url.indexOf(':');
        if (isSchemeCandidate(0, colon)) {
            scheme = url.substring(0, colon).toLowerCase(Locale.ROOT);
        } else {
            colon = -1;
        }

        if (scheme != null && !(colon + 2 < length
                && url.charAt(colon + 1) == '/'
                && url.charAt(colon + 2) == '/')) {
            path = url.substring(colon + 1);
            valid = validate();
            return;
        }

        int authorityStart = scheme == null ? 0 : colon + 3;
        int end = length;

        int fragmentAt = url.indexOf('#', authorityStart);
        if (fragmentAt >= 0) {
            fragment = url.substring(fragmentAt + 1);
            end = fragmentAt;
        }

        int queryAt = url.indexOf('?', authorityStart);
        if (queryAt >= 0 && queryAt <= end) {
            query = url.substring(queryAt + 1, end);
            end = queryAt;
        }

        int pathAt = url.indexOf('/', authorityStart);
        int authorityEnd = end;
        if (pathAt >= 0 && pathAt <= end) {
            path = url.substring(pathAt, end);
            authorityEnd = pathAt;
        } else {
            path = "";
        }

        int hostEnd = authorityEnd;
        int lastColon = url.lastIndexOf(':', authorityEnd - 1);
        if (lastColon > authorityStart && lastColon < authorityEnd) {
            String possiblePort = url.substring(lastColon + 1, authorityEnd);
            if (possiblePort.isEmpty() || isAsciiDigits(possiblePort)) {
                port = possiblePort;
                hostEnd = lastColon;
            }
        }

        int at = url.indexOf('@', authorityStart);
        int hostStart = authorityStart;
        if (at > authorityStart && at <= hostEnd) {
            userInfo = url.substring(authorityStart, at);
            hostStart = at + 1;
        }
        host = url.substring(hostStart, hostEnd);
        valid = validate();
    }

    private boolean isSchemeCandidate(int start, int end) {
        if (end <= start || start < 0 || !isAsciiLetter(url.charAt(start))) {
            return false;
        }
        for (int i = start + 1; i < end; i++) {
            char value = url.charAt(i);
            if (!isAsciiLetter(value)
                    && !Character.isDigit(value)
                    && value != '+'
                    && value != '.'
                    && value != '-') {
                return false;
            }
        }

        String candidate = url.substring(start, end);
        if ("localhost".equalsIgnoreCase(candidate)) {
            return false;
        }
        if (candidate.indexOf('.') >= 0 && new ViaUrlParser(candidate).isValid()) {
            return false;
        }
        return true;
    }

    private boolean validate() {
        int whitespace = firstWhitespace(url);
        if (scheme == null) {
            return validateBareHost(host);
        }

        if ("http".equals(scheme) || "https".equals(scheme) || "ftp".equals(scheme)) {
            if (host == null) {
                return false;
            }
            int hostStart = url.indexOf(host);
            if (whitespace >= 0 && whitespace < hostStart) {
                return false;
            }
            return containsNone(host, NETWORK_HOST_FORBIDDEN);
        }

        boolean opaqueForm = host == null;
        if (opaqueForm) {
            if ("javascript".equals(scheme) || "data".equals(scheme)) {
                return true;
            }
            if ("about".equals(scheme)) {
                return path != null && (path.isEmpty() || isLettersAndHyphen(path));
            }
            return "view-source".equals(scheme)
                    || "magnet".equals(scheme)
                    || "sms".equals(scheme)
                    || "tel".equals(scheme)
                    || "mailto".equals(scheme)
                    || "geo".equals(scheme)
                    || "tg".equals(scheme);
        }

        return whitespace < 0;
    }

    private boolean validateBareHost(String candidate) {
        if (candidate == null || candidate.isEmpty()) {
            return false;
        }
        String normalized = normalizeDots(candidate);
        if ("localhost".equalsIgnoreCase(normalized)) {
            return true;
        }
        if (normalized.startsWith("[") && normalized.endsWith("]")
                && normalized.length() > 3) {
            String ipv6 = normalized.substring(1, normalized.length() - 1);
            return ipv6.indexOf(':') >= 0 && isHexAndColon(ipv6);
        }

        int lastDot = normalized.lastIndexOf('.');
        if (lastDot == normalized.length() - 1
                || normalized.startsWith(".")
                || normalized.contains("..")
                || !containsNone(normalized, STRICT_HOST_FORBIDDEN)) {
            return false;
        }

        if ("/".equals(path)) {
            return true;
        }
        if (lastDot < 0) {
            return false;
        }

        String suffix = normalized.substring(lastDot + 1);
        if (isAsciiDigits(suffix)) {
            return isIpv4(normalized);
        }
        return ViaTldHashes.isKnownTld(suffix);
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || !isAsciiDigits(part)) {
                return false;
            }
            int number;
            try {
                number = Integer.parseInt(part);
            } catch (NumberFormatException ignored) {
                return false;
            }
            if (number > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsNone(String value, String forbidden) {
        for (int i = 0; i < value.length(); i++) {
            if (forbidden.indexOf(value.charAt(i)) >= 0) {
                return false;
            }
        }
        return true;
    }

    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if (next == ' ' || next == '\n' || next == '\t'
                    || next == '\u00a0' || Character.isWhitespace(next)) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeDots(String value) {
        return value.replace('\u3002', '.')
                .replace('\uff0e', '.')
                .replace('\uff61', '.');
    }

    private static boolean isLettersAndHyphen(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if (!isAsciiLetter(next) && next != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexAndColon(String value) {
        for (int i = 0; i < value.length(); i++) {
            char next = value.charAt(i);
            if (next != ':' && Character.digit(next, 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char value) {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
    }
}
