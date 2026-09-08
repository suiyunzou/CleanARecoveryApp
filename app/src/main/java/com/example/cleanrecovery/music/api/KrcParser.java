package com.example.cleanrecovery.music.api;

import com.example.cleanrecovery.music.data.Lyrics;
import com.example.cleanrecovery.util.Base64Compat;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses the downloaded KRC body without discarding word timing or language metadata. */
public final class KrcParser {
    private static final Pattern LINE = Pattern.compile("^\\[(\\d+),(\\d+)](.*)$");
    private static final Pattern WORD = Pattern.compile("<(\\d+),(\\d+),\\d+>([^<]*)");
    private static final Pattern LANGUAGE = Pattern.compile("\\[language:([^\\]]+)]");

    private KrcParser() {}

    public static Lyrics parse(String raw) {
        if (raw == null || raw.isEmpty()) return Lyrics.empty();
        List<String> translations = translations(raw);
        List<Lyrics.Line> lines = new ArrayList<>();
        int lineIndex = 0;
        for (String source : raw.split("\n")) {
            Matcher line = LINE.matcher(source.trim());
            if (!line.matches()) continue;
            String translation = lineIndex < translations.size() ? translations.get(lineIndex) : "";
            lineIndex++;
            try {
                long startMs = Long.parseLong(line.group(1));
                Matcher word = WORD.matcher(line.group(3));
                StringBuilder text = new StringBuilder();
                List<Lyrics.Word> words = new ArrayList<>();
                while (word.find()) {
                    int start = text.length();
                    text.append(word.group(3));
                    words.add(new Lyrics.Word(startMs + Long.parseLong(word.group(1)),
                            Long.parseLong(word.group(2)), start, text.length()));
                }
                if (!words.isEmpty()) lines.add(new Lyrics.Line(startMs, text.toString(), translation, words));
            } catch (NumberFormatException ignored) {
                // A malformed row must not prevent subsequent rows from being displayed.
            }
        }
        return Lyrics.fromLines(lines, raw);
    }

    private static List<String> translations(String raw) {
        List<String> result = new ArrayList<>();
        Matcher language = LANGUAGE.matcher(raw);
        if (!language.find()) return result;
        try {
            String json = new String(Base64Compat.decode(language.group(1)), StandardCharsets.UTF_8);
            JsonArray content = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("content");
            for (JsonElement entry : content) {
                JsonObject item = entry.getAsJsonObject();
                if (!item.has("type") || item.get("type").getAsInt() != 1) continue;
                for (JsonElement row : item.getAsJsonArray("lyricContent")) {
                    StringBuilder text = new StringBuilder();
                    for (JsonElement part : row.getAsJsonArray()) {
                        if (text.length() > 0) text.append('\n');
                        text.append(part.getAsString().trim());
                    }
                    result.add(text.toString().trim());
                }
                break;
            }
        } catch (RuntimeException ignored) {
            // Translation metadata is optional; retain the original timed lyrics.
            result.clear();
        }
        return result;
    }
}
