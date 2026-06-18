package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.SongInfo;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Real Kugou metadata data source using lightweight public mobile endpoints. */
public class KugouDataSource implements IMusicDataSource {

    private static final String SEARCH_URL = "http://mobilecdn.kugou.com/api/v3/search/song";
    private static final String RANK_URL = "http://mobilecdn.kugou.com/api/v3/rank/song";

    // Multiple play URL endpoints — tried in order until one returns a playable URL.
    // Endpoint 1: Legacy getSongInfo (HTTP, cleartext allowed in network_security_config)
    private static final String PLAY_INFO_URL = "http://m.kugou.com/app/i/getSongInfo.php";
    // Endpoint 2: trackercdn v2 — requires a key derived from the hash
    private static final String TRACKER_URL = "http://trackercdn.kugou.com/i/v2/";
    // Endpoint 3: Web API — more reliable for non-VIP songs, returns JSON with play_url
    private static final String WEB_PLAY_URL = "https://wwwapi.kugou.com/yy/index.php";

    @Override
    public List<SongInfo> search(String keyword, int page) throws Exception {
        String url = SEARCH_URL
                + "?format=json"
                + "&keyword=" + encode(keyword)
                + "&page=" + Math.max(1, page)
                + "&pagesize=30"
                + "&showtype=1";
        return parseSongList(get(url));
    }

    @Override
    public List<SongInfo> getRecommendations(int page) throws Exception {
        String url = RANK_URL
                + "?format=json"
                + "&rankid=8888"
                + "&page=" + Math.max(1, page)
                + "&pagesize=30";
        return parseSongList(get(url));
    }

    /**
     * Resolve a playable URL by trying multiple endpoints in sequence.
     * The first endpoint that returns a non-empty URL wins.
     */
    @Override
    public String resolvePlayUrl(SongInfo song) throws Exception {
        if (song == null || isEmpty(song.hash)) return null;

        // Endpoint 1: Legacy getSongInfo.php
        String url = tryLegacyPlayInfo(song.hash);
        if (!isEmpty(url)) return url;

        // Endpoint 2: trackercdn v2 with key
        url = tryTrackerCdn(song.hash);
        if (!isEmpty(url)) return url;

        // Endpoint 3: Web API (HTTPS)
        url = tryWebPlayUrl(song);
        if (!isEmpty(url)) return url;

        return null;
    }

    @Override
    public String resolveTrialUrl(SongInfo song) throws Exception {
        // Trial URL uses the same resolution but with a trial-specific parameter.
        // For now, fall back to the standard resolution — VIP songs without
        // entitlement will return null, which the UI handles gracefully.
        return resolvePlayUrl(song);
    }

    // ---- Play URL endpoints ------------------------------------------------

    /** Endpoint 1: Legacy getSongInfo.php?cmd=playInfo&hash=... */
    private String tryLegacyPlayInfo(String hash) throws Exception {
        try {
            JsonObject resp = get(PLAY_INFO_URL + "?cmd=playInfo&hash=" + encode(hash));
            return firstPlayableUrl(resp);
        } catch (Exception e) {
            return null; // Fail silently — try the next endpoint
        }
    }

