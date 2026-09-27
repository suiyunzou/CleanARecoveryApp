package com.example.cleanrecovery.music.data;

import org.junit.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class LyricsRepositoryTest {
    private static SongInfo song(String hash) {
        SongInfo song = new SongInfo();
        song.hash = hash;
        return song;
    }
    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }

    @Test public void pageAndOverlayShareOneFetchAndDeliveryUsesUiExecutor() {
        Queue worker = new Queue();
        Queue ui = new Queue();
        AtomicInteger calls = new AtomicInteger();
        Lyrics lyrics = Lyrics.parse("[00:01]one");
        LyricsRepository repository = new LyricsRepository(song -> { calls.incrementAndGet(); return lyrics; }, worker, ui);
        List<Lyrics> received = new ArrayList<>();
        repository.load(song("a"), (result, failed) -> { assertFalse(failed); received.add(result); });
        repository.load(song("a"), (result, failed) -> received.add(result));
        assertEquals(1, worker.tasks.size());
        worker.runAll();
        assertTrue(received.isEmpty());
        ui.runAll();
        assertEquals(2, received.size());
        assertSame(lyrics, received.get(0));
        repository.load(song("a"), (result, failed) -> received.add(result));
        ui.runAll();
        assertEquals(3, received.size());
        assertEquals(1, calls.get());
    }

    @Test public void failureIsRetryableAndSongResultsDoNotMix() {
        AtomicInteger attempts = new AtomicInteger();
        LyricsRepository repository = new LyricsRepository(song -> {
            if (attempts.incrementAndGet() == 1) throw new java.io.IOException("offline");
            return Lyrics.parse("[00:01]" + song.hash);
        }, Runnable::run, Runnable::run);
        repository.load(song("a"), (result, failed) -> { assertTrue(failed); assertTrue(result.isEmpty()); });
        repository.load(song("b"), (result, failed) -> assertEquals("b", result.lines().get(0).text));
        repository.load(song("a"), (result, failed) -> { assertFalse(failed); assertEquals("a", result.lines().get(0).text); });
        assertEquals(3, attempts.get());
    }

    @Test public void missingHashDoesNotRequestNetworkAndCacheIsBounded() {
        AtomicInteger calls = new AtomicInteger();
        LyricsRepository repository = new LyricsRepository(song -> { calls.incrementAndGet(); return Lyrics.empty(); },
                Runnable::run, Runnable::run);
        repository.load(song(null), (result, failed) -> assertTrue(result.isEmpty()));
        repository.load(song(""), (result, failed) -> assertTrue(result.isEmpty()));
        assertEquals(0, calls.get());
        for (int i = 0; i < 17; i++) repository.load(song("s" + i), (result, failed) -> {});
        repository.load(song("s16"), (result, failed) -> {});
        assertEquals(17, calls.get());
        repository.load(song("s0"), (result, failed) -> {});
        assertEquals(18, calls.get());
    }
}
