package com.example.cleanrecovery.experiment.jpeg;

public final class JpegFragmentationValidator {
    public static final class ValidationResult {
        public final boolean validCompleteJpeg;
        public final boolean partialDetected;
        public final int fragmentationPoint;
        public final String reason;

        public ValidationResult(boolean validCompleteJpeg, boolean partialDetected, int fragmentationPoint, String reason) {
            this.validCompleteJpeg = validCompleteJpeg;
            this.partialDetected = partialDetected;
            this.fragmentationPoint = fragmentationPoint;
            this.reason = reason;
        }
    }

    private final JpegStructureParser parser = new JpegStructureParser();

    public ValidationResult validate(byte[] data, int startOffset) {
        JpegStructureParser.ParseResult parseResult = parser.parse(data, startOffset);
        if (parseResult.valid) {
            if (hasSuspiciousEntropyPattern(data, parseResult.endOffset)) {
                return new ValidationResult(true, true, parseResult.endOffset, "post_eoi_entropy");
            }
            return new ValidationResult(true, false, -1, "");
        }
        if (isPostSosFailure(parseResult)) {
            int point = parseResult.failureReason.startsWith("unexpected_non_marker_at_")
                    ? parseFragmentationPoint(parseResult.failureReason)
                    : parseResult.endOffset;
            return new ValidationResult(false, true, point, parseResult.failureReason);
        }
        return new ValidationResult(false, false, -1, parseResult.failureReason);
    }

    private static boolean isPostSosFailure(JpegStructureParser.ParseResult parseResult) {
        if (!parseResult.sawSos) {
            return "entropy_without_eoi".equals(parseResult.failureReason);
        }
        return "entropy_without_eoi".equals(parseResult.failureReason)
                || "missing_eoi".equals(parseResult.failureReason)
                || parseResult.failureReason.startsWith("unexpected_non_marker_at_");
    }

    private static int parseFragmentationPoint(String failureReason) {
        String prefix = "unexpected_non_marker_at_";
        if (!failureReason.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(failureReason.substring(prefix.length()));
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private boolean hasSuspiciousEntropyPattern(byte[] data, int endOffset) {
        int trailing = 0;
        for (int index = endOffset; index < data.length; index++) {
            if (data[index] != 0x00) {
                trailing++;
            }
            if (trailing > 32) {
                return true;
            }
        }
        return false;
    }
}
