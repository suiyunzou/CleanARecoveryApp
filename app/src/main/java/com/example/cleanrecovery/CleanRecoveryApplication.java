package com.example.cleanrecovery;

import android.app.Application;
import com.example.cleanrecovery.ui.widget.GlassToast;

public final class CleanRecoveryApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        GlassToast.initialize(this);
        com.example.cleanrecovery.update.AutomaticUpdateChecks.initialize(this);
    }
}
