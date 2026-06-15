package com.example.cleanrecovery;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import java.io.File;

public final class RecoveryOutputPaths {
    private RecoveryOutputPaths() {
    }

    public static File primaryDataRecoveryDir() {
        return new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "DataRecovery"
        );
    }

    public static String primaryDisplayPath() {
        return primaryDataRecoveryDir().getAbsolutePath();
    }

    public static boolean openPrimaryFolder(Context context) {
        File directory = primaryDataRecoveryDir();
        if (!directory.exists()) {
            directory.mkdirs();
        }
        Uri uri = Uri.parse("file://" + directory.getAbsolutePath());
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "resource/folder");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception first) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setDataAndType(uri, "*/*");
            try {
                context.startActivity(fallback);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
