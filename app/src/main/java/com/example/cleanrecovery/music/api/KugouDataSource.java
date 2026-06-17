package com.example.cleanrecovery.music.api;

import android.util.Pair;
import com.example.cleanrecovery.music.data.SongInfo;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Hits public Kugou tool.php endpoints for song listing/search only. */
public class KugouDataSource implements IMusicDataSource {

    private static final String TOOL_URL = "http://mobileservice.kugou.com/new/app/i/tool.php";
    private final IAuthService auth;

    public KugouDataSource(IAuthService auth) {
        this.auth = auth;
    }

    @Override
    public List<SongInfo> search(String keyword, int page) throws Exception {
        StringBuilder sb = new StringBuilder(TOOL_URL);
        sb.append("?cmd=517");
        sb.append("&keyword=").append(encode(keyword));
        sb.append("&page=").append(page);
        sb.append("&pagesize=25");
        sb.append("&plat=0");
        sb.append("&clientver=11000");
        JsonObject resp = get(sb.toString());
        return parseSongList(resp);
    }

    @Override
    public List<SongInfo> getRecommendations(int page) throws Exception {
        StringBuilder sb = new StringBuilder(TOOL_URL);
        sb.append("?cmd=501");
        sb.append("&page=").append(page);
        sb.append("&pagesize=25");
        sb.append("&plat=0");
        sb.append("&clientver=11000");
        JsonObject resp = get(sb.toString());
        return parseSongList(resp);
    }

    @Override
    public String resolvePlayUrl(SongInfo song) throws Exception {
        if (auth == null || !auth.isLoggedIn()) return null;
        StringBuilder sb = new StringBuilder(TOOL_URL);
        sb.append("?cmd=502");
        sb.append("&hash=").append(song.hash);
        sb.append("&album_id=").append(song.albumId);
        sb.append("&bitrate=128");
        sb.append("&plat=0");
        sb.append("&token=").append(auth.getToken());
        sb.append("&userid=").append(auth.currentUser().userId);
        JsonObject resp = get(sb.toString());
        return extractUrl(resp);
    }

    @Override
    public String resolveTrialUrl(SongInfo song) throws Exception {
        StringBuilder sb = new StringBuilder(TOOL_URL);
        sb.append("?cmd=502");
        sb.append("&hash=").append(song.hash);
        sb.append("&album_id=").append(song.albumId);
        sb.append("&bitrate=64");
        sb.append("&plat=0");
        JsonObject resp = get(sb.toString());
        String url = extractUrl(resp);
        // If server still returned a full URL, truncate to trial (client cannot enforce, just surface)
        return url;
    }

    // ---- internal ---------------------------------------------------------

    private JsonObject get(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);
        conn.setRequestProperty("User-Agent", "kugou-lite/2.5.5");
        conn.setRequestProperty("Accept", "application/json");
        int code = conn.getResponseCode();
        if (code != 200) throw new RuntimeException("HTTP " + code);
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
        }
        return JsonParser.parseString(sb.toString()).getAsJsonObject();
    }

    private String extractUrl(JsonObject resp) {
        if (resp.has("url")) return resp.get("url").getAsString();
        if (resp.has("data")) {
            JsonObject data = resp.getAsJsonObject("data");
            if (data.has("url")) return data.get("url").getAsString();
            if (data.has("play_url")) return data.get("play_url").getAsString();
        }
        return null;
    }

    private List<SongInfo> parseSongList(JsonObject resp) {
        List<SongInfo> result = new ArrayList<>();
        JsonArray arr = null;
        try {
            if (resp.has("data")) {
                JsonObject d = resp.getAsJsonObject("data");
                if (d.has("lists")) arr = d.getAsJsonArray("lists");
                else if (d.has("info")) arr = d.getAsJsonArray("info");
            }
        } catch (Exception ignored) {}
        if (arr == null) return result;

        for (int i = 0; i < arr.size(); i++) {
            try {
                JsonObject o = arr.get(i).getAsJsonObject();
                SongInfo s = new SongInfo();
                s.hash = str(o, "hash", "Hash");
                s.albumId = str(o, "album_id", "albumid", "AlbumID");
                s.title = str(o, "songname", "filename", "name");
                s.artist = str(o, "singername", "singer_name", "author_name");
                s.album = str(o, "album_name", "albumname");
                s.duration = num(o, "duration", "time");
                s.imgUrl = str(o, "img", "image", "cover");
                s.vipRequired = num(o, "privilege", "pay_type") > 0
                        || num(o, "is_vip") > 0;
                if (!s.title.isEmpty()) result.add(s);
            } catch (Exception ignored) {}
        }
        return result;
    }

    private static String str(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k)) try { return o.get(k).getAsString(); } catch (Exception ignored) {}
        }
        return "";
    }

    private static int num(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k)) try { return o.get(k).getAsInt(); } catch (Exception ignored) {}
        }
        return 0;
    }

    @SuppressWarnings("deprecation")
    private static String encode(String s) { return URLEncoder.encode(s); }
}
