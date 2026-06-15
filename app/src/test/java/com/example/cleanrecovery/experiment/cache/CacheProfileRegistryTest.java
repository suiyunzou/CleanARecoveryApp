package com.example.cleanrecovery.experiment.cache;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public final class CacheProfileRegistryTest {
    @Test
    public void matchesGenericThumbnailsPath() {
        CacheProfile profile = CacheProfileRegistry.matchPath("/storage/emulated/0/DCIM/.thumbnails/123");
        assertNotNull(profile);
        assertEquals("generic_thumbnails", profile.profileId);
    }

    @Test
    public void matchesXiaomiPhotoBlob() {
        CacheProfile profile = CacheProfileRegistry.matchPath("/storage/emulated/0/MIUI/photo_blob_001");
        assertNotNull(profile);
        assertEquals("xiaomi_photo_blob", profile.profileId);
    }

    @Test
    public void returnsNullForUnrelatedPath() {
        assertNull(CacheProfileRegistry.matchPath("/storage/emulated/0/Download/report.pdf"));
    }

    @Test
    public void excludesOtherAppPrivateData() {
        assertNull(CacheProfileRegistry.matchPath("/storage/emulated/0/Android/data/com.other/.thumbnails/1"));
    }

    @Test
    public void prefersOemBlobOverGenericThumbnail() {
        CacheProfile profile = CacheProfileRegistry.matchPath("/storage/emulated/0/MIUI/photo_blob_cache");
        assertNotNull(profile);
        assertEquals("xiaomi_photo_blob", profile.profileId);
    }

    @Test
    public void matchesSamsungGalleryCache() {
        CacheProfile profile = CacheProfileRegistry.matchPath(
                "/storage/emulated/0/.cache/photos/thumb.jpg");
        assertNotNull(profile);
        assertEquals("samsung_gallery_cache", profile.profileId);
    }

    @Test
    public void matchesOppoGalleryCache() {
        CacheProfile profile = CacheProfileRegistry.matchPath(
                "/storage/emulated/0/Android/media/com.coloros.gallery3d/gallery/cache/item");
        assertNotNull(profile);
        assertEquals("oppo_gallery_cache", profile.profileId);
    }
}
