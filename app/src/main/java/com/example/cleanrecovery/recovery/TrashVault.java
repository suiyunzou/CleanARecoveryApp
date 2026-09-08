package com.example.cleanrecovery.recovery;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.example.cleanrecovery.experiment.mediastore.MediaStoreQueryExecutor;
import com.example.cleanrecovery.experiment.mediastore.MediaStoreQuerySpec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 回收站保底快照（Trash Vault）。
 *
 * <p>物理事实：现代 Android（API 29+）启用 FBE 文件级加密后，一旦系统回收站被清空，
 * 被删文件的数据块对任何工具都只剩密文，原始字节不可恢复。因此“清空后可救”的唯一
 * 诚实手段是【清空前留底】：扫描/守护时把回收站里的文件先复制进应用私有保险库，
 * 此后随时可字节级还原到原位置。
 *
 * <p>Copy-only：guardPass 只读取并复制，从不删除或改写源文件；restore 只在原位置
 * 重建文件（已存在时另存副本），不触碰任何现有文件。
 */
public final class TrashVault {
    private static final String VAULT_DIR = "trash_vault";
    private static final String MANIFEST_FILE = "manifest.json";

    public static final class VaultEntry {
        public final String key;            // content:// URI or absolute source path
        public final String displayName;    // original user-facing name (trash prefix stripped)
        public final String relativePath;   // e.g. DCIM/Camera/
        public final long size;
        public final long vaultedAt;
        public final long expiresAt;        // system trash auto-purge time (0 = n/a)
        public final String vaultFile;      // file name inside the vault dir
        public final String sha256;

        VaultEntry(String key, String displayName, String relativePath, long size,
                   long vaultedAt, long expiresAt, String vaultFile, String sha256) {
            this.key = key;
            this.displayName = displayName;
            this.relativePath = relativePath;
            this.size = size;
            this.vaultedAt = vaultedAt;
            this.expiresAt = expiresAt;
            this.vaultFile = vaultFile;
            this.sha256 = sha256;
        }
    }

    private TrashVault() {
    }

    private static File vaultDir(Context context) {
        File dir = new File(context.getFilesDir(), VAULT_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    /**
     * Snapshot everything currently sitting in system trash (MediaStore rows on
     * R+ plus on-disk {@code .trashed-*} files). Idempotent: already-vaulted
     * sources are skipped. Returns how many new items were vaulted.
     */
    public static synchronized int guardPass(Context context) {
        Set<String> knownKeys = new HashSet<>();
        List<VaultEntry> entries = readManifest(context);
        for (VaultEntry entry : entries) {
            knownKeys.add(entry.key);
        }
        int added = 0;
        added += vaultMediaStoreTrash(context, entries, knownKeys);
        added += vaultDotTrashedFiles(context, entries, knownKeys);
        if (added > 0) {
            writeManifest(context, entries);
        }
        return added;
    }

    public static synchronized List<VaultEntry> list(Context context) {
        return readManifest(context);
    }

    /** Restore every vaulted item to its original location. Returns success count. */
    public static synchronized int restoreAll(Context context) {
        List<VaultEntry> entries = readManifest(context);
        int restored = 0;
        // The same trashed file is often vaulted twice (MediaStore row key + dot
        // file key). Restore each destination once — the first entry wins.
        Set<String> restoredTargets = new HashSet<>();
        for (VaultEntry entry : entries) {
            String targetKey = (entry.relativePath + "/" + entry.displayName)
                    .replace('\\', '/').toLowerCase(Locale.US);
            if (!restoredTargets.add(targetKey)) {
                continue;
            }
            try {
                if (restoreEntry(context, entry)) {
                    restored++;
                }
            } catch (Exception ignored) {
                // Keep restoring the rest.
            }
        }
        return restored;
    }

    private static int vaultMediaStoreTrash(Context context, List<VaultEntry> entries, Set<String> knownKeys) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return 0;
        }
        int added = 0;
        for (String volume : MediaStore.getExternalVolumeNames(context)) {
            added += vaultQuery(context, MediaStoreQuerySpec.trashedImages(volume), entries, knownKeys);
            added += vaultQuery(context, MediaStoreQuerySpec.trashedVideos(volume), entries, knownKeys);
            added += vaultQuery(context, MediaStoreQuerySpec.trashedAudio(volume), entries, knownKeys);
            added += vaultQuery(context, MediaStoreQuerySpec.trashedFiles(volume), entries, knownKeys);
        }
        return added;
    }

