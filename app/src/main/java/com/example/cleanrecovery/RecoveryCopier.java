package com.example.cleanrecovery;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.util.Locale;

public final class RecoveryCopier {
    private RecoveryCopier() {
    }

    public static File copyToRecoveryDirectory(Context context, RecoveryItem item) throws IOException {
        File outputDirectory = PathManager.recoveredDirFor(item.type);
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            throw new IOException("Cannot create output directory: " + outputDirectory.getAbsolutePath());
        }

        File destination = uniqueDestination(outputDirectory, item);
        copySource(context, item, destination);
        context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(destination)));
        return destination;
    }

    static void copySource(Context context, RecoveryItem item, File destination) throws IOException {
        String path = item.path;
        if (path != null && path.startsWith("content://")) {
            copyContentUri(context, Uri.parse(path), destination);
            return;
        }
        CarvedSource carved = parseCarvedPath(path);
        if (carved != null) {
            copyFileSlice(carved.file, carved.offset, item.size, destination);
            return;
        }
        File source = item.asFile();
        try (FileChannel input = new FileInputStream(source).getChannel();
             FileChannel output = new FileOutputStream(destination).getChannel()) {
            input.transferTo(0L, input.size(), output);
        }
    }

    private static void copyContentUri(Context context, Uri uri, File destination) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) {
                throw new IOException("Cannot open content URI: " + uri);
            }
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
        }
    }

    private static void copyFileSlice(File source, long offset, long length, File destination) throws IOException {
        try (FileInputStream inputStream = new FileInputStream(source);
             FileOutputStream outputStream = new FileOutputStream(destination)) {
            long skipped = inputStream.skip(offset);
            if (skipped < offset) {
                throw new IOException("Cannot seek carved offset in " + source.getAbsolutePath());
            }
            long remaining = length;
            byte[] buffer = new byte[8192];
            while (remaining > 0L) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = inputStream.read(buffer, 0, toRead);
                if (read < 0) {
                    break;
                }
                outputStream.write(buffer, 0, read);
                remaining -= read;
            }
        }
    }

    static CarvedSource parseCarvedPath(String path) {
        if (path == null) {
            return null;
        }
        int hashIndex = path.lastIndexOf('#');
        if (hashIndex <= 0 || hashIndex >= path.length() - 1) {
            return null;
        }
        String suffix = path.substring(hashIndex + 1);
        if (!suffix.matches("\\d+")) {
            return null;
        }
        return new CarvedSource(new File(path.substring(0, hashIndex)), Long.parseLong(suffix));
    }

    static final class CarvedSource {
        final File file;
        final long offset;

        CarvedSource(File file, long offset) {
            this.file = file;
            this.offset = offset;
        }
    }

    static File uniqueDestination(File directory, RecoveryItem item) {
        String safeName = buildRecoveryName(item);
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

    static String buildRecoveryName(RecoveryItem item) {
        String name = item.name;
        if (name == null || name.trim().isEmpty()) {
            name = "recovered_file";
        }
        if (name.contains(".") && name.lastIndexOf('.') < name.length() - 1) {
            return name;
        }
        // Name has no extension — infer from item type
        String ext = extensionForType(item.type);
        if (ext.isEmpty()) return name;
        return name + "." + ext;
    }

    static String extensionForType(RecoveryType type) {
        if (type == RecoveryType.IMAGE) return "jpg";
        if (type == RecoveryType.VIDEO) return "mp4";
        if (type == RecoveryType.AUDIO) return "mp3";
        if (type == RecoveryType.DOCUMENT) return "pdf";
        return "";
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
