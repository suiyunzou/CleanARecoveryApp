package com.example.cleanrecovery.experiment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class BenchmarkRunner {
    public static final class RunResult {
        public final List<RecoveryCandidate> uniqueCandidates;
        public final ExperimentMetrics metrics;
        public final int duplicateCount;

        public RunResult(List<RecoveryCandidate> uniqueCandidates, ExperimentMetrics metrics, int duplicateCount) {
            this.uniqueCandidates = uniqueCandidates;
            this.metrics = metrics;
            this.duplicateCount = duplicateCount;
        }
    }

    public static RunResult run(
            String experimentId,
            List<RecoveryCandidate> rawCandidates,
            Iterable<GroundTruthEntry> groundTruth,
            int groundTruthExactTotal,
            int groundTruthDerivativeTotal,
            long runtimeMs,
            long bytesRead,
            File outputDir
    ) throws IOException {
        CandidateDeduper deduper = new CandidateDeduper();
        List<RecoveryCandidate> graded = new java.util.ArrayList<>();
        for (RecoveryCandidate candidate : rawCandidates) {
            graded.add(GroundTruthMatcher.gradeAgainstGroundTruth(candidate, groundTruth));
        }
        List<RecoveryCandidate> unique = deduper.dedupe(graded);
        ExperimentMetrics metrics = ExperimentMetrics.fromCandidates(
                unique,
                groundTruthExactTotal,
                groundTruthDerivativeTotal,
                runtimeMs,
                bytesRead
        );
        metrics.duplicateCount = deduper.getDuplicateCount();
        metrics.duplicateRate = rawCandidates.isEmpty()
                ? 0.0d
                : deduper.getDuplicateCount() / (double) rawCandidates.size();

        File experimentDir = new File(outputDir, experimentId);
        experimentDir.mkdirs();
        ExperimentReportWriter.writeCandidatesCsv(new File(experimentDir, "candidates.csv"), unique);
        ExperimentReportWriter.writeMetricsJson(new File(experimentDir, "metrics.json"), metrics);
        File decisionFile = new File(experimentDir, "decision.md");
        if (!decisionFile.exists()) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(decisionFile),
                    StandardCharsets.UTF_8
            ))) {
                writer.write(defaultDecisionTemplate(experimentId));
            }
        }
        return new RunResult(unique, metrics, deduper.getDuplicateCount());
    }

    private static String defaultDecisionTemplate(String experimentId) {
        return "Decision:\n"
                + "- MERGE_TO_APP\n"
                + "- KEEP_AS_OFFLINE_TOOL\n"
                + "- REWORK_ONCE\n"
                + "- DELETE_BRANCH\n\n"
                + "Experiment: " + experimentId + "\n\n"
                + "Measured gain:\n"
                + "Measured cost:\n"
                + "Known failure modes:\n"
                + "License:\n"
                + "Security/privacy:\n"
                + "Reason:\n";
    }
}
