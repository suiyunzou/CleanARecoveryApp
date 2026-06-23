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
 * 抖音视频提取器（对应 yt-dlp DouyinIE）。
 *
 * <p>提取流程参照 yt-dlp {@code tiktok.py} 的 {@code DouyinIE._real_extract}：</p>
 * <ol>
 *   <li>从 URL 提取 video_id</li>
 *   <li>调用 {@code https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=xxx} 获取视频详情</li>
 *   <li>从 {@code video.play_addr.url_list} 提取直链</li>
 * </ol>
 *
 * <p>支持的 URL 格式：</p>
 * <ul>
 *   <li>{@code https://www.douyin.com/video/6961737553342991651}</li>
 *   <li>{@code https://www.iesdouyin.com/share/video/6961737553342991651}</li>
 *   <li>{@code https://v.douyin.com/xxxxxxx/}（短链，自动跟随重定向）</li>
 * </ul>
 *
 * <p>注意：抖音 API 需要有效的 cookie（s_v_web_id 等），未携带 cookie 时
 * 可能返回空数据。本实现会尝试用浏览器 UA 请求，部分视频可成功提取。</p>
 *
 * @see <a href="https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/tiktok.py">DouyinIE</a>
 */
public class DouyinExtractor implements Extractor {

    private static final String NAME = "douyin";

    private static final Pattern VALID_URL = Pattern.compile(
            "https?://(?:www\\.)?(?:douyin|iesdouyin)\\.com/(?:video/|share/video/)(?<id>\\d+)" +
            "|https?://v\\.douyin\\.com/(?<shortid>[a-zA-Z0-9]+)/?");

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
        String videoId = m.group("id");
        if (videoId == null) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "无法提取视频 ID");
        }

        // 3. 调用详情 API
        Map<String, String> headers = ExtractorHttp.defaultHeaders();
        headers.put("Referer", "https://www.douyin.com/");
        headers.put("Cookie", "msToken=; ttwid=1|");

        String apiUrl = "https://www.douyin.com/aweme/v1/web/aweme/detail/?aweme_id=" + videoId
                + "&aid=6383&cookie_enabled=true&platform=PC&device_platform=webapp";
        String json = ExtractorHttp.downloadJson(apiUrl, headers);
        JSONObject resp;
        try {
            resp = new JSONObject(json);
        } catch (org.json.JSONException e) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "抖音 API 响应解析失败: " + e.getMessage());
        }

        // 检查状态码
        int statusCode = resp.optInt("status_code", -1);
        if (statusCode != 0 && statusCode != 6028) {
            String msg = resp.optString("status_msg", "未知错误");
            if (statusCode == 10216 || statusCode == 10222) {
                throw new ExtractorException(ExtractorException.Kind.LOGIN_REQUIRED, "私密内容，需登录查看");
            }
            if (statusCode == 10204) {
                throw new ExtractorException(ExtractorException.Kind.GEO_RESTRICTED, "IP 被限制访问");
            }
        }

        JSONObject detail = resp.optJSONObject("aweme_detail");
        if (detail == null) {
            throw new ExtractorException(ExtractorException.Kind.LOGIN_REQUIRED,
                    "需要有效的 cookie 才能获取抖音视频，请稍后重试或使用其他平台");
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
            addFormatsFromAddr(playAddr, formats, "play_addr", "无水印", video);
        }

        // download_addr（可能有水印）
        JSONObject downloadAddr = video.optJSONObject("download_addr");
        if (downloadAddr != null) {
            boolean hasWatermark = video.optInt("has_watermark", 0) == 1;
            addFormatsFromAddr(downloadAddr, formats, "download_addr",
                    hasWatermark ? "有水印" : "下载", video);
        }

        // bit_rate（多码率版本）
        JSONArray bitRates = video.optJSONArray("bit_rate");
        if (bitRates != null) {
            for (int i = 0; i < bitRates.length(); i++) {
                JSONObject br = bitRates.optJSONObject(i);
                if (br == null) continue;
                JSONObject brPlayAddr = br.optJSONObject("play_addr");
                if (brPlayAddr != null) {
                    int bitrate = br.optInt("bit_rate", 0);
                    String gear = br.optString("gear_name", "gear_" + i);
                    addFormatsFromAddr(brPlayAddr, formats, gear,
                            "码率 " + (bitrate / 1000) + "kbps", video);
                }
            }
        }

        if (formats.isEmpty()) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "未找到可下载的视频格式");
        }

        // 5. 音频（music 字段）
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

    /** 从 addr 对象提取格式（对应 yt-dlp extract_addr）。 */
    private void addFormatsFromAddr(JSONObject addr, List<ExtractorResult.Format> formats,
                                     String formatId, String note, JSONObject video) {
        JSONArray urlList = addr.optJSONArray("url_list");
        if (urlList == null || urlList.length() == 0) return;

        int width = addr.optInt("width", video.optInt("width", 0));
        int height = addr.optInt("height", video.optInt("height", 0));
        long dataSize = addr.optLong("data_size", 0);

        // 取第一个 URL（通常 url_list 中多个 URL 是同一视频的不同 CDN）
        String url = urlList.optString(0);
        if (url == null || url.isEmpty()) return;

        // 抖音 URL 可能需要替换为无水印版本
        url = url.replace("playwm", "play");

        formats.add(new ExtractorResult.Format(
                url, "mp4", height, "h264", "aac",
                0, width, height, dataSize,
                note + " " + height + "p"));
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
