package com.example.cleanrecovery.algorithm;

public final class AlgorithmAvailability {
    public enum Status {
        RUNNABLE,
        DISABLED,
        REQUIRES_API,
        EVIDENCE_ONLY
    }

    private final Status status;
    private final int reasonResId;
    private final int minApi;

    private AlgorithmAvailability(Status status, int reasonResId, int minApi) {
        this.status = status;
        this.reasonResId = reasonResId;
        this.minApi = minApi;
    }

    public static AlgorithmAvailability runnable() {
        return new AlgorithmAvailability(Status.RUNNABLE, 0, 0);
    }

    public static AlgorithmAvailability disabled(int reasonResId) {
        return new AlgorithmAvailability(Status.DISABLED, reasonResId, 0);
    }

    public static AlgorithmAvailability requiresApi(int minApi) {
        return new AlgorithmAvailability(Status.REQUIRES_API, 0, minApi);
    }

    public static AlgorithmAvailability evidenceOnly(int reasonResId) {
        return new AlgorithmAvailability(Status.EVIDENCE_ONLY, reasonResId, 0);
    }

    public boolean isRunnable() {
        return status == Status.RUNNABLE;
    }

    public Status getStatus() {
        return status;
    }

    public int getReasonResId() {
        return reasonResId;
    }

    public int getMinApi() {
        return minApi;
    }
}
