package com.example.cleanrecovery.experiment.cache;

public final class CacheProfile {
    public final String profileId;
    public final String vendor;
    public final String androidRange;
    public final String[] pathPatterns;
    public final String containerType;
    public final String parserId;
    public final String evidenceReference;

    public CacheProfile(
            String profileId,
            String vendor,
            String androidRange,
            String[] pathPatterns,
            String containerType,
            String parserId,
            String evidenceReference
    ) {
        this.profileId = profileId;
        this.vendor = vendor;
        this.androidRange = androidRange;
        this.pathPatterns = pathPatterns;
        this.containerType = containerType;
        this.parserId = parserId;
        this.evidenceReference = evidenceReference;
    }
}
