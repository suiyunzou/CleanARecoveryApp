package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.Lyrics;
import com.google.gson.JsonParser;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public class KrcParserTest {
    @Test public void wordsDoNotHighlightAheadOfPlaybackAndRewindClearsThem() {
        Lyrics lyrics = KrcParser.parse("[15000,1800]<0,220,0>Why<220,0,0> <220,270,0>do<490,0,0> <490,180,0>we");
        Lyrics.Line line = lyrics.lines().get(0);
        assertEquals("Why do we", line.text);
        assertEquals(0, line.sungTextEnd(14999));
        assertEquals("Why do", line.text.substring(0, line.sungTextEnd(15300)));
        assertEquals(line.text.length(), line.sungTextEnd(16000));
        assertEquals("Why", line.text.substring(0, line.sungTextEnd(15000)));
        assertEquals(270, line.words.get(2).durationMs);
        assertEquals(-1, lyrics.indexOfActive(14999));
    }

    @Test public void translationsKeepRowAlignmentIncludingEmptyMetadataRows() {
        String json = "{\"content\":[{\"type\":0,\"lyricContent\":[[\"romanization\"]]},"
                + "{\"type\":1,\"lyricContent\":[[\" \"],[\"中文第一行\",\"中文第二行\"]]}]}";
        String raw = "[language:" + Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8))
                + "]\n[0,10]<0,10,0>Credit\n[1000,500]<0,500,0>Hello";
        Lyrics lyrics = KrcParser.parse(raw);
        assertEquals("", lyrics.lines().get(0).translation);
        assertEquals("中文第一行\n中文第二行", lyrics.lines().get(1).translation);
        assertEquals(raw, lyrics.raw);
    }

    @Test public void brokenOptionalTranslationDoesNotLoseOriginalLyrics() {
        Lyrics lyrics = KrcParser.parse("[language:not-base64]\n[100,200]<0,200,0>你好");
        assertEquals("你好", lyrics.lines().get(0).text);
        assertEquals("", lyrics.lines().get(0).translation);
        assertEquals(2, lyrics.lines().get(0).sungTextEnd(100));
    }

    @Test public void climaxUsesMillisecondsAndMissingDataNeverInventsAPoint() {
        assertEquals(30800, climax("{\"status\":1,\"data\":[{\"start_time\":\"30800\"}]}"));
        assertEquals(0, climax("{\"status\":1,\"data\":[{\"start_time\":\"0\"}]}"));
        assertEquals(-1, climax("{\"status\":1,\"data\":[]}"));
        assertEquals(-1, climax("{\"status\":0,\"data\":[{\"start_time\":1}]}"));
        assertEquals(-1, climax("{\"status\":1,\"data\":[{}]}"));
    }

    private long climax(String json) {
        return KugouDataSource.parseClimaxStartMs(JsonParser.parseString(json).getAsJsonObject());
    }
}