    private static int vaultQuery(
            Context context,
            MediaStoreQuerySpec spec,
            List<VaultEntry> entries,
            Set<String> knownKeys
    ) {
        int added = 0;
        try (Cursor cursor = MediaStoreQueryExecutor.query(context.getContentResolver(), spec)) {
            if (cursor == null) {
                return 0;
            }
            int idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int pathCol = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            int sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int expiresCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_EXPIRES);
            int dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA);
            while (cursor.moveToNext()) {
                Uri contentUri = android.content.ContentUris.withAppendedId(spec.collectionUri, cursor.getLong(idCol));
                String key = contentUri.toString();
                if (knownKeys.contains(key)) {
                    continue;
                }
                String trashedName = cursor.getString(nameCol);
                String relativePath = pathCol >= 0 ? cursor.getString(pathCol) : "";
                String dataPath = dataCol >= 0 ? cursor.getString(dataCol) : null;
                long size = sizeCol >= 0 ? cursor.getLong(sizeCol) : 0L;
                long expires = expiresCol >= 0 ? cursor.getLong(expiresCol) * 1000L : 0L;
                VaultEntry entry = vaultBytes(context, key, trashedNameFrom(dataPath, contentUri, trashedName),
                        relativePath, size, expires, entryName(key), new UriSource(context, contentUri));
                if (entry != null) {
                    entries.add(entry);
                    knownKeys.add(key);
                    added++;
                }
            }
        } catch (Exception ignored) {
            // A failing collection must not break the whole guard pass.
        }
        return added;
    }

    private static int vaultDotTrashedFiles(Context context, List<VaultEntry> entries, Set<String> knownKeys) {
        File external = Environment.getExternalStorageDirectory();
        if (external == null) {
            return 0;
        }
        int added = 0;
        String[] seeds = {"DCIM", "Pictures", "Download", "Movies", "Music", "Documents",
                "Android/data/com.google.android.apps.photos/files/trash",
                "Android/media/com.google.android.apps.photos/trash"};
        for (String seed : seeds) {
            File dir = new File(external, seed);
            if (dir.isDirectory()) {
                added += walkDotTrashed(context, external, dir, 0, entries, knownKeys);
            }
        }
        return added;
    }

    private static int walkDotTrashed(
            Context context,
            File storageRoot,
            File dir,
            int depth,
            List<VaultEntry> entries,
            Set<String> knownKeys
    ) {
        if (dir == null || depth > 6 || !dir.isDirectory()) {
            return 0;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int added = 0;
        for (File child : children) {
            if (child.isDirectory()) {
                added += walkDotTrashed(context, storageRoot, child, depth + 1, entries, knownKeys);
            } else if (child.isFile() && isTrashedStyleName(child.getName())) {
                String key = "file:" + child.getAbsolutePath();
                if (knownKeys.contains(key) || child.length() <= 1L) {
                    continue;
                }
                // Full storage-relative directory so restore lands at the exact
                // original location (not just the parent folder name).
                String relativeDir = relativeDirOf(storageRoot, child.getParentFile());
                VaultEntry entry = vaultBytes(context, key, stripTrashPrefixName(child.getName()),
                        relativeDir, child.length(), 0L,
                        entryName(key), new FileSource(child));
                if (entry != null) {
                    entries.add(entry);
                    knownKeys.add(key);
                    added++;
                }
            }
        }
        return added;
    }

    private static String relativeDirOf(File storageRoot, File dir) {
        String root = storageRoot.getAbsolutePath().replace('\\', '/');
        String path = dir.getAbsolutePath().replace('\\', '/');
        if (path.startsWith(root) && path.length() > root.length()) {
            String relative = path.substring(root.length());
            return relative.startsWith("/") ? relative.substring(1) + "/" : relative + "/";
        }
        return "";
    }

    private abstract static class VaultSource {
        abstract InputStream open() throws IOException;
    }

    private static final class UriSource extends VaultSource {
        private final Context context;
        private final Uri uri;

        UriSource(Context context, Uri uri) {
            this.context = context;
            this.uri = uri;
        }

        @Override
        InputStream open() throws IOException {
            return context.getContentResolver().openInputStream(uri);
        }
    }

    private static final class FileSource extends VaultSource {
        private final File file;

        FileSource(File file) {
            this.file = file;
        }

        @Override
        InputStream open() throws IOException {
            return new FileInputStream(file);
        }
    }

    private static VaultEntry vaultBytes(
            Context context,
            String key,
            String displayName,
            String relativePath,
            long size,
            long expiresAt,
            String vaultFileName,
            VaultSource source
    ) {
        File dir = vaultDir(context);
        if (dir == null || displayName == null || displayName.isEmpty()) {
            return null;
        }
        File target = new File(dir, vaultFileName);
        String sha256;
        try {
            sha256 = copyAndHash(source, target);
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return null;
        }
        return new VaultEntry(
                key,
                stripTrashPrefixName(displayName),
                relativePath == null ? "" : relativePath,
                size,
                System.currentTimeMillis(),
                expiresAt,
                vaultFileName,
                sha256);
    }

    private static boolean restoreEntry(Context context, VaultEntry entry) {
        File dir = vaultDir(context);
        if (dir == null) {
            return false;
        }
        File vaulted = new File(dir, entry.vaultFile);
        if (!vaulted.isFile()) {
            return false;
        }
        File external = Environment.getExternalStorageDirectory();
        if (external == null) {
            return false;
        }
        String relative = entry.relativePath == null ? "" : entry.relativePath;
        File targetDir = new File(external, relative);
        if (!targetDir.isDirectory() && !targetDir.mkdirs()) {
            return false;
        }
        File target = new File(targetDir, entry.displayName);
        if (target.exists()) {
            // Never overwrite an existing file: save alongside instead.
            String name = entry.displayName;
            int dot = name.lastIndexOf('.');
            String base = dot > 0 ? name.substring(0, dot) : name;
            String ext = dot > 0 ? name.substring(dot) : "";
            int counter = 1;
            do {
                target = new File(targetDir, base + " (vault " + counter + ")" + ext);
                counter++;
            } while (target.exists());
        }
        try {
            copyAndHash(new FileSource(vaulted), target);
        } catch (Exception ignored) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            return false;
        }
        context.sendBroadcast(new android.content.Intent(
                android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(target)));
        return true;
    }

    private static String copyAndHash(VaultSource source, File target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception exception) {
            digest = null;
        }
        try (InputStream input = source.open(); OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
                if (digest != null) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        if (digest == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (byte value : digest.digest()) {
            builder.append(String.format(Locale.US, "%02x", value));
        }
        return builder.toString();
    }

    private static String entryName(String key) {
        int hash = key.hashCode();
        return String.format(Locale.US, "v_%08x.bin", hash);
    }

    private static String trashedNameFrom(String dataPath, Uri contentUri, String fallbackName) {
        if (dataPath != null && !dataPath.isEmpty()) {
            return new File(dataPath).getName();
        }
        return fallbackName;
    }

    private static String stripTrashPrefixName(String name) {
        if (name == null) {
            return "trash_item";
        }
        String lower = name.toLowerCase(Locale.US);
        if (lower.startsWith(".trashed-") || lower.startsWith(".TRASHED-")) {
            String rest = name.substring(".trashed-".length());
            int dash = rest.indexOf('-');
            if (dash >= 0 && dash + 1 < rest.length()) {
                return rest.substring(dash + 1);
            }
        }
        return name;
    }

    private static boolean isTrashedStyleName(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.US);
        return lower.startsWith(".trashed-");
    }

    private static File manifestFile(Context context) {
        File dir = vaultDir(context);
        return dir == null ? null : new File(dir, MANIFEST_FILE);
    }

    private static List<VaultEntry> readManifest(Context context) {
        ArrayList<VaultEntry> entries = new ArrayList<>();
        File file = manifestFile(context);
        if (file == null || !file.isFile()) {
            return entries;
        }
        try {
            StringBuilder builder = new StringBuilder();
            try (FileInputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            }
            JSONObject root = new JSONObject(builder.toString());
            JSONArray array = root.optJSONArray("entries");
            if (array == null) {
                return entries;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                entries.add(new VaultEntry(
                        object.optString("key"),
                        object.optString("displayName"),
                        object.optString("relativePath"),
                        object.optLong("size"),
                        object.optLong("vaultedAt"),
                        object.optLong("expiresAt"),
                        object.optString("vaultFile"),
                        object.optString("sha256")));
            }
        } catch (Exception ignored) {
            // Corrupt manifest: start fresh rather than failing the guard.
        }
        return entries;
    }

    private static void writeManifest(Context context, List<VaultEntry> entries) {
        File file = manifestFile(context);
        if (file == null) {
            return;
        }
        try {
            JSONArray array = new JSONArray();
            for (VaultEntry entry : entries) {
                JSONObject object = new JSONObject();
                object.put("key", entry.key);
                object.put("displayName", entry.displayName);
                object.put("relativePath", entry.relativePath);
                object.put("size", entry.size);
                object.put("vaultedAt", entry.vaultedAt);
                object.put("expiresAt", entry.expiresAt);
                object.put("vaultFile", entry.vaultFile);
                object.put("sha256", entry.sha256);
                array.put(object);
            }
            JSONObject root = new JSONObject();
            root.put("entries", array);
            try (FileOutputStream output = new FileOutputStream(file);
                 OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                writer.write(root.toString(2));
            }
        } catch (Exception ignored) {
            // Manifest write failure leaves the vault unusable for restore, but
            // must never crash the scan.
        }
    }
}
