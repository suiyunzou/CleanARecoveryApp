package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.Lyrics;
import com.example.cleanrecovery.music.data.RemotePlaylist;
import com.example.cleanrecovery.music.data.SongInfo;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

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
    private static final String GATEWAY_URL = "https://gateway.kugou.com";
    private static final String USER_PLAYLIST_PATH = "/v7/get_all_list";
    private static final String PLAYLIST_ADD_PATH = "/cloudlist.service/v5/add_list";
    private static final String PLAYLIST_TRACKS_PATH = "/v4/get_list_all_file";
    private static final String PLAYLIST_TRACKS_FALLBACK_PATH = "/pubsongs/v2/get_other_list_file_nofilt";
    private static final String PLAYLIST_TRACKS_ADD_PATH = "/cloudlist.service/v6/add_song";
    private static final String PLAYLIST_TRACKS_DELETE_PATH = "/v4/delete_songs";
    // 改名端点：http://cloudlist.service.kugou.com/v1/modify_list（直连 + http）
    private static final String PLAYLIST_RENAME_PATH = "/v1/modify_list";
    // 删歌单端点：与改名同族加密协议，body 扁平 {"total_ver","listid","type"}
    private static final String PLAYLIST_DELETE_PATH = "/v1/delete_list";
    // 概念版云歌单移动端加密协议参数（官方 PlayListEditInputFragment 抓包还原）：
    // clientver 固定 10597（key 公式与 UA 都依赖它，与网关侧 LITE_CLIENTVER 无关）
    private static final String CLOUDLIST_MOBILE_HOST = "http://cloudlist.service.kugou.com";
    private static final String MOBILE_CLIENTVER = "10597";
    // p 参数的 RSA-1024 内置公钥（模数，指数 65537），包裹随机密钥种子做密钥协商
    private static final String CLOUDLIST_RSA_MODULUS =
            "c40a2d0da76511f3bb1cc2bbd3afbd8bea83b4d6b05b6c13eb8920c53f1af767"
                    + "9b32ba0d0edb843240ef1b836efed3ee240734c14c1399fd6594d16af22f5252"
                    + "5d14d72e0155c6dcc8638d4f7bb94f3a0b1f4c29f991972f2a160a25eb0a9e72"
                    + "4336be7f69bbd319ffab1c6dd8470b021dc434f3faba89f4a2a01b33bdbdd08b";
    // Endpoint 4: Concept (lite) privileged play URL via /v5/url.
    // 概念版通过 appid=3116 + clientver=11440 + 特定 page_id/pid 参数区分，
    // 不是通过 Cookie。参考 KuGouMusicApi module/song_url.js。
    private static final String CONCEPT_PLAY_URL = "https://gateway.kugou.com/v5/url";
    private static final String LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    private static final String LITE_KEY_SALT = "185672dd44712f60bb1736df5a377e82";
    private static final int LITE_APPID = 3116;
    private static final int LITE_CLIENTVER = 11440;
    private static final Gson GSON = new Gson();

    // Lyrics endpoints — search for a candidate, then download the LRC body.
    private static final String LYRIC_SEARCH_URL = "http://lyrics.kugou.com/search";
    private static final String LYRIC_DOWNLOAD_URL = "http://lyrics.kugou.com/download";

    /** Auth context for VIP song URL resolution. Set by MusicApp after login. */
    private volatile AuthContext auth;
    private volatile RemotePlaylist favoritePlaylistCache;
    private volatile long favoritePlaylistCacheAt;
    /** 最近一次 resolvePlayUrl 失败的可读原因（成功后清空）；UI 用它给出针对性提示。 */
    public volatile String lastResolveFailReason;

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
        favoritePlaylistCache = null;
        favoritePlaylistCacheAt = 0;
    }

    /**
     * 酷狗云「我喜欢」歌单：爱心按钮的云端同步目标（按歌单名匹配）。
     * listid 对同一用户稳定，缓存 10 分钟，避免每次点爱心都拉全量歌单；
     * 云端还没有「我喜欢」时自动创建一个。
     */
    public RemotePlaylist getFavoriteCloudPlaylist() throws Exception {
        ensureAuth();
        if (favoritePlaylistCache != null
                && System.currentTimeMillis() - favoritePlaylistCacheAt < 600_000L) {
            return favoritePlaylistCache;
        }
        RemotePlaylist favorite = findPlaylistByName(getAllUserPlaylists(30), "我喜欢");
        if (favorite == null) {
            createUserPlaylist("我喜欢", false);
            favorite = findPlaylistByName(getAllUserPlaylists(30), "我喜欢");
        }
        if (favorite != null) {
            favoritePlaylistCache = favorite;
            favoritePlaylistCacheAt = System.currentTimeMillis();
        }
        return favorite;
    }

    private static RemotePlaylist findPlaylistByName(List<RemotePlaylist> playlists, String name) {
        for (RemotePlaylist playlist : playlists) {
            if (name.equals(playlist.name)) return playlist;
        }
        return null;
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

    @Override
    public List<RemotePlaylist> getUserPlaylists(int page, int pageSize) throws Exception {
        return getUserPlaylists(page, pageSize, 2);
    }

    @Override
    public List<RemotePlaylist> getAllUserPlaylists(int pageSize) throws Exception {
        int safePageSize = Math.max(1, pageSize);
        List<RemotePlaylist> result = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int page = 1; page <= 20; page++) {
            List<RemotePlaylist> batch = getUserPlaylists(page, safePageSize);
            if (batch.isEmpty()) break;
            for (RemotePlaylist playlist : batch) {
                String key = firstNonEmpty(playlist.playableId(), playlist.name);
                if (seen.add(key)) result.add(playlist);
            }
            if (batch.size() < safePageSize) break;
        }
        return result;
    }

    List<RemotePlaylist> getUserPlaylists(int page, int pageSize, int type) throws Exception {
        ensureAuth();
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("userid", auth.userid);
        body.put("token", auth.token);
        body.put("total_ver", 979);
        body.put("type", type);
        body.put("page", Math.max(1, page));
        body.put("pagesize", Math.max(1, pageSize));

        TreeMap<String, String> params = new TreeMap<>();
        params.put("plat", "1");
        params.put("userid", auth.userid);
        params.put("token", auth.token);

        JsonObject resp = signedPost(USER_PLAYLIST_PATH, params, body,
                new String[][]{{"x-router", "cloudlist.service.kugou.com"}});
        return parseRemotePlaylists(resp);
    }

    @Override
    public List<SongInfo> getUserPlaylistSongs(RemotePlaylist playlist, int page, int pageSize) throws Exception {
        ensureAuth();
        if (playlist == null) return new ArrayList<>();
        String listId = firstNonEmpty(playlist.listId, playlist.id, playlist.globalCollectionId);
        if (isEmpty(listId)) return new ArrayList<>();

        TreeMap<String, Object> body = new TreeMap<>();
        body.put("listid", listId);
        body.put("userid", auth.userid);
        body.put("area_code", 1);
        body.put("show_relate_goods", 0);
        body.put("pagesize", Math.max(1, pageSize));
        body.put("allplatform", 1);
        body.put("show_cover", 1);
        body.put("type", 0);
        body.put("token", auth.token);
        body.put("page", Math.max(1, page));

        JsonObject resp = signedPost(PLAYLIST_TRACKS_PATH, new TreeMap<>(), body,
                new String[][]{{"x-router", "cloudlist.service.kugou.com"}});
        List<SongInfo> songs = parseSongList(resp);
        if (!songs.isEmpty()) return songs;

        String collectionId = firstNonEmpty(playlist.globalCollectionId, playlist.id, playlist.listId);
        if (isEmpty(collectionId)) return songs;
        TreeMap<String, String> params = new TreeMap<>();
        int safePageSize = Math.max(1, pageSize);
        params.put("area_code", "1");
        params.put("begin_idx", String.valueOf((Math.max(1, page) - 1) * safePageSize));
        params.put("plat", "1");
        params.put("type", "1");
        params.put("mode", "1");
        params.put("personal_switch", "1");
        params.put("extend_fields", "abtags,hot_cmt,popularization");
        params.put("pagesize", String.valueOf(safePageSize));
        params.put("global_collection_id", collectionId);
        resp = signedGet(PLAYLIST_TRACKS_FALLBACK_PATH, params, null);
        return parseSongList(resp);
    }

    @Override
    public List<SongInfo> getAllUserPlaylistSongs(RemotePlaylist playlist, int pageSize) throws Exception {
        int safePageSize = Math.max(1, pageSize);
        List<SongInfo> result = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int page = 1; page <= 50; page++) {
            List<SongInfo> batch = getUserPlaylistSongs(playlist, page, safePageSize);
            if (batch.isEmpty()) break;
            for (SongInfo song : batch) {
                String key = firstNonEmpty(song.hash, song.title + "|" + song.artist);
                if (seen.add(key)) result.add(song);
            }
            if (batch.size() < safePageSize) break;
        }
        return result;
    }

    @Override
    public void createUserPlaylist(String name, boolean privatePlaylist) throws Exception {
        ensureAuth();
        if (isEmpty(name)) {
            throw new IllegalArgumentException("Playlist name required");
        }
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("userid", auth.userid);
        body.put("token", auth.token);
        body.put("total_ver", 0);
        body.put("name", name);
        body.put("type", 0);
        body.put("source", 1);
        body.put("is_pri", privatePlaylist ? 1 : 0);
        body.put("list_create_userid", auth.userid);
        body.put("list_create_listid", "");
        body.put("list_create_gid", "");
        body.put("from_shupinmv", 0);

        TreeMap<String, String> params = new TreeMap<>();
        params.put("last_time", String.valueOf(System.currentTimeMillis() / 1000));
        params.put("last_area", "gztx");
        params.put("userid", auth.userid);
        params.put("token", auth.token);
        signedPost(PLAYLIST_ADD_PATH, params, body, null);
    }

    @Override
    public void renameUserPlaylist(RemotePlaylist playlist, String newName) throws Exception {
        ensureAuth();
        // 酷狗约定：仅自建歌单可改名，默认歌单（我喜欢/默认收藏）服务端会拒绝
        String listId = firstNonEmpty(playlist.listId, playlist.id, playlist.globalCollectionId);
        if (isEmpty(listId)) throw new IllegalArgumentException("Playlist id required");
        if (isEmpty(newName)) throw new IllegalArgumentException("Playlist name required");

        // 移动端 v1/modify_list 协议：body 用数字 listid；total_ver 必须等于账户当前
        // 版本（get_all_list 的 data.total_ver，乐观锁，不一致即 error_code 20010）
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("total_ver", fetchAccountTotalVer());
        body.put("listid", parseLong(listId));
        body.put("type", 0);
        body.put("name", newName);
        body.put("sort", 0);
        body.put("tags", "");
        body.put("intro", "");
        JsonObject resp = cloudListEncryptedPost(PLAYLIST_RENAME_PATH, GSON.toJson(body));
        logD("KugouDataSource", "modify_list resp=" + resp);
        int status = num(resp, "status");
        int errorCode = num(resp, "error_code", "err_code");
        if (status != 1 || errorCode != 0) {
            String reason = str(resp, "info", "error", "error_msg", "msg");
            throw new RuntimeException("modify_list status=" + status
                    + " error_code=" + errorCode
                    + (reason.isEmpty() ? "" : " " + reason));
        }
    }

    @Override
    public void deleteUserPlaylist(RemotePlaylist playlist) throws Exception {
        ensureAuth();
        String listId = playlist == null ? ""
                : firstNonEmpty(playlist.listId, playlist.id, playlist.globalCollectionId);
        if (isEmpty(listId)) throw new IllegalArgumentException("Playlist id required");

        // 与改名同构：扁平 body + 账户级 total_ver 乐观锁
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("total_ver", fetchAccountTotalVer());
        body.put("listid", parseLong(listId));
        body.put("type", 0);
        JsonObject resp = cloudListEncryptedPost(PLAYLIST_DELETE_PATH, GSON.toJson(body));
        logD("KugouDataSource", "delete_list resp=" + resp);
        int status = num(resp, "status");
        int errorCode = num(resp, "error_code", "err_code");
        if (status != 1 || errorCode != 0) {
            String reason = str(resp, "info", "error", "error_msg", "msg");
            throw new RuntimeException("delete_list status=" + status
                    + " error_code=" + errorCode
                    + (reason.isEmpty() ? "" : " " + reason));
        }
    }

    /** 账户级歌单版本：任何云歌单修改都会 +1，提交时必须与 get_all_list 当前值一致。 */
    private int fetchAccountTotalVer() throws Exception {
        ensureAuth();
        TreeMap<String, Object> body = new TreeMap<>();
        body.put("userid", auth.userid);
        body.put("token", auth.token);
        body.put("total_ver", 979);
        body.put("type", 2);
        body.put("page", 1);
        body.put("pagesize", 1);
        TreeMap<String, String> params = new TreeMap<>();
        params.put("plat", "1");
        params.put("userid", auth.userid);
        params.put("token", auth.token);
        JsonObject resp = signedPost(USER_PLAYLIST_PATH, params, body,
                new String[][]{{"x-router", "cloudlist.service.kugou.com"}});
        JsonObject data = object(resp, "data");
        return data != null && data.has("total_ver") && !data.get("total_ver").isJsonNull()
                ? data.get("total_ver").getAsInt() : 0;
    }

    /**
     * 概念版云歌单移动端加密协议直连（与官方客户端完全同构）：
     * <ul>
     *   <li>随机 6 位种子 K；MD5(K) 的前/后 16 个 hex 字符的 ASCII 文本即
     *       AES-128-CBC 的 key/iv，body 为该 AES 加密的 JSON</li>
     *   <li>p = RSA-PKCS1({"aes":K,"uid":userid,"token":token}) 大写 hex，
     *       服务端用内置私钥解出 K 再解 body（token 不随 query 明文传输）</li>
     *   <li>key = MD5(appid + LITE_SALT + 10597 + clienttime)，与网关签名不同源</li>
     *   <li>响应体用同一 K 加密，解密后为 JSON</li>
     * </ul>
     */
    private JsonObject cloudListEncryptedPost(String path, String bodyJson) throws Exception {
        ensureAuth();
        String kseed = randomKeySeed();
        byte[] encrypted = kugouAes(kseed, Cipher.ENCRYPT_MODE,
                bodyJson.getBytes(StandardCharsets.UTF_8));
        String clienttime = String.valueOf(System.currentTimeMillis() / 1000);
        String key = md5(LITE_APPID + LITE_SALT + MOBILE_CLIENTVER + clienttime);
        String dfid = isEmpty(auth.dfid) ? "-" : auth.dfid;
        StringBuilder url = new StringBuilder(CLOUDLIST_MOBILE_HOST).append(path)
                .append("?clienttime=").append(clienttime)
                .append("&mid=").append(encode(auth.mid))
                .append("&key=").append(key)
                .append("&dfid=").append(encode(dfid))
                .append("&clientver=").append(MOBILE_CLIENTVER)
                .append("&appid=").append(LITE_APPID)
                .append("&p=").append(rsaWrapKeySeed(kseed));

        HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent", liteUserAgent(MOBILE_CLIENTVER, "CloudMusic"));
        // KG-THash 官方为逐请求变化值，服务端不校验具体取值（抓包比对确认）
        conn.setRequestProperty("KG-THash", "5ee3d8");
        conn.setRequestProperty("KG-Rec", "1");
        conn.setRequestProperty("KG-RC", "1");
        conn.setRequestProperty("Content-Type", "application/json;charset=utf-8");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(encrypted);
        }
        int code = conn.getResponseCode();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        java.io.InputStream stream = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        if (stream != null) {
            byte[] tmp = new byte[4096];
            int n;
            while ((n = stream.read(tmp)) > 0) buf.write(tmp, 0, n);
        }
        conn.disconnect();
        if (code != 200) throw new RuntimeException("HTTP " + code);
        byte[] raw = buf.toByteArray();
        byte[] plain;
        try {
            plain = kugouAes(kseed, Cipher.DECRYPT_MODE, raw);
        } catch (Exception e) {
            plain = raw; // 部分错误路径直接回明文 JSON
        }
        String text = new String(plain, StandardCharsets.UTF_8).trim();
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /** K 种子 → MD5 前/后 16 hex 字符（ASCII）作 AES-128-CBC key/iv，PKCS5 填充。 */
    private static byte[] kugouAes(String kseed, int opmode, byte[] data) throws Exception {
        String md5hex = md5(kseed);
        SecretKeySpec key = new SecretKeySpec(
                md5hex.substring(0, 16).getBytes(StandardCharsets.US_ASCII), "AES");
        IvParameterSpec iv = new IvParameterSpec(
                md5hex.substring(16).getBytes(StandardCharsets.US_ASCII));
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(opmode, key, iv);
        return cipher.doFinal(data);
    }

    /** p 参数：官方内置 1024 位公钥包裹 {"aes":K,"uid":..,"token":..}。 */
    private String rsaWrapKeySeed(String kseed) throws Exception {
        String keyJson = "{\"aes\":\"" + kseed + "\",\"uid\":" + auth.userid
                + ",\"token\":\"" + auth.token + "\"}";
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PublicKey pub = factory.generatePublic(new RSAPublicKeySpec(
                new BigInteger(CLOUDLIST_RSA_MODULUS, 16), BigInteger.valueOf(65537)));
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.ENCRYPT_MODE, pub);
        byte[] wrapped = rsa.doFinal(keyJson.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(wrapped.length * 2);
        for (byte b : wrapped) hex.append(String.format("%02X", b));
        return hex.toString();
    }

    /** 官方抓包样本均为 6 位 [A-Za-z0-9] 随机种子（如 PRvdGD）。 */
    private static String randomKeySeed() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(6);
        for (int i = 0; i < 6; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return sb.toString();
    }

    @Override
    public void addSongsToUserPlaylist(RemotePlaylist playlist, List<SongInfo> songs) throws Exception {
        ensureAuth();
        String listId = playlist == null ? "" : firstNonEmpty(playlist.listId, playlist.id, playlist.globalCollectionId);
        if (isEmpty(listId) || songs == null || songs.isEmpty()) return;
        JsonArray resource = new JsonArray();
        for (SongInfo song : songs) {
            if (song == null || isEmpty(song.hash)) continue;
            JsonObject item = new JsonObject();
            item.addProperty("number", 1);
            item.addProperty("name", firstNonEmpty(song.title, ""));
            item.addProperty("hash", song.hash);
            item.addProperty("size", 0);
            item.addProperty("sort", 0);
            item.addProperty("timelen", Math.max(0, song.duration * 1000));
            item.addProperty("bitrate", 0);
            item.addProperty("album_id", parseLong(song.albumId));
            item.addProperty("mixsongid", parseLong(firstNonEmpty(song.mixSongId, song.albumId)));
            resource.add(item);
        }
        if (resource.size() == 0) return;

        TreeMap<String, Object> body = new TreeMap<>();
        body.put("userid", auth.userid);
        body.put("token", auth.token);
        body.put("listid", listId);
        body.put("list_ver", 0);
        body.put("type", 0);
        body.put("slow_upload", 1);
        body.put("scene", "false;null");
        body.put("data", resource);

        TreeMap<String, String> params = new TreeMap<>();
        params.put("last_time", String.valueOf(System.currentTimeMillis() / 1000));
        params.put("last_area", "gztx");
        params.put("userid", auth.userid);
        params.put("token", auth.token);
        // 校验业务结果：kugou 约定 status=1 成功；失败时 error_code/info 携带原因。
        // 不校验会把失败当成功——歌没进云歌单却提示"已加入"
        JsonObject resp = signedPost(PLAYLIST_TRACKS_ADD_PATH, params, body, null);
        int status = num(resp, "status");
        int errorCode = num(resp, "error_code", "err_code");
        if (status != 1 || errorCode != 0) {
            String reason = str(resp, "info", "error", "error_msg", "msg");
            throw new RuntimeException("add_song status=" + status
                    + " error_code=" + errorCode
                    + (reason.isEmpty() ? "" : " " + reason));
        }
    }

    @Override
    public void deleteSongsFromUserPlaylist(RemotePlaylist playlist, List<SongInfo> songs) throws Exception {
        ensureAuth();
        String listId = playlist == null ? "" : firstNonEmpty(playlist.listId, playlist.id, playlist.globalCollectionId);
        if (isEmpty(listId) || songs == null || songs.isEmpty()) return;
        JsonArray resource = new JsonArray();
        for (SongInfo song : songs) {
            long fileId = parseLong(song == null ? "" : song.fileId);
            if (fileId <= 0) continue;
            JsonObject item = new JsonObject();
            item.addProperty("fileid", fileId);
            resource.add(item);
        }
        if (resource.size() == 0) {
            throw new IllegalArgumentException("Missing cloud file id");
        }

        TreeMap<String, Object> body = new TreeMap<>();
        body.put("listid", listId);
        body.put("userid", auth.userid);
        body.put("token", auth.token);
        body.put("type", 0);
        body.put("list_ver", 0);
        body.put("data", resource);
        signedPost(PLAYLIST_TRACKS_DELETE_PATH, new TreeMap<>(), body,
                new String[][]{{"x-router", "cloudlist.service.kugou.com"}});
    }

    @Override
    public List<SongInfo> getUserListenRanking(int type) {
        // The documented endpoint requires an additional RSA-encrypted "p"
        // parameter. Keep the interface seam in place and fall back safely
        // until the RSA helper is added.
        return new ArrayList<>();
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
        if (song == null || isEmpty(song.hash)) {
            lastResolveFailReason = "歌曲缺少播放信息（hash 为空）";
            return null;
        }

        // When logged in, use the concept endpoint first for both free and VIP
        // songs. Cloud playlist entries often do not expose the same fields as
        // search results, and the legacy public endpoints may return no URL.
        if (auth != null && !isEmpty(auth.token)) {
            try {
                String url = tryConceptPlayUrl(song);
                if (!isEmpty(url)) {
                    lastResolveFailReason = null;
                    return url;
                }
            } catch (Exception e) {
                logW("KugouDataSource", "concept play url failed: " + e.getMessage());
            }
        }

        // Endpoint 1: Legacy getSongInfo.php
        String url = tryLegacyPlayInfo(song.hash);
        if (!isEmpty(url)) {
            lastResolveFailReason = null;
            return url;
        }

        // Endpoint 2: trackercdn v2 with key
        url = tryTrackerCdn(song.hash);
        if (!isEmpty(url)) {
            lastResolveFailReason = null;
            return url;
        }

        // Endpoint 3: Web API (HTTPS)
        url = tryWebPlayUrl(song);
        if (!isEmpty(url)) {
            lastResolveFailReason = null;
            return url;
        }

        // 全部接口无果：给出可读原因（VIP 无权益 / 版权 / 网络），供 UI 明确提示
        if (isEmpty(lastResolveFailReason)) {
            lastResolveFailReason = "无可用音源（版权限制或网络异常）";
        }
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
     *   <li><b>优先获取 KRC 格式</b>：调用 {@code fmt=krc}
     *       获取 KRC 二进制数据，通过 {@link KrcDecoder} 进行 XOR + zlib 解码。
     *       保留毫秒级逐字时间与译文。</li>
     *   <li><b>LRC 回退</b>：KRC 不可用时调用 {@code fmt=lrc}，保留行同步歌词。</li>
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

        // KRC retains word timing and translations; LRC is a line-only fallback.
        Lyrics krc = downloadAndDecodeKrc(candidate);
        if (krc != null && !krc.isEmpty()) {
            return krc;
        }
        Lyrics lrc = downloadLrc(candidate);
        return lrc == null ? Lyrics.empty() : lrc;
    }

    /** Real climax start in milliseconds. Missing metadata is -1, never an estimated point. */
    public long getClimaxStartMs(SongInfo song) throws Exception {
        if (song == null || isEmpty(song.hash)) return -1;
        JsonObject entry = new JsonObject();
        entry.addProperty("hash", song.hash);
        JsonArray request = new JsonArray();
        request.add(entry);
        return parseClimaxStartMs(get("https://expendablekmrcdn.kugou.com/v1/audio_climax/audio?data="
                + encode(request.toString())));
    }

    static long parseClimaxStartMs(JsonObject response) {
        if (num(response, "status") != 1) return -1;
        JsonArray data = array(response, "data");
        if (data == null || data.size() == 0) return -1;
        try {
            long start = data.get(0).getAsJsonObject().get("start_time").getAsLong();
            return start >= 0 ? start : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /**
     * 搜索歌词候选，返回排名第一的候选（服务器已按匹配度排序）。
     *
     * <p>本地文件（如"已下载"）的 artist 常是歌单名而非真实歌手，会把关键词搜挂；
     * "歌名+歌手"无候选时回退仅按歌名再搜一次。</p>
     *
     * @param song 歌曲信息
     * @return 歌词候选，无匹配返回 null
     */
    private LyricCandidate searchLyricCandidate(SongInfo song) throws Exception {
        LyricCandidate candidate =
                searchLyricCandidateBy(buildLyricKeyword(song), song);
        if (candidate == null && !isEmpty(song.artist) && !song.artist.equals(song.title)) {
            candidate = searchLyricCandidateBy(song.title, song);
        }
        return candidate;
    }

    private LyricCandidate searchLyricCandidateBy(String keyword, SongInfo song) throws Exception {
        String searchUrl = LYRIC_SEARCH_URL
                + "?ver=1&man=yes&client=pc&keyword=" + encode(keyword)
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
            byte[] bytes = com.example.cleanrecovery.util.Base64Compat.decode(content);
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
     * <p>直接解析 KRC 明文，保留逐字时间戳及 language 译文元数据。</p>
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

            return KrcParser.parse(krcText);
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
                logW("KugouDataSource", "download url concept failed: " + e.getMessage());
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
            params.put("album_audio_id", firstNonEmpty(song.mixSongId, song.albumId, "0"));
            params.put("album_id", firstNonEmpty(song.albumId, "0"));
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

            logD("KugouDataSource", "concept /v5/url hash=" + hash + " userid=" + auth.userid);

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
                logW("KugouDataSource",
                        "concept /v5/url status=" + status + " err_code=" + errCode + " msg=" + errMsg);
                // 透出可读原因：20028=需要VIP权益；版权/地区类错误同样原样保留
                lastResolveFailReason = errMsg;
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
            logW("KugouDataSource", "concept /v5/url error: " + e.getMessage());
            return null;
        }
    }

    private void ensureAuth() {
        if (auth == null || isEmpty(auth.token) || isEmpty(auth.userid)) {
            throw new IllegalStateException("Kugou login required");
        }
    }

    private JsonObject signedPost(String path, TreeMap<String, String> params,
                                  TreeMap<String, Object> body,
                                  String[][] headers) throws Exception {
        String bodyJson = GSON.toJson(body != null ? body : new TreeMap<String, Object>());
        return signedRequest("POST", path, params, bodyJson, headers);
    }

    private JsonObject signedGet(String path, TreeMap<String, String> params,
                                 String[][] headers) throws Exception {
        return signedRequest("GET", path, params, "", headers);
    }

    private JsonObject signedRequest(String method, String path, TreeMap<String, String> params,
                                     String bodyJson, String[][] headers) throws Exception {
        String url = GATEWAY_URL + path + "?" + buildQuery(buildSignedParams(params, bodyJson));
        return executeSigned(url, method, bodyJson, buildSignedParams(params, bodyJson), headers);
    }

    /** 组装公共参数并计算签名（gateway 与直连云服务共用）。 */
    private TreeMap<String, String> buildSignedParams(TreeMap<String, String> params,
                                                      String bodyJson) {
        TreeMap<String, String> allParams = new TreeMap<>();
        String dfid = auth != null && !isEmpty(auth.dfid) ? auth.dfid : "-";
        String mid = auth != null && !isEmpty(auth.mid) ? auth.mid : "";
        String userid = auth != null && !isEmpty(auth.userid) ? auth.userid : "0";
        String token = auth != null && !isEmpty(auth.token) ? auth.token : "";

        allParams.put("dfid", dfid);
        allParams.put("mid", mid);
        allParams.put("uuid", "-");
        allParams.put("appid", String.valueOf(LITE_APPID));
        allParams.put("clientver", String.valueOf(LITE_CLIENTVER));
        allParams.put("clienttime", String.valueOf(System.currentTimeMillis() / 1000));
        if (!isEmpty(token)) allParams.put("token", token);
        if (!isEmpty(userid) && !"0".equals(userid)) allParams.put("userid", userid);
        if (params != null) allParams.putAll(params);
        allParams.put("signature", signatureAndroidParams(allParams, bodyJson));
        return allParams;
    }

    /**
     * 概念版 UA：Android&lt;系统版本&gt;-1070-&lt;clientver&gt;-46-0-&lt;业务标签&gt;-wifi。
     * 与官方 UA 同构；系统版本取真机实际值——写死具体版本在别的真机上
     * 会与设备现实矛盾，属于可被风控识别的不自洽信号。
     */
    private static String liteUserAgent(String clientver, String moduleTag) {
        String os = android.os.Build.VERSION.RELEASE;
        if (os == null || os.isEmpty()) os = "15";
        return "Android" + os + "-1070-" + clientver + "-46-0-" + moduleTag + "-wifi";
    }

    private JsonObject executeSigned(String url, String method, String bodyJson,
                                     TreeMap<String, String> allParams,
                                     String[][] headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(15_000);
        conn.setRequestProperty("User-Agent",
                liteUserAgent(String.valueOf(LITE_CLIENTVER), "DiscoveryDRADProtocol"));
        conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
        conn.setRequestProperty("dfid", allParams.get("dfid"));
        conn.setRequestProperty("mid", allParams.get("mid"));
        conn.setRequestProperty("clienttime", allParams.get("clienttime"));
        conn.setRequestProperty("kg-rc", "1");
        conn.setRequestProperty("kg-thash", "5d816a0");
        conn.setRequestProperty("kg-rec", "1");
        conn.setRequestProperty("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
        if (headers != null) {
            for (String[] h : headers) {
                if (h != null && h.length >= 2) conn.setRequestProperty(h[0], h[1]);
            }
        }
        if ("POST".equals(method)) {
            byte[] bytes = bodyJson.getBytes(StandardCharsets.UTF_8);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
            }
        }

        int code = conn.getResponseCode();
        StringBuilder sb = new StringBuilder();
        java.io.InputStream stream = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        if (stream != null) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
        }
        conn.disconnect();
        if (code != 200) throw new RuntimeException("HTTP " + code);
        if (sb.length() == 0) return new JsonObject();
        return JsonParser.parseString(sb.toString()).getAsJsonObject();
    }

    static String signatureAndroidParams(Map<String, String> params, String data) {
        StringBuilder sb = new StringBuilder(LITE_SALT);
        java.util.List<String> keys = new java.util.ArrayList<>(params.keySet());
        java.util.Collections.sort(keys);
        for (String key : keys) {
            if ("signature".equals(key)) continue;
            sb.append(key).append("=").append(params.get(key));
        }
        if (data != null) sb.append(data);
        sb.append(LITE_SALT);
        return md5(sb.toString());
    }

    private static String buildQuery(TreeMap<String, String> params) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) sb.append("&");
            sb.append(encode(e.getKey())).append("=").append(encode(e.getValue()));
            first = false;
        }
        return sb.toString();
    }

    private static void logD(String tag, String message) {
        try {
            android.util.Log.d(tag, message);
        } catch (Throwable ignored) {
            System.out.println(tag + " DEBUG: " + message);
        }
    }

    private static void logW(String tag, String message) {
        try {
            android.util.Log.w(tag, message);
        } catch (Throwable ignored) {
            System.out.println(tag + " WARN: " + message);
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

    static List<RemotePlaylist> parseRemotePlaylists(JsonObject resp) {
        List<RemotePlaylist> result = new ArrayList<>();
        JsonArray arr = findRemotePlaylistArray(resp);
        if (arr == null) return result;
        for (JsonElement element : arr) {
            if (!element.isJsonObject()) continue;
            JsonObject o = element.getAsJsonObject();
            RemotePlaylist p = parseRemotePlaylist(o);
            if (!isEmpty(p.name) && !isEmpty(p.playableId())) result.add(p);
        }
        return result;
    }

    private static JsonArray findRemotePlaylistArray(JsonObject resp) {
        JsonObject data = object(resp, "data");
        JsonArray arr = firstArrayWithPlaylistShape(data, "info", "list", "lists", "data");
        if (arr != null) return arr;
        arr = firstArrayWithPlaylistShape(resp, "info", "list", "lists", "data");
        if (arr != null) return arr;
        return findArrayByPlaylistShape(resp);
    }

    private static JsonArray firstArrayWithPlaylistShape(JsonObject o, String... keys) {
        if (o == null) return null;
        for (String key : keys) {
            JsonArray arr = array(o, key);
            if (looksLikePlaylistArray(arr)) return arr;
        }
        return null;
    }

    private static JsonArray findArrayByPlaylistShape(JsonElement element) {
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonArray()) {
            JsonArray arr = element.getAsJsonArray();
            return looksLikePlaylistArray(arr) ? arr : null;
        }
        if (!element.isJsonObject()) return null;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            JsonArray found = findArrayByPlaylistShape(entry.getValue());
            if (found != null) return found;
        }
        return null;
    }

    private static boolean looksLikePlaylistArray(JsonArray arr) {
        if (arr == null || arr.size() == 0) return false;
        for (JsonElement e : arr) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            boolean hasId = !isEmpty(str(o, "listid", "listId", "global_collection_id",
                    "globalCollectionId", "id", "specialid"));
            boolean hasName = !isEmpty(str(o, "name", "listname", "list_name", "title",
                    "specialname"));
            if (hasId && hasName) return true;
        }
        return false;
    }

    private static RemotePlaylist parseRemotePlaylist(JsonObject o) {
        RemotePlaylist p = new RemotePlaylist();
        p.listId = str(o, "listid", "listId");
        p.globalCollectionId = str(o, "global_collection_id", "globalCollectionId",
                "globalid", "globalId");
        p.id = firstNonEmpty(str(o, "id", "specialid"), p.globalCollectionId, p.listId);
        p.name = str(o, "name", "listname", "list_name", "title", "specialname");
        p.coverUrl = normalizeImage(str(o, "cover", "coverUrl", "img", "image", "pic",
                "list_pic", "sizable_cover"));
        p.songCount = num(o, "song_count", "songCount", "count", "file_count",
                "total", "total_count");
        p.ownerName = str(o, "owner_name", "ownerName", "username", "nickname",
                "user_name");
        return p;
    }

    static List<SongInfo> parseSongList(JsonObject resp) {
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

    private static JsonArray findSongArray(JsonObject resp) {
        JsonObject data = object(resp, "data");
        JsonArray arr = firstSongArray(data);
        if (arr != null) return arr;
        return firstSongArray(resp);
    }

    private static JsonArray firstSongArray(JsonObject o) {
        if (o == null) return null;
        JsonArray info = array(o, "info");
        if (info != null) return info;
        JsonArray lists = array(o, "lists");
        if (lists != null) return lists;
        JsonArray list = array(o, "list");
        if (list != null) return list;
        JsonArray songs = array(o, "songs");
        if (songs != null) return songs;
        return array(o, "files");
    }

    private static SongInfo parseSong(JsonObject o) {
        SongInfo s = new SongInfo();
        s.hash = upper(str(o, "hash", "Hash", "file_hash"));
        s.albumId = str(o, "album_id", "albumid", "AlbumID", "album_audio_id");
        s.fileId = str(o, "fileid", "file_id", "FileID", "id");
        s.mixSongId = str(o, "mixsongid", "mix_song_id", "MixSongID", "album_audio_id");
        s.title = str(o, "songname", "songName", "audio_name", "name", "remark", "filename", "fileName");
        s.artist = str(o, "singername", "singerName", "author_name", "authorName", "singer_name");
        if (isEmpty(s.artist)) s.artist = parseAuthors(o);
        s.album = str(o, "album_name", "albumname", "remark");
        s.duration = durationSeconds(o);
        s.imgUrl = normalizeImage(str(o, "album_sizable_cover", "img", "image", "cover", "imgUrl", "album_img"));
        if (isEmpty(s.imgUrl)) s.imgUrl = normalizeImage(nestedStr(o, "trans_param", "union_cover"));
        s.playCount = num(o, "play_count", "playcount", "pc", "count");
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

    private static String firstNonEmpty(String... values) {
        if (values == null) return "";
        for (String value : values) {
            if (!isEmpty(value)) return value;
        }
        return "";
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
                if (e != null && !e.isJsonNull()) {
                    if (e.isJsonPrimitive()) {
                        String raw = e.getAsString();
                        if (raw != null && raw.contains(".")) {
                            return (int) Math.round(Double.parseDouble(raw));
                        }
                    }
                    return e.getAsInt();
                }
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static int durationSeconds(JsonObject o) {
        int ms = num(o, "timelen", "time_len", "time_length", "duration_ms", "durationMillis",
                "duration_millis", "millis", "milliseconds");
        if (ms > 0) return normalizeDuration(ms, true);

        int value = num(o, "duration", "timeLength", "timelength", "time", "duration_sec",
                "durationSeconds", "seconds");
        if (value > 0) return normalizeDuration(value, false);

        return parseClockDuration(str(o, "duration", "timeLength", "timelength", "time"));
    }

    private static int normalizeDuration(int value, boolean likelyMillis) {
        if (value <= 0) return 0;
        if (likelyMillis || value > 1000) {
            return Math.max(1, Math.round(value / 1000f));
        }
        return value;
    }

    private static int parseClockDuration(String value) {
        if (isEmpty(value) || !value.contains(":")) return 0;
        String[] parts = value.split(":");
        if (parts.length < 2 || parts.length > 3) return 0;
        try {
            int seconds = 0;
            for (String part : parts) {
                seconds = seconds * 60 + Integer.parseInt(part.trim());
            }
            return Math.max(0, seconds);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        if (isEmpty(value)) return 0L;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return 0L;
        }
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
