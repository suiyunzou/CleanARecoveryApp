package com.example.cleanrecovery.music.player;

import com.example.cleanrecovery.music.data.SongInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Playback copies are independent of saved playlists; duplicate songs are distinct entries. */
public final class PlaybackQueue {
    private static final int HISTORY_LIMIT = 3;
    private static final class Entry {
        SongInfo song;
        boolean pending;
        Entry(SongInfo song, boolean pending) { this.song = song; this.pending = pending; }
    }

    public static final class Snapshot {
        private List<Entry> entries = new ArrayList<>();
        private int index;
        private String source;
        private String name;
        private String tag;
        public List<SongInfo> songs() {
            List<SongInfo> songs = new ArrayList<>();
            for (Entry entry : entries) songs.add(entry.song);
            return songs;
        }
        public String sourceName() { return name; }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Snapshot> history = new ArrayList<>();
    private final List<Entry> candidates = new ArrayList<>();
    private final List<Entry> randomHistory = new ArrayList<>();
    private final Random random;
    private int randomCursor = -1;
    private int index = -1;
    private String source = "UNKNOWN";
    private String name = "";
    private String tag;

    public PlaybackQueue() { this(new Random()); }
    PlaybackQueue(Random random) { this.random = random; }
    public int size() { return entries.size(); }
    public int index() { return index; }
    public SongInfo current() { return index < 0 ? null : entries.get(index).song; }
    public String source() { return source; }
    public String sourceName() { return name; }
    public String sourceTag() { return tag; }
    public void setSourceTag(String tag) { this.tag = tag; }
    public boolean isPending(int at) { return at >= 0 && at < size() && entries.get(at).pending; }
    public List<SongInfo> songs() { return snapshot().songs(); }
    public List<Snapshot> history() { return new ArrayList<>(history); }

    public Snapshot snapshot() {
        Snapshot snapshot = new Snapshot();
        for (Entry entry : entries) snapshot.entries.add(new Entry(entry.song, entry.pending));
        snapshot.index = index;
        snapshot.source = source;
        snapshot.name = name;
        snapshot.tag = tag;
        return snapshot;
    }

    public void restore(Snapshot snapshot, List<Snapshot> savedHistory) {
        entries.clear();
        for (Entry entry : snapshot.entries) entries.add(new Entry(entry.song, entry.pending));
        index = entries.isEmpty() ? -1 : Math.max(0, Math.min(snapshot.index, size() - 1));
        source = snapshot.source == null ? "UNKNOWN" : snapshot.source;
        name = snapshot.name == null ? "" : snapshot.name;
        tag = snapshot.tag;
        history.clear();
        if (savedHistory != null) history.addAll(savedHistory.subList(0, Math.min(HISTORY_LIMIT, savedHistory.size())));
        resetRandom();
    }

    private void archive() {
        if (entries.isEmpty()) return;
        history.add(0, snapshot());
        if (history.size() > HISTORY_LIMIT) history.remove(history.size() - 1);
    }

    public void replace(List<SongInfo> songs, int start, String source, String name, String tag) {
        if (songs.isEmpty()) return;
        boolean sameQueue = size() == songs.size()
                && java.util.Objects.equals(this.source, source)
                && java.util.Objects.equals(this.name, name)
                && java.util.Objects.equals(this.tag, tag);
        for (int i = 0; sameQueue && i < size(); i++) {
            sameQueue = !entries.get(i).pending && sameSong(entries.get(i).song, songs.get(i));
        }
        if (!sameQueue) archive();
        entries.clear();
        for (SongInfo song : songs) entries.add(new Entry(song, false));
        index = Math.max(0, Math.min(start, size() - 1));
        this.source = source == null ? "UNKNOWN" : source;
        this.name = name == null ? "" : name;
        this.tag = tag;
        resetRandom();
    }

    public void playHistory(Snapshot snapshot, int start) {
        if (start < 0 || start >= snapshot.entries.size()) return;
        history.remove(snapshot);
        archive();
        restore(snapshot, history());
        select(start);
    }

    /** Search deduplicates ordinary copies but preserves explicitly scheduled copies. */
    public void insertSearch(SongInfo song, String searchName) {
        if (entries.isEmpty()) {
            replace(java.util.Collections.singletonList(song), 0, "SEARCH", searchName, null);
            return;
        }
        int insertAt = index + 1;
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            if (!entry.pending && sameSong(entry.song, song)) {
                entries.remove(i);
                if (i < insertAt) insertAt--;
            }
        }
        entries.add(insertAt, new Entry(song, false));
        index = insertAt;
        resetRandom();
    }

