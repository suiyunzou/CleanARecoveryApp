package com.example.cleanrecovery.music.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Time-synced lyrics parsed from LRC text. Each line carries a timestamp (ms)
 * and the lyric text. Lines are sorted ascending by time. Untimed lines
 * (e.g. metadata [ti:...], [ar:...]) are dropped.
 */
public class Lyrics {

    /** A single timed lyric line. */
    public static class Line {
        public final long timeMs;
        public final String text;
        public Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text == null ? "" : text;
        }
    }

    private final List<Line> lines;
    /** Raw LRC text retained for caching. */
    public final String raw;

    private Lyrics(List<Line> lines, String raw) {
        this.lines = lines;
        this.raw = raw;
    }

    /** Returns an empty lyrics object (no lines). */
    public static Lyrics empty() {
        return new Lyrics(Collections.emptyList(), "");
    }

    public List<Line> lines() { return lines; }

    public boolean isEmpty() { return lines.isEmpty(); }

    public int size() { return lines.size(); }

    /**
     * Index of the line active at the given playback position. Returns -1 if
     * no line is active yet (e.g. before the first line). The active line is
     * the last line whose timestamp is &lt;= {@code positionMs}.
     */
    public int indexOfActive(long positionMs) {
        if (lines.isEmpty()) return -1;
        // Binary search for the last line with timeMs <= positionMs
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).timeMs <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    /** Parse LRC-formatted text. Tolerant of multiple timestamps per line. */
    public static Lyrics parse(String lrc) {
        if (lrc == null || lrc.isEmpty()) return empty();
        List<Line> out = new ArrayList<>();
        // Matches [mm:ss.xx] or [mm:ss] timestamps. Allows 1-3 fractional digits.
        Pattern ts = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
        String[] rawLines = lrc.split("\n");
        for (String raw : rawLines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            Matcher m = ts.matcher(line);
            List<Long> times = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                times.add(parseTimestamp(m));
                lastEnd = m.end();
            }
            String text = line.substring(lastEnd).trim();
            if (times.isEmpty()) {
                // No timestamp — skip metadata / stray lines.
                continue;
            }
            for (Long t : times) {
                out.add(new Line(t, text));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(a.timeMs, b.timeMs));
        return new Lyrics(out, lrc);
    }

    private static long parseTimestamp(Matcher m) {
        try {
            int min = Integer.parseInt(m.group(1));
            int sec = Integer.parseInt(m.group(2));
            long ms = (min * 60L + sec) * 1000L;
            String frac = m.group(3);
            if (frac != null && !frac.isEmpty()) {
                // Normalize to milliseconds: 2 digits → centiseconds, 3 → ms.
                int f;
                if (frac.length() == 1) f = Integer.parseInt(frac) * 100;
                else if (frac.length() == 2) f = Integer.parseInt(frac) * 10;
                else f = Integer.parseInt(frac.substring(0, 3));
                ms += f;
            }
            return ms;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
