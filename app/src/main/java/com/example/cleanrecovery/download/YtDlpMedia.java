package com.example.cleanrecovery.download;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure metadata/selection layer. Never interprets user text as command arguments. */
public final class YtDlpMedia {
    public final String title;
    public final double durationSeconds;
    public final List<Choice> videos;
    public final List<Choice> audios;

    public static final class Choice {
        public final String selector, label;
        public final int height;
        public final boolean audioOnly;
        Choice(String selector, String label, int height, boolean audioOnly) {
            this.selector = selector; this.label = label; this.height = height; this.audioOnly = audioOnly;
        }
    }

    private YtDlpMedia(String title, List<Choice> videos, List<Choice> audios, double durationSeconds) {
        this.title = title;
        this.durationSeconds = durationSeconds;
        this.videos = Collections.unmodifiableList(videos);
        this.audios = Collections.unmodifiableList(audios);
    }

    /** Persist selections only, never signed stream URLs or cookies. */
    public JSONObject toJson() throws Exception {
        JSONObject json = new JSONObject().put("title", title).put("duration", durationSeconds);
        JSONArray choices = new JSONArray();
        for (List<Choice> list : java.util.Arrays.asList(videos, audios)) for (Choice c : list)
            choices.put(new JSONObject().put("selector", c.selector).put("label", c.label).put("height", c.height).put("audio", c.audioOnly));
        return json.put("choices", choices);
    }
    public static YtDlpMedia restore(JSONObject json) throws Exception {
        List<Choice> videos = new ArrayList<>(), audios = new ArrayList<>();
        JSONArray choices = json.getJSONArray("choices");
        for (int i = 0; i < choices.length(); i++) {
            JSONObject c = choices.getJSONObject(i);
            String selector = c.getString("selector");
            if (!selector.matches("[A-Za-z0-9_.-]+(\\+bestaudio)?")) throw new IllegalArgumentException("Invalid saved format");
            Choice choice = new Choice(selector, c.getString("label"), c.getInt("height"), c.getBoolean("audio"));
            (choice.audioOnly ? audios : videos).add(choice);
        }
        return new YtDlpMedia(json.getString("title"), videos, audios, json.optDouble("duration", 0));
    }

    public static String normalizeUrl(String input) {
        String value = input == null ? "" : input.trim();
        Matcher match = Pattern.compile("https?://[^\\s<>\\\"，。！）】]+", Pattern.CASE_INSENSITIVE).matcher(value);
        if (match.find()) value = match.group();
        else if (!value.contains("://") && !value.contains(" ")) value = "https://" + value;
        while (value.endsWith(")") || value.endsWith("。")) value = value.substring(0, value.length() - 1);
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || !uri.getHost().contains(".") || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (Exception error) { throw new IllegalArgumentException("请输入有效的视频链接，也可以粘贴分享文案"); }
    }

    public static List<String> normalizeUrls(String input) {
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        String text = input == null ? "" : input.trim();
        Matcher links = Pattern.compile("https?://[^\\s<>\\\"，。！）】]+", Pattern.CASE_INSENSITIVE).matcher(text);
        while (links.find()) urls.add(normalizeUrl(links.group()));
        if (urls.isEmpty()) for (String line : text.split("\\r?\\n")) urls.add(normalizeUrl(line));
        if (urls.size() > 20) throw new IllegalArgumentException("每批最多 20 个链接，请分批添加");
        return Collections.unmodifiableList(new ArrayList<>(urls));
    }

    public static YtDlpMedia parse(String json) throws Exception {
        JSONObject info = new JSONObject(json);
        if (info.has("entries")) throw new IllegalArgumentException("请粘贴单个视频链接，暂不下载整个合集");
        if (info.optBoolean("is_live")) throw new IllegalArgumentException("暂不支持正在直播的链接");
        JSONArray formats = info.optJSONArray("formats");
        if (formats == null) formats = new JSONArray().put(info);
        List<JSONObject> usable = new ArrayList<>();
        boolean hasAudio = false;
        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.getJSONObject(i);
            String id = f.optString("format_id");
            if (!id.matches("[A-Za-z0-9_.-]+") || f.optBoolean("has_drm") || f.optString("url").isEmpty()) continue;
            String protocol = f.optString("protocol");
            if (protocol.contains("mhtml") || "images".equals(f.optString("ext"))) continue;
            usable.add(f);
            if ("none".equals(f.optString("vcodec")) && codec(f, "acodec")) hasAudio = true;
        }
        // Keep the best bitrate for each resolution/codec/fps, while retaining codec alternatives.
        Map<String, JSONObject> grouped = new LinkedHashMap<>();
        for (JSONObject f : usable) {
            String key = f.optInt("height") + ":" + f.optInt("fps") + ":" + f.optString("ext")
                    + ":" + f.optString("vcodec") + ":" + f.optString("acodec") + ":" + f.optString("language")
                    + ":" + f.optString("dynamic_range");
            JSONObject previous = grouped.get(key);
            if (previous == null || sourceRank(f) > sourceRank(previous)
                    || sourceRank(f) == sourceRank(previous) && f.optDouble("tbr", 0) > previous.optDouble("tbr", 0)) grouped.put(key, f);
        }
        List<Choice> videos = new ArrayList<>(), audios = new ArrayList<>();
        for (JSONObject f : grouped.values()) {
            boolean video = codec(f, "vcodec"), audio = codec(f, "acodec");
            if (!video && !audio) continue;
            String id = f.getString("format_id"), ext = f.optString("ext", "");
            int height = f.optInt("height"), fps = f.optInt("fps");
            if (video) {
                if (!audio && !hasAudio) continue; // Never offer an accidentally silent download.
                String selector = id + (audio ? "" : "+bestaudio");
                int width = f.optInt("width");
                String label = (width > 0 && height > width ? width + "×" + height
                        : height > 0 ? height + "p" : f.optString("format_note", "视频"))
                        + (fps > 30 ? " " + fps + "fps" : "") + " · " + ext
                        + " · " + f.optString("vcodec", "") + size(f)
                        + ("absent".equals(f.optString("shu_watermark")) ? " · 无水印" : "");
                videos.add(new Choice(selector, label, height, false));
            } else {
                long bitrate = Math.round(f.optDouble("abr", f.optDouble("tbr", 0)));
                String label = ext + (bitrate > 0 ? " · " + bitrate + " kbps" : "") + size(f);
                audios.add(new Choice(id, label, 0, true));
            }
        }
        videos.sort(Comparator.comparingInt((Choice c) -> c.height).reversed());
        if (videos.isEmpty() && audios.isEmpty()) throw new IllegalArgumentException("没有可下载的音视频格式，请检查登录状态或更新下载引擎");
        return new YtDlpMedia(info.optString("title", "媒体"), videos, audios, info.optDouble("duration", 0));
    }

    private static boolean codec(JSONObject f, String key) {
        // yt-dlp uses explicit "none" for an absent track. Missing/null means unknown,
        // which is normal for direct MP4 and HLS media playlists without CODECS metadata.
        return !"none".equals(f.optString(key, ""));
    }
    private static int sourceRank(JSONObject format) {
        String mark = format.optString("shu_watermark");
        return "absent".equals(mark) ? 1 : "present".equals(mark) ? -1 : 0;
    }
    private static String size(JSONObject f) {
        long bytes = f.optLong("filesize", f.optLong("filesize_approx", 0));
        return bytes > 0 ? String.format(Locale.ROOT, " · %.1f MB", bytes / 1048576d) : "";
    }
}
