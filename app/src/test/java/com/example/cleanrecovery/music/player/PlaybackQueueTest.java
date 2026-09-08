package com.example.cleanrecovery.music.player;

import com.example.cleanrecovery.music.data.SongInfo;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/** Scenarios recorded in artifacts/queue-exploration, independent of network/audio timing. */
public class PlaybackQueueTest {
    private final SongInfo a = song("a"), b = song("b"), c = song("c"), x = song("x");
    private final PlaybackQueue queue = new PlaybackQueue(new Random(42));
    private static SongInfo song(String hash) {
        SongInfo song = new SongInfo();
        song.hash = hash; song.title = hash; song.artist = "artist";
        return song;
    }
    private void start() { queue.replace(Arrays.asList(a, b, c), 0, "LOCAL_PLAYLIST", "My playlist", "local:test"); }

    @Test public void searchPreservesQueueSourceAndPlaysInsertedEntry() {
        start(); queue.insertSearch(x, "Search x");
        assertEquals(Arrays.asList(a, x, b, c), queue.songs());
        assertSame(x, queue.current());
        assertEquals("My playlist", queue.sourceName());
        assertEquals("local:test", queue.sourceTag());
    }

    @Test public void pendingCopiesSurviveSearchDeduplication() {
        start(); queue.insertNext(Collections.singletonList(b));
        queue.insertSearch(song("b"), "Search b");
        assertEquals(Arrays.asList(a, b, b, c), queue.songs());
        assertEquals(1, queue.index());
        assertFalse(queue.isPending(1));
        assertTrue(queue.isPending(2));
    }

    @Test public void searchSameCurrentDoesNotLeaveIndexPointingToAnotherSong() {
        start(); queue.select(1); queue.insertSearch(song("b"), "b");
        assertEquals(Arrays.asList(a, b, c), queue.songs());
        assertEquals(1, queue.index());
    }

    @Test public void searchRemovingEarlierCopyKeepsCurrentAnchor() {
        start(); queue.select(2); queue.insertSearch(a, "a");
        assertEquals(Arrays.asList(b, c, a), queue.songs());
        assertEquals(2, queue.index());
    }

    @Test public void distinctRecordingIdsAreNotMergedByTitle() {
        a.mixSongId = "1"; b.mixSongId = "2"; b.title = a.title;
        start(); queue.insertSearch(b, "b");
        assertEquals(Arrays.asList(a, b, c), queue.songs());
    }

    @Test public void emptySearchCreatesOneSongQueueWithSearchSource() {
        queue.insertSearch(x, "Search x");
        assertEquals(Collections.singletonList(x), queue.songs());
        assertEquals("SEARCH", queue.source());
        assertEquals("Search x", queue.sourceName());
    }

    @Test public void playNextIsFifoAndDoesNotRemoveOriginalDuplicates() {
        start(); queue.insertNext(Collections.singletonList(b)); queue.insertNext(Collections.singletonList(c));
        assertEquals(Arrays.asList(a, b, c, b, c), queue.songs());
        assertSame(a, queue.current());
        assertTrue(queue.isPending(1)); assertTrue(queue.isPending(2));
    }

    @Test public void pendingTakesPriorityInShuffleAndRemainsAfterConsumption() {
        start(); queue.insertNext(Arrays.asList(x, b));
        queue.next(true, false); assertSame(x, queue.current());
        assertFalse(queue.isPending(1));
        queue.next(true, false); assertSame(b, queue.current());
        assertFalse(queue.isPending(2));
        assertEquals(Arrays.asList(a, x, b, b, c), queue.songs());
    }

    @Test public void naturalRepeatWaitsButManualNextConsumesPending() {
        start(); queue.insertNext(Collections.singletonList(x));
        queue.next(false, true);
        assertSame(a, queue.current()); assertTrue(queue.isPending(1));
        queue.next(false, false);
        assertSame(x, queue.current()); assertFalse(queue.isPending(1));
    }

    @Test public void selectingAQueuedDuplicateConsumesOnlyThatEntry() {
        start(); queue.insertNext(Arrays.asList(b, b)); queue.select(2);
        assertTrue(queue.isPending(1)); assertFalse(queue.isPending(2));
        assertEquals(2, queue.index());
    }

    @Test public void emptyNextStartsFirstAndSchedulesTheRest() {
        queue.insertNext(Arrays.asList(a, b, c));
        assertSame(a, queue.current()); assertFalse(queue.isPending(0));
        assertTrue(queue.isPending(1)); assertTrue(queue.isPending(2));
    }

    @Test public void sequentialWrapsAtTheEnd() {
        start(); queue.select(2); queue.next(false, false);
        assertSame(a, queue.current());
        queue.previous(false); assertSame(c, queue.current());
    }

