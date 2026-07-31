package com.example.cleanrecovery.ui.widget;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.recovery.RecoveryOutputPaths;
import com.example.cleanrecovery.ui.activity.AboutActivity;
import com.example.cleanrecovery.ui.activity.FileBrowserActivity;
import com.example.cleanrecovery.ui.activity.MainActivity;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Shared bottom-nav wiring so MainActivity and FileBrowserActivity stay consistent.
 */
public final class AppBottomNavBinder {
    public enum Tab {
        HOME,
        SCAN,
        FOLDER,
        SETTINGS
    }

    private AppBottomNavBinder() {
    }

    public static void bind(final Activity activity, Tab activeTab) {
        View root = activity.findViewById(R.id.bottom_nav);
        if (root == null) {
            return;
        }
        root.setVisibility(View.VISIBLE);

        View home = activity.findViewById(R.id.nav_home);
        View scan = activity.findViewById(R.id.nav_results);
        View folder = activity.findViewById(R.id.nav_folder);
        View settings = activity.findViewById(R.id.nav_about);
        ImageView homeIcon = activity.findViewById(R.id.nav_home_icon);
        ImageView scanIcon = activity.findViewById(R.id.nav_results_icon);
        ImageView folderIcon = activity.findViewById(R.id.nav_folder_icon);
        ImageView settingsIcon = activity.findViewById(R.id.nav_about_icon);
        TextView homeLabel = activity.findViewById(R.id.nav_home_label);
        TextView scanLabel = activity.findViewById(R.id.nav_results_label);
        TextView folderLabel = activity.findViewById(R.id.nav_folder_label);
        TextView settingsLabel = activity.findViewById(R.id.nav_about_label);

        int activeColor = activity.getResources().getColor(R.color.brand_primary, activity.getTheme());
        int inactiveColor = activity.getResources().getColor(R.color.text_secondary, activity.getTheme());

        style(home, homeIcon, homeLabel, activeTab == Tab.HOME, activeColor, inactiveColor);
        style(scan, scanIcon, scanLabel, activeTab == Tab.SCAN, activeColor, inactiveColor);
        style(folder, folderIcon, folderLabel, activeTab == Tab.FOLDER, activeColor, inactiveColor);
        style(settings, settingsIcon, settingsLabel, activeTab == Tab.SETTINGS, activeColor, inactiveColor);

        home.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openMain(activity, MainNavAction.HOME);
            }
        });
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Bottom "Scan" shows last results only; start scan only from home CTAs.
                openMain(activity, MainNavAction.RESULTS);
            }
        });
        folder.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activity instanceof FileBrowserActivity) {
                    return;
                }
                FileBrowserActivity.open(activity, RecoveryOutputPaths.primaryDataRecoveryDir());
            }
        });
        settings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                activity.startActivity(new Intent(activity, AboutActivity.class));
            }
        });
    }

    private enum MainNavAction {
        HOME,
        RESULTS
    }

    private static void openMain(Activity activity, MainNavAction action) {
        if (activity instanceof MainActivity) {
            MainActivity main = (MainActivity) activity;
            if (action == MainNavAction.RESULTS) {
                main.requestShowResultsFromNav();
            } else {
                main.requestShowHomeFromNav();
            }
            return;
        }
        Intent intent = new Intent(activity, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (action == MainNavAction.RESULTS) {
            intent.putExtra(MainActivity.EXTRA_SHOW_RESULTS, true);
        } else {
            intent.putExtra(MainActivity.EXTRA_SHOW_HOME, true);
        }
        activity.startActivity(intent);
        activity.finish();
    }

    private static void style(
            View container,
            ImageView icon,
            TextView label,
            boolean active,
            int activeColor,
            int inactiveColor
    ) {
        int color = active ? activeColor : inactiveColor;
        container.setBackgroundResource(active ? R.drawable.bg_nav_item_active : android.R.color.transparent);
        icon.setColorFilter(color);
        label.setTextColor(color);
    }
}
