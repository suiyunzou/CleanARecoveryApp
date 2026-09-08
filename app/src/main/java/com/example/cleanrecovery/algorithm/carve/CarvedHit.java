package com.example.cleanrecovery.algorithm.carve;

import com.example.cleanrecovery.recovery.RecoveryType;

/**
 * One signature-carved span inside a byte window.
 */
public final class CarvedHit {
    public final int start;
    public final int end;
    public final RecoveryType type;
    public final String mime;
    public final String extension;
    public final String sha256;
    public final String decodeStatus;

    public CarvedHit(
            int start,
            int end,
            RecoveryType type,
            String mime,
            String extension,
            String sha256,
            String decodeStatus
    ) {
        this.start = start;
        this.end = end;
        this.type = type;
        this.mime = mime;
        this.extension = extension;
        this.sha256 = sha256;
        this.decodeStatus = decodeStatus;
    }

    public int length() {
        return end - start;
    }
}
