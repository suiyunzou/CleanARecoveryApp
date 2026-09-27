package com.example.cleanrecovery.music.ui;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;
import com.example.cleanrecovery.ui.activity.OnboardingActivity;
import com.example.cleanrecovery.ui.activity.PermissionSetupActivity;
import com.example.cleanrecovery.scan.ScanHistoryStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PermissionSetupUiTest {
    @Rule public GrantPermissionRule notifications = GrantPermissionRule.grant(android.Manifest.permission.POST_NOTIFICATIONS);
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

    @Test public void selectedTourReturnsFromDeniedOverlayWithoutLoopAndCanBeSkipped() throws Exception {
        Context context = instrumentation.getTargetContext();
        assertFalse("Run this test with the original ungranted overlay permission", Settings.canDrawOverlays(context));
        SharedPreferences history = context.getSharedPreferences("clean_recovery_scan_history", Context.MODE_PRIVATE);
        boolean hadKey = history.contains("onboarding_done"), done = history.getBoolean("onboarding_done", false);
        boolean hadVersion = history.contains("permission_setup_version");
        int previousVersion = history.getInt("permission_setup_version", 0);
        // Model an existing installation that completed the OLD introduction.
        history.edit().putBoolean("onboarding_done", true).remove("permission_setup_version").commit();
        assertFalse("Legacy onboarding must not skip the new permission tour", ScanHistoryStore.isOnboardingComplete(context));
        Instrumentation.ActivityMonitor entry = instrumentation.addMonitor(OnboardingActivity.class.getName(), null, false);
        android.app.Activity launcher = instrumentation.startActivitySync(new Intent(context,
                com.example.cleanrecovery.ui.activity.MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
        OnboardingActivity activity = (OnboardingActivity) entry.waitForActivityWithTimeout(5000);
        instrumentation.removeMonitor(entry);
        assertNotNull("Opening the app must show the permission tour after upgrade", activity);
        OnboardingActivity[] current = {activity};
        SystemClock.sleep(500);
        java.io.File folder = new java.io.File(context.getExternalFilesDir(null), "navigation-test"); folder.mkdirs();
        android.graphics.Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(folder, "permission-sheet.png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } bitmap.recycle();
        try {
            main(() -> {
                boolean[] choices = (boolean[]) field(activity, "selected");
                assertTrue(choices[0] && choices[1] && choices[2]);
                assertFalse(choices[3] || choices[4] || choices[5] || choices[6]);
                choices[1] = false; // Do not change the user's storage access in this test.
                return null;
            });
            android.app.Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(OnboardingActivity.class.getName(), null, false);
            main(() -> { activity.recreate(); return null; });
            OnboardingActivity recreated = (OnboardingActivity) monitor.waitForActivityWithTimeout(4000);
            instrumentation.removeMonitor(monitor);
            assertNotNull(recreated);
            current[0] = recreated;
            main(() -> {
                assertFalse(((boolean[]) field(recreated, "selected"))[1]);
                ((TextView) field(recreated, "action")).performClick(); return null;
            });
            for (int i = 0; i < 50 && !settingsVisible(); i++) SystemClock.sleep(100);
            assertTrue("The selected special permission must open its system page", settingsVisible());
            SystemClock.sleep(400);
            assertFalse(ScanHistoryStore.isOnboardingComplete(context));
            for (int back = 0; back < 3 && settingsVisible(); back++) {
                assertTrue(instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
                SystemClock.sleep(500);
            }
            for (int i = 0; i < 40 && !(boolean) main(() -> field(recreated, "completed")); i++) SystemClock.sleep(100);
            android.view.accessibility.AccessibilityNodeInfo debugRoot = instrumentation.getUiAutomation().getRootInActiveWindow();
            assertTrue("stage=" + main(() -> field(recreated, "stage")) + ", busy=" + main(() -> field(recreated, "busy"))
                    + ", destroyed=" + recreated.isDestroyed() + ", root=" + (debugRoot == null ? "null" : debugRoot.getPackageName()),
                    main(() -> (boolean) field(recreated, "completed")));
            assertFalse(Settings.canDrawOverlays(context));
            SystemClock.sleep(500);
            main(() -> { ((TextView) field(recreated, "action")).performClick(); return null; });
            instrumentation.waitForIdleSync();
            SystemClock.sleep(500);
            assertTrue(ScanHistoryStore.isOnboardingComplete(context));
            history.edit().remove("onboarding_done").commit();
            OnboardingActivity skip = launch(context);
            current[0] = skip;
            main(() -> { ((TextView) field(skip, "skip")).performClick(); return null; });
            assertTrue(ScanHistoryStore.isOnboardingComplete(context));
            assertFalse(Settings.canDrawOverlays(context));
        } finally {
            main(() -> { if (!current[0].isDestroyed()) current[0].finish(); return null; });
            SharedPreferences.Editor edit = history.edit();
            if (hadKey) edit.putBoolean("onboarding_done", done); else edit.remove("onboarding_done");
            if (hadVersion) edit.putInt("permission_setup_version", previousVersion); else edit.remove("permission_setup_version");
            edit.commit();
            main(() -> { launcher.finish(); return null; });
        }
    }
    private boolean settingsVisible() {
        android.view.accessibility.AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        return root != null && "com.android.settings".contentEquals(root.getPackageName());
    }
    private OnboardingActivity launch(Context context) {
        return (OnboardingActivity) instrumentation.startActivitySync(new Intent(context, OnboardingActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK));
    }
    private static Object field(Object object, String name) throws Exception {
        Field field = PermissionSetupActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private <T> T main(Callable<T> callable) throws Exception {
        FutureTask<T> task = new FutureTask<>(callable); instrumentation.runOnMainSync(task); return task.get();
    }
}
