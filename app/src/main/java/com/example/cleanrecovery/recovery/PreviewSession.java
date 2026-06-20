package com.example.cleanrecovery.recovery;

import java.util.ArrayList;
import java.util.List;

public final class PreviewSession {
    private static List<RecoveryItem> items = new ArrayList<>();
    private static int index;

    private PreviewSession() {
    }

    public static void setItems(List<RecoveryItem> visibleItems, int startIndex) {
        items = new ArrayList<>(visibleItems);
        index = Math.max(0, Math.min(startIndex, Math.max(0, items.size() - 1)));
    }

    public static RecoveryItem currentItem() {
        if (items.isEmpty()) {
            return null;
        }
        return items.get(index);
    }

    public static int currentIndex() {
        return index;
    }

    public static int totalCount() {
        return items.size();
    }

    public static boolean moveBy(int delta) {
        if (items.isEmpty()) {
            return false;
        }
        int next = index + delta;
        if (next < 0 || next >= items.size()) {
            return false;
        }
        index = next;
        return true;
    }
}
