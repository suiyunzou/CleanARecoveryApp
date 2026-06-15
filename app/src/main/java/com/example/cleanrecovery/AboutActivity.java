package com.example.cleanrecovery;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class AboutActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SystemUiHelper.apply(this);
        setContentView(R.layout.activity_about);

        ImageButton backButton = findViewById(R.id.about_back_button);
        backButton.setOnClickListener(view -> finish());

        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = info.versionName != null ? info.versionName : "0.1.0";
            ((TextView) findViewById(R.id.about_version)).setText(
                    getString(R.string.about_version, version));
        } catch (PackageManager.NameNotFoundException ignored) {
        }

        ImageButton shareButton = findViewById(R.id.about_share_button);
        shareButton.setOnClickListener(view -> shareApk());
    }

    private void shareApk() {
        File apkFile = copyApkToCache();
        if (apkFile == null) return;

        Uri uri = FileProvider.getUriForFile(
                this, getPackageName() + ".fileprovider", apkFile);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/vnd.android.package-archive");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, getString(R.string.about_share)));
    }

    private File copyApkToCache() {
        File cacheDir = new File(getCacheDir(), "share");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File outFile = new File(cacheDir, "QingxunRecovery.apk");

        try {
            InputStream in = new FileInputStream(getApplicationInfo().sourceDir);
            try {
                OutputStream out = new FileOutputStream(outFile);
                try {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            return outFile;
        } catch (IOException e) {
            return null;
        }
    }
}
