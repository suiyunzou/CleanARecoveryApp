package com.example.cleanrecovery.ui.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ViaAddressResolverTest {
    private static final String SEARCH = "https://www.google.com/search?q=";

    @Test
    public void resolvesBareDomainsAndHttpsHints() {
        assertResolution("example.com", ViaAddressResolver.Kind.DIRECT,
                "http://example.com");
        assertResolution("google.com", ViaAddressResolver.Kind.DIRECT,
                "https://google.com");
        assertResolution("www.bilibili.com", ViaAddressResolver.Kind.DIRECT,
                "https://www.bilibili.com");
    }

    @Test
    public void unknownTldBecomesSearch() {
        assertResolution("example.invalidtld", ViaAddressResolver.Kind.SEARCH,
                SEARCH + "example.invalidtld");
    }

    @Test
    public void resolvesIpv4Ipv6AndLocalhost() {
        assertResolution("1.2.3.255", ViaAddressResolver.Kind.DIRECT,
                "http://1.2.3.255");
        assertResolution("1.2.3.256", ViaAddressResolver.Kind.SEARCH,
                SEARCH + "1.2.3.256");
        assertResolution("[2001:db8::1]:8080/a", ViaAddressResolver.Kind.DIRECT,
                "http://[2001:db8::1]:8080/a");
        assertResolution("localhost:8080", ViaAddressResolver.Kind.DIRECT,
                "http://localhost:8080");
    }

    @Test
    public void normalizesExplicitUnicodeHttpHostToIdn() {
        assertResolution("中文.中国", ViaAddressResolver.Kind.DIRECT,
                "http://中文.中国");
        assertResolution("http://中文.中国/路径", ViaAddressResolver.Kind.DIRECT,
                "http://xn--fiq228c.xn--fiqs8s/路径");
        assertResolution("https://用户:密码@中文.中国:8443/路径",
                ViaAddressResolver.Kind.DIRECT,
                "https://用户:密码@xn--fiq228c.xn--fiqs8s:8443/路径");
    }

    @Test
    public void preservesMailtoAndControlsJavascript() {
        assertResolution("mailto:test@example.com", ViaAddressResolver.Kind.DIRECT,
                "mailto:test@example.com");
        assertResolution("javascript:alert(1)", ViaAddressResolver.Kind.DIRECT,
                "javascript:alert(1)");

        ViaAddressResolver.Resolution blocked = ViaAddressResolver.resolve(
                "javascript:alert(1)", SEARCH, true);
        assertEquals(ViaAddressResolver.Kind.SEARCH, blocked.getKind());
        assertEquals(SEARCH + "javascript%3Aalert%281%29", blocked.getOutput());
    }

    @Test
    public void emptyInputHasNoNavigationTarget() {
        ViaAddressResolver.Resolution nullInput =
                ViaAddressResolver.resolve(null, SEARCH, false);
        assertEquals(ViaAddressResolver.Kind.EMPTY, nullInput.getKind());
        assertNull(nullInput.getInput());
        assertNull(nullInput.getOutput());

        ViaAddressResolver.Resolution blank =
                ViaAddressResolver.resolve(" \t ", SEARCH, false);
        assertEquals(ViaAddressResolver.Kind.EMPTY, blank.getKind());
        assertEquals(" \t ", blank.getInput());
        assertNull(blank.getOutput());
    }

    @Test
    public void percentEncodesSearchAsUtf8WithoutFormEncoding() {
        assertResolution("a b+C/中文", ViaAddressResolver.Kind.SEARCH,
                SEARCH + "a%20b%2BC%2F%E4%B8%AD%E6%96%87");
    }

    @Test
    public void supportsAllTemplatePlaceholdersAndAppendFallback() {
        assertEquals("https://search.test/?q=a%20b&from=via",
                ViaAddressResolver.buildSearchUrl(
                        "https://search.test/?q=%@&from=via", "a b"));
        assertEquals("https://search.test/?q=a%20b",
                ViaAddressResolver.buildSearchUrl(
                        "https://search.test/?q=%s", "a b"));
        assertEquals("https://search.test/a%20b/result",
                ViaAddressResolver.buildSearchUrl(
                        "https://search.test/%S/result", "a b"));
        assertEquals("https://search.test/?q=a%20b",
                ViaAddressResolver.buildSearchUrl(
                        "https://search.test/?q=", "a b"));
    }

    @Test
    public void usesExactKnownTldHashMembership() {
        assertResolution("example.zip", ViaAddressResolver.Kind.DIRECT,
                "http://example.zip");
        assertResolution("example.museum", ViaAddressResolver.Kind.DIRECT,
                "http://example.museum");
        assertResolution("example.not-a-real-tld", ViaAddressResolver.Kind.SEARCH,
                SEARCH + "example.not-a-real-tld");
    }

    private static void assertResolution(
            String input,
            ViaAddressResolver.Kind kind,
            String output) {
        ViaAddressResolver.Resolution actual =
                ViaAddressResolver.resolve(input, SEARCH, false);
        assertEquals(input, actual.getInput());
        assertEquals(kind, actual.getKind());
        assertEquals(output, actual.getOutput());
    }
}
