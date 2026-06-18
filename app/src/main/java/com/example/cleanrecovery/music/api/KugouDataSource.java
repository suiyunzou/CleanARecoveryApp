package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.Lyrics;
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
    // Endpoint 4: Concept (lite) privileged play URL via /v5/url.
    // 概念版通过 appid=3116 + clientver=11440 + 特定 page_id/pid 参数区分，
    // 不是通过 Cookie。参考 KuGouMusicApi module/song_url.js。
    private static final String CONCEPT_PLAY_URL = "https://gateway.kugou.com/v5/url";
    private static final String LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    private static final String LITE_KEY_SALT = "185672dd44712f60bb1736df5a377e82";
    private static final int LITE_APPID = 3116;
    private static final int LITE_CLIENTVER = 11440;

    // Lyrics endpoints — search for a candidate, then download the LRC body.
    private static final String LYRIC_SEARCH_URL = "http://lyrics.kugou.com/search";
    private static final String LYRIC_DOWNLOAD_URL = "http://lyrics.kugou.com/download";

    /** Auth context for VIP song URL resolution. Set by MusicApp after login. */
    private volatile AuthContext auth;

    public static class AuthContext {
        public final String token;
        public final String userid;
        public final String mid;
        public final String dfid;
        public AuthContext(String token, String userid, String mid, String dfid) {
            this.token = token;
            this.userid = userid;
            this.mid = mid;
            this.dfid = dfid;
        }
    }

    /** Set the auth context so VIP songs can be resolved with the user's token. */
    public void setAuthContext(AuthContext ctx) {
        this.auth = ctx;
    }

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
     *
     * For VIP songs, the concept (Youth) endpoint is tried first with the
     * user's token + userid so the privileged CDN URL is returned.
     */
    @Override
    public String resolvePlayUrl(SongInfo song) throws Exception {
        if (song == null || isEmpty(song.hash)) return null;

        // For VIP songs, try the concept endpoint first (carries token + userid)
        if (song.vipRequired && auth != null && !isEmpty(auth.token)) {
            try {
                String url = tryConceptPlayUrl(song);
                if (!isEmpty(url)) return url;
            } catch (Exception e) {
                android.util.Log.w("KugouDataSource",
                        "concept play url failed: " + e.getMessage());
            }
        }

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

    /**
     * 获取同步歌词。
     *
     * <p>实现策略（按优先级回退）：</p>
     * <ol>
     *   <li><b>优先获取 LRC 格式</b>：调用 {@code lyrics.kugou.com/download?fmt=lrc}
     *       获取明文 LRC 歌词。LRC 为标准行同步格式，仅 Base64 传输编码，未加密。</li>
     *   <li><b>KRC 回退</b>：当 LRC 获取失败或内容为空时，调用 {@code fmt=krc}
     *       获取 KRC 二进制数据，通过 {@link KrcDecoder} 进行 XOR + zlib 解码。
     *       KRC 为酷狗逐字歌词格式，毫秒级精度。</li>
     *   <li><b>返回空歌词</b>：当两种格式均不可用时返回 {@link Lyrics#empty()}。</li>
     * </ol>
     *
     * <p>技术原理参考调研报告：
     * LRC 未加密可直接获取；KRC 加密但密钥固定可逆（16 字节 XOR 密钥 + zlib 压缩）。</p>
     *
     * @param song 歌曲信息，需包含 hash 字段
     * @return 解析后的歌词对象，无歌词时返回空对象（非 null）
     */
    @Override
    public Lyrics getLyrics(SongInfo song) throws Exception {
        if (song == null || isEmpty(song.hash)) return Lyrics.empty();

        // Step 1: 搜索匹配的歌词候选
        LyricCandidate candidate = searchLyricCandidate(song);
        if (candidate == null) {
            return Lyrics.empty();
        }

        // Step 2: 优先下载 LRC 格式（明文，未加密）
        Lyrics lrc = downloadLrc(candidate);
        if (lrc != null && !lrc.isEmpty()) {
            return lrc;
        }

        // Step 3: LRC 不可用时回退到 KRC 格式（加密，需解码）
        Lyrics krc = downloadAndDecodeKrc(candidate);
        if (krc != null && !krc.isEmpty()) {
            android.util.Log.d("KugouDataSource",
                    "LRC unavailable, fell back to KRC for hash=" + song.hash);
            return krc;
        }

        return Lyrics.empty();
    }

    /**
     * 搜索歌词候选，返回排名第一的候选（服务器已按匹配度排序）。
     *
     * @param song 歌曲信息
     * @return 歌词候选，无匹配返回 null
     */
    private LyricCandidate searchLyricCandidate(SongInfo song) throws Exception {
        String searchUrl = LYRIC_SEARCH_URL
                + "?ver=1&man=yes&client=pc&keyword=" + encode(buildLyricKeyword(song))
                + "&duration=" + Math.max(0, song.duration * 1000)
                + "&hash=" + encode(song.hash.toLowerCase());
        JsonObject resp = get(searchUrl);
        int status = num(resp, "status");
        // candidates 是数组，不是对象。早期版本可能返回 candidates.list，这里两种都兼容。
        JsonArray list = null;
        JsonElement candidatesElem = resp.get("candidates");
        if (candidatesElem != null && candidatesElem.isJsonArray()) {
            list = candidatesElem.getAsJsonArray();
        } else if (candidatesElem != null && candidatesElem.isJsonObject()) {
            list = array(candidatesElem.getAsJsonObject(), "list");
        }
        if (status != 200 || list == null || list.size() == 0) {
            return null;
        }
        JsonObject best = list.get(0).getAsJsonObject();
        String id = str(best, "id", "lrcid");
        String accesskey = str(best, "accesskey");
        if (isEmpty(id) || isEmpty(accesskey)) return null;
        return new LyricCandidate(id, accesskey);
    }

    /**
     * 下载 LRC 格式歌词。
     *
     * <p>调用 {@code lyrics.kugou.com/download?fmt=lrc}，返回的 {@code content}
     * 字段为 Base64 编码的 UTF-8 LRC 明文文本。LRC 格式未加密，仅做传输编码。</p>
     *
     * @param candidate 歌词候选
     * @return 解析后的歌词对象，获取失败返回 null
     */
    private Lyrics downloadLrc(LyricCandidate candidate) {
        try {
            String dlUrl = LYRIC_DOWNLOAD_URL
                    + "?ver=1&client=pc&id=" + encode(candidate.id)
                    + "&accesskey=" + encode(candidate.accesskey)
                    + "&fmt=lrc&charset=utf8";
            JsonObject dl = get(dlUrl);
            String content = str(dl, "content");
            if (isEmpty(content)) return null;

            // content 为 Base64 编码的 UTF-8 LRC 明文
            byte[] bytes = java.util.Base64.getDecoder().decode(content);
            String lrc = new String(bytes, StandardCharsets.UTF_8);
            return Lyrics.parse(lrc);
        } catch (Exception e) {
            android.util.Log.w("KugouDataSource",
                    "LRC download failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 下载并解码 KRC 格式歌词。
     *
     * <p>调用 {@code lyrics.kugou.com/download?fmt=krc}，返回的 {@code content}
     * 字段为 Base64 编码的 KRC 二进制数据。KRC 格式采用固定密钥 XOR + zlib 压缩，
     * 通过 {@link KrcDecoder} 解码后得到 UTF-8 明文逐字歌词。</p>
     *
     * <p>解码后的 KRC 明文含逐字时间戳，但格式与 LRC 不完全兼容。
     * 当前实现提取行级时间戳转换为 LRC 兼容格式，保留基本同步功能。
     * 逐字精度信息在 Lyrics 数据结构中暂未使用，由 LyricsView 自行处理。</p>
     *
     * @param candidate 歌词候选
     * @return 解析后的歌词对象，获取或解码失败返回 null
     */
    private Lyrics downloadAndDecodeKrc(LyricCandidate candidate) {
        try {
            String dlUrl = LYRIC_DOWNLOAD_URL
                    + "?ver=1&client=pc&id=" + encode(candidate.id)
                    + "&accesskey=" + encode(candidate.accesskey)
                    + "&fmt=krc&charset=utf8";
            JsonObject dl = get(dlUrl);
            String content = str(dl, "content");
            if (isEmpty(content)) return null;

            // 使用 KrcDecoder 解码：Base64 → 跳过文件头 → XOR 解密 → zlib 解压
            String krcText = KrcDecoder.decodeFromBase64(content);
            if (isEmpty(krcText)) return null;

            // 将 KRC 逐字格式转换为 LRC 兼容格式
            String lrcCompatible = KrcToLrcConverter.convert(krcText);
            return Lyrics.parse(lrcCompatible);
        } catch (Exception e) {
            android.util.Log.w("KugouDataSource",
                    "KRC download/decode failed: " + e.getMessage());
            return null;
        }
    }

    /** 歌词候选信息（id + accesskey 为下载凭证）。 */
    private static class LyricCandidate {
        final String id;
        final String accesskey;
        LyricCandidate(String id, String accesskey) {
            this.id = id;
            this.accesskey = accesskey;
        }
    }

    private String buildLyricKeyword(SongInfo song) {
        // Prefer "title artist"; fall back to title alone.
        if (!isEmpty(song.artist)) return song.title + " " + song.artist;
        return song.title;
    }

    /**
     * Resolve a download URL at the requested quality. Maps the quality token
     * to the concept /v5/url "quality" parameter (128 / 320 / flac) and reuses
     * the privileged endpoint when auth is available. Falls back to the standard
     * play URL resolution for 128 kbps.
     */
    @Override
    public String resolveDownloadUrl(SongInfo song, String quality) throws Exception {
        if (song == null || isEmpty(song.hash)) return null;
        String q = normalizeQuality(quality);
        // Try the concept endpoint with the requested quality first (works for
        // both VIP and non-VIP when auth is present).
        if (auth != null && !isEmpty(auth.token)) {
            try {
                String url = tryConceptPlayUrl(song, q);
                if (!isEmpty(url)) return url;
            } catch (Exception e) {
                android.util.Log.w("KugouDataSource",
                        "download url concept failed: " + e.getMessage());
            }
        }
        // For standard quality, the regular play URL is acceptable.
        if ("128".equals(q)) {
            return resolvePlayUrl(song);
        }
        // Higher qualities require entitlement — return null so the UI can
        // prompt the user.
        return null;
    }

    private static String normalizeQuality(String quality) {
        if (quality == null) return "128";
        String q = quality.toLowerCase();
        if (q.contains("flac") || q.contains("lossless") || q.contains("sq")) return "flac";
        if (q.contains("320") || q.contains("high") || q.contains("hq")) return "320";
        return "128";
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

    /**
     * Endpoint 4: Concept (lite) privileged play URL via /v5/url.
     *
     * <p>概念版通过 appid=3116 + clientver=11440 + 特定 page_id/pid/ppage_id 参数区分，
     * 不是通过 Cookie。参考 KuGouMusicApi module/song_url.js 和 util/request.js。</p>
     *
     * <p>签名算法：MD5(salt + sorted("k=v" pairs).join("") + bodyJson + salt)，
     * 概念版盐值 LnT6xpN3khm36zse0QzvmgTZ3waWdRSA。</p>
     *
     * <p>key 参数：MD5(hash + "185672dd44712f60bb1736df5a377e82" + appid + mid + userid)。</p>
     */
    private String tryConceptPlayUrl(SongInfo song) {
        return tryConceptPlayUrl(song, "128");
    }

    /**
     * Same as {@link #tryConceptPlayUrl(SongInfo)} but allows specifying a
     * quality token ("128", "320", "flac"). Used by the download module to
     * request higher-bitrate streams.
     */
    private String tryConceptPlayUrl(SongInfo song, String quality) {
        try {
            String hash = song.hash.toLowerCase();
            String dfid = isEmpty(auth.dfid) ? "-" : auth.dfid;
            String mid = auth.mid != null ? auth.mid : "";
            long clienttime = System.currentTimeMillis() / 1000;

            // 构造参数（TreeMap 自动按 key 排序，用于签名）
            java.util.TreeMap<String, String> params = new java.util.TreeMap<>();
            params.put("album_audio_id", "0");
            params.put("album_id", "0");
            params.put("area_code", "1");
            params.put("behavior", "play");
            params.put("cdnBackup", "1");
            params.put("clienttime", String.valueOf(clienttime));
            params.put("clientver", String.valueOf(LITE_CLIENTVER));
            params.put("cmd", "26");
            params.put("dfid", dfid);
            params.put("hash", hash);
            params.put("IsFreePart", "0");
            params.put("mid", mid);
            params.put("module", "");
            params.put("page_id", "967177915");  // 概念版特有
            params.put("pid", "411");             // 概念版特有（标准版为 2）
            params.put("pidversion", "3001");
            params.put("ppage_id", "356753938,823673182,967485191");  // 概念版特有
            params.put("quality", quality == null ? "128" : quality);
            params.put("ssa_flag", "is_fromtrack");
            params.put("uuid", "-");
            params.put("version", "11430");

            // 概念版标识参数
            params.put("appid", String.valueOf(LITE_APPID));

            // 登录态参数（VIP 歌曲必需）
            if (!isEmpty(auth.token)) params.put("token", auth.token);
            if (!isEmpty(auth.userid)) params.put("userid", auth.userid);

            // 生成 key 参数：MD5(hash + keySalt + appid + mid + userid)
            String keyUserId = isEmpty(auth.userid) ? "0" : auth.userid;
            String key = md5(hash + LITE_KEY_SALT + LITE_APPID + mid + keyUserId);
            params.put("key", key);

            // 生成 signature：MD5(salt + sorted("k=v").join("") + bodyJson + salt)
            // bodyJson 为空（GET 请求无 body）
            StringBuilder sigBuilder = new StringBuilder(LITE_SALT);
            for (java.util.Map.Entry<String, String> e : params.entrySet()) {
                sigBuilder.append(e.getKey()).append("=").append(e.getValue());
            }
            sigBuilder.append(LITE_SALT);
            String signature = md5(sigBuilder.toString());
            params.put("signature", signature);

            // 构建最终 URL
            StringBuilder urlBuilder = new StringBuilder(CONCEPT_PLAY_URL).append("?");
            boolean first = true;
            for (java.util.Map.Entry<String, String> e : params.entrySet()) {
                if (!first) urlBuilder.append("&");
                urlBuilder.append(e.getKey()).append("=").append(encode(e.getValue()));
                first = false;
            }
            String fullUrl = urlBuilder.toString();

            android.util.Log.d("KugouDataSource",
                    "concept /v5/url hash=" + hash + " userid=" + auth.userid);

            JsonObject resp = getWithHeaders(fullUrl, new String[][]{
                    {"User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi"},
                    {"x-router", "trackercdn.kugou.com"},
                    {"dfid", dfid},
                    {"mid", mid},
                    {"clienttime", String.valueOf(clienttime)},
                    {"kg-rc", "1"},
                    {"kg-thash", "5d816a0"},
                    {"kg-rec", "1"},
                    {"kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F"}
            });

            // /v5/url 实际响应格式（经 PC 端验证）：
            //   成功: {"status":1,"url":["http://...mp3","http://...mp3"],"backupUrl":["..."],"hash":"..."}
            //   失败: {"status":0,"err_code":20028,"error":"需要VIP权限"}
            // 注意：url 字段在顶层且是数组（不是 data.url 字符串）。
            int status = resp.has("status") ? resp.get("status").getAsInt() : -1;
            int errCode = resp.has("err_code") ? resp.get("err_code").getAsInt() : 0;
            if (status != 1 || errCode != 0) {
                String errMsg = resp.has("error") ? resp.get("error").getAsString()
                        : resp.has("msg") ? resp.get("msg").getAsString()
                        : resp.has("error_msg") ? resp.get("error_msg").getAsString()
                        : "status=" + status + " err_code=" + errCode;
                android.util.Log.w("KugouDataSource",
                        "concept /v5/url status=" + status + " err_code=" + errCode + " msg=" + errMsg);
                return null;
            }

            // 1. 顶层 url 数组（概念版 /v5/url 的标准格式）
            String urlFromTop = firstUrlFromArray(resp, "url");
            if (!isEmpty(urlFromTop)) return urlFromTop;

            // 2. 顶层 backupUrl 数组
            String backupFromTop = firstUrlFromArray(resp, "backupUrl");
            if (!isEmpty(backupFromTop)) return backupFromTop;

            // 3. data 对象内的 url（兼容旧格式）
            JsonObject data = object(resp, "data");
            if (data != null) {
                String urlInData = firstUrlFromArray(data, "url");
                if (!isEmpty(urlInData)) return urlInData;
                String playUrl = str(data, "play_url", "playUrl");
                if (!isEmpty(playUrl)) return playUrl;
                JsonArray urls = array(data, "urls");
                if (urls != null) {
                    for (JsonElement e : urls) {
                        if (e.isJsonPrimitive()) {
                            String u = e.getAsString();
                            if (!isEmpty(u)) return u;
                        }
                    }
                }
            }
            return firstPlayableUrl(resp);
        } catch (Exception e) {
            android.util.Log.w("KugouDataSource", "concept /v5/url error: " + e.getMessage());
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
        // 优先处理 url 字段为数组的情况（概念版 /v5/url 的实际格式）
        String urlFromArray = firstUrlFromArray(resp, "url");
        if (!isEmpty(urlFromArray)) return urlFromArray;

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

    /**
     * 从 JSON 对象中提取指定字段的第一个 URL。
     * 兼容三种格式：
     *   1. "url": ["http://...", "http://..."]  （数组，概念版 /v5/url 实际格式）
     *   2. "url": "http://..."                  （字符串）
     *   3. 字段不存在                           （返回空字符串）
     */
    private static String firstUrlFromArray(JsonObject o, String key) {
        if (o == null || !o.has(key)) return "";
        try {
            JsonElement e = o.get(key);
            if (e == null || e.isJsonNull()) return "";
            if (e.isJsonArray()) {
                JsonArray arr = e.getAsJsonArray();
                for (JsonElement item : arr) {
                    if (item.isJsonPrimitive()) {
                        String u = item.getAsString();
                        if (!isEmpty(u)) return u;
                    }
                }
                return "";
            }
            if (e.isJsonPrimitive()) return e.getAsString();
        } catch (Exception ignored) {
        }
        return "";
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
