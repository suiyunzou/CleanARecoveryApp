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
}
