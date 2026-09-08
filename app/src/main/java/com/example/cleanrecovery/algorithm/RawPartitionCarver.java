package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.algorithm.carve.CarvedHit;
import com.example.cleanrecovery.algorithm.carve.SignatureCarver;
import com.example.cleanrecovery.experiment.CandidateLabel;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.experiment.ResultGrade;
import com.example.cleanrecovery.recovery.RecoveryType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Carves embedded files out of a raw partition byte stream (read via root
 * {@code dd}). Reads overlapping windows so payloads straddling a window
 * boundary are still found, slices each hit into the app-private staging
 * directory, and emits a candidate that points at the staged file —
 * {@code RecoveryCopier} then copies it normally.
 *
 * <p>This is signature carving over raw bytes (delegated to
 * {@link SignatureCarver}), not a filesystem parser. The caller supplies the
 * {@link CandidateSourceKind} and extraction-method label that honestly
 * describe the partition the bytes came from.
 */
public final class RawPartitionCarver {
    static final int WINDOW_BYTES = 8 * 1024 * 1024;
    static final int OVERLAP_BYTES = 2 * 1024 * 1024;
    // Safety caps so a multi-GB partition can't run unbounded on-device.
    static final long DEFAULT_MAX_BYTES = 4L * 1024L * 1024L * 1024L;
    static final int DEFAULT_MAX_CANDIDATES = 5_000;

    private final SignatureCarver carver = new SignatureCarver();
    private final long maxBytes;
    private final int maxCandidates;
    private final RecoveryType typeFilter;

    public RawPartitionCarver() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_CANDIDATES, null);
    }

    public RawPartitionCarver(RecoveryType typeFilter) {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_CANDIDATES, typeFilter);
    }

    RawPartitionCarver(long maxBytes, int maxCandidates) {
        this(maxBytes, maxCandidates, null);
    }

    RawPartitionCarver(long maxBytes, int maxCandidates, RecoveryType typeFilter) {
        this.maxBytes = maxBytes;
        this.maxCandidates = maxCandidates;
        this.typeFilter = typeFilter;
    }

    /**
     * @return number of candidates emitted.
     */
    public int carveStream(
            InputStream input,
            File stagingDir,
            CandidateSourceKind sourceKind,
            String extractionMethod,
            AlgorithmCallback callback
    ) throws IOException {
        if (input == null || stagingDir == null) {
            return 0;
        }
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw new IOException("cannot_create_staging_dir");
        }
        Set<String> seenHashes = new HashSet<>();
        byte[] window = new byte[WINDOW_BYTES];
        int carry = 0;            // bytes retained from previous window (overlap)
        long basePosition = 0;    // partition offset of window[0]
        long totalRead = 0;
        int emitted = 0;

        while (!callback.isCancelled() && totalRead < maxBytes && emitted < maxCandidates) {
            int filled = fill(input, window, carry);
            if (filled == carry) {
                break; // no new bytes
            }
            totalRead += (filled - carry);

            byte[] view = filled == window.length ? window : trimmed(window, filled);
            emitted += carveWindow(view, basePosition, "", stagingDir, sourceKind,
                    extractionMethod, seenHashes, callback, emitted);

            callback.onProgress((int) (totalRead / WINDOW_BYTES),
                    sourceKind + " @" + basePosition);

            if (filled < window.length) {
                break; // reached EOF
            }
            // Retain a tail overlap so a payload spanning the boundary is recoverable.
            carry = Math.min(OVERLAP_BYTES, filled);
            System.arraycopy(window, filled - carry, window, 0, carry);
            basePosition += (filled - carry);
        }
        return emitted;
    }

    /**
     * Carve a single in-memory buffer (used by plaintext residual scans and tests).
     */
    public int carveBytes(
            byte[] data,
            long basePosition,
            File stagingDir,
            CandidateSourceKind sourceKind,
            String extractionMethod,
            AlgorithmCallback callback
    ) throws IOException {
        return carveBytes(data, basePosition, "", stagingDir, sourceKind, extractionMethod, callback);
    }

    /**
     * Carve a single in-memory buffer with a per-source tag so staged file names
     * never collide between different sources (otherwise the deduper would
     * discard every hit after the first, and staged files would overwrite each
     * other — both when carving whole files whose payload starts at offset 0).
     */
    public int carveBytes(
            byte[] data,
            long basePosition,
            String sourceTag,
            File stagingDir,
            CandidateSourceKind sourceKind,
            String extractionMethod,
            AlgorithmCallback callback
    ) throws IOException {
        if (data == null || stagingDir == null) {
            return 0;
        }
        if (!stagingDir.exists() && !stagingDir.mkdirs()) {
            throw new IOException("cannot_create_staging_dir");
        }
        return carveWindow(data, basePosition, sourceTag, stagingDir, sourceKind, extractionMethod,
                new HashSet<String>(), callback, 0);
    }

    private int carveWindow(
            byte[] view,
            long basePosition,
            String sourceTag,
            File stagingDir,
            CandidateSourceKind sourceKind,
            String extractionMethod,
            Set<String> seenHashes,
            AlgorithmCallback callback,
            int emittedSoFar
    ) throws IOException {
        List<CarvedHit> carved = carver.carveWindow(view, typeFilter, new SignatureCarver.Progress() {
            @Override
            public boolean isCancelled() {
                return callback.isCancelled();
            }
        });

        int emitted = 0;
        for (CarvedHit hit : carved) {
            if (callback.isCancelled() || emittedSoFar + emitted >= maxCandidates) {
                break;
            }
            if (hit.start < 0 || hit.end <= hit.start || hit.end > view.length) {
                continue;
            }
            if (hit.sha256 != null && !hit.sha256.isEmpty() && !seenHashes.add(hit.sha256)) {
                continue; // dedupe across overlapping windows
            }
            int length = hit.length();
            String tagPrefix = sourceTag == null || sourceTag.isEmpty() ? "" : sourceTag + "_";
            File staged = new File(stagingDir,
                    "offline_" + tagPrefix + (basePosition + hit.start) + "." + hit.extension);
            try (FileOutputStream out = new FileOutputStream(staged)) {
                out.write(view, hit.start, length);
            }
            callback.onCandidate(new RecoveryCandidate.Builder()
                    .candidateId(staged.getAbsolutePath())
                    .sourceKind(sourceKind)
                    .sourceUriOrPath(staged.getAbsolutePath())
                    .extractionMethod(extractionMethod)
                    .originalContainer("partition@" + (basePosition + hit.start))
                    .byteLength(length)
                    .sha256(hit.sha256)
                    .mimeDetected(hit.mime)
                    .decodeStatus(hit.decodeStatus)
                    .label(CandidateLabel.BLOB_EXTRACTED)
                    .grade(ResultGrade.PARTIAL_BYTES)
                    .build());
            emitted++;
        }
        return emitted;
    }

    /** Fill window[from..] from the stream, returning total valid bytes. */
    private static int fill(InputStream input, byte[] window, int from) throws IOException {
        int offset = from;
        while (offset < window.length) {
            int read = input.read(window, offset, window.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return offset;
    }

    private static byte[] trimmed(byte[] window, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(window, 0, copy, 0, length);
        return copy;
    }
}