    @Test public void shuffleVisitsEntriesBeforeRepeatingAndPreviousRetracesHistory() {
        start(); HashSet<SongInfo> seen = new HashSet<>(); seen.add(queue.current());
        queue.next(true, false); SongInfo second = queue.current(); seen.add(second);
        queue.next(true, false); SongInfo third = queue.current(); seen.add(third);
        assertEquals(3, seen.size());
        queue.previous(true); assertSame(second, queue.current());
        queue.next(true, false); assertSame(third, queue.current());
        for (int i = 0; i < 100; i++) {
            SongInfo previous = queue.current(); queue.next(true, false);
            assertNotSame(previous, queue.current());
        }
    }

    @Test public void shuffleMutationNeverReturnsRemovedEntriesOrInvalidIndexes() {
        start(); queue.next(true, false); queue.remove(1); queue.move(0, 1);
        for (int i = 0; i < 20; i++) {
            queue.next(true, false);
            assertTrue(queue.index() >= 0 && queue.index() < queue.size());
            assertTrue(queue.songs().contains(queue.current()));
        }
    }

    @Test public void singleEntryShuffleRemainsPlayable() {
        queue.insertNext(Collections.singletonList(a));
        for (int i = 0; i < 10; i++) { queue.next(true, false); assertSame(a, queue.current()); }
    }

    @Test public void shufflePreviousAtHistoryStartCanReturnForwardToOriginalEntry() {
        start(); queue.previous(true);
        assertNotSame(a, queue.current());
        queue.next(true, false); assertSame(a, queue.current());
    }

    @Test public void replayingUnchangedPlaylistDoesNotFloodHistory() {
        start(); start(); start();
        assertTrue(queue.history().isEmpty());
    }

    @Test public void historyRetainsOnlyRecentSnapshots() {
        start();
        for (int i = 0; i < 8; i++) queue.replace(Collections.singletonList(x), 0, "SEARCH", "Search " + i, null);
        assertEquals(3, queue.history().size());
        assertEquals("Search 6", queue.history().get(0).sourceName());
    }

    @Test public void deletingAndSortingQueueNeverMutatesSourcePlaylist() {
        List<SongInfo> original = new java.util.ArrayList<>(Arrays.asList(a, b, c));
        queue.replace(original, 1, "LOCAL_PLAYLIST", "list", null);
        queue.move(0, 2); assertSame(b, queue.current()); assertEquals(0, queue.index());
        queue.remove(1); assertSame(b, queue.current());
        assertEquals(Arrays.asList(a, b, c), original);
    }

    @Test public void deletingCurrentSelectsSuccessorAndDeletingLastClears() {
        start(); queue.select(1); queue.remove(1); assertSame(c, queue.current());
        queue.remove(1); assertSame(a, queue.current());
        queue.remove(0); assertNull(queue.current()); assertEquals(-1, queue.index());
    }

    @Test public void historyRestoresPlaybackCopyIncludingDuplicateAndPendingEntries() {
        start(); queue.insertNext(Collections.singletonList(b));
        queue.replace(Collections.singletonList(x), 0, "REMOTE_PLAYLIST", "other", "remote:2");
        PlaybackQueue.Snapshot previous = queue.history().get(0);
        assertEquals(Arrays.asList(a, b, b, c), previous.songs());
        queue.playHistory(previous, 2);
        assertEquals(Arrays.asList(a, b, b, c), queue.songs());
        assertEquals(2, queue.index()); assertTrue(queue.isPending(1));
        assertEquals("My playlist", queue.sourceName());
        assertEquals(Collections.singletonList(x), queue.history().get(0).songs());
    }

    @Test public void reloadingSourceReplacesTemporaryEntriesAndOrder() {
        start(); queue.insertNext(Collections.singletonList(x)); queue.move(0, 3);
        start(); assertEquals(Arrays.asList(a, b, c), queue.songs());
        for (int i = 0; i < queue.size(); i++) assertFalse(queue.isPending(i));
    }

    @Test public void savedSnapshotsDoNotAliasMutableQueueEntryFlags() {
        start(); queue.insertNext(Collections.singletonList(x));
        PlaybackQueue.Snapshot saved = queue.snapshot();
        queue.next(false, false);
        Gson gson = new Gson();
        PlaybackQueue.Snapshot decoded = gson.fromJson(gson.toJson(saved), PlaybackQueue.Snapshot.class);
        queue.restore(decoded, Collections.emptyList());
        assertTrue(queue.isPending(1)); assertEquals(0, queue.index());
        assertEquals(Arrays.asList(a, x, b, c), queue.songs());
    }

    @Test public void clearPersistsAnEmptyCurrentQueueWithoutDestroyingHistory() {
        start(); queue.replace(Collections.singletonList(x), 0, "SEARCH", "x", null);
        queue.clear();
        PlaybackQueue restored = new PlaybackQueue();
        restored.restore(queue.snapshot(), queue.history());
        assertEquals(0, restored.size()); assertNull(restored.current());
        assertEquals(1, restored.history().size());
    }
}
