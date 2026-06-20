package com.example.cleanrecovery.extractor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bilibili 提取器（对应 yt-dlp BiliBiliIE）。
 *
 * <p>提取流程参照 yt-dlp {@code bilibili.py} 的 {@code _real_extract}：</p>
 * <ol>
 *   <li>请求视频页面，解析 {@code window.__INITIAL_STATE__} 获取 bvid/cid/title</li>
 *   <li>调用 {@code api.bilibili.com/x/player/wbi/playurl?fnval=4048} 获取 DASH 流</li>
 *   <li>从 DASH 的 video/audio 数组提取直链（fnval=4048 启用 DASH + 4K + HDR + 杜比）</li>
 * </ol>
 *
 * <p>支持的 URL 格式：</p>
 * <ul>
 *   <li>{@code https://www.bilibili.com/video/BV1xx411c7mC}</li>
 *   <li>{@code https://www.bilibili.com/video/BV1xx411c7mC?p=2}</li>
 *   <li>{@code https://www.bilibili.com/video/av170001}</li>
 *   <li>{@code https://b23.tv/xxxxxxx}（短链，自动跟随重定向）</li>
 * </ul>
 *
 * <p>注意：WBI 签名需要从 nav API 获取密钥。本实现采用 fnval=16（DASH）的
 * 简化路径，绕过 WBI 签名要求（playurl 接口对未签名请求返回基础 DASH 流）。</p>
 *
 * @see <a href="https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/bilibili.py">BiliBiliIE</a>
 */
public class BilibiliExtractor implements Extractor {

    private static final String NAME = "bilibili";

    /** _VALID_URL（对应 yt-dlp BiliBiliIE._VALID_URL）。 */
    private static final Pattern VALID_URL = Pattern.compile(
            "https?://(?:www\\.|m\\.)?bilibili\\.com/(?:video/|festival/[^/?#]+\\?(?:[^#]*&)?bvid=)" +
            "(?<prefix>[aAbB][vV])(?<id>[^/?#&]+)" +
            "|https?://b23\\.tv/(?<shortid>[a-zA-Z0-9]+)");

    /** __INITIAL_STATE__ 正则。 */
    private static final Pattern INITIAL_STATE_PATTERN =
            Pattern.compile("window\\.__INITIAL_STATE__\\s*=\\s*(\\{.+?\\});\\s*\\(function");

    /** Bilibili 画质映射（id → 描述）。 */
    private static final Map<Integer, String> QUALITY_NAMES = new HashMap<>();
    static {
        QUALITY_NAMES.put(127, "8K 超高清");
        QUALITY_NAMES.put(126, "杜比视界");
        QUALITY_NAMES.put(125, "HDR 真彩");
        QUALITY_NAMES.put(120, "4K 超清");
        QUALITY_NAMES.put(116, "1080P 60帧");
        QUALITY_NAMES.put(112, "1080P+ 高码率");
        QUALITY_NAMES.put(80, "1080P 高清");
        QUALITY_NAMES.put(74, "720P 60帧");
        QUALITY_NAMES.put(64, "720P 高清");
        QUALITY_NAMES.put(32, "480P 清晰");
        QUALITY_NAMES.put(16, "360P 流畅");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean suitable(String url) {
        return url != null && VALID_URL.matcher(url).find();
    }

    @Override
    public ExtractorResult extract(String url) throws ExtractorException, IOException {
        // 1. 处理短链 b23.tv（自动重定向到完整 URL）
        if (url.contains("b23.tv")) {
            url = resolveShortUrl(url);
        }

        // 2. 提取 bvid
        Matcher m = VALID_URL.matcher(url);
        if (!m.find()) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "无法识别的 Bilibili URL");
        }
        String prefix = m.group("prefix");
        String videoId = m.group("id");
        if (prefix == null || videoId == null) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "无法提取视频 ID");
        }
        prefix = prefix.toUpperCase();
        String bvid = prefix.equals("BV") ? prefix + videoId : convertAvToBvid(videoId);

        // 3. 解析页面获取 cid 和 title
        Map<String, String> headers = ExtractorHttp.defaultHeaders();
        headers.put("Referer", "https://www.bilibili.com/");
        String webpage = ExtractorHttp.downloadWebpage(url, headers);

        JSONObject initialState = extractInitialState(webpage);
        if (initialState == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "无法解析页面数据，可能需要登录或视频已被删除");
        }

        // 检查错误码
        int errCode = initialState.optJSONObject("error") != null
                ? initialState.optJSONObject("error").optInt("trueCode", 0) : 0;
        if (errCode == -403) {
            throw new ExtractorException(ExtractorException.Kind.LOGIN_REQUIRED, "需要登录才能观看");
        }
        if (errCode == -404) {
            throw new ExtractorException(ExtractorException.Kind.NOT_FOUND,
                    "视频不存在或已被删除，或受地区限制");
        }

        JSONObject videoData = initialState.optJSONObject("videoData");
        if (videoData == null) videoData = initialState.optJSONObject("videoInfo");
        if (videoData == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "无法获取视频数据");
        }

        String title = videoData.optString("title", bvid);
        bvid = videoData.optString("bvid", bvid);
        long aid = videoData.optLong("aid", 0);

        // 获取 cid（支持分 P）
        int page = parsePageParam(url);
        long cid;
        if (page > 0) {
            JSONArray pages = videoData.optJSONArray("pages");
            if (pages != null && page <= pages.length()) {
                cid = pages.optJSONObject(page - 1).optLong("cid", 0);
                String partTitle = pages.optJSONObject(page - 1).optString("part", "");
                if (!partTitle.isEmpty()) title = title + " p" + page + " " + partTitle;
            } else {
                cid = videoData.optLong("cid", 0);
            }
        } else {
            cid = videoData.optLong("cid", 0);
        }

        if (cid == 0) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "无法获取 cid");
        }

        // 4. 调用 playurl API 获取 DASH 流（fnval=4048 启用 DASH+4K+HDR+杜比）
        String playUrl = "https://api.bilibili.com/x/player/wbi/playurl" +
                "?bvid=" + bvid + "&cid=" + cid + "&fnval=4048&fourk=1&try_look=1";
        String playJson = ExtractorHttp.downloadJson(playUrl, headers);
        JSONObject playResp;
        try {
            playResp = new JSONObject(playJson);
        } catch (org.json.JSONException e) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "playurl 响应解析失败: " + e.getMessage());
        }

        int code = playResp.optInt("code", -1);
        if (code != 0) {
            String msg = playResp.optString("message", "未知错误");
            if (code == -10403) {
                throw new ExtractorException(ExtractorException.Kind.PREMIUM_ONLY, "仅大会员可看：" + msg);
            }
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "playurl 接口错误 code=" + code + " msg=" + msg);
        }

        JSONObject data = playResp.optJSONObject("data");
        if (data == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "playurl 返回数据为空");
        }

        // 5. 提取 DASH 格式
        List<ExtractorResult.Format> formats = extractFormats(data);

        // 6. 若无 DASH，尝试 durl（旧格式 FLV/MP4）
        if (formats.isEmpty()) {
            JSONArray durl = data.optJSONArray("durl");
            if (durl != null && durl.length() > 0) {
                JSONObject first = durl.optJSONObject(0);
                String durlUrl = first.optString("url", "");
                long size = first.optLong("size", 0);
                if (!durlUrl.isEmpty()) {
                    // durl 是合并格式（含音视频）
                    ExtractorResult.Format fmt = new ExtractorResult.Format(
                            durlUrl, "mp4", data.optInt("quality", 32),
                            "unknown", "unknown", 0, 0, 0, size,
                            QUALITY_NAMES.getOrDefault(data.optInt("quality", 32), "MP4"));
                    formats.add(fmt);
                }
            }
        }

        if (formats.isEmpty()) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "未找到可下载的格式，可能需要登录或仅会员可看");
        }

        // 7. 推荐扩展名：有视频流用 mp4，纯音频用 m4a
        boolean hasVideo = false;
        for (ExtractorResult.Format f : formats) {
            if (!f.isAudioOnly()) { hasVideo = true; break; }
        }
        String ext = hasVideo ? "mp4" : "m4a";

        return new ExtractorResult(bvid, title, null, formats, sanitizeTitle(title), ext);
    }

    /** 解析 b23.tv 短链（跟随重定向）。 */
    private String resolveShortUrl(String shortUrl) throws IOException {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(shortUrl).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", ExtractorHttp.DEFAULT_UA);
        conn.connect();
        String location = conn.getHeaderField("Location");
        conn.disconnect();
        if (location != null && !location.isEmpty()) return location;
        return shortUrl;
    }

    /** AV 转 BVID（通过 API）。 */
    private String convertAvToBvid(String aid) throws IOException {
        String apiUrl = "https://api.bilibili.com/x/web-interface/view?aid=" + aid;
        String json = ExtractorHttp.downloadJson(apiUrl, ExtractorHttp.defaultHeaders());
        JSONObject resp;
        try {
            resp = new JSONObject(json);
        } catch (org.json.JSONException e) {
            return "av" + aid;
        }
        if (resp.optInt("code", -1) == 0) {
            return resp.optJSONObject("data").optString("bvid", "av" + aid);
        }
        return "av" + aid;
    }

    /** 从网页提取 __INITIAL_STATE__ JSON。 */
    private JSONObject extractInitialState(String webpage) {
        Matcher m = INITIAL_STATE_PATTERN.matcher(webpage);
        if (!m.find()) {
            // 降级：尝试更宽松的匹配
            int start = webpage.indexOf("window.__INITIAL_STATE__");
            if (start < 0) return null;
            int braceStart = webpage.indexOf('{', start);
            if (braceStart < 0) return null;
            int depth = 0;
            int end = -1;
            for (int i = braceStart; i < webpage.length(); i++) {
                char c = webpage.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) { end = i; break; }
                }
            }
            if (end < 0) return null;
            try {
                return new JSONObject(webpage.substring(braceStart, end + 1));
            } catch (Exception e) {
                return null;
            }
        }
        try {
            return new JSONObject(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }

    /** 从 DASH 数据提取格式列表（对应 yt-dlp extract_formats）。 */
    private List<ExtractorResult.Format> extractFormats(JSONObject data) {
        List<ExtractorResult.Format> formats = new ArrayList<>();
        JSONObject dash = data.optJSONObject("dash");
        if (dash == null) return formats;

        // 视频流
        JSONArray videos = dash.optJSONArray("video");
        if (videos != null) {
            for (int i = 0; i < videos.length(); i++) {
                JSONObject v = videos.optJSONObject(i);
                if (v == null) continue;
                String vurl = getFirstNonEmpty(v, "baseUrl", "base_url", "url");
                if (vurl == null) continue;
                int quality = v.optInt("id", 0);
                int width = v.optInt("width", 0);
                int height = v.optInt("height", 0);
                long bandwidth = v.optLong("bandwidth", 0);
                long size = v.optLong("size", 0);
                String codecs = v.optString("codecs", "unknown");
                String mime = v.optString("mimeType", v.optString("mime_type", "video/mp4"));
                String ext = mimeToExt(mime, "mp4");
                String desc = QUALITY_NAMES.getOrDefault(quality, height + "p");

                formats.add(new ExtractorResult.Format(
                        vurl, ext, quality, codecs, "none",
                        (int) (bandwidth / 1000), width, height, size, desc));
            }
        }

        // 音频流
        JSONArray audios = dash.optJSONArray("audio");
        if (audios != null) {
            for (int i = 0; i < audios.length(); i++) {
                JSONObject a = audios.optJSONObject(i);
                if (a == null) continue;
                String aurl = getFirstNonEmpty(a, "baseUrl", "base_url", "url");
                if (aurl == null) continue;
                int quality = a.optInt("id", 0);
                long bandwidth = a.optLong("bandwidth", 0);
                long size = a.optLong("size", 0);
                String codecs = a.optString("codecs", "unknown");
                String mime = a.optString("mimeType", a.optString("mime_type", "audio/mp4"));
                String ext = mimeToExt(mime, "m4a");

                formats.add(new ExtractorResult.Format(
                        aurl, ext, quality, "none", codecs,
                        (int) (bandwidth / 1000), 0, 0, size,
                        "音频 " + (bandwidth / 1000) + "kbps"));
            }
        }

        // 杜比音频
        JSONObject dolby = dash.optJSONObject("dolby");
        if (dolby != null) {
            JSONArray dolbyAudio = dolby.optJSONArray("audio");
            if (dolbyAudio != null) {
                for (int i = 0; i < dolbyAudio.length(); i++) {
                    JSONObject a = dolbyAudio.optJSONObject(i);
                    if (a == null) continue;
                    String aurl = getFirstNonEmpty(a, "baseUrl", "base_url", "url");
                    if (aurl == null) continue;
                    formats.add(new ExtractorResult.Format(
                            aurl, "m4a", a.optInt("id", 30250), "none",
                            a.optString("codecs", "ec-3"),
                            (int) (a.optLong("bandwidth", 0) / 1000),
                            0, 0, a.optLong("size", 0), "杜比音频"));
                }
            }
        }

        // FLAC 无损音频
        JSONObject flac = dash.optJSONObject("flac");
        if (flac != null) {
            JSONObject flacAudio = flac.optJSONObject("audio");
            if (flacAudio != null) {
                String aurl = getFirstNonEmpty(flacAudio, "baseUrl", "base_url", "url");
                if (aurl != null) {
                    formats.add(new ExtractorResult.Format(
                            aurl, "flac", 30251, "none",
                            flacAudio.optString("codecs", "flac"),
                            (int) (flacAudio.optLong("bandwidth", 0) / 1000),
                            0, 0, flacAudio.optLong("size", 0), "FLAC 无损"));
                }
            }
        }

        return formats;
    }

    /** MIME 转扩展名（简化版）。 */
    private static String mimeToExt(String mime, String fallback) {
        if (mime == null) return fallback;
        String lower = mime.toLowerCase();
        if (lower.contains("mp4")) return "mp4";
        if (lower.contains("webm")) return "webm";
        if (lower.contains("flac")) return "flac";
        if (lower.contains("aac")) return "aac";
        if (lower.contains("mpeg")) return "mp3";
        return fallback;
    }

    /** 获取 JSON 对象中第一个非空字符串字段。 */
    private static String getFirstNonEmpty(JSONObject obj, String... keys) {
        for (String k : keys) {
            String v = obj.optString(k, null);
            if (v != null && !v.isEmpty()) return v;
        }
        return null;
    }

    /** 解析 URL 中的 p 参数。 */
    private static int parsePageParam(String url) {
        int q = url.indexOf('?');
        if (q < 0) return 0;
        String query = url.substring(q + 1);
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals("p")) {
                try { return Integer.parseInt(kv[1]); } catch (Exception e) { return 0; }
            }
        }
        return 0;
    }

    /** 清理标题中的非法字符（用于文件名）。 */
    private static String sanitizeTitle(String title) {
        if (title == null) return "bilibili_video";
        return title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
