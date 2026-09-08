package com.example.cleanrecovery.extractor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 抖音视频提取器（对齐 yt-dlp DouyinIE 现行方案）。
 *
 * <p>yt-dlp 2025 起的官方实现只带 {@code aweme_id} 一个参数调用
 * {@code /aweme/v1/web/aweme/detail/}，能否成功完全取决于请求方是否持有
 * 抖音下发的<b>新鲜会话 Cookie</b>（ttwid / s_v_web_id / msToken，由页面 JS
 * 质询生成，纯 HTTP 拿不到，分享页 SSR 也不再内嵌视频数据——已实测确认）。</p>
 *
 * <p>我们的等价实现：App 内置浏览器就是"浏览器 Cookie"的来源。</p>
 * <ol>
 *   <li>直接用全局 {@link com.example.cleanrecovery.ytdlp.CookieJar} 里的
 *       Cookie 调 detail API（用户在内置浏览器刷过抖音即命中）</li>
 *   <li>没有 Cookie 时触发 {@link CookieBootstrapper} 钩子 —— UI 层用隐藏
 *       WebView 打开 douyin.com 完成 JS 质询并回填 CookieJar，然后重试一次</li>
 *   <li>解析 {@code play_addr}（playwm→play 去水印）+ {@code bit_rate} 多档位
 *       + 原声音乐</li>
 * </ol>
 *
 * <p>支持的 URL 格式：</p>
 * <ul>
 *   <li>{@code https://www.douyin.com/video/6961737553342991651}</li>
 *   <li>{@code https://www.iesdouyin.com/share/video/6961737553342991651}</li>
 *   <li>{@code https://v.douyin.com/xxxxxxx/}（短链，自动跟随重定向）</li>
 * </ul>
 *
 * @see <a href="https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/tiktok.py">DouyinIE</a>
 */
public class DouyinExtractor implements Extractor {

    private static final String NAME = "douyin";

    private static final Pattern VALID_URL = Pattern.compile(
            "https?://(?:www\\.)?(?:douyin|iesdouyin)\\.com/(?:video/|share/video/)(\\d+)" +
            "|https?://v\\.douyin\\.com/([a-zA-Z0-9]+)/?");

    /**
     * Cookie 预热钩子：由 UI 层注入。实现应同步阻塞地完成 Cookie 预热
     * （播种 ttwid + 隐藏 WebView 打开目标页执行 JS 质询），把
     * ttwid/s_v_web_id/msToken 回填进全局 CookieJar，或超时抛出。
     *
     * @param pageUrl 目标视频页（加载它比加载首页更能触发质询 JS）
     */
    public interface CookieBootstrapper {
        void bootstrap(String pageUrl) throws Exception;
    }

    private static volatile CookieBootstrapper cookieBootstrapper;

    /** 注入 Cookie 预热钩子（Activity onCreate 时调用；传 null 清除）。 */
    public static void setCookieBootstrapper(CookieBootstrapper bootstrapper) {
        cookieBootstrapper = bootstrapper;
    }

    @Override
    public String name() { return NAME; }

    @Override
    public boolean suitable(String url) {
        return url != null && VALID_URL.matcher(url).find();
    }

    @Override
    public ExtractorResult extract(String url) throws ExtractorException, IOException {
        // 1. 处理短链 v.douyin.com
        if (url.contains("v.douyin.com")) {
            url = resolveShortUrl(url);
        }

        // 2. 提取 video_id
        Matcher m = VALID_URL.matcher(url);
        if (!m.find()) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "无法识别的抖音 URL");
        }
        String videoId = m.group(1);
        if (videoId == null) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "无法提取视频 ID");
        }

        // 3. detail API（Cookie 由全局 CookieJar 注入；无 Cookie 时预热一次后重试）
        JSONObject detail = fetchDetail(videoId);
        if (detail == null && cookieBootstrapper != null) {
            try {
                cookieBootstrapper.bootstrap("https://www.douyin.com/video/" + videoId);
            } catch (Exception e) {
                throw new ExtractorException(ExtractorException.Kind.LOGIN_REQUIRED,
                        "抖音 Cookie 预热失败：" + (e.getMessage() != null ? e.getMessage() : e));
            }
            detail = fetchDetail(videoId);
        }
        if (detail == null) {
            throw new ExtractorException(ExtractorException.Kind.LOGIN_REQUIRED,
                    "缺少抖音会话 Cookie（或被风控拦截）：请先在应用内浏览器打开一次 douyin.com，再回来解析");
        }

        // 4. 提取视频信息
        String awemeId = detail.optString("aweme_id", videoId);
        String desc = detail.optString("desc", "douyin_" + videoId);
        if (desc.isEmpty()) desc = "douyin_" + videoId;

        JSONObject video = detail.optJSONObject("video");
        if (video == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "视频数据为空");
        }

        List<ExtractorResult.Format> formats = new ArrayList<>();

        // play_addr（无水印直链）
        JSONObject playAddr = video.optJSONObject("play_addr");
        if (playAddr != null) {
            addFormatsFromAddr(playAddr, formats, "无水印", video);
        }

        // bit_rate（多码率档位，yt-dlp 取所有 gear）
        JSONArray bitRates = video.optJSONArray("bit_rate");
        if (bitRates != null) {
            for (int i = 0; i < bitRates.length(); i++) {
                JSONObject br = bitRates.optJSONObject(i);
                if (br == null) continue;
                JSONObject brPlayAddr = br.optJSONObject("play_addr");
                if (brPlayAddr == null) continue;
                int bitrate = br.optInt("bit_rate", 0);
                String gear = br.optString("gear_name", "");
                if (gear.isEmpty()) gear = "档位" + (i + 1);
                addFormatsFromAddr(brPlayAddr, formats, gear + " " + (bitrate / 1000) + "kbps", video);
            }
        }

        // download_addr（带水印的原版）
        JSONObject downloadAddr = video.optJSONObject("download_addr");
        if (downloadAddr != null) {
            boolean hasWatermark = video.optInt("has_watermark", 0) == 1;
            addFormatsFromAddr(downloadAddr, formats,
                    hasWatermark ? "有水印" : "下载", video);
        }

        if (formats.isEmpty()) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "未找到可下载的视频格式");
        }

        // 5. 原声音乐
        JSONObject music = detail.optJSONObject("music");
        if (music != null) {
            JSONObject playUrl = music.optJSONObject("play_url");
            if (playUrl != null) {
                JSONArray urlList = playUrl.optJSONArray("url_list");
                if (urlList != null && urlList.length() > 0) {
                    String audioUrl = urlList.optString(0);
                    if (!audioUrl.isEmpty()) {
                        String trackTitle = music.optString("title", "audio");
                        formats.add(new ExtractorResult.Format(
                                audioUrl, "mp3", 0, "none", "mp3",
                                music.optInt("play_url_bit_rate", 0) / 1000,
                                0, 0, 0, "原声：" + trackTitle));
                    }
                }
            }
        }

        return new ExtractorResult(awemeId, desc, null, formats, sanitizeTitle(desc), "mp4");
    }

    /**
     * 调用 detail API（yt-dlp DouyinIE 同款最小参数）。
     *
     * <p>不显式设置 Cookie 头 → {@link ExtractorHttp} 自动注入全局 CookieJar。
     * 拿不到数据返回 null（由调用方决定是否预热重试），私密/地区限制抛对应异常。</p>
     */
    private JSONObject fetchDetail(String videoId) throws ExtractorException, IOException {
        Map<String, String> headers = ExtractorHttp.defaultHeaders();
        headers.put("Referer", "https://www.douyin.com/video/" + videoId);
        String apiUrl = "https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=" + videoId;
        String json = ExtractorHttp.downloadJson(apiUrl, headers);
        JSONObject resp;
        try {
            resp = new JSONObject(json);
        } catch (org.json.JSONException e) {
            // 无 Cookie 时抖音返回 HTML 质询页而非 JSON —— 与缺 Cookie 同因，
            // 返回 null 交给调用方走 Cookie 预热/重试流程
            return null;
        }

        int statusCode = resp.optInt("status_code", -1);
        if (statusCode == 10216 || statusCode == 10222) {
            throw new ExtractorException(ExtractorException.Kind.LOGIN_REQUIRED, "私密内容，需登录查看");
        }
        if (statusCode == 10204) {
            throw new ExtractorException(ExtractorException.Kind.GEO_RESTRICTED, "IP 被限制访问");
        }
        return resp.optJSONObject("aweme_detail");
    }

    /** 从 addr 对象提取格式；playwm→play 去水印（对应 yt-dlp extract_addr）。 */
    private void addFormatsFromAddr(JSONObject addr, List<ExtractorResult.Format> formats,
                                     String note, JSONObject video) {
        JSONArray urlList = addr.optJSONArray("url_list");
        if (urlList == null) urlList = new JSONArray();

        String url = null;
        for (int i = 0; i < urlList.length(); i++) {
            String u = urlList.optString(i, null);
            if (u != null && !u.isEmpty()) { url = u; break; }
        }
        if (url == null) {
            // url_list 为空时用 uri 构造播放直链（aweme/v1/play 无需签名）
            String uri = addr.optString("uri", null);
            if (uri != null && !uri.isEmpty() && uri.matches("\\d+")) {
                url = "https://www.douyin.com/aweme/v1/play/?video_id=" + uri + "&ratio=1080p&line=0";
            }
        }
        if (url == null) return;

        // 抖音直链 playwm 为带水印端点，替换为 play 即无水印
        url = url.replace("playwm", "play");

        int width = addr.optInt("width", video.optInt("width", 0));
        int height = addr.optInt("height", video.optInt("height", 0));
        long dataSize = addr.optLong("data_size", 0);

        formats.add(new ExtractorResult.Format(
                url, "mp4", height, "h264", "aac",
                0, width, height, dataSize,
                note + " " + (height > 0 ? height + "p" : "")));
    }

    /** 解析 v.douyin.com 短链。 */
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

    private static String sanitizeTitle(String title) {
        if (title == null) return "douyin_video";
        return title.replaceAll("[\\\\/:*?\"<>|\n\r]", "_").trim();
    }
}
