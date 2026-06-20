package com.example.cleanrecovery.ui.widget;

import com.example.cleanrecovery.R;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;

public final class SystemUiHelper {
    private SystemUiHelper() {
    }

    public static void apply(Activity activity) {
        Window window = activity.getWindow();
        int background = activity.getResources().getColor(R.color.background_app, activity.getTheme());
        int surface = activity.getResources().getColor(R.color.surface_card, activity.getTheme());
        window.setStatusBarColor(background);
        window.setNavigationBarColor(surface);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = window.getDecorView().getSystemUiVisibility();
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int flags = window.getDecorView().getSystemUiVisibility();
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }
}