    /**
     * Endpoint 2: trackercdn v2.
     * The key is MD5(hash_lowercase + "kgcloudv2").
     */
    private String tryTrackerCdn(String hash) {
        try {
            String lowerHash = hash.toLowerCase();
            String key = md5(lowerHash + "kgcloudv2");
            String url = TRACKER_URL
                    + "?key=" + key
                    + "&hash=" + lowerHash
                    + "&appid=1005"
                    + "&pid=2"
                    + "&cmd=4"
                    + "&behavior=play"
                    + "&album_id=";
            JsonObject resp = get(url);
            // trackercdn returns urls array at top level or under data
            String playUrl = firstPlayableUrl(resp);
            if (!isEmpty(playUrl)) return playUrl;

            JsonArray urls = array(resp, "urls");
            if (urls != null) {
                for (JsonElement e : urls) {
                    if (e.isJsonPrimitive()) {
                        String u = e.getAsString();
                        if (!isEmpty(u)) return u;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Endpoint 3: Web API wwwapi.kugou.com.
     * More reliable for non-VIP songs; returns JSON with play_url under data.
     */
    private String tryWebPlayUrl(SongInfo song) {
        try {
            StringBuilder url = new StringBuilder(WEB_PLAY_URL);
            url.append("?r=play/getdata");
            url.append("&hash=").append(encode(song.hash.toLowerCase()));
            if (!isEmpty(song.albumId)) {
                url.append("&album_id=").append(encode(song.albumId));
            }
            url.append("&mid=").append(md5(song.hash + System.currentTimeMillis()));
            url.append("&platid=4");

            JsonObject resp = getWithHeaders(url.toString(), new String[][]{
                    {"User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36"},
                    {"Referer", "https://www.kugou.com/song/"},
                    {"Cookie", "kg_mid=" + md5(song.hash)}
            });

            JsonObject data = object(resp, "data");
            if (data != null) {
                String playUrl = str(data, "play_url", "play_backup_url");
                if (!isEmpty(playUrl)) return playUrl;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject get(String urlStr) throws Exception {
        return getWithHeaders(urlStr, null);
    }

    private JsonObject getWithHeaders(String urlStr, String[][] headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(10_000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) KugouConcept/1.0");
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        if (headers != null) {
            for (String[] h : headers) {
                if (h != null && h.length >= 2) {
                    conn.setRequestProperty(h[0], h[1]);
                }
            }
        }

        int code = conn.getResponseCode();
        if (code != 200) throw new RuntimeException("HTTP " + code);

        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        } finally {
            conn.disconnect();
        }
        return JsonParser.parseString(sb.toString()).getAsJsonObject();
    }

    private List<SongInfo> parseSongList(JsonObject resp) {
        List<SongInfo> result = new ArrayList<>();
        JsonArray arr = findSongArray(resp);
        if (arr == null) return result;

        for (JsonElement element : arr) {
            if (!element.isJsonObject()) continue;
            JsonObject songJson = element.getAsJsonObject();
            SongInfo song = parseSong(songJson);
            if (!isEmpty(song.hash) && !isEmpty(song.title)) {
                result.add(song);
            }
            JsonArray grouped = array(songJson, "group");
            if (grouped != null) {
                for (JsonElement groupedElement : grouped) {
                    if (!groupedElement.isJsonObject()) continue;
                    SongInfo groupedSong = parseSong(groupedElement.getAsJsonObject());
                    if (!isEmpty(groupedSong.hash) && !isEmpty(groupedSong.title)) {
                        result.add(groupedSong);
                    }
                }
            }
        }
        return result;
    }

    private JsonArray findSongArray(JsonObject resp) {
        JsonObject data = object(resp, "data");
        if (data == null) return null;
        JsonArray info = array(data, "info");
        if (info != null) return info;
        JsonArray lists = array(data, "lists");
        if (lists != null) return lists;
        return array(resp, "info");
    }

    private SongInfo parseSong(JsonObject o) {
        SongInfo s = new SongInfo();
        s.hash = upper(str(o, "hash", "Hash", "file_hash"));
        s.albumId = str(o, "album_id", "albumid", "AlbumID");
        s.title = str(o, "songname", "songName", "name", "remark", "filename", "fileName");
        s.artist = str(o, "singername", "singerName", "author_name", "authorName");
        if (isEmpty(s.artist)) s.artist = parseAuthors(o);
        s.album = str(o, "album_name", "albumname", "remark");
        s.duration = num(o, "duration", "timeLength", "timelength", "time");
        s.imgUrl = normalizeImage(str(o, "album_sizable_cover", "img", "image", "cover", "imgUrl", "album_img"));
        if (isEmpty(s.imgUrl)) s.imgUrl = normalizeImage(nestedStr(o, "trans_param", "union_cover"));
        s.vipRequired = num(o, "pay_type", "pay_type_320", "pay_type_sq", "privilege") > 0
                || num(o, "fail_process", "fail_process_320", "fail_process_sq") > 0
                || num(o, "pkg_price", "pkg_price_320", "pkg_price_sq") > 0;
        return s;
    }

    private String firstPlayableUrl(JsonObject resp) {
        String url = str(resp, "url", "play_url", "playUrl");
        if (!isEmpty(url)) return url;

        JsonArray backups = array(resp, "backup_url");
        if (backups != null) {
            for (JsonElement e : backups) {
                if (e.isJsonPrimitive()) {
                    String backup = e.getAsString();
                    if (!isEmpty(backup)) return backup;
                }
            }
        }

        JsonObject data = object(resp, "data");
        if (data != null) {
            url = str(data, "url", "play_url", "playUrl");
            if (!isEmpty(url)) return url;
            backups = array(data, "backup_url");
            if (backups != null && backups.size() > 0 && backups.get(0).isJsonPrimitive()) {
                return backups.get(0).getAsString();
            }
        }
        return null;
    }

    private static String parseAuthors(JsonObject o) {
        JsonArray authors = array(o, "authors");
        if (authors == null || authors.size() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonElement e : authors) {
            if (!e.isJsonObject()) continue;
            String name = str(e.getAsJsonObject(), "author_name", "name");
            if (isEmpty(name)) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }

    private static JsonObject object(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonArray array(JsonObject o, String key) {
        if (o == null || !o.has(key)) return null;
        try {
            JsonElement e = o.get(key);
            return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String nestedStr(JsonObject o, String objectKey, String valueKey) {
        JsonObject nested = object(o, objectKey);
        return nested == null ? "" : str(nested, valueKey);
    }

    private static String str(JsonObject o, String... keys) {
        if (o == null) return "";
        for (String k : keys) {
            if (!o.has(k)) continue;
            try {
                JsonElement e = o.get(k);
                if (e != null && !e.isJsonNull()) return e.getAsString();
            } catch (Exception ignored) {
            }
        }
        return "";
    }

    private static int num(JsonObject o, String... keys) {
        if (o == null) return 0;
        for (String k : keys) {
            if (!o.has(k)) continue;
            try {
                JsonElement e = o.get(k);
                if (e != null && !e.isJsonNull()) return e.getAsInt();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static String normalizeImage(String url) {
        if (isEmpty(url)) return "";
        return url.replace("{size}", "240");
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    private static String encode(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return input; // Fallback (non-cryptographic, but functional)
        }
    }
}
