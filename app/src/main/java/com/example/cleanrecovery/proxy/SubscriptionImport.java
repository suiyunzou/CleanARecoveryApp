package com.example.cleanrecovery.proxy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure-Java validation for pasted multi-subscription URL lists. */
public final class SubscriptionImport {
    private SubscriptionImport() {
    }

    public static List<String> parseUrls(String text) {
        Set<String> unique = new LinkedHashSet<>();
        if (text == null) return new ArrayList<>();
        for (String token : text.split("[\\r\\n]+")) {
            String value = token.trim();
            if (value.startsWith("http://") || value.startsWith("https://")) {
                unique.add(value);
            }
        }
        return new ArrayList<>(unique);
    }

    /** Loose check: share-link list (any scheme) or base64 blob worth importing. */
    public static boolean looksLikeNodeList(String value) {
        if (value == null || value.isEmpty()) return false;
        if (value.contains("://")) return true;
        return value.length() >= 16 && value.matches("[A-Za-z0-9+/=\\-_,:.\\s]+");
    }
}
