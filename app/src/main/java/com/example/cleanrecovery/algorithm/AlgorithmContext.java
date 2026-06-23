package com.example.cleanrecovery.algorithm;

import android.content.Context;

import com.example.cleanrecovery.recovery.RecoveryType;

public final class AlgorithmContext {
    public final Context context;
    public final RecoveryType type;

    public AlgorithmContext(Context context, RecoveryType type) {
        this.context = context == null ? null : context.getApplicationContext();
        this.type = type;
    }
}
