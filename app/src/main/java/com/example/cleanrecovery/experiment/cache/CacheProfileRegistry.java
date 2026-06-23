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
                ),
                new CacheProfile(
                        "huawei_gallery_cache",
                        "Huawei/Honor",
                        "10-14",
                        new String[]{"/com.huawei.photos/cache/", "/huawei/gallery/cache/", "/magazineunlock/"},
                        "FILE",
                        "file_reader",
                        "plan-013-huawei-gallery-cache"
                ),
                new CacheProfile(
                        "vivo_gallery_cache",
                        "Vivo/Funtouch",
                        "10-14",
                        new String[]{"/vivo/gallery/cache/", "/com.vivo.gallery/cache/"},
                        "FILE",
                        "file_reader",
                        "plan-013-vivo-gallery-cache"
                ),
                new CacheProfile(
                        "realme_gallery_cache",
                        "Realme/ColorOS",
                        "10-14",
                        new String[]{"/com.realme.gallery/cache/", "/realme/gallery/cache/"},
                        "FILE",
                        "file_reader",
                        "plan-013-realme-gallery-cache"
                ),
                new CacheProfile(
                        "oneplus_gallery_cache",
                        "OnePlus/OxygenOS",
                        "10-14",
                        new String[]{"/oneplus/gallery/cache/", "/com.oneplus.gallery/cache/"},
                        "FILE",
                        "file_reader",
                        "plan-013-oneplus-gallery-cache"
                ),
                new CacheProfile(
                        "miui_gallery_cloud_cache",
                        "Xiaomi/MIUI",
                        "11-14",
                        new String[]{"/miui/gallery/cloud/.cache/", "/miui/gallery/.cache/"},
                        "FILE",
                        "file_reader",
                        "plan-013-miui-gallery-cloud"
                ),
                new CacheProfile(
                        "dcim_hidden_cache",
                        "generic",
                        "10-14",
                        new String[]{"/dcim/.cache/", "/pictures/.cache/", "/pictures/screenshots/.cache/"},
                        "FILE",
                        "file_reader",
                        "plan-013-dcim-hidden-cache"
                ),
                new CacheProfile(
                        "samsung_myfiles_temp",
                        "Samsung",
                        "10-14",
                        new String[]{"/com.samsung.android.app.myfiles/.tmp/"},
                        "FILE",
                        "file_reader",
                        "plan-013-samsung-myfiles"
                ),
                new CacheProfile(
                        "oppo_coloros_photos_cache",
                        "Oppo/ColorOS",
                        "12-14",
                        new String[]{"/coloros/gallery/cache/", "/com.coloros.gallery3d/cache/"},
                        "FILE",
                        "file_reader",
                        "plan-013-oppo-photos-cache"
                )
        ));
        return Collections.unmodifiableList(profiles);
    }
}
