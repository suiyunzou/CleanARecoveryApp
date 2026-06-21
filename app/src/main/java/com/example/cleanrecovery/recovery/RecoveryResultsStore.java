package com.example.cleanrecovery.recovery;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class RecoveryResultsStore {
    private static final String FILE_NAME = "latest_recovery_results.json";
    private static final int VERSION = 1;

    public static final class Snapshot {
        public final List<RecoveryItem> items;
        public final RecoveryState.FilterMode filter;
        public final RecoveryType scanType;
        public final boolean scanAllMode;
        public final boolean experimentalMode;
        public final int scannedCount;
        public final int foundCount;

        Snapshot(
                List<RecoveryItem> items,
                RecoveryState.FilterMode filter,
                RecoveryType scanType,
                boolean scanAllMode,
                boolean experimentalMode,
                int scannedCount,
                int foundCount
        ) {
            this.items = items;
            this.filter = filter;
            this.scanType = scanType;
            this.scanAllMode = scanAllMode;
            this.experimentalMode = experimentalMode;
            this.scannedCount = scannedCount;
            this.foundCount = foundCount;
        }
    }

    private RecoveryResultsStore() {
    }

    public static void saveFrom(
            Context context,
            RecoveryState state,
            RecoveryType type,
            boolean allTypes,
            boolean experimental,
            int scanned,
            int found
    ) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("filter", state.getFilter().name());
            root.put("scanType", type == null ? JSONObject.NULL : type.name());
            root.put("scanAllMode", allTypes);
            root.put("experimentalMode", experimental);
            root.put("scannedCount", scanned);
            root.put("foundCount", found);

            JSONArray array = new JSONArray();
            for (RecoveryItem item : state.getAllItems()) {
                JSONObject object = new JSONObject();
                object.put("type", item.type.name());
                object.put("name", item.name);
                object.put("path", item.path);
                object.put("size", item.size);
                object.put("modifiedAt", item.modifiedAt);
                object.put("width", item.width);
                object.put("height", item.height);
                object.put("suspectedDeleted", item.suspectedDeleted);
                object.put("sourceKind", item.sourceKind.name());
                array.put(object);
            }
            root.put("items", array);
            write(context, root);
        } catch (JSONException | IOException exception) {
            android.util.Log.w("RecoveryResultsStore", "Failed to save results: " + exception.getMessage());
        }
    }

    public static Snapshot read(Context context) {
        File file = resultsFile(context);
        if (!file.exists()) {
            return null;
        }
        try {
            JSONObject root = new JSONObject(readText(file));
            JSONArray array = root.optJSONArray("items");
            ArrayList<RecoveryItem> items = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject object = array.optJSONObject(i);
                    if (object == null) {
                        continue;
                    }
                    RecoveryItem item = itemFromJson(object);
                    if (item != null) {
                        items.add(item);
                    }
                }
            }
            return new Snapshot(
                    items,
                    parseFilter(root.optString("filter", RecoveryState.FilterMode.ALL.name())),
                    parseType(root.optString("scanType", "")),
                    root.optBoolean("scanAllMode", false),
                    root.optBoolean("experimentalMode", false),
                    root.optInt("scannedCount", 0),
                    root.optInt("foundCount", items.size())
            );
        } catch (JSONException | IOException exception) {
            android.util.Log.w("RecoveryResultsStore", "Failed to read results: " + exception.getMessage());
            return null;
        }
    }

    private static RecoveryItem itemFromJson(JSONObject object) {
        String path = object.optString("path", "");
        if (path.isEmpty()) {
            return null;
        }
        RecoveryType type = parseType(object.optString("type", ""));
        if (type == null) {
            return null;
        }
        String name = object.optString("name", new File(path).getName());
        return new RecoveryItem(
                type,
                name,
                path,
                object.optLong("size", 0L),
                object.optLong("modifiedAt", 0L),
                object.optInt("width", 0),
                object.optInt("height", 0),
                object.optBoolean("suspectedDeleted", false),
                parseSourceKind(object.optString("sourceKind", RecoverySourceKind.VISIBLE_SHARED_FILE.name()))
        );
    }

    private static RecoveryType parseType(String name) {
        if (name == null || name.isEmpty() || "null".equals(name)) {
            return null;
        }
        try {
            return RecoveryType.valueOf(name);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static RecoveryState.FilterMode parseFilter(String name) {
        try {
            return RecoveryState.FilterMode.valueOf(name);
        } catch (IllegalArgumentException exception) {
            return RecoveryState.FilterMode.ALL;
        }
    }

    private static RecoverySourceKind parseSourceKind(String name) {
        try {
            return RecoverySourceKind.valueOf(name);
        } catch (IllegalArgumentException exception) {
            return RecoverySourceKind.VISIBLE_SHARED_FILE;
        }
    }

    private static void write(Context context, JSONObject root) throws IOException {
        try (FileOutputStream output = new FileOutputStream(resultsFile(context));
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            writer.write(root.toString());
        }
    }

    private static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static File resultsFile(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }
}
