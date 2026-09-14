package com.example.cleanrecovery.download;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import androidx.test.platform.app.InstrumentationRegistry;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import org.junit.Test;
import java.io.File;
import java.util.UUID;
import static org.junit.Assert.*;

/** Explicit real-network checks. Never treated as offline unit-test coverage. */
public class YtDlpEngineDeviceTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void bundledRuntimeStarts() throws Exception {
        YtDlpEngine.init(context);
        YoutubeDLRequest request = new YoutubeDLRequest(java.util.Collections.emptyList());
        request.addOption("--version");
        String version = YoutubeDL.getInstance().execute(request, "runtime-check", null).getOut().trim();
        assertTrue(version, version.matches(".*\\d{4}\\.\\d{2}\\.\\d{2}.*"));
    }
    @Test public void douyinWatermarkFreeSourceDownloadsAndDecodes() throws Exception {
        watermarkSource("https://www.douyin.com/video/6982497745948921092", "douyin", true);
    }
    @Test public void bilibiliOptionalTvKeepsWorkingDownload() throws Exception {
        watermarkSource("https://www.bilibili.com/video/BV1GJ411x7h7?p=1", "bilibili", false);
    }
    private void watermarkSource(String url, String platform, boolean requireClean) throws Exception {
        File work = new File(context.getCacheDir(), "yt-watermark-" + UUID.randomUUID());
        try {
            YtDlpMedia media = YtDlpEngine.resolve(context, url, work, UUID.randomUUID().toString());
            assertFalse(media.videos.isEmpty());
            YtDlpMedia.Choice choice = media.videos.get(media.videos.size() - 1);
            if (requireClean) {
                assertTrue(choice.label, choice.label.contains("无水印"));
                assertTrue(choice.selector, choice.selector.endsWith("_nw"));
            }
            File file = YtDlpEngine.download(context, url, choice, work, UUID.randomUUID().toString(), (p, eta, line) -> {});
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                long duration = Long.parseLong(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
                assertTrue("Complete duration", duration >= media.durationSeconds * 980);
                assertEquals("yes", retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
                if (choice.height > 0) assertEquals(choice.height, Integer.parseInt(retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)));
                File evidence = new File(context.getExternalFilesDir(null), "watermark-evidence");
                assertTrue(evidence.isDirectory() || evidence.mkdirs());
                for (int part = 1; part <= 3; part++) {
                    android.graphics.Bitmap frame = retriever.getFrameAtTime(duration * 1000 * part / 4, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    assertNotNull("Decoded frame " + part, frame);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(new File(evidence, platform + "-" + part + ".png"))) {
                        assertTrue(frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out));
                    } finally { frame.recycle(); }
                }
                android.util.Log.i("YtDlpAcceptance", "Watermark source " + platform + " " + choice.selector + " " + choice.label + " bytes=" + file.length() + " durationMs=" + duration);
            } finally { retriever.release(); }
        } finally { delete(work); }
    }
    @Test public void youtubeDownloadsPlayableVideo() throws Exception {
        download("https://www.youtube.com/watch?v=jNQXAC9IVRw");
    }
    @Test public void bilibiliDownloadsPlayableVideo() throws Exception {
        download("https://www.bilibili.com/video/BV1GJ411x7h7");
    }
    @Test public void douyinDownloadsPlayableVideo() throws Exception {
        download("https://v.douyin.com/_8TBJ7UR0nM/");
    }
    @Test public void weiboMobileDownloadsPlayableVideo() throws Exception {
        download("https://m.weibo.cn/status/4189191225395228");
    }
    @Test public void xDownloadsPlayableVideo() throws Exception {
        download("https://twitter.com/captainamerica/status/719944021058060289");
    }
    @Test public void soundCloudDownloadsCompleteAudio() throws Exception {
        download("https://soundcloud.com/ethmusic/lostin-powers-she-so-heavy");
    }
    @Test public void douyinPortraitQualityMatrix() throws Exception {
        matrix("https://www.douyin.com/video/6961737553342991651", 1024, 1280);
    }
    @Test public void douyinSecondVideoQualityMatrix() throws Exception {
        matrix("https://www.douyin.com/video/6982497745948921092", 1024, 1280);
    }
    @Test public void youtubeShortLinkCodecAndAudioMatrix() throws Exception {
        matrix("https://youtu.be/jNQXAC9IVRw");
    }
    @Test public void bilibiliPageLinkCodecAndAudioMatrix() throws Exception {
        matrix("https://www.bilibili.com/video/BV1GJ411x7h7?p=1");
    }
    @Test public void youtubeHdVideoDownloads720And1080() throws Exception {
        // Public 60-second UHD test clip from the upstream yt-dlp extractor's test catalog.
        matrix("https://www.youtube.com/watch?v=a9LDPn-MO4I", 720, 1080);
    }
    @Test public void youtubeHlsAudioHasFullDuration() throws Exception {
        File work = new File(context.getCacheDir(), "yt-audio-regression-" + UUID.randomUUID());
        try {
            String url = "https://youtu.be/jNQXAC9IVRw";
            YtDlpMedia info = YtDlpEngine.resolve(context, url, work, UUID.randomUUID().toString());
            YtDlpMedia.Choice choice = null;
            for (YtDlpMedia.Choice c : info.audios) if (c.selector.equals("233")) choice = c;
            assertNotNull("HLS audio regression format available", choice);
            File file = YtDlpEngine.download(context, url, choice, work, UUID.randomUUID().toString(), (p, eta, line) -> {});
            MediaExtractor extractor = new MediaExtractor(); long duration = 0, lastSample = 0;
            try {
                extractor.setDataSource(file.getAbsolutePath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    if (f.containsKey(MediaFormat.KEY_DURATION)) duration = Math.max(duration, f.getLong(MediaFormat.KEY_DURATION));
                }
                extractor.selectTrack(0);
                do { lastSample = Math.max(lastSample, extractor.getSampleTime()); } while (extractor.advance());
            } finally { extractor.release(); }
            assertTrue("Full audio duration " + duration + " expected " + info.durationSeconds, duration >= info.durationSeconds * 980000);
            assertTrue("Audio samples reach the end of the source", lastSample >= info.durationSeconds * 950000);
            assertTrue("Raw AAC is packaged as M4A", file.getName().endsWith(".m4a"));
        } finally { delete(work); }
    }
    private void matrix(String url, int... heights) throws Exception {
        File work = new File(context.getCacheDir(), "yt-matrix-" + UUID.randomUUID());
        try {
            YtDlpMedia media = YtDlpEngine.resolve(context, url, work, UUID.randomUUID().toString());
            java.util.List<YtDlpMedia.Choice> chosen = new java.util.ArrayList<>();
            assertFalse(media.videos.isEmpty());
            if (heights.length == 0) {
                chosen.add(media.videos.get(0));
                if (media.videos.size() > 1) chosen.add(media.videos.get(media.videos.size() - 1));
                if (!media.audios.isEmpty()) chosen.add(media.audios.get(0));
            } else for (int height : heights) {
                YtDlpMedia.Choice selected = null;
                for (YtDlpMedia.Choice c : media.videos) if (c.height == height && (selected == null || c.label.contains("avc1"))) selected = c;
                assertNotNull("Expected available quality " + height, selected); chosen.add(selected);
            }
            for (YtDlpMedia.Choice choice : chosen) {
                android.util.Log.i("YtDlpAcceptance", "Selected " + choice.selector + " " + choice.label);
                File file = YtDlpEngine.download(context, url, choice, work, UUID.randomUUID().toString(), (p, eta, line) -> {});
                MediaExtractor extractor = new MediaExtractor();
                boolean video = false, audio = false; int height = 0; long durationUs = 0;
                try {
                    extractor.setDataSource(file.getAbsolutePath());
                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat f = extractor.getTrackFormat(i); String mime = f.getString(MediaFormat.KEY_MIME);
                        video |= mime != null && mime.startsWith("video/"); audio |= mime != null && mime.startsWith("audio/");
                        if (f.containsKey(MediaFormat.KEY_HEIGHT)) height = f.getInteger(MediaFormat.KEY_HEIGHT);
                        if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = Math.max(durationUs, f.getLong(MediaFormat.KEY_DURATION));
                    }
                    assertEquals(!choice.audioOnly, video); assertTrue(audio);
                    if (!choice.audioOnly && choice.height > 0) assertEquals(choice.height, height);
                    if (media.durationSeconds > 0) assertTrue("Downloaded full duration: " + durationUs + " expected " + media.durationSeconds,
                            durationUs / 1_000_000d >= media.durationSeconds * 0.98);
                    android.util.Log.i("YtDlpAcceptance", "Verified " + choice.selector + " bytes=" + file.length() + " height=" + height + " audio=" + audio + " durationUs=" + durationUs);
                } finally { extractor.release(); }
            }
        } finally { delete(work); }
    }
    private void download(String defaultUrl) throws Exception {
        String url = InstrumentationRegistry.getArguments().getString("mediaUrl", defaultUrl);
        File work = new File(context.getCacheDir(), "yt-dlp-test-" + UUID.randomUUID());
        String id = UUID.randomUUID().toString();
        try {
            YtDlpMedia media = YtDlpEngine.resolve(context, url, work, id);
            java.util.List<YtDlpMedia.Choice> choices = media.videos.isEmpty() ? media.audios : media.videos;
            assertFalse("Expected downloadable choices", choices.isEmpty());
            YtDlpMedia.Choice choice = choices.get(choices.size() - 1);
            File file = YtDlpEngine.download(context, url, choice, work, id, (p, eta, line) -> {});
            assertTrue(file.length() > 1024);
            MediaExtractor extractor = new MediaExtractor();
            boolean video = false, audio = false;
            long durationUs = 0; int height = 0;
            try {
                extractor.setDataSource(file.getAbsolutePath());
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    String mime = format.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/")) video = true;
                    if (mime != null && mime.startsWith("audio/")) audio = true;
                    if (format.containsKey(MediaFormat.KEY_DURATION)) durationUs = Math.max(durationUs, format.getLong(MediaFormat.KEY_DURATION));
                    if (format.containsKey(MediaFormat.KEY_HEIGHT)) height = format.getInteger(MediaFormat.KEY_HEIGHT);
                }
                assertEquals("Expected video track presence", !choice.audioOnly, video);
                assertTrue("Downloaded media has an audio track", audio);
                if (choice.height > 0) assertEquals(choice.height, height);
                assertTrue("Full source duration", durationUs >= media.durationSeconds * 980000);
                android.util.Log.i("YtDlpAcceptance", "Link verified " + url + " bytes=" + file.length() + " height=" + height + " durationUs=" + durationUs);
            } finally { extractor.release(); }
        } finally { delete(work); }
    }
    private static void delete(File file) {
        File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
