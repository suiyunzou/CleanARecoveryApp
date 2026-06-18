package com.example.cleanrecovery;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;

/**
 * @deprecated 改用 {@link PathManager} 统一管理路径。本类保留作为兼容入口，
 *             内部委托给 PathManager，避免破坏既有调用方。
 */
@Deprecated
public final class RecoveryOutputPaths {
    private RecoveryOutputPaths() {
    }

    public static File primaryDataRecoveryDir() {
        return PathManager.recoveredRoot();
    }

    public static String primaryDisplayPath() {
        return primaryDataRecoveryDir().getAbsolutePath();
    }

    public static boolean openPrimaryFolder(Context context) {
        File directory = primaryDataRecoveryDir();
        if (!directory.exists()) {
            //noinspection ResultOfMethodCallIgnored
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
