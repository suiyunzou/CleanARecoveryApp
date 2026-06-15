package com.example.cleanrecovery;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class JunkScanner {
    public interface Callback {
        boolean isCancelled();
        void onProgress(int scannedCount, int foundCount, String currentPath);
        void onItemFound(JunkItem item);
        void onDone(int scannedCount, int foundCount, long totalBytes);
        void onError(File file, Exception exception);
    }

    private static final Set<String> INSTALLER_EXTENSIONS = setOf("apk", "apks", "apkm", "aab");
    private static final Set<String> PROTECTED_EMPTY_BASE_DIRS = setOf(
            "alarms", "android", "audiobooks", "camera", "dcim", "documents", "download",
            "movies", "music", "notifications", "pictures", "podcasts", "recordings",
            "ringtones", "video", "videos", "datarecovery"
    );

    private final Context context;
    private final Set<String> installedPackages = new HashSet<>();
    private final Set<String> visitedDirectories = new HashSet<>();
    private final Set<String> emittedPaths = new HashSet<>();
    private int scannedCount;
    private int foundCount;
    private long totalBytes;

    public JunkScanner(Context context) {
        this.context = context.getApplicationContext();
        loadInstalledPackages();
    }

    public void scan(Callback callback) {
        scannedCount = 0;
        foundCount = 0;
        totalBytes = 0L;
        visitedDirectories.clear();
        emittedPaths.clear();

        scanDirectory(Environment.getExternalStorageDirectory(), callback, true);
        callback.onDone(scannedCount, foundCount, totalBytes);
    }

    private void scanDirectory(File directory, Callback callback, boolean allowResidueDirectoryMatch) {
        if (callback.isCancelled() || directory == null || !directory.exists() || !directory.canRead()) {
            return;
        }
        if (isProtectedOutput(directory)) {
            return;
        }

        String canonicalPath = canonicalPath(directory);
        if (!visitedDirectories.add(canonicalPath)) {
            return;
        }

        JunkItem residue = allowResidueDirectoryMatch ? inspectResidueDirectory(directory) : null;
        if (residue != null) {
            emit(residue, callback);
            return;
        }

        File[] children;
        try {
            children = directory.listFiles();
        } catch (SecurityException exception) {
            callback.onError(directory, exception);
            return;
        }
        if (children == null) {
            return;
        }

        for (File child : children) {
            if (callback.isCancelled()) {
                return;
            }
            scannedCount++;
            if (scannedCount % 25 == 0) {
                callback.onProgress(scannedCount, foundCount, child.getAbsolutePath());
            }

            if (child.isDirectory()) {
                scanDirectory(child, callback, true);
            } else {
                JunkItem item = inspectFile(child);
                if (item != null) {
                    emit(item, callback);
                }
            }
        }

        JunkItem emptyDirectory = inspectEmptyDirectory(directory);
        if (emptyDirectory != null) {
            emit(emptyDirectory, callback);
        }
    }

    private JunkItem inspectFile(File file) {
        if (!file.exists() || !file.canRead() || isProtectedOutput(file)) {
            return null;
        }

        String path = normalize(file);
        String name = file.getName();
        String lowerName = name.toLowerCase(Locale.US);
        String extension = extensionOf(file);

        if (".nomedia".equals(lowerName)) {
            return null;
        }
        if (file.length() == 0L) {
            return item(file, JunkType.EMPTY, JunkRisk.SAFE, R.string.junk_reason_empty_file, false);
        }
        if (INSTALLER_EXTENSIONS.contains(extension)) {
            return item(file, JunkType.APK, JunkRisk.REVIEW, R.string.junk_reason_apk, false);
        }
        if (isLog(path, lowerName)) {
            return item(file, JunkType.LOGS, JunkRisk.SAFE, R.string.junk_reason_logs, false);
        }
        if (isTemp(path, extension)) {
            return item(file, JunkType.TEMP, JunkRisk.SAFE, R.string.junk_reason_temp, false);
        }
        if (isThumbnail(path, lowerName, extension)) {
            return item(file, JunkType.THUMBNAILS, JunkRisk.SAFE, R.string.junk_reason_thumbnails, false);
        }
        if (isAdOrAnalytics(path)) {
            return item(file, JunkType.AD_ANALYTICS, JunkRisk.SAFE, R.string.junk_reason_ad_analytics, false);
        }
        if (isAppCache(path)) {
            return item(file, JunkType.APP_CACHE, JunkRisk.SAFE, R.string.junk_reason_app_cache, false);
        }
        if (isRecycleBin(path, lowerName)) {
            return item(file, JunkType.RECYCLE_BIN, JunkRisk.HIGH, R.string.junk_reason_recycle_bin, false);
        }
        if (isAppMediaCache(path)) {
            return item(file, JunkType.APP_MEDIA, JunkRisk.HIGH, R.string.junk_reason_app_media, false);
        }
        if (path.contains("/lost.dir/") || path.endsWith("/lost.dir")) {
            return item(file, JunkType.RESIDUE, JunkRisk.REVIEW, R.string.junk_reason_lost_dir, false);
        }
        return null;
    }

    private JunkItem inspectResidueDirectory(File directory) {
        String path = normalize(directory);
        String packageName = packageNameFromAndroidPublicDir(path);
        if (packageName == null || installedPackages.contains(packageName)) {
            return null;
        }
        return item(directory, JunkType.RESIDUE, JunkRisk.REVIEW, R.string.junk_reason_uninstalled_residue, true);
    }

    private JunkItem inspectEmptyDirectory(File directory) {
        if (!directory.isDirectory() || isProtectedEmptyBase(directory) || isProtectedOutput(directory)) {
            return null;
        }
        File[] children = directory.listFiles();
        if (children == null || children.length > 0) {
            return null;
        }
        return item(directory, JunkType.EMPTY, JunkRisk.SAFE, R.string.junk_reason_empty_folder, true);
    }

    private void emit(JunkItem item, Callback callback) {
        String canonicalPath = canonicalPath(item.asFile());
        if (!emittedPaths.add(canonicalPath)) {
            return;
        }
        foundCount++;
        totalBytes += item.size;
        callback.onItemFound(item);
    }

    private JunkItem item(File file, JunkType type, JunkRisk risk, int reasonRes, boolean directory) {
        return new JunkItem(
                type,
                risk,
                file.getName(),
                file.getAbsolutePath(),
                directory ? directorySize(file) : file.length(),
                file.lastModified(),
                context.getString(reasonRes),
                directory
        );
    }

    private boolean isLog(String path, String lowerName) {
        return lowerName.endsWith(".log")
                || lowerName.equals("logcat.txt")
                || lowerName.equals("crash.txt")
                || lowerName.contains("crashlog")
                || path.contains("/logs/")
                || path.endsWith("/logs")
                || path.contains("/bugreport")
                || path.contains("/appmonitorsdklogs/")
                || path.contains("/mgc_crash_log/");
    }

    private boolean isTemp(String path, String extension) {
        return "tmp".equals(extension)
                || "temp".equals(extension)
                || path.contains("/tmp/")
                || path.contains("/temp/")
                || path.contains("/temporary/")
                || path.endsWith("/tmp")
                || path.endsWith("/temp");
    }

    private boolean isThumbnail(String path, String lowerName, String extension) {
        return path.contains("/.thumbnails/")
                || path.contains("/thumbnails/")
                || path.contains("/thumbnail/")
                || path.contains("/albumthumbs")
                || lowerName.equals("thumbs.db")
                || lowerName.equals("desktop.ini")
                || lowerName.startsWith(".thumb")
                || extension.startsWith("thumb");
    }

    private boolean isAppCache(String path) {
        return path.contains("/android/data/") && path.contains("/cache/")
                || path.contains("/android/media/") && path.contains("/cache/")
                || path.contains("/ucshare/.ucthumb/")
                || path.contains("/supercache/")
                || path.contains("/app_webview/cache/")
                || path.contains("/app_webview/gpucache/")
                || path.contains("/service worker/cachestorage/")
                || path.contains("/service worker/scriptcache/")
                || path.contains("/offlinecache/")
                || path.contains("/offline-cache/")
                || path.contains("/offline_cache/");
    }

    private boolean isAdOrAnalytics(String path) {
        return path.contains("/unityadsvideocache/")
                || path.contains("/supersonicads/")
                || path.contains("/mobvista")
                || path.contains("/splashad")
                || path.contains("/pangled")
                || path.contains("/analytics/")
                || path.contains("/unityservicescachedmetrics/")
                || path.contains("/fastbot/")
                || path.contains("/.chartboost/")
                || path.contains("/vast_rtb_cache/")
                || path.contains("/goadsdk/")
                || path.contains("/iflyadimgcache/");
    }

    private boolean isRecycleBin(String path, String lowerName) {
        return path.contains("/.trash/")
                || path.contains("/trash/")
                || path.contains("/recyclebin/")
                || path.contains("/recycle/")
                || path.contains("/.garbage/")
                || lowerName.startsWith(".trashed-");
    }

    private boolean isAppMediaCache(String path) {
        return path.contains("/telegram/telegram audio/")
                || path.contains("/telegram/telegram documents/")
                || path.contains("/telegram/telegram images/")
                || path.contains("/telegram/telegram video/")
                || path.contains("/telegram/telegram stories/")
                || path.contains("/tencent/micromsg/") && (
                        path.contains("/sns/")
                                || path.contains("/video/")
                                || path.contains("/image2/")
                                || path.contains("/voice2/")
                );
    }

    private String packageNameFromAndroidPublicDir(String path) {
        String[] markers = {
                "/android/data/",
                "/android/media/",
                "/android/obb/"
        };
        for (String marker : markers) {
            int index = path.indexOf(marker);
            if (index < 0) {
                continue;
            }
            String rest = path.substring(index + marker.length());
            if (rest.length() == 0 || rest.contains("/")) {
                continue;
            }
            return rest;
        }
        return null;
    }

    private boolean isProtectedEmptyBase(File directory) {
        String name = directory.getName().toLowerCase(Locale.US);
        if (PROTECTED_EMPTY_BASE_DIRS.contains(name)) {
            return true;
        }
        String path = normalize(directory);
        return path.endsWith("/android/data")
                || path.endsWith("/android/media")
                || path.endsWith("/android/obb")
                || path.contains("/android/data/") && (path.endsWith("/files") || path.endsWith("/cache"))
                || path.contains("/android/media/") && (path.endsWith("/files") || path.endsWith("/cache"));
    }

    private void loadInstalledPackages() {
        PackageManager packageManager = context.getPackageManager();
        List<ApplicationInfo> applications = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo applicationInfo : applications) {
            installedPackages.add(applicationInfo.packageName);
        }
    }

    private long directorySize(File directory) {
        if (directory == null || !directory.exists()) {
            return 0L;
        }
        if (directory.isFile()) {
            return directory.length();
        }
        long total = 0L;
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                total += directorySize(child);
            }
        }
        return total;
    }

    private static boolean isProtectedOutput(File file) {
        String path = normalize(file);
        return path.contains("/datarecovery/");
    }

    private static String normalize(File file) {
        return file.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.US);
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException exception) {
            return file.getAbsolutePath();
        }
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
