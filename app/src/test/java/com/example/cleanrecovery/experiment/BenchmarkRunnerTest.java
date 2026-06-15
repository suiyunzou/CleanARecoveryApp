package com.example.cleanrecovery.experiment;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class BenchmarkRunnerTest {
    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesCandidatesAndMetrics() throws Exception {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .candidateId("c1")
                .sha256("abc")
                .sourceUriOrPath("/storage/photo.jpg")
                .build();
        GroundTruthEntry truth = new GroundTruthEntry("gt", "/orig.jpg", "abc", "", true, false);

        BenchmarkRunner.RunResult result = BenchmarkRunner.run(
                "008e-benchmark",
                List.of(candidate),
                List.of(truth),
                1,
                0,
                50L,
                1024L,
                temporaryFolder.getRoot()
        );

        assertEquals(1, result.uniqueCandidates.size());
        assertTrue(result.uniqueCandidates.get(0).exactGroundTruthMatch);
        assertTrue(new File(temporaryFolder.getRoot(), "008e-benchmark/candidates.csv").exists());
        assertTrue(new File(temporaryFolder.getRoot(), "008e-benchmark/metrics.json").exists());
        assertTrue(new File(temporaryFolder.getRoot(), "008e-benchmark/decision.md").exists());
    }
}
