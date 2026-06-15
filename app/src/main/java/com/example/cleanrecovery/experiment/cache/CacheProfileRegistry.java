package com.example.cleanrecovery.experiment.cache;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class CacheProfileRegistry {
    private static final List<CacheProfile> PROFILES = bootstrapProfilesInternal();

    private CacheProfileRegistry() {
    }

    public static List<CacheProfile> bootstrapProfiles() {
        return PROFILES;
    }

    public static boolean isExcludedPath(String absolutePath) {
        String normalized = normalize(absolutePath);
        return normalized.contains("/android/data/");
    }

    public static CacheProfile matchPath(String absolutePath) {
        if (absolutePath == null || absolutePath.isEmpty() || isExcludedPath(absolutePath)) {
            return null;
        }
        String normalized = normalize(absolutePath);
        for (CacheProfile profile : PROFILES) {
            for (String pattern : profile.pathPatterns) {
                if (matchesPattern(normalized, pattern)) {
                    return profile;
                }
            }
        }
        return null;
    }

    private static boolean matchesPattern(String normalizedPath, String pattern) {
        String normalizedPattern = normalize(pattern);
        if (normalizedPattern.startsWith("/")) {
            return normalizedPath.contains(normalizedPattern);
        }
        int index = 0;
        while ((index = normalizedPath.indexOf(normalizedPattern, index)) >= 0) {
            boolean startOk = index == 0 || normalizedPath.charAt(index - 1) == '/';
            if (startOk) {
                return true;
            }
            index++;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.US);
    }

    private static List<CacheProfile> bootstrapProfilesInternal() {
        ArrayList<CacheProfile> profiles = new ArrayList<>(Arrays.asList(
                new CacheProfile(
                        "xiaomi_photo_blob",
                        "Xiaomi/MIUI",
                        "11-14",
                        new String[]{"/photo_blob", "photo_blob"},
                        "BLOB",
                        "jpeg_blob_carver",
                        "plan-008A-xiaomi-photo-blob"
                ),
                new CacheProfile(
                        "verified_imgcache",
                        "multi",
                        "10-14",
                        new String[]{"/imgcache"},
                        "BLOB",
                        "jpeg_blob_carver",
                        "plan-008A-imgcache"
                ),
                new CacheProfile(
                        "samsung_gallery_cache",
                        "Samsung",
                        "10-14",
                        new String[]{"/.cache/photos/", "/com.sec.android.gallery3d/cache/"},
                        "FILE",
                        "file_reader",
                        "plan-010-samsung-gallery-cache"
                ),
                new CacheProfile(
                        "oppo_gallery_cache",
                        "Oppo/ColorOS",
                        "10-14",
                        new String[]{"/gallery/cache/", "/coloros/gallery/cache/"},
                        "FILE",
                        "file_reader",
                        "plan-010-oppo-gallery-cache"
                ),
                new CacheProfile(
                        "generic_thumbnails",
                        "generic",
                        "10-14",
                        new String[]{"/.thumbnails/", "/dcim/.thumbnails/", "/pictures/.thumbnails/"},
                        "FILE",
                        "file_reader",
                        "plan-008A-generic-thumbnails"
                ),
                new CacheProfile(
                        "android_media_public",
                        "generic",
                        "10-14",
                        new String[]{"/android/media/"},
                        "FILE",
                        "file_reader",
                        "plan-008A-android-media"
                )
        ));
        return Collections.unmodifiableList(profiles);
    }
}
