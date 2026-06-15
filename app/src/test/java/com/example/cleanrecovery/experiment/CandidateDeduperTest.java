package com.example.cleanrecovery.experiment;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CandidateDeduperTest {
    @Test
    public void dedupesBySha256() {
        RecoveryCandidate first = new RecoveryCandidate.Builder()
                .candidateId("a")
                .sourceUriOrPath("/storage/a.jpg")
                .sha256("abc")
                .build();
        RecoveryCandidate duplicate = new RecoveryCandidate.Builder()
                .candidateId("b")
                .sourceUriOrPath("/storage/b.jpg")
                .sha256("abc")
                .build();

        CandidateDeduper deduper = new CandidateDeduper();
        List<RecoveryCandidate> unique = deduper.dedupe(Arrays.asList(first, duplicate));

        assertEquals(1, unique.size());
        assertEquals(1, deduper.getDuplicateCount());
    }

    @Test
    public void dedupesByContentUri() {
        RecoveryCandidate first = new RecoveryCandidate.Builder()
                .sourceUriOrPath("content://media/external/images/media/1")
                .build();
        RecoveryCandidate duplicate = new RecoveryCandidate.Builder()
                .sourceUriOrPath("content://media/external/images/media/1")
                .build();

        CandidateDeduper deduper = new CandidateDeduper();
        List<RecoveryCandidate> unique = deduper.dedupe(Arrays.asList(first, duplicate));

        assertEquals(1, unique.size());
    }
}
