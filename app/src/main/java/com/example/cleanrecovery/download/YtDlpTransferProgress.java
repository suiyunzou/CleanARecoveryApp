package com.example.cleanrecovery.download;

import org.json.JSONObject;

/** Machine-readable progress for the currently transferring video/audio file. */
public final class YtDlpTransferProgress {
    public static final String PREFIX = "__SHU_PROGRESS__";
    public final long downloaded, total;
    public final boolean estimated, audio;
    private YtDlpTransferProgress(JSONObject json) {
        downloaded = Math.max(0, json.optLong("downloaded"));
        long exact = json.optLong("total");
        estimated = exact <= 0 && json.optLong("estimate") > 0;
        total = Math.max(0, exact > 0 ? exact : json.optLong("estimate"));
        audio = "none".equals(json.optString("vcodec"));
    }
    public int percent() { return total > 0 ? (int) Math.min(100, downloaded * 100d / total) : -1; }
    public static YtDlpTransferProgress parse(String line) {
        if (line == null || !line.startsWith(PREFIX)) return null;
        try { return new YtDlpTransferProgress(new JSONObject(line.substring(PREFIX.length()))); }
        catch (Exception ignored) { return null; }
    }
}
