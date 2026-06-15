package com.example.cleanrecovery;

import android.content.Context;

import java.io.File;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public final class JunkItem {
    public final JunkType type;
    public final JunkRisk risk;
    public final String name;
    public final String path;
    public final long size;
    public final long modifiedAt;
    public final String reason;
    public final boolean directory;
    public boolean selected;

    public JunkItem(
            JunkType type,
            JunkRisk risk,
            String name,
            String path,
            long size,
            long modifiedAt,
            String reason,
            boolean directory
    ) {
        this.type = type;
        this.risk = risk;
        this.name = name;
        this.path = path;
        this.size = size;
        this.modifiedAt = modifiedAt;
        this.reason = reason;
        this.directory = directory;
        this.selected = risk.selectedByDefault;
    }

    public File asFile() {
        return new File(path);
    }

    public String subtitle(Context context) {
        StringBuilder builder = new StringBuilder();
        builder.append(context.getString(type.labelResId));
        builder.append(" | ");
        builder.append(context.getString(risk.labelResId));
        builder.append(" | ");
        builder.append(formatSize(size));
        if (modifiedAt > 0) {
            builder.append(" | ").append(DateFormat.getDateTimeInstance().format(new Date(modifiedAt)));
        }
        builder.append("\n").append(reason);
        builder.append("\n").append(path);
        return builder.toString();
    }

    public static String formatSize(long bytes) {
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