    static boolean sameSong(SongInfo a, SongInfo b) {
        if (a.mixSongId != null && !a.mixSongId.isEmpty() && b.mixSongId != null && !b.mixSongId.isEmpty())
            return a.mixSongId.equals(b.mixSongId);
        if (a.hash != null && !a.hash.isEmpty() && b.hash != null && !b.hash.isEmpty())
            return a.hash.equalsIgnoreCase(b.hash);
        if (a.localPath != null && !a.localPath.isEmpty() && b.localPath != null && !b.localPath.isEmpty())
            return a.localPath.equals(b.localPath);
        return a.equals(b) && a.duration == b.duration;
    }

    public void insertNext(List<SongInfo> songs) {
        if (songs.isEmpty()) return;
        if (entries.isEmpty()) {
            replace(songs, 0, "UNKNOWN", "", null);
            for (int i = 1; i < size(); i++) entries.get(i).pending = true;
            return;
        }
        int at = index + 1;
        while (at < size() && entries.get(at).pending) at++;
        for (SongInfo song : songs) entries.add(at++, new Entry(song, true));
        resetRandom();
    }

    public void append(List<SongInfo> songs) {
        for (SongInfo song : songs) entries.add(new Entry(song, false));
        if (index < 0 && !entries.isEmpty()) index = 0;
        resetRandom();
    }

    public void select(int at) {
        if (at < 0 || at >= size()) return;
        index = at;
        entries.get(index).pending = false;
        resetRandom();
    }

    public void next(boolean shuffle, boolean repeatCurrent) {
        if (entries.isEmpty() || repeatCurrent) return;
        if (isPending(index + 1)) {
            index++;
            entries.get(index).pending = false;
            recordRandom(entries.get(index));
        } else if (!shuffle) {
            select((index + 1) % size());
        } else if (randomCursor + 1 < randomHistory.size()) {
            index = entries.indexOf(randomHistory.get(++randomCursor));
        } else {
            if (candidates.isEmpty()) {
                candidates.addAll(entries);
                if (size() > 1) candidates.remove(entries.get(index));
            }
            Entry next = candidates.remove(random.nextInt(candidates.size()));
            index = entries.indexOf(next);
            recordRandom(next);
        }
        entries.get(index).pending = false;
    }

    public void previous(boolean shuffle) {
        if (entries.isEmpty()) return;
        if (shuffle) {
            if (randomCursor > 0) index = entries.indexOf(randomHistory.get(--randomCursor));
            else if (size() > 1) {
                if (candidates.isEmpty()) {
                    candidates.addAll(entries);
                    candidates.remove(entries.get(index));
                }
                Entry previous = candidates.remove(random.nextInt(candidates.size()));
                randomHistory.add(0, previous);
                if (randomHistory.size() > size() * 3) randomHistory.remove(randomHistory.size() - 1);
                index = entries.indexOf(previous);
            }
        } else {
            select((index - 1 + size()) % size());
        }
        entries.get(index).pending = false;
    }

    private void recordRandom(Entry entry) {
        while (randomHistory.size() > randomCursor + 1) randomHistory.remove(randomHistory.size() - 1);
        candidates.remove(entry);
        randomHistory.add(entry);
        if (randomHistory.size() > Math.max(1, size() * 3)) randomHistory.remove(0);
        randomCursor = randomHistory.size() - 1;
    }

    public void resetRandom() {
        candidates.clear();
        candidates.addAll(entries);
        randomHistory.clear();
        randomCursor = -1;
        if (index >= 0) recordRandom(entries.get(index));
    }

    public void remove(int at) {
        if (at < 0 || at >= size()) return;
        entries.remove(at);
        if (entries.isEmpty()) index = -1;
        else if (at < index) index--;
        else if (index >= size()) index = 0;
        if (index >= 0) entries.get(index).pending = false;
        resetRandom();
    }

    public void move(int from, int to) {
        if (from < 0 || to < 0 || from >= size() || to >= size() || from == to) return;
        Entry current = entries.get(index);
        entries.add(to, entries.remove(from));
        index = entries.indexOf(current);
        resetRandom();
    }

    public void clear() {
        entries.clear();
        index = -1;
        source = "UNKNOWN";
        name = "";
        tag = null;
        resetRandom();
    }
}
