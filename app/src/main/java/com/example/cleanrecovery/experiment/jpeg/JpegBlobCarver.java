package com.example.cleanrecovery.experiment.jpeg;

import com.example.cleanrecovery.experiment.CandidateLabel;
import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class JpegBlobCarver {
    public interface Progress {
        void onCandidateFound(RecoveryCandidate candidate);
        boolean isCancelled();
    }

    private final JpegStructureParser parser = new JpegStructureParser();
    private final JpegFragmentationValidator validator = new JpegFragmentationValidator();

    private static final long MAX_STREAM_CANDIDATE_SLICE = 1_024L * 1_024L;

    public List<RecoveryCandidate> carve(File containerFile, Progress progress) throws IOException {
        long size = containerFile.length();
        if (size > JpegCarveLimits.MAX_CONTAINER_BYTES) {
            throw new IOException("container_exceeds_limit");
        }
        byte[] data = readAllBytes(containerFile, (int) size);
        return carveBytes(containerFile.getAbsolutePath(), data, progress);
    }

    private static byte[] readAllBytes(File file, int byteCount) throws IOException {
        byte[] buffer = new byte[byteCount];
        try (FileInputStream inputStream = new FileInputStream(file)) {
            int offset = 0;
            while (offset < byteCount) {
                int read = inputStream.read(buffer, offset, byteCount - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        }
        return buffer;
    }

    public List<RecoveryCandidate> carveBytes(String containerLabel, byte[] data, Progress progress) {
        ArrayList<RecoveryCandidate> candidates = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        if (progress != null && progress.isCancelled()) {
            cancelled.set(true);
        }
        long started = System.currentTimeMillis();
        for (int startOffset : parser.findSoiOffsets(data)) {
            if (cancelled.get() || candidates.size() >= JpegCarveLimits.MAX_CANDIDATES_PER_CONTAINER) {
                break;
            }
            JpegStructureParser.ParseResult parseResult = parser.parse(data, startOffset);
            JpegFragmentationValidator.ValidationResult validation = validator.validate(data, startOffset);
            if (!parseResult.valid && !validation.partialDetected) {
                continue;
            }
            int endOffset = parseResult.valid
                    ? parseResult.endOffset
                    : boundedPartialEnd(data, startOffset, parseResult, validation);
            long length = endOffset - startOffset;
            if (length <= 0 || length > JpegCarveLimits.MAX_CANDIDATE_BYTES) {
                continue;
            }
            byte[] slice = new byte[(int) length];
            System.arraycopy(data, startOffset, slice, 0, (int) length);
            // Hash only this slice — not the entire container.
            String sha256 = sha256(slice);
            RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                    .candidateId(UUID.randomUUID().toString())
                    .sourceKind(CandidateSourceKind.CARVED_FROM_KNOWN_BLOB)
                    .sourceUriOrPath(containerLabel + "#" + startOffset)
                    .extractionMethod(parseResult.valid ? "structured_jpeg_parser" : "structured_jpeg_parser_partial")
                    .originalContainer(containerLabel)
                    .byteLength(length)
                    .sha256(sha256)
                    .mimeDetected("image/jpeg")
                    .decodeStatus(parseResult.valid ? "COMPLETE" : validation.reason)
                    .extractionOffsetStart(startOffset)
                    .extractionOffsetEnd(endOffset)
                    .readBytes(length)
                    .elapsedMs(System.currentTimeMillis() - started)
                    .label(parseResult.valid ? CandidateLabel.BLOB_EXTRACTED : CandidateLabel.UNVERIFIED)
                    .build();
            candidates.add(candidate);
            if (progress != null) {
                progress.onCandidateFound(candidate);
                if (progress.isCancelled()) {
                    break;
                }
            }
        }
        return dedupeOverlappingCandidates(candidates);
    }

    private static int boundedPartialEnd(
            byte[] data,
            int startOffset,
            JpegStructureParser.ParseResult parseResult,
            JpegFragmentationValidator.ValidationResult validation
    ) {
        if (validation.fragmentationPoint > startOffset && validation.fragmentationPoint <= data.length) {
            return validation.fragmentationPoint;
        }
        return Math.min(data.length, startOffset + Math.max(parseResult.endOffset - startOffset, 256));
    }

    private static List<RecoveryCandidate> dedupeOverlappingCandidates(List<RecoveryCandidate> candidates) {
        if (candidates.size() <= 1) {
            return candidates;
        }
        ArrayList<RecoveryCandidate> sorted = new ArrayList<>(candidates);
        Collections.sort(sorted, new Comparator<RecoveryCandidate>() {
            @Override
            public int compare(RecoveryCandidate left, RecoveryCandidate right) {
                int byStart = Long.compare(left.extractionOffsetStart, right.extractionOffsetStart);
                if (byStart != 0) {
                    return byStart;
                }
                return Long.compare(right.byteLength, left.byteLength);
            }
        });
        ArrayList<RecoveryCandidate> kept = new ArrayList<>();
        for (RecoveryCandidate candidate : sorted) {
            if (isContainedInAny(candidate, kept)) {
                continue;
            }
            kept.add(candidate);
        }
        Collections.sort(kept, new Comparator<RecoveryCandidate>() {
            @Override
            public int compare(RecoveryCandidate left, RecoveryCandidate right) {
                return Long.compare(left.extractionOffsetStart, right.extractionOffsetStart);
            }
        });
        return kept;
    }

    private static boolean isContainedInAny(RecoveryCandidate candidate, List<RecoveryCandidate> kept) {
        long start = candidate.extractionOffsetStart;
        long end = candidate.extractionOffsetEnd;
        for (RecoveryCandidate other : kept) {
            if (other.extractionOffsetStart <= start && other.extractionOffsetEnd >= end) {
                if (other.decodeStatus.equals("COMPLETE") && !candidate.decodeStatus.equals("COMPLETE")) {
                    return true;
                }
                if (other.decodeStatus.equals(candidate.decodeStatus) && other.byteLength >= candidate.byteLength) {
                    return true;
                }
            }
        }
        return false;
    }

    public List<RecoveryCandidate> carveHeaderFooterBaseline(String containerLabel, byte[] data) {
        ArrayList<RecoveryCandidate> candidates = new ArrayList<>();
        for (int start = 0; start + 1 < data.length; start++) {
            if (((data[start] & 0xFF) == 0xFF) && ((data[start + 1] & 0xFF) == 0xD8)) {
                int end = findFooter(data, start + 2);
                if (end < 0) {
                    continue;
                }
                int length = end - start;
                if (length <= 0 || length > JpegCarveLimits.MAX_CANDIDATE_BYTES) {
                    continue;
                }
                byte[] slice = new byte[length];
                System.arraycopy(data, start, slice, 0, length);
                candidates.add(new RecoveryCandidate.Builder()
                        .candidateId(UUID.randomUUID().toString())
                        .sourceKind(CandidateSourceKind.CARVED_FROM_KNOWN_BLOB)
                        .sourceUriOrPath(containerLabel + "#hf@" + start)
                        .extractionMethod("header_footer_baseline")
                        .originalContainer(containerLabel)
                        .byteLength(length)
                        .sha256(sha256(slice))
                        .mimeDetected("image/jpeg")
                        .decodeStatus("BASELINE")
                        .extractionOffsetStart(start)
                        .extractionOffsetEnd(end)
                        .label(CandidateLabel.BLOB_EXTRACTED)
                        .build());
            }
        }
        return candidates;
    }

    private static int findFooter(byte[] data, int from) {
        for (int index = from; index + 1 < data.length; index++) {
            if (((data[index] & 0xFF) == 0xFF) && ((data[index + 1] & 0xFF) == 0xD9)) {
                return index + 2;
            }
        }
        return -1;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format(Locale.US, "%02x", value));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }
}
