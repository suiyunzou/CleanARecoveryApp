package com.example.cleanrecovery.music.data;

/** Metadata for a song that has been (or is being) downloaded to local storage. */
public class DownloadedSong {
    public String hash;
    public String title;
    public String artist;
    public String album;
    public int duration;          // seconds
    public String quality;        // "128" | "320" | "flac"
    public String localPath;      // absolute path to the downloaded file
    public long sizeBytes;        // file size in bytes (0 while in progress)
    public long downloadedAt;     // epoch millis when the download finished
    public String imgUrl;

    /** Human-readable size, e.g. "3.2 MB". */
    public String sizeFormatted() {
        if (sizeBytes <= 0) return "0 B";
        double kb = sizeBytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }

    public String durationFormatted() {
        int m = duration / 60;
        int s = duration % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    /** Display label for the quality token, e.g. "320 kbps". */
    public String qualityLabel() {
        if ("flac".equalsIgnoreCase(quality)) return "FLAC";
        if ("320".equals(quality)) return "320 kbps";
        return "128 kbps";
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DownloadedSong)) return false;
        DownloadedSong d = (DownloadedSong) o;
        return hash != null && hash.equals(d.hash);
    }

    @Override
    public int hashCode() { return hash == null ? 0 : hash.hashCode(); }
}
