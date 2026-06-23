package com.example.cleanrecovery.recovery;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the latest scan results for the app process so Results tab and rotation
 * can show prior results without re-scanning.
 */
public final class RecoveryResultsSession {
    private static List<RecoveryItem> items = new ArrayList<>();
    private static RecoveryState.FilterMode filter = RecoveryState.FilterMode.ALL;
    private static RecoveryType scanType;
    private static boolean scanAllMode;
    private static boolean experimentalMode;
    private static int scannedCount;
    private static int foundCount;
    private static boolean hasSnapshot;

    private RecoveryResultsSession() {
    }

    public static void saveFrom(
            RecoveryState state,
            RecoveryType type,
            boolean allTypes,
            boolean experimental,
            int scanned,
            int found
    ) {
        saveFromItems(
                state.getAllItems(),
                state.getFilter(),
                type,
                allTypes,
                experimental,
                scanned,
                found
        );
    }

    public static void saveFromItems(
            List<RecoveryItem> sourceItems,
            RecoveryState.FilterMode savedFilter,
            RecoveryType type,
            boolean allTypes,
            boolean experimental,
            int scanned,
            int found
    ) {
        items = copyItems(sourceItems);
        filter = savedFilter == null ? RecoveryState.FilterMode.ALL : savedFilter;
        scanType = type;
        scanAllMode = allTypes;
        experimentalMode = experimental;
        scannedCount = scanned;
        foundCount = found;
        hasSnapshot = true;
    }

    public static boolean hasResults() {
        return hasSnapshot;
    }

    public static void restoreTo(RecoveryState state) {
        state.clear();
        state.addAll(copyItems(items));
        state.setFilter(filter);
    }

    public static RecoveryType getScanType() {
        return scanType;
    }

    public static boolean isScanAllMode() {
        return scanAllMode;
    }

    public static boolean isExperimentalMode() {
        return experimentalMode;
    }

    public static int getScannedCount() {
        return scannedCount;
    }

    public static int getFoundCount() {
        return foundCount;
    }

    public static void clear() {
        items.clear();
        filter = RecoveryState.FilterMode.ALL;
        scanType = null;
        scanAllMode = false;
        experimentalMode = false;
        scannedCount = 0;
        foundCount = 0;
        hasSnapshot = false;
    }

    private static List<RecoveryItem> copyItems(List<RecoveryItem> source) {
        ArrayList<RecoveryItem> copies = new ArrayList<>(source.size());
        for (RecoveryItem item : source) {
            copies.add(new RecoveryItem(
                    item.type,
                    item.name,
                    item.path,
                    item.size,
                    item.modifiedAt,
                    item.width,
                    item.height,
                    item.suspectedDeleted,
                    item.sourceKind
            ));
            copies.get(copies.size() - 1).selected = item.selected;
        }
        return copies;
    }
}
