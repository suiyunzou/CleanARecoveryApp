package com.example.cleanrecovery;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Locale;

public final class RecoveryCopier {
    private RecoveryCopier() {
    }

    public static File copyToRecoveryDirectory(Context context, RecoveryItem item) throws IOException {
        File outputDirectory = new File(
                Environment.getExternalStoragePublicDirectory(item.type.publicDirectory),
                "DataRecovery"
        );
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Cannot create output directory: " + outputDirectory.getAbsolutePath());
        }

        File source = item.asFile();
        File destination = uniqueDestination(outputDirectory, source.getName());

        try (FileChannel input = new FileInputStream(source).getChannel();
             FileChannel output = new FileOutputStream(destination).getChannel()) {
            input.transferTo(0L, input.size(), output);
        }

        context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destination)));
        return destination;
    }

    private static File uniqueDestination(File directory, String originalName) {
        String safeName = originalName == null || originalName.trim().isEmpty() ? "recovered_file" : originalName;
        String baseName = stripExtension(safeName);
        String extension = extensionWithDot(safeName);
        File candidate = new File(directory, "Recovered_" + safeName);
        int counter = 1;
        while (candidate.exists()) {
            candidate = new File(directory, "Recovered_" + baseName + "_" + counter + extension);
            counter++;
        }
        return candidate;
    }

    private static String stripExtension(String name) {
        int index = name.lastIndexOf('.');
        if (index <= 0) {
            return name;
        }
        return name.substring(0, index);
    }

    private static String extensionWithDot(String name) {
        int index = name.lastIndexOf('.');
        if (index < 0 || index == name.length() - 1) {
            return "";
        }
        return name.substring(index).toLowerCase(Locale.US);
    }
}
