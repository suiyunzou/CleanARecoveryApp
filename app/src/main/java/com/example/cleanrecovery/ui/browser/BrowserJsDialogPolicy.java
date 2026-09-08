package com.example.cleanrecovery.ui.browser;

import java.util.HashMap;
import java.util.Map;

/** Installed Via f8.a/f8.b: independent alert/confirm scores per host. */
public final class BrowserJsDialogPolicy {
    private final Map<String, long[]> records = new HashMap<>();

    public int request(String host, boolean confirm, long now) {
        long[] record = records.computeIfAbsent(host, key -> new long[4]);
        int index = confirm ? 2 : 0;
        long score = record[index], elapsed = now - record[index + 1];
        if (elapsed >= 60000) score = 0;
        else if (score < 30) score = Math.min(30, score +
                (elapsed <= 5000 ? 15 : elapsed <= 10000 ? 10 : elapsed <= 30000 ? 2 : 1));
        record[index] = score;
        if (score <= 30) record[index + 1] = now;
        return score < 30 ? 0 : score > 30 ? 2 : 1;
    }

    public void answer(String host, boolean confirm, boolean suppress, long now) {
        long[] record = records.computeIfAbsent(host, key -> new long[4]);
        int index = confirm ? 2 : 0;
        if (record[index] > 30) return;
        if (suppress) record[index] = 31;
        record[index + 1] = now;
    }
}
