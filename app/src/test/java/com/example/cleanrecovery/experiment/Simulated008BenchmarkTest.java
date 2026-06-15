package com.example.cleanrecovery.experiment;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertTrue;

public final class Simulated008BenchmarkTest {
    @Test
    public void runAllSimulatedExperiments() throws Exception {
        File resultsRoot = resolveProjectResultsRoot();
        SimulatedExperimentHarness harness = new SimulatedExperimentHarness(resultsRoot);
        harness.runAll();

        assertTrue(new File(resultsRoot, "corpus-manifest.csv").exists());
        assertTrue(new File(resultsRoot, "008a-mediastore/metrics.json").exists());
        assertTrue(new File(resultsRoot, "008a-mediastore/decision.md").exists());
        assertTrue(new File(resultsRoot, "008b-cache-profiles/decision.md").exists());
        assertTrue(new File(resultsRoot, "008c-jpeg-blob-carver/decision.md").exists());
        assertTrue(new File(resultsRoot, "008d-result-provenance/decision.md").exists());
        assertTrue(new File(resultsRoot, "008f-jpeg-fragment-validator/decision.md").exists());
        assertTrue(new File(resultsRoot, "008e-f2fs-metadata-recovery/decision.md").exists());
        assertTrue(new File(resultsRoot, "008h-log-evidence/decision.md").exists());
    }

    private static File resolveProjectResultsRoot() {
        File cwd = new File(System.getProperty("user.dir"));
        if (new File(cwd, "settings.gradle").exists()) {
            return new File(cwd, "results");
        }
        File parent = cwd.getParentFile();
        if (parent != null && new File(parent, "settings.gradle").exists()) {
            return new File(parent, "results");
        }
        return new File(cwd, "results");
    }
}
