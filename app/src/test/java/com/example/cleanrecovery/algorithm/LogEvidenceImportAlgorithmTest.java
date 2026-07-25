package com.example.cleanrecovery.algorithm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.cleanrecovery.experiment.CandidateSourceKind;
import com.example.cleanrecovery.experiment.RecoveryCandidate;
import com.example.cleanrecovery.experiment.ResultGrade;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

public final class LogEvidenceImportAlgorithmTest {

    @Test
    public void existingFileIsNotStale() throws IOException {
        File file = File.createTempFile("evidence-present", ".bin");
        file.deleteOnExit();
        assertFalse(LogEvidenceImportAlgorithm.isStaleRecord(file.getAbsolutePath()));
    }

    @Test
    public void missingPathIsStale() {
        assertTrue(LogEvidenceImportAlgorithm.isStaleRecord(
                "/storage/emulated/0/DCIM/deleted-" + System.nanoTime() + ".jpg"));
    }

    @Test
    public void nullOrEmptyPathIsNotStale() {
        assertFalse(LogEvidenceImportAlgorithm.isStaleRecord(null));
        assertFalse(LogEvidenceImportAlgorithm.isStaleRecord(""));
    }

    @Test
    public void evidenceCandidateIsLabelledNonRecoverable() {
        RecoveryCandidate candidate = LogEvidenceImportAlgorithm.buildEvidence(
                "/storage/emulated/0/DCIM/gone.jpg", "content://media/external/images/media/42",
                1234L, "image/jpeg");
        assertEquals(CandidateSourceKind.LOG_EVIDENCE_ONLY, candidate.sourceKind);
        assertEquals(ResultGrade.METADATA_ONLY, candidate.grade);
        assertEquals("FILE_MISSING", candidate.errorCode);
        assertEquals("EVIDENCE_ONLY", candidate.decodeStatus);
    }
}
