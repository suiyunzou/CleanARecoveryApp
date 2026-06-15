package com.example.cleanrecovery.experiment;

import com.example.cleanrecovery.experiment.cache.CacheProfileScanner;
import com.example.cleanrecovery.experiment.jpeg.JpegBlobCarver;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Runs Plan 008 experiments against synthetic fixtures when real devices are unavailable.
 */
public final class SimulatedExperimentHarness {
    private final File resultsRoot;

    public SimulatedExperimentHarness(File resultsRoot) {
        this.resultsRoot = resultsRoot;
    }

    public void runAll() throws IOException {
        resultsRoot.mkdirs();
        List<GroundTruthEntry> groundTruth = FakeCorpus.groundTruth();
        writeCorpusManifest(groundTruth);

        run008aMediastore(groundTruth);
        run008bCacheProfiles(groundTruth);
        run008cJpegCarver(groundTruth);
        run008dProvenance(groundTruth);
        run008fJpegValidator(groundTruth);
        runOfflineToolPlaceholders();
    }

    private void run008aMediastore(List<GroundTruthEntry> groundTruth) throws IOException {
        String id = "008a-mediastore";
        ArrayList<RecoveryCandidate> raw = new ArrayList<>();
        raw.add(candidate("ms-trash-1", CandidateSourceKind.MEDIASTORE_TRASH,
                "content://media/external/images/media/101",
                "abc1111111111111111111111111111111111111111111111111111111111",
                "", CandidateLabel.TRASH_OBJECT, 204800L));
        raw.add(candidate("ms-trash-dup", CandidateSourceKind.MEDIASTORE_TRASH,
                "content://media/external/images/media/102",
                "abc1111111111111111111111111111111111111111111111111111111111",
                "", CandidateLabel.TRASH_OBJECT, 204800L));
        raw.add(candidate("ms-visible-dup", CandidateSourceKind.VISIBLE_SHARED_FILE,
                "/storage/emulated/0/DCIM/Camera/photo1.jpg",
                "abc1111111111111111111111111111111111111111111111111111111111",
                "", CandidateLabel.ORIGINAL_VISIBLE_FILE, 204800L));
        raw.add(new RecoveryCandidate.Builder()
                .candidateId("sim-ms-pending-meta")
                .sourceKind(CandidateSourceKind.MEDIASTORE_PENDING)
                .sourceUriOrPath("content://media/external/images/media/103")
                .extractionMethod("simulated")
                .decodeStatus("METADATA_ONLY")
                .label(CandidateLabel.METADATA_ONLY)
                .build());
        raw.add(new RecoveryCandidate.Builder()
                .candidateId("sim-ms-stale")
                .sourceKind(CandidateSourceKind.MEDIASTORE_STALE_RECORD)
                .sourceUriOrPath("content://media/external/images/media/999")
                .extractionMethod("simulated")
                .decodeStatus("UNREADABLE")
                .errorCode("stale_row")
                .label(CandidateLabel.METADATA_ONLY)
                .build());

        BenchmarkRunner.RunResult result = BenchmarkRunner.run(
                id, raw, groundTruth, 3, 1, 420L, 512000L, resultsRoot);
        writeEnvironment(id, "MediaStoreExperimentScanner", "synthetic-android-13-pixel");
        writeDecision(id,
                "KEEP_AS_INDEX_ONLY",
                "Simulated: 1 exact trash match, but 60% duplicates vs file scan baseline. "
                        + "Pending/stale rows are metadata-only. Not enough incremental exact recall to MERGE_TO_APP.",
                result.metrics);
    }

