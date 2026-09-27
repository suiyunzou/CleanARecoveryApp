package com.example.cleanrecovery.music.ui;

/** Separate test APK/UID: proves Android 12+ cross-app overlay touch routing. */
public final class OverlayTouchTargetActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        android.widget.TextView target = new android.widget.TextView(this);
        target.setGravity(android.view.Gravity.CENTER);
        target.setTextSize(24);
        target.setText("Cross-app touch target");
        target.setBackgroundColor(0xFFF0F4F2);
        target.setOnClickListener(view -> target.setText("Cross-app touch received"));
        setContentView(target);
    }
}
