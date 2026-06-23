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
 * TikTok 国际版提取器（对应 yt-dlp TikTokIE + TikTokVMIE）。
 *
 * <p>提取流程参照 yt-dlp {@code tiktok.py}：</p>
 * <ol>
 *   <li>短链（vm.tiktok.com / vt.tiktok.com）跟随重定向到完整 URL</li>
 *   <li>从 URL 提取 video_id</li>
 *   <li>请求视频页面，解析嵌入的 SIGI_STATE / __UNIVERSAL_DATA_FOR_REHYDRATION__ JSON</li>
 *   <li>从 {@code video.playAddr} / {@code video.downloadAddr} 提取直链</li>
 * </ol>
 *
 * <p>支持的 URL 格式：</p>
 * <ul>
 *   <li>{@code https://www.tiktok.com/@username/video/1234567890}</li>
 *   <li>{@code https://vm.tiktok.com/ZMxxxxxxx/}（短链）</li>
 *   <li>{@code https://vt.tiktok.com/ZSxxxxxxx/}（短链）</li>
 * </ul>
 *
 * @see <a href="https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/tiktok.py">TikTokIE</a>
 */
public class TikTokExtractor implements Extractor {

    private static final String NAME = "tiktok";

    private static final Pattern VALID_URL = Pattern.compile(
            "https?://(?:www\\.)?tiktok\\.com/(?:@[^/]+/video/|t/)(?<id>\\w+)" +
            "|https?://(?:vm|vt)\\.tiktok\\.com/(?<shortid>\\w+)/?");

    /** 嵌入式 JSON 正则（SIGI_STATE 或 UNIVERSAL_DATA）。 */
    private static final Pattern SIGI_PATTERN =
            Pattern.compile("window\\[(?:'SIGI_STATE'|\"SIGI_STATE\")\\]\\s*=\\s*(\\{.+?\\})\\s*;?</script>",
                    Pattern.DOTALL);
    private static final Pattern UNIVERSAL_PATTERN =
            Pattern.compile("<script[^>]*id=\"__UNIVERSAL_DATA_FOR_REHYDRATION__\"[^>]*>(\\{.+?\\})</script>",
                    Pattern.DOTALL);

    @Override
    public String name() { return NAME; }

    @Override
    public boolean suitable(String url) {
        return url != null && VALID_URL.matcher(url).find();
    }

    @Override
    public ExtractorResult extract(String url) throws ExtractorException, IOException {
        // 1. 处理短链
        if (url.contains("vm.tiktok.com") || url.contains("vt.tiktok.com")
                || url.contains("tiktok.com/t/")) {
            url = resolveShortUrl(url);
        }

        // 2. 提取 video_id
        Matcher m = VALID_URL.matcher(url);
        if (!m.find()) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "无法识别的 TikTok URL");
        }
        String videoId = m.group("id");
        if (videoId == null) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "无法提取视频 ID");
        }

        // 3. 请求视频页面
        Map<String, String> headers = ExtractorHttp.defaultHeaders();
        headers.put("Referer", "https://www.tiktok.com/");
        String webpage = ExtractorHttp.downloadWebpage(url, headers);

        // 4. 解析嵌入 JSON
        JSONObject root = extractEmbeddedJson(webpage);
        if (root == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "无法解析页面数据，TikTok 可能需要登录或页面结构已变更");
        }

        // 5. 定位 ItemModule 中的视频详情
        JSONObject itemModule = root.optJSONObject("ItemModule");
        if (itemModule == null) {
            // 尝试 universal data 路径
            itemModule = navigateUniversal(root);
        }
        if (itemModule == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "未找到 ItemModule");
        }

        JSONObject detail = itemModule.optJSONObject(videoId);
        if (detail == null) {
            // 取第一个
            java.util.Iterator<String> keys = itemModule.keys();
            if (keys.hasNext()) {
                detail = itemModule.optJSONObject(keys.next());
            }
        }
        if (detail == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "视频详情为空");
        }

        String desc = detail.optString("desc", "tiktok_" + videoId);
        if (desc.isEmpty()) desc = "tiktok_" + videoId;

        // 6. 提取视频格式
        JSONObject video = detail.optJSONObject("video");
        if (video == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "视频数据为空");
        }

        List<ExtractorResult.Format> formats = new ArrayList<>();

        // playAddr
        JSONObject playAddr = video.optJSONObject("playAddr");
        if (playAddr != null) {
            addFormatsFromAddr(playAddr, formats, "play", "无水印", video);
        }

        // downloadAddr
        JSONObject downloadAddr = video.optJSONObject("downloadAddr");
        if (downloadAddr != null) {
            addFormatsFromAddr(downloadAddr, formats, "download", "下载", video);
        }

        // bitrateVersions
        JSONArray bitRates = video.optJSONArray("bitrateVersions");
        if (bitRates != null) {
            for (int i = 0; i < bitRates.length(); i++) {
                JSONObject br = bitRates.optJSONObject(i);
                if (br == null) continue;
                String brUrl = br.optString("playAddr", "");
                if (!brUrl.isEmpty()) {
                    formats.add(new ExtractorResult.Format(
                            brUrl, "mp4", br.optInt("qualityType", 0),
                            "h264", "aac", br.optInt("bitrate", 0) / 1000,
                            0, br.optInt("height", 0), 0,
                            "码率 " + (br.optInt("bitrate", 0) / 1000) + "kbps"));
                }
            }
        }

        if (formats.isEmpty()) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "未找到可下载的视频格式");
        }

        // 7. 音频
        JSONObject music = detail.optJSONObject("music");
        if (music != null) {
            String playUrl = music.optString("playUrl", "");
            if (!playUrl.isEmpty()) {
                String title = music.optString("title", "audio");
                formats.add(new ExtractorResult.Format(
                        playUrl, "mp3", 0, "none", "mp3",
                        0, 0, 0, 0, "原声：" + title));
            }
        }

        return new ExtractorResult(videoId, desc, null, formats, sanitizeTitle(desc), "mp4");
    }

    /** 从嵌入脚本提取 JSON。 */
    private JSONObject extractEmbeddedJson(String webpage) {
        // 先尝试 UNIVERSAL_DATA（新版）
        Matcher m = UNIVERSAL_PATTERN.matcher(webpage);
        if (m.find()) {
            try {
                return new JSONObject(extractBalancedJson(m.group(1)));
            } catch (Exception ignored) {}
        }
        // 再尝试 SIGI_STATE（旧版）
        m = SIGI_PATTERN.matcher(webpage);
        if (m.find()) {
            try {
                return new JSONObject(extractBalancedJson(m.group(1)));
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** 从 universal data 路径导航到 ItemModule。 */
    private JSONObject navigateUniversal(JSONObject root) {
        JSONObject defaultScope = root.optJSONObject("__default_scope__");
        if (defaultScope == null) return null;
        return defaultScope.optJSONObject("itemModule");
    }

    /** 从 addr 对象提取格式。 */
    private void addFormatsFromAddr(JSONObject addr, List<ExtractorResult.Format> formats,
                                     String formatId, String note, JSONObject video) {
        JSONArray urlList = addr.optJSONArray("urlList");
        if (urlList == null || urlList.length() == 0) return;

        int width = addr.optInt("width", video.optInt("width", 0));
        int height = addr.optInt("height", video.optInt("height", 0));

        String url = urlList.optString(0);
        if (url == null || url.isEmpty()) return;

        formats.add(new ExtractorResult.Format(
                url, "mp4", height, "h264", "aac",
                0, width, height, 0, note + " " + height + "p"));
    }

    /** 提取平衡的 JSON 字符串（处理正则匹配不完整的情况）。 */
    private String extractBalancedJson(String text) {
        int start = text.indexOf('{');
        if (start < 0) return text;
        int depth = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return text.substring(start, i + 1);
            }
        }
        return text.substring(start);
    }

    /** 解析短链。 */
    private String resolveShortUrl(String shortUrl) throws IOException {
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL(shortUrl).openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent",
                "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)");
        conn.connect();
        String location = conn.getHeaderField("Location");
        conn.disconnect();
        if (location != null && !location.isEmpty()) return location;
        return shortUrl;
    }

    private static String sanitizeTitle(String title) {
        if (title == null) return "tiktok_video";
        return title.replaceAll("[\\\\/:*?\"<>|\n\r]", "_").trim();
    }
}
