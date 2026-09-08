package com.example.cleanrecovery.recovery;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class RecoveryStateTest {
    private RecoveryItem existingItem() {
        return new RecoveryItem(
                RecoveryType.IMAGE,
                "photo.jpg",
                "/storage/photo.jpg",
                100L,
                0L,
                100,
                100,
                false
        );
    }

    private RecoveryItem indexOnlyItem() {
        return new RecoveryItem(
                RecoveryType.IMAGE,
                "cache.jpg",
                "content://media/external/images/media/1",
                100L,
                0L,
                100,
                100,
                true,
                RecoverySourceKind.MEDIASTORE_STALE_RECORD,
                false,
                0L
        );
    }

    @Test
    public void filterRecoverableShowsOnlyItemsWithData() {
        RecoveryState state = new RecoveryState();
        state.addAll(Arrays.asList(existingItem(), indexOnlyItem()));

        state.setFilter(RecoveryState.FilterMode.RECOVERABLE);

        assertEquals(1, state.getVisibleCount());
        assertTrue(state.getVisibleItems().get(0).recoverable);
    }

    @Test
    public void filterIndexOnlyShowsOnlyItemsWithoutData() {
        RecoveryState state = new RecoveryState();
        state.addAll(Arrays.asList(existingItem(), indexOnlyItem()));

        state.setFilter(RecoveryState.FilterMode.INDEX_ONLY);

        assertEquals(1, state.getVisibleCount());
        assertFalse(state.getVisibleItems().get(0).recoverable);
    }

    @Test
    public void removeAllDropsProcessedItems() {
        RecoveryState state = new RecoveryState();
        RecoveryItem first = existingItem();
        RecoveryItem second = indexOnlyItem();
        state.addAll(Arrays.asList(first, second));

        int removed = state.removeAll(Collections.singletonList(second));

        assertEquals(1, removed);
        assertEquals(1, state.getAllCount());
        assertFalse(state.getAllItems().contains(second));
    }

    @Test
    public void setAllSelectedUpdatesVisibleItems() {
        RecoveryState state = new RecoveryState();
        state.addAll(Collections.singletonList(existingItem()));

        state.setAllSelected(true);

        assertEquals(1, state.getSelectedCount());
        assertEquals(1, state.getSelectedItems().size());
    }

    @Test
    public void clearSelectionResetsSelectedItems() {
        RecoveryState state = new RecoveryState();
        state.addAll(Collections.singletonList(existingItem()));
        state.setAllSelected(true);

        state.setAllSelected(false);

        assertEquals(0, state.getSelectedCount());
        assertTrue(state.getSelectedItems().isEmpty());
    }

    @Test
    public void clearRemovesAllItems() {
        RecoveryState state = new RecoveryState();
        state.addAll(Collections.singletonList(existingItem()));

        state.clear();

        assertEquals(0, state.getAllCount());
        assertEquals(0, state.getVisibleCount());
    }

    @Test
    public void addAllSkipsDuplicatePaths() {
        RecoveryState state = new RecoveryState();
        RecoveryItem first = existingItem();
        RecoveryItem duplicate = new RecoveryItem(
                RecoveryType.IMAGE,
                "copy.jpg",
                "/storage/photo.jpg",
                200L,
                1L,
                50,
                50,
                false,
                RecoverySourceKind.KNOWN_CACHE_BLOB
        );

        state.addAll(Arrays.asList(first, duplicate));

        assertEquals(1, state.getAllCount());
        assertEquals(1, state.getDuplicateSkipCount());
    }

    @Test
    public void addAllAcceptsLargeBatches() {
        RecoveryState state = new RecoveryState();
        RecoveryItem[] items = new RecoveryItem[10_005];
        for (int index = 0; index < items.length; index++) {
            items[index] = new RecoveryItem(
                    RecoveryType.IMAGE,
                    "photo-" + index + ".jpg",
                    "/storage/photo-" + index + ".jpg",
                    100L,
                    0L,
                    100,
                    100,
                    false
            );
        }

        state.addAll(Arrays.asList(items));

        assertEquals(items.length, state.getAllCount());
    }
}
