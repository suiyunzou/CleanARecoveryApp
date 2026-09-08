package com.example.cleanrecovery.music.ui;

import com.example.cleanrecovery.music.data.SongInfo;

import java.util.Locale;

/**
 * 本地音频文件名 → 展示用标题/歌手。
 *
 * <p>下载库/会话恢复里可能存的是原始文件名（"王菲 - 传奇.mp3"）。
 * 展示层统一过一遍：去音频后缀；形如"歌手 - 标题"的拆出歌手（仅当歌手为空
 * 或为占位文案时）。幂等：解析后的结果再过一遍不变。</p>
 */
public final class LocalSongNames {

    private static final String EXT_PATTERN = "\\.(mp3|flac|wav|m4a|aac|ogg|opus|wma)$";

    private LocalSongNames() {
    }

    /** 就地规整 SongInfo 的 title/artist（队列、歌单页、已下载页共用）。 */
    public static void apply(SongInfo song, String placeholderArtist) {
        if (song == null || song.title == null) return;
        String title = stripExtension(song.title);
        String parsedArtist = null;
        int sep = title.indexOf(" - ");
        if (sep > 0 && sep < title.length() - 3) {
            parsedArtist = title.substring(0, sep).trim();
            title = title.substring(sep + 3).trim();
        }
        boolean artistMissing = song.artist == null || song.artist.trim().isEmpty()
                || (placeholderArtist != null && placeholderArtist.equals(song.artist));
        if (artistMissing && parsedArtist != null && !parsedArtist.isEmpty()) {
            song.artist = parsedArtist;
        }
        song.title = title;
    }

    static String stripExtension(String name) {
        if (name == null) return "";
        return name.replaceFirst(EXT_PATTERN, "");
    }

    /** 仅展示用（不改原对象）：去音频后缀并剥"歌手 - "前缀，顶部大标题/迷你条用。 */
    public static String displayTitle(String raw) {
        if (raw == null) return "";
        String t = stripExtension(raw).trim();
        int sep = t.indexOf(" - ");
        if (sep > 0 && sep < t.length() - 3) {
            t = t.substring(sep + 3).trim();
        }
        return t;
    }

    /** 仅展示用（不改原对象）：从"歌手 - 标题"形态提取歌手；无前缀返回 fallback。 */
    public static String displayArtist(String raw, String fallback) {
        if (raw == null) return fallback;
        String t = stripExtension(raw).trim();
        int sep = t.indexOf(" - ");
        if (sep > 0 && sep < t.length() - 3) {
            return t.substring(0, sep).trim();
        }
        return fallback;
    }
}
