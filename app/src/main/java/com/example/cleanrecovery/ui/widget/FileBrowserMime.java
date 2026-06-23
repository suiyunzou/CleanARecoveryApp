package com.example.cleanrecovery.ui.widget;

import com.example.cleanrecovery.recovery.RecoveryType;

import android.webkit.MimeTypeMap;

import com.example.cleanrecovery.algorithm.FileSignatureProbe;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public final class FileBrowserMime {
    public enum Kind {
        DIRECTORY,
        VIDEO,
        IMAGE,
        AUDIO,
        TEXT,
        PDF,
        ZIP,
        APK,
        DOC,
        XLS,
        PPT,
        UNKNOWN
    }

    private FileBrowserMime() {
    }

    public static Kind kindFor(String fileName, boolean directory) {
        return kindFor(null, fileName, directory);
    }

    public static Kind kindFor(File file, String fileName, boolean directory) {
        if (directory) {
            return Kind.DIRECTORY;
        }
        Kind byExtension = kindForExtension(extensionOf(fileName));
        if (byExtension != Kind.UNKNOWN) {
            return byExtension;
        }
        if (file != null && file.isFile()) {
            Kind sniffed = kindForSniff(file);
            if (sniffed != Kind.UNKNOWN) {
                return sniffed;
            }
        }
        return Kind.UNKNOWN;
    }

    public static Kind kindForExtension(String extension) {
        if (extension == null || extension.isEmpty()) {
            return Kind.UNKNOWN;
        }
        if ("apk".equals(extension)) {
            return Kind.APK;
        }
        if (isPdfExtension(extension)) {
            return Kind.PDF;
        }
        if (isZipExtension(extension)) {
            return Kind.ZIP;
        }
        if (isDocExtension(extension)) {
            return Kind.DOC;
        }
        if (isXlsExtension(extension)) {
            return Kind.XLS;
        }
        if (isPptExtension(extension)) {
            return Kind.PPT;
        }
        if (isVideoExtension(extension)) {
            return Kind.VIDEO;
        }
        if (isImageExtension(extension)) {
            return Kind.IMAGE;
        }
        if (isAudioExtension(extension)) {
            return Kind.AUDIO;
        }
        if (isTextExtension(extension)) {
            return Kind.TEXT;
        }
        return Kind.UNKNOWN;
    }

    private static Kind kindForSniff(File file) {
        try {
            FileSignatureProbe.ProbeResult result = FileSignatureProbe.probe(file);
            if (result == null) {
                return Kind.UNKNOWN;
            }
            switch (result.type) {
                case VIDEO:
                    return Kind.VIDEO;
                case IMAGE:
                    return Kind.IMAGE;
                case AUDIO:
                    return Kind.AUDIO;
                case DOCUMENT:
                    if ("application/pdf".equals(result.mimeDetected)) {
                        return Kind.PDF;
                    }
                    if ("application/zip".equals(result.mimeDetected)) {
                        return Kind.ZIP;
                    }
                    return Kind.UNKNOWN;
                default:
                    return Kind.UNKNOWN;
            }
        } catch (IOException ignored) {
            return Kind.UNKNOWN;
        }
    }

    public static RecoveryType recoveryTypeFor(Kind kind) {
        switch (kind) {
            case VIDEO:
                return RecoveryType.VIDEO;
            case IMAGE:
                return RecoveryType.IMAGE;
            case AUDIO:
                return RecoveryType.AUDIO;
            case TEXT:
            case PDF:
            case ZIP:
            case APK:
            case DOC:
            case XLS:
            case PPT:
            case UNKNOWN:
            default:
                return RecoveryType.DOCUMENT;
        }
    }

    public static String mimeTypeFor(String fileName) {
        return mimeTypeFor(null, fileName);
    }

    public static String mimeTypeFor(File file, String fileName) {
        Kind kind = kindFor(file, fileName, false);
        switch (kind) {
            case APK:
                return "application/vnd.android.package-archive";
            case PDF:
                return "application/pdf";
            case ZIP:
                return "application/zip";
            case DOC:
                return docMimeType(extensionOf(fileName));
            case XLS:
                return xlsMimeType(extensionOf(fileName));
            case PPT:
                return pptMimeType(extensionOf(fileName));
            case TEXT:
                return "text/plain";
            case VIDEO:
                return videoMimeType(extensionOf(fileName));
            case IMAGE:
                return imageMimeType(extensionOf(fileName));
            case AUDIO:
                return audioMimeType(extensionOf(fileName));
            default:
                break;
        }
        String extension = extensionOf(fileName);
        if (extension.isEmpty()) {
            return "*/*";
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime != null && !mime.isEmpty() ? mime : "*/*";
    }

    public static String displayTypeLabel(Kind kind) {
        switch (kind) {
            case DIRECTORY:
                return "Folder";
            case VIDEO:
                return "Video";
            case IMAGE:
                return "Image";
            case AUDIO:
                return "Audio";
            case TEXT:
                return "Text";
            case PDF:
                return "PDF";
            case ZIP:
                return "Archive";
            case APK:
                return "APK";
            case DOC:
                return "Document";
            case XLS:
                return "Spreadsheet";
            case PPT:
                return "Presentation";
            case UNKNOWN:
            default:
                return "File";
        }
    }

    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.trim();
        if (trimmed.isEmpty() || trimmed.equals(".") || trimmed.equals("..")) {
            return false;
        }
        return trimmed.indexOf('/') < 0 && trimmed.indexOf('\\') < 0;
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.US);
    }

    private static boolean isVideoExtension(String extension) {
        return matches(extension, "mp4", "mkv", "avi", "mov", "webm", "3gp", "m4v", "ts", "flv", "wmv");
    }

    private static boolean isImageExtension(String extension) {
        return matches(extension, "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif");
    }

    private static boolean isAudioExtension(String extension) {
        return matches(extension, "mp3", "wav", "flac", "aac", "m4a", "ogg", "opus", "amr");
    }

    private static boolean isTextExtension(String extension) {
        return matches(extension, "txt", "log", "md", "csv", "json", "xml", "html", "htm", "java", "kt",
                "properties", "ini", "cfg", "yaml", "yml", "conf");
    }

    private static boolean isPdfExtension(String extension) {
        return matches(extension, "pdf");
    }

    private static boolean isZipExtension(String extension) {
        return matches(extension, "zip", "rar", "7z", "tar", "gz", "bz2");
    }

    private static boolean isDocExtension(String extension) {
        return matches(extension, "doc", "docx", "rtf", "odt");
    }

    private static boolean isXlsExtension(String extension) {
        return matches(extension, "xls", "xlsx", "ods", "csv");
    }

    private static boolean isPptExtension(String extension) {
        return matches(extension, "ppt", "pptx", "odp");
    }

    private static String videoMimeType(String extension) {
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime != null && !mime.isEmpty() ? mime : "video/*";
    }

    private static String imageMimeType(String extension) {
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime != null && !mime.isEmpty() ? mime : "image/*";
    }

    private static String audioMimeType(String extension) {
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mime != null && !mime.isEmpty() ? mime : "audio/*";
    }

    private static String docMimeType(String extension) {
        if ("docx".equals(extension)) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        }
        if ("doc".equals(extension)) {
            return "application/msword";
        }
        return "application/msword";
    }

    private static String xlsMimeType(String extension) {
        if ("xlsx".equals(extension)) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        }
        if ("xls".equals(extension)) {
            return "application/vnd.ms-excel";
        }
        return "application/vnd.ms-excel";
    }

    private static String pptMimeType(String extension) {
        if ("pptx".equals(extension)) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        }
        if ("ppt".equals(extension)) {
            return "application/vnd.ms-powerpoint";
        }
        return "application/vnd.ms-powerpoint";
    }

    private static boolean matches(String extension, String... candidates) {
        for (String candidate : candidates) {
            if (candidate.equals(extension)) {
                return true;
            }
        }
        return false;
    }
}
