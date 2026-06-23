package com.example.cleanrecovery.util;

import com.example.cleanrecovery.recovery.RecoveryType;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.util.Locale;

/**
 * 统一的应用路径管理器。
 *
 * 层级结构（位于外部存储根目录下的 DataRecovery/）：
 *   DataRecovery/
 *     Recovered/{Images,Videos,Audio,Documents}/   恢复产物
 *     .trash/<uuid>/{data,meta.json}               回收站
 *     .cache/{thumbnails,scan}/                    缓存
 *     .logs/scan_YYYYMMDD.log                      诊断日志
 *
 * 设计要点：
 *  - 所有路径通过本类获取，避免散落在各处的硬编码字符串。
 *  - 目录按需创建（lazy mkdirs），避免启动时全量创建。
 *  - 路径分隔符统一使用 "/"，对外返回绝对路径。
 *  - 不引入任何云备份相关路径。
 */
public final class PathManager {
    /** 应用在外部存储根目录下的工作目录名。 */
    public static final String APP_ROOT_DIR_NAME = "DataRecovery";
    public static final String RECOVERED_DIR_NAME = "Recovered";
    public static final String TRASH_DIR_NAME = ".trash";
    public static final String CACHE_DIR_NAME = ".cache";
    public static final String LOGS_DIR_NAME = ".logs";

    private static final String THUMBNAILS_DIR_NAME = "thumbnails";
    private static final String SCAN_CACHE_DIR_NAME = "scan";

    private PathManager() {
    }

    /** 返回应用根目录 /storage/emulated/0/DataRecovery/。 */
    public static File appRoot() {
        return new File(Environment.getExternalStorageDirectory(), APP_ROOT_DIR_NAME);
    }

    /** 返回恢复产物根目录，按需创建。 */
    public static File recoveredRoot() {
        File dir = new File(appRoot(), RECOVERED_DIR_NAME);
        ensureDir(dir);
        return dir;
    }

    /** 按恢复类型返回对应子目录（Images/Videos/Audio/Documents），按需创建。 */
    public static File recoveredDirFor(RecoveryType type) {
        File dir = new File(recoveredRoot(), subdirForType(type));
        ensureDir(dir);
        return dir;
    }

    private static String subdirForType(RecoveryType type) {
        if (type == null) return "Other";
        switch (type) {
            case IMAGE:    return "Images";
            case VIDEO:    return "Videos";
            case AUDIO:    return "Audio";
            case DOCUMENT: return "Documents";
            default:       return "Other";
        }
    }

    /** 返回回收站根目录，按需创建。 */
    public static File trashRoot() {
        File dir = new File(appRoot(), TRASH_DIR_NAME);
        ensureDir(dir);
        return dir;
    }

    /** 为一个新回收项分配独立子目录（按需创建）。 */
    public static File trashEntryDir(String entryId) {
        File dir = new File(trashRoot(), entryId);
        ensureDir(dir);
        return dir;
    }

    /** 返回缓存根目录，按需创建。 */
    public static File cacheRoot() {
        File dir = new File(appRoot(), CACHE_DIR_NAME);
        ensureDir(dir);
        return dir;
    }

    /** 返回缩略图磁盘缓存目录，按需创建。 */
    public static File thumbnailCacheDir() {
        File dir = new File(cacheRoot(), THUMBNAILS_DIR_NAME);
        ensureDir(dir);
        return dir;
    }

    /** 返回扫描结果快照缓存目录，按需创建。 */
    public static File scanCacheDir() {
        File dir = new File(cacheRoot(), SCAN_CACHE_DIR_NAME);
        ensureDir(dir);
        return dir;
    }

    /** 返回诊断日志目录，按需创建。 */
    public static File logsDir() {
        File dir = new File(appRoot(), LOGS_DIR_NAME);
        ensureDir(dir);
        return dir;
    }

    /** 返回应用私有缓存目录（context.getCacheDir()），用于纯临时文件。 */
    public static File internalCache(Context context) {
        File dir = context.getCacheDir();
        ensureDir(dir);
        return dir;
    }

    /** 计算指定目录及其子目录占用字节数；目录不可读返回 0。 */
    public static long directorySize(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return 0L;
        }
        long total = 0L;
        File[] children = directory.listFiles();
        if (children == null) return 0L;
        for (File child : children) {
            if (child.isDirectory()) {
                total += directorySize(child);
            } else {
                total += child.length();
            }
        }
        return total;
    }

    /** 递归删除目录及其内容；仅删除目录下内容时 keepRoot=true。 */
    public static boolean deleteRecursively(File file, boolean keepRoot) {
        if (file == null || !file.exists()) return true;
        boolean ok = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    ok &= deleteRecursively(child, false);
                }
            }
        }
        if (keepRoot) return ok;
        return file.delete() && ok;
    }

    /** 规范化路径用于显示：统一分隔符为 "/"，去掉重复分隔符。 */
    public static String normalize(String path) {
        if (path == null) return "";
        return path.replace('\\', '/').replaceAll("/+", "/");
    }

    /** 生成基于时间的扫描日志文件名，例如 scan_20260618.log。 */
    public static String scanLogFileName(long timestampMillis) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMdd", Locale.US);
        return "scan_" + sdf.format(new java.util.Date(timestampMillis)) + ".log";
    }

    private static void ensureDir(File dir) {
        if (!dir.exists() && !dir.mkdirs()) {
            // 创建失败通常是因为权限不足或父目录被锁；调用方在使用文件时会自然抛 IOException
            android.util.Log.w("PathManager", "Cannot create dir: " + dir.getAbsolutePath());
        }
    }
}
