package com.example.cleanrecovery.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import com.example.cleanrecovery.R;
import com.example.cleanrecovery.ui.widget.GlassToast;
import com.example.cleanrecovery.ui.widget.SystemUiHelper;
import com.example.cleanrecovery.update.GitHubUpdates;

/** App identity, feature introduction and browser-based release access. */
public final class AppAboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_app_about);
        findViewById(R.id.app_about_back).setOnClickListener(v -> finish());
        ((ImageView) findViewById(R.id.app_about_icon))
                .setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            ((TextView) findViewById(R.id.app_about_version))
                    .setText(getString(R.string.about_version, version));
        } catch (PackageManager.NameNotFoundException ignored) {
            // The running application is always installed.
        }
        findViewById(R.id.app_about_features).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(R.string.app_about_features)
                        .setMessage(R.string.app_about_features_body)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
        findViewById(R.id.app_about_updates).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GitHubUpdates.RELEASES));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                GlassToast.makeText(this, R.string.app_about_no_browser, GlassToast.LENGTH_SHORT).show();
            }
        });
    }
}
