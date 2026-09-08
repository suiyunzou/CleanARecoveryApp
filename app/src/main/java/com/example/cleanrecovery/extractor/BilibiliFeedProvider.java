package com.example.cleanrecovery.extractor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B站「抖音模式」feed 数据链。
 *
 * <p>当前视频 → 官方相关视频 API（{@code x/web-interface/archive/related}，无需 WBI 签名）
 * 无限追加 → 每条经 {@link BilibiliExtractor}（匿名 try_look 档位）解析直链。
 * DASH 分离流给出 videoUrl+audioUrl（播放侧用 MergingMediaSource 合并），
 * 合并流直接给 videoUrl。所有请求头（Referer 等）取自 Format.httpHeaders。</p>
 */
public final class BilibiliFeedProvider {

    private static final Pattern AV_PATTERN = Pattern.compile("av(\\d+)");
    private static final Pattern BV_PATTERN = Pattern.compile("(BV[0-9A-Za-z]{10})");

    public static final class FeedItem {
        public String webUrl = "";
        public String bvid = "";
        public long aid;
        public String title = "";
        public String uploader = "";
        public String cover = "";
        public long durationSec;
        /** 合并流直链（或 DASH 视频轨直链）。 */
        public String videoUrl;
        /** DASH 音轨直链（为空表示已合并）。 */
        public String audioUrl;
        public String ext = "mp4";
        public boolean hls;
        public Map<String, String> headers;
        public String error;
        public boolean resolved;

        public String key() {
            return !bvid.isEmpty() ? bvid + "#p" + pageOf(webUrl) : webUrl;
        }
    }

    private BilibiliFeedProvider() { }

    /** 解析起始视频（含分 P 页面 URL）。 */
    public static FeedItem root(String webUrl) throws Exception {
        FeedItem item = new FeedItem();
        item.webUrl = normalizeWebUrl(webUrl);
        resolve(item);
        return item;
    }

    /** 相关视频（官方 API）；anchor 提供 aid/bvid。 */
    public static List<FeedItem> related(FeedItem anchor) throws Exception {
        List<FeedItem> out = new ArrayList<>();
        StringBuilder api = new StringBuilder("https://api.bilibili.com/x/web-interface/archive/related?");
        if (!anchor.bvid.isEmpty()) {
            api.append("bvid=").append(anchor.bvid);
        } else if (anchor.aid > 0) {
            api.append("aid=").append(anchor.aid);
        } else {
            return out;
        }
        String body = ExtractorHttp.downloadJson(api.toString(), ExtractorHttp.defaultHeaders());
        JSONObject json = new JSONObject(body);
        int code = json.optInt("code", -1);
        if (code != 0) {
            throw new ExtractorException(ExtractorException.Kind.UNKNOWN,
                    "相关视频接口返回 " + code + ": " + json.optString("message"));
        }
        JSONArray data = json.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject o = data.optJSONObject(i);
                if (o == null) continue;
                FeedItem item = new FeedItem();
                item.bvid = o.optString("bvid");
                item.aid = o.optLong("aid");
                item.title = o.optString("title");
                item.uploader = o.optJSONObject("owner") == null
                        ? "" : o.optJSONObject("owner").optString("name");
                item.cover = o.optString("pic");
                item.durationSec = o.optLong("duration");
                item.webUrl = item.bvid.isEmpty()
                        ? ("https://www.bilibili.com/video/av" + item.aid)
                        : ("https://www.bilibili.com/video/" + item.bvid);
                out.add(item);
            }
        }
        return out;
    }

    /** 解析单条直链（网络调用，放后台线程）。 */
    public static void resolve(FeedItem item) throws Exception {
        if (item.resolved) return;
        Extractor extractor = ExtractorRegistry.match(item.webUrl);
        if (extractor == null) {
            throw new ExtractorException(ExtractorException.Kind.UNSUPPORTED, "不支持的链接: " + item.webUrl);
        }
        ExtractorResult result = extractor.extract(item.webUrl);
        fillFromResult(item, result);
        item.resolved = true;
        if (item.aid <= 0 || item.bvid.isEmpty()) {
            fillIds(item, result.getWebpageUrl() != null ? result.getWebpageUrl() : item.webUrl);
        }
    }

    private static void fillFromResult(FeedItem item, ExtractorResult r) throws ExtractorException {
        if (item.title == null || item.title.isEmpty()) item.title = r.getTitle();
        if (item.uploader == null || item.uploader.isEmpty()) item.uploader = r.getUploader();
        if (item.cover == null || item.cover.isEmpty()) item.cover = r.getThumbnailUrl();
        if (item.durationSec <= 0) item.durationSec = r.getDuration();
        ExtractorResult.Format best = r.getBestCombinedFormat();
        if (best != null) {
            item.videoUrl = best.url;
            item.audioUrl = null;
            item.ext = best.ext;
            item.hls = best.isHls();
            item.headers = best.httpHeaders;
            return;
        }
        ExtractorResult.Format video = r.getBestVideoOnlyFormat();
        ExtractorResult.Format audio = r.getBestAudioOnlyFormat();
        if (video != null && audio != null) {
            item.videoUrl = video.url;
            item.audioUrl = audio.url;
            item.ext = video.ext;
            item.hls = false;
            item.headers = video.httpHeaders;
            return;
        }
        throw new ExtractorException(ExtractorException.Kind.PARSE_FAILED, "未找到可播放的格式");
    }

    private static void fillIds(FeedItem item, String url) {
        if (url == null) return;
        Matcher bv = BV_PATTERN.matcher(url);
        if (bv.find()) item.bvid = bv.group(1);
        Matcher av = AV_PATTERN.matcher(url);
        if (av.find()) {
            try {
                item.aid = Long.parseLong(av.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static String normalizeWebUrl(String url) {
        if (url == null) return "";
        // b23.tv 短链交给 extractor 自行展开；这里只做基本信息提取的前置 URL
        return url;
    }

    private static int pageOf(String url) {
        if (url == null) return 1;
        Matcher m = Pattern.compile("[?&]p=(\\d+)").matcher(url);
        return m.find() ? Integer.parseInt(m.group(1)) : 1;
    }

    /** 去重合并工具：按 key 过滤已见条目。 */
    public static List<FeedItem> filterNew(List<FeedItem> candidates, Set<String> seenKeys) {
        List<FeedItem> out = new ArrayList<>();
        for (FeedItem c : candidates) {
            if (seenKeys.add(c.key())) out.add(c);
        }
        return out;
    }

    /** 便捷构造空集工具（保留 LinkedHashSet 语义避免乱序）。 */
    public static Set<String> newSeenSet() {
        return new LinkedHashSet<>();
    }
}
