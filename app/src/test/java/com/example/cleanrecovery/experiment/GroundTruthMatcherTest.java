package com.example.cleanrecovery.experiment;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class GroundTruthMatcherTest {
    @Test
    public void gradesExactSha256Match() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .sha256("deadbeef")
                .build();
        GroundTruthEntry truth = new GroundTruthEntry("gt-1", "/orig.jpg", "deadbeef", "", true, false);

        RecoveryCandidate graded = GroundTruthMatcher.gradeAgainstGroundTruth(candidate, List.of(truth));

        assertTrue(graded.exactGroundTruthMatch);
        assertEquals(ResultGrade.EXACT_BYTES, graded.grade);
    }

    @Test
    public void gradesDerivativeByPerceptualHash() {
        RecoveryCandidate candidate = new RecoveryCandidate.Builder()
                .perceptualHash("phash-1")
                .build();
        GroundTruthEntry truth = new GroundTruthEntry("gt-2", "/orig.jpg", "other", "phash-1", false, true);

        RecoveryCandidate graded = GroundTruthMatcher.gradeAgainstGroundTruth(candidate, List.of(truth));

        assertTrue(graded.derivativeMatch);
        assertEquals(ResultGrade.VALID_DERIVATIVE, graded.grade);
    }
}
