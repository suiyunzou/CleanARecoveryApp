package com.example.cleanrecovery.experiment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

public final class ExperimentReportWriter {
    private ExperimentReportWriter() {
    }

    public static void writeCandidatesCsv(File output, List<RecoveryCandidate> candidates) throws IOException {
        File parent = output.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(output),
                StandardCharsets.UTF_8
        ))) {
            writer.write("candidateId,sourceKind,sourceUriOrPath,extractionMethod,originalContainer,byteLength,sha256,perceptualHash,mimeDetected,decodeStatus,width,height,exactGroundTruthMatch,derivativeMatch,extractionOffsetStart,extractionOffsetEnd,readBytes,elapsedMs,errorCode,label,grade");
            writer.newLine();
            for (RecoveryCandidate candidate : candidates) {
                writer.write(String.format(Locale.US,
                        "%s,%s,%s,%s,%s,%d,%s,%s,%s,%s,%d,%d,%s,%s,%d,%d,%d,%d,%s,%s,%s",
                        csvEscape(candidate.candidateId),
                        candidate.sourceKind,
                        csvEscape(candidate.sourceUriOrPath),
                        csvEscape(candidate.extractionMethod),
                        csvEscape(candidate.originalContainer),
                        candidate.byteLength,
                        csvEscape(candidate.sha256),
                        csvEscape(candidate.perceptualHash),
                        csvEscape(candidate.mimeDetected),
                        csvEscape(candidate.decodeStatus),
                        candidate.width,
                        candidate.height,
                        candidate.exactGroundTruthMatch,
                        candidate.derivativeMatch,
                        candidate.extractionOffsetStart,
                        candidate.extractionOffsetEnd,
                        candidate.readBytes,
                        candidate.elapsedMs,
                        csvEscape(candidate.errorCode),
                        candidate.label,
                        candidate.grade
                ));
                writer.newLine();
            }
        }
    }

    public static void writeMetricsJson(File output, ExperimentMetrics metrics) throws IOException {
        File parent = output.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(output),
                StandardCharsets.UTF_8
        ))) {
            writer.write(metrics.toJson());
        }
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
