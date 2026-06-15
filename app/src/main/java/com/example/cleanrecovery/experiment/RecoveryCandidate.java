package com.example.cleanrecovery.experiment;

public final class RecoveryCandidate {
    public final String candidateId;
    public final CandidateSourceKind sourceKind;
    public final String sourceUriOrPath;
    public final String extractionMethod;
    public final String originalContainer;
    public final long byteLength;
    public final String sha256;
    public final String perceptualHash;
    public final String mimeDetected;
    public final String decodeStatus;
    public final int width;
    public final int height;
    public final boolean exactGroundTruthMatch;
    public final boolean derivativeMatch;
    public final long extractionOffsetStart;
    public final long extractionOffsetEnd;
    public final long readBytes;
    public final long elapsedMs;
    public final String errorCode;
    public final CandidateLabel label;
    public final ResultGrade grade;

    public RecoveryCandidate(Builder builder) {
        candidateId = builder.candidateId;
        sourceKind = builder.sourceKind;
        sourceUriOrPath = builder.sourceUriOrPath;
        extractionMethod = builder.extractionMethod;
        originalContainer = builder.originalContainer;
        byteLength = builder.byteLength;
        sha256 = builder.sha256;
        perceptualHash = builder.perceptualHash;
        mimeDetected = builder.mimeDetected;
        decodeStatus = builder.decodeStatus;
        width = builder.width;
        height = builder.height;
        exactGroundTruthMatch = builder.exactGroundTruthMatch;
        derivativeMatch = builder.derivativeMatch;
        extractionOffsetStart = builder.extractionOffsetStart;
        extractionOffsetEnd = builder.extractionOffsetEnd;
        readBytes = builder.readBytes;
        elapsedMs = builder.elapsedMs;
        errorCode = builder.errorCode;
        label = builder.label;
        grade = builder.grade;
    }

    public static final class Builder {
        private String candidateId = "";
        private CandidateSourceKind sourceKind = CandidateSourceKind.VISIBLE_SHARED_FILE;
        private String sourceUriOrPath = "";
        private String extractionMethod = "";
        private String originalContainer = "";
        private long byteLength;
        private String sha256 = "";
        private String perceptualHash = "";
        private String mimeDetected = "";
        private String decodeStatus = "";
        private int width;
        private int height;
        private boolean exactGroundTruthMatch;
        private boolean derivativeMatch;
        private long extractionOffsetStart = -1L;
        private long extractionOffsetEnd = -1L;
        private long readBytes;
        private long elapsedMs;
        private String errorCode = "";
        private CandidateLabel label = CandidateLabel.UNVERIFIED;
        private ResultGrade grade = ResultGrade.NOT_FOUND;

        public Builder candidateId(String value) {
            candidateId = value;
            return this;
        }

        public Builder sourceKind(CandidateSourceKind value) {
            sourceKind = value;
            return this;
        }

        public Builder sourceUriOrPath(String value) {
            sourceUriOrPath = value;
            return this;
        }

        public Builder extractionMethod(String value) {
            extractionMethod = value;
            return this;
        }

        public Builder originalContainer(String value) {
            originalContainer = value;
            return this;
        }

        public Builder byteLength(long value) {
            byteLength = value;
            return this;
        }

        public Builder sha256(String value) {
            sha256 = value;
            return this;
        }

        public Builder perceptualHash(String value) {
            perceptualHash = value;
            return this;
        }

        public Builder mimeDetected(String value) {
            mimeDetected = value;
            return this;
        }

        public Builder decodeStatus(String value) {
            decodeStatus = value;
            return this;
        }

        public Builder width(int value) {
            width = value;
            return this;
        }

        public Builder height(int value) {
            height = value;
            return this;
        }

        public Builder exactGroundTruthMatch(boolean value) {
            exactGroundTruthMatch = value;
            return this;
        }

        public Builder derivativeMatch(boolean value) {
            derivativeMatch = value;
            return this;
        }

        public Builder extractionOffsetStart(long value) {
            extractionOffsetStart = value;
            return this;
        }

        public Builder extractionOffsetEnd(long value) {
            extractionOffsetEnd = value;
            return this;
        }

        public Builder readBytes(long value) {
            readBytes = value;
            return this;
        }

        public Builder elapsedMs(long value) {
            elapsedMs = value;
            return this;
        }

        public Builder errorCode(String value) {
            errorCode = value;
            return this;
        }

        public Builder label(CandidateLabel value) {
            label = value;
            return this;
        }

        public Builder grade(ResultGrade value) {
            grade = value;
            return this;
        }

        public RecoveryCandidate build() {
            return new RecoveryCandidate(this);
        }
    }
}
