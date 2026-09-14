package com.example.cleanrecovery.download;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class YtDlpMediaTest {
    @Test public void watermarkFreeWinsOnlyWithinSameQualityAndSurvivesRestore() throws Exception {
        YtDlpMedia info = media(format("web", "avc1", "none", 720).put("tbr", 2000),
                format("bili_tv_clean", "avc1", "none", 720).put("tbr", 1000).put("shu_watermark", "absent"),
                format("hd", "avc1", "none", 1080), format("audio", "none", "aac", 0));
        assertEquals(2, info.videos.size());
        assertEquals("hd+bestaudio", info.videos.get(0).selector);
        assertEquals("bili_tv_clean+bestaudio", info.videos.get(1).selector);
        assertTrue(info.videos.get(1).label.contains("无水印"));
        assertFalse(info.videos.get(0).label.contains("无水印"));
        assertEquals(info.videos.get(1).label, YtDlpMedia.restore(info.toJson()).videos.get(1).label);
    }
    @Test public void missingCleanSourceKeepsOrdinaryDownloadAndUnknownIsNotLabelled() throws Exception {
        YtDlpMedia info = media(format("marked", "avc1", "aac", 720).put("tbr", 2000).put("shu_watermark", "present"),
                format("unknown", "avc1", "aac", 720).put("tbr", 1000));
        assertEquals("unknown", info.videos.get(0).selector);
        assertFalse(info.videos.get(0).label.contains("无水印"));
        assertEquals("marked", media(format("marked", "avc1", "aac", 720).put("shu_watermark", "present")).videos.get(0).selector);
    }
    @Test public void portraitLabelsShowActualDimensions() throws Exception {
        YtDlpMedia info = media(format("portrait", "h264", "aac", 1280).put("width", 720));
        assertTrue(info.videos.get(0).label.startsWith("720×1280"));
        assertEquals(1280, info.videos.get(0).height);
    }
    private JSONObject format(String id, String video, String audio, int height) throws Exception {
        return new JSONObject().put("format_id", id).put("url", "https://cdn.example.org/video")
                .put("vcodec", video).put("acodec", audio).put("height", height).put("ext", "mp4");
    }
    private YtDlpMedia media(JSONObject... formats) throws Exception {
        JSONArray array = new JSONArray(); for (JSONObject f : formats) array.put(f);
        return YtDlpMedia.parse(new JSONObject().put("title", "测试").put("formats", array).toString());
    }
    @Test public void sharedDouyinTextExtractsOnlyLink() {
        assertEquals("https://v.douyin.com/Ab12/", YtDlpMedia.normalizeUrl("3.2 复制打开抖音 https://v.douyin.com/Ab12/ 看视频"));
        assertEquals("https://www.bilibili.com/video/BV123", YtDlpMedia.normalizeUrl("www.bilibili.com/video/BV123"));
    }
    @Test public void rejectsNonWebInputAndCredentials() {
        for (String value : new String[]{"", "--exec rm", "file:///sdcard/test", "ftp://example.org/video", "https://user:pass@example.org/"}) {
            try { YtDlpMedia.normalizeUrl(value); fail(value); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void dashAddsAudioWithoutFallingBackToDifferentVideoQuality() throws Exception {
        YtDlpMedia info = media(format("137", "avc1", "none", 1080), format("140", "none", "mp4a", 0), format("18", "avc1", "mp4a", 360));
        assertEquals("137+bestaudio", info.videos.get(0).selector);
        assertEquals("18", info.videos.get(1).selector);
        assertEquals("140", info.audios.get(0).selector);
    }
    @Test public void preservesDifferentCodecsAtSameResolution() throws Exception {
        YtDlpMedia info = media(format("137", "avc1", "aac", 1080), format("248", "vp9", "opus", 1080));
        assertEquals(2, info.videos.size());
        assertTrue(info.videos.get(1).label.contains("vp9"));
    }
    @Test public void doesNotOfferSilentVideoOrDrmOrStoryboards() throws Exception {
        YtDlpMedia info = media(format("video", "avc1", "none", 1080), format("drm", "avc1", "aac", 2160).put("has_drm", true),
                format("board", "mhtml", "none", 100).put("protocol", "mhtml"), format("ok", "avc1", "aac", 360));
        assertEquals(1, info.videos.size()); assertEquals("ok", info.videos.get(0).selector);
    }
    @Test public void supportsAudioOnlyAndRejectsEmptyFormats() throws Exception {
        assertTrue(media(format("audio", "none", "opus", 0)).videos.isEmpty());
        try { media(); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void rejectsPlaylistAndLiveStream() throws Exception {
        for (String json : new String[]{"{\"entries\":[]}", "{\"is_live\":true}"}) {
            try { YtDlpMedia.parse(json); fail(); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void batchesDeduplicateSharedLinksAndLimitCount() {
        assertEquals(2, YtDlpMedia.normalizeUrls("分享 https://youtu.be/one\nhttps://youtu.be/two\nhttps://youtu.be/one").size());
        StringBuilder links = new StringBuilder(); for (int i = 0; i < 21; i++) links.append("https://youtu.be/").append(i).append('\n');
        try { YtDlpMedia.normalizeUrls(links.toString()); fail(); } catch (IllegalArgumentException expected) { }
    }
    @Test public void journalRetainsExactChoicesButNoSignedUrls() throws Exception {
        YtDlpMedia original = media(format("137", "avc1", "none", 1080), format("140", "none", "mp4a", 0));
        String saved = original.toJson().toString(); assertFalse(saved.contains("cdn.example.org"));
        YtDlpMedia restored = YtDlpMedia.restore(new JSONObject(saved));
        assertEquals(original.videos.get(0).selector, restored.videos.get(0).selector);
        assertEquals(original.audios.get(0).selector, restored.audios.get(0).selector);
    }
    @Test public void unknownCodecsDoNotHideDirectMp4AndHls() throws Exception {
        JSONObject generic = new JSONObject().put("format_id", "mp4").put("url", "https://example.org/test.mp4").put("ext", "mp4");
        assertEquals("mp4", media(generic).videos.get(0).selector);
        generic.put("format_id", "hls").put("protocol", "m3u8_native").put("vcodec", JSONObject.NULL).put("acodec", JSONObject.NULL);
        assertEquals("hls", media(generic).videos.get(0).selector);
    }
}
