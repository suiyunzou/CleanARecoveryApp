package com.example.cleanrecovery.ytdlp;

import com.example.cleanrecovery.extractor.ExtractorResult;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SmartFormatSelectorTest {

    private final SmartFormatSelector selector = new SmartFormatSelector();

    @Test
    public void autoPrefersCombinedWhenCombinedIsAtLeast1080p() {
        ExtractorResult result = result(
                combined("720", 720),
                combined("1080", 1080),
                video("2160v", 2160),
                audio("128a", 128)
        );

        RequestedFormats selected = selector.select(result, QualityHint.AUTO);

        assertNotNull(selected);
        assertFalse(selected.needsMerge);
        assertEquals("1080", selected.primary().formatId);
    }

    @Test
    public void autoSelectsVideoAndAudioWhenCombinedIsBelow1080p() {
        ExtractorResult result = result(
                combined("720", 720),
                video("1080v", 1080),
                audio("160a", 160),
                audio("64a", 64)
        );

        RequestedFormats selected = selector.select(result, QualityHint.AUTO);

        assertNotNull(selected);
        assertTrue(selected.needsMerge);
        assertEquals("1080v", selected.primary().formatId);
        assertEquals("160a", selected.audio().formatId);
    }

    @Test
    public void explicitQualityDoesNotPickCombinedAboveTarget() {
        ExtractorResult result = result(
                combined("2160", 2160),
                video("1080v", 1080),
                video("720v", 720),
                audio("128a", 128)
        );

        RequestedFormats selected = selector.select(result, QualityHint.P1080);

        assertNotNull(selected);
        assertTrue(selected.needsMerge);
        assertEquals("1080v", selected.primary().formatId);
    }

    @Test
    public void audioOnlyPicksBestAudioTrack() {
        ExtractorResult result = result(
                combined("1080", 1080),
                audio("96a", 96),
                audio("192a", 192)
        );

        RequestedFormats selected = selector.select(result, QualityHint.AUDIO_ONLY);

        assertNotNull(selected);
        assertFalse(selected.needsMerge);
        assertEquals("192a", selected.primary().formatId);
    }

    private static ExtractorResult result(ExtractorResult.Format... formats) {
        return new ExtractorResult("id", "title", "uploader",
                Arrays.asList(formats), "title", "mp4",
                0L, null, null, null, "https://example.test/v/id", "Test");
    }

    private static ExtractorResult.Format combined(String id, int height) {
        return new ExtractorResult.Format.Builder()
                .url("https://example.test/" + id + ".mp4")
                .ext("mp4")
                .formatId(id)
                .quality(height)
                .height(height)
                .width(height * 16 / 9)
                .vcodec("avc1")
                .acodec("mp4a")
                .tbr(height)
                .build();
    }

    private static ExtractorResult.Format video(String id, int height) {
        return new ExtractorResult.Format.Builder()
                .url("https://example.test/" + id + ".mp4")
                .ext("mp4")
                .formatId(id)
                .quality(height)
                .height(height)
                .width(height * 16 / 9)
                .vcodec("avc1")
                .acodec("none")
                .tbr(height)
                .build();
    }

    private static ExtractorResult.Format audio(String id, int bitrate) {
        return new ExtractorResult.Format.Builder()
                .url("https://example.test/" + id + ".m4a")
                .ext("m4a")
                .formatId(id)
                .quality(0)
                .height(0)
                .width(0)
                .vcodec("none")
                .acodec("mp4a")
                .tbr(bitrate)
                .httpHeaders(Collections.singletonMap("Accept", "*/*"))
                .build();
    }
}
