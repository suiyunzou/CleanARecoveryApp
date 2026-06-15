package com.example.cleanrecovery;

public enum JunkRisk {
    SAFE(R.string.junk_risk_safe, true),
    REVIEW(R.string.junk_risk_review, false),
    HIGH(R.string.junk_risk_high, false);

    public final int labelResId;
    public final boolean selectedByDefault;

    JunkRisk(int labelResId, boolean selectedByDefault) {
        this.labelResId = labelResId;
        this.selectedByDefault = selectedByDefault;
    }
}
