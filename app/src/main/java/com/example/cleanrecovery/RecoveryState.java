package com.example.cleanrecovery;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public final class RecoveryState {
    public enum FilterMode {
        ALL,
        EXISTING,
        DELETED
    }

    private final ArrayList<RecoveryItem> allItems = new ArrayList<>();
    private final ArrayList<RecoveryItem> visibleItems = new ArrayList<>();
    private final RecoveryDeduper deduper = new RecoveryDeduper();
    private FilterMode currentFilter = FilterMode.ALL;

    public void clear() {
        allItems.clear();
        visibleItems.clear();
        deduper.clear();
    }

    public boolean addAll(Collection<RecoveryItem> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }

        int added = 0;
        for (RecoveryItem item : items) {
            if (deduper.isDuplicate(item)) {
                continue;
            }
            allItems.add(item);
            if (matchesFilter(item)) {
                visibleItems.add(item);
            }
            added++;
        }
        return added > 0;
    }

    public void setFilter(FilterMode filter) {
        currentFilter = filter;
        rebuildVisibleItems();
    }

    public FilterMode getFilter() {
        return currentFilter;
    }

    public List<RecoveryItem> getVisibleItems() {
        return visibleItems;
    }

    public List<RecoveryItem> getAllItems() {
        return Collections.unmodifiableList(allItems);
    }

    public int countByType(RecoveryType type) {
        int count = 0;
        for (RecoveryItem item : allItems) {
            if (item.type == type) {
                count++;
            }
        }
        return count;
    }

    public int getAllCount() {
        return allItems.size();
    }

    public int getVisibleCount() {
        return visibleItems.size();
    }

    public int getSelectedCount() {
        int selected = 0;
        for (RecoveryItem item : visibleItems) {
            if (item.selected) {
                selected++;
            }
        }
        return selected;
    }

    public List<RecoveryItem> getSelectedItems() {
        ArrayList<RecoveryItem> selected = new ArrayList<>();
        for (RecoveryItem item : visibleItems) {
            if (item.selected) {
                selected.add(item);
            }
        }
        return selected;
    }

    public void setAllSelected(boolean selected) {
        for (RecoveryItem item : visibleItems) {
            item.selected = selected;
        }
    }

    public int getDuplicateSkipCount() {
        return deduper.getDuplicateCount();
    }

    public boolean matchesFilter(RecoveryItem item) {
        if (currentFilter == FilterMode.EXISTING) {
            return !item.suspectedDeleted;
        }
        if (currentFilter == FilterMode.DELETED) {
            return item.suspectedDeleted;
        }
        return true;
    }

    private void rebuildVisibleItems() {
        visibleItems.clear();
        for (RecoveryItem item : allItems) {
            if (matchesFilter(item)) {
                visibleItems.add(item);
            }
        }
    }
}
