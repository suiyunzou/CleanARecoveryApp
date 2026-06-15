package com.example.cleanrecovery;

import android.graphics.BitmapFactory;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class RecoveryScanner {
    private static final int PROGRESS_INTERVAL = 25;
    private static final int PREPARE_PROGRESS_INTERVAL = 100;

    public interface Callback {
        boolean isCancelled();

        void onProgress(int scannedCount, int foundCount, String currentPath);

        void onItemFound(RecoveryItem item);

        void onDone(int scannedCount, int foundCount);

        void onError(File file, Exception exception);
    }

    public interface PrepareCallback {
        boolean isCancelled();

        void onPrepareProgress(int countedSoFar, String currentPath);

        void onError(File file, Exception exception);
    }

    private static final Set<String> VIDEO_EXTENSIONS = setOf("mp4", "3gp", "mkv", "ts");
    private static final Set<String> AUDIO_EXTENSIONS = setOf("mp3", "flac", "wav", "ogg");
    private static final Set<String> DOCUMENT_EXTENSIONS = setOf("pdf", "txt", "doc", "ppt", "docx", "pptx", "xlsx", "xls");
    private static final Set<String> IMAGE_LIKELY_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heif", "heic", "thumbnails", "");
    private static final Set<String> IMAGE_SKIP_EXTENSIONS = setOf(
            "mp3", "mp4", "mkv", "3gp", "ts", "pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
            "apk", "dex", "so", "json", "xml", "txt", "log", "zip", "html", "css", "js", "ttf",
            "otf", "ini", "db", "sqlite", "nomedia", "exo", "dat", "m4a"
    );

    private final Set<String> visitedDirectories = new HashSet<>();
    private int scannedCount;
    private int foundCount;
    private int preparedCount;

    public int countEntries(PrepareCallback callback) {
        preparedCount = 0;
        visitedDirectories.clear();
        countDirectory(Environment.getExternalStorageDirectory(), callback);
        return preparedCount;
    }

    public void scan(RecoveryType type, Callback callback) {
        scannedCount = 0;
        foundCount = 0;
        visitedDirectories.clear();
        ScanDiagnostics.trackerEvent("fileScanStart type=" + type);
        scanDirectory(Environment.getExternalStorageDirectory(), type, callback);
        ScanDiagnostics.trackerEvent("fileScanDone type=" + type + " scanned=" + scannedCount + " found=" + foundCount);
        callback.onDone(scannedCount, foundCount);
    }

    private void countDirectory(File directory, PrepareCallback callback) {
        if (callback.isCancelled() || directory == null || !directory.exists() || !directory.canRead()) {
            return;
        }

        String canonicalPath = canonicalPathOf(directory);
        if (!visitedDirectories.add(canonicalPath)) {
            return;
        }

        File[] children = listChildren(directory, callback);
        if (children == null) {
            return;
        }

        for (File child : children) {
            if (callback.isCancelled()) {
                return;
            }
            preparedCount++;
            if (preparedCount % PREPARE_PROGRESS_INTERVAL == 0) {
                callback.onPrepareProgress(preparedCount, child.getAbsolutePath());
            }
            if (child.isDirectory() && !isOutputDirectory(child)) {
                countDirectory(child, callback);
            }
        }
    }

    private void scanDirectory(File directory, RecoveryType type, Callback callback) {
        if (callback.isCancelled() || directory == null || !directory.exists() || !directory.canRead()) {
            return;
        }

        String canonicalPath = canonicalPathOf(directory);
        if (!visitedDirectories.add(canonicalPath)) {
            return;
        }

        File[] children = listChildren(directory, callback);
        if (children == null) {
            return;
        }

        for (File child : children) {
            if (callback.isCancelled()) {
                return;
            }
            scannedCount++;
            if (scannedCount % PROGRESS_INTERVAL == 0) {
                callback.onProgress(scannedCount, foundCount, child.getAbsolutePath());
            }

            if (child.isDirectory()) {
                if (!isOutputDirectory(child)) {
                    scanDirectory(child, type, callback);
                }
            } else {
                RecoveryItem item = inspectFile(child, type);
                if (item != null) {
                    foundCount++;
                    callback.onItemFound(item);
                }
            }
        }
    }

    private static String canonicalPathOf(File directory) {
        try {
            return directory.getCanonicalPath();
        } catch (IOException exception) {
            return directory.getAbsolutePath();
        }
    }

    private static File[] listChildren(File directory, PrepareCallback callback) {
        try {
            return directory.listFiles();
        } catch (SecurityException exception) {
            callback.onError(directory, exception);
            ScanDiagnostics.error("listFiles", directory.getAbsolutePath(), exception);
            return null;
        }
    }

    private static File[] listChildren(File directory, Callback callback) {
        try {
            return directory.listFiles();
        } catch (SecurityException exception) {
            callback.onError(directory, exception);
            ScanDiagnostics.error("listFiles", directory.getAbsolutePath(), exception);
            return null;
        }
    }

    private RecoveryItem inspectFile(File file, RecoveryType type) {
        if (!file.exists() || !file.canRead() || file.length() <= 1L) {
            return null;
        }

        String extension = extensionOf(file);
        if (type == RecoveryType.IMAGE) {
            return inspectImage(file, extension);
        }
        if (type == RecoveryType.VIDEO && VIDEO_EXTENSIONS.contains(extension)) {
            return item(file, type, 0, 0);
        }
        if (type == RecoveryType.AUDIO && AUDIO_EXTENSIONS.contains(extension)) {
            return item(file, type, 0, 0);
        }
        if (type == RecoveryType.DOCUMENT && DOCUMENT_EXTENSIONS.contains(extension)) {
            return item(file, type, 0, 0);
        }
        return null;
    }

    private RecoveryItem inspectImage(File file, String extension) {
        if (IMAGE_SKIP_EXTENSIONS.contains(extension) && !IMAGE_LIKELY_EXTENSIONS.contains(extension)) {
            return null;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (FileInputStream inputStream = new FileInputStream(file)) {
            BitmapFactory.decodeStream(inputStream, null, options);
        } catch (IOException | RuntimeException exception) {
            ScanDiagnostics.error("decodeImage", file.getAbsolutePath(), exception);
            return null;
        }

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null;
        }
        return item(file, RecoveryType.IMAGE, options.outWidth, options.outHeight);
    }

    private RecoveryItem item(File file, RecoveryType type, int width, int height) {
        String extension = extensionOf(file);
        return new RecoveryItem(
                type,
                file.getName(),
                file.getAbsolutePath(),
                file.length(),
                file.lastModified(),
                width,
                height,
                isSuspectedDeleted(file, extension),
                RecoverySourceKind.VISIBLE_SHARED_FILE
        );
    }

    static boolean isSuspectedDeletedPath(String path, String name, String extension) {
        String normalizedPath = path.replace('\\', '/').toLowerCase(Locale.US);
        String normalizedName = name.toLowerCase(Locale.US);
        return "thumbnails".equals(extension)
                || normalizedName.startsWith(".")
                || normalizedPath.contains("/.thumbnails/")
                || normalizedPath.contains("/thumbnail")
                || normalizedPath.contains("/thumbnails/")
                || normalizedPath.contains("/cache/")
                || normalizedPath.contains("/cached/")
                || normalizedPath.contains("/tmp/")
                || normalizedPath.contains("/temp/")
                || normalizedPath.contains("/trash/")
                || normalizedPath.contains("/recycle")
                || normalizedPath.contains("/recycler/")
                || normalizedPath.contains("/lost.dir/")
                || normalizedPath.contains("/lost+found/")
                || normalizedPath.contains("/android/data/")
                || normalizedPath.contains("/android/media/");
    }

    private static boolean isSuspectedDeleted(File file, String extension) {
        return isSuspectedDeletedPath(file.getAbsolutePath(), file.getName(), extension);
    }

    public static boolean isOutputDirectoryName(String directoryName) {
        return "DataRecovery".equalsIgnoreCase(directoryName);
    }

    private static boolean isOutputDirectory(File directory) {
        return isOutputDirectoryName(directory.getName());
    }

    private static String extensionOf(File file) {
        String name = file.getName();
        int index = name.lastIndexOf('.');
        if (index < 0 || index == name.length() - 1) {
            return "";
        }
        return name.substring(index + 1).toLowerCase(Locale.US);
    }

    private static Set<String> setOf(String... values) {
        HashSet<String> set = new HashSet<>();
        Collections.addAll(set, values);
        return Collections.unmodifiableSet(set);
    }
}
