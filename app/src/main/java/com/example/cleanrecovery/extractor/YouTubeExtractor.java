package com.example.cleanrecovery.extractor;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube 视频提取器（对应 yt-dlp youtube.py → YoutubeIE）。
 *
 * <p>实现基于 yt-dlp-master/yt_dlp/extractor/youtube/_base.py 与 _video.py 的
 * 完整提取逻辑，使用 InnerTube player API 获取直链。</p>
 *
 * <p><b>核心策略（参照 yt-dlp _DEFAULT_CLIENTS）：</b></p>
 * <ol>
 *   <li><b>android_vr 客户端</b>（首选）：REQUIRE_JS_PLAYER=False，
 *       不需要 PO Token，返回的格式带直接可下载 URL（无 signatureCipher）。
 *       对应 yt-dlp INNERTUBE_CLIENTS['android_vr']。</li>
 *   <li><b>android 客户端</b>（备选）：REQUIRE_JS_PLAYER=False，
 *       可能需要 PO Token 但部分视频可用。对应 INNERTUBE_CLIENTS['android']。</li>
 *   <li><b>web 客户端</b>（回退）：从 watch 页面提取 ytInitialPlayerResponse，
 *       可能触发反爬。对应 _WEBPAGE_CLIENTS。</li>
 * </ol>
 *
 * <p>支持 URL 格式（对应 yt-dlp _VALID_URL）：</p>
 * <ul>
 *   <li>{@code https://www.youtube.com/watch?v=VIDEO_ID}</li>
 *   <li>{@code https://youtu.be/VIDEO_ID}</li>
 *   <li>{@code https://www.youtube.com/embed/VIDEO_ID}</li>
 *   <li>{@code https://www.youtube.com/shorts/VIDEO_ID}</li>
 *   <li>{@code https://www.youtube.com/live/VIDEO_ID}</li>
 *   <li>{@code https://m.youtube.com/watch?v=VIDEO_ID}</li>
 *   <li>{@code https://www.youtube.com/v/VIDEO_ID}</li>
 *   <li>{@code https://www.youtube.com/e/VIDEO_ID}</li>
 * </ul>
 *
 * <p>限制：</p>
 * <ul>
 *   <li>不绕过年龄限制（需登录的内容会返回 LOGIN_REQUIRED）</li>
 *   <li>不实现 JS 签名解密（仅使用可直接下载的格式）</li>
 *   <li>会员内容返回 PREMIUM_ONLY</li>
 *   <li>需网络可达 YouTube 服务器</li>
 * </ul>
 *
 * @see <a href="https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py">yt-dlp youtube/_base.py</a>
 * @see <a href="https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_video.py">yt-dlp youtube/_video.py</a>
 */
public class YouTubeExtractor implements Extractor {

    private static final String TAG = "YouTubeExtractor";
    private static final String NAME = "youtube";

    /**
     * YouTube 视频 ID 正则（11 位字母数字_-）。
     * 支持 watch?v=、youtu.be/、embed/、shorts/、live/、v/、e/ 多种 URL 形式。
     * 对应 yt-dlp YoutubeIE._VALID_URL 中的视频 ID 捕获组。
     */
    private static final Pattern VALID_URL = Pattern.compile(
            // youtu.be/VIDEO_ID
            "https?://(?:www\\.|m\\.)?youtu\\.be/(?<id>[A-Za-z0-9_-]{11})"
            // youtube.com/watch?v=VIDEO_ID（v 参数可能在任意位置）
            + "|https?://(?:www\\.|m\\.)?youtube\\.com/(?:watch|movie)(?:_popup)?(?:\\.php)?/?\\?(?:[^#]*&)?v=(?<id2>[A-Za-z0-9_-]{11})"
            // youtube.com/embed/VIDEO_ID
            + "|https?://(?:www\\.|m\\.)?youtube\\.com/embed/(?<id3>[A-Za-z0-9_-]{11})"
            // youtube.com/shorts/VIDEO_ID
            + "|https?://(?:www\\.|m\\.)?youtube\\.com/shorts/(?<id4>[A-Za-z0-9_-]{11})"
            // youtube.com/live/VIDEO_ID
            + "|https?://(?:www\\.|m\\.)?youtube\\.com/live/(?<id5>[A-Za-z0-9_-]{11})"
            // youtube.com/v/VIDEO_ID
            + "|https?://(?:www\\.|m\\.)?youtube\\.com/v/(?<id6>[A-Za-z0-9_-]{11})"
            // youtube.com/e/VIDEO_ID
            + "|https?://(?:www\\.|m\\.)?youtube\\.com/e/(?<id7>[A-Za-z0-9_-]{11})");

    /**
     * ytInitialPlayerResponse 正则（对应 yt-dlp _YT_INITIAL_PLAYER_RESPONSE_RE）。
     *
     * <p>yt-dlp 使用 _search_json 进行更灵活的 JSON 提取，这里用宽松正则
     * 捕获 {@code ytInitialPlayerResponse = } 之后的 JSON 对象。
     * 使用非贪婪匹配到第一个 {@code ;</script> 或 var meta} 边界。</p>
     */
    private static final Pattern PLAYER_RESPONSE_PATTERN = Pattern.compile(
            "ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\})\\s*;",
            Pattern.DOTALL);

    /**
     * 备用：从 ytcfg 提取 player_response（对应 yt-dlp extract_ytcfg）。
     * 格式: "playerResponse":"{...}"
     */
    private static final Pattern YTCFG_PLAYER_RESPONSE_PATTERN = Pattern.compile(
            "\"playerResponse\"\\s*:\\s*(\"\\{.+?\\}\")",
            Pattern.DOTALL);

    // ===== InnerTube 客户端配置（对应 yt-dlp INNERTUBE_CLIENTS）=====

    /**
     * android_vr 客户端配置（对应 yt-dlp INNERTUBE_CLIENTS['android_vr']）。
     *
     * <p>优势：</p>
     * <ul>
     *   <li>REQUIRE_JS_PLAYER=False：返回的格式无需签名解密</li>
     *   <li>不需要 PO Token</li>
     *   <li>"Made for kids" 视频也可用</li>
     * </ul>
     */
    private static final class AndroidVrClient {
        static final String NAME = "ANDROID_VR";
        static final String VERSION = "1.65.10";
        static final String USER_AGENT =
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; " +
                "eureka-user Build/SQ3A.220605.009.A1) gzip";
        static final String DEVICE_MAKE = "Oculus";
        static final String DEVICE_MODEL = "Quest 3";
        static final int ANDROID_SDK = 32;
        static final String OS_NAME = "Android";
        static final String OS_VERSION = "12L";
    }

    /**
     * android 客户端配置（对应 yt-dlp INNERTUBE_CLIENTS['android']）。
     *
     * <p>REQUIRE_JS_PLAYER=False，但可能需要 PO Token。
     * 作为 android_vr 失败时的备选。</p>
     */
    private static final class AndroidClient {
        static final String NAME = "ANDROID";
        static final String VERSION = "21.02.35";
        static final String USER_AGENT =
                "com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip";
        static final int ANDROID_SDK = 30;
        static final String OS_NAME = "Android";
        static final String OS_VERSION = "11";
    }

    /**
     * tv 客户端配置（对应 yt-dlp INNERTUBE_CLIENTS['tv']）。
     *
     * <p>TVHTML5 客户端，用于 Smart TV。优势：</p>
     * <ul>
     *   <li>无 GVS_PO_TOKEN_POLICY：不需要 PO Token（HTTPS/DASH/HLS 均不需要）</li>
     *   <li>无 REQUIRE_JS_PLAYER：不需要签名解密</li>
     *   <li>返回 HLS manifest URL，可被 HlsDownloader 直接处理</li>
     *   <li>Smart TV 客户端可能不触发反机器人检测</li>
     * </ul>
     */
    private static final class TvClient {
        static final String NAME = "TVHTML5";
        static final String VERSION = "7.20260114.12.00";
        static final String USER_AGENT =
                "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold " +
                "(unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)";
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean suitable(String url) {
        if (url == null) return false;
        return VALID_URL.matcher(url).find();
    }

    @Override
    public ExtractorResult extract(String url) throws ExtractorException, IOException {
        if (url == null || url.trim().isEmpty()) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "URL 为空");
        }

        Log.d(TAG, "extract: " + url);

        // 1. 提取 video_id（对应 yt-dlp _extract_id）
        Matcher m = VALID_URL.matcher(url);
        if (!m.find()) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "无法识别的 YouTube URL");
        }
        String videoId = extractGroupId(m);
        if (videoId == null) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "无法提取视频 ID");
        }
        Log.d(TAG, "video_id: " + videoId);

        // 2. 预获取 YouTube cookies（对应 yt-dlp _get_cookies / _download_webpage 获取会话）
        // 纯 API 调用无 cookies 会被 YouTube 判定为机器人，触发 LOGIN_REQUIRED
        String youtubeCookies = fetchYoutubeCookies();
        if (youtubeCookies != null && !youtubeCookies.isEmpty()) {
            Log.i(TAG, "获取到 YouTube cookies: " + youtubeCookies.substring(0,
                    Math.min(60, youtubeCookies.length())) + "...");
        } else {
            Log.w(TAG, "未能获取 YouTube cookies，可能触发反机器人检测");
        }

        // 3. 按优先级尝试多个客户端（对应 yt-dlp _extract_player_responses 的多客户端策略）
        ExtractorResult result = null;
        ExtractorException lastError = null;

        // 3a. 首选：android_vr 客户端（REQUIRE_JS_PLAYER=False，无需 PO Token）
        try {
            result = extractViaInnertube(videoId, AndroidVrClient.NAME, AndroidVrClient.VERSION,
                    AndroidVrClient.USER_AGENT, AndroidVrClient.DEVICE_MAKE, AndroidVrClient.DEVICE_MODEL,
                    AndroidVrClient.ANDROID_SDK, AndroidVrClient.OS_NAME, AndroidVrClient.OS_VERSION,
                    youtubeCookies);
            Log.i(TAG, "android_vr 客户端提取成功");
        } catch (ExtractorException e) {
            lastError = e;
            Log.w(TAG, "android_vr 客户端失败: " + e.getKind() + " " + e.getMessage());
        } catch (IOException e) {
            Log.w(TAG, "android_vr 客户端网络错误: " + e.getMessage());
        }

        // 3b. 备选：android 客户端
        if (result == null) {
            try {
                result = extractViaInnertube(videoId, AndroidClient.NAME, AndroidClient.VERSION,
                        AndroidClient.USER_AGENT, null, null,
                        AndroidClient.ANDROID_SDK, AndroidClient.OS_NAME, AndroidClient.OS_VERSION,
                        youtubeCookies);
                Log.i(TAG, "android 客户端提取成功");
            } catch (ExtractorException e) {
                if (lastError == null) lastError = e;
                Log.w(TAG, "android 客户端失败: " + e.getKind() + " " + e.getMessage());
            } catch (IOException e) {
                Log.w(TAG, "android 客户端网络错误: " + e.getMessage());
            }
        }

        // 3c. 备选：tv 客户端（TVHTML5，无 PO Token 要求，返回 HLS manifest）
        if (result == null) {
            try {
                result = extractViaInnertube(videoId, TvClient.NAME, TvClient.VERSION,
                        TvClient.USER_AGENT, null, null, 0, null, null, youtubeCookies);
                Log.i(TAG, "tv 客户端提取成功");
            } catch (ExtractorException e) {
                if (lastError == null) lastError = e;
                Log.w(TAG, "tv 客户端失败: " + e.getKind() + " " + e.getMessage());
            } catch (IOException e) {
                Log.w(TAG, "tv 客户端网络错误: " + e.getMessage());
            }
        }

        // 2d. 回退：watch 页面抓取（对应 yt-dlp _WEBPAGE_CLIENTS）
        if (result == null) {
            try {
                result = extractViaWatchPage(videoId, youtubeCookies);
                Log.i(TAG, "Watch 页面提取成功");
            } catch (ExtractorException e) {
                if (lastError == null) lastError = e;
                Log.w(TAG, "Watch 页面提取失败: " + e.getKind() + " " + e.getMessage());
            } catch (IOException e) {
                Log.w(TAG, "Watch 页面网络错误: " + e.getMessage());
            }
        }

        if (result == null) {
            if (lastError != null) {
                throw lastError;
            }
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "所有提取方法均失败（android_vr、android、watch 页面）");
        }
        return result;
    }

    /**
     * 预获取 YouTube cookies（对应 yt-dlp _get_cookies）。
     *
     * <p>访问 YouTube 首页，收集 Set-Cookie 响应头中的会话 cookies
     * （PREF、VISITOR_INFO1_LIVE、GPS 等）。这些 cookies 用于后续 InnerTube
     * API 调用，使请求看起来像真实客户端，绕过反机器人检测。</p>
     *
     * @return 合并后的 cookie 字符串（如 "PREF=xxx; VISITOR_INFO1_LIVE=yyy"），失败返回 null
     */
    private String fetchYoutubeCookies() {
        Log.d(TAG, "fetchYoutubeCookies: START");
        try {
            Map<String, String> headers = ExtractorHttp.defaultHeaders();
            headers.put("User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; SQ3A.220605.009) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
            headers.put("Accept-Language", "en-US,en;q=0.9");
            headers.put("Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            // 预设 consent cookie（对应 yt-dlp 中绕过 EU consent 页面的做法）
            headers.put("Cookie", "CONSENT=YES+; PREF=f2=8000000");

            ExtractorHttp.CookieResponse resp = ExtractorHttp.downloadWebpageWithCookies(
                    "https://www.youtube.com/?gl=US&hl=en", headers);
            Log.d(TAG, "fetchYoutubeCookies: bodyLen=" + resp.body.length()
                    + " cookieLen=" + resp.cookies.length());

            // 合并预设 cookies 和服务器返回的 cookies
            StringBuilder merged = new StringBuilder("CONSENT=YES+; PREF=f2=8000000");
            if (resp.cookies != null && !resp.cookies.isEmpty()) {
                merged.append("; ").append(resp.cookies);
            }
            return merged.toString();
        } catch (IOException e) {
            Log.w(TAG, "fetchYoutubeCookies FAIL: " + e.getMessage());
            return "CONSENT=YES+; PREF=f2=8000000";
        }
    }

    /**
     * 通过 InnerTube player API 提取（对应 yt-dlp _extract_player_response）。
     *
     * <p>调用 {@code /youtubei/v1/player} 端点，使用指定的客户端身份。
     * 移动客户端（android/android_vr）不需要 API Key，也不需要 JS 签名解密。</p>
     *
     * @param videoId       YouTube 视频 ID
     * @param clientName    客户端名称（如 ANDROID_VR、ANDROID）
     * @param clientVersion 客户端版本
     * @param userAgent     User-Agent 字符串
     * @param deviceMake    设备厂商（android_vr 需要）
     * @param deviceModel   设备型号（android_vr 需要）
     * @param androidSdk    Android SDK 版本
     * @param osName        操作系统名称
     * @param osVersion     操作系统版本
     * @param cookies       预获取的 YouTube cookies（可为 null）
     */
    private ExtractorResult extractViaInnertube(String videoId,
                                                 String clientName, String clientVersion,
                                                 String userAgent,
                                                 String deviceMake, String deviceModel,
                                                 int androidSdk,
                                                 String osName, String osVersion,
                                                 String cookies)
            throws ExtractorException, IOException {
        // InnerTube API 端点（移动客户端不需要 key 参数）
        String apiUrl = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";

        // 构造请求体（对应 yt-dlp _call_api 的 data 构造）
        JSONObject body = new JSONObject();
        try {
            // context.client（对应 INNERTUBE_CONTEXT.client）
            JSONObject context = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", clientName);
            client.put("clientVersion", clientVersion);
            client.put("hl", "en");
            client.put("gl", "US");
            if (userAgent != null) client.put("userAgent", userAgent);
            // android 系列客户端需要 androidSdkVersion/osName/osVersion
            if (androidSdk > 0) client.put("androidSdkVersion", androidSdk);
            if (osName != null) client.put("osName", osName);
            if (osVersion != null) client.put("osVersion", osVersion);
            // android_vr 需要设备信息
            if (deviceMake != null) client.put("deviceMake", deviceMake);
            if (deviceModel != null) client.put("deviceModel", deviceModel);
            context.put("client", client);
            body.put("context", context);

            // videoId
            body.put("videoId", videoId);

            // contentCheckOk / racyCheckOk（对应 yt-dlp _get_checkok_params）
            // 绕过内容检查，避免某些视频返回 UNPLAYABLE
            body.put("contentCheckOk", true);
            body.put("racyCheckOk", true);

            // playbackContext（对应 yt-dlp _generate_player_context）
            JSONObject playbackContext = new JSONObject();
            JSONObject contentPlaybackContext = new JSONObject();
            contentPlaybackContext.put("html5Preference", "HTML5_PREF_WANTS");
            playbackContext.put("contentPlaybackContext", contentPlaybackContext);
            body.put("playbackContext", playbackContext);

        } catch (org.json.JSONException e) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "构造 InnerTube 请求失败: " + e.getMessage());
        }

        // 请求头（对应 yt-dlp generate_api_headers）
        Map<String, String> headers = ExtractorHttp.defaultHeaders();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", userAgent);
        headers.put("X-Goog-Api-Format-Version", "2");
        headers.put("Origin", "https://www.youtube.com");
        headers.put("Referer", "https://www.youtube.com/");
        // 携带预获取的 cookies（对应 yt-dlp cookiejar，绕过反机器人检测）
        if (cookies != null && !cookies.isEmpty()) {
            headers.put("Cookie", cookies);
        }

        String response;
        try {
            response = ExtractorHttp.postString(apiUrl, body.toString(),
                    "application/json", headers);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("SSLHandshake") || msg.contains("connection closed")
                    || msg.contains("timed out") || msg.contains("UnknownHost")) {
                throw new ExtractorException(ExtractorException.Kind.GEO_RESTRICTED,
                        "无法连接 YouTube 服务器，可能受网络限制: " + msg);
            }
            throw e;
        }

        JSONObject playerResponse;
        try {
            playerResponse = new JSONObject(response);
        } catch (org.json.JSONException e) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "InnerTube 响应 JSON 解析失败: " + e.getMessage());
        }

        return buildResultFromPlayerResponse(playerResponse, videoId);
    }

    /**
     * 通过 watch 页面抓取提取（回退方案，对应 yt-dlp _WEBPAGE_CLIENTS）。
     *
     * <p>下载 watch 页面 HTML，提取 ytInitialPlayerResponse JSON。
     * 此方法可能触发 YouTube 的浏览器反爬机制，作为最后手段。</p>
     */
    private ExtractorResult extractViaWatchPage(String videoId, String cookies)
            throws ExtractorException, IOException {
        Map<String, String> headers = ExtractorHttp.defaultHeaders();
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Referer", "https://www.youtube.com/");
        // 添加 Sec-Fetch 头模拟真实浏览器
        headers.put("Sec-Fetch-Mode", "navigate");
        headers.put("Sec-Fetch-Dest", "document");
        headers.put("Sec-Fetch-Site", "none");
        headers.put("Sec-Fetch-User", "?1");
        headers.put("Upgrade-Insecure-Requests", "1");
        if (cookies != null && !cookies.isEmpty()) {
            headers.put("Cookie", cookies);
        }

        // bpctr=9999999999 绕过争议内容限制（对应 yt-dlp 中的 bpctr 参数）
        String watchUrl = "https://www.youtube.com/watch?v=" + videoId
                + "&hl=en&gl=US&has_verified=1&bpctr=9999999999";
        String webpage;
        try {
            webpage = ExtractorHttp.downloadWebpage(watchUrl, headers);
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.contains("SSLHandshake") || msg.contains("connection closed")
                    || msg.contains("timed out") || msg.contains("UnknownHost")) {
                throw new ExtractorException(ExtractorException.Kind.GEO_RESTRICTED,
                        "无法连接 YouTube 服务器，可能受网络限制: " + msg);
            }
            throw e;
        }

        JSONObject playerResponse = extractPlayerResponse(webpage);
        if (playerResponse == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "无法从页面提取 ytInitialPlayerResponse");
        }

        return buildResultFromPlayerResponse(playerResponse, videoId);
    }

    /** 从 playerResponse JSON 构建 ExtractorResult。 */
    private ExtractorResult buildResultFromPlayerResponse(JSONObject playerResponse,
                                                           String videoId)
            throws ExtractorException {
        // 检查 playabilityStatus（对应 yt-dlp _extract_player_response 中的状态检查）
        checkPlayability(playerResponse);

        JSONObject videoDetails = playerResponse.optJSONObject("videoDetails");
        String title = videoDetails != null ? videoDetails.optString("title", videoId) : videoId;
        String author = videoDetails != null ? videoDetails.optString("author", "") : "";

        JSONObject streamingData = playerResponse.optJSONObject("streamingData");
        if (streamingData == null) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "streamingData 为空，视频可能需要签名解密或登录");
        }

        List<ExtractorResult.Format> formats = new ArrayList<>();
        // 合并格式（video+audio，对应 yt-dlp streamingData.formats）
        JSONArray combinedFormats = streamingData.optJSONArray("formats");
        if (combinedFormats != null) {
            addFormatsFromJsonArray(combinedFormats, formats, true);
        }
        // 分离格式（DASH，对应 yt-dlp streamingData.adaptiveFormats）
        JSONArray adaptiveFormats = streamingData.optJSONArray("adaptiveFormats");
        if (adaptiveFormats != null) {
            addFormatsFromJsonArray(adaptiveFormats, formats, false);
        }

        // HLS manifest URL（对应 yt-dlp streamingData.hlsManifestUrl）
        // tv 客户端通常返回此字段，可被 HlsDownloader 直接处理
        String hlsManifestUrl = streamingData.optString("hlsManifestUrl", null);
        if (hlsManifestUrl != null && !hlsManifestUrl.isEmpty()) {
            Log.i(TAG, "发现 HLS manifest: " + hlsManifestUrl.substring(0,
                    Math.min(80, hlsManifestUrl.length())));
            // 添加为 HLS 格式，downloadSmart 会自动路由到 HlsDownloader
            ExtractorResult.Format hlsFmt = new ExtractorResult.Format(
                    hlsManifestUrl, "ts", 720, "hls", "aac", 0, 0, 720, 0, "HLS 流（自适应画质）");
            formats.add(hlsFmt);
        }

        if (formats.isEmpty()) {
            throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED,
                    "未提取到可直接下载的格式（所有格式可能需要签名解密或 PO Token）");
        }

        Log.i(TAG, "提取成功: " + title + " 格式数=" + formats.size());

        String safeTitle = sanitizeTitle(title);
        return new ExtractorResult(videoId, title, author, formats, safeTitle, "mp4");
    }

    /** 从正则匹配结果中提取 video_id（多个命名组中第一个非空的）。 */
    private String extractGroupId(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            String g = m.group(i);
            if (g != null && !g.isEmpty()) return g;
        }
        return null;
    }

    /**
     * 从 HTML 中提取 ytInitialPlayerResponse JSON。
     *
     * <p>对应 yt-dlp _search_json(_YT_INITIAL_PLAYER_RESPONSE_RE, ...)。
     * 先尝试主正则，再尝试 ytcfg 中的 playerResponse 字段。</p>
     */
    private JSONObject extractPlayerResponse(String webpage) {
        // 方式1：ytInitialPlayerResponse = {...};
        Matcher m = PLAYER_RESPONSE_PATTERN.matcher(webpage);
        if (m.find()) {
            String json = m.group(1);
            try {
                return new JSONObject(json);
            } catch (org.json.JSONException e) {
                Log.w(TAG, "ytInitialPlayerResponse JSON 解析失败: " + e.getMessage());
            }
        }

        // 方式2：ytcfg 中的 "playerResponse":"{...}"（转义 JSON）
        Matcher m2 = YTCFG_PLAYER_RESPONSE_PATTERN.matcher(webpage);
        if (m2.find()) {
            String escapedJson = m2.group(1);
            try {
                // 去除外层引号并反转义
                String json = escapedJson.substring(1, escapedJson.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\")
                        .replace("\\/", "/");
                return new JSONObject(json);
            } catch (org.json.JSONException e) {
                Log.w(TAG, "ytcfg playerResponse JSON 解析失败: " + e.getMessage());
            }
        }

        Log.w(TAG, "未找到 ytInitialPlayerResponse");
        return null;
    }

    /**
     * 检查 playabilityStatus，处理错误状态。
     *
     * <p>对应 yt-dlp 中对 playabilityStatus 的各种状态处理。</p>
     */
    private void checkPlayability(JSONObject playerResponse) throws ExtractorException {
        JSONObject ps = playerResponse.optJSONObject("playabilityStatus");
        if (ps == null) return;

        String status = ps.optString("status", "");
        if ("OK".equals(status) || "LIVE_STREAM_OFFLINE".equals(status)) {
            // LIVE_STREAM_OFFLINE 也可能有预览格式可下载
            return;
        }

        // 获取错误原因（对应 yt-dlp 中的 reason/messages/errorScreen）
        String reason = ps.optString("reason", "");
        if (reason.isEmpty()) {
            reason = ps.optString("messages", "");
        }
        if (reason.isEmpty()) {
            JSONObject errorScreen = ps.optJSONObject("errorScreen");
            if (errorScreen != null) {
                reason = errorScreen.optString("playerErrorMessageRenderer", "");
            }
        }
        if (reason.isEmpty()) reason = "未知错误";

        switch (status) {
            case "LOGIN_REQUIRED":
                throw new ExtractorException(ExtractorException.Kind.LOGIN_REQUIRED,
                        "需要登录才能观看: " + reason);
            case "AGE_RESTRICTED":
            case "AGE_VERIFICATION_REQUIRED":
                throw new ExtractorException(ExtractorException.Kind.LOGIN_REQUIRED,
                        "年龄限制内容，需要登录验证: " + reason);
            case "PREMIUM_ONLY":
                throw new ExtractorException(ExtractorException.Kind.PREMIUM_ONLY,
                        "仅会员可看: " + reason);
            case "PREMIERE":
                throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED,
                        "首播未开始: " + reason);
            case "UNPLAYABLE":
                throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED,
                        "无法播放: " + reason);
            case "ERROR":
                throw new ExtractorException(ExtractorException.Kind.NOT_FOUND,
                        "视频错误: " + reason);
            case "GEO_BLOCKED":
                throw new ExtractorException(ExtractorException.Kind.GEO_RESTRICTED,
                        "地区限制: " + reason);
            default:
                throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED,
                        "播放状态异常 [" + status + "]: " + reason);
        }
    }

    /**
     * 从 JSON 数组中提取格式信息。
     *
     * <p>对应 yt-dlp _extract_formats 中的格式遍历。
     * 仅保留有直接 URL 的格式（跳过 signatureCipher，因为不实现 JS 签名解密）。</p>
     */
    private void addFormatsFromJsonArray(JSONArray arr, List<ExtractorResult.Format> out,
                                          boolean isCombined) {
        for (int i = 0; i < arr.length(); i++) {
            try {
                JSONObject fmt = arr.getJSONObject(i);
                ExtractorResult.Format f = parseFormat(fmt, isCombined);
                if (f != null) out.add(f);
            } catch (org.json.JSONException e) {
                Log.w(TAG, "格式解析失败: " + e.getMessage());
            }
        }
    }

    /**
     * 解析单个格式对象。
     *
     * <p>对应 yt-dlp process_format_stream 中的格式解析逻辑。
     * 仅使用带直接 URL 的格式；signatureCipher 格式需要 JS 解密，跳过。</p>
     */
    private ExtractorResult.Format parseFormat(JSONObject fmt, boolean isCombined) {
        String url = fmt.optString("url", null);

        // signatureCipher 格式需要 JS 签名解密，不实现（对应 yt-dlp REQUIRE_JS_PLAYER=False 的行为）
        String signatureCipher = fmt.optString("signatureCipher", null);
        if ((url == null || url.isEmpty()) && signatureCipher != null) {
            Log.d(TAG, "跳过需签名的格式 itag=" + fmt.optString("itag")
                    + "（不实现 JS 签名解密）");
            return null;
        }
        if (url == null || url.isEmpty()) return null;

        int itag = fmt.optInt("itag", 0);
        String mimeType = fmt.optString("mimeType", "");
        String ext = mimeToExt(mimeType);
        String vcodec = extractCodec(mimeType, "video");
        String acodec = extractCodec(mimeType, "audio");

        int width = fmt.optInt("width", 0);
        int height = fmt.optInt("height", 0);
        int fps = fmt.optInt("fps", 0);
        // 优先使用 averageBitrate，回退到 bitrate（对应 yt-dlp 的 tbr 提取）
        long tbr = fmt.optLong("averageBitrate", 0);
        if (tbr == 0) tbr = fmt.optLong("bitrate", 0);
        tbr = tbr / 1000; // 转为 kbps
        long contentLength = fmt.optLong("contentLength", 0);

        // 画质描述
        String desc = buildFormatDescription(itag, height, fps, vcodec, acodec, isCombined);

        return new ExtractorResult.Format(
                url, ext, height, vcodec, acodec,
                (int) Math.min(tbr, Integer.MAX_VALUE),
                width, height, contentLength, desc);
    }

    /** MIME 类型转扩展名。 */
    private String mimeToExt(String mime) {
        if (mime == null) return "mp4";
        String lower = mime.toLowerCase();
        if (lower.contains("mp4")) return "mp4";
        if (lower.contains("webm")) return "webm";
        if (lower.contains("3gpp")) return "3gp";
        if (lower.contains("mp3")) return "mp3";
        if (lower.contains("mp4a") || lower.contains("m4a")) return "m4a";
        if (lower.contains("opus")) return "opus";
        if (lower.contains("vorbis")) return "ogg";
        return "mp4";
    }

    /** 从 MIME 类型中提取编码器。 */
    private String extractCodec(String mime, String type) {
        if (mime == null) return "unknown";
        // 格式: video/mp4; codecs="avc1.640028, mp4a.40.2"
        int idx = mime.indexOf("codecs=\"");
        if (idx < 0) return "unknown";
        int end = mime.indexOf("\"", idx + 8);
        if (end < 0) return "unknown";
        String codecs = mime.substring(idx + 8, end);
        String[] parts = codecs.split(",");
        for (String c : parts) {
            String ct = c.trim().toLowerCase();
            if ("video".equals(type) && (ct.startsWith("avc") || ct.startsWith("vp")
                    || ct.startsWith("av01") || ct.startsWith("h26"))) {
                return ct;
            }
            if ("audio".equals(type) && (ct.startsWith("mp4a") || ct.startsWith("opus")
                    || ct.startsWith("vorbis") || ct.startsWith("ac-3"))) {
                return ct;
            }
        }
        return "unknown";
    }

    /** 构建格式描述。 */
    private String buildFormatDescription(int itag, int height, int fps,
                                           String vcodec, String acodec, boolean isCombined) {
        StringBuilder sb = new StringBuilder();
        if (height > 0) {
            sb.append(height).append("P");
            if (fps > 30) sb.append(fps);
        } else {
            sb.append("Audio");
        }
        if (isCombined) sb.append(" (合并)");
        if (vcodec != null && !"unknown".equals(vcodec)) sb.append(" ").append(vcodec);
        if (acodec != null && !"unknown".equals(acodec)) sb.append(" ").append(acodec);
        if (itag > 0) sb.append(" [").append(itag).append("]");
        return sb.toString();
    }

    /** 清理标题用于文件名。 */
    private String sanitizeTitle(String title) {
        if (title == null) return "youtube_video";
        return title.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }
}
