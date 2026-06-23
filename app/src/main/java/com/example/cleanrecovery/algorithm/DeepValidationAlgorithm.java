package com.example.cleanrecovery.algorithm;

import android.os.Build;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.recovery.RecoveryType;
import com.example.cleanrecovery.scan.ScanDiagnostics;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import java.io.File;
import java.util.Locale;

/**
 * Deep-validates video and audio candidates by decoding at least one frame
 * through FFmpeg.  This catches false positives from extension-only or
 * header-only matches: corrupt files, truncated downloads, misidentified
 * container types.
 *
 * Because FFmpeg decoding is expensive, this algorithm only probes
 * candidates whose path or MIME already suggests the requested type.
 * It is registered as an experimental-only algorithm so it never runs
 * during a default quick scan.
 */
public final class DeepValidationAlgorithm implements RecoveryAlgorithm {
    public static final String ID = "ffmpeg_deep_validation";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int displayNameResId() {
        return R.string.alg_ffmpeg_deep_validation;
    }

    @Override
    public RecoveryType[] supportedTypes() {
        return new RecoveryType[] {RecoveryType.VIDEO, RecoveryType.AUDIO};
    }

    @Override
    public AlgorithmAvailability availability(AlgorithmContext context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return AlgorithmAvailability.requiresApi(Build.VERSION_CODES.LOLLIPOP);
        }
        if (!FfmpegDeepValidator.ensureLibrariesLoaded()) {
            return AlgorithmAvailability.evidenceOnly(R.string.alg_ffmpeg_libs_missing);
        }
        return AlgorithmAvailability.runnable();
    }

    @Override
    public void scan(AlgorithmContext context, AlgorithmCallback callback) {
        if (!FfmpegDeepValidator.ensureLibrariesLoaded()) {
            callback.onAlgorithmEvent(AlgorithmEvent.algorithmSkipped(
                    ID, "native_libs_missing"));
            return;
        }
        // Walk shared storage and re-validate every file that looks like
        // video or audio.  This is a discoverer (not a post-filter) so it
        // can emit candidates that earlier scanners flagged only by
        // extension.
        java.io.File root = android.os.Environment.getExternalStorageDirectory();
        final int[] processed = {0};
        final java.util.Set<String> seen = new java.util.HashSet<>();
        walkAndValidate(root, context, callback, processed, seen);
        ScanDiagnostics.algorithmEnd(
                ID, context.type,
                System.currentTimeMillis(), processed[0], 0, 0);
    }

    private void walkAndValidate(
            java.io.File dir, AlgorithmContext context,
            AlgorithmCallback callback, int[] processed,
            java.util.Set<String> seen) {
        if (callback.isCancelled() || dir == null || !dir.exists() || !dir.canRead()) return;
        String canon = canonical(dir);
        if (!seen.add(canon)) return;
        java.io.File[] children = dir.listFiles();
        if (children == null) return;
        for (java.io.File child : children) {
            if (callback.isCancelled()) return;
            if (child.isDirectory()) {
                walkAndValidate(child, context, callback, processed, seen);
            } else {
                if (!isMediaFile(child.getName())) continue;
                processed[0]++;
                if (processed[0] % 10 == 0) {
                    callback.onProgress(processed[0], child.getAbsolutePath());
                }
                FfmpegDeepValidator.ProbeResult r = FfmpegDeepValidator.probe(
                        child.getAbsolutePath());
                if (!r.valid) continue;
                RecoveryCandidate c = new RecoveryCandidate.Builder()
                        .candidateId(child.getAbsolutePath())
                        .sourceKind(convertType(context.type))
                        .sourceUriOrPath(child.getAbsolutePath())
                        .extractionMethod("ffmpeg_deep_validated")
                        .byteLength(child.length())
                        .mimeDetected(mimeForType(context.type))
                        .decodeStatus("FFMPEG_OK")
                        .width(r.width)
                        .height(r.height)
                        .build();
                callback.onCandidate(c);
            }
        }
    }

    private static boolean isMediaFile(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        return n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".ts")
                || n.endsWith(".3gp") || n.endsWith(".mov") || n.endsWith(".avi")
                || n.endsWith(".mp3") || n.endsWith(".flac") || n.endsWith(".wav")
                || n.endsWith(".ogg") || n.endsWith(".m4a") || n.endsWith(".aac")
                || n.endsWith(".amr");
    }

    private static com.example.cleanrecovery.experiment.CandidateSourceKind convertType(
            RecoveryType type) {
        return type == RecoveryType.VIDEO
                ? com.example.cleanrecovery.experiment.CandidateSourceKind.VISIBLE_SHARED_FILE
                : com.example.cleanrecovery.experiment.CandidateSourceKind.VISIBLE_SHARED_FILE;
    }

    private static String mimeForType(RecoveryType type) {
        return type == RecoveryType.VIDEO ? "video/mp4" : "audio/mpeg";
    }

    private static String canonical(java.io.File f) {
        try { return f.getCanonicalPath(); } catch (Exception e) { return f.getAbsolutePath(); }
    }

    /**
     * Re-validate a single candidate that was found by another algorithm.
     * Returns the same candidate if it passes FFmpeg decoding, or null
     * if the file is corrupt / un-decodable.
     */
    public static RecoveryCandidate validate(RecoveryCandidate candidate) {
        if (candidate == null) return null;
        String path = candidate.sourceUriOrPath;
        if (path == null || path.isEmpty()) return candidate;

        String mime = candidate.mimeDetected;
        if (mime == null) mime = "";
        String ml = mime.toLowerCase(Locale.US);

        // Only deep-validate video and audio — images already get
        // BitmapFactory bounds-check in the file-tree scan.
        if (!ml.startsWith("video/") && !ml.startsWith("audio/")) {
            return candidate;
        }

        // Skip content:// URIs — we cannot pass them to FFmpeg's file
        // I/O layer without a custom AVIO context.
        if (path.startsWith("content://")) {
            return candidate;
        }

        File file = new File(path);
        if (!file.isFile() || !file.canRead()) return candidate;

        FfmpegDeepValidator.ProbeResult result = FfmpegDeepValidator.probe(path);
        if (!result.valid) {
            ScanDiagnostics.debug("ffmpeg_reject path=" + path + " reason=" + result.detail);
            return null;
        }

        // Enrich with real dimensions when available
        if (result.width > 0 && result.height > 0
                && candidate.width <= 0 && candidate.height <= 0) {
            return new RecoveryCandidate.Builder()
                    .candidateId(candidate.candidateId)
                    .sourceKind(candidate.sourceKind)
                    .sourceUriOrPath(candidate.sourceUriOrPath)
                    .extractionMethod(candidate.extractionMethod + "+ffmpeg_validated")
                    .originalContainer(candidate.originalContainer)
                    .byteLength(candidate.byteLength)
                    .sha256(candidate.sha256)
                    .mimeDetected(candidate.mimeDetected)
                    .decodeStatus("FFMPEG_OK")
                    .width(result.width)
                    .height(result.height)
                    .label(candidate.label)
                    .build();
        }

        return candidate;
    }
}
