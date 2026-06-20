package com.example.cleanrecovery.recovery;

import com.example.cleanrecovery.R;

import android.content.Context;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public final class RecoveryItem {
    public final RecoveryType type;
    public final String name;
    public final String path;
    public final long size;
    public final long modifiedAt;
    public final int width;
    public final int height;
    public final boolean suspectedDeleted;
    public final RecoverySourceKind sourceKind;
    public boolean selected;

    public RecoveryItem(
            RecoveryType type,
            String name,
            String path,
            long size,
            long modifiedAt,
            int width,
            int height,
            boolean suspectedDeleted
    ) {
        this(type, name, path, size, modifiedAt, width, height, suspectedDeleted, RecoverySourceKind.VISIBLE_SHARED_FILE);
    }

    public RecoveryItem(
            RecoveryType type,
            String name,
            String path,
            long size,
            long modifiedAt,
            int width,
            int height,
            boolean suspectedDeleted,
            RecoverySourceKind sourceKind
    ) {
        this.type = type;
        this.name = name;
        this.path = path;
        this.size = size;
        this.modifiedAt = modifiedAt;
        this.width = width;
        this.height = height;
        this.suspectedDeleted = suspectedDeleted;
        this.sourceKind = sourceKind == null ? RecoverySourceKind.VISIBLE_SHARED_FILE : sourceKind;
    }

    public File asFile() {
        return new File(path);
    }

    public String subtitle(Context context) {
        StringBuilder builder = new StringBuilder();
        builder.append(context.getString(suspectedDeleted ? R.string.status_deleted : R.string.status_existing));
        builder.append(" | ");
        builder.append(formatSize(size));
        if (width > 0 && height > 0) {
            builder.append(" | ").append(width).append("x").append(height);
        }
        if (modifiedAt > 0) {
            builder.append(" | ").append(DateFormat.getDateTimeInstance().format(new Date(modifiedAt)));
        }
        builder.append("\n").append(path);
        return builder.toString();
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int index = -1;
        do {
            value = value / 1024.0d;
            index++;
        } while (value >= 1024.0d && index < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[index]);
    }
}
