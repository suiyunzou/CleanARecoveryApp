package com.example.cleanrecovery.music.data;

/** Immutable song info. */
public class SongInfo {
    public String hash;
    public String albumId;
    public String fileId;
    public String mixSongId;
    public String title;
    public String artist;
    public String album;
    public int    duration;  // seconds
    public String imgUrl;
    public boolean vipRequired;
    public String localPath;
    public int playCount;

    public String durationFormatted() {
        int m = duration / 60;
        int s = duration % 60;
        return m + ":" + (s < 10 ? "0" : "") + s;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SongInfo)) return false;
        SongInfo s = (SongInfo) o;
        if (hash != null && s.hash != null && !hash.isEmpty() && !s.hash.isEmpty())
            return hash.equals(s.hash);
        return title != null && title.equals(s.title)
                && artist != null && artist.equals(s.artist);
    }

    @Override
    public int hashCode() {
        int h = hash != null ? hash.hashCode() : 0;
        return h ^ (title != null ? title.hashCode() : 0);
    }
}
