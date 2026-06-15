package com.example.cleanrecovery.experiment.jpeg;

public final class JpegCarveLimits {
    public static final long MAX_CONTAINER_BYTES = 512L * 1024L * 1024L;
    public static final int MAX_CANDIDATES_PER_CONTAINER = 500;
    public static final long MAX_CANDIDATE_BYTES = 100L * 1024L * 1024L;
    public static final int READ_CHUNK_BYTES = 64 * 1024;

    private JpegCarveLimits() {
    }
}
