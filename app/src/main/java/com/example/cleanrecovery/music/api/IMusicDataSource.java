package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.Lyrics;
import com.example.cleanrecovery.music.data.SongInfo;
import java.util.List;

/** Music data source abstraction — swap implementation without touching UI. */
public interface IMusicDataSource {

    /** Search songs by keyword. Returns empty list when network fails. */
    List<SongInfo> search(String keyword, int page) throws Exception;

    /** Get discovery / recommended songs. */
    List<SongInfo> getRecommendations(int page) throws Exception;

    /** Resolve a playable stream URL for the given song. */
    String resolvePlayUrl(SongInfo song) throws Exception;

    /** Resolve a shortened trial URL when VIP is unavailable. */
    String resolveTrialUrl(SongInfo song) throws Exception;

    /**
     * Fetch time-synced lyrics (LRC) for the given song. Returns an empty
     * (non-null) Lyrics object when no lyrics are available.
     */
    Lyrics getLyrics(SongInfo song) throws Exception;

    /**
     * Resolve a download URL for the given song at the requested quality.
     * Quality is one of: "128", "320", "flac" (best-effort). Returns null
     * when the requested quality is unavailable (e.g. VIP-only).
     */
    String resolveDownloadUrl(SongInfo song, String quality) throws Exception;
}

