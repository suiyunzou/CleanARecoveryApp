package com.example.cleanrecovery.algorithm;

import android.os.Environment;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Scans WeChat directories for cached, sent, and potentially deleted media files.
 * Derived from reference APK reverse-engineering: scanWechatDirectoryForFiles,
 * scanWechatDirectoryForLargeFiles.
 *
 * WeChat stores media in nested directory structures under
 * /storage/emulated/0/Android/data/com.tencent.mm/MicroMsg/
 * that often survive the chat UI "clear cache" action.
 */
public final class WechatDirectoryScannerAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "wechat_directory_scanner";

    private static final String[] WECHAT_BASE_PATHS = {
        "/tencent/MicroMsg/",
        "/Android/data/com.tencent.mm/MicroMsg/",
    };

    private static final String[] WECHAT_MEDIA_DIRS = {
        "image2", "video", "voice2", "avatar", "attachment",
        "emoji", "favorite", "sight", "snssight", "draft",
        "appbrand", "bizimg", "cdntran", "hdheadimg",
    };

    private final Set<String> visitedDirs = new HashSet<>();
    private final Set<String> emittedHashes = new HashSet<>();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_wechat_directory_scanner;
    }

    @Override
    public RecoveryType[] supportedTypes() {
        return new RecoveryType[] {RecoveryType.IMAGE, RecoveryType.VIDEO, RecoveryType.AUDIO};
    }

    @Override
    public AlgorithmAvailability availability(AlgorithmContext context) {
        return AlgorithmAvailability.runnable();
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        visitedDirs.clear();
        emittedHashes.clear();
        final int[] processed = {0};
        File root = Environment.getExternalStorageDirectory();
        findAndScanWechatDirs(root, context, callback, processed);
    }

    private void findAndScanWechatDirs(File dir, AlgorithmContext context, AlgorithmCallback callback, int[] processed) {
        if (callback.isCancelled() || dir == null || !dir.exists() || !dir.canRead()) {
            return;
        }
        String canon = canonicalPath(dir);
        if (!visitedDirs.add(canon)) {
            return;
        }
        if (isOutputDir(dir)) {
            return;
        }

        String normCanon = normalize(canon);
        if (isWechatMediaDir(normCanon)) {
            walkMediaFiles(dir, context, callback, processed);
            return;
        }
        if (isWechatBasePath(normCanon)) {
            File[] children = listFiles(dir);
            if (children != null) {
                for (File child : children) {
                    if (callback.isCancelled()) break;
                    if (child.isDirectory()) {
                        findAndScanWechatDirs(child, context, callback, processed);
                    }
                }
            }
            return;
        }

        File[] children = listFiles(dir);
        if (children == null) return;
        for (File child : children) {
            if (callback.isCancelled()) break;
            if (child.isDirectory()) {
                String childNorm = normalize(canonicalPath(child));
                if (isWechatBasePath(childNorm)) {
                    findAndScanWechatDirs(child, context, callback, processed);
                }
                // Only recurse 2 levels deep outside known paths to limit scan cost
            }
        }
    }

    private void walkMediaFiles(File dir, AlgorithmContext context, AlgorithmCallback callback, int[] processed) {
        if (callback.isCancelled()) return;
        File[] children = listFiles(dir);
        if (children == null) return;
        for (File child : children) {
            if (callback.isCancelled()) return;
            if (child.isDirectory()) {
                walkMediaFiles(child, context, callback, processed);
            } else if (child.isFile() && child.canRead() && child.length() > 1L) {
                processed[0]++;
                if (processed[0] % 25 == 0) {
                    callback.onProgress(processed[0], child.getAbsolutePath());
                }
                tryEmitWechatFile(child, context, callback);
            }
        }
    }

    private void tryEmitWechatFile(File file, AlgorithmContext context, AlgorithmCallback callback) {
        RecoveryType fileType = classifyForType(context.type, file);
        if (fileType == null) return;

        String path = file.getAbsolutePath();
        String quickHash = quickPathHash(path);
        if (!emittedHashes.add(quickHash)) return;

        String mime = "";
        try {
            FileSignatureProbe.ProbeResult probe = FileSignatureProbe.probe(file);
            if (probe != null) {
                mime = probe.mimeDetected;
            }
        } catch (Exception ignored) {
        }

        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .candidateId(path)
                .sourceKind(CandidateSourceKind.KNOWN_CACHE_BLOB)
                .sourceUriOrPath(path)
                .extractionMethod("wechat_directory_scanner")
                .byteLength(file.length())
                .mimeDetected(mime)
                .build();
        callback.onCandidate(candidate);
    }

    private RecoveryType classifyForType(RecoveryType targetType, File file) {
        String name = file.getName().toLowerCase(Locale.US);

        // WeChat media typically has no extension but is stored in typed directories
        if (targetType == RecoveryType.IMAGE) {
            // WeChat image2 dir, avatar, emoji, snssight, favorite images
            String parentDir = file.getParent();
            if (parentDir == null) return null;
            String p = parentDir.replace('\\', '/').toLowerCase(Locale.US);
            if (p.contains("/image2") || p.contains("/avatar") || p.contains("/emoji")
                    || p.contains("/snssight") || p.contains("/bizimg")
                    || p.contains("/hdheadimg") || p.contains("/cdntran")
                    || p.contains("/favorite")) {
                // Quick check: if has video extension, skip
                if (name.endsWith(".mp4") || name.endsWith(".3gp") || name.endsWith(".avi")) {
                    return null;
                }
                return RecoveryType.IMAGE;
            }
            // Check file signature as fallback
            try {
                FileSignatureProbe.ProbeResult probe = FileSignatureProbe.probe(file);
                if (probe != null && probe.type == RecoveryType.IMAGE) {
                    return RecoveryType.IMAGE;
                }
            } catch (Exception ignored) {
            }
            return null;
        }

        if (targetType == RecoveryType.VIDEO) {
            String parentDir = file.getParent();
            if (parentDir == null) return null;
            String p = parentDir.replace('\\', '/').toLowerCase(Locale.US);
            if (p.contains("/video") || p.contains("/sight") || p.contains("/snssight")) {
                return RecoveryType.VIDEO;
            }
            if (name.endsWith(".mp4") || name.endsWith(".3gp") || name.endsWith(".avi")
                    || name.endsWith(".mkv") || name.endsWith(".mov")) {
                return RecoveryType.VIDEO;
            }
            return null;
        }

        if (targetType == RecoveryType.AUDIO) {
            String parentDir = file.getParent();
            if (parentDir == null) return null;
            String p = parentDir.replace('\\', '/').toLowerCase(Locale.US);
            if (p.contains("/voice2") || p.contains("/audio")) {
                return RecoveryType.AUDIO;
            }
            if (name.endsWith(".amr") || name.endsWith(".mp3") || name.endsWith(".aac")
                    || name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".silk")) {
                return RecoveryType.AUDIO;
            }
            return null;
        }

        return null;
    }

    private static boolean isWechatBasePath(String normalized) {
        for (String p : WECHAT_BASE_PATHS) {
            if (normalized.contains(p.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWechatMediaDir(String normalized) {
        for (String dir : WECHAT_MEDIA_DIRS) {
            if (normalized.endsWith("/" + dir) || normalized.endsWith("/" + dir + "/")) {
                return true;
            }
        }
        return false;
    }

    private static String quickPathHash(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(path.getBytes());
            StringBuilder sb = new StringBuilder(32);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return path;
        }
    }

    private static boolean isOutputDir(File dir) {
        String name = dir.getName();
        return "DataRecovery".equalsIgnoreCase(name);
    }

    private static String normalize(String path) {
        return path == null ? "" : path.replace('\\', '/').toLowerCase(Locale.US);
    }

    private static String canonicalPath(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    private static File[] listFiles(File dir) {
        try {
            return dir.listFiles();
        } catch (SecurityException ignored) {
            return null;
        }
    }
}
