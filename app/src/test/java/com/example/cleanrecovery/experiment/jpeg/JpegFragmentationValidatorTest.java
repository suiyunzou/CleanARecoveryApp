package com.example.cleanrecovery.experiment.jpeg;

import com.example.cleanrecovery.experiment.FakeCorpus;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class JpegFragmentationValidatorTest {
    @Test
    public void validMinimalJpegIsCompleteNotPartial() {
        JpegFragmentationValidator validator = new JpegFragmentationValidator();
        JpegFragmentationValidator.ValidationResult result =
                validator.validate(FakeCorpus.minimalJpegBytes(), 0);

        assertTrue(result.validCompleteJpeg);
        assertFalse(result.partialDetected);
    }

    @Test
    public void corruptedHeaderIsNotPartialFragmentation() {
        JpegFragmentationValidator validator = new JpegFragmentationValidator();
        JpegFragmentationValidator.ValidationResult result =
                validator.validate(FakeCorpus.corruptedJpegBytes(), 0);

        assertFalse(result.validCompleteJpeg);
        assertFalse(result.partialDetected);
    }
}
