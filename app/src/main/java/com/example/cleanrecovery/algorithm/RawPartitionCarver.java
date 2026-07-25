package com.example.cleanrecovery.algorithm;

import com.example.cleanrecovery.experiment.CandidateLabel;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.experiment.ResultGrade;
import com.example.cleanrecovery.experiment.jpeg.JpegBlobCarver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Carves embedded files out of a raw partition byte stream (read via root
 * {@code dd}). Reads overlapping windows so JPEGs straddling a window boundary
 * are still found, slices each hit into the app-private staging directory, and
 * emits a candidate that points at the staged file — {@code RecoveryCopier}
 * then copies it normally, so no special copy path is needed.
 *
 * <p>This is signature carving over raw bytes, not a filesystem parser. The
 * caller supplies the {@link CandidateSourceKind} and extraction-method label
 * that honestly describe the partition the bytes came from.
 */
public final class RawPartitionCarver {
    static final int WINDOW_BYTES = 8 * 1024 * 1024;
    static final int OVERLAP_BYTES = 2 * 1024 * 1024;
    // Safety caps so a multi-GB partition can't run unbounded on-device.
    static final long DEFAULT_MAX_BYTES = 4L * 1024L * 1024L * 1024L;
    static final int DEFAULT_MAX_CANDIDATES = 5_000;

    private final JpegBlobCarver carver = new JpegBlobCarver();
    private final long maxBytes;
    private final int maxCandidates;

    public RawPartitionCarver() {
        this(DEFAULT_MAX_BYTES, DEFAULT_MAX_CANDIDATES);
    }

    RawPartitionCarver(long maxBytes, int maxCandidates) {
        this.maxBytes = maxBytes;
        this.maxCandidates = maxCandidates;
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
            emitted += carveWindow(view, basePosition, stagingDir, sourceKind,
                    extractionMethod, seenHashes, callback, emitted);

            callback.onProgress((int) (totalRead / WINDOW_BYTES),
                    sourceKind + " @" + basePosition);

            if (filled < window.length) {
                break; // reached EOF
            }
            // Retain a tail overlap so a JPEG spanning the boundary is recoverable.
            carry = Math.min(OVERLAP_BYTES, filled);
            System.arraycopy(window, filled - carry, window, 0, carry);
            basePosition += (filled - carry);
        }
        return emitted;
    }

    private int carveWindow(
            byte[] view,
            long basePosition,
            File stagingDir,
            CandidateSourceKind sourceKind,
            String extractionMethod,
            Set<String> seenHashes,
            AlgorithmCallback callback,
            int emittedSoFar
    ) throws IOException {
        List<RecoveryCandidate> carved = carver.carveBytes("partition", view, new JpegBlobCarver.Progress() {
            @Override
            public void onCandidateFound(RecoveryCandidate candidate) {
            }

            @Override
            public boolean isCancelled() {
                return callback.isCancelled();
            }
        });

        int emitted = 0;
        for (RecoveryCandidate carvedCandidate : carved) {
            if (callback.isCancelled() || emittedSoFar + emitted >= maxCandidates) {
                break;
            }
            int start = (int) carvedCandidate.extractionOffsetStart;
            int end = (int) carvedCandidate.extractionOffsetEnd;
            if (start < 0 || end <= start || end > view.length) {
                continue;
            }
            if (!seenHashes.add(carvedCandidate.sha256)) {
                continue; // dedupe across overlapping windows
            }
            int length = end - start;
            File staged = new File(stagingDir, "offline_" + (basePosition + start) + ".jpg");
            try (FileOutputStream out = new FileOutputStream(staged)) {
                out.write(view, start, length);
            }
            callback.onCandidate(new RecoveryCandidate.Builder()
                    .candidateId(staged.getAbsolutePath())
                    .sourceKind(sourceKind)
                    .sourceUriOrPath(staged.getAbsolutePath())
                    .extractionMethod(extractionMethod)
                    .originalContainer("partition@" + (basePosition + start))
                    .byteLength(length)
                    .sha256(carvedCandidate.sha256)
                    .mimeDetected("image/jpeg")
                    .decodeStatus(carvedCandidate.decodeStatus)
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