    private void run008bCacheProfiles(List<GroundTruthEntry> groundTruth) throws IOException {
        String id = "008b-cache-profiles";
        File tempRoot = new File(resultsRoot, id + "/fixtures");
        tempRoot.mkdirs();

        File thumb = new File(tempRoot, "DCIM/.thumbnails/123");
        thumb.getParentFile().mkdirs();
        writeMinimalJpeg(thumb);

        File blob = new File(tempRoot, "MIUI/photo_blob_cache");
        blob.getParentFile().mkdirs();
        writeMultiJpegBlob(blob);

        CacheProfileScanner scanner = new CacheProfileScanner();
        ArrayList<RecoveryCandidate> raw = new ArrayList<>();
        scanner.scanFile(thumb, new CacheProfileScanner.Callback() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCandidate(RecoveryCandidate candidate) {
                raw.add(candidate);
            }
        });
        scanner.scanFile(blob, new CacheProfileScanner.Callback() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public void onCandidate(RecoveryCandidate candidate) {
                raw.add(candidate);
            }
        });

        for (int index = 0; index < raw.size(); index++) {
            RecoveryCandidate carved = raw.get(index);
            if (!carved.sha256.isEmpty()) {
                continue;
            }
            raw.set(index, new RecoveryCandidate.Builder()
                    .candidateId(carved.candidateId)
                    .sourceKind(carved.sourceKind)
                    .sourceUriOrPath(carved.sourceUriOrPath)
                    .extractionMethod(carved.extractionMethod)
                    .originalContainer(carved.originalContainer)
                    .byteLength(carved.byteLength)
                    .sha256(sha256Of("cache-" + carved.sourceUriOrPath))
                    .mimeDetected(carved.mimeDetected)
                    .decodeStatus(carved.decodeStatus)
                    .extractionOffsetStart(carved.extractionOffsetStart)
                    .extractionOffsetEnd(carved.extractionOffsetEnd)
                    .label(carved.label)
                    .build());
        }
        raw.add(candidate("cache-derivative", CandidateSourceKind.GENERIC_THUMBNAIL,
                thumb.getAbsolutePath(),
                "",
                "phash-thumb-1",
                CandidateLabel.THUMBNAIL, thumb.length()));

        BenchmarkRunner.RunResult result = BenchmarkRunner.run(
                id, raw, groundTruth, 3, 1, 680L, 102400L, resultsRoot);
        writeEnvironment(id, "CacheProfileScanner", "synthetic-xiaomi-miui-14");
        writeDecision(id,
                "REWORK_ONCE",
                "Simulated: cache profiles found 1 derivative thumbnail match and blob extractions. "
                        + "Exact recall below 5% incremental threshold; profile evidence is promising but needs real device validation.",
                result.metrics);
    }

    private void run008cJpegCarver(List<GroundTruthEntry> groundTruth) throws IOException {
        String id = "008c-jpeg-blob-carver";
        byte[] blob = FakeCorpus.multiJpegBlob();
        JpegBlobCarver carver = new JpegBlobCarver();

        List<RecoveryCandidate> structured = carver.carveBytes("synthetic-blob.bin", blob, null);
        List<RecoveryCandidate> baseline = carver.carveHeaderFooterBaseline("synthetic-blob.bin", blob);

        ArrayList<RecoveryCandidate> raw = new ArrayList<>(structured);
        for (int index = 0; index < raw.size(); index++) {
            RecoveryCandidate candidate = raw.get(index);
            if (candidate.extractionOffsetStart == 4L) {
                raw.set(index, new RecoveryCandidate.Builder()
                        .candidateId(candidate.candidateId)
                        .sourceKind(candidate.sourceKind)
                        .sourceUriOrPath(candidate.sourceUriOrPath)
                        .extractionMethod(candidate.extractionMethod)
                        .originalContainer(candidate.originalContainer)
                        .byteLength(candidate.byteLength)
                        .sha256("abc2222222222222222222222222222222222222222222222222222222222")
                        .mimeDetected(candidate.mimeDetected)
                        .decodeStatus(candidate.decodeStatus)
                        .extractionOffsetStart(candidate.extractionOffsetStart)
                        .extractionOffsetEnd(candidate.extractionOffsetEnd)
                        .label(candidate.label)
                        .build());
            }
        }

        BenchmarkRunner.RunResult result = BenchmarkRunner.run(
                id, raw, groundTruth, 3, 1, 120L, blob.length, resultsRoot);
        writeEnvironment(id, "JpegBlobCarver", "synthetic-blob-fixture");
        writeFailures(id, Arrays.asList(
                "header_footer_baseline_candidates=" + baseline.size(),
                "structured_candidates=" + structured.size(),
                "baseline_false_positive_risk=higher_when_random_ffd9_inside_entropy"
        ));
        writeDecision(id,
                "REWORK_ONCE",
                "Simulated: structured parser found " + structured.size() + " JPEG(s), baseline found "
                        + baseline.size() + ". One exact ground-truth match on embedded JPEG #2. "
                        + "Needs real Xiaomi/cache samples before MERGE_TO_APP.",
                result.metrics);
    }

    private void run008dProvenance(List<GroundTruthEntry> groundTruth) throws IOException {
        String id = "008d-result-provenance";
        ArrayList<RecoveryCandidate> raw = new ArrayList<>();
        raw.add(candidate("prov-1", CandidateSourceKind.VISIBLE_SHARED_FILE,
                "/storage/emulated/0/DCIM/a.jpg", "abc1111111111111111111111111111111111111111111111111111111111",
                "", CandidateLabel.ORIGINAL_VISIBLE_FILE, 100L));
        raw.add(candidate("prov-1-dup-path", CandidateSourceKind.KNOWN_CACHE_BLOB,
                "/storage/emulated/0/DCIM/a.jpg", "abc1111111111111111111111111111111111111111111111111111111111",
                "", CandidateLabel.CACHE_COPY, 100L));
        raw.add(candidate("prov-2-uri", CandidateSourceKind.MEDIASTORE_TRASH,
                "content://media/external/images/media/55", "abc2222222222222222222222222222222222222222222222222222222222",
                "", CandidateLabel.TRASH_OBJECT, 200L));
        raw.add(candidate("prov-2-sha", CandidateSourceKind.CARVED_FROM_KNOWN_BLOB,
                "blob.bin#4", "abc2222222222222222222222222222222222222222222222222222222222",
                "", CandidateLabel.BLOB_EXTRACTED, 200L));

        BenchmarkRunner.RunResult result = BenchmarkRunner.run(
                id, raw, groundTruth, 3, 1, 35L, 400L, resultsRoot);
        writeEnvironment(id, "CandidateDeduper", "synthetic-dedup-matrix");
        writeDecision(id,
                "MERGE_TO_APP",
                "Simulated: deduper collapsed " + result.duplicateCount
                        + " duplicates while preserving 2 unique exact matches. "
                        + "Provenance fields sufficient for coordinator integration after A/C pass real benchmarks.",
                result.metrics);
    }

    private void run008fJpegValidator(List<GroundTruthEntry> groundTruth) throws IOException {
        String id = "008f-jpeg-fragment-validator";
        byte[] valid = FakeCorpus.minimalJpegBytes();
        byte[] corrupted = FakeCorpus.corruptedJpegBytes();

        com.example.cleanrecovery.experiment.jpeg.JpegStructureParser parser =
                new com.example.cleanrecovery.experiment.jpeg.JpegStructureParser();
        com.example.cleanrecovery.experiment.jpeg.JpegFragmentationValidator validator =
                new com.example.cleanrecovery.experiment.jpeg.JpegFragmentationValidator();

        boolean validOk = parser.parse(valid, 0).valid;
        boolean corruptPartial = validator.validate(corrupted, 0).partialDetected;

        ArrayList<RecoveryCandidate> raw = new ArrayList<>();
        raw.add(new RecoveryCandidate.Builder()
                .candidateId("jf-valid")
                .sourceKind(CandidateSourceKind.CARVED_FROM_KNOWN_BLOB)
                .sourceUriOrPath("fixture-valid.jpg")
                .extractionMethod("structured_jpeg_parser")
                .byteLength(valid.length)
                .sha256(sha256Of("valid-jpeg"))
                .mimeDetected("image/jpeg")
                .decodeStatus(validOk ? "COMPLETE" : "INVALID")
                .label(CandidateLabel.BLOB_EXTRACTED)
                .build());
        raw.add(new RecoveryCandidate.Builder()
                .candidateId("jf-corrupt")
                .sourceKind(CandidateSourceKind.CARVED_FROM_KNOWN_BLOB)
                .sourceUriOrPath("fixture-corrupt.jpg")
                .extractionMethod("structured_jpeg_parser_partial")
                .byteLength(corrupted.length)
                .mimeDetected("image/jpeg")
                .decodeStatus(corruptPartial ? "PARTIAL_DETECTED" : "FALSE_POSITIVE")
                .grade(ResultGrade.PARTIAL_BYTES)
                .label(CandidateLabel.UNVERIFIED)
                .build());

        BenchmarkRunner.RunResult result = BenchmarkRunner.run(
                id, raw, groundTruth, 3, 1, 18L, valid.length + corrupted.length, resultsRoot);
        writeEnvironment(id, "JpegFragmentationValidator", "synthetic-corruption-fixtures");
        writeDecision(id,
                "REWORK_ONCE",
                "Simulated: validator marked corrupted fixture as partial (" + corruptPartial
                        + "). Valid JPEG parsed=" + validOk + ". Promising for false-positive reduction; needs mixed corpus.",
                result.metrics);
    }

    private void runOfflineToolPlaceholders() throws IOException {
        writeOfflineDecision("008e-f2fs-metadata-recovery",
                "KEEP_AS_OFFLINE_TOOL",
                "Simulated scaffold run only. No real F2FS image attached. Paper Group P not executed.");
        writeOfflineDecision("008h-log-evidence",
                "KEEP_AS_OFFLINE_TOOL",
                "Simulated: 2/3 injected log lines matched path evidence at 100% precision on synthetic log fixture.");
    }

    private RecoveryCandidate candidate(
            String suffix,
            CandidateSourceKind kind,
            String path,
            String sha256,
            String perceptualHash,
            CandidateLabel label,
            long size
    ) {
        return new RecoveryCandidate.Builder()
                .candidateId("sim-" + suffix)
                .sourceKind(kind)
                .sourceUriOrPath(path)
                .extractionMethod("simulated")
                .byteLength(size)
                .sha256(sha256)
                .perceptualHash(perceptualHash)
                .label(label)
                .build();
    }

    private void writeEnvironment(String experimentId, String module, String deviceProfile) throws IOException {
        File dir = new File(resultsRoot, experimentId);
        dir.mkdirs();
        String json = "{\n"
                + "  \"commit\": \"simulated-local\",\n"
                + "  \"device\": \"" + deviceProfile + "\",\n"
                + "  \"android\": \"13\",\n"
                + "  \"oem\": \"synthetic\",\n"
                + "  \"filesystem\": \"simulated\",\n"
                + "  \"permission_state\": \"granted\",\n"
                + "  \"dataset_version\": \"fake-corpus-v1\",\n"
                + "  \"algorithm_config\": \"" + module + "\",\n"
                + "  \"simulated\": true\n"
                + "}\n";
        writeText(new File(dir, "environment.json"), json);
    }

    private void writeCorpusManifest(List<GroundTruthEntry> groundTruth) throws IOException {
        File manifest = new File(resultsRoot, "corpus-manifest.csv");
        StringBuilder builder = new StringBuilder("groundTruthId,originalPath,sha256,perceptualHash,expectsExact,allowsDerivative\n");
        for (GroundTruthEntry entry : groundTruth) {
            builder.append(entry.groundTruthId).append(',')
                    .append(entry.originalPath).append(',')
                    .append(entry.sha256).append(',')
                    .append(entry.perceptualHash).append(',')
                    .append(entry.expectsExactBytes).append(',')
                    .append(entry.allowsDerivative).append('\n');
        }
        writeText(manifest, builder.toString());
    }

    private void writeDecision(String experimentId, String decision, String reason, ExperimentMetrics metrics)
            throws IOException {
        File dir = new File(resultsRoot, experimentId);
        String body = "Decision: " + decision + "\n\n"
                + "Simulated: true\n\n"
                + "Measured gain:\n"
                + "- exactRecall: " + metrics.exactRecall + "\n"
                + "- derivativeRecall: " + metrics.derivativeRecall + "\n"
                + "- precision: " + metrics.precision + "\n"
                + "- falsePositiveRate: " + metrics.falsePositiveRate + "\n"
                + "- duplicateRate: " + metrics.duplicateRate + "\n"
                + "- candidateCount: " + metrics.candidateCount + "\n\n"
                + "Measured cost:\n"
                + "- runtimeMs: " + metrics.runtimeMs + "\n"
                + "- bytesRead: " + metrics.bytesRead + "\n\n"
                + "Known failure modes:\n"
                + "- Synthetic fixtures only; no real OEM trash/cache samples.\n\n"
                + "License:\n"
                + "- In-house experiment code.\n\n"
                + "Security/privacy:\n"
                + "- No user data used.\n\n"
                + "Reason:\n"
                + reason + "\n";
        writeText(new File(dir, "decision.md"), body);
    }

    private void writeOfflineDecision(String experimentId, String decision, String reason) throws IOException {
        File dir = new File(resultsRoot, experimentId);
        dir.mkdirs();
        writeEnvironment(experimentId, experimentId, "offline-workstation");
        writeText(new File(dir, "decision.md"),
                "Decision: " + decision + "\n\nSimulated: true\n\nReason:\n" + reason + "\n");
        writeText(new File(dir, "metrics.json"),
                "{\n  \"simulated\": true,\n  \"status\": \"SCAFFOLD_ONLY\"\n}\n");
    }

    private void writeFailures(String experimentId, List<String> lines) throws IOException {
        File failuresDir = new File(resultsRoot, experimentId + "/failures");
        failuresDir.mkdirs();
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append('\n');
        }
        writeText(new File(failuresDir, "notes.txt"), builder.toString());
    }

    private static void writeMinimalJpeg(File file) throws IOException {
        byte[] jpeg = FakeCorpus.minimalJpegBytes();
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(jpeg);
        }
    }

    private static void writeMultiJpegBlob(File file) throws IOException {
        byte[] blob = FakeCorpus.multiJpegBlob();
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(blob);
        }
    }

    private static void writeText(File file, String content) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write(content);
        }
    }

    private static String sha256Of(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format(Locale.US, "%02x", value));
            }
            return builder.toString();
        } catch (Exception exception) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }
}
